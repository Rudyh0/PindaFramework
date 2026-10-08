package main

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"strings"
)

const (
	maxExtractFiles = 200_000
	maxExtractBytes = 20 << 30
)

// archiveKind: welk soort archief dit is (aan de naam te zien), of "".
func archiveKind(name string) string {
	lower := strings.ToLower(name)
	switch {
	case strings.HasSuffix(lower, ".zip"):
		return "zip"
	case strings.HasSuffix(lower, ".tar.gz"), strings.HasSuffix(lower, ".tgz"):
		return "tar.gz"
	case strings.HasSuffix(lower, ".tar"):
		return "tar"
	}
	return ""
}

// zipDirectory leest uit het einde van een zip-bestand hoeveel onderdelen erin zitten en hoe
// groot de inhoudsopgave is, zonder die te laden. zip.NewReader houdt de hele inhoudsopgave in
// het geheugen; een kwaadaardig bestand kan daar gigabytes van maken.
func zipDirectory(reader io.ReaderAt, size int64) (entries, directory uint64, err error) {
	const eocdLength = 22
	if size < eocdLength {
		return 0, 0, errors.New("geen geldig zip-bestand")
	}
	tail := int64(eocdLength + 65535)
	if tail > size {
		tail = size
	}
	buf := make([]byte, tail)
	if _, err := reader.ReadAt(buf, size-tail); err != nil && !errors.Is(err, io.EOF) {
		return 0, 0, err
	}
	at := -1
	for i := len(buf) - eocdLength; i >= 0; i-- {
		if binary.LittleEndian.Uint32(buf[i:]) == 0x06054b50 {
			at = i
			break
		}
	}
	if at < 0 {
		return 0, 0, errors.New("geen geldig zip-bestand")
	}
	entries = uint64(binary.LittleEndian.Uint16(buf[at+10:]))
	directory = uint64(binary.LittleEndian.Uint32(buf[at+12:]))
	if entries != 0xffff && directory != 0xffffffff {
		return entries, directory, nil
	}
	// Zip64: de echte getallen staan in een eigen record (de "locator" staat er vlak voor).
	if at < 20 || binary.LittleEndian.Uint32(buf[at-20:]) != 0x07064b50 {
		return 0, 0, errors.New("ongeldig zip64-bestand")
	}
	offset := int64(binary.LittleEndian.Uint64(buf[at-20+8:]))
	record := make([]byte, 56)
	if offset < 0 || offset+56 > size {
		return 0, 0, errors.New("ongeldig zip64-bestand")
	}
	if _, err := reader.ReadAt(record, offset); err != nil {
		return 0, 0, err
	}
	if binary.LittleEndian.Uint32(record) != 0x06064b50 {
		return 0, 0, errors.New("ongeldig zip64-bestand")
	}
	return binary.LittleEndian.Uint64(record[32:]), binary.LittleEndian.Uint64(record[40:]), nil
}

// ExtractResult: wat er is uitgepakt.
type ExtractResult struct {
	Files   int   `json:"files"`
	Dirs    int   `json:"dirs"`
	Bytes   int64 `json:"bytes"`
	Skipped int   `json:"skipped"`
}

// Extract pakt een archief uit in dest. Paden met .., absolute paden, symlinks en apparaten
// worden overgeslagen; meer dan 20 GB (of meer dan er vrij is) of 200.000 bestanden stopt het.
// Bestaande bestanden worden overschreven.
func (fr FileRoot) Extract(archiveRel, dest string, say func(string, ...any)) (ExtractResult, error) {
	var result ExtractResult
	kind := archiveKind(archiveRel)
	if kind == "" {
		return result, errors.New("alleen .zip, .tar en .tar.gz kunnen worden uitgepakt")
	}
	root, err := fr.open()
	if err != nil {
		return result, err
	}
	defer root.Close()
	file, info, err := openRegular(root, archiveRel)
	if err != nil {
		return result, err
	}
	defer file.Close()
	if err := fr.mkdirAll(root, dest); err != nil {
		return result, err
	}
	limit := int64(maxExtractBytes)
	if free := fr.Free(); free >= 0 && free-(512<<20) < limit {
		limit = free - (512 << 20)
	}
	if limit <= 0 {
		return result, errors.New("de schijf is (bijna) vol")
	}

	add := func(name string, isDir bool, mode fs.FileMode, content io.Reader) error {
		name = strings.TrimLeft(strings.ReplaceAll(name, `\`, "/"), "/")
		rel, err := cleanPath(name)
		if err != nil || rel == "." {
			result.Skipped++
			return nil
		}
		target := rel
		if dest != "." {
			target = dest + "/" + rel
		}
		// Ook mappen tellen mee (anders kan een archief de schijf vullen met lege mappen).
		if result.Files+result.Dirs >= maxExtractFiles {
			return fmt.Errorf("meer dan %d bestanden en mappen in het archief", maxExtractFiles)
		}
		if isDir {
			result.Dirs++
			return fr.mkdirAll(root, target)
		}
		dir, base := splitPath(target)
		if err := fr.mkdirAll(root, dir); err != nil {
			return err
		}
		perm := fs.FileMode(0o644)
		if mode&0o111 != 0 {
			perm = 0o755
		}
		remaining := limit - result.Bytes
		counted := &countingReader{reader: io.LimitReader(content, remaining+1)}
		if info, err := root.Lstat(target); err == nil && info.IsDir() {
			result.Skipped++
			return nil
		}
		if _, err := fr.writeInto(root, dir, base, counted, perm); err != nil {
			return fmt.Errorf("%s: %w", rel, err)
		}
		result.Bytes += counted.n
		if result.Bytes > limit {
			_ = root.Remove(target)
			return fmt.Errorf("het archief is uitgepakt groter dan %s", humanBytes(limit))
		}
		result.Files++
		if result.Files%500 == 0 {
			say("%d bestanden uitgepakt (%s)…", result.Files, humanBytes(result.Bytes))
		}
		return nil
	}

	switch kind {
	case "zip":
		entries, directory, err := zipDirectory(file, info.Size())
		if err != nil {
			return result, fmt.Errorf("geen geldig zip-bestand: %w", err)
		}
		if entries > maxExtractFiles || directory > 128<<20 {
			return result, fmt.Errorf("het archief heeft te veel onderdelen (%d)", entries)
		}
		reader, err := zip.NewReader(file, info.Size())
		if err != nil {
			return result, fmt.Errorf("geen geldig zip-bestand: %w", err)
		}
		for _, entry := range reader.File {
			mode := entry.Mode()
			switch {
			case mode.IsDir() || strings.HasSuffix(entry.Name, "/"):
				if err := add(entry.Name, true, 0, nil); err != nil {
					return result, err
				}
			case mode.IsRegular():
				content, err := entry.Open()
				if err != nil {
					return result, fmt.Errorf("%s: %w", entry.Name, err)
				}
				err = add(entry.Name, false, mode, content)
				content.Close()
				if err != nil {
					return result, err
				}
			default:
				result.Skipped++
			}
		}
	default:
		var stream io.Reader = file
		if kind == "tar.gz" {
			gz, err := gzip.NewReader(file)
			if err != nil {
				return result, fmt.Errorf("geen geldig .tar.gz-bestand: %w", err)
			}
			defer gz.Close()
			stream = gz
		}
		reader := tar.NewReader(stream)
		for {
			header, err := reader.Next()
			if errors.Is(err, io.EOF) {
				break
			}
			if err != nil {
				return result, fmt.Errorf("het archief is beschadigd: %w", err)
			}
			switch header.Typeflag {
			case tar.TypeDir:
				err = add(header.Name, true, 0, nil)
			case tar.TypeReg, tar.TypeRegA:
				err = add(header.Name, false, fs.FileMode(header.Mode), reader)
			default:
				result.Skipped++
			}
			if err != nil {
				return result, err
			}
		}
	}
	return result, nil
}

type countingReader struct {
	reader io.Reader
	n      int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.reader.Read(p)
	c.n += int64(n)
	return n, err
}

// zipEntries schrijft rel (bestand of map, met inhoud) in het zip-bestand, met paden vanaf
// strip. Symlinks en bijzondere bestanden worden overgeslagen. skip: dit pad niet meenemen.
func zipEntries(root *os.Root, writer *zip.Writer, rel, strip, skip string, count *int) error {
	return walk(root, rel, func(current string, info fs.FileInfo) error {
		if current == skip {
			return nil
		}
		name := strings.TrimPrefix(current, strip)
		name = strings.TrimPrefix(name, "/")
		if name == "" || name == "." {
			return nil
		}
		switch {
		case info.IsDir():
			_, err := writer.CreateHeader(&zip.FileHeader{Name: name + "/", Method: zip.Store, Modified: info.ModTime()})
			return err
		case info.Mode().IsRegular():
			file, _, err := openRegular(root, current)
			if err != nil {
				// Bijv. een hardlink of een bestand dat net weg is: overslaan.
				return nil
			}
			defer file.Close()
			header := &zip.FileHeader{Name: name, Method: zip.Deflate, Modified: info.ModTime()}
			header.SetMode(info.Mode().Perm())
			out, err := writer.CreateHeader(header)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, file); err != nil {
				return err
			}
			*count++
		}
		return nil
	})
}

// Archive pakt de gekozen namen uit dir in tot een nieuw zip-bestand in dir.
func (fr FileRoot) Archive(dir string, names []string, archiveName string) (int, error) {
	root, err := fr.open()
	if err != nil {
		return 0, err
	}
	defer root.Close()
	target := joinPath(dir, archiveName)
	if _, err := root.Lstat(target); err == nil {
		return 0, errExists
	}
	temp := ".pinda-" + randomToken()[:12] + ".zip"
	tempRel := joinPath(dir, temp)
	file, err := root.OpenFile(tempRel, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return 0, err
	}
	fail := func(err error) (int, error) {
		file.Close()
		_ = root.Remove(tempRel)
		return 0, err
	}
	writer := zip.NewWriter(file)
	count := 0
	strip := dir
	if dir == "." {
		strip = ""
	}
	for _, name := range names {
		if err := zipEntries(root, writer, joinPath(dir, name), strip, tempRel, &count); err != nil {
			return fail(err)
		}
	}
	if err := writer.Close(); err != nil {
		return fail(err)
	}
	if err := fr.chown(file); err != nil {
		return fail(err)
	}
	if err := file.Close(); err != nil {
		_ = root.Remove(tempRel)
		return 0, err
	}
	if err := fr.Rename(tempRel, target); err != nil {
		_ = root.Remove(tempRel)
		return 0, err
	}
	return count, nil
}

// ZipTo schrijft een map als zip naar out (voor downloaden).
func (fr FileRoot) ZipTo(rel string, out io.Writer) error {
	root, err := fr.open()
	if err != nil {
		return err
	}
	defer root.Close()
	writer := zip.NewWriter(out)
	count := 0
	strip := rel
	if rel == "." {
		strip = ""
	}
	if err := zipEntries(root, writer, rel, strip, "", &count); err != nil {
		return err
	}
	return writer.Close()
}

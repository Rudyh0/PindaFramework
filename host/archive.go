package main

import (
	"archive/tar"
	"archive/zip"
	"bufio"
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

// zipDirectory telt de onderdelen van een zip-bestand zonder de inhoudsopgave in het geheugen
// te laden, en controleert dat die precies vóór het eindrecord ligt. zip.NewReader houdt de hele
// inhoudsopgave in het geheugen en leest door zolang er onderdelen staan (wat het eindrecord
// zegt, telt daarbij niet); een kwaadaardig bestand kan daar gigabytes van maken. Na deze
// controle leest zip.NewReader alleen de onderdelen die hier geteld zijn. Geeft het aantal
// onderdelen en de grootte van de inhoudsopgave.
func zipDirectory(reader io.ReaderAt, size int64, maxEntries uint64) (entries, directory uint64, err error) {
	const eocdLength = 22
	invalid := errors.New("geen geldig zip-bestand")
	if size < eocdLength {
		return 0, 0, invalid
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
		return 0, 0, invalid
	}
	end := size - tail + int64(at) // waar de inhoudsopgave moet ophouden
	entries = uint64(binary.LittleEndian.Uint16(buf[at+10:]))
	directory = uint64(binary.LittleEndian.Uint32(buf[at+12:]))
	offset := uint64(binary.LittleEndian.Uint32(buf[at+16:]))
	if entries == 0xffff || directory == 0xffffffff || offset == 0xffffffff {
		// Zip64: de echte getallen staan in een eigen record (de "locator" staat er vlak voor).
		if at < 20 || binary.LittleEndian.Uint32(buf[at-20:]) != 0x07064b50 {
			return 0, 0, errors.New("ongeldig zip64-bestand")
		}
		recordAt := int64(binary.LittleEndian.Uint64(buf[at-20+8:]))
		record := make([]byte, 56)
		if recordAt < 0 || recordAt+56 > size {
			return 0, 0, errors.New("ongeldig zip64-bestand")
		}
		if _, err := reader.ReadAt(record, recordAt); err != nil {
			return 0, 0, err
		}
		if binary.LittleEndian.Uint32(record) != 0x06064b50 {
			return 0, 0, errors.New("ongeldig zip64-bestand")
		}
		entries = binary.LittleEndian.Uint64(record[32:])
		directory = binary.LittleEndian.Uint64(record[40:])
		offset = binary.LittleEndian.Uint64(record[48:])
		end = recordAt
	}
	if entries > maxEntries || directory > maxEntries*1024 {
		return 0, 0, fmt.Errorf("het archief heeft te veel onderdelen (%d)", entries)
	}
	// De inhoudsopgave moet precies tot het eindrecord lopen (geen data ervoor of ertussen).
	if offset > uint64(end) || offset+directory != uint64(end) {
		return 0, 0, errors.New("geen geldig zip-bestand (de inhoudsopgave klopt niet)")
	}
	// Tellen, kop voor kop.
	section := bufio.NewReaderSize(io.NewSectionReader(reader, int64(offset), int64(directory)), 64<<10)
	header := make([]byte, 46)
	var counted, read uint64
	for read < directory {
		if _, err := io.ReadFull(section, header); err != nil || binary.LittleEndian.Uint32(header) != 0x02014b50 {
			return 0, 0, errors.New("geen geldig zip-bestand (kapotte inhoudsopgave)")
		}
		rest := uint64(binary.LittleEndian.Uint16(header[28:])) + uint64(binary.LittleEndian.Uint16(header[30:])) + uint64(binary.LittleEndian.Uint16(header[32:]))
		if _, err := section.Discard(int(rest)); err != nil {
			return 0, 0, errors.New("geen geldig zip-bestand (kapotte inhoudsopgave)")
		}
		read += 46 + rest
		if counted++; counted > maxEntries {
			return 0, 0, fmt.Errorf("het archief heeft te veel onderdelen (meer dan %d)", maxEntries)
		}
	}
	if read != directory {
		return 0, 0, errors.New("geen geldig zip-bestand (kapotte inhoudsopgave)")
	}
	return counted, directory, nil
}

// ExtractResult: wat er is uitgepakt.
type ExtractResult struct {
	Files   int   `json:"files"`
	Dirs    int   `json:"dirs"`
	Bytes   int64 `json:"bytes"`
	Skipped int   `json:"skipped"`
}

// extractor zet onderdelen van een archief veilig neer in een FileRoot: paden met .., absolute
// paden, symlinks en apparaten worden overgeslagen, en er geldt een grens voor de grootte en het
// aantal bestanden. Bestaande bestanden worden overschreven.
type extractor struct {
	fr       FileRoot
	root     *os.Root
	dest     string
	limit    int64
	maxFiles int
	result   ExtractResult
	say      func(string, ...any)
}

// newExtractor: limit = hooguit zoveel bytes (en nooit meer dan er vrij is, min 512 MB).
func (fr FileRoot) newExtractor(root *os.Root, dest string, limit int64, maxFiles int, say func(string, ...any)) (*extractor, error) {
	if err := fr.mkdirAll(root, dest); err != nil {
		return nil, err
	}
	if free := fr.Free(); free >= 0 && free-(512<<20) < limit {
		limit = free - (512 << 20)
	}
	if limit <= 0 {
		return nil, errors.New("de schijf is (bijna) vol")
	}
	return &extractor{fr: fr, root: root, dest: dest, limit: limit, maxFiles: maxFiles, say: say}, nil
}

func (e *extractor) add(name string, isDir bool, mode fs.FileMode, content io.Reader) error {
	name = strings.TrimLeft(strings.ReplaceAll(name, `\`, "/"), "/")
	rel, err := cleanPath(name)
	if err != nil || rel == "." {
		e.result.Skipped++
		return nil
	}
	target := rel
	if e.dest != "." {
		target = e.dest + "/" + rel
	}
	// Ook mappen tellen mee (anders kan een archief de schijf vullen met lege mappen).
	if e.result.Files+e.result.Dirs >= e.maxFiles {
		return fmt.Errorf("meer dan %d bestanden en mappen in het archief", e.maxFiles)
	}
	if isDir {
		e.result.Dirs++
		return e.fr.mkdirAll(e.root, target)
	}
	dir, base := splitPath(target)
	if err := e.fr.mkdirAll(e.root, dir); err != nil {
		return err
	}
	perm := fs.FileMode(0o644)
	if mode&0o111 != 0 {
		perm = 0o755
	}
	remaining := e.limit - e.result.Bytes
	counted := &countingReader{reader: io.LimitReader(content, remaining+1)}
	if info, err := e.root.Lstat(target); err == nil && info.IsDir() {
		e.result.Skipped++
		return nil
	}
	if _, err := e.fr.writeInto(e.root, dir, base, counted, perm); err != nil {
		return fmt.Errorf("%s: %w", rel, err)
	}
	e.result.Bytes += counted.n
	if e.result.Bytes > e.limit {
		_ = e.root.Remove(target)
		return fmt.Errorf("het archief is uitgepakt groter dan %s", humanBytes(e.limit))
	}
	e.result.Files++
	if e.result.Files%1000 == 0 {
		e.say("%d bestanden uitgepakt (%s)…", e.result.Files, humanBytes(e.result.Bytes))
	}
	return nil
}

// zipEntries pakt alle onderdelen uit die met prefix beginnen (prefix eraf).
func (e *extractor) zip(reader *zip.Reader, prefix string) error {
	for _, entry := range reader.File {
		if !strings.HasPrefix(entry.Name, prefix) {
			continue
		}
		name := strings.TrimPrefix(entry.Name, prefix)
		mode := entry.Mode()
		switch {
		case mode.IsDir() || strings.HasSuffix(entry.Name, "/"):
			if err := e.add(name, true, 0, nil); err != nil {
				return err
			}
		case mode.IsRegular():
			content, err := entry.Open()
			if err != nil {
				return fmt.Errorf("%s: %w", entry.Name, err)
			}
			err = e.add(name, false, mode, content)
			content.Close()
			if err != nil {
				return err
			}
		default:
			e.result.Skipped++
		}
	}
	return nil
}

// openZip opent een zip-bestand, maar alleen als de inhoudsopgave niet absurd groot is.
func openZip(file io.ReaderAt, size int64, maxEntries uint64) (*zip.Reader, error) {
	entries, _, err := zipDirectory(file, size, maxEntries)
	if err != nil {
		return nil, err
	}
	reader, err := zip.NewReader(file, size)
	if err != nil {
		return nil, fmt.Errorf("geen geldig zip-bestand: %w", err)
	}
	if uint64(len(reader.File)) != entries {
		return nil, errors.New("geen geldig zip-bestand (de inhoudsopgave klopt niet)")
	}
	return reader, nil
}

// Extract pakt een archief uit in dest: hooguit 20 GB (of wat er vrij is) en 200.000 bestanden.
func (fr FileRoot) Extract(archiveRel, dest string, say func(string, ...any)) (ExtractResult, error) {
	kind := archiveKind(archiveRel)
	if kind == "" {
		return ExtractResult{}, errors.New("alleen .zip, .tar en .tar.gz kunnen worden uitgepakt")
	}
	root, err := fr.open()
	if err != nil {
		return ExtractResult{}, err
	}
	defer root.Close()
	file, info, err := openRegular(root, archiveRel)
	if err != nil {
		return ExtractResult{}, err
	}
	defer file.Close()
	e, err := fr.newExtractor(root, dest, maxExtractBytes, maxExtractFiles, say)
	if err != nil {
		return ExtractResult{}, err
	}
	switch kind {
	case "zip":
		reader, err := openZip(file, info.Size(), maxExtractFiles)
		if err != nil {
			return e.result, err
		}
		return e.result, e.zip(reader, "")
	default:
		var stream io.Reader = file
		if kind == "tar.gz" {
			gz, err := gzip.NewReader(file)
			if err != nil {
				return e.result, fmt.Errorf("geen geldig .tar.gz-bestand: %w", err)
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
				return e.result, fmt.Errorf("het archief is beschadigd: %w", err)
			}
			switch header.Typeflag {
			case tar.TypeDir:
				err = e.add(header.Name, true, 0, nil)
			case tar.TypeReg, tar.TypeRegA:
				err = e.add(header.Name, false, fs.FileMode(header.Mode), reader)
			default:
				e.result.Skipped++
			}
			if err != nil {
				return e.result, err
			}
		}
	}
	return e.result, nil
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

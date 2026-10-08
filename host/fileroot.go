package main

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"sort"
	"strings"
	"syscall"
	"unicode/utf8"
)

// FileRoot is een map waarin het paneel bestanden beheert: de Minecraft-server of de website.
//
// Het paneel draait als root, maar de servermap is van de gebruiker "minecraft", en daarmee van
// elke plugin. Alles gaat daarom via os.Root: een pad (ook via een symlink of "..") kan nooit
// buiten de map uitkomen, ook niet als er tegelijk iets verandert.
type FileRoot struct {
	Name string // "server" of "website"
	Base string
	// Eigenaar voor wat het paneel aanmaakt; -1 = root laten.
	UID, GID int
}

// FileEntry is één regel in de bestandsbrowser.
type FileEntry struct {
	Name     string `json:"name"`
	Type     string `json:"type"` // dir, file, link of other
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
	Mode     string `json:"mode"`
}

var (
	errNotFile   = errors.New("dit is geen gewoon bestand")
	errNotDir    = errors.New("dit is geen map")
	errExists    = errors.New("er bestaat al iets met die naam")
	errRootPath  = errors.New("dat kan niet met de hoofdmap zelf")
	errLinkWrite = errors.New("dit is een snelkoppeling (symlink); bewerk het bestand waar hij naartoe wijst")
	errHardlink  = errors.New("dit bestand heeft meerdere hardlinks; dat doet het paneel niet")
)

// cleanPath maakt van wat de browser stuurt een veilig, relatief pad ("." = de hoofdmap).
func cleanPath(p string) (string, error) {
	if len(p) > 4096 || strings.ContainsRune(p, 0) {
		return "", errors.New("ongeldig pad")
	}
	var parts []string
	for _, part := range strings.Split(p, "/") {
		switch part {
		case "", ".":
			continue
		case "..":
			return "", errors.New("ongeldig pad")
		}
		if len(part) > 255 {
			return "", errors.New("een naam is maximaal 255 tekens")
		}
		parts = append(parts, part)
	}
	if len(parts) == 0 {
		return ".", nil
	}
	return strings.Join(parts, "/"), nil
}

// cleanName: één naam (geen pad), zoals bij hernoemen of uploaden.
func cleanName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\x00") || len(name) > 255 {
		return "", errors.New("ongeldige naam")
	}
	return name, nil
}

func splitPath(rel string) (string, string) {
	dir, name := path.Split(rel)
	dir = strings.TrimSuffix(dir, "/")
	if dir == "" {
		dir = "."
	}
	return dir, name
}

func joinPath(dir, name string) string {
	if dir == "." || dir == "" {
		return name
	}
	return dir + "/" + name
}

// openDir opent een map. Met O_DIRECTORY: staat er iets anders (bijv. een fifo die de
// minecraft-gebruiker neerzette), dan meteen een fout in plaats van eeuwig wachten.
func openDir(root *os.Root, rel string) (*os.File, error) {
	return root.OpenFile(rel, os.O_RDONLY|syscall.O_DIRECTORY, 0)
}

// open opent de hoofdmap (en maakt hem als hij er nog niet is).
func (fr FileRoot) open() (*os.Root, error) {
	if _, err := os.Stat(fr.Base); errors.Is(err, os.ErrNotExist) {
		if err := os.MkdirAll(fr.Base, 0o755); err != nil {
			return nil, err
		}
		if fr.UID >= 0 {
			_ = os.Chown(fr.Base, fr.UID, fr.GID)
		}
	}
	return os.OpenRoot(fr.Base)
}

func (fr FileRoot) chown(file *os.File) error {
	if fr.UID < 0 {
		return nil
	}
	return file.Chown(fr.UID, fr.GID)
}

func entryType(mode fs.FileMode) string {
	switch {
	case mode&fs.ModeSymlink != 0:
		return "link"
	case mode.IsDir():
		return "dir"
	case mode.IsRegular():
		return "file"
	}
	return "other"
}

const (
	maxListEntries = 20000
	maxDepth       = 1024
)

// List geeft de inhoud van een map: eerst mappen, dan bestanden, op naam.
func (fr FileRoot) List(rel string) ([]FileEntry, bool, error) {
	root, err := fr.open()
	if err != nil {
		return nil, false, err
	}
	defer root.Close()
	dir, err := openDir(root, rel)
	if err != nil {
		return nil, false, err
	}
	defer dir.Close()
	if info, err := dir.Stat(); err != nil {
		return nil, false, err
	} else if !info.IsDir() {
		return nil, false, errNotDir
	}
	names, err := dir.Readdirnames(maxListEntries + 1)
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, false, err
	}
	truncated := len(names) > maxListEntries
	if truncated {
		names = names[:maxListEntries]
	}
	list := make([]FileEntry, 0, len(names))
	for _, name := range names {
		// Lstat via de root (niet DirEntry.Info: die werkt met een los pad).
		info, err := root.Lstat(joinPath(rel, name))
		if err != nil {
			continue
		}
		entry := FileEntry{Name: name, Type: entryType(info.Mode()), Modified: info.ModTime().UnixMilli(), Mode: info.Mode().Perm().String()}
		if entry.Type == "file" {
			entry.Size = info.Size()
		}
		list = append(list, entry)
	}
	sort.Slice(list, func(i, j int) bool {
		if (list[i].Type == "dir") != (list[j].Type == "dir") {
			return list[i].Type == "dir"
		}
		return strings.ToLower(list[i].Name) < strings.ToLower(list[j].Name)
	})
	return list, truncated, nil
}

// openRegular opent een gewoon bestand om te lezen (geen fifo, map of apparaat; geen hardlinks).
func openRegular(root *os.Root, rel string) (*os.File, fs.FileInfo, error) {
	file, err := root.OpenFile(rel, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, nil, err
	}
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, nil, err
	}
	if !info.Mode().IsRegular() {
		file.Close()
		return nil, nil, errNotFile
	}
	if stat, ok := info.Sys().(*syscall.Stat_t); ok && stat.Nlink > 1 {
		file.Close()
		return nil, nil, errHardlink
	}
	return file, info, nil
}

// TextFile: een bestand voor de editor.
type TextFile struct {
	Path     string `json:"path"`
	Content  string `json:"content"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
	Binary   bool   `json:"binary"`
	TooLarge bool   `json:"tooLarge"`
}

const maxEditSize = 5 << 20

// ReadText leest een bestand voor de editor. Binaire of te grote bestanden worden niet geladen.
func (fr FileRoot) ReadText(rel string) (TextFile, error) {
	result := TextFile{Path: rel}
	root, err := fr.open()
	if err != nil {
		return result, err
	}
	defer root.Close()
	file, info, err := openRegular(root, rel)
	if err != nil {
		return result, err
	}
	defer file.Close()
	result.Size, result.Modified = info.Size(), info.ModTime().UnixMilli()
	if info.Size() > maxEditSize {
		result.TooLarge = true
		return result, nil
	}
	data, err := io.ReadAll(io.LimitReader(file, maxEditSize+1))
	if err != nil {
		return result, err
	}
	if looksBinary(data) {
		result.Binary = true
		return result, nil
	}
	result.Content = string(data)
	return result, nil
}

func looksBinary(data []byte) bool {
	head := data
	if len(head) > 8000 {
		head = head[:8000]
	}
	for _, b := range head {
		if b == 0 {
			return true
		}
	}
	return !utf8.Valid(data)
}

// errChangedOnDisk: het bestand is veranderd sinds de editor het opende.
var errChangedOnDisk = errors.New("het bestand is intussen veranderd (bijvoorbeeld door de server zelf)")

// WriteFile schrijft een bestand in één keer: eerst een tijdelijk bestand ernaast, dan hernoemen.
// Met expected (ms) wordt eerst gecontroleerd of het bestand intussen niet veranderd is.
func (fr FileRoot) WriteFile(rel string, data io.Reader, expected int64) (int64, error) {
	if rel == "." {
		return 0, errRootPath
	}
	root, err := fr.open()
	if err != nil {
		return 0, err
	}
	defer root.Close()
	perm := fs.FileMode(0o644)
	if info, err := root.Lstat(rel); err == nil {
		switch {
		case info.Mode()&fs.ModeSymlink != 0:
			return 0, errLinkWrite
		case !info.Mode().IsRegular():
			return 0, errNotFile
		case expected > 0 && info.ModTime().UnixMilli() != expected:
			return 0, errChangedOnDisk
		}
		if stat, ok := info.Sys().(*syscall.Stat_t); ok && stat.Nlink > 1 {
			return 0, errHardlink
		}
		perm = info.Mode().Perm()
	} else if !errors.Is(err, os.ErrNotExist) {
		return 0, err
	}
	dir, name := splitPath(rel)
	return fr.writeInto(root, dir, name, data, perm)
}

// writeInto schrijft via een tijdelijk bestand in dir en hernoemt dat naar name.
func (fr FileRoot) writeInto(root *os.Root, dir, name string, data io.Reader, perm fs.FileMode) (int64, error) {
	return fr.writeWith(root, dir, name, perm, func(w io.Writer) (int64, error) { return io.Copy(w, data) })
}

// writeWith: zoals writeInto, maar fill schrijft de inhoud. Geeft fill een fout (bijv. een
// download die niet klopt), dan blijft het oude bestand gewoon staan.
func (fr FileRoot) writeWith(root *os.Root, dir, name string, perm fs.FileMode, fill func(io.Writer) (int64, error)) (int64, error) {
	parent, err := openDir(root, dir)
	if err != nil {
		return 0, err
	}
	defer parent.Close()
	if info, err := parent.Stat(); err != nil {
		return 0, err
	} else if !info.IsDir() {
		return 0, errNotDir
	}
	temp := ".pinda-" + randomToken()[:12] + ".tmp"
	file, err := root.OpenFile(joinPath(dir, temp), os.O_WRONLY|os.O_CREATE|os.O_EXCL, perm)
	if err != nil {
		return 0, err
	}
	fail := func(err error) (int64, error) {
		file.Close()
		_ = root.Remove(joinPath(dir, temp))
		return 0, err
	}
	written, err := fill(file)
	if err != nil {
		return fail(err)
	}
	if err := fr.chown(file); err != nil {
		return fail(err)
	}
	if err := file.Chmod(perm); err != nil {
		return fail(err)
	}
	if err := file.Sync(); err != nil {
		return fail(err)
	}
	if err := file.Close(); err != nil {
		_ = root.Remove(joinPath(dir, temp))
		return 0, err
	}
	dirFD := int(parent.Fd())
	if err := syscall.Renameat(dirFD, temp, dirFD, name); err != nil {
		_ = root.Remove(joinPath(dir, temp))
		return 0, err
	}
	return written, nil
}

// WriteVerified schrijft rel met fill (bijv. een download met controlegetal), maakt de map
// erboven als die er nog niet is, en vervangt het bestand pas als alles klopt.
func (fr FileRoot) WriteVerified(rel string, fill func(io.Writer) (int64, error)) (int64, error) {
	if rel == "." {
		return 0, errRootPath
	}
	root, err := fr.open()
	if err != nil {
		return 0, err
	}
	defer root.Close()
	dir, name := splitPath(rel)
	if err := fr.mkdirAll(root, dir); err != nil {
		return 0, err
	}
	if info, err := root.Lstat(rel); err == nil && !info.Mode().IsRegular() {
		return 0, errNotFile
	}
	return fr.writeWith(root, dir, name, 0o644, fill)
}

// Create maakt een nieuw, leeg bestand of een nieuwe map.
func (fr FileRoot) Create(rel string, directory bool) error {
	if rel == "." {
		return errRootPath
	}
	root, err := fr.open()
	if err != nil {
		return err
	}
	defer root.Close()
	if _, err := root.Lstat(rel); err == nil {
		return errExists
	}
	if directory {
		return fr.mkdir(root, rel)
	}
	file, err := root.OpenFile(rel, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return err
	}
	defer file.Close()
	return fr.chown(file)
}

func (fr FileRoot) mkdir(root *os.Root, rel string) error {
	if err := root.Mkdir(rel, 0o755); err != nil {
		return err
	}
	dir, err := openDir(root, rel)
	if err != nil {
		return err
	}
	defer dir.Close()
	return fr.chown(dir)
}

// mkdirAll maakt rel en alle mappen erboven (binnen de root).
func (fr FileRoot) mkdirAll(root *os.Root, rel string) error {
	if rel == "." {
		return nil
	}
	current := ""
	for _, part := range strings.Split(rel, "/") {
		current = joinPath(current, part)
		info, err := root.Stat(current)
		if err == nil {
			if !info.IsDir() {
				return fmt.Errorf("%s: %w", current, errNotDir)
			}
			continue
		}
		if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		if err := fr.mkdir(root, current); err != nil && !errors.Is(err, os.ErrExist) {
			return err
		}
	}
	return nil
}

// Rename hernoemt of verplaatst (binnen dezelfde root). Bestaat het doel al, dan gebeurt er niets.
func (fr FileRoot) Rename(from, to string) error {
	if from == "." || to == "." {
		return errRootPath
	}
	if from == to {
		return nil
	}
	if strings.HasPrefix(to+"/", from+"/") {
		return errors.New("een map kan niet in zichzelf")
	}
	root, err := fr.open()
	if err != nil {
		return err
	}
	defer root.Close()
	if _, err := root.Lstat(from); err != nil {
		return err
	}
	if _, err := root.Lstat(to); err == nil {
		return errExists
	}
	fromDir, fromName := splitPath(from)
	toDir, toName := splitPath(to)
	source, err := openDir(root, fromDir)
	if err != nil {
		return err
	}
	defer source.Close()
	target, err := openDir(root, toDir)
	if err != nil {
		return err
	}
	defer target.Close()
	if info, err := target.Stat(); err != nil {
		return err
	} else if !info.IsDir() {
		return errNotDir
	}
	return syscall.Renameat(int(source.Fd()), fromName, int(target.Fd()), toName)
}

// Delete gooit bestanden en mappen (met inhoud) weg. Symlinks worden zelf weggehaald, nooit
// gevolgd.
func (fr FileRoot) Delete(rel string) (int, error) {
	if rel == "." {
		return 0, errRootPath
	}
	root, err := fr.open()
	if err != nil {
		return 0, err
	}
	defer root.Close()
	count := 0
	return count, removeAll(root, rel, &count, 0)
}

func removeAll(root *os.Root, rel string, count *int, depth int) error {
	if depth > maxDepth {
		return errors.New("te diep geneste mappen")
	}
	info, err := root.Lstat(rel)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if info.IsDir() {
		dir, err := openDir(root, rel)
		if err != nil {
			return err
		}
		for {
			names, err := dir.Readdirnames(1000)
			for _, name := range names {
				if err := removeAll(root, joinPath(rel, name), count, depth+1); err != nil {
					dir.Close()
					return err
				}
			}
			if err != nil || len(names) == 0 {
				break
			}
		}
		dir.Close()
	}
	if err := root.Remove(rel); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	*count++
	return nil
}

// Open opent een gewoon bestand om te downloaden.
func (fr FileRoot) Open(rel string) (*os.File, fs.FileInfo, error) {
	root, err := fr.open()
	if err != nil {
		return nil, nil, err
	}
	defer root.Close()
	return openRegular(root, rel)
}

// Stat: zonder een symlink aan het eind te volgen.
func (fr FileRoot) Stat(rel string) (fs.FileInfo, error) {
	root, err := fr.open()
	if err != nil {
		return nil, err
	}
	defer root.Close()
	return root.Lstat(rel)
}

// Free: hoeveel ruimte er nog op de schijf is.
func (fr FileRoot) Free() int64 {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(fr.Base, &stat); err != nil {
		return -1
	}
	return int64(stat.Bavail) * int64(stat.Bsize)
}

// walk loopt door alles onder rel (geen symlinks volgen) en roept visit aan voor elk bestand
// of elke map, met het pad vanaf rel.
func walk(root *os.Root, rel string, visit func(path string, info fs.FileInfo) error) error {
	var step func(current string, depth int) error
	step = func(current string, depth int) error {
		if depth > maxDepth {
			return errors.New("te diep geneste mappen")
		}
		info, err := root.Lstat(current)
		if err != nil {
			return err
		}
		if err := visit(current, info); err != nil {
			return err
		}
		if !info.IsDir() {
			return nil
		}
		dir, err := openDir(root, current)
		if err != nil {
			return err
		}
		names, err := dir.Readdirnames(-1)
		dir.Close()
		if err != nil {
			return err
		}
		sort.Strings(names)
		for _, name := range names {
			if err := step(joinPath(current, name), depth+1); err != nil {
				return err
			}
		}
		return nil
	}
	return step(rel, 0)
}

// touchTime: voor tests en de editor, de wijzigingstijd in ms.
func modifiedMillis(info fs.FileInfo) int64 {
	if info == nil {
		return 0
	}
	return info.ModTime().UnixMilli()
}

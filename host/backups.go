package main

import (
	"archive/zip"
	"compress/flate"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"time"
)

// Een backup is één zip-bestand in /opt/pinda/backups (alleen voor root) met:
//   server/     de servermap (zonder cache, libraries en versions: die haalt Purpur zelf weer op)
//   website/    de website
//   databases/  een .sql-dump per database in MariaDB
//   manifest.json  wat erin zit
// Naast elke backup staat een verborgen .<naam>.json met dezelfde gegevens, zodat de lijst snel is.

const (
	backupFormat = 1
	// Hooguit zoveel bestanden en mappen in een backup (de inhoudsopgave staat bij het lezen in
	// het geheugen: ongeveer 250 bytes per stuk).
	maxBackupEntries = 3_000_000
)

var (
	backupNamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.zip$`)
	defaultExclude    = []string{"cache", "libraries", "versions", "logs", "crash-reports", "plugins/dynmap/web/tiles"}
	// Al gecomprimeerd: niet nog eens inpakken (scheelt veel tijd).
	storedExtensions = map[string]bool{".jar": true, ".zip": true, ".gz": true, ".tgz": true, ".xz": true, ".bz2": true, ".7z": true,
		".png": true, ".jpg": true, ".jpeg": true, ".webp": true, ".gif": true, ".ogg": true, ".mp3": true, ".mp4": true, ".mca": true, ".mcr": true}
)

// BackupManifest: wat er in een backup zit.
type BackupManifest struct {
	Format    int         `json:"format"`
	Created   int64       `json:"created"`
	Trigger   string      `json:"trigger"` // handmatig, automatisch, voor-herstel, voor-herstart
	Note      string      `json:"note,omitempty"`
	By        string      `json:"by,omitempty"`
	Server    *BackupPart `json:"server,omitempty"`
	Website   *BackupPart `json:"website,omitempty"`
	Databases []string    `json:"databases,omitempty"`
	Minecraft string      `json:"minecraft,omitempty"`
	Build     string      `json:"build,omitempty"`
	Exclude   []string    `json:"exclude,omitempty"`
	Panel     string      `json:"panel"`
	Warnings  []string    `json:"warnings,omitempty"`
}

type BackupPart struct {
	Files int   `json:"files"`
	Bytes int64 `json:"bytes"`
}

// BackupInfo: een backup in de lijst.
type BackupInfo struct {
	Name     string          `json:"name"`
	Size     int64           `json:"size"`
	Modified int64           `json:"modified"`
	Manifest *BackupManifest `json:"manifest,omitempty"`
	// Foreign: een zip zonder manifest (geen backup van het paneel); niet terug te zetten.
	Foreign bool `json:"foreign,omitempty"`
	// Uploaded: via het paneel geüpload (van een andere server of een oude download).
	Uploaded bool `json:"uploaded,omitempty"`
	// Pinned: niet automatisch opruimen.
	Pinned bool `json:"pinned,omitempty"`
}

// backupSidecar: het verborgen .<naam>.json naast een backup.
type backupSidecar struct {
	Manifest *BackupManifest `json:"manifest"`
	Foreign  bool            `json:"foreign,omitempty"`
	Uploaded bool            `json:"uploaded,omitempty"`
	Pinned   bool            `json:"pinned,omitempty"`
}

// BackupOptions: wat er in een nieuwe backup moet.
type BackupOptions struct {
	Trigger   string
	Note      string
	By        string
	Website   bool
	Databases bool
	Exclude   []string
}

func (a *App) backupDir() string {
	return filepath.Join(a.config.get().BaseDir, "backups")
}

func (a *App) ensureBackupDir() (string, error) {
	dir := a.backupDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, os.Chmod(dir, 0o700)
}

func sidecarPath(dir, name string) string {
	return filepath.Join(dir, "."+name+".json")
}

func writeSidecar(dir, name string, sidecar backupSidecar) error {
	data, err := json.Marshal(sidecar)
	if err != nil {
		return err
	}
	return writeFileAtomic(sidecarPath(dir, name), data, 0o600)
}

// lockBackup: één backup of terugzetten tegelijk. Met wait wacht hij op een lopende.
func (a *App) lockBackup(wait bool) bool {
	if !a.backupJob.TryLock() {
		if !wait {
			return false
		}
		a.backupJob.Lock()
	}
	a.backupRunning.Store(true)
	return true
}

func (a *App) unlockBackup() {
	a.backupRunning.Store(false)
	a.backupJob.Unlock()
}

// ============================================================ lijst

func (a *App) listBackups() ([]BackupInfo, error) {
	dir, err := a.ensureBackupDir()
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	list := []BackupInfo{}
	for _, entry := range entries {
		name := entry.Name()
		if !entry.Type().IsRegular() || !backupNamePattern.MatchString(name) {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		sidecar := a.backupSidecar(dir, name)
		list = append(list, BackupInfo{Name: name, Size: info.Size(), Modified: info.ModTime().UnixMilli(),
			Manifest: sidecar.Manifest, Foreign: sidecar.Foreign, Uploaded: sidecar.Uploaded, Pinned: sidecar.Pinned})
	}
	sort.Slice(list, func(i, j int) bool { return backupTime(list[i]) > backupTime(list[j]) })
	return list, nil
}

func backupTime(backup BackupInfo) int64 {
	if backup.Manifest != nil && backup.Manifest.Created > 0 {
		return backup.Manifest.Created
	}
	return backup.Modified
}

// backupSidecar: uit het zijbestand, of (eenmalig) uit de zip zelf.
func (a *App) backupSidecar(dir, name string) backupSidecar {
	var stored backupSidecar
	if data, err := os.ReadFile(sidecarPath(dir, name)); err == nil && json.Unmarshal(data, &stored) == nil {
		return stored
	}
	manifest, err := readManifest(filepath.Join(dir, name))
	stored = backupSidecar{Manifest: manifest, Foreign: err != nil}
	_ = writeSidecar(dir, name, stored)
	return stored
}

// updateSidecar past het zijbestand van een backup aan.
func (a *App) updateSidecar(name string, change func(*backupSidecar)) error {
	if _, err := a.backupPath(name); err != nil {
		return err
	}
	dir := a.backupDir()
	sidecar := a.backupSidecar(dir, name)
	change(&sidecar)
	return writeSidecar(dir, name, sidecar)
}

func readManifest(path string) (*BackupManifest, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, err
	}
	reader, err := openZip(file, info.Size(), maxBackupEntries)
	if err != nil {
		return nil, err
	}
	return manifestFrom(reader)
}

func manifestFrom(reader *zip.Reader) (*BackupManifest, error) {
	for _, entry := range reader.File {
		if entry.Name != "manifest.json" || entry.UncompressedSize64 > 1<<20 {
			continue
		}
		content, err := entry.Open()
		if err != nil {
			return nil, err
		}
		defer content.Close()
		var manifest BackupManifest
		if err := json.NewDecoder(io.LimitReader(content, 1<<20)).Decode(&manifest); err != nil {
			return nil, err
		}
		if manifest.Format != backupFormat {
			return nil, fmt.Errorf("onbekende versie van het backup-formaat (%d)", manifest.Format)
		}
		// Een backup van elders kan van alles beweren: alleen bekende soorten, en geen datum in
		// de toekomst.
		switch manifest.Trigger {
		case "handmatig", "automatisch", "voor-herstel", "voor-herstart":
		default:
			manifest.Trigger = "onbekend"
		}
		if manifest.Created > time.Now().Add(24*time.Hour).UnixMilli() || manifest.Created < 0 {
			manifest.Created = 0
		}
		return &manifest, nil
	}
	return nil, errors.New("geen backup van het dev-paneel (manifest.json ontbreekt)")
}

func (a *App) backupPath(name string) (string, error) {
	if !backupNamePattern.MatchString(name) {
		return "", errors.New("ongeldige naam")
	}
	path := filepath.Join(a.backupDir(), name)
	if info, err := os.Lstat(path); err != nil || !info.Mode().IsRegular() {
		return "", os.ErrNotExist
	}
	return path, nil
}

func (a *App) deleteBackup(name string) error {
	path, err := a.backupPath(name)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil {
		return err
	}
	_ = os.Remove(sidecarPath(a.backupDir(), name))
	return nil
}

// ============================================================ maken

// errBackupBusy: er loopt al een backup of herstel.
var errBackupBusy = errors.New("er loopt al een backup of terugzetten; wacht tot die klaar is")

// createBackup maakt een backup (één tegelijk).
func (a *App) createBackup(ctx context.Context, options BackupOptions, say func(string, ...any)) (BackupInfo, error) {
	if !a.lockBackup(false) {
		return BackupInfo{}, errBackupBusy
	}
	defer a.unlockBackup()
	return a.createBackupLocked(ctx, options, say)
}

// createBackupLocked: zoals createBackup, maar backupJob is al vergrendeld.
func (a *App) createBackupLocked(ctx context.Context, options BackupOptions, say func(string, ...any)) (BackupInfo, error) {
	dir, err := a.ensureBackupDir()
	if err != nil {
		return BackupInfo{}, err
	}
	exclude := options.Exclude
	if exclude == nil {
		exclude = defaultExclude
	}
	serverRoot := a.serverRoot()
	websiteRoot, _ := a.fileRoot("website")

	// Past het? De zip wordt kleiner dan de bestanden, maar reken ruim.
	estimate := treeSize(serverRoot, exclude)
	if options.Website {
		estimate += treeSize(websiteRoot, nil)
	}
	var stat syscall.Statfs_t
	if syscall.Statfs(dir, &stat) == nil {
		free := int64(stat.Bavail) * int64(stat.Bsize)
		if free < estimate*7/10+(512<<20) {
			return BackupInfo{}, fmt.Errorf("niet genoeg ruimte voor een backup: nodig ongeveer %s, vrij %s. Verwijder oude backups", humanBytes(estimate*7/10), humanBytes(free))
		}
	}

	now := time.Now()
	trigger := options.Trigger
	if trigger == "" {
		trigger = "handmatig"
	}
	name := fmt.Sprintf("pinda-%s-%s.zip", now.Format("2006-01-02-150405"), trigger)
	temp := filepath.Join(dir, "."+name+".tmp")
	file, err := os.OpenFile(temp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return BackupInfo{}, err
	}
	finished := false
	defer func() {
		if !finished {
			file.Close()
			_ = os.Remove(temp)
		}
	}()
	writer := zip.NewWriter(file)
	writer.RegisterCompressor(zip.Deflate, func(out io.Writer) (io.WriteCloser, error) {
		return flate.NewWriter(out, flate.BestSpeed)
	})
	state := a.server.get()
	manifest := &BackupManifest{Format: backupFormat, Created: now.UnixMilli(), Trigger: trigger, Note: options.Note,
		By: options.By, Panel: Version, Minecraft: state.Version, Build: state.Build, Exclude: exclude}

	// De server: even niet opslaan, zodat de wereld niet half geschreven in de backup komt.
	if _, err := os.Stat(serverRoot.Base); err == nil {
		resume := a.pauseSaving(say)
		say("Servermap inpakken…")
		part, err := zipTree(ctx, serverRoot, writer, "server/", exclude, say)
		resume()
		if err != nil {
			return BackupInfo{}, fmt.Errorf("servermap: %w", err)
		}
		manifest.Server = &part
		say("Servermap: %d bestanden (%s).", part.Files, humanBytes(part.Bytes))
	}
	if options.Website {
		if _, err := os.Stat(websiteRoot.Base); err == nil {
			part, err := zipTree(ctx, websiteRoot, writer, "website/", nil, say)
			if err != nil {
				return BackupInfo{}, fmt.Errorf("website: %w", err)
			}
			manifest.Website = &part
			say("Website: %d bestanden (%s).", part.Files, humanBytes(part.Bytes))
		}
	}
	if options.Databases {
		if status := a.mariadb.status(); status.Running {
			databases, err := a.mariadb.databases()
			if err != nil {
				return BackupInfo{}, err
			}
			for _, db := range databases {
				if err := ctx.Err(); err != nil {
					return BackupInfo{}, err
				}
				if checkDBName(db.Name) != nil {
					// Bijvoorbeeld met een streepje, buiten het paneel om gemaakt.
					manifest.Warnings = append(manifest.Warnings, "database "+db.Name+" overgeslagen (naam niet te gebruiken)")
					say("Let op: database %s overgeslagen (de naam kan het paneel niet gebruiken).", db.Name)
					continue
				}
				out, err := writer.CreateHeader(&zip.FileHeader{Name: "databases/" + db.Name + ".sql", Method: zip.Deflate, Modified: time.Now()})
				if err != nil {
					return BackupInfo{}, err
				}
				if err := a.mariadb.dumpTo(db.Name, out); err != nil {
					return BackupInfo{}, fmt.Errorf("database %s: %w", db.Name, err)
				}
				manifest.Databases = append(manifest.Databases, db.Name)
			}
			if len(manifest.Databases) > 0 {
				say("Databases: %s.", strings.Join(manifest.Databases, ", "))
			}
		} else if status.Installed {
			manifest.Warnings = append(manifest.Warnings, "MariaDB draaide niet: geen databases in deze backup")
			say("Let op: MariaDB draait niet; databases overgeslagen.")
		}
	}
	data, _ := json.MarshalIndent(manifest, "", "  ")
	if out, err := writer.Create("manifest.json"); err != nil {
		return BackupInfo{}, err
	} else if _, err := out.Write(data); err != nil {
		return BackupInfo{}, err
	}
	if err := writer.Close(); err != nil {
		return BackupInfo{}, err
	}
	if err := file.Sync(); err != nil {
		return BackupInfo{}, err
	}
	if err := file.Close(); err != nil {
		return BackupInfo{}, err
	}
	finished = true
	final := filepath.Join(dir, name)
	if err := os.Rename(temp, final); err != nil {
		_ = os.Remove(temp)
		return BackupInfo{}, err
	}
	_ = writeSidecar(dir, name, backupSidecar{Manifest: manifest})
	info, _ := os.Stat(final)
	backup := BackupInfo{Name: name, Manifest: manifest}
	if info != nil {
		backup.Size, backup.Modified = info.Size(), info.ModTime().UnixMilli()
	}
	say("Backup klaar: %s (%s).", name, humanBytes(backup.Size))
	return backup, nil
}

// pauseSaving: draait de server, dan via RCON save-off en save-all flush. Geeft terug hoe het
// weer aan gaat.
func (a *App) pauseSaving(say func(string, ...any)) func() {
	if a.process.Status().State != "active" {
		return func() {}
	}
	state := a.server.get()
	// save-on altijd terug, ook als save-off leek te mislukken: Minecraft kan die later alsnog
	// uitvoeren, en dan zou opslaan uit blijven staan (save-on twee keer kan geen kwaad).
	resume := func() {
		if _, err := rconCommand(state.RconPort, state.RconPassword, "save-on", 30*time.Second); err != nil {
			say("Let op: save-on lukte niet (%s). Typ save-on in de console.", err.Error())
		}
	}
	if _, err := rconCommand(state.RconPort, state.RconPassword, "save-off", 30*time.Second); err != nil {
		say("Let op: de server is niet bereikbaar via RCON (%s); de wereld wordt ingepakt zoals hij nu op schijf staat.", err.Error())
		return func() { _, _ = rconCommand(state.RconPort, state.RconPassword, "save-on", 10*time.Second) }
	}
	if _, err := rconCommand(state.RconPort, state.RconPassword, "save-all flush", 3*time.Minute); err != nil {
		say("Let op: save-all lukte niet: %s", err.Error())
	} else {
		say("Wereld opgeslagen; opslaan staat even uit.")
	}
	return resume
}

func excluded(rel string, exclude []string) bool {
	for _, pattern := range exclude {
		if rel == pattern || strings.HasPrefix(rel, pattern+"/") {
			return true
		}
	}
	return false
}

// treeSize: ongeveer hoe groot een map is (zonder de uitgesloten paden).
func treeSize(fr FileRoot, exclude []string) int64 {
	root, err := os.OpenRoot(fr.Base)
	if err != nil {
		return 0
	}
	defer root.Close()
	var total int64
	_ = walkTree(context.Background(), root, ".", exclude, func(rel string, info fs.FileInfo) error {
		if info.Mode().IsRegular() {
			// Wat er echt op schijf staat (een "sparse" bestand kan veel groter lijken).
			if stat, ok := info.Sys().(*syscall.Stat_t); ok {
				total += min(info.Size(), stat.Blocks*512)
			} else {
				total += info.Size()
			}
		}
		return nil
	})
	return total
}

// walkTree loopt door een map binnen een os.Root (geen symlinks, geen fifo's), behalve de
// uitgesloten paden en de tijdelijke bestanden van het paneel.
func walkTree(ctx context.Context, root *os.Root, rel string, exclude []string, visit func(string, fs.FileInfo) error) error {
	var step func(current string, depth int) error
	step = func(current string, depth int) error {
		if depth > maxDepth {
			return errors.New("te diep geneste mappen")
		}
		if err := ctx.Err(); err != nil {
			return err
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
			path := joinPath(current, name)
			if strings.HasPrefix(name, ".pinda-") || excluded(path, exclude) {
				continue
			}
			info, err := root.Lstat(path)
			if err != nil {
				continue
			}
			if err := visit(path, info); err != nil {
				return err
			}
			if info.IsDir() {
				if err := step(path, depth+1); err != nil {
					return err
				}
			}
		}
		return nil
	}
	return step(rel, 0)
}

// zipTree zet een hele map in de zip, onder prefix.
func zipTree(ctx context.Context, fr FileRoot, writer *zip.Writer, prefix string, exclude []string, say func(string, ...any)) (BackupPart, error) {
	var part BackupPart
	root, err := os.OpenRoot(fr.Base)
	if err != nil {
		return part, err
	}
	defer root.Close()
	err = walkTree(ctx, root, ".", exclude, func(rel string, info fs.FileInfo) error {
		switch {
		case info.IsDir():
			_, err := writer.CreateHeader(&zip.FileHeader{Name: prefix + rel + "/", Method: zip.Store, Modified: info.ModTime()})
			return err
		case info.Mode().IsRegular():
			file, current, err := openRegular(root, rel)
			if err != nil {
				return nil // Een hardlink of iets dat net weg is: overslaan.
			}
			defer file.Close()
			method := zip.Deflate
			if storedExtensions[strings.ToLower(filepath.Ext(rel))] {
				method = zip.Store
			}
			header := &zip.FileHeader{Name: prefix + rel, Method: method, Modified: current.ModTime()}
			header.SetMode(current.Mode().Perm())
			out, err := writer.CreateHeader(header)
			if err != nil {
				return err
			}
			// Hooguit de grootte van nu: een bestand dat intussen blijft groeien, maakt de
			// backup niet eindeloos groot.
			written, err := io.Copy(out, io.LimitReader(file, current.Size()))
			if err != nil {
				return fmt.Errorf("%s: %w", rel, err)
			}
			part.Files++
			part.Bytes += written
			if part.Files%2000 == 0 {
				say("  %d bestanden (%s)…", part.Files, humanBytes(part.Bytes))
			}
		}
		return nil
	})
	return part, err
}

// pruneBackups ruimt oude backups van de planning op: van de automatische en van die voor een
// herstart blijven de nieuwste keep staan, van die voor het terugzetten de nieuwste 5. Handmatige,
// geüploade en vastgezette backups blijven altijd.
func (a *App) pruneBackups(keep int, say func(string, ...any)) {
	if keep < 1 {
		return
	}
	list, err := a.listBackups()
	if err != nil {
		return
	}
	limits := map[string]int{"automatisch": keep, "voor-herstart": keep, "voor-herstel": 5}
	counts := map[string]int{}
	for _, backup := range list {
		if backup.Manifest == nil || backup.Uploaded || backup.Pinned {
			continue
		}
		limit, ok := limits[backup.Manifest.Trigger]
		if !ok {
			continue
		}
		counts[backup.Manifest.Trigger]++
		if counts[backup.Manifest.Trigger] > limit {
			if err := a.deleteBackup(backup.Name); err == nil {
				say("Oude backup weggehaald: %s", backup.Name)
			}
		}
	}
}

// cleanupBackupTemp: halve backups en oude mappen van een terugzetting van een vorige keer (het
// paneel stopte midden in een backup, of voordat de oude map helemaal weg was).
func (a *App) cleanupBackupTemp() {
	if entries, err := os.ReadDir(a.backupDir()); err == nil {
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), ".pinda-") && strings.HasSuffix(entry.Name(), ".zip.tmp") {
				_ = os.Remove(filepath.Join(a.backupDir(), entry.Name()))
			}
		}
	}
	config := a.config.get()
	for _, dir := range []string{config.serverDir(), config.websiteDir()} {
		parent, base := filepath.Dir(dir), filepath.Base(dir)
		pattern := regexp.MustCompile(`^\.` + regexp.QuoteMeta(base) + `-oud-[0-9]{8}-[0-9]{6}(-[0-9a-f]+)?$`)
		entries, err := os.ReadDir(parent)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() && pattern.MatchString(entry.Name()) {
				// os.RemoveAll volgt geen symlinks.
				go func(path string) { _ = os.RemoveAll(path) }(filepath.Join(parent, entry.Name()))
			}
		}
	}
}

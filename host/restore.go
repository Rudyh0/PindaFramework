package main

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"time"
)

// RestoreOptions: wat er uit een backup terug moet.
type RestoreOptions struct {
	Server    bool     `json:"server"`
	Website   bool     `json:"website"`
	Databases []string `json:"databases"`
	// Safety: eerst een backup van de huidige stand maken.
	Safety bool `json:"safety"`
	// Start: de server daarna (weer) starten.
	Start bool `json:"start"`
}

// openBackup opent een backup van het paneel (met manifest).
func (a *App) openBackup(name string) (*os.File, *zip.Reader, *BackupManifest, error) {
	path, err := a.backupPath(name)
	if err != nil {
		return nil, nil, nil, errors.New("die backup bestaat niet (meer)")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, nil, nil, err
	}
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, nil, nil, err
	}
	reader, err := openZip(file, info.Size(), maxBackupEntries)
	if err == nil {
		var manifest *BackupManifest
		if manifest, err = manifestFrom(reader); err == nil {
			return file, reader, manifest, nil
		}
	}
	file.Close()
	return nil, nil, nil, err
}

// unpackedSize: hoeveel ruimte de onderdelen onder prefix uitgepakt innemen (volgens de zip).
// Telt op tot hooguit 2^60 (een vreemde zip kan anders "negatief" uitkomen).
func unpackedSize(reader *zip.Reader, prefix string) int64 {
	var total int64
	for _, entry := range reader.File {
		if strings.HasPrefix(entry.Name, prefix) {
			total = addSize(total, int64(min(entry.UncompressedSize64, 1<<60)))
		}
	}
	return total
}

func addSize(a, b int64) int64 {
	if a > 1<<60-b {
		return 1 << 60
	}
	return a + b
}

func freeSpace(path string) int64 {
	var stat syscall.Statfs_t
	if syscall.Statfs(path, &stat) != nil {
		return -1
	}
	return int64(stat.Bavail) * int64(stat.Bsize)
}

// restoreBackup zet een backup terug. De aanroeper heeft serverJob vergrendeld.
func (a *App) restoreBackup(ctx context.Context, name string, options RestoreOptions, by string, say func(string, ...any)) (err error) {
	file, reader, manifest, err := a.openBackup(name)
	if err != nil {
		return err
	}
	defer file.Close()
	if options.Server && manifest.Server == nil {
		return errors.New("in deze backup zit geen servermap")
	}
	if options.Website && manifest.Website == nil {
		return errors.New("in deze backup zit geen website")
	}
	for _, db := range options.Databases {
		if checkDBName(db) != nil || !slices.Contains(manifest.Databases, db) {
			return fmt.Errorf("database %q zit niet in deze backup", db)
		}
	}
	if !options.Server && !options.Website && len(options.Databases) == 0 {
		return errors.New("kies wat je wilt terugzetten")
	}
	if len(options.Databases) > 0 && !a.mariadb.status().Running {
		return errors.New("MariaDB draait niet; databases terugzetten kan nu niet")
	}

	// Past het? De oude map blijft staan tot het terugzetten gelukt is.
	config := a.config.get()
	var needed int64
	if options.Server {
		needed = addSize(needed, unpackedSize(reader, "server/"))
	}
	if options.Website {
		needed = addSize(needed, unpackedSize(reader, "website/"))
	}
	for _, db := range options.Databases {
		size := unpackedSize(reader, "databases/"+db+".sql")
		needed = addSize(needed, addSize(size, size))
	}
	if options.Safety {
		needed = addSize(needed, treeSize(a.serverRoot(), defaultExclude)*7/10)
	}
	if free := freeSpace(config.BaseDir); free >= 0 && free < needed+(512<<20) {
		return fmt.Errorf("niet genoeg ruimte: nodig ongeveer %s, vrij %s. Verwijder oude backups of zet minder tegelijk terug",
			humanBytes(needed+(512<<20)), humanBytes(free))
	}

	// Geen backup tegelijk met terugzetten (wacht als er net een loopt).
	if !a.lockBackup(false) {
		say("Er loopt nog een backup; wachten tot die klaar is…")
		a.lockBackup(true)
	}
	defer a.unlockBackup()

	say("Terugzetten van %s (gemaakt %s).", name, time.UnixMilli(manifest.Created).In(a.schedule.location()).Format("02-01-2006 15:04"))
	if options.Safety {
		say("Eerst een backup van de huidige stand…")
		safety, err := a.createBackupLocked(ctx, BackupOptions{Trigger: "voor-herstel", By: by, Website: options.Website,
			Databases: len(options.Databases) > 0, Exclude: defaultExclude}, say)
		if err != nil {
			return fmt.Errorf("de backup van de huidige stand lukte niet (%w); er is niets teruggezet", err)
		}
		say("Huidige stand bewaard als %s.", safety.Name)
	}

	a.restoring.Store(true)
	defer a.restoring.Store(false)
	state := a.server.get()
	wasRunning := running(a.process.Status().State)
	if options.Server || len(options.Databases) > 0 {
		if err := a.stopAndWait(say); err != nil {
			return err
		}
	}
	// Mislukt er iets nadat de server gestopt is, dan draait hij daarna weer (met wat er nu staat:
	// de oude stand, of wat al wel is teruggezet).
	var done []string
	defer func() {
		if err != nil && len(done) > 0 {
			err = fmt.Errorf("%w. Wel al teruggezet: %s", err, strings.Join(done, ", "))
		}
		if err != nil && wasRunning && state.Installed && !running(a.process.Status().State) {
			if command, commandErr := a.serverCommand(); commandErr == nil && a.process.Start(command) == nil {
				say("De server is weer gestart.")
			}
		}
	}()
	if options.Server {
		say("Servermap terugzetten…")
		result, err := replaceTree(a.serverRoot(), reader, "server/", manifest.Exclude, 0o750, say)
		if err != nil {
			return fmt.Errorf("servermap: %w", err)
		}
		done = append(done, "servermap")
		say("Servermap teruggezet: %d bestanden (%s).", result.Files, humanBytes(result.Bytes))
		a.afterServerRestore(manifest, say)
	}
	if options.Website {
		website, _ := a.fileRoot("website")
		say("Website terugzetten…")
		result, err := replaceTree(website, reader, "website/", nil, 0o755, say)
		if err != nil {
			return fmt.Errorf("website: %w", err)
		}
		done = append(done, "website")
		say("Website teruggezet: %d bestanden.", result.Files)
	}
	for _, db := range options.Databases {
		say("Database %s terugzetten…", db)
		if err := a.restoreDatabase(reader, db, say); err != nil {
			return fmt.Errorf("database %s: %w", db, err)
		}
		done = append(done, "database "+db)
	}
	if options.Start && a.server.get().Installed {
		command, err := a.serverCommand()
		if err != nil {
			return err
		}
		if err := a.process.Start(command); err != nil {
			return fmt.Errorf("teruggezet, maar starten lukte niet: %w", err)
		}
		say("De server start weer.")
	} else if wasRunning {
		say("De server staat uit. Start hem met de knop Starten.")
	}
	say("Klaar.")
	return nil
}

// afterServerRestore: de teruggezette servermap kan van een andere server of een oudere versie
// zijn. RCON, de poort en de versie (met de bijbehorende Java) weer goed zetten.
func (a *App) afterServerRestore(manifest *BackupManifest, say func(string, ...any)) {
	if err := a.ensureProperties(); err != nil {
		say("Let op: server.properties bijwerken lukte niet (%s); de console werkt misschien niet.", err.Error())
	}
	state := a.server.get()
	if manifest.Minecraft == "" || !minecraftVersionPattern.MatchString(manifest.Minecraft) {
		return
	}
	if manifest.Minecraft == state.Version && (manifest.Build == "" || manifest.Build == state.Build) {
		return
	}
	java, javaErr := pickJava(requiredJava(manifest.Minecraft))
	_ = a.server.update(func(s *ServerState) {
		s.Version = manifest.Minecraft
		if manifest.Build != "" && buildPattern.MatchString(manifest.Build) {
			s.Build = manifest.Build
		}
		s.Java, s.JavaMajor = "", 0
		if javaErr == nil {
			s.Java, s.JavaMajor = java.Path, java.Major
		}
	})
	say("De server is nu weer Minecraft %s (zoals in de backup).", manifest.Minecraft)
	if javaErr != nil {
		say("Let op: %s.", javaErr.Error())
	}
}

func running(state string) bool {
	return state == "active" || state == "activating" || state == "deactivating"
}

// stopAndWait stopt de server (als hij draait) en wacht tot hij echt uit is.
func (a *App) stopAndWait(say func(string, ...any)) error {
	if !running(a.process.Status().State) {
		return nil
	}
	say("Server stoppen (de wereld wordt eerst bewaard)…")
	if err := a.process.Stop(); err != nil {
		return fmt.Errorf("stoppen lukte niet: %w", err)
	}
	deadline := time.Now().Add(3 * time.Minute)
	for time.Now().Before(deadline) {
		if !running(a.process.Status().State) {
			say("Server gestopt.")
			return nil
		}
		time.Sleep(time.Second)
	}
	say("Stoppen duurt te lang; de server wordt afgebroken.")
	_ = a.process.Kill()
	for range 10 {
		time.Sleep(time.Second)
		if !running(a.process.Status().State) {
			return nil
		}
	}
	return errors.New("de server wil niet stoppen")
}

// replaceTree zet een map terug uit de zip. De huidige map gaat eerst opzij; lukt het uitpakken
// niet, dan komt die gewoon terug. Wat bewust niet in de backup zat (cache, libraries, …) gaat
// van de oude map mee, zodat Purpur dat niet opnieuw hoeft te downloaden.
func replaceTree(fr FileRoot, reader *zip.Reader, prefix string, keep []string, perm fs.FileMode, say func(string, ...any)) (ExtractResult, error) {
	base := fr.Base
	old := filepath.Join(filepath.Dir(base), "."+filepath.Base(base)+"-oud-"+time.Now().Format("20060102-150405")+"-"+randomToken()[:8])
	hadOld := false
	if _, err := os.Lstat(base); err == nil {
		if err := os.Rename(base, old); err != nil {
			return ExtractResult{}, err
		}
		hadOld = true
	}
	var undoErr error
	undo := func() {
		_ = os.RemoveAll(base)
		if hadOld {
			if err := os.Rename(old, base); err != nil {
				// Bijvoorbeeld als iets de map intussen opnieuw maakte: nog één keer.
				_ = os.RemoveAll(base)
				if err := os.Rename(old, base); err != nil {
					undoErr = fmt.Errorf("de oude map terugzetten lukte niet: hij staat nog in %s", old)
				}
			}
		}
	}
	fail := func(err error) (ExtractResult, error) {
		undo()
		if undoErr != nil {
			return ExtractResult{}, fmt.Errorf("%w; %v", err, undoErr)
		}
		if hadOld {
			return ExtractResult{}, fmt.Errorf("%w (de oude map staat er weer)", err)
		}
		return ExtractResult{}, err
	}
	if err := os.Mkdir(base, perm); err != nil {
		return fail(err)
	}
	if err := os.Chmod(base, perm); err != nil {
		return fail(err)
	}
	if fr.UID >= 0 {
		if err := os.Chown(base, fr.UID, fr.GID); err != nil {
			return fail(err)
		}
	}
	root, err := os.OpenRoot(base)
	if err != nil {
		return fail(err)
	}
	e, err := fr.newExtractor(root, ".", 1<<50, maxBackupEntries, say)
	if err == nil {
		err = e.zip(reader, prefix)
	}
	root.Close()
	if err != nil {
		return fail(err)
	}
	if hadOld {
		moveKept(old, base, keep)
		// De oude map weg (op de achtergrond; dat kan even duren).
		go func() { _ = os.RemoveAll(old) }()
	}
	return e.result, nil
}

// moveKept verhuist de paden die niet in de backup zaten van de oude naar de nieuwe map (als ze
// in de nieuwe nog niet bestaan en de map erboven er wel is). Alles via open mappen zonder
// symlinks te volgen: de oude map is van de minecraft-gebruiker.
func moveKept(oldBase, newBase string, keep []string) {
	oldRoot, err := os.OpenRoot(oldBase)
	if err != nil {
		return
	}
	defer oldRoot.Close()
	newRoot, err := os.OpenRoot(newBase)
	if err != nil {
		return
	}
	defer newRoot.Close()
	for _, path := range keep {
		rel, err := cleanPath(path)
		if err != nil || rel == "." {
			continue
		}
		if _, err := newRoot.Lstat(rel); err == nil {
			continue
		}
		if info, err := oldRoot.Lstat(rel); err != nil || (!info.IsDir() && !info.Mode().IsRegular()) {
			continue
		}
		dir, name := splitPath(rel)
		from, err := openDir(oldRoot, dir)
		if err != nil {
			continue
		}
		to, err := openDir(newRoot, dir)
		if err == nil {
			_ = syscall.Renameat(int(from.Fd()), name, int(to.Fd()), name)
			to.Close()
		}
		from.Close()
	}
}

// restoreDatabase maakt de database leeg en laadt de dump uit de backup in. Lukt dat niet, dan
// komt de vorige inhoud terug.
func (a *App) restoreDatabase(reader *zip.Reader, db string, say func(string, ...any)) error {
	var entry *zip.File
	for _, candidate := range reader.File {
		if candidate.Name == "databases/"+db+".sql" {
			entry = candidate
		}
	}
	if entry == nil {
		return errors.New("niet in de backup")
	}
	dir, err := a.tempDir()
	if err != nil {
		return err
	}
	// De dump komt tijdelijk op schijf, en de huidige inhoud ook: past dat?
	if free := freeSpace(dir); free >= 0 && entry.UncompressedSize64 > uint64(max(0, free-(512<<20)))/2 {
		return fmt.Errorf("niet genoeg ruimte voor deze dump (%s)", humanBytes(int64(min(entry.UncompressedSize64, 1<<60))))
	}
	dump, err := writeTemp(dir, "restore-*.sql", func(out io.Writer) error {
		content, err := entry.Open()
		if err != nil {
			return err
		}
		defer content.Close()
		_, err = io.Copy(out, content)
		return err
	})
	if err != nil {
		return err
	}
	defer os.Remove(dump)

	exists, err := a.mariadb.databaseExists(db)
	if err != nil {
		return err
	}
	previous := ""
	if exists {
		if previous, err = writeTemp(dir, "previous-*.sql", func(out io.Writer) error { return a.mariadb.dumpTo(db, out) }); err != nil {
			return fmt.Errorf("de huidige inhoud bewaren lukte niet (%w); er is niets veranderd", err)
		}
		defer os.Remove(previous)
	}
	if err = a.mariadb.recreateDatabase(db); err == nil {
		err = a.mariadb.importFile(db, dump, dir, say)
	}
	if err == nil {
		return nil
	}
	if previous == "" {
		return err
	}
	say("Inladen mislukt; de vorige inhoud van %s wordt teruggezet…", db)
	if undoErr := a.mariadb.recreateDatabase(db); undoErr != nil {
		return fmt.Errorf("%w; de vorige inhoud terugzetten lukte ook niet: %v", err, undoErr)
	}
	if undoErr := a.mariadb.importFile(db, previous, dir, func(string, ...any) {}); undoErr != nil {
		return fmt.Errorf("%w; de vorige inhoud terugzetten lukte ook niet: %v", err, undoErr)
	}
	return fmt.Errorf("%w (de vorige inhoud staat er weer)", err)
}

// writeTemp schrijft een tijdelijk bestand (alleen voor root) en geeft het pad.
func writeTemp(dir, pattern string, fill func(io.Writer) error) (string, error) {
	file, err := os.CreateTemp(dir, pattern)
	if err != nil {
		return "", err
	}
	fillErr := fill(file)
	closeErr := file.Close()
	if err := errors.Join(fillErr, closeErr); err != nil {
		os.Remove(file.Name())
		return "", err
	}
	return file.Name(), nil
}

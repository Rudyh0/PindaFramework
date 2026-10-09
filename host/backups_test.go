package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"hash/crc32"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// ============================================================ planning

func amsterdam(t *testing.T, value string) time.Time {
	t.Helper()
	location, err := time.LoadLocation("Europe/Amsterdam")
	if err != nil {
		t.Fatal(err)
	}
	when, err := time.ParseInLocation("2006-01-02 15:04", value, location)
	if err != nil {
		t.Fatal(err)
	}
	return when
}

func TestScheduleNext(t *testing.T) {
	s := defaultSchedule()
	format := func(when time.Time) string {
		if when.IsZero() {
			return "-"
		}
		return when.In(s.location()).Format("2006-01-02 15:04 Mon")
	}
	cases := []struct {
		name   string
		change func(*Schedule)
		after  string
		want   string
	}{
		{"zelfde dag", nil, "2026-10-09 03:00", "2026-10-09 04:00 Fri"},
		{"precies op tijd telt niet", nil, "2026-10-09 04:00", "2026-10-10 04:00 Sat"},
		{"meerdere tijden", func(s *Schedule) { s.Backup.Times = []string{"04:00", "16:30"} }, "2026-10-09 12:00", "2026-10-09 16:30 Fri"},
		{"alleen weekend", func(s *Schedule) { s.Backup.Days = []int{6, 7} }, "2026-10-09 12:00", "2026-10-10 04:00 Sat"},
		{"alleen maandag", func(s *Schedule) { s.Backup.Days = []int{1} }, "2026-10-12 05:00", "2026-10-19 04:00 Mon"},
		{"elke 6 uur", func(s *Schedule) { s.Backup.Mode = "interval"; s.Backup.Every = 6 }, "2026-10-09 13:10", "2026-10-09 18:00 Fri"},
		{"elke 12 uur over middernacht", func(s *Schedule) { s.Backup.Mode = "interval"; s.Backup.Every = 12 }, "2026-10-09 23:00", "2026-10-10 00:00 Sat"},
		// Zomertijd voorbij: 02:30 bestaat op 29 maart niet; dan wordt het 03:30.
		{"zomertijd", func(s *Schedule) { s.Backup.Times = []string{"02:30"} }, "2026-03-29 01:00", "2026-03-29 03:30 Sun"},
		{"uit", func(s *Schedule) { s.Backup.Enabled = false }, "2026-10-09 03:00", "-"},
	}
	for _, c := range cases {
		schedule := defaultSchedule()
		if c.change != nil {
			c.change(&schedule)
		}
		if got := format(schedule.nextBackup(amsterdam(t, c.after))); got != c.want {
			t.Errorf("%s: %s, verwacht %s", c.name, got, c.want)
		}
	}
	// Wintertijd: 02:30 komt op 25 oktober twee keer voor; de backup draait maar één keer.
	schedule := defaultSchedule()
	schedule.Backup.Times = []string{"02:30"}
	first := schedule.nextBackup(amsterdam(t, "2026-10-25 01:00"))
	second := schedule.nextBackup(first)
	if format(first) != "2026-10-25 02:30 Sun" || format(second) != "2026-10-26 02:30 Mon" {
		t.Errorf("wintertijd: %s en daarna %s", format(first), format(second))
	}
	// Herstart.
	schedule.Restart.Enabled, schedule.Restart.Time, schedule.Restart.Days = true, "05:15", []int{3}
	if got := format(schedule.nextRestart(amsterdam(t, "2026-10-09 12:00"))); got != "2026-10-14 05:15 Wed" {
		t.Errorf("herstart: %s", got)
	}
}

// De planner: elk moment één keer, ook als de planning in de waarschuwingstijd wordt opgeslagen.
func TestPlanner(t *testing.T) {
	schedule := defaultSchedule()
	schedule.Restart = RestartSchedule{Enabled: true, Time: "05:00", Warn: true}
	plan := newPlanner()
	count := func(from, to string, step time.Duration, version func(time.Time) int) (backups, restarts int) {
		for now := amsterdam(t, from); now.Before(amsterdam(t, to)); now = now.Add(step) {
			backup, restart := plan.tick(schedule, version(now), now)
			if backup {
				backups++
			}
			if !restart.IsZero() {
				restarts++
				if want := amsterdam(t, now.In(schedule.location()).Format("2006-01-02")+" 05:00"); !restart.Equal(want) {
					t.Errorf("herstart voor %v, verwacht %v", restart, want)
				}
				if now.Before(restart.Add(-5*time.Minute)) || now.After(restart) {
					t.Errorf("herstart begint om %v voor %v", now, restart)
				}
			}
		}
		return
	}
	// Drie dagen, elke 15 seconden: 3 backups (04:00) en 3 herstarts (05:00).
	backups, restarts := count("2026-10-09 00:00", "2026-10-12 00:00", 15*time.Second, func(time.Time) int { return 1 })
	if backups != 3 || restarts != 3 {
		t.Errorf("drie dagen: %d backups, %d herstarts", backups, restarts)
	}
	// Elke minuut "opgeslagen" (nieuwe versie), ook tijdens de waarschuwing: toch maar één keer.
	version := 1
	backups, restarts = count("2026-10-12 03:50", "2026-10-12 05:30", 15*time.Second, func(now time.Time) int {
		if now.Second() == 0 {
			version++
		}
		return version
	})
	if backups != 1 || restarts != 1 {
		t.Errorf("met steeds een nieuwe versie: %d backups, %d herstarts", backups, restarts)
	}
}

func TestScheduleNormalize(t *testing.T) {
	good := defaultSchedule()
	good.Backup.Times = []string{"16:00", "04:00", "16:00"}
	good.Backup.Days = []int{7, 1, 1}
	good.Backup.Exclude = []string{" cache ", "/logs", "plugins/dynmap/web/tiles", "", "cache"}
	clean, err := good.normalize()
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(clean.Backup.Times, []string{"04:00", "16:00"}) || !slices.Equal(clean.Backup.Days, []int{1, 7}) ||
		!slices.Equal(clean.Backup.Exclude, []string{"cache", "logs", "plugins/dynmap/web/tiles"}) {
		t.Errorf("opgeschoond: %+v", clean.Backup)
	}
	bad := map[string]func(*Schedule){
		"tijdzone":    func(s *Schedule) { s.Timezone = "Mars/Olympus" },
		"lokale zone": func(s *Schedule) { s.Timezone = "Local" },
		"tijd":        func(s *Schedule) { s.Backup.Times = []string{"24:00"} },
		"geen tijden": func(s *Schedule) { s.Backup.Times = nil },
		"te veel tijden": func(s *Schedule) {
			s.Backup.Times = []string{"01:00", "02:00", "03:00", "04:00", "05:00", "06:00", "07:00"}
		},
		"modus":        func(s *Schedule) { s.Backup.Mode = "weekly" },
		"bewaren":      func(s *Schedule) { s.Backup.Keep = 0 },
		"dag":          func(s *Schedule) { s.Backup.Days = []int{8} },
		"pad":          func(s *Schedule) { s.Backup.Exclude = []string{"../etc"} },
		"hele map":     func(s *Schedule) { s.Backup.Exclude = []string{"/"} },
		"herstarttijd": func(s *Schedule) { s.Restart.Time = "5:00" },
	}
	for name, change := range bad {
		schedule := defaultSchedule()
		change(&schedule)
		if _, err := schedule.normalize(); err == nil {
			t.Errorf("%s: geen fout", name)
		}
	}
	// Elke paar uur: een vreemde waarde wordt 6.
	interval := defaultSchedule()
	interval.Backup.Mode, interval.Backup.Every, interval.Backup.Times = "interval", 5, nil
	if clean, err := interval.normalize(); err != nil || clean.Backup.Every != 6 {
		t.Errorf("interval: %v %d", err, clean.Backup.Every)
	}
}

// ============================================================ maken en terugzetten

// recordingRcon: een RCON-server die bijhoudt welke opdrachten er kwamen.
func recordingRcon(t *testing.T, password string) (int, func() []string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	var mu sync.Mutex
	var commands []string
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				for {
					id, kind, body, err := rconRead(conn)
					if err != nil {
						return
					}
					if kind == rconAuth {
						if body != password {
							id = -1
						}
						rconWrite(conn, id, rconAuthResponse, "")
						continue
					}
					if kind != rconExec {
						rconWrite(conn, id, rconResponseValue, "Unknown request 64")
						continue
					}
					mu.Lock()
					commands = append(commands, body)
					mu.Unlock()
					rconWrite(conn, id, rconResponseValue, "ok")
				}
			}(conn)
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), commands...)
	}
}

func writeFiles(t *testing.T, base string, files map[string]string) {
	t.Helper()
	for name, content := range files {
		path := filepath.Join(base, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func readTree(t *testing.T, base string) map[string]string {
	t.Helper()
	out := map[string]string{}
	_ = filepath.WalkDir(base, func(path string, entry os.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(base, path)
		if entry.Type()&os.ModeSymlink != 0 {
			out[filepath.ToSlash(rel)] = "(symlink)"
			return nil
		}
		data, _ := os.ReadFile(path)
		out[filepath.ToSlash(rel)] = string(data)
		return nil
	})
	return out
}

func zipNames(t *testing.T, path string) []string {
	t.Helper()
	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	var names []string
	for _, file := range reader.File {
		names = append(names, file.Name)
	}
	return names
}

func quiet(string, ...any) {}

func TestBackupCreateAndRestore(t *testing.T) {
	app, process := testApp(t)
	config := app.config.get()
	serverDir, websiteDir := config.serverDir(), config.websiteDir()
	original := map[string]string{
		"server.properties":                 "motd=PindaCraft\n",
		"world/level.dat":                   "wereld-1",
		"world/region/r.0.0.mca":            strings.Repeat("blok", 5000),
		"plugins/PindaFramework.jar":        "jar",
		"plugins/PindaFramework/config.yml": "a: 1\n",
		"cache/mojang_1.jar":                "groot en overbodig",
		"logs/latest.log":                   "log",
		"plugins/dynmap/web/tiles/t.png":    "tegel",
		"plugins/dynmap/config.txt":         "dynmap",
	}
	writeFiles(t, serverDir, original)
	writeFiles(t, websiteDir, map[string]string{"index.html": "<h1>Hoi</h1>", "css/site.css": "body{}"})
	secret := filepath.Join(t.TempDir(), "geheim.txt")
	os.WriteFile(secret, []byte("niet in de backup"), 0o600)
	if err := os.Symlink(secret, filepath.Join(serverDir, "link-naar-buiten")); err != nil {
		t.Fatal(err)
	}

	// De server draait: opslaan gaat even uit via RCON.
	port, commands := recordingRcon(t, "wachtwoord")
	app.server.update(func(s *ServerState) {
		s.Installed, s.Version, s.Build, s.Java, s.RconPort, s.RconPassword = true, "26.1.2", "12", "/usr/bin/java", port, "wachtwoord"
	})
	process.Start(ServerCommand{})

	backup, err := app.createBackup(context.Background(), BackupOptions{Note: "test", By: "rudy", Website: true}, quiet)
	if err != nil {
		t.Fatal(err)
	}
	if got := commands(); !slices.Equal(got, []string{"save-off", "save-all flush", "save-on"}) {
		t.Errorf("RCON-opdrachten: %v", got)
	}
	m := backup.Manifest
	if m.Server == nil || m.Server.Files != 6 || m.Website == nil || m.Website.Files != 2 || m.Minecraft != "26.1.2" || m.Trigger != "handmatig" {
		t.Fatalf("manifest: %+v server %+v", m, m.Server)
	}
	names := zipNames(t, filepath.Join(app.backupDir(), backup.Name))
	for _, name := range names {
		if strings.Contains(name, "cache/") || strings.Contains(name, "logs/") || strings.Contains(name, "tiles") || strings.Contains(name, "link-naar-buiten") {
			t.Errorf("hoort niet in de backup: %s", name)
		}
	}
	for _, want := range []string{"manifest.json", "server/world/level.dat", "server/plugins/dynmap/config.txt", "website/index.html"} {
		if !slices.Contains(names, want) {
			t.Errorf("mist %s in %v", want, names)
		}
	}
	if info, _ := os.Stat(app.backupDir()); info.Mode().Perm() != 0o700 {
		t.Errorf("backupmap is %v", info.Mode().Perm())
	}
	list, _ := app.listBackups()
	if len(list) != 1 || list[0].Manifest == nil || list[0].Manifest.Note != "test" {
		t.Fatalf("lijst: %+v", list)
	}
	// Zonder zijbestand leest de lijst het manifest uit de zip.
	os.Remove(sidecarPath(app.backupDir(), backup.Name))
	if list, _ := app.listBackups(); len(list) != 1 || list[0].Manifest == nil || list[0].Manifest.Server.Files != 6 {
		t.Fatalf("lijst zonder zijbestand: %+v", list)
	}

	// Alles verandert…
	writeFiles(t, serverDir, map[string]string{"world/level.dat": "wereld-2", "nieuw.txt": "weg ermee"})
	os.Remove(filepath.Join(serverDir, "plugins", "PindaFramework.jar"))
	writeFiles(t, websiteDir, map[string]string{"index.html": "<h1>Kapot</h1>"})

	// Intussen is er een andere versie geïnstalleerd.
	app.server.update(func(s *ServerState) { s.Version, s.Build, s.Java = "26.2", "99", "/oud/java" })
	javaInstalls = func() []JavaInstall { return []JavaInstall{{Path: "/opt/java-25/bin/java", Major: 25}} }
	defer func() { javaInstalls = findJavas }()

	// …en gaat terug.
	err = app.restoreBackup(context.Background(), backup.Name, RestoreOptions{Server: true, Website: true, Safety: true, Start: true}, "rudy", quiet)
	if err != nil {
		t.Fatal(err)
	}
	got := readTree(t, serverDir)
	// server.properties krijgt de instellingen van het paneel terug (RCON, poort).
	if props := got["server.properties"]; !strings.HasPrefix(props, "motd=PindaCraft\n") || !strings.Contains(props, "rcon.password=wachtwoord") || !strings.Contains(props, "rcon.port=") {
		t.Errorf("server.properties na terugzetten: %q", props)
	}
	for name, content := range original {
		if name == "server.properties" {
			continue
		}
		if got[name] != content {
			t.Errorf("%s na terugzetten: %q, verwacht %q", name, got[name], content)
		}
	}
	if _, ok := got["nieuw.txt"]; ok {
		t.Error("nieuw.txt staat er nog")
	}
	if _, ok := got["link-naar-buiten"]; ok {
		t.Error("de symlink is meegekomen")
	}
	if site := readTree(t, websiteDir); site["index.html"] != "<h1>Hoi</h1>" || site["css/site.css"] != "body{}" {
		t.Errorf("website: %v", site)
	}
	if process.Status().State != "active" || process.starts != 2 {
		t.Errorf("server na terugzetten: %s, %d keer gestart", process.Status().State, process.starts)
	}
	if state := app.server.get(); state.Version != "26.1.2" || state.Java != "/opt/java-25/bin/java" || state.Build == "99" {
		t.Errorf("versie na terugzetten: %s build %s java %s", state.Version, state.Build, state.Java)
	}
	// Er is een backup van de stand ervoor, met wereld-2 erin.
	list, _ = app.listBackups()
	var safety *BackupInfo
	for i := range list {
		if list[i].Manifest != nil && list[i].Manifest.Trigger == "voor-herstel" {
			safety = &list[i]
		}
	}
	if safety == nil {
		t.Fatalf("geen backup van de stand ervoor: %+v", list)
	}
	_, reader, _, err := app.openBackup(safety.Name)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range reader.File {
		if file.Name == "server/world/level.dat" {
			content, _ := file.Open()
			data, _ := io.ReadAll(content)
			content.Close()
			if string(data) != "wereld-2" {
				t.Errorf("veiligheidsbackup: %q", data)
			}
		}
	}
	// Geen oude mappen achtergelaten (op de achtergrond opgeruimd).
	deadline := time.Now().Add(5 * time.Second)
	for {
		entries, _ := os.ReadDir(config.BaseDir)
		leftover := ""
		for _, entry := range entries {
			if strings.Contains(entry.Name(), "-oud-") {
				leftover = entry.Name()
			}
		}
		if leftover == "" {
			break
		}
		if time.Now().After(deadline) {
			t.Errorf("oude map blijft staan: %s", leftover)
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	// Niet bestaande onderdelen of namen.
	if err := app.restoreBackup(context.Background(), "../../etc/passwd", RestoreOptions{Server: true}, "rudy", quiet); err == nil {
		t.Error("vreemde naam geaccepteerd")
	}
	if err := app.restoreBackup(context.Background(), backup.Name, RestoreOptions{Databases: []string{"mysql"}}, "rudy", quiet); err == nil {
		t.Error("database mysql geaccepteerd")
	}
	if err := app.restoreBackup(context.Background(), backup.Name, RestoreOptions{}, "rudy", quiet); err == nil {
		t.Error("niets gekozen geaccepteerd")
	}
}

// fakeBackup maakt een backup-zip met een eigen manifest en inhoud.
func fakeBackup(t *testing.T, dir, name string, manifest BackupManifest, files map[string]string, badCRC string) {
	t.Helper()
	os.MkdirAll(dir, 0o700)
	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	for entry, content := range files {
		if entry == badCRC {
			header := &zip.FileHeader{Name: entry, Method: zip.Store, CRC32: crc32.ChecksumIEEE([]byte(content)) + 1,
				CompressedSize64: uint64(len(content)), UncompressedSize64: uint64(len(content))}
			out, err := writer.CreateRaw(header)
			if err != nil {
				t.Fatal(err)
			}
			out.Write([]byte(content))
			continue
		}
		out, _ := writer.Create(entry)
		out.Write([]byte(content))
	}
	manifest.Format = backupFormat
	data, _ := json.Marshal(manifest)
	out, _ := writer.Create("manifest.json")
	out.Write(data)
	writer.Close()
	if err := os.WriteFile(filepath.Join(dir, name), buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
}

// Gaat het uitpakken mis, dan staat de oude servermap er gewoon weer.
func TestRestoreRollback(t *testing.T) {
	app, process := testApp(t)
	serverDir := app.config.get().serverDir()
	writeFiles(t, serverDir, map[string]string{"world/level.dat": "huidig", "cache/x": "cache"})
	app.server.update(func(s *ServerState) { s.Installed, s.Java = true, "/usr/bin/java" })
	process.Start(ServerCommand{})
	fakeBackup(t, app.backupDir(), "kapot.zip", BackupManifest{Created: 1, Server: &BackupPart{Files: 2}},
		map[string]string{"server/a.txt": "a", "server/world/level.dat": "uit de backup"}, "server/world/level.dat")
	err := app.restoreBackup(context.Background(), "kapot.zip", RestoreOptions{Server: true}, "rudy", quiet)
	if err == nil {
		t.Fatal("kapotte backup teruggezet")
	}
	got := readTree(t, serverDir)
	if got["world/level.dat"] != "huidig" || got["cache/x"] != "cache" || got["a.txt"] != "" {
		t.Errorf("na mislukken: %v", got)
	}
	// De server draaide en draait weer.
	if process.Status().State != "active" || process.starts != 2 {
		t.Errorf("server na mislukken: %s, %d keer gestart", process.Status().State, process.starts)
	}
	// Een zip zonder manifest is geen backup.
	os.WriteFile(filepath.Join(app.backupDir(), "vreemd.zip"), func() []byte {
		var buf bytes.Buffer
		w := zip.NewWriter(&buf)
		f, _ := w.Create("server/a.txt")
		f.Write([]byte("a"))
		w.Close()
		return buf.Bytes()
	}(), 0o600)
	if err := app.restoreBackup(context.Background(), "vreemd.zip", RestoreOptions{Server: true}, "rudy", quiet); err == nil {
		t.Error("zip zonder manifest teruggezet")
	}
	list, _ := app.listBackups()
	for _, backup := range list {
		if backup.Name == "vreemd.zip" && !backup.Foreign {
			t.Error("vreemde zip niet als vreemd gemarkeerd")
		}
	}
}

// Alleen de oudste automatische backups gaan weg; handmatige, geüploade en vastgezette blijven.
func TestPruneBackups(t *testing.T) {
	app, _ := testApp(t)
	dir := app.backupDir()
	for i, trigger := range []string{"automatisch", "automatisch", "handmatig", "automatisch", "automatisch", "automatisch", "voor-herstart", "voor-herstart", "voor-herstart"} {
		name := "pinda-" + string(rune('a'+i)) + ".zip"
		fakeBackup(t, dir, name, BackupManifest{Created: int64(1000 + i), Trigger: trigger}, nil, "")
	}
	app.updateSidecar("pinda-a.zip", func(s *backupSidecar) { s.Pinned = true })
	app.updateSidecar("pinda-b.zip", func(s *backupSidecar) { s.Uploaded = true })
	app.pruneBackups(2, quiet)
	list, _ := app.listBackups()
	var names []string
	for _, backup := range list {
		names = append(names, backup.Name)
	}
	// Nieuwste eerst: i en h (voor een herstart) en f en e (automatisch) blijven, g en d gaan weg,
	// c is handmatig, b geüpload, a vast.
	if want := []string{"pinda-i.zip", "pinda-h.zip", "pinda-f.zip", "pinda-e.zip", "pinda-c.zip", "pinda-b.zip", "pinda-a.zip"}; !slices.Equal(names, want) {
		t.Errorf("na opruimen: %v, verwacht %v", names, want)
	}
}

// Eén backup tegelijk.
func TestBackupBusy(t *testing.T) {
	app, _ := testApp(t)
	if !app.lockBackup(false) {
		t.Fatal("geen slot")
	}
	if _, err := app.createBackup(context.Background(), BackupOptions{}, quiet); err != errBackupBusy {
		t.Errorf("tweede backup: %v", err)
	}
	app.unlockBackup()
	if _, err := app.createBackup(context.Background(), BackupOptions{}, quiet); err != nil {
		t.Errorf("na vrijgeven: %v", err)
	}
}

// ============================================================ geplande herstart

func TestScheduledRestart(t *testing.T) {
	app, process := testApp(t)
	port, commands := recordingRcon(t, "pw")
	app.server.update(func(s *ServerState) {
		s.Installed, s.Java, s.RconPort, s.RconPassword = true, "/usr/bin/java", port, "pw"
	})
	schedule := defaultSchedule()
	schedule.Restart = RestartSchedule{Enabled: true, Time: "05:00", Warn: true}
	app.schedule.update(func(s *Schedule) { s.Restart = schedule.Restart })

	// Server uit: niets doen.
	app.scheduledRestart(schedule, time.Now())
	if process.starts != 0 || app.schedule.schedule.LastRestart == nil || app.schedule.schedule.LastRestart.OK {
		t.Errorf("herstart terwijl de server uit stond: %d %+v", process.starts, app.schedule.schedule.LastRestart)
	}
	// Server aan, het moment is net voorbij: alleen de laatste waarschuwing nog, dan herstarten.
	process.Start(ServerCommand{})
	app.scheduledRestart(schedule, time.Now().Add(6*time.Second))
	if process.starts != 2 || !app.schedule.schedule.LastRestart.OK {
		t.Errorf("herstart: %d %+v", process.starts, app.schedule.schedule.LastRestart)
	}
	if got := commands(); len(got) != 1 || !strings.Contains(got[0], "10 seconden") {
		t.Errorf("waarschuwingen: %v", got)
	}
	// Intussen uitgezet of verplaatst: niets doen (en ook niets vastleggen).
	before := *app.schedule.schedule.LastRestart
	app.schedule.update(func(s *Schedule) { s.Restart.Time = "06:00" })
	app.scheduledRestart(schedule, time.Now())
	if process.starts != 2 || *app.schedule.schedule.LastRestart != before {
		t.Errorf("verplaatste herstart toch uitgevoerd: %d", process.starts)
	}
	app.schedule.update(func(s *Schedule) { s.Restart.Time = "05:00" })
	// Loopt er iets met de server (terugzetten), dan niet.
	app.lockServerJob()
	app.scheduledRestart(schedule, time.Now())
	app.unlockServerJob()
	if process.starts != 2 || app.schedule.schedule.LastRestart.OK {
		t.Errorf("herstart tijdens terugzetten: %d", process.starts)
	}
}

// ============================================================ API

func waitJob(t *testing.T, c *client, job map[string]any) map[string]any {
	t.Helper()
	id, _ := job["id"].(string)
	if id == "" {
		t.Fatalf("geen taak: %v", job)
	}
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		_, current := c.get("/api/jobs/" + id)
		if current["status"] != "running" {
			return current
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("taak blijft lopen")
	return nil
}

func TestBackupsAPI(t *testing.T) {
	app, _ := testApp(t)
	server := httptest.NewServer(app.routes())
	defer server.Close()
	rudy := loggedIn(t, app, server, "rudy", true)
	lisa := loggedIn(t, app, server, "lisa", false)
	writeFiles(t, app.config.get().serverDir(), map[string]string{"world/level.dat": "wereld"})

	// Een developer maakt een backup.
	status, job := lisa.post("/api/backups", map[string]any{"note": "voor de update\nregel 2", "website": true})
	if status != 200 {
		t.Fatalf("backup maken: %d %v", status, job)
	}
	if done := waitJob(t, lisa, job); done["status"] != "done" {
		t.Fatalf("backup: %v", done)
	}
	status, data := lisa.get("/api/backups")
	backups, _ := data["backups"].([]any)
	if status != 200 || len(backups) != 1 {
		t.Fatalf("lijst: %d %v", status, data)
	}
	first := backups[0].(map[string]any)
	name := first["name"].(string)
	if note := first["manifest"].(map[string]any)["note"]; note != "voor de update regel 2" {
		t.Errorf("notitie: %q", note)
	}
	if data["schedule"] == nil || data["nextBackup"].(float64) == 0 {
		t.Errorf("planning ontbreekt: %v", data)
	}

	// Downloaden (ook een stuk, voor hervatten).
	resp, err := lisa.http.Get(server.URL + "/api/backups/" + name + "/download")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !bytes.HasPrefix(body, []byte("PK")) || !strings.Contains(resp.Header.Get("Content-Disposition"), name) {
		t.Fatalf("download: %d %q", resp.StatusCode, resp.Header.Get("Content-Disposition"))
	}
	req, _ := http.NewRequest("GET", server.URL+"/api/backups/"+name+"/download", nil)
	req.Header.Set("Range", "bytes=0-1")
	resp, err = lisa.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	part, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 206 || string(part) != "PK" {
		t.Errorf("deel downloaden: %d %q", resp.StatusCode, part)
	}
	for _, bad := range []string{"..%2Fconfig.json", ".pinda-x.zip", "bestaat-niet.zip", "x.tar"} {
		if status, _ := lisa.get("/api/backups/" + bad + "/download"); status != 404 {
			t.Errorf("download %s: %d", bad, status)
		}
	}

	// Vastzetten mag een developer; terugzetten, verwijderen en de planning niet.
	if status, _ := lisa.post("/api/backups/"+name+"/pin", map[string]bool{"pinned": true}); status != 200 {
		t.Errorf("vastzetten: %d", status)
	}
	if list, _ := app.listBackups(); !list[0].Pinned {
		t.Error("niet vastgezet")
	}
	for path, payload := range map[string]any{
		"/api/backups/" + name + "/restore": map[string]any{"server": true, "confirm": true},
		"/api/backups/" + name + "/delete":  nil,
		"/api/backups/schedule":             defaultSchedule(),
	} {
		if status, _ := lisa.post(path, payload); status != 403 {
			t.Errorf("developer %s: %d", path, status)
		}
	}

	// Planning: controleren en opslaan.
	schedule := defaultSchedule()
	schedule.Backup.Times = []string{"25:00"}
	if status, _ := rudy.post("/api/backups/schedule", schedule); status != 400 {
		t.Errorf("ongeldige planning: %d", status)
	}
	schedule = defaultSchedule()
	schedule.Restart.Enabled, schedule.Restart.Time = true, "06:30"
	schedule.LastBackup = &ScheduleResult{At: 1, OK: true, Message: "vervalst"}
	if status, result := rudy.post("/api/backups/schedule", schedule); status != 200 || result["nextRestart"].(float64) == 0 {
		t.Errorf("planning opslaan: %d %v", status, result)
	}
	if saved, _ := app.schedule.get(); !saved.Restart.Enabled || saved.LastBackup != nil {
		t.Errorf("opgeslagen planning: %+v", saved)
	}
	if reopened, err := openSchedule(app.schedule.path); err != nil || reopened.schedule.Restart.Time != "06:30" {
		t.Errorf("planning na opnieuw openen: %v", err)
	}

	// Terugzetten zonder bevestiging of keuze niet; met wel.
	if status, _ := rudy.post("/api/backups/"+name+"/restore", map[string]any{"server": true}); status != 400 {
		t.Errorf("zonder bevestiging: %d", status)
	}
	if status, _ := rudy.post("/api/backups/"+name+"/restore", map[string]any{"confirm": true}); status != 400 {
		t.Errorf("zonder keuze: %d", status)
	}
	writeFiles(t, app.config.get().serverDir(), map[string]string{"world/level.dat": "veranderd"})
	status, job = rudy.post("/api/backups/"+name+"/restore", map[string]any{"server": true, "confirm": true, "safety": false})
	if status != 200 {
		t.Fatalf("terugzetten: %d %v", status, job)
	}
	if done := waitJob(t, rudy, job); done["status"] != "done" {
		t.Fatalf("terugzetten: %v", done)
	}
	if data, _ := os.ReadFile(filepath.Join(app.config.get().serverDir(), "world", "level.dat")); string(data) != "wereld" {
		t.Errorf("na terugzetten: %q", data)
	}

	// Uploaden: alleen beheerders, alleen backups van het paneel, nooit over een bestaande heen.
	upload := func(c *client, fileName string, content []byte) (int, map[string]any) {
		status, start := c.post("/api/files/backups/upload", map[string]any{"dir": ".", "name": fileName, "size": len(content)})
		if status != 200 {
			return status, start
		}
		id := start["id"].(string)
		if status, body := c.raw("POST", "/api/files/backups/upload/"+id+"?offset=0", content); status != 200 {
			t.Fatalf("stuk: %d %s", status, body)
		}
		return c.post("/api/files/backups/upload/"+id+"/finish", nil)
	}
	if status, _ := upload(lisa, "x.zip", []byte("PK")); status != 403 {
		t.Errorf("developer uploadt een backup: %d", status)
	}
	for _, bad := range []string{"x.tar", "../x.zip", "map/x.zip", ".x.zip"} {
		if status, _ := rudy.post("/api/files/backups/upload", map[string]any{"dir": ".", "name": bad, "size": 2}); status != 400 {
			t.Errorf("naam %q: %d", bad, status)
		}
	}
	if status, _ := rudy.post("/api/files/backups/upload", map[string]any{"dir": ".", "name": name, "size": 2, "overwrite": true}); status != 409 {
		t.Errorf("over een bestaande backup heen: %d", status)
	}
	var plain bytes.Buffer
	w := zip.NewWriter(&plain)
	f, _ := w.Create("iets.txt")
	f.Write([]byte("geen backup"))
	w.Close()
	if status, _ := upload(rudy, "geen-backup.zip", plain.Bytes()); status != 400 {
		t.Errorf("zip zonder manifest geaccepteerd: %d", status)
	}
	if _, err := os.Stat(filepath.Join(app.backupDir(), "geen-backup.zip")); err == nil {
		t.Error("afgewezen upload blijft staan")
	}
	if status, body := upload(rudy, "van-elders.zip", body); status != 200 {
		t.Fatalf("backup uploaden: %d %v", status, body)
	}
	list, _ := app.listBackups()
	var uploaded *BackupInfo
	for i := range list {
		if list[i].Name == "van-elders.zip" {
			uploaded = &list[i]
		}
	}
	if uploaded == nil || !uploaded.Uploaded || uploaded.Manifest == nil {
		t.Fatalf("geüploade backup: %+v", uploaded)
	}
	if info, _ := os.Stat(filepath.Join(app.backupDir(), "van-elders.zip")); info.Mode().Perm() != 0o600 {
		t.Errorf("rechten van de upload: %v", info.Mode().Perm())
	}
	// De backups zijn geen gewone bestandsmap.
	for _, path := range []string{"/api/files/backups/list", "/api/files/backups/read?path=van-elders.zip", "/api/files/backups/download?path=van-elders.zip"} {
		if status, _ := rudy.get(path); status != 404 {
			t.Errorf("%s: %d", path, status)
		}
	}

	// Verwijderen.
	if status, _ := rudy.post("/api/backups/van-elders.zip/delete", nil); status != 200 {
		t.Errorf("verwijderen: %d", status)
	}
	if _, err := os.Stat(sidecarPath(app.backupDir(), "van-elders.zip")); err == nil {
		t.Error("zijbestand blijft staan")
	}
}

// Databases in de backup: dumpen, leegmaken, terugzetten; en bij een kapotte dump komt de oude
// inhoud terug.
func TestBackupDatabases(t *testing.T) {
	app, _ := testApp(t)
	if status := app.mariadb.status(); !status.Running {
		t.Skip("geen MariaDB beschikbaar")
	}
	const db = "pinda_backup_test"
	if exists, _ := app.mariadb.databaseExists(db); exists {
		t.Skip("database bestaat al")
	}
	if err := app.mariadb.createDatabase(db); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { app.mariadb.dropDatabase(db) })
	if _, err := app.mariadb.run("CREATE TABLE spelers (naam VARCHAR(32) PRIMARY KEY, geld INT); INSERT INTO spelers VALUES ('Rudy', 100), ('Lisa', 50);", db); err != nil {
		t.Fatal(err)
	}
	money := func() string {
		rows, err := app.mariadb.run("SELECT COALESCE(SUM(geld), -1) FROM spelers;", db)
		if err != nil {
			return "fout: " + err.Error()
		}
		return rows[0][0]
	}
	backup, err := app.createBackup(context.Background(), BackupOptions{Databases: true}, quiet)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(backup.Manifest.Databases, db) {
		t.Fatalf("database niet in de backup: %v", backup.Manifest.Databases)
	}
	app.mariadb.run("UPDATE spelers SET geld = 0; INSERT INTO spelers VALUES ('Nieuw', 7);", db)
	if err := app.restoreBackup(context.Background(), backup.Name, RestoreOptions{Databases: []string{db}}, "rudy", quiet); err != nil {
		t.Fatal(err)
	}
	if got := money(); got != "150" {
		t.Errorf("na terugzetten: %s", got)
	}

	// Een backup met een kapotte dump: de huidige inhoud blijft.
	app.mariadb.run("UPDATE spelers SET geld = 1;", db)
	fakeBackup(t, app.backupDir(), "kapotte-dump.zip", BackupManifest{Created: 5, Databases: []string{db}},
		map[string]string{"databases/" + db + ".sql": "CREATE TABLE a (x INT);\nDIT IS GEEN SQL;\n"}, "")
	if err := app.restoreBackup(context.Background(), "kapotte-dump.zip", RestoreOptions{Databases: []string{db}}, "rudy", quiet); err == nil {
		t.Error("kapotte dump ingeladen")
	}
	if got := money(); got != "2" {
		t.Errorf("na mislukte dump: %s (verwacht de oude inhoud, 2)", got)
	}
}

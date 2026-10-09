package main

import (
	"context"
	"fmt"
	"mime"
	"net/http"
	"os"
	"strings"
	"syscall"
	"time"
	"unicode"
)

func (a *App) registerBackups(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/backups", a.api(member, a.handleBackups))
	mux.HandleFunc("POST /api/backups", a.api(member, a.handleBackupCreate))
	mux.HandleFunc("GET /api/backups/{name}/download", a.api(member, a.handleBackupDownload))
	mux.HandleFunc("POST /api/backups/{name}/pin", a.api(member, a.handleBackupPin))
	mux.HandleFunc("POST /api/backups/{name}/restore", a.api(admin, a.handleBackupRestore))
	mux.HandleFunc("POST /api/backups/{name}/delete", a.api(admin, a.handleBackupDelete))
	mux.HandleFunc("POST /api/backups/schedule", a.api(admin, a.handleBackupSchedule))
}

var errBackupBusyAPI = &apiError{http.StatusConflict, "Er loopt al een backup of terugzetten. Wacht tot die klaar is."}

func millis(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}

func (a *App) handleBackups(q *Request) (any, error) {
	list, err := a.listBackups()
	if err != nil {
		return nil, err
	}
	var total int64
	for _, backup := range list {
		total += backup.Size
	}
	schedule, _ := a.schedule.get()
	now := time.Now()
	disk := map[string]int64{"free": -1, "total": -1}
	var stat syscall.Statfs_t
	if syscall.Statfs(a.backupDir(), &stat) == nil {
		disk["free"] = int64(stat.Bavail) * int64(stat.Bsize)
		disk["total"] = int64(stat.Blocks) * int64(stat.Bsize)
	}
	state := a.server.get()
	db := a.mariadb.status()
	return map[string]any{
		"backups": list, "total": total, "disk": disk, "schedule": schedule,
		"nextBackup": millis(schedule.nextBackup(now)), "nextRestart": millis(schedule.nextRestart(now)),
		"busy": a.backupRunning.Load(), "serverBusy": a.serverBusy(),
		"server":  map[string]any{"installed": state.Installed, "state": a.process.Status().State, "version": state.Version},
		"mariadb": db.Running, "mariadbInstalled": db.Installed, "website": a.config.get().SiteDomain != "",
		"defaultExclude": defaultExclude,
	}, nil
}

// cleanNote: een korte notitie op één regel.
func cleanNote(note string) string {
	note = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, note)
	return clip(strings.TrimSpace(note), 200)
}

func (a *App) handleBackupCreate(q *Request) (any, error) {
	var body struct {
		Note      string `json:"note"`
		Website   bool   `json:"website"`
		Databases bool   `json:"databases"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if a.backupRunning.Load() {
		return nil, errBackupBusyAPI
	}
	if a.serverBusy() {
		return nil, errServerBusy
	}
	schedule, _ := a.schedule.get()
	options := BackupOptions{Trigger: "handmatig", Note: cleanNote(body.Note), By: q.user.Name, Website: body.Website,
		Databases: body.Databases, Exclude: schedule.Backup.Exclude}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start("Backup maken", user, func(say func(string, ...any)) error {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Hour)
		defer cancel()
		backup, err := a.createBackup(ctx, options, say)
		if err != nil {
			a.audit.add(user, ip, "backup mislukt", "", err.Error())
			return err
		}
		a.audit.add(user, ip, "backup gemaakt", backup.Name, humanBytes(backup.Size))
		return nil
	})
	return job, nil
}

func (a *App) backupParam(q *Request) (string, error) {
	name := q.r.PathValue("name")
	if _, err := a.backupPath(name); err != nil {
		return "", notFound("Die backup bestaat niet (meer).")
	}
	return name, nil
}

func (a *App) handleBackupDownload(q *Request) (any, error) {
	name, err := a.backupParam(q)
	if err != nil {
		return nil, err
	}
	path, _ := a.backupPath(name)
	file, err := os.Open(path)
	if err != nil {
		return nil, notFound("Die backup bestaat niet (meer).")
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return nil, notFound("Die backup bestaat niet (meer).")
	}
	// Alleen het begin van een download in het logboek (niet elk stuk van een hervatte download).
	if q.r.Header.Get("Range") == "" {
		q.log("backup gedownload", name, humanBytes(info.Size()))
	}
	q.w.Header().Set("Content-Type", "application/zip")
	q.w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	http.ServeContent(q.w, q.r, "", info.ModTime(), file)
	return nil, nil
}

func (a *App) handleBackupPin(q *Request) (any, error) {
	name, err := a.backupParam(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Pinned bool `json:"pinned"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if err := a.updateSidecar(name, func(s *backupSidecar) { s.Pinned = body.Pinned }); err != nil {
		return nil, fileError(err)
	}
	q.log(map[bool]string{true: "backup vastgezet", false: "backup losgemaakt"}[body.Pinned], name, "")
	return map[string]bool{"ok": true}, nil
}

func (a *App) handleBackupDelete(q *Request) (any, error) {
	name, err := a.backupParam(q)
	if err != nil {
		return nil, err
	}
	if a.backupRunning.Load() {
		return nil, errBackupBusyAPI
	}
	if err := a.deleteBackup(name); err != nil {
		return nil, fileError(err)
	}
	q.log("backup verwijderd", name, "")
	return map[string]bool{"ok": true}, nil
}

func (a *App) handleBackupRestore(q *Request) (any, error) {
	name, err := a.backupParam(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		RestoreOptions
		Confirm bool `json:"confirm"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if !body.Confirm {
		return nil, badRequest("Bevestig dat je de huidige stand wilt vervangen.")
	}
	options := body.RestoreOptions
	if !options.Server && !options.Website && len(options.Databases) == 0 {
		return nil, badRequest("Kies wat je wilt terugzetten.")
	}
	if len(options.Databases) > 0 && !a.mariadb.status().Running {
		return nil, badRequest("MariaDB draait niet; databases terugzetten kan nu niet.")
	}
	file, _, manifest, err := a.openBackup(name)
	if err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	file.Close()
	if options.Server && manifest.Server == nil {
		return nil, badRequest("In deze backup zit geen servermap.")
	}
	if options.Website && manifest.Website == nil {
		return nil, badRequest("In deze backup zit geen website.")
	}
	if !a.lockServerJob() {
		return nil, errServerBusy
	}
	user, ip := q.user.Name, q.ip
	parts := restoreParts(options)
	job := a.jobs.start("Backup terugzetten", user, func(say func(string, ...any)) error {
		defer a.unlockServerJob()
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Hour)
		defer cancel()
		if err := a.restoreBackup(ctx, name, options, user, say); err != nil {
			a.audit.add(user, ip, "backup terugzetten mislukt", name, clip(parts+": "+err.Error(), 300))
			return err
		}
		a.audit.add(user, ip, "backup teruggezet", name, parts)
		return nil
	})
	return job, nil
}

func restoreParts(options RestoreOptions) string {
	var parts []string
	if options.Server {
		parts = append(parts, "servermap")
	}
	if options.Website {
		parts = append(parts, "website")
	}
	for _, db := range options.Databases {
		parts = append(parts, "database "+db)
	}
	return strings.Join(parts, ", ")
}

func (a *App) handleBackupSchedule(q *Request) (any, error) {
	var body Schedule
	if err := q.body(&body); err != nil {
		return nil, err
	}
	clean, err := body.normalize()
	if err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	before, _ := a.schedule.get()
	if _, err := a.schedule.update(func(s *Schedule) {
		s.Timezone, s.Backup, s.Restart = clean.Timezone, clean.Backup, clean.Restart
	}); err != nil {
		return nil, err
	}
	q.log("planning aangepast", "backups en herstart", scheduleSummary(before, clean))
	return a.handleBackups(q)
}

// scheduleSummary: wat er veranderde, voor het logboek.
func scheduleSummary(before, after Schedule) string {
	describe := func(s Schedule) (string, string) {
		backup := "backups uit"
		if s.Backup.Enabled {
			if s.Backup.Mode == "interval" {
				backup = fmt.Sprintf("backups elke %d uur", s.Backup.Every)
			} else {
				backup = "backups om " + strings.Join(s.Backup.Times, ", ")
			}
			backup += fmt.Sprintf(" (bewaar %d)", s.Backup.Keep)
		}
		restart := "herstart uit"
		if s.Restart.Enabled {
			restart = "herstart om " + s.Restart.Time
		}
		return backup, restart
	}
	b1, r1 := describe(before)
	b2, r2 := describe(after)
	var changes []string
	if b1 != b2 {
		changes = append(changes, b2)
	}
	if r1 != r2 {
		changes = append(changes, r2)
	}
	if before.Timezone != after.Timezone {
		changes = append(changes, "tijdzone "+after.Timezone)
	}
	if len(changes) == 0 {
		return "details"
	}
	return strings.Join(changes, "; ")
}

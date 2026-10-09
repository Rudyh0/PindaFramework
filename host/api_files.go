package main

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// fileRoot: de map achter /api/files/{root}/...: "server" (de Minecraft-server) of "website".
func (a *App) fileRoot(name string) (FileRoot, error) {
	config := a.config.get()
	switch name {
	case "server":
		uid, gid := lookupOwner(config.ServiceUser)
		return FileRoot{Name: name, Base: config.serverDir(), UID: uid, GID: gid}, nil
	case "website":
		return FileRoot{Name: name, Base: config.websiteDir(), UID: -1, GID: -1}, nil
	case "backups":
		// Alleen om backups te uploaden (zie uploadRootFor).
		return FileRoot{Name: name, Base: a.backupDir(), UID: -1, GID: -1}, nil
	}
	return FileRoot{}, notFound("Onbekende map.")
}

func (a *App) registerFiles(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/files/{root}/list", a.api(member, a.handleFilesList))
	mux.HandleFunc("GET /api/files/{root}/read", a.api(member, a.handleFilesRead))
	mux.HandleFunc("POST /api/files/{root}/write", a.api(member, a.handleFilesWrite))
	mux.HandleFunc("POST /api/files/{root}/create", a.api(member, a.handleFilesCreate))
	mux.HandleFunc("POST /api/files/{root}/rename", a.api(member, a.handleFilesRename))
	mux.HandleFunc("POST /api/files/{root}/delete", a.api(member, a.handleFilesDelete))
	mux.HandleFunc("POST /api/files/{root}/archive", a.api(member, a.handleFilesArchive))
	mux.HandleFunc("POST /api/files/{root}/extract", a.api(member, a.handleFilesExtract))
	mux.HandleFunc("POST /api/files/{root}/upload", a.api(member, a.handleUploadStart))
	mux.HandleFunc("POST /api/files/{root}/upload/{id}", a.api(member, a.handleUploadChunk))
	mux.HandleFunc("POST /api/files/{root}/upload/{id}/finish", a.api(member, a.handleUploadFinish))
	mux.HandleFunc("POST /api/files/{root}/upload/{id}/cancel", a.api(member, a.handleUploadCancel))
	mux.HandleFunc("GET /api/files/{root}/download", a.api(member, a.handleFilesDownload))
}

// fileError maakt van een fout van het bestandssysteem een nette melding.
func fileError(err error) error {
	var api *apiError
	switch {
	case err == nil:
		return nil
	case errors.As(err, &api):
		return err
	case errors.Is(err, os.ErrNotExist):
		return notFound("Dat bestand of die map bestaat niet (meer).")
	case errors.Is(err, os.ErrExist), errors.Is(err, errExists):
		return &apiError{http.StatusConflict, "Er bestaat al iets met die naam."}
	case errors.Is(err, errChangedOnDisk):
		return &apiError{http.StatusConflict, capitalize(err.Error()) + ". Laad het opnieuw of sla toch op."}
	case errors.Is(err, os.ErrPermission):
		return forbidden("Daar mag het paneel niet bij.")
	case errors.Is(err, syscall.ENOSPC):
		return badRequest("De schijf is vol.")
	case errors.Is(err, syscall.ENOTEMPTY):
		return badRequest("Die map is niet leeg.")
	case errors.Is(err, syscall.EINVAL):
		return badRequest("Dat kan niet (bijvoorbeeld een map in zichzelf verplaatsen).")
	case strings.Contains(err.Error(), "escapes from parent"), strings.Contains(err.Error(), "path escapes"):
		return forbidden("Dat pad wijst buiten de map (bijvoorbeeld via een symlink).")
	case errors.Is(err, errNotDir), errors.Is(err, errNotFile), errors.Is(err, errLinkWrite), errors.Is(err, errHardlink), errors.Is(err, errRootPath):
		return badRequest("%s", capitalize(err.Error())+".")
	}
	return badRequest("%s", capitalize(err.Error()))
}

// pathParam haalt een veilig pad uit de query (?path=...).
func pathParam(q *Request, name string) (string, error) {
	rel, err := cleanPath(q.r.URL.Query().Get(name))
	if err != nil {
		return "", badRequest("Ongeldig pad.")
	}
	return rel, nil
}

func (a *App) rootFor(q *Request) (FileRoot, error) {
	if q.r.PathValue("root") == "backups" {
		return FileRoot{}, notFound("Onbekende map.")
	}
	if a.restoring.Load() {
		return FileRoot{}, &apiError{http.StatusConflict, "Er wordt een backup teruggezet. Probeer het zo weer."}
	}
	return a.fileRoot(q.r.PathValue("root"))
}

// uploadRootFor: zoals rootFor, maar uploaden kan ook naar de backups (alleen beheerders).
func (a *App) uploadRootFor(q *Request) (FileRoot, error) {
	if q.r.PathValue("root") != "backups" {
		return a.rootFor(q)
	}
	if !q.user.Admin {
		return FileRoot{}, forbidden("Alleen beheerders kunnen backups uploaden.")
	}
	if _, err := a.ensureBackupDir(); err != nil {
		return FileRoot{}, err
	}
	return a.fileRoot("backups")
}

func (a *App) handleFilesList(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	rel, err := pathParam(q, "path")
	if err != nil {
		return nil, err
	}
	entries, truncated, err := root.List(rel)
	if err != nil {
		return nil, fileError(err)
	}
	return map[string]any{"path": rel, "entries": entries, "truncated": truncated, "free": root.Free()}, nil
}

func (a *App) handleFilesRead(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	rel, err := pathParam(q, "path")
	if err != nil {
		return nil, err
	}
	file, err := root.ReadText(rel)
	if err != nil {
		return nil, fileError(err)
	}
	return file, nil
}

func (a *App) handleFilesWrite(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Path     string `json:"path"`
		Content  string `json:"content"`
		Modified int64  `json:"modified"`
	}
	if err := q.bodyMax(&body, maxEditSize*2+4096); err != nil {
		return nil, err
	}
	if len(body.Content) > maxEditSize {
		return nil, badRequest("Dat is te groot voor de editor (maximaal 5 MB). Upload het bestand liever.")
	}
	rel, err := cleanPath(body.Path)
	if err != nil {
		return nil, badRequest("Ongeldig pad.")
	}
	if _, err := root.WriteFile(rel, strings.NewReader(body.Content), body.Modified); err != nil {
		return nil, fileError(err)
	}
	q.log("bestand opgeslagen", root.Name+": "+rel, humanBytes(int64(len(body.Content))))
	info, _ := root.Stat(rel)
	return map[string]any{"ok": true, "modified": modifiedMillis(info), "size": len(body.Content)}, nil
}

func (a *App) handleFilesCreate(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Path string `json:"path"`
		Dir  bool   `json:"dir"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	rel, err := cleanPath(body.Path)
	if err != nil {
		return nil, badRequest("Ongeldige naam.")
	}
	if err := root.Create(rel, body.Dir); err != nil {
		return nil, fileError(err)
	}
	q.log(map[bool]string{true: "map aangemaakt", false: "bestand aangemaakt"}[body.Dir], root.Name+": "+rel, "")
	return map[string]bool{"ok": true}, nil
}

func (a *App) handleFilesRename(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		From string `json:"from"`
		To   string `json:"to"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	from, err1 := cleanPath(body.From)
	to, err2 := cleanPath(body.To)
	if err1 != nil || err2 != nil {
		return nil, badRequest("Ongeldige naam of map.")
	}
	if err := root.Rename(from, to); err != nil {
		return nil, fileError(err)
	}
	q.log("verplaatst", root.Name+": "+from, "naar "+to)
	return map[string]bool{"ok": true}, nil
}

func (a *App) handleFilesDelete(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Paths []string `json:"paths"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if len(body.Paths) == 0 || len(body.Paths) > 1000 {
		return nil, badRequest("Kies wat je wilt verwijderen.")
	}
	removed := 0
	for _, p := range body.Paths {
		rel, err := cleanPath(p)
		if err != nil {
			return nil, badRequest("Ongeldig pad.")
		}
		count, err := root.Delete(rel)
		removed += count
		if err != nil {
			return nil, fileError(err)
		}
		q.log("verwijderd", root.Name+": "+rel, fmt.Sprintf("%d items", count))
	}
	return map[string]int{"removed": removed}, nil
}

func (a *App) handleFilesArchive(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Dir   string   `json:"dir"`
		Names []string `json:"names"`
		Name  string   `json:"name"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	dir, err := cleanPath(body.Dir)
	if err != nil {
		return nil, badRequest("Ongeldige map.")
	}
	archiveName, err := cleanName(body.Name)
	if err != nil {
		return nil, badRequest("Ongeldige naam voor het archief.")
	}
	if !strings.HasSuffix(strings.ToLower(archiveName), ".zip") {
		archiveName += ".zip"
	}
	if len(body.Names) == 0 {
		return nil, badRequest("Kies wat je wilt inpakken.")
	}
	var names []string
	for _, name := range body.Names {
		clean, err := cleanName(name)
		if err != nil {
			return nil, badRequest("Ongeldige naam.")
		}
		names = append(names, clean)
	}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start("Inpakken: "+archiveName, user, func(say func(string, ...any)) error {
		say("%d %s inpakken naar %s…", len(names), map[bool]string{true: "item", false: "items"}[len(names) == 1], archiveName)
		count, err := root.Archive(dir, names, archiveName)
		if err != nil {
			return fileError(err)
		}
		say("Klaar: %d bestanden in %s.", count, archiveName)
		a.audit.add(user, ip, "ingepakt", root.Name+": "+joinPath(dir, archiveName), fmt.Sprintf("%d bestanden", count))
		return nil
	})
	return job, nil
}

func (a *App) handleFilesExtract(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Path string `json:"path"`
		Dest string `json:"dest"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	rel, err1 := cleanPath(body.Path)
	dest, err2 := cleanPath(body.Dest)
	if err1 != nil || err2 != nil {
		return nil, badRequest("Ongeldig pad.")
	}
	if archiveKind(rel) == "" {
		return nil, badRequest("Alleen .zip, .tar en .tar.gz kunnen worden uitgepakt.")
	}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start("Uitpakken: "+path.Base(rel), user, func(say func(string, ...any)) error {
		say("%s uitpakken…", path.Base(rel))
		result, err := root.Extract(rel, dest, say)
		if err != nil {
			say("Gestopt na %d bestanden.", result.Files)
			return fileError(err)
		}
		say("Klaar: %d bestanden (%s).", result.Files, humanBytes(result.Bytes))
		if result.Skipped > 0 {
			say("%d onderdelen overgeslagen (symlinks, paden buiten de map of mappen met dezelfde naam als een bestand).", result.Skipped)
		}
		a.audit.add(user, ip, "uitgepakt", root.Name+": "+rel, fmt.Sprintf("%d bestanden", result.Files))
		return nil
	})
	return job, nil
}

func (a *App) handleFilesDownload(q *Request) (any, error) {
	root, err := a.rootFor(q)
	if err != nil {
		return nil, err
	}
	rel, err := pathParam(q, "path")
	if err != nil {
		return nil, err
	}
	info, err := root.Stat(rel)
	if err != nil {
		return nil, fileError(err)
	}
	name := path.Base(rel)
	if rel == "." {
		name = root.Name
	}
	if info.IsDir() {
		q.w.Header().Set("Content-Type", "application/zip")
		q.w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name + ".zip"}))
		q.log("map gedownload", root.Name+": "+rel, "als zip")
		if err := root.ZipTo(rel, q.w); err != nil {
			// De download is al begonnen; afbreken is het enige wat kan.
			panic(http.ErrAbortHandler)
		}
		return nil, nil
	}
	file, info, err := root.Open(rel)
	if err != nil {
		return nil, fileError(err)
	}
	defer file.Close()
	q.w.Header().Set("Content-Type", "application/octet-stream")
	q.w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	q.w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(q.w, q.r, "", info.ModTime(), file)
	return nil, nil
}

// ============================================================ uploaden in stukken

// Uploads in stukken van hooguit 16 MB (Cloudflare laat maximaal 100 MB per verzoek toe). Het
// bestand komt eerst als verborgen tijdelijk bestand in de doelmap en krijgt pas aan het eind
// zijn naam. Het paneel houdt dat bestand open en schrijft alleen via die open verwijzing: wat
// de minecraft-gebruiker intussen met de naam doet (een hardlink, een fifo), raakt het niet.
const (
	uploadChunk = 16 << 20
	maxUpload   = 20 << 30
)

type upload struct {
	mu        sync.Mutex
	ID        string
	Root      string
	Dir       string
	Name      string
	Temp      string
	Size      int64
	Written   int64
	User      string
	Overwrite bool
	Updated   time.Time
	file      *os.File
	inode     uint64
	closed    bool
}

// close sluit het tijdelijke bestand en haalt het weg (als het nog ons bestand is).
func (up *upload) discard(root FileRoot) {
	if up.closed {
		return
	}
	up.closed = true
	if up.file != nil {
		up.file.Close()
	}
	if info, err := root.Stat(joinPath(up.Dir, up.Temp)); err == nil && inodeOf(info) == up.inode {
		_, _ = root.Delete(joinPath(up.Dir, up.Temp))
	}
}

type Uploads struct {
	mu   sync.Mutex
	list map[string]*upload
}

func newUploads(a *App) *Uploads {
	uploads := &Uploads{list: map[string]*upload{}}
	go func() {
		for range time.Tick(10 * time.Minute) {
			uploads.expire(a)
		}
	}()
	return uploads
}

// expire ruimt uploads op waar al een uur niets mee gebeurt (en die nu niet bezig zijn).
func (u *Uploads) expire(a *App) {
	u.mu.Lock()
	var old []*upload
	for id, up := range u.list {
		if !up.mu.TryLock() {
			continue
		}
		if time.Since(up.Updated) > time.Hour {
			old = append(old, up)
			delete(u.list, id)
		} else {
			up.mu.Unlock()
		}
	}
	u.mu.Unlock()
	for _, up := range old {
		if root, err := a.fileRoot(up.Root); err == nil {
			up.discard(root)
		}
		up.mu.Unlock()
	}
}

func (u *Uploads) get(id, rootName, user string) *upload {
	u.mu.Lock()
	defer u.mu.Unlock()
	up := u.list[id]
	if up == nil || up.Root != rootName || !strings.EqualFold(up.User, user) {
		return nil
	}
	return up
}

func (u *Uploads) remove(id string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	delete(u.list, id)
}

func (a *App) handleUploadStart(q *Request) (any, error) {
	root, err := a.uploadRootFor(q)
	if err != nil {
		return nil, err
	}
	var body struct {
		Dir       string `json:"dir"`
		Name      string `json:"name"`
		Size      int64  `json:"size"`
		Overwrite bool   `json:"overwrite"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	dir, err := cleanPath(body.Dir)
	if err != nil {
		return nil, badRequest("Ongeldige map.")
	}
	name, err := cleanName(body.Name)
	if err != nil || strings.HasPrefix(name, ".pinda-") {
		return nil, badRequest("Ongeldige bestandsnaam.")
	}
	if body.Size < 0 || body.Size > maxUpload {
		return nil, badRequest("Een bestand is hooguit %s.", humanBytes(maxUpload))
	}
	if root.Name == "backups" {
		// Een backup: alleen een .zip, direct in de map, en nooit over een bestaande heen.
		if dir != "." || !backupNamePattern.MatchString(name) || strings.HasPrefix(name, ".") {
			return nil, badRequest("Een backup is een .zip-bestand met een eenvoudige naam (letters, cijfers, . - _).")
		}
		if _, err := root.Stat(name); err == nil {
			return nil, &apiError{http.StatusConflict, "Er is al een backup met de naam " + name + "."}
		}
		body.Overwrite = false
	}
	if free := root.Free(); free >= 0 && body.Size > free-(256<<20) {
		return nil, badRequest("Daar is niet genoeg ruimte voor op de schijf (nog %s vrij).", humanBytes(free))
	}
	if info, err := root.Stat(joinPath(dir, name)); err == nil {
		if info.IsDir() {
			return nil, &apiError{http.StatusConflict, "Er is al een map met de naam " + name + "."}
		}
		if !body.Overwrite {
			return map[string]any{"exists": true}, nil
		}
	}
	r, err := root.open()
	if err != nil {
		return nil, err
	}
	defer r.Close()
	parent, err := openDir(r, dir)
	if err != nil {
		return nil, fileError(err)
	}
	parent.Close()
	id := randomToken()[:24]
	temp := ".pinda-upload-" + id[:12]
	file, err := r.OpenFile(joinPath(dir, temp), os.O_RDWR|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return nil, fileError(err)
	}
	info, err := file.Stat()
	if err == nil {
		err = root.chown(file)
	}
	if err == nil {
		err = file.Chmod(map[bool]fs.FileMode{true: 0o600, false: 0o644}[root.Name == "backups"])
	}
	if err != nil {
		file.Close()
		_ = r.Remove(joinPath(dir, temp))
		return nil, fileError(err)
	}
	up := &upload{ID: id, Root: root.Name, Dir: dir, Name: name, Temp: temp, Size: body.Size, User: q.user.Name,
		Overwrite: body.Overwrite, Updated: time.Now(), file: file, inode: inodeOf(info)}
	a.uploads.mu.Lock()
	a.uploads.list[id] = up
	a.uploads.mu.Unlock()
	return map[string]any{"id": id, "chunk": uploadChunk}, nil
}

func (a *App) handleUploadChunk(q *Request) (any, error) {
	root, err := a.uploadRootFor(q)
	if err != nil {
		return nil, err
	}
	up := a.uploads.get(q.r.PathValue("id"), root.Name, q.user.Name)
	if up == nil {
		return nil, notFound("Deze upload bestaat niet (meer). Begin opnieuw.")
	}
	up.mu.Lock()
	defer up.mu.Unlock()
	if up.closed {
		return nil, notFound("Deze upload bestaat niet (meer). Begin opnieuw.")
	}
	offset, err := strconv.ParseInt(q.r.URL.Query().Get("offset"), 10, 64)
	if err != nil || offset != up.Written {
		return nil, &apiError{http.StatusConflict, fmt.Sprintf("Verkeerd stuk (verwacht vanaf %d).", up.Written)}
	}
	room := up.Size - up.Written
	if room > uploadChunk {
		room = uploadChunk
	}
	written, err := io.Copy(io.NewOffsetWriter(up.file, offset), http.MaxBytesReader(q.w, q.r.Body, room))
	up.Updated = time.Now()
	if err != nil {
		// Half aangekomen: terug naar het begin van dit stuk, dan kan de browser het opnieuw sturen.
		_ = up.file.Truncate(offset)
		return nil, badRequest("Dit stuk kwam niet goed aan (of is groter dan aangekondigd). Probeer het opnieuw.")
	}
	up.Written += written
	return map[string]int64{"written": up.Written}, nil
}

func (a *App) handleUploadFinish(q *Request) (any, error) {
	root, err := a.uploadRootFor(q)
	if err != nil {
		return nil, err
	}
	up := a.uploads.get(q.r.PathValue("id"), root.Name, q.user.Name)
	if up == nil {
		return nil, notFound("Deze upload bestaat niet (meer). Begin opnieuw.")
	}
	up.mu.Lock()
	defer up.mu.Unlock()
	if up.closed {
		return nil, notFound("Deze upload bestaat niet (meer). Begin opnieuw.")
	}
	if up.Written != up.Size {
		return nil, badRequest("Het bestand is nog niet helemaal binnen (%s van %s).", humanBytes(up.Written), humanBytes(up.Size))
	}
	target := joinPath(up.Dir, up.Name)
	var manifest *BackupManifest
	if root.Name == "backups" {
		// Alleen backups van het paneel: anders weer weg.
		reader, err := openZip(up.file, up.Size, maxBackupEntries)
		if err == nil {
			manifest, err = manifestFrom(reader)
		}
		if err != nil {
			a.uploads.remove(up.ID)
			up.discard(root)
			return nil, badRequest("Dit is geen backup van het dev-paneel (%s).", err.Error())
		}
	}
	if err := a.finishUpload(root, up); err != nil {
		return nil, fileError(err)
	}
	a.uploads.remove(up.ID)
	if root.Name == "backups" {
		_ = writeSidecar(root.Base, up.Name, backupSidecar{Manifest: manifest, Uploaded: true})
		q.log("backup geüpload", up.Name, humanBytes(up.Size))
		return map[string]bool{"ok": true}, nil
	}
	q.log("geüpload", root.Name+": "+target, humanBytes(up.Size))
	return map[string]bool{"ok": true}, nil
}

// finishUpload geeft het tijdelijke bestand zijn naam (en overschrijft als dat mocht). Alleen
// als de naam nog naar ons eigen bestand wijst.
func (a *App) finishUpload(root FileRoot, up *upload) error {
	if err := up.file.Sync(); err != nil {
		return err
	}
	r, err := root.open()
	if err != nil {
		return err
	}
	defer r.Close()
	target := joinPath(up.Dir, up.Name)
	if info, err := r.Lstat(target); err == nil {
		if !up.Overwrite {
			return errExists
		}
		if !info.Mode().IsRegular() {
			return errNotFile
		}
	}
	parent, err := openDir(r, up.Dir)
	if err != nil {
		return err
	}
	defer parent.Close()
	if info, err := r.Lstat(joinPath(up.Dir, up.Temp)); err != nil || !info.Mode().IsRegular() || inodeOf(info) != up.inode {
		return errors.New("het tijdelijke bestand is intussen veranderd; begin opnieuw")
	}
	fd := int(parent.Fd())
	if err := syscall.Renameat(fd, up.Temp, fd, up.Name); err != nil {
		return err
	}
	up.closed = true
	return up.file.Close()
}

func (a *App) handleUploadCancel(q *Request) (any, error) {
	root, err := a.uploadRootFor(q)
	if err != nil {
		return nil, err
	}
	up := a.uploads.get(q.r.PathValue("id"), root.Name, q.user.Name)
	if up == nil {
		return map[string]bool{"ok": true}, nil
	}
	up.mu.Lock()
	defer up.mu.Unlock()
	a.uploads.remove(up.ID)
	up.discard(root)
	return map[string]bool{"ok": true}, nil
}

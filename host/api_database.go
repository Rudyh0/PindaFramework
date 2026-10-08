package main

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func (a *App) registerDashboard(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/dashboard", a.api(member, a.handleDashboard))
	mux.HandleFunc("GET /api/jobs/{id}", a.api(member, a.handleJob))
}

func (a *App) registerDatabase(mux *http.ServeMux) {
	// Developers mogen kijken, databases aanmaken en downloaden. Alles wat gegevens weggooit,
	// overschrijft of wie-mag-waar verandert, is voor beheerders.
	mux.HandleFunc("GET /api/database", a.api(member, a.handleDatabase))
	mux.HandleFunc("POST /api/database/databases", a.api(member, a.handleCreateDatabase))
	mux.HandleFunc("GET /api/database/databases/{name}/export", a.api(member, a.handleExportDatabase))
	mux.HandleFunc("POST /api/database/databases/{name}/delete", a.api(admin, a.handleDropDatabase))
	mux.HandleFunc("POST /api/database/databases/{name}/import", a.api(admin, a.handleImportDatabase))
	mux.HandleFunc("POST /api/database/users", a.api(admin, a.handleCreateDBUser))
	mux.HandleFunc("POST /api/database/users/{name}/{host}/{action}", a.api(admin, a.handleDBUserAction))
	mux.HandleFunc("POST /api/database/plugin/mysql", a.api(admin, a.handlePluginToMySQL))
}

// pluginView: de database van de plugin, zonder het wachtwoord.
func (a *App) pluginView() map[string]any {
	plugin, exists, err := a.readPluginDB()
	view := map[string]any{
		"exists":     exists,
		"type":       plugin.Type,
		"host":       plugin.Host,
		"port":       plugin.Port,
		"database":   plugin.Database,
		"user":       plugin.User,
		"convert":    plugin.Convert,
		"sqliteFile": plugin.SqliteFile,
		"status":     nil,
		"conversion": nil,
	}
	if err != nil {
		view["error"] = err.Error()
	}
	status := a.readPluginDBStatus()
	if status.Status != nil {
		view["status"] = status.Status
	}
	if status.Conversion != nil {
		view["conversion"] = status.Conversion
	}
	if a.pluginFileExists(plugin.SqliteFile) {
		view["sqliteExists"] = true
	}
	return view
}

func (a *App) handleDashboard(q *Request) (any, error) {
	config := a.config.get()
	return map[string]any{
		"services": a.services(),
		"system":   systemInfo(config.BaseDir),
		"plugin":   a.pluginView(),
		"setup": map[string]any{
			"done": config.SetupDone, "serverName": config.ServerName, "gameAddress": config.GameAddress,
			"gamePort": config.GamePort, "siteDomain": config.SiteDomain, "domain": config.Domain, "mode": config.Mode,
		},
	}, nil
}

func (a *App) handleJob(q *Request) (any, error) {
	job := a.jobs.get(q.r.PathValue("id"))
	if job == nil {
		return nil, notFound("Deze taak bestaat niet (meer).")
	}
	return job, nil
}

func (a *App) handleDatabase(q *Request) (any, error) {
	server := a.mariadb.status()
	result := map[string]any{"server": server, "plugin": a.pluginView(), "databases": []DBInfo{}, "users": []DBUser{}}
	if server.Running {
		databases, err := a.mariadb.databases()
		if err != nil {
			return nil, err
		}
		users, err := a.mariadb.users()
		if err != nil {
			return nil, err
		}
		result["databases"] = databases
		result["users"] = users
	}
	return result, nil
}

// dbError maakt van een fout van MariaDB een nette melding.
func dbError(err error) error {
	var api *apiError
	if errors.As(err, &api) {
		return err
	}
	return badRequest("%s", capitalize(err.Error()))
}

// pluginDatabaseName: de database die de plugin gebruikt (die mag je niet zomaar weggooien).
func (a *App) pluginDatabaseName() string {
	plugin, _, err := a.readPluginDB()
	if err != nil || plugin.Type != "mysql" {
		return ""
	}
	return plugin.Database
}

func (a *App) handleCreateDatabase(q *Request) (any, error) {
	var body struct {
		Name       string `json:"name"`
		CreateUser bool   `json:"createUser"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(body.Name)
	if err := checkDBName(name); err != nil {
		return nil, dbError(err)
	}
	if body.CreateUser {
		// Eerst controleren, zodat we niet halverwege blijven steken.
		if err := checkDBUser(name, "localhost"); err != nil {
			return nil, dbError(err)
		}
		if exists, err := a.mariadb.userExists(name, "localhost"); err != nil {
			return nil, dbError(err)
		} else if exists {
			return nil, badRequest("Er is al een gebruiker %s. Maak de database zonder gebruiker en geef die gebruiker toegang.", name)
		}
	}
	if err := a.mariadb.createDatabase(name); err != nil {
		return nil, dbError(err)
	}
	q.log("database aangemaakt", name, "")
	response, err := a.handleDatabase(q)
	if err != nil {
		return nil, err
	}
	if body.CreateUser {
		password := randomPassword(24)
		if err := a.mariadb.createUser(name, "localhost", password, name, false); err != nil {
			return nil, dbError(fmt.Errorf("de database is aangemaakt, maar de gebruiker niet: %w", err))
		}
		q.log("databasegebruiker aangemaakt", name+"@localhost", "toegang tot "+name)
		response, err = a.handleDatabase(q)
		if err != nil {
			return nil, err
		}
		response.(map[string]any)["credentials"] = map[string]string{"user": name, "password": password, "database": name}
	}
	return response, nil
}

func (a *App) handleDropDatabase(q *Request) (any, error) {
	var body struct {
		Confirm  string `json:"confirm"`
		DropUser bool   `json:"dropUser"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := q.r.PathValue("name")
	if body.Confirm != name {
		return nil, badRequest("Typ de naam van de database om te bevestigen.")
	}
	if name == a.pluginDatabaseName() {
		return nil, badRequest("PindaFramework gebruikt deze database. Die kun je hier niet verwijderen.")
	}
	if err := a.mariadb.dropDatabase(name); err != nil {
		return nil, dbError(err)
	}
	q.log("database verwijderd", name, "")
	if body.DropUser && checkDBUser(name, "localhost") == nil {
		plugin, _, _ := a.readPluginDB()
		if exists, _ := a.mariadb.userExists(name, "localhost"); exists && !(plugin.Type == "mysql" && plugin.User == name) {
			if err := a.mariadb.dropUser(name, "localhost"); err != nil {
				return nil, dbError(fmt.Errorf("de database is verwijderd, maar de gebruiker niet: %w", err))
			}
			q.log("databasegebruiker verwijderd", name+"@localhost", "samen met de database")
		}
	}
	return a.handleDatabase(q)
}

func (a *App) tempDir() (string, error) {
	dir := filepath.Join(a.dataDir, "tmp")
	return dir, os.MkdirAll(dir, 0o700)
}

func (a *App) handleExportDatabase(q *Request) (any, error) {
	name := q.r.PathValue("name")
	if err := checkDBName(name); err != nil {
		return nil, dbError(err)
	}
	dir, err := a.tempDir()
	if err != nil {
		return nil, err
	}
	path := filepath.Join(dir, "export-"+randomToken()[:12]+".sql")
	defer os.Remove(path)
	if err := a.mariadb.export(name, path); err != nil {
		return nil, dbError(err)
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return nil, err
	}
	q.log("database gedownload", name, fmt.Sprintf("%d bytes", stat.Size()))
	filename := fmt.Sprintf("%s-%s.sql", name, time.Now().Format("2006-01-02-1504"))
	q.w.Header().Set("Content-Type", "application/sql")
	q.w.Header().Set("Content-Disposition", `attachment; filename="`+filename+`"`)
	http.ServeContent(q.w, q.r, filename, stat.ModTime(), file)
	return nil, nil
}

// handleImportDatabase: de browser stuurt het .sql-bestand als body. Inladen gebeurt op de
// achtergrond; de browser volgt de voortgang via /api/jobs/{id}. Eén import tegelijk.
func (a *App) handleImportDatabase(q *Request) (any, error) {
	name := q.r.PathValue("name")
	if err := checkDBName(name); err != nil {
		return nil, dbError(err)
	}
	if !a.mariadb.importing.TryLock() {
		return nil, badRequest("Er wordt al een database ingeladen. Wacht tot die klaar is.")
	}
	started := false
	defer func() {
		if !started {
			a.mariadb.importing.Unlock()
		}
	}()
	if exists, err := a.mariadb.databaseExists(name); err != nil {
		return nil, dbError(err)
	} else if !exists {
		return nil, notFound("Die database bestaat niet.")
	}
	dir, err := a.tempDir()
	if err != nil {
		return nil, err
	}
	path := filepath.Join(dir, "import-"+randomToken()[:12]+".sql")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, err
	}
	const maxImport = 4 << 30
	written, err := io.Copy(file, http.MaxBytesReader(q.w, q.r.Body, maxImport))
	closeErr := file.Close()
	if err != nil || closeErr != nil {
		os.Remove(path)
		return nil, badRequest("Het bestand kwam niet goed aan. Bij Cloudflare is de grens 100 MB per upload.")
	}
	if written == 0 {
		os.Remove(path)
		return nil, badRequest("Het bestand is leeg.")
	}
	user, ip := q.user.Name, q.ip
	started = true
	job := a.jobs.start("Inladen in "+name, user, func(say func(string, ...any)) error {
		defer a.mariadb.importing.Unlock()
		defer os.Remove(path)
		say("%s inladen in %s…", humanBytes(written), name)
		if err := a.mariadb.importFile(name, path, dir, say); err != nil {
			a.audit.add(user, ip, "database-import mislukt", name, err.Error())
			return err
		}
		say("Klaar.")
		a.audit.add(user, ip, "database ingeladen", name, humanBytes(written))
		return nil
	})
	return job, nil
}

func humanBytes(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.0f kB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d bytes", n)
}

func (a *App) handleCreateDBUser(q *Request) (any, error) {
	var body struct {
		Name     string `json:"name"`
		Password string `json:"password"`
		Database string `json:"database"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(body.Name)
	if err := checkDBUser(name, "localhost"); err != nil {
		return nil, dbError(err)
	}
	password := body.Password
	generated := password == ""
	if generated {
		password = randomPassword(24)
	} else if len(password) < 12 {
		return nil, badRequest("Kies een wachtwoord van minimaal 12 tekens (of laat het leeg voor een sterk wachtwoord).")
	}
	if exists, err := a.mariadb.userExists(name, "localhost"); err != nil {
		return nil, dbError(err)
	} else if exists {
		return nil, badRequest("Die gebruiker bestaat al.")
	}
	if body.Database != "" {
		if exists, err := a.mariadb.databaseExists(body.Database); err != nil {
			return nil, dbError(err)
		} else if !exists {
			return nil, badRequest("Die database bestaat niet.")
		}
	}
	if err := a.mariadb.createUser(name, "localhost", password, body.Database, false); err != nil {
		return nil, dbError(err)
	}
	q.log("databasegebruiker aangemaakt", name+"@localhost", body.Database)
	response, err := a.handleDatabase(q)
	if err != nil {
		return nil, err
	}
	if generated {
		response.(map[string]any)["credentials"] = map[string]string{"user": name, "password": password, "database": body.Database}
	}
	return response, nil
}

func (a *App) handleDBUserAction(q *Request) (any, error) {
	name, host, action := q.r.PathValue("name"), q.r.PathValue("host"), q.r.PathValue("action")
	if err := checkDBUser(name, host); err != nil {
		return nil, dbError(err)
	}
	var body struct {
		Database string `json:"database"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	plugin, _, _ := a.readPluginDB()
	isPlugin := plugin.Type == "mysql" && plugin.User == name && host == "localhost"
	var credentials map[string]string
	switch action {
	case "password":
		password := randomPassword(24)
		if err := a.mariadb.setPassword(name, host, password); err != nil {
			return nil, dbError(err)
		}
		credentials = map[string]string{"user": name, "password": password}
		q.log("databasewachtwoord gereset", name+"@"+host, "")
		if isPlugin {
			// De plugin moet het nieuwe wachtwoord ook weten; het geldt na een herstart.
			plugin.Password = password
			if err := a.writePluginDB(plugin); err != nil {
				return nil, fmt.Errorf("het wachtwoord is veranderd, maar database.yml niet bij te werken: %w", err)
			}
			credentials["plugin"] = "true"
		}
	case "grant":
		if err := a.mariadb.grant(name, host, body.Database); err != nil {
			return nil, dbError(err)
		}
		q.log("databasetoegang gegeven", name+"@"+host, body.Database)
	case "revoke":
		if isPlugin && body.Database == plugin.Database {
			return nil, badRequest("PindaFramework heeft deze toegang nodig.")
		}
		if err := a.mariadb.revoke(name, host, body.Database); err != nil {
			return nil, dbError(err)
		}
		q.log("databasetoegang ingetrokken", name+"@"+host, body.Database)
	case "delete":
		if isPlugin {
			return nil, badRequest("PindaFramework gebruikt deze gebruiker. Die kun je hier niet verwijderen.")
		}
		if err := a.mariadb.dropUser(name, host); err != nil {
			return nil, dbError(err)
		}
		q.log("databasegebruiker verwijderd", name+"@"+host, "")
	default:
		return nil, notFound("Onbekende actie.")
	}
	response, err := a.handleDatabase(q)
	if err != nil {
		return nil, err
	}
	if credentials != nil {
		response.(map[string]any)["credentials"] = credentials
	}
	return response, nil
}

// handlePluginToMySQL zet de plugin over op MySQL. Met convert worden de bestaande gegevens
// uit SQLite bij de volgende start van de Minecraft-server overgezet.
func (a *App) handlePluginToMySQL(q *Request) (any, error) {
	var body struct {
		Restart bool `json:"restart"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	current, _, err := a.readPluginDB()
	if err != nil {
		return nil, err
	}
	if current.Type == "mysql" && !current.Convert {
		return nil, badRequest("De plugin gebruikt al MySQL.")
	}
	convert := a.pluginFileExists(current.SqliteFile)
	if _, err := a.setupPluginMySQL(convert); err != nil {
		return nil, dbError(err)
	}
	if err := a.config.update(func(c *Config) { c.Database = "mysql" }); err != nil {
		return nil, err
	}
	q.log("plugin naar MySQL", "pindacraft", map[bool]string{true: "met omzetten van SQLite", false: "nieuwe database"}[convert])
	response, err := a.handleDatabase(q)
	if err != nil {
		return nil, err
	}
	restarted := false
	if body.Restart {
		if installed, state := unitState(minecraftUnit); installed && state == "active" {
			if _, err := runQuiet(2*time.Minute, "systemctl", "restart", minecraftUnit); err == nil {
				restarted = true
				q.log("minecraft-server herstart", "", "om naar MySQL om te zetten")
			}
		}
	}
	response.(map[string]any)["restarted"] = restarted
	response.(map[string]any)["converting"] = convert
	return response, nil
}

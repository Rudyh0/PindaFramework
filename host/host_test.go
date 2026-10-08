package main

import (
	"bytes"
	"encoding/base32"
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestTOTPMatchesRFC6238(t *testing.T) {
	// Testvector uit RFC 6238 (SHA-1, geheim "12345678901234567890").
	secret := []byte("12345678901234567890")
	cases := map[int64]string{59: "287082", 1111111109: "081804", 1234567890: "005924", 2000000000: "279037"}
	for unix, want := range cases {
		if got := totpCode(secret, unix/30); got != want {
			t.Errorf("tijd %d: kreeg %s, verwacht %s", unix, got, want)
		}
	}
	encoded := base32NoPadding.EncodeToString(secret)
	now := time.Unix(1234567890, 0)
	step, ok := verifyTOTP(encoded, "005924", 0, now)
	if !ok {
		t.Fatal("geldige code afgekeurd")
	}
	if _, ok := verifyTOTP(encoded, "005924", step, now); ok {
		t.Error("dezelfde code mocht twee keer gebruikt worden")
	}
	if _, ok := verifyTOTP(encoded, "123456", 0, now); ok {
		t.Error("verkeerde code goedgekeurd")
	}
	if _, ok := verifyTOTP(strings.ToLower(encoded), "005 924", 0, now); !ok {
		t.Error("code met spatie of kleine letters in het geheim afgekeurd")
	}
	if _, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(newTOTPSecret()); err != nil {
		t.Error("nieuw geheim is geen base32")
	}
}

func TestPasswords(t *testing.T) {
	hash, err := hashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !verifyPassword(hash, "correct horse battery") {
		t.Error("goed wachtwoord afgekeurd")
	}
	if verifyPassword(hash, "correct horse batterY") || verifyPassword("onzin", "x") {
		t.Error("fout wachtwoord goedgekeurd")
	}
	if checkPasswordStrength("kort", "") == nil || checkPasswordStrength("rudyh0rudyh0", "Rudyh0") == nil {
		t.Error("zwak wachtwoord goedgekeurd")
	}
	if len(randomPassword(24)) != 24 || randomCode(12)[4] != '-' {
		t.Error("willekeurige wachtwoorden of codes hebben de verkeerde vorm")
	}
}

func TestPluginDatabaseFile(t *testing.T) {
	dir := t.TempDir()
	config := defaultPluginDB()
	config.Type = "mysql"
	config.Password = `ge"heim\'#: met tekens`
	config.Convert = true
	if err := writePluginDBAt(dir, "plugins/Pinda", "", config); err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(filepath.Join(dir, "plugins", "Pinda", "database.yml")); err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("database.yml niet (alleen voor de eigenaar) geschreven: %v", err)
	}
	read, exists, err := readPluginDBAt(dir, "plugins/Pinda")
	if err != nil || !exists {
		t.Fatalf("niet terug te lezen: %v", err)
	}
	if read.Type != "mysql" || read.Password != config.Password || !read.Convert || read.Port != 3306 || read.Database != "pindacraft" {
		t.Errorf("anders teruggelezen: %+v", read)
	}
	// Zoals de plugin (Bukkit) hem zelf zou kunnen wegschrijven.
	bukkit := "type: mysql\nsqlite:\n  file: data.db\nmysql:\n  host: db.example.com\n  port: 3307\n  database: mc\n  user: 'o''neil'\n  password: abc # commentaar\n  ssl: true\nconvert-from-sqlite: false\n"
	if err := os.WriteFile(filepath.Join(dir, "plugins", "Pinda", "database.yml"), []byte(bukkit), 0o600); err != nil {
		t.Fatal(err)
	}
	read, _, _ = readPluginDBAt(dir, "plugins/Pinda")
	if read.Host != "db.example.com" || read.Port != 3307 || read.User != "o'neil" || read.Password != "abc" || !read.SSL || read.Convert {
		t.Errorf("Bukkit-stijl verkeerd gelezen: %+v", read)
	}
}

// De pluginmap is van de Minecraft-gebruiker: symlinks daarin mogen het paneel (root) nooit
// naar een ander bestand laten lezen of schrijven.
func TestPluginFilesIgnoreSymlinks(t *testing.T) {
	base := t.TempDir()
	outside := t.TempDir()
	secret := filepath.Join(outside, "geheim")
	if err := os.WriteFile(secret, []byte("type: mysql\nmysql:\n  password: root-geheim\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	plugin := filepath.Join(base, "plugins", "Pinda")
	if err := os.MkdirAll(plugin, 0o755); err != nil {
		t.Fatal(err)
	}
	// 1. database.yml is een symlink naar een ander bestand.
	if err := os.Symlink(secret, filepath.Join(plugin, "database.yml")); err != nil {
		t.Fatal(err)
	}
	if read, _, err := readPluginDBAt(base, "plugins/Pinda"); err == nil && read.Password == "root-geheim" {
		t.Error("symlink gevolgd bij lezen")
	}
	if fileExistsAt(base, "plugins/Pinda", "database.yml") {
		t.Error("symlink telt als bestand")
	}
	if err := writePluginDBAt(base, "plugins/Pinda", "", defaultPluginDB()); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(secret); !strings.Contains(string(data), "root-geheim") {
		t.Error("symlink gevolgd bij schrijven: bestand buiten de map overschreven")
	}
	if info, err := os.Lstat(filepath.Join(plugin, "database.yml")); err != nil || !info.Mode().IsRegular() {
		t.Error("de symlink is niet vervangen door een gewoon bestand")
	}
	// 2. Een map onderweg is een symlink.
	if err := os.RemoveAll(filepath.Join(base, "plugins")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(base, "plugins")); err != nil {
		t.Fatal(err)
	}
	if err := writePluginDBAt(base, "plugins", "", defaultPluginDB()); err == nil {
		t.Error("geschreven via een gesymlinkte map")
	}
	if _, err := os.Stat(filepath.Join(outside, "database.yml")); err == nil {
		t.Error("bestand buiten de map aangemaakt")
	}
	if _, err := readFileAt(base, "plugins", "geheim", 1<<20); err == nil {
		t.Error("gelezen via een gesymlinkte map")
	}
	// 3. Geen paden als bestandsnaam en geen gigantische bestanden.
	for _, name := range []string{"../geheim", "", ".", "..", "a/b"} {
		if fileExistsAt(outside, "", name) {
			t.Errorf("naam %q toegestaan", name)
		}
	}
	if err := os.WriteFile(filepath.Join(outside, "groot"), make([]byte, 2048), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readFileAt(outside, "", "groot", 1024); err == nil {
		t.Error("te groot bestand gelezen")
	}
}

func TestSQLHelpers(t *testing.T) {
	quoted, err := sqlString(`a'b\c`)
	if err != nil || quoted != `'a\'b\\c'` {
		t.Errorf("sqlString: %s %v", quoted, err)
	}
	if _, err := sqlString("a\x00b"); err == nil {
		t.Error("NUL-teken toegestaan")
	}
	if cleanMariaDBError("ERROR 1007 (HY000) at line 1: Can't create database 'x'; database exists") != "Can't create database 'x'; database exists" {
		t.Error("foutmelding niet opgeschoond")
	}
	if unescapeBatch(`a\tb\\c\nd`) != "a\tb\\c\nd" {
		t.Error("batch-escapes verkeerd")
	}
	for _, bad := range []string{"mysql", "a-b", "a b", "", strings.Repeat("x", 65), "`x`"} {
		if checkDBName(bad) == nil {
			t.Errorf("databasenaam %q toegestaan", bad)
		}
	}
	if checkDBUser("root", "localhost") == nil || checkDBUser("ok_user", "evil.com") == nil || checkDBUser("ok_user", "localhost") != nil {
		t.Error("controle op gebruikers klopt niet")
	}
}

func TestClientIP(t *testing.T) {
	app := &App{config: &ConfigStore{config: Config{Mode: "domain", Cloudflare: true}}}
	request := func(remote string, headers map[string]string) *http.Request {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = remote
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		return r
	}
	cases := []struct {
		remote  string
		headers map[string]string
		want    string
	}{
		{"203.0.113.9:1234", map[string]string{"X-Forwarded-For": "1.2.3.4"}, "203.0.113.9"},
		{"127.0.0.1:5000", map[string]string{"X-Forwarded-For": "198.51.100.7"}, "198.51.100.7"},
		{"127.0.0.1:5000", map[string]string{"X-Forwarded-For": "1.1.1.1, 104.16.0.5", "CF-Connecting-IP": "198.51.100.8"}, "198.51.100.8"},
		{"127.0.0.1:5000", map[string]string{"X-Forwarded-For": "198.51.100.9", "CF-Connecting-IP": "6.6.6.6"}, "198.51.100.9"},
		{"127.0.0.1:5000", nil, "127.0.0.1"},
	}
	for _, c := range cases {
		if got := app.clientIP(request(c.remote, c.headers)); got != c.want {
			t.Errorf("%s %v: kreeg %s, verwacht %s", c.remote, c.headers, got, c.want)
		}
	}
	// Zonder Cloudflare geloven we CF-Connecting-IP nooit.
	app.config.config.Cloudflare = false
	if got := app.clientIP(request("127.0.0.1:5000", map[string]string{"X-Forwarded-For": "104.16.0.5", "CF-Connecting-IP": "6.6.6.6"})); got != "104.16.0.5" {
		t.Errorf("zonder Cloudflare toch CF-Connecting-IP gebruikt: %s", got)
	}
}

func TestDNSRecords(t *testing.T) {
	config := Config{Mode: "domain", Domain: "dev.pinda.nl", SiteDomain: "pinda.nl", GameAddress: "play.pinda.nl", GamePort: 25570, Cloudflare: true}
	records := dnsRecords(config, []string{"203.0.113.5", "2001:db8::5"})
	var srv, game *DNSRecord
	for i := range records {
		if records[i].Type == "SRV" {
			srv = &records[i]
		}
		if records[i].Type == "A" && records[i].Name == "play.pinda.nl" {
			game = &records[i]
		}
	}
	if srv == nil || srv.Name != "_minecraft._tcp.play.pinda.nl" || srv.Content != "0 5 25570 play.pinda.nl" {
		t.Errorf("SRV-record klopt niet: %+v", srv)
	}
	if game == nil || game.Proxy != "uit" {
		t.Errorf("het adres voor spelers moet zonder Cloudflare-proxy: %+v", game)
	}
	if len(records) != 7 {
		t.Errorf("verwacht 7 records (3x A, 3x AAAA, SRV), kreeg %d", len(records))
	}
}

// ============================================================ de hele inlog-flow via HTTP

type client struct {
	t      *testing.T
	server *httptest.Server
	http   *http.Client
}

func newClient(t *testing.T, server *httptest.Server) *client {
	jar, _ := cookiejar.New(nil)
	return &client{t: t, server: server, http: &http.Client{Jar: jar}}
}

func (c *client) call(method, path string, body any, csrf bool) (int, map[string]any) {
	var reader *bytes.Reader
	if body != nil {
		data, _ := json.Marshal(body)
		reader = bytes.NewReader(data)
	} else {
		reader = bytes.NewReader(nil)
	}
	req, _ := http.NewRequest(method, c.server.URL+path, reader)
	req.Header.Set("Content-Type", "application/json")
	if csrf {
		req.Header.Set("X-Pinda-Host", "1")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func (c *client) post(path string, body any) (int, map[string]any) {
	return c.call("POST", path, body, true)
}

func (c *client) get(path string) (int, map[string]any) {
	return c.call("GET", path, nil, false)
}

func currentCode(t *testing.T, secret string) string {
	key, err := base32NoPadding.DecodeString(strings.ReplaceAll(secret, " ", ""))
	if err != nil {
		t.Fatal(err)
	}
	return totpCode(key, time.Now().Unix()/30)
}

func TestLoginFlow(t *testing.T) {
	dir := t.TempDir()
	app, err := openApp(filepath.Join(dir, "panel", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	_ = app.config.update(func(c *Config) { c.BaseDir = filepath.Join(dir, "pinda") })
	server := httptest.NewServer(app.routes())
	defer server.Close()
	admin := newClient(t, server)

	if status, state := admin.get("/api/state"); status != 200 || state["needsSetup"] != true {
		t.Fatalf("setup niet nodig? %d %v", status, state)
	}
	// Zonder de X-Pinda-Host-header (zoals een andere website zou doen) mag niets.
	if status, _ := admin.call("POST", "/api/setup/account", map[string]string{}, false); status != 403 {
		t.Fatalf("verzoek zonder header toegestaan: %d", status)
	}
	if status, body := admin.post("/api/setup/account", map[string]string{"code": "FOUT-FOUT-FOUT", "username": "rudy", "password": "een lang wachtwoord"}); status != 400 {
		t.Fatalf("verkeerde setupcode geaccepteerd: %d %v", status, body)
	}
	code, _ := app.setupCode()
	status, challenge := admin.post("/api/setup/account", map[string]string{"code": strings.ToLower(code), "username": "rudy", "password": "een lang wachtwoord"})
	if status != 200 || challenge["step"] != "setup" || !strings.HasPrefix(challenge["qr"].(string), "data:image/png;base64,") {
		t.Fatalf("setup-stap 1: %d %v", status, challenge)
	}
	secret := challenge["secret"].(string)
	if status, body := admin.post("/api/setup/account/verify", map[string]string{"code": "000000"}); status != 400 {
		t.Fatalf("verkeerde 2FA-code geaccepteerd: %d %v", status, body)
	}
	if status, body := admin.post("/api/setup/account/verify", map[string]string{"code": currentCode(t, secret)}); status != 200 || body["step"] != "done" {
		t.Fatalf("setup-stap 2: %d %v", status, body)
	}
	if _, err := os.Stat(filepath.Join(dir, "panel", "setup-code")); !os.IsNotExist(err) {
		t.Error("setupcode niet weggehaald")
	}
	if status, _ := newClient(t, server).post("/api/setup/account", map[string]string{"code": code, "username": "nog1", "password": "een lang wachtwoord"}); status != 403 {
		t.Error("tweede setup toegestaan")
	}
	if status, dash := admin.get("/api/dashboard"); status != 200 || dash["services"] == nil {
		t.Fatalf("dashboard: %d %v", status, dash)
	}

	// Setup afmaken.
	if status, body := admin.post("/api/setup/server", map[string]any{"serverName": "PindaCraft", "siteDomain": "pindacraft.nl", "gameAddress": "play.pindacraft.nl", "gamePort": 25565}); status != 200 {
		t.Fatalf("setup server: %d %v", status, body)
	}
	if status, body := admin.post("/api/setup/database", map[string]string{"type": "sqlite"}); status != 200 {
		t.Fatalf("setup database: %d %v", status, body)
	}
	if read, exists, _ := app.readPluginDB(); !exists || read.Type != "sqlite" {
		t.Error("database.yml niet geschreven")
	}
	if status, _ := admin.post("/api/setup/finish", nil); status != 200 {
		t.Fatal("setup afmaken mislukt")
	}
	if status, _ := admin.post("/api/setup/database", map[string]string{"type": "sqlite"}); status != 400 {
		t.Error("database-keuze na de setup nog via de setup aan te passen")
	}

	// Een developer aanmaken: tijdelijk wachtwoord, 2FA koppelen, eigen wachtwoord.
	status, users := admin.post("/api/users", map[string]any{"name": "dev1", "admin": false})
	if status != 200 || users["password"] == nil {
		t.Fatalf("gebruiker maken: %d %v", status, users)
	}
	temp := users["password"].(string)
	dev := newClient(t, server)
	if status, _ := dev.post("/api/login", map[string]string{"username": "dev1", "password": "fout wachtwoord"}); status != 401 {
		t.Fatal("verkeerd wachtwoord geaccepteerd")
	}
	status, login := dev.post("/api/login", map[string]string{"username": "DEV1", "password": temp})
	if status != 200 || login["step"] != "setup" {
		t.Fatalf("eerste login moet 2FA koppelen: %d %v", status, login)
	}
	if status, body := dev.post("/api/login/verify", map[string]string{"code": currentCode(t, login["secret"].(string))}); status != 200 || body["step"] != "password" {
		t.Fatalf("na 2FA moet een nieuw wachtwoord: %d %v", status, body)
	}
	if status, _ := dev.get("/api/dashboard"); status != 401 {
		t.Fatal("al ingelogd zonder eigen wachtwoord")
	}
	if status, _ := dev.post("/api/login/password", map[string]string{"password": temp}); status != 400 {
		t.Fatal("tijdelijk wachtwoord opnieuw gekozen")
	}
	if status, body := dev.post("/api/login/password", map[string]string{"password": "mijn eigen geheim"}); status != 200 || body["step"] != "done" {
		t.Fatalf("eigen wachtwoord: %d %v", status, body)
	}
	if status, _ := dev.get("/api/users"); status != 403 {
		t.Fatal("developer mag gebruikers beheren")
	}
	for _, path := range []string{"/api/database/databases/pindacraft/delete", "/api/database/databases/pindacraft/import",
		"/api/database/users", "/api/database/users/pindacraft/localhost/password", "/api/database/plugin/mysql"} {
		if status, _ := dev.post(path, map[string]string{}); status != 403 {
			t.Errorf("developer mag %s (%d)", path, status)
		}
	}
	if status, _ := dev.get("/api/dashboard"); status != 200 {
		t.Fatal("developer niet ingelogd")
	}

	// Uitzetten logt de developer meteen uit; de laatste beheerder is beschermd.
	if status, _ := admin.post("/api/users/dev1/disable", map[string]bool{"value": true}); status != 200 {
		t.Fatal("uitzetten mislukt")
	}
	if status, _ := dev.get("/api/dashboard"); status != 401 {
		t.Fatal("uitgezette gebruiker nog ingelogd")
	}
	if status, _ := admin.post("/api/users/rudy/delete", nil); status != 400 {
		t.Fatal("jezelf verwijderen toegestaan")
	}

	// Een inlogpoging die liep vóór een reset, kan daarna niet meer worden afgemaakt.
	// A: reset via het paneel (gooit ook lopende inlogpogingen weg).
	status, created := admin.post("/api/users", map[string]any{"name": "dev2", "admin": false})
	if status != 200 {
		t.Fatalf("dev2 maken: %d", status)
	}
	thief := newClient(t, server)
	status, pending := thief.post("/api/login", map[string]string{"username": "dev2", "password": created["password"].(string)})
	if status != 200 || pending["step"] != "setup" {
		t.Fatalf("inloggen dev2: %d %v", status, pending)
	}
	status, reset := admin.post("/api/users/dev2/reset-2fa", nil)
	if status != 200 || reset["password"] == nil {
		t.Fatalf("reset-2fa: %d %v", status, reset)
	}
	if status, _ := thief.post("/api/login/verify", map[string]string{"code": currentCode(t, pending["secret"].(string))}); status != 401 {
		t.Errorf("inlogpoging van vóór de reset (paneel) afgemaakt: %d", status)
	}
	// B: reset op de opdrachtregel (andere processen; alleen users.json verandert), terwijl de
	// aanvaller al voorbij de 2FA-stap is.
	status, created = admin.post("/api/users", map[string]any{"name": "dev3", "admin": false})
	if status != 200 {
		t.Fatalf("dev3 maken: %d", status)
	}
	thief = newClient(t, server)
	if status, pending = thief.post("/api/login", map[string]string{"username": "dev3", "password": created["password"].(string)}); status != 200 {
		t.Fatalf("inloggen dev3: %d", status)
	}
	if status, body := thief.post("/api/login/verify", map[string]string{"code": currentCode(t, pending["secret"].(string))}); status != 200 || body["step"] != "password" {
		t.Fatalf("2FA dev3: %d %v", status, body)
	}
	other, err := openUsers(filepath.Join(dir, "panel", "users.json"))
	if err != nil {
		t.Fatal(err)
	}
	fresh, err := other.resetTwoFactor("dev3")
	if err != nil {
		t.Fatal(err)
	}
	if status, _ := thief.post("/api/login/password", map[string]string{"password": "van de aanvaller zelf"}); status != 401 {
		t.Errorf("inlogpoging van vóór de reset (opdrachtregel) afgemaakt: %d", status)
	}
	if user, _ := app.users.get("dev3"); !user.MustChangePassword || user.TOTPSecret != "" || !verifyPassword(user.PasswordHash, fresh) {
		t.Error("de reset is door een oude inlogpoging ongedaan gemaakt")
	}

	// Een aanvaller met veel IP-adressen kan de beheerder niet buitensluiten: in zijn eigen
	// browser (een bekend apparaat) komt hij er altijd in.
	for i := 0; i < limitUser; i++ {
		app.limiter.take("user:rudy", limitUser, limitWindow)
	}
	if status, _ := newClient(t, server).post("/api/login", map[string]string{"username": "rudy", "password": "een lang wachtwoord"}); status != 429 {
		t.Errorf("onbekend apparaat niet begrensd na veel pogingen op deze naam: %d", status)
	}
	if status, body := admin.post("/api/login", map[string]string{"username": "rudy", "password": "een lang wachtwoord"}); status != 200 || body["step"] != "code" {
		t.Errorf("bekend apparaat buitengesloten: %d %v", status, body)
	}

	// Te veel foute pogingen vanaf één IP: tijdelijk geblokkeerd.
	attacker := newClient(t, server)
	blocked := false
	for i := 0; i < 15; i++ {
		if status, _ := attacker.post("/api/login", map[string]string{"username": "rudy", "password": "raden" + url.QueryEscape(string(rune('a'+i)))}); status == 429 {
			blocked = true
			break
		}
	}
	if !blocked {
		t.Error("geen blokkade na veel foute pogingen")
	}
	if entries := app.audit.list(100); len(entries) < 5 {
		t.Errorf("logboek te leeg: %d", len(entries))
	}
}

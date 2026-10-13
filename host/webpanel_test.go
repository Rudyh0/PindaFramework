package main

import (
	"archive/zip"
	"bytes"
	"crypto/x509"
	"encoding/pem"
	"net"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

// fakeNginx: nginx-blokken in een tijdelijke map, zonder echte nginx.
func fakeNginx(t *testing.T) (string, *atomic.Int32) {
	t.Helper()
	oldDir, oldSSL, oldTest, oldReload, oldCertbot := nginxDir, nginxSSLDir, nginxTest, nginxReload, runCertbot
	dir := t.TempDir()
	var reloads atomic.Int32
	nginxDir, nginxSSLDir = dir, filepath.Join(dir, "pinda-ssl")
	nginxTest = func() (string, error) { return "", nil }
	nginxReload = func() (string, error) { reloads.Add(1); return "", nil }
	runCertbot = func(...string) (string, error) { t.Error("certbot mag achter Cloudflare niet draaien"); return "", nil }
	t.Cleanup(func() {
		nginxDir, nginxSSLDir, nginxTest, nginxReload, runCertbot = oldDir, oldSSL, oldTest, oldReload, oldCertbot
	})
	return dir, &reloads
}

// frameworkJar: een nep-PindaFramework met de echte modules/panel.yml erin.
func frameworkJar(t *testing.T) []byte {
	t.Helper()
	panel, err := os.ReadFile("../src/main/resources/modules/panel.yml")
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	w, _ := writer.Create("plugin.yml")
	w.Write([]byte("name: PindaFramework\nversion: '1.0'\nmain: nl.pinda.framework.PindaFramework\n"))
	w, _ = writer.Create("modules/panel.yml")
	w.Write(panel)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// devRcon: een nep-RCON die op "panel devlink" antwoordt zoals answer zegt.
func devRcon(t *testing.T, password string, answer func(command string) string) (int, func() []string) {
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
					switch kind {
					case rconAuth:
						if body != password {
							id = -1
						}
						rconWrite(conn, id, rconAuthResponse, "")
					case rconExec:
						mu.Lock()
						commands = append(commands, body)
						mu.Unlock()
						rconWrite(conn, id, rconResponseValue, answer(body))
					default:
						rconWrite(conn, id, rconResponseValue, "Unknown request 64")
					}
				}
			}(conn)
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string{}, commands...)
	}
}

func TestSetYAMLValues(t *testing.T) {
	bukkit := "# Uitleg\n\nweb:\n  # Op welk adres\n  bind: 0.0.0.0\n  port: 8085\n\npublic-url: ''\nbehind-proxy: false\nlogin:\n  two-factor: true\n"
	values := managedPanelValues("panel.pindacraft.nl")
	out, changed := setYAMLValues(bukkit, values)
	if !changed {
		t.Fatal("niets veranderd")
	}
	want := "# Uitleg\n\nweb:\n  # Op welk adres\n  bind: \"127.0.0.1\"\n  port: 8085\n\npublic-url: \"https://panel.pindacraft.nl\"\nbehind-proxy: true\nlogin:\n  two-factor: true\n"
	if out != want {
		t.Errorf("aangepast:\n%s\nverwacht:\n%s", out, want)
	}
	// Nog een keer: niets meer te doen. Ook niet als Bukkit het zonder aanhalingstekens schreef.
	if _, again := setYAMLValues(out, values); again {
		t.Error("tweede keer weer veranderd")
	}
	unquoted := strings.NewReplacer(`"127.0.0.1"`, "127.0.0.1", `"https://panel.pindacraft.nl"`, "https://panel.pindacraft.nl").Replace(out)
	if _, again := setYAMLValues(unquoted, values); again {
		t.Error("waarden zonder aanhalingstekens gelden als anders")
	}
	// Ontbrekende sleutels en secties komen erbij, de rest blijft staan.
	out, _ = setYAMLValues("web:\n  port: 9000\nlogin:\n  two-factor: true\n", values)
	parsed := parseSimpleYAML(strings.NewReader(out))
	if parsed["web.bind"] != "127.0.0.1" || parsed["web.port"] != "9000" || parsed["public-url"] != "https://panel.pindacraft.nl" ||
		parsed["behind-proxy"] != "true" || parsed["login.two-factor"] != "true" {
		t.Errorf("aangevuld:\n%s", out)
	}
	out, _ = setYAMLValues("", values)
	parsed = parseSimpleYAML(strings.NewReader(out))
	if parsed["web.bind"] != "127.0.0.1" || parsed["public-url"] != "https://panel.pindacraft.nl" || !strings.HasSuffix(out, "\n") {
		t.Errorf("leeg bestand:\n%s", out)
	}
	// Terug naar de standaard.
	out, _ = setYAMLValues(want, defaultPanelValues)
	parsed = parseSimpleYAML(strings.NewReader(out))
	if parsed["web.bind"] != "0.0.0.0" || parsed["public-url"] != "" || parsed["behind-proxy"] != "false" {
		t.Errorf("standaard:\n%s", out)
	}
}

func TestParseDevLink(t *testing.T) {
	token := strings.Repeat("aB3_-", 9)
	cases := []struct {
		response, base, token, problem string
	}{
		{"PindaHost-link: https://panel.pindacraft.nl/#login=" + token, "https://panel.pindacraft.nl", token, ""},
		{"[12:00] iets\nPindaHost-link: http://203.0.113.5:8085/#login=" + token + "\n", "http://203.0.113.5:8085", token, ""},
		{"PindaHost-fout: het webpaneel draait niet.", "", "", "het webpaneel draait niet."},
		{"Gebruik: /panel [logout]", "", "", ""},
		{"PindaHost-link: javascript:alert(1)//#login=" + token, "", "", ""},
		{"PindaHost-link: https://x.nl/#login=kort", "", "", ""},
	}
	for _, c := range cases {
		base, got, problem := parseDevLink(c.response)
		if base != c.base || got != c.token || problem != c.problem {
			t.Errorf("%q: %q %q %q", c.response, base, got, problem)
		}
	}
}

func TestPanelSiteConfig(t *testing.T) {
	site := panelSite{Domain: "panel.pindacraft.nl", Port: 8090, Cert: "/c.crt", Key: "/c.key", Acme: "/opt/pinda/acme", ListenSSL: "ssl http2", IPv6: true}
	for _, cloudflare := range []bool{false, true} {
		site.Cloudflare = cloudflare
		content := panelSiteConfig(site)
		for _, want := range []string{"server_name panel.pindacraft.nl;", "proxy_pass http://127.0.0.1:8090;", "ssl_certificate /c.crt;",
			"ssl_certificate_key /c.key;", "root /opt/pinda/acme;", "listen [::]:443 ssl http2;", "proxy_set_header X-Forwarded-For $remote_addr;",
			"error_page 502 504 = @offline;"} {
			if !strings.Contains(content, want) {
				t.Errorf("mist %q:\n%s", want, content)
			}
		}
		if strings.Contains(content, "real_ip_header CF-Connecting-IP;") != cloudflare {
			t.Errorf("Cloudflare-IP (%v) klopt niet", cloudflare)
		}
	}
	if _, err := exec.LookPath("nginx"); err != nil {
		t.Skip("geen nginx")
	}
	if os.Geteuid() != 0 {
		t.Skip("nginx -t alleen als root")
	}
	dir := t.TempDir()
	old := nginxSSLDir
	nginxSSLDir = dir
	defer func() { nginxSSLDir = old }()
	cert, key, err := selfSignedCert("panel.pindacraft.nl")
	if err != nil {
		t.Fatal(err)
	}
	listenSSL, http2 := nginxHTTP2()
	_, ipv6 := os.Stat("/proc/net/if_inet6")
	content := panelSiteConfig(panelSite{Domain: "panel.pindacraft.nl", Port: 8085, Cert: cert, Key: key, Acme: dir, ListenSSL: listenSSL,
		HTTP2Line: http2, IPv6: ipv6 == nil, Cloudflare: true})
	os.WriteFile(filepath.Join(dir, "site.conf"), []byte(content), 0o644)
	conf := "pid " + dir + "/nginx.pid;\nerror_log " + dir + "/error.log;\nevents {}\nhttp {\n access_log off;\n include " + dir + "/site.conf;\n}\n"
	os.WriteFile(filepath.Join(dir, "nginx.conf"), []byte(conf), 0o644)
	if out, err := exec.Command("nginx", "-t", "-c", filepath.Join(dir, "nginx.conf")).CombinedOutput(); err != nil {
		t.Fatalf("nginx -t: %s\n%s", out, content)
	}
}

func TestWebpanelAPI(t *testing.T) {
	nginx, reloads := fakeNginx(t)
	app, process := testApp(t)
	_ = app.config.update(func(c *Config) {
		c.Mode, c.Domain, c.Cloudflare = "domain", "dev.pindacraft.nl", true
		c.SiteDomain, c.GameAddress = "pindacraft.nl", "play.pindacraft.nl"
	})
	server := httptest.NewServer(app.routes())
	defer server.Close()
	rudy := loggedIn(t, app, server, "rudy", true)
	lisa := loggedIn(t, app, server, "lisa", false)
	base := app.config.get().BaseDir

	// Nog geen adres: inloggen kan niet, en dat staat in de status.
	if status, info := lisa.get("/api/webpanel"); status != 200 || info["ready"] != false || info["suggested"] != "panel.pindacraft.nl" {
		t.Errorf("status zonder adres: %d %v", status, info)
	}
	if status, _ := lisa.post("/api/webpanel/login", nil); status != 409 {
		t.Errorf("inloggen zonder adres: %d", status)
	}
	if _, info := lisa.get("/api/server"); info["webpanelReady"] != false {
		t.Errorf("server zonder adres: %v", info["webpanelReady"])
	}

	// Het adres: alleen een beheerder, en niet dat van iets anders.
	if status, _ := lisa.post("/api/webpanel", map[string]string{"domain": "panel.pindacraft.nl"}); status != 403 {
		t.Errorf("developer stelt adres in: %d", status)
	}
	for _, bad := range []string{"dev.pindacraft.nl", "play.pindacraft.nl", "pindacraft.nl", "www.pindacraft.nl", "niet geldig", "panel", "a/b.nl"} {
		if status, _ := rudy.post("/api/webpanel", map[string]string{"domain": bad}); status != 400 {
			t.Errorf("adres %q: %d", bad, status)
		}
	}
	if app.config.get().PanelDomain != "" {
		t.Fatal("ongeldig adres opgeslagen")
	}

	// PindaFramework staat er al, maar heeft panel.yml nog niet gemaakt (nooit gestart).
	writeFiles(t, app.config.get().serverDir(), map[string]string{"plugins/PindaFramework-1.0.jar": string(frameworkJar(t))})
	status, job := rudy.post("/api/webpanel", map[string]string{"domain": " Panel.PindaCraft.nl "})
	if status != 200 {
		t.Fatalf("adres opslaan: %d %v", status, job)
	}
	if done := waitJob(t, rudy, job); done["status"] != "done" {
		t.Fatalf("taak: %v", done)
	}
	if app.config.get().PanelDomain != "panel.pindacraft.nl" {
		t.Errorf("adres: %q", app.config.get().PanelDomain)
	}
	site, err := os.ReadFile(filepath.Join(nginx, "sites-available", panelSiteName))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"server_name panel.pindacraft.nl;", "proxy_pass http://127.0.0.1:8085;", "real_ip_header CF-Connecting-IP;",
		"ssl_certificate " + filepath.Join(nginx, "pinda-ssl", "panel.pindacraft.nl.crt") + ";"} {
		if !strings.Contains(string(site), want) {
			t.Errorf("nginx-blok mist %q", want)
		}
	}
	if target, err := os.Readlink(filepath.Join(nginx, "sites-enabled", panelSiteName)); err != nil || target != filepath.Join(nginx, "sites-available", panelSiteName) {
		t.Errorf("niet aangezet: %q %v", target, err)
	}
	if reloads.Load() == 0 {
		t.Error("nginx niet herladen")
	}
	certPEM, _ := os.ReadFile(filepath.Join(nginx, "pinda-ssl", "panel.pindacraft.nl.crt"))
	block, _ := pem.Decode(certPEM)
	if block == nil {
		t.Fatal("geen certificaat")
	}
	if cert, err := x509.ParseCertificate(block.Bytes); err != nil || len(cert.DNSNames) != 1 || cert.DNSNames[0] != "panel.pindacraft.nl" {
		t.Errorf("certificaat: %v", err)
	}
	if info, err := os.Stat(filepath.Join(nginx, "pinda-ssl", "panel.pindacraft.nl.key")); err != nil || info.Mode().Perm() != 0o600 {
		t.Errorf("sleutel: %v", err)
	}
	panel, err := readFileAt(base, panelConfigDir, panelConfigFile, 1<<20)
	if err != nil {
		t.Fatalf("panel.yml niet gemaakt: %v", err)
	}
	values := parseSimpleYAML(bytes.NewReader(panel))
	if values["public-url"] != "https://panel.pindacraft.nl" || values["web.bind"] != "127.0.0.1" || values["behind-proxy"] != "true" ||
		values["web.port"] != "8085" || values["login.two-factor"] != "true" || !strings.Contains(string(panel), "# Bereikbaar maken") {
		t.Errorf("panel.yml:\n%s", panel)
	}
	if status, info := lisa.get("/api/webpanel"); status != 200 || info["ready"] != true || info["url"] != "https://panel.pindacraft.nl" ||
		info["https"] != "cloudflare" || info["site"] != true || info["plugin"].(map[string]any)["behindProxy"] != true {
		t.Errorf("status: %v", info)
	}
	found := false
	for _, record := range dnsRecords(app.config.get(), []string{"203.0.113.5"}) {
		if record.Name == "panel.pindacraft.nl" && record.Proxy == "aan" && record.Content == "203.0.113.5" {
			found = true
		}
	}
	if !found {
		t.Error("geen DNS-record voor het webpaneel")
	}

	// Inloggen als beheerder: alleen als de server draait.
	if status, _ := lisa.post("/api/webpanel/login", nil); status != 400 {
		t.Errorf("inloggen met de server uit: %d", status)
	}
	token := strings.Repeat("Tok3n_-", 7)
	answer := "PindaHost-link: http://10.0.0.5:8085/#login=" + token
	port, commands := devRcon(t, "geheim", func(command string) string {
		if strings.HasPrefix(command, "panel devlink ") {
			return answer
		}
		return "ok"
	})
	_ = app.server.update(func(s *ServerState) {
		s.Installed, s.Java, s.RconPort, s.RconPassword = true, "/usr/bin/java", port, "geheim"
	})
	process.mu.Lock()
	process.state = "active"
	process.mu.Unlock()
	status, result := lisa.post("/api/webpanel/login", nil)
	// Het adres van nginx, niet wat de plugin (nog) denkt.
	if status != 200 || result["url"] != "https://panel.pindacraft.nl/#login="+token {
		t.Errorf("inloggen: %d %v", status, result)
	}
	if got := commands(); len(got) == 0 || got[len(got)-1] != "panel devlink lisa" {
		t.Errorf("RCON: %v", got)
	}
	if entries := app.audit.list(5); entries[0].Action != "inloggen op het webpaneel" || entries[0].User != "lisa" {
		t.Errorf("logboek: %+v", entries[0])
	}
	answer = "PindaHost-fout: het webpaneel draait niet."
	if status, result := lisa.post("/api/webpanel/login", nil); status != 400 || !strings.Contains(result["error"].(string), "draait niet") {
		t.Errorf("fout van de plugin: %d %v", status, result)
	}
	answer = "Unknown or incomplete command"
	if status, result := lisa.post("/api/webpanel/login", nil); status != 400 || !strings.Contains(result["error"].(string), "PindaFramework") {
		t.Errorf("oude plugin: %d %v", status, result)
	}

	// Via de console kan het niet: dan zou een inloglink op een andere naam kunnen.
	for _, command := range []string{"panel devlink rudy", "/PindaFramework:paneel DEVLINK rudy", "execute run panel devlink rudy"} {
		if status, result := lisa.post("/api/server/command", map[string]string{"command": command}); status != 400 || !strings.Contains(result["error"].(string), "knop Webpaneel") {
			t.Errorf("console %q: %d %v", command, status, result)
		}
	}
	if status, _ := lisa.post("/api/server/command", map[string]string{"command": "list"}); status != 200 {
		t.Errorf("gewone opdracht: %d", status)
	}

	// Een poort van iets anders op de VPS: nginx blijft naar 8085 wijzen.
	for _, bad := range []string{"25575", "3306", "22", "25565", "8484"} {
		_ = app.config.update(func(c *Config) { c.Listen = "127.0.0.1:8484" })
		edited, _ := setYAMLValues(string(panel), []yamlSet{{Key: "web.port", Text: bad, Literal: true}})
		if err := writeFileAt(base, panelConfigDir, panelConfigFile, []byte(edited), -1, -1); err != nil {
			t.Fatal(err)
		}
		if port := panelPortAt(app.config.get()); port != 8085 {
			t.Errorf("poort %s: %d", bad, port)
		}
	}

	// Een andere poort in panel.yml, of een eigen public-url: bij de volgende start klopt alles weer.
	edited, _ := setYAMLValues(string(panel), []yamlSet{{Key: "web.port", Text: "8090", Literal: true}, {Key: "public-url", Text: "http://iets-anders"}})
	if err := writeFileAt(base, panelConfigDir, panelConfigFile, []byte(edited), -1, -1); err != nil {
		t.Fatal(err)
	}
	if _, err := app.startCommand(); err != nil {
		t.Fatal(err)
	}
	site, _ = os.ReadFile(filepath.Join(nginx, "sites-available", panelSiteName))
	panel, _ = readFileAt(base, panelConfigDir, panelConfigFile, 1<<20)
	if !strings.Contains(string(site), "proxy_pass http://127.0.0.1:8090;") || parseSimpleYAML(bytes.NewReader(panel))["public-url"] != "https://panel.pindacraft.nl" {
		t.Errorf("na de start:\n%s\n%s", site, panel)
	}
	before := reloads.Load()
	if _, err := app.startCommand(); err != nil || reloads.Load() != before {
		t.Errorf("niets veranderd, toch nginx herladen: %v", err)
	}

	// Adres weghalen: het nginx-blok gaat weg, panel.yml terug naar de standaard. Draait de
	// server, dan herlaadt PindaFramework.
	status, job = rudy.post("/api/webpanel", map[string]string{"domain": ""})
	if status != 200 {
		t.Fatalf("weghalen: %d", status)
	}
	if done := waitJob(t, rudy, job); done["status"] != "done" {
		t.Fatalf("taak: %v", done)
	}
	if _, err := os.Stat(filepath.Join(nginx, "sites-available", panelSiteName)); !os.IsNotExist(err) {
		t.Error("nginx-blok staat er nog")
	}
	if _, err := os.Lstat(filepath.Join(nginx, "sites-enabled", panelSiteName)); !os.IsNotExist(err) {
		t.Error("nginx-blok staat nog aan")
	}
	panel, _ = readFileAt(base, panelConfigDir, panelConfigFile, 1<<20)
	values = parseSimpleYAML(bytes.NewReader(panel))
	if values["public-url"] != "" || values["web.bind"] != "0.0.0.0" || values["behind-proxy"] != "false" || values["web.port"] != "8090" {
		t.Errorf("panel.yml na weghalen:\n%s", panel)
	}
	if got := commands(); got[len(got)-1] != "pinda reload" {
		t.Errorf("niet herladen: %v", got)
	}
	if status, _ := lisa.post("/api/webpanel/login", nil); status != 409 {
		t.Errorf("inloggen na weghalen: %d", status)
	}
}

// Zonder domein: het adres komt van de plugin, en een eigen adres instellen kan niet.
func TestWebpanelWithoutDomain(t *testing.T) {
	fakeNginx(t)
	app, process := testApp(t)
	_ = app.config.update(func(c *Config) { c.Mode = "ip" })
	server := httptest.NewServer(app.routes())
	defer server.Close()
	rudy := loggedIn(t, app, server, "rudy", true)
	if status, _ := rudy.post("/api/webpanel", map[string]string{"domain": "panel.pindacraft.nl"}); status != 400 {
		t.Errorf("adres zonder domein: %d", status)
	}
	token := strings.Repeat("x", 43)
	answer := "PindaHost-link: http://203.0.113.5:8085/#login=" + token
	port, _ := devRcon(t, "pw", func(string) string { return answer })
	_ = app.server.update(func(s *ServerState) { s.Installed, s.RconPort, s.RconPassword = true, port, "pw" })
	process.mu.Lock()
	process.state = "active"
	process.mu.Unlock()
	if status, result := rudy.post("/api/webpanel/login", nil); status != 200 || result["url"] != "http://203.0.113.5:8085/#login="+token {
		t.Errorf("inloggen: %d %v", status, result)
	}
	// Een link die geen http(s) is, komt nooit in de browser.
	answer = "PindaHost-link: javascript:alert(1)#login=" + token
	if status, _ := rudy.post("/api/webpanel/login", nil); status != 400 {
		t.Errorf("rare link: %d", status)
	}
	if status, info := rudy.get("/api/webpanel"); status != 200 || info["ready"] != true || info["port"].(float64) != 8085 {
		t.Errorf("status: %v", info)
	}
}

// De knop "SSL-certificaat aanvragen": ook achter Cloudflare een echt certificaat voor het webpaneel.
func TestWebpanelCertificate(t *testing.T) {
	nginx, _ := fakeNginx(t)
	oldLive, oldInstalled, oldInstall, oldReachable := letsEncryptLive, certbotInstalled, installCertbot, domainReachable
	live := t.TempDir()
	letsEncryptLive = live
	installed := false
	var installs, certbots atomic.Int32
	certbotInstalled = func() bool { return installed }
	installCertbot = func() (string, error) { installs.Add(1); installed = true; return "certbot geïnstalleerd", nil }
	reachable := false
	domainReachable = func(Config, string) bool { return reachable }
	var lastArgs []string
	runCertbot = func(args ...string) (string, error) {
		certbots.Add(1)
		lastArgs = args
		domain := args[len(args)-3] // ... -d <domein> --deploy-hook ...
		for i, arg := range args {
			if arg == "-d" {
				domain = args[i+1]
			}
		}
		// Zoals certbot: fullchain.pem en privkey.pem in live/<domein>
		old := nginxSSLDir
		nginxSSLDir = filepath.Join(live, "tmp")
		cert, key, err := selfSignedCert(domain)
		nginxSSLDir = old
		if err != nil {
			return "", err
		}
		os.MkdirAll(filepath.Join(live, domain), 0o755)
		data, _ := os.ReadFile(cert)
		os.WriteFile(filepath.Join(live, domain, "fullchain.pem"), data, 0o644)
		data, _ = os.ReadFile(key)
		os.WriteFile(filepath.Join(live, domain, "privkey.pem"), data, 0o600)
		return "Successfully received certificate.", nil
	}
	t.Cleanup(func() {
		letsEncryptLive, certbotInstalled, installCertbot, domainReachable = oldLive, oldInstalled, oldInstall, oldReachable
	})

	app, _ := testApp(t)
	_ = app.config.update(func(c *Config) {
		c.Mode, c.Domain, c.Cloudflare = "domain", "dev.pindacraft.nl", true
		c.SiteDomain, c.GameAddress = "pindacraft.nl", "play.pindacraft.nl"
	})
	server := httptest.NewServer(app.routes())
	defer server.Close()
	rudy := loggedIn(t, app, server, "rudy", true)
	lisa := loggedIn(t, app, server, "lisa", false)

	// Zonder adres kan het niet; een developer mag het niet.
	if status, _ := rudy.post("/api/webpanel/certificate", nil); status != 400 {
		t.Errorf("zonder adres: %d", status)
	}
	_ = app.config.update(func(c *Config) { c.PanelDomain = "panel.pindacraft.nl" })
	if status, _ := lisa.post("/api/webpanel/certificate", nil); status != 403 {
		t.Errorf("developer vraagt certificaat aan: %d", status)
	}
	if _, info := rudy.get("/api/webpanel"); info["https"] != "cloudflare" || info["certbot"] != false {
		t.Errorf("status vooraf: %v %v", info["https"], info["certbot"])
	}

	// Het adres wijst nog nergens heen: certbot wel installeren, maar niet aanvragen.
	status, job := rudy.post("/api/webpanel/certificate", nil)
	if status != 200 {
		t.Fatalf("aanvragen: %d %v", status, job)
	}
	done := waitJob(t, rudy, job)
	if done["status"] != "failed" || installs.Load() != 1 || certbots.Load() != 0 {
		t.Fatalf("zonder A-record: %v (installs %d, certbot %d)", done["status"], installs.Load(), certbots.Load())
	}

	// Nu wel: certificaat aanvragen en in nginx zetten (ook achter Cloudflare).
	reachable = true
	status, job = rudy.post("/api/webpanel/certificate", nil)
	if status != 200 {
		t.Fatalf("aanvragen: %d", status)
	}
	if done := waitJob(t, rudy, job); done["status"] != "done" {
		t.Fatalf("taak: %v", done)
	}
	if installs.Load() != 1 || certbots.Load() != 1 {
		t.Errorf("installs %d, certbot %d", installs.Load(), certbots.Load())
	}
	joined := strings.Join(lastArgs, " ")
	for _, want := range []string{"certonly", "--webroot", "--cert-name panel.pindacraft.nl", "-d panel.pindacraft.nl", "--deploy-hook systemctl reload nginx"} {
		if !strings.Contains(joined, want) {
			t.Errorf("certbot mist %q: %s", want, joined)
		}
	}
	site, err := os.ReadFile(filepath.Join(nginx, "sites-available", panelSiteName))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"ssl_certificate " + filepath.Join(live, "panel.pindacraft.nl", "fullchain.pem") + ";",
		"ssl_certificate_key " + filepath.Join(live, "panel.pindacraft.nl", "privkey.pem") + ";", "real_ip_header CF-Connecting-IP;"} {
		if !strings.Contains(string(site), want) {
			t.Errorf("nginx-blok mist %q", want)
		}
	}
	// De controle van Let's Encrypt op poort 80 én 443 (Cloudflare met "Always Use HTTPS")
	if strings.Count(string(site), "location /.well-known/acme-challenge/") != 2 {
		t.Errorf("acme-challenge niet op beide poorten:\n%s", site)
	}
	_, info := rudy.get("/api/webpanel")
	cert, _ := info["certificate"].(map[string]any)
	if info["https"] != "letsencrypt" || cert == nil || info["certbot"] != true {
		t.Errorf("status na aanvragen: %v %v %v", info["https"], cert, info["certbot"])
	}
}

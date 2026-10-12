package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Het webpaneel van PindaFramework: het beheerpaneel voor staff (in-game /panel). Het draait in
// de Minecraft-server zelf, op poort 8085.
//
//   - Met een domein zet het dev-paneel het achter nginx op een eigen adres (bijv.
//     panel.pindacraft.nl), met HTTPS (via Cloudflare, of Let's Encrypt). In modules/panel.yml van
//     de plugin komen dat adres, bind 127.0.0.1 en behind-proxy, zodat de inloglinks uit het spel
//     kloppen en de plugin het echte IP van bezoekers ziet. Dat wordt bij elke start opnieuw
//     gezet, net als de RCON-instellingen in server.properties.
//   - Zonder domein (alleen IP) gaat de poort open in de firewall; de plugin vindt het IP zelf.
//
// Met de knop "Webpaneel" logt een gebruiker van het dev-paneel er meteen op in als beheerder:
// het dev-paneel vraagt via RCON een eenmalige link op (/panel devlink). De 2FA is dan al gedaan,
// bij het inloggen op het dev-paneel.

const (
	panelSiteName    = "pinda-panel"
	panelConfigDir   = pluginRel + "/modules"
	panelConfigFile  = "panel.yml"
	defaultPanelPort = 8085
)

var (
	// Waar nginx zijn instellingen heeft (in tests een tijdelijke map).
	nginxDir = "/etc/nginx"
	// De eigen certificaten (zoals de installer ze maakt, voor Cloudflare in modus Full).
	nginxSSLDir = "/etc/nginx/pinda-ssl"
	nginxTest   = func() (string, error) { return runQuiet(30*time.Second, "nginx", "-t") }
	nginxReload = func() (string, error) { return runQuiet(30*time.Second, "systemctl", "reload", "nginx") }
	// certbot vraagt een certificaat aan (in tests vervangen).
	runCertbot = func(args ...string) (string, error) { return runQuiet(5*time.Minute, "certbot", args...) }
)

func nginxSitePath(name string) string {
	return filepath.Join(nginxDir, "sites-available", name)
}

// ============================================================ API

func (a *App) registerWebpanel(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/webpanel", a.api(member, a.handleWebpanel))
	mux.HandleFunc("POST /api/webpanel", a.api(admin, a.handleWebpanelSave))
	mux.HandleFunc("POST /api/webpanel/login", a.api(member, a.handleWebpanelLogin))
}

// suggestedPanelDomain: panel.<domein>, bijv. panel.pindacraft.nl bij dev.pindacraft.nl.
func suggestedPanelDomain(config Config) string {
	base := config.SiteDomain
	if base == "" && config.Domain != "" {
		parts := strings.Split(config.Domain, ".")
		if len(parts) >= 2 {
			base = strings.Join(parts[len(parts)-2:], ".")
		}
	}
	if base == "" {
		return ""
	}
	return "panel." + base
}

// panelURL: het adres van het webpaneel zoals het dev-paneel het kent (leeg = nog niet ingesteld).
func panelURL(config Config, port int) string {
	if config.Mode == "domain" {
		if config.PanelDomain == "" {
			return ""
		}
		return "https://" + config.PanelDomain
	}
	addresses := publicAddresses()
	for _, address := range addresses {
		if !strings.Contains(address, ":") {
			return fmt.Sprintf("http://%s:%d", address, port)
		}
	}
	return ""
}

func (a *App) handleWebpanel(q *Request) (any, error) {
	config := a.config.get()
	base := config.BaseDir
	port := panelPortAt(config)
	values := map[string]string{}
	exists := false
	if data, err := readFileAt(base, panelConfigDir, panelConfigFile, 1<<20); err == nil {
		values = parseSimpleYAML(strings.NewReader(string(data)))
		exists = true
	}
	https := "none"
	if config.Mode == "domain" && config.PanelDomain != "" {
		switch {
		case config.Cloudflare:
			https = "cloudflare"
		default:
			if _, err := letsEncryptCert(config.PanelDomain); err == nil {
				https = "letsencrypt"
			} else {
				https = "self-signed"
			}
		}
	}
	_, siteErr := os.Stat(nginxSitePath(panelSiteName))
	result := map[string]any{
		"mode": config.Mode, "cloudflare": config.Cloudflare, "domain": config.PanelDomain,
		"suggested": suggestedPanelDomain(config), "port": port, "url": panelURL(config, port),
		"https": https, "site": siteErr == nil, "ready": config.Mode != "domain" || config.PanelDomain != "",
		"plugin": map[string]any{"exists": exists, "publicUrl": values["public-url"], "bind": values["web.bind"],
			"behindProxy": values["behind-proxy"] == "true"},
		"running": a.process.Status().State == "active",
	}
	if config.PanelDomain != "" {
		result["dns"] = dnsRecordsFor(config, config.PanelDomain)
	}
	return result, nil
}

// checkPanelDomain: een geldig adres dat niet al voor iets anders in gebruik is.
func checkPanelDomain(config Config, domain string) error {
	if domain == "" {
		return nil
	}
	if !hostnamePattern.MatchString(domain) {
		return badRequest("%q is geen geldig adres. Bijvoorbeeld: %s", domain, fallback(suggestedPanelDomain(config), "panel.jouwdomein.nl"))
	}
	taken := map[string]string{config.Domain: "het dev-paneel", config.SiteDomain: "de website",
		"www." + config.SiteDomain: "de website", config.GameAddress: "het adres voor spelers"}
	if what, ok := taken[domain]; ok && domain != "" && what != "" {
		return badRequest("%s is al het adres van %s. Kies een eigen adres, bijv. %s.", domain, what, fallback(suggestedPanelDomain(config), "panel.jouwdomein.nl"))
	}
	return nil
}

func (a *App) handleWebpanelSave(q *Request) (any, error) {
	var body struct {
		Domain string `json:"domain"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	config := a.config.get()
	if config.Mode != "domain" {
		return nil, badRequest("Zonder domein is het webpaneel bereikbaar op het IP-adres van de VPS; een eigen adres kan alleen als het dev-paneel ook op een domein staat.")
	}
	domain := strings.ToLower(strings.TrimSpace(body.Domain))
	if err := checkPanelDomain(config, domain); err != nil {
		return nil, err
	}
	if err := a.config.update(func(c *Config) { c.PanelDomain = domain }); err != nil {
		return nil, err
	}
	user, ip := q.user.Name, q.ip
	title := "Webpaneel op " + domain
	if domain == "" {
		title = "Adres van het webpaneel weghalen"
	}
	previous := config.PanelDomain
	job := a.jobs.start(title, user, func(say func(string, ...any)) error {
		err := a.setupWebpanel(previous, say)
		if err != nil {
			a.audit.add(user, ip, "webpaneel instellen mislukt", domain, err.Error())
			return err
		}
		a.audit.add(user, ip, "webpaneel ingesteld", fallback(domain, "(geen adres)"), "")
		return nil
	})
	return job, nil
}

// webpanelMu: één tegelijk aan nginx en panel.yml van het webpaneel (opslaan, setup, starten).
var webpanelMu sync.Mutex

// setupWebpanel zet nginx, het certificaat en panel.yml goed voor het huidige adres. previous is
// het adres van daarvoor: wordt het adres weggehaald, dan gaat panel.yml terug naar de standaard
// (alleen als daar nog dat oude adres in stond).
func (a *App) setupWebpanel(previous string, say func(string, ...any)) error {
	webpanelMu.Lock()
	defer webpanelMu.Unlock()
	config := a.config.get()
	domain := config.PanelDomain
	var changed bool
	var err error
	if domain == "" {
		say("Het nginx-blok van het webpaneel weghalen…")
		if err := removeNginxSite(panelSiteName); err != nil {
			return err
		}
		changed, err = a.resetPanelConfig(previous)
	} else {
		say("nginx instellen voor https://%s (naar het webpaneel op poort %d)…", domain, panelPortAt(config))
		if err := a.applyPanelSite(); err != nil {
			return err
		}
		if !config.Cloudflare {
			if _, err := letsEncryptCert(domain); err != nil {
				a.requestPanelCertificate(domain, say)
			}
		}
		changed, err = a.ensurePanelConfig()
	}
	switch {
	case err != nil:
		return fmt.Errorf("modules/panel.yml: %w", err)
	case changed:
		say("modules/panel.yml van PindaFramework bijgewerkt.")
	}
	if changed && a.process.Status().State == "active" {
		state := a.server.get()
		if _, err := rconCommand(state.RconPort, state.RconPassword, "pinda reload", 15*time.Second); err != nil {
			say("De server kon niet herladen (%s); het nieuwe adres werkt na een herstart.", err.Error())
		} else {
			say("PindaFramework herladen: het nieuwe adres werkt meteen.")
		}
	}
	if domain != "" {
		if config.Cloudflare {
			say("Klaar. Maak in Cloudflare het A-record voor %s aan (proxy aan, oranje wolk) als dat er nog niet is.", domain)
		} else {
			say("Klaar: https://%s", domain)
		}
	}
	return nil
}

// requestPanelCertificate: zonder Cloudflare een certificaat van Let's Encrypt (lukt alleen als
// het adres al naar deze server wijst). Mislukt het, dan blijft het eigen certificaat staan.
func (a *App) requestPanelCertificate(domain string, say func(string, ...any)) {
	if _, err := exec.LookPath("certbot"); err != nil {
		say("certbot is er niet: het webpaneel gebruikt voorlopig een eigen certificaat (je browser waarschuwt).")
		return
	}
	if !pointsHere(domain) {
		say("%s wijst (nog) niet naar deze server. Maak eerst het A-record aan en klik dan nog eens op Opslaan voor een certificaat van Let's Encrypt.", domain)
		return
	}
	say("Certificaat aanvragen bij Let's Encrypt voor %s…", domain)
	config := a.config.get()
	out, err := runCertbot("certonly", "--webroot", "-w", filepath.Join(config.BaseDir, "acme"), "--non-interactive", "--agree-tos",
		"--register-unsafely-without-email", "--keep-until-expiring", "--cert-name", domain, "-d", domain,
		"--deploy-hook", "systemctl reload nginx")
	if err != nil {
		lines := strings.Split(strings.TrimSpace(out), "\n")
		if len(lines) > 6 {
			lines = lines[len(lines)-6:]
		}
		say("Let's Encrypt gaf geen certificaat:\n%s", strings.Join(lines, "\n"))
		return
	}
	if err := a.applyPanelSite(); err != nil {
		say("Certificaat ontvangen, maar nginx instellen lukte niet: %s", err.Error())
		return
	}
	say("Certificaat ontvangen; het wordt vanzelf verlengd.")
}

// ============================================================ inloggen als beheerder

var (
	// Een opdracht die een inloglink maakt (ook via execute of met pindaframework: ervoor).
	devlinkCommand  = regexp.MustCompile(`(?i)\bdevlink\b`)
	devLinkPattern  = regexp.MustCompile(`PindaHost-link: (https?://[^\s#?/]+(?:/[^\s#?]*)?)#login=([A-Za-z0-9_-]{16,128})`)
	devErrorPattern = regexp.MustCompile(`PindaHost-fout: ([^\n]+)`)
)

// parseDevLink haalt de link uit het antwoord van "panel devlink". Een fout van de plugin komt
// terug als tweede waarde.
func parseDevLink(response string) (base, token, problem string) {
	if match := devLinkPattern.FindStringSubmatch(response); match != nil {
		return strings.TrimRight(match[1], "/"), match[2], ""
	}
	if match := devErrorPattern.FindStringSubmatch(response); match != nil {
		return "", "", strings.TrimSpace(match[1])
	}
	return "", "", ""
}

func (a *App) handleWebpanelLogin(q *Request) (any, error) {
	config := a.config.get()
	if config.Mode == "domain" && config.PanelDomain == "" {
		return nil, &apiError{http.StatusConflict, "Het webpaneel heeft nog geen adres. Kies er een bij Server › Instellingen."}
	}
	if status := a.process.Status(); status.State != "active" {
		return nil, badRequest("De server staat uit, en daarmee ook het webpaneel. Start eerst de server.")
	}
	state := a.server.get()
	response, err := rconCommand(state.RconPort, state.RconPassword, "panel devlink "+q.user.Name, 10*time.Second)
	if err != nil {
		if errors.Is(err, errRconAuth) {
			return nil, badRequest("%s", capitalize(err.Error())+".")
		}
		return nil, badRequest("Geen verbinding met de server: %s. Is hij al helemaal opgestart?", err.Error())
	}
	base, token, problem := parseDevLink(response)
	if problem != "" {
		return nil, badRequest("Het webpaneel: %s", problem)
	}
	if token == "" {
		return nil, badRequest("De server gaf geen inloglink. Staat PindaFramework aan en is het bijgewerkt (Server › Plugins)?")
	}
	if config.Mode == "domain" {
		// Wat nginx doorstuurt; de plugin kent dat adres pas na een herstart of herladen.
		base = "https://" + config.PanelDomain
	}
	q.log("inloggen op het webpaneel", "", "")
	return map[string]string{"url": base + "/#login=" + token}, nil
}

// ============================================================ panel.yml

// panelPortAt: de poort van het webpaneel uit panel.yml (standaard 8085). panel.yml is van de
// Minecraft-gebruiker; nginx (root) stuurt er verkeer heen. Daarom nooit naar een poort onder
// 1024 of van iets anders op deze VPS (RCON, MariaDB, het dev-paneel).
func panelPortAt(config Config) int {
	data, err := readFileAt(config.BaseDir, panelConfigDir, panelConfigFile, 1<<20)
	if err != nil {
		return defaultPanelPort
	}
	port, err := strconv.Atoi(parseSimpleYAML(strings.NewReader(string(data)))["web.port"])
	if err != nil || port < 1024 || port > 65535 || port == rconPort || port == 3306 || port == config.GamePort || port == listenPort(config) {
		return defaultPanelPort
	}
	return port
}

// listenPort: de poort van het dev-paneel zelf.
func listenPort(config Config) int {
	_, value, err := net.SplitHostPort(config.Listen)
	if err != nil {
		return 0
	}
	port, _ := strconv.Atoi(value)
	return port
}

// managedPanelValues: wat het dev-paneel in panel.yml zet voor een adres achter nginx.
func managedPanelValues(domain string) []yamlSet {
	return []yamlSet{
		{Key: "web.bind", Text: "127.0.0.1"},
		{Key: "public-url", Text: "https://" + domain},
		{Key: "behind-proxy", Text: "true", Literal: true},
	}
}

// defaultPanelValues: de standaard van de plugin (die zoekt dan zelf zijn adres).
var defaultPanelValues = []yamlSet{
	{Key: "web.bind", Text: "0.0.0.0"},
	{Key: "public-url", Text: ""},
	{Key: "behind-proxy", Text: "false", Literal: true},
}

// ensurePanelConfig zet panel.yml goed (en in IP-modus de poort open in de firewall). Gebeurt bij
// elke start van de server. Staat panel.yml er nog niet (eerste start), dan komt hij uit de jar
// van PindaFramework, met alle uitleg erin. Zonder adres blijft panel.yml zoals hij is: dan kan
// een beheerder hem ook zelf instellen.
func (a *App) ensurePanelConfig() (bool, error) {
	config := a.config.get()
	if config.Mode != "domain" {
		openPanelPort(panelPortAt(config))
		return false, nil
	}
	if config.PanelDomain == "" {
		return false, nil
	}
	data, err := readFileAt(config.BaseDir, panelConfigDir, panelConfigFile, 1<<20)
	if errors.Is(err, os.ErrNotExist) {
		data, err = a.bundledPanelConfig()
		if err != nil {
			return false, nil // nog geen PindaFramework: dan bij de volgende start
		}
	} else if err != nil {
		return false, err
	}
	content, changed := setYAMLValues(string(data), managedPanelValues(config.PanelDomain))
	if !changed {
		return false, a.syncPanelSite()
	}
	uid, gid := lookupOwner(config.ServiceUser)
	if err := writeFileAt(config.BaseDir, panelConfigDir, panelConfigFile, []byte(content), uid, gid); err != nil {
		return false, err
	}
	return true, a.syncPanelSite()
}

// resetPanelConfig: het adres is weggehaald. Stond in panel.yml nog het oude adres van het
// dev-paneel, dan weer de standaard; heeft een beheerder er zelf iets anders neergezet, dan blijft dat.
func (a *App) resetPanelConfig(previous string) (bool, error) {
	config := a.config.get()
	data, err := readFileAt(config.BaseDir, panelConfigDir, panelConfigFile, 1<<20)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if previous == "" || parseSimpleYAML(strings.NewReader(string(data)))["public-url"] != "https://"+previous {
		return false, nil
	}
	content, changed := setYAMLValues(string(data), defaultPanelValues)
	if !changed {
		return false, nil
	}
	uid, gid := lookupOwner(config.ServiceUser)
	return true, writeFileAt(config.BaseDir, panelConfigDir, panelConfigFile, []byte(content), uid, gid)
}

// syncPanelSite: is de poort in panel.yml veranderd, dan nginx daarop aanpassen.
func (a *App) syncPanelSite() error {
	config := a.config.get()
	if config.Mode != "domain" || config.PanelDomain == "" {
		return nil
	}
	return a.applyPanelSite()
}

// bundledPanelConfig: modules/panel.yml uit de jar van PindaFramework.
func (a *App) bundledPanelConfig() ([]byte, error) {
	plugins, err := a.listPlugins()
	if err != nil {
		return nil, err
	}
	root, err := a.serverRoot().open()
	if err != nil {
		return nil, err
	}
	defer root.Close()
	for _, plugin := range plugins {
		if plugin.Managed != "pindaframework" || !plugin.Enabled {
			continue
		}
		file, info, err := openRegular(root, "plugins/"+plugin.File)
		if err != nil {
			continue
		}
		data, err := zipEntry(file, info.Size(), "modules/panel.yml", 1<<20)
		file.Close()
		if err == nil {
			return data, nil
		}
	}
	return nil, os.ErrNotExist
}

// zipEntry leest één bestand uit een zip (of jar), met een grens aan de grootte.
func zipEntry(file io.ReaderAt, size int64, name string, max int64) ([]byte, error) {
	if size > 512<<20 {
		return nil, errors.New("te groot")
	}
	reader, err := openZip(file, size, 100_000)
	if err != nil {
		return nil, err
	}
	for _, entry := range reader.File {
		if entry.Name != name {
			continue
		}
		if entry.UncompressedSize64 > uint64(max) {
			return nil, errors.New("te groot")
		}
		content, err := entry.Open()
		if err != nil {
			return nil, err
		}
		defer content.Close()
		data, err := io.ReadAll(io.LimitReader(content, max+1))
		if err != nil {
			return nil, err
		}
		if int64(len(data)) > max {
			return nil, errors.New("te groot")
		}
		return data, nil
	}
	return nil, os.ErrNotExist
}

// openPanelPort: zonder domein is het webpaneel rechtstreeks bereikbaar op zijn eigen poort.
func openPanelPort(port int) {
	if _, err := exec.LookPath("ufw"); err == nil {
		_, _ = runQuiet(15*time.Second, "ufw", "allow", fmt.Sprintf("%d/tcp", port), "comment", "PindaFramework webpaneel")
	}
}

// yamlSet: één waarde voor setYAMLValues. Key met punten voor een sectie (web.bind).
type yamlSet struct {
	Key     string
	Text    string
	Literal bool // true/false of een getal: zonder aanhalingstekens
}

func (v yamlSet) yaml() string {
	if v.Literal {
		return v.Text
	}
	return quoteYAML(v.Text)
}

// setYAMLValues past waarden aan in een YAML-bestand zoals Bukkit het schrijft, en laat de rest
// (uitleg, volgorde, andere instellingen) staan. Een ontbrekende sleutel komt erbij. Alleen voor
// eenvoudige bestanden: secties met "sleutel: waarde".
func setYAMLValues(content string, values []yamlSet) (string, bool) {
	trailing := strings.HasSuffix(content, "\n")
	lines := strings.Split(strings.TrimSuffix(content, "\n"), "\n")
	if content == "" {
		lines = nil
	}
	type keyLine struct {
		index, indent int
		section       bool
		value         string
	}
	found := map[string]keyLine{}
	type level struct {
		indent int
		key    string
	}
	var stack []level
	for index, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") || strings.HasPrefix(trimmed, "-") {
			continue
		}
		indent := len(line) - len(strings.TrimLeft(line, " "))
		colon := strings.Index(trimmed, ":")
		if colon <= 0 {
			continue
		}
		key := strings.Trim(strings.TrimSpace(trimmed[:colon]), `"'`)
		value := strings.TrimSpace(trimmed[colon+1:])
		for len(stack) > 0 && stack[len(stack)-1].indent >= indent {
			stack = stack[:len(stack)-1]
		}
		full := key
		if len(stack) > 0 {
			full = stack[len(stack)-1].key + "." + key
		}
		if _, seen := found[full]; !seen {
			found[full] = keyLine{index: index, indent: indent, section: value == "", value: value}
		}
		if value == "" {
			stack = append(stack, level{indent: indent, key: full})
		}
	}

	changed := false
	inserts := map[int][]string{} // na regel i (-1 = aan het eind)
	for _, value := range values {
		parts := strings.Split(value.Key, ".")
		name := parts[len(parts)-1]
		if line, ok := found[value.Key]; ok {
			if line.section || unquoteYAML(line.value) == value.Text {
				continue
			}
			lines[line.index] = strings.Repeat(" ", line.indent) + name + ": " + value.yaml()
			changed = true
			continue
		}
		// De diepste sectie die er al is, en de rest eronder aanmaken.
		after, indent, depth := -1, 0, 0
		for depth = len(parts) - 1; depth > 0; depth-- {
			if section, ok := found[strings.Join(parts[:depth], ".")]; ok && section.section {
				after, indent = section.index, section.indent+2
				break
			}
		}
		var added []string
		for i := depth; i < len(parts)-1; i++ {
			added = append(added, strings.Repeat(" ", indent)+parts[i]+":")
			indent += 2
		}
		added = append(added, strings.Repeat(" ", indent)+name+": "+value.yaml())
		inserts[after] = append(inserts[after], added...)
		changed = true
	}
	if !changed {
		return content, false
	}
	positions := make([]int, 0, len(inserts))
	for position := range inserts {
		if position >= 0 {
			positions = append(positions, position)
		}
	}
	sort.Sort(sort.Reverse(sort.IntSlice(positions)))
	for _, position := range positions {
		rest := append(append([]string{}, inserts[position]...), lines[position+1:]...)
		lines = append(lines[:position+1], rest...)
	}
	if extra := inserts[-1]; len(extra) > 0 {
		if len(lines) > 0 && strings.TrimSpace(lines[len(lines)-1]) != "" {
			lines = append(lines, "")
		}
		lines = append(lines, extra...)
	}
	result := strings.Join(lines, "\n")
	if trailing || content == "" {
		result += "\n"
	}
	return result, true
}

// ============================================================ nginx

// applyPanelSite schrijft het nginx-blok voor het webpaneel (met het certificaat dat er is).
func (a *App) applyPanelSite() error {
	config := a.config.get()
	domain := config.PanelDomain
	if config.Mode != "domain" || domain == "" {
		return removeNginxSite(panelSiteName)
	}
	cert, key := "", ""
	live := filepath.Join("/etc/letsencrypt/live", domain)
	if _, err := letsEncryptCert(domain); err == nil && !config.Cloudflare {
		cert, key = filepath.Join(live, "fullchain.pem"), filepath.Join(live, "privkey.pem")
	} else {
		var err error
		if cert, key, err = selfSignedCert(domain); err != nil {
			return fmt.Errorf("certificaat maken: %w", err)
		}
	}
	listenSSL, http2Line := nginxHTTP2()
	_, err := os.Stat("/proc/net/if_inet6")
	content := panelSiteConfig(panelSite{
		Domain: domain, Port: panelPortAt(config), Cert: cert, Key: key, Cloudflare: config.Cloudflare,
		Acme: filepath.Join(config.BaseDir, "acme"), ListenSSL: listenSSL, HTTP2Line: http2Line, IPv6: err == nil,
	})
	_, err = installNginxSite(panelSiteName, content)
	return err
}

type panelSite struct {
	Domain, Cert, Key, Acme, ListenSSL, HTTP2Line string
	Port                                          int
	Cloudflare, IPv6                              bool
}

// panelSiteConfig: het nginx-blok dat het webpaneel van de plugin doorgeeft.
func panelSiteConfig(site panelSite) string {
	listen6 := func(port string) string {
		if !site.IPv6 {
			return ""
		}
		return "\tlisten [::]:" + port + ";\n"
	}
	realIP := ""
	if site.Cloudflare {
		// Achter Cloudflare: het echte IP van de bezoeker staat in CF-Connecting-IP. Alleen
		// Cloudflare kan bij poort 443 (firewall), dus die header is te vertrouwen.
		var b strings.Builder
		b.WriteString("\t# Het echte IP van bezoekers (alleen Cloudflare kan bij deze poort).\n")
		for _, cidr := range cloudflareRanges {
			b.WriteString("\tset_real_ip_from " + cidr + ";\n")
		}
		b.WriteString("\treal_ip_header CF-Connecting-IP;\n")
		realIP = b.String()
	}
	return fmt.Sprintf(`# Beheerd door PindaHost (dev-paneel): het webpaneel van PindaFramework op %[1]s.
# Pas het adres aan in het dev-paneel (Server › Instellingen); dit bestand wordt dan opnieuw geschreven.
server {
	listen 80;
%[2]s	server_name %[1]s;
	server_tokens off;
	location /.well-known/acme-challenge/ {
		root %[3]s;
	}
	location / {
		return 301 https://$host$request_uri;
	}
}

server {
	listen 443 %[4]s;
%[5]s%[6]s	server_name %[1]s;
	server_tokens off;
	ssl_certificate %[7]s;
	ssl_certificate_key %[8]s;
	ssl_protocols TLSv1.2 TLSv1.3;
	client_max_body_size 16m;
%[9]s
	location / {
		proxy_pass http://127.0.0.1:%[10]d;
		proxy_http_version 1.1;
		proxy_set_header Host $host;
		# De plugin leest het laatste adres hieruit (behind-proxy: true in panel.yml).
		proxy_set_header X-Forwarded-For $remote_addr;
		proxy_set_header X-Forwarded-Proto $scheme;
		proxy_read_timeout 60s;
	}

	# Staat de Minecraft-server uit, dan ook het webpaneel.
	error_page 502 504 = @offline;
	location @offline {
		default_type text/html;
		add_header Cache-Control no-store always;
		return 503 "<!doctype html><html lang='nl'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Webpaneel offline</title><body style='font-family:system-ui,sans-serif;background:#0f0e0c;color:#f0eadf;display:grid;place-items:center;min-height:100vh;margin:0'><div style='max-width:420px;padding:24px;text-align:center'><h1 style='font-size:22px'>Het webpaneel is offline</h1><p style='color:#8f8476'>Het draait in de Minecraft-server, en die staat nu uit of start net op. Probeer het zo opnieuw.</p></div></body></html>";
	}
}
`, site.Domain, listen6("80"), site.Acme, site.ListenSSL, listen6("443 "+site.ListenSSL), site.HTTP2Line, site.Cert, site.Key, realIP, site.Port)
}

// installNginxSite schrijft een blok in sites-available, zet het aan, test nginx en laadt opnieuw.
// Klopt de test niet, dan komt het vorige blok terug en blijft alles zoals het was. Is er niets
// veranderd, dan gebeurt er niets (geeft false).
func installNginxSite(name, content string) (bool, error) {
	path := nginxSitePath(name)
	enabled := filepath.Join(nginxDir, "sites-enabled", name)
	previous, readErr := os.ReadFile(path)
	_, linkErr := os.Lstat(enabled)
	if readErr == nil && linkErr == nil && string(previous) == content {
		return false, nil
	}
	restore := func() {
		if readErr == nil {
			_ = writeFileAtomic(path, previous, 0o644)
		} else {
			_ = os.Remove(path)
		}
		if linkErr != nil {
			_ = os.Remove(enabled)
		}
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return false, err
	}
	if err := writeFileAtomic(path, []byte(content), 0o644); err != nil {
		return false, err
	}
	if linkErr != nil {
		if err := os.MkdirAll(filepath.Dir(enabled), 0o755); err != nil {
			restore()
			return false, err
		}
		if err := os.Symlink(path, enabled); err != nil {
			restore()
			return false, err
		}
	}
	if out, err := nginxTest(); err != nil {
		restore()
		return false, fmt.Errorf("de nginx-instellingen kloppen niet: %s", strings.TrimSpace(out))
	}
	if out, err := nginxReload(); err != nil {
		return true, fmt.Errorf("nginx herladen: %s", strings.TrimSpace(out))
	}
	return true, nil
}

// removeNginxSite haalt een blok weg (als het er is) en laadt nginx opnieuw.
func removeNginxSite(name string) error {
	path := nginxSitePath(name)
	enabled := filepath.Join(nginxDir, "sites-enabled", name)
	_, pathErr := os.Lstat(path)
	_, linkErr := os.Lstat(enabled)
	if pathErr != nil && linkErr != nil {
		return nil
	}
	_ = os.Remove(enabled)
	_ = os.Remove(path)
	if out, err := nginxReload(); err != nil {
		return fmt.Errorf("nginx herladen: %s", strings.TrimSpace(out))
	}
	return nil
}

// selfSignedCert: een eigen certificaat (10 jaar) voor een adres, zoals de installer ze maakt.
// Achter Cloudflare (modus Full) is dat genoeg. Bestaat het al, dan blijft het.
func selfSignedCert(domain string) (string, string, error) {
	certFile := filepath.Join(nginxSSLDir, domain+".crt")
	keyFile := filepath.Join(nginxSSLDir, domain+".key")
	if info, err := os.Stat(certFile); err == nil && info.Size() > 0 {
		if info, err := os.Stat(keyFile); err == nil && info.Size() > 0 {
			return certFile, keyFile, nil
		}
	}
	if err := os.MkdirAll(nginxSSLDir, 0o700); err != nil {
		return "", "", err
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	template := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: domain},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{domain},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return "", "", err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return "", "", err
	}
	if err := writeFileAtomic(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		return "", "", err
	}
	if err := writeFileAtomic(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
		return "", "", err
	}
	return certFile, keyFile, nil
}

// startCommand: zoals serverCommand, maar zet eerst panel.yml goed. Voor elke start.
func (a *App) startCommand() (ServerCommand, error) {
	webpanelMu.Lock()
	_, err := a.ensurePanelConfig()
	webpanelMu.Unlock()
	if err != nil {
		log.Printf("webpaneel klaarzetten: %v", err)
	}
	return a.serverCommand()
}

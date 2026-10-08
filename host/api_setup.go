package main

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var hostnamePattern = regexp.MustCompile(`^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$`)

func (a *App) registerSetup(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/setup/account", a.api(public, a.handleSetupAccount))
	mux.HandleFunc("POST /api/setup/account/verify", a.api(public, a.handleSetupAccountVerify))
	mux.HandleFunc("GET /api/setup", a.api(member, a.handleSetupInfo))
	mux.HandleFunc("POST /api/setup/server", a.api(admin, a.handleSetupServer))
	mux.HandleFunc("POST /api/setup/database", a.api(admin, a.handleSetupDatabase))
	mux.HandleFunc("POST /api/setup/finish", a.api(admin, a.handleSetupFinish))
}

// DNSRecord is een regel die je bij je domeinnaam (bijv. in Cloudflare) aanmaakt.
type DNSRecord struct {
	Type    string `json:"type"`
	Name    string `json:"name"`
	Content string `json:"content"`
	Proxy   string `json:"proxy,omitempty"` // Cloudflare-proxy: "aan" of "uit"
	Note    string `json:"note,omitempty"`
}

func (a *App) handleSetupInfo(q *Request) (any, error) {
	config := a.config.get()
	plugin, exists, err := readPluginDB(config.pluginDir())
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"mode":        config.Mode,
		"domain":      config.Domain,
		"cloudflare":  config.Cloudflare,
		"serverName":  config.ServerName,
		"siteDomain":  config.SiteDomain,
		"gameAddress": config.GameAddress,
		"gamePort":    config.GamePort,
		"database":    config.Database,
		"setupDone":   config.SetupDone,
		"addresses":   publicAddresses(),
		"dns":         dnsRecords(config, publicAddresses()),
		"mariadb":     a.mariadb.status(),
		"plugin":      map[string]any{"exists": exists, "type": plugin.Type},
		"fingerprint": a.certificateFingerprint(),
	}, nil
}

// dnsRecords: welke DNS-regels nodig zijn voor het paneel, de website en de Minecraft-server.
func dnsRecords(config Config, addresses []string) []DNSRecord {
	ipv4, ipv6 := "", ""
	for _, address := range addresses {
		if strings.Contains(address, ":") {
			if ipv6 == "" {
				ipv6 = address
			}
		} else if ipv4 == "" {
			ipv4 = address
		}
	}
	if ipv4 == "" {
		ipv4 = "<IP van je VPS>"
	}
	web := "uit"
	if config.Cloudflare {
		web = "aan"
	}
	var records []DNSRecord
	add := func(name, proxy, note string) {
		if name == "" {
			return
		}
		records = append(records, DNSRecord{Type: "A", Name: name, Content: ipv4, Proxy: proxy, Note: note})
		if ipv6 != "" {
			records = append(records, DNSRecord{Type: "AAAA", Name: name, Content: ipv6, Proxy: proxy})
		}
	}
	if config.Mode == "domain" {
		add(config.Domain, web, "Het dev-paneel")
	}
	add(config.SiteDomain, web, "De website van de server")
	if config.GameAddress != "" {
		add(config.GameAddress, "uit", "Minecraft kan niet door de Cloudflare-proxy: zet de wolk hier op grijs (DNS only).")
		port := config.GamePort
		if port == 0 {
			port = 25565
		}
		records = append(records, DNSRecord{
			Type:    "SRV",
			Name:    "_minecraft._tcp." + config.GameAddress,
			Content: fmt.Sprintf("0 5 %d %s", port, config.GameAddress),
			Note:    "Prioriteit 0, gewicht 5, poort " + fmt.Sprint(port) + ", doel " + config.GameAddress + ". Zo hoeven spelers nooit een poort te typen.",
		})
	}
	return records
}

func (a *App) handleSetupServer(q *Request) (any, error) {
	var body struct {
		ServerName  string `json:"serverName"`
		SiteDomain  string `json:"siteDomain"`
		GameAddress string `json:"gameAddress"`
		GamePort    int    `json:"gamePort"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(body.ServerName)
	if len([]rune(name)) < 1 || len([]rune(name)) > 32 {
		return nil, badRequest("Een servernaam is 1 tot 32 tekens.")
	}
	site := strings.ToLower(strings.TrimSpace(body.SiteDomain))
	game := strings.ToLower(strings.TrimSpace(body.GameAddress))
	if site != "" && !hostnamePattern.MatchString(site) {
		return nil, badRequest("%q is geen geldige domeinnaam.", site)
	}
	if !hostnamePattern.MatchString(game) {
		return nil, badRequest("Vul het adres voor spelers in, bijvoorbeeld play.%s", fallback(site, "jouwdomein.nl"))
	}
	port := body.GamePort
	if port == 0 {
		port = 25565
	}
	if port < 1024 || port > 65535 {
		return nil, badRequest("Kies een poort tussen 1024 en 65535 (standaard 25565).")
	}
	if err := a.config.update(func(c *Config) {
		c.ServerName, c.SiteDomain, c.GameAddress, c.GamePort = name, site, game, port
	}); err != nil {
		return nil, err
	}
	q.log("setup", "server", fmt.Sprintf("%s, %s, %s:%d", name, site, game, port))
	return a.handleSetupInfo(q)
}

func fallback(value, other string) string {
	if value != "" {
		return value
	}
	return other
}

func (a *App) handleSetupDatabase(q *Request) (any, error) {
	var body struct {
		Type string `json:"type"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	switch body.Type {
	case "sqlite":
		config := a.config.get()
		current, exists, err := readPluginDB(config.pluginDir())
		if err != nil {
			return nil, err
		}
		if exists && current.Type == "mysql" {
			return nil, badRequest("De plugin gebruikt al MySQL. Terug naar SQLite kan niet vanuit de setup.")
		}
		current.Type = "sqlite"
		current.Convert = false
		if err := writePluginDB(config.pluginDir(), config.ServiceUser, current); err != nil {
			return nil, err
		}
	case "mysql":
		// Heeft de plugin al gegevens in SQLite (bijv. bij een herinstallatie), zet die dan over.
		config := a.config.get()
		current, _, err := readPluginDB(config.pluginDir())
		if err != nil {
			return nil, err
		}
		_, statErr := os.Stat(filepath.Join(config.pluginDir(), current.SqliteFile))
		if _, err := a.setupPluginMySQL(statErr == nil); err != nil {
			return nil, err
		}
	default:
		return nil, badRequest("Kies SQLite of MySQL.")
	}
	if err := a.config.update(func(c *Config) { c.Database = body.Type }); err != nil {
		return nil, err
	}
	q.log("setup", "database", body.Type)
	return a.handleSetupInfo(q)
}

// setupPluginMySQL maakt de database en gebruiker voor de plugin (of zet een nieuw wachtwoord)
// en schrijft database.yml. Met convert worden de SQLite-gegevens bij de volgende start overgezet.
func (a *App) setupPluginMySQL(convert bool) (PluginDB, error) {
	config := a.config.get()
	server := a.mariadb.status()
	if !server.Installed {
		return PluginDB{}, badRequest("MariaDB is niet geïnstalleerd. Draai de installer opnieuw om het te installeren.")
	}
	if !server.Running {
		return PluginDB{}, badRequest("MariaDB draait niet: %s", fallback(server.Error, "start de dienst mariadb"))
	}
	plugin, _, err := readPluginDB(config.pluginDir())
	if err != nil {
		return PluginDB{}, err
	}
	if plugin.Type == "mysql" && !convert && plugin.Password != "" {
		// Al ingesteld; niets kapot maken.
		return plugin, nil
	}
	name := "pindacraft"
	exists, err := a.mariadb.databaseExists(name)
	if err != nil {
		return PluginDB{}, err
	}
	if !exists {
		if err := a.mariadb.createDatabase(name); err != nil {
			return PluginDB{}, err
		}
	}
	password := randomPassword(24)
	if err := a.mariadb.createUser(name, "localhost", password, name, true); err != nil {
		return PluginDB{}, err
	}
	plugin.Type = "mysql"
	plugin.Host = "127.0.0.1"
	plugin.Port = 3306
	plugin.Database = name
	plugin.User = name
	plugin.Password = password
	plugin.SSL = false
	plugin.Convert = convert
	if err := writePluginDB(config.pluginDir(), config.ServiceUser, plugin); err != nil {
		return PluginDB{}, err
	}
	return plugin, nil
}

func (a *App) handleSetupFinish(q *Request) (any, error) {
	config := a.config.get()
	if config.ServerName == "" || config.GameAddress == "" {
		return nil, badRequest("Vul eerst de gegevens van de server in.")
	}
	if config.Database == "" {
		return nil, badRequest("Kies eerst een database.")
	}
	if err := a.config.update(func(c *Config) { c.SetupDone = true }); err != nil {
		return nil, err
	}
	q.log("setup", "klaar", "")
	return map[string]bool{"ok": true}, nil
}

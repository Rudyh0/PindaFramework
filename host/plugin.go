package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
)

// PluginDB is database.yml van PindaFramework: waar de plugin zijn gegevens bewaart.
type PluginDB struct {
	Type       string `json:"type"` // sqlite of mysql
	SqliteFile string `json:"sqliteFile"`
	Host       string `json:"host"`
	Port       int    `json:"port"`
	Database   string `json:"database"`
	User       string `json:"user"`
	Password   string `json:"-"`
	SSL        bool   `json:"ssl"`
	Convert    bool   `json:"convert"`
}

func defaultPluginDB() PluginDB {
	return PluginDB{Type: "sqlite", SqliteFile: "data.db", Host: "127.0.0.1", Port: 3306, Database: "pindacraft", User: "pindacraft"}
}

// readPluginDB leest database.yml. Bestaat hij niet, dan de standaard (SQLite).
func readPluginDB(pluginDir string) (PluginDB, bool, error) {
	config := defaultPluginDB()
	file, err := os.Open(filepath.Join(pluginDir, "database.yml"))
	if errors.Is(err, os.ErrNotExist) {
		return config, false, nil
	}
	if err != nil {
		return config, false, err
	}
	defer file.Close()
	values := parseSimpleYAML(file)
	if v, ok := values["type"]; ok {
		config.Type = strings.ToLower(v)
	}
	if v, ok := values["sqlite.file"]; ok && v != "" {
		config.SqliteFile = v
	}
	if v, ok := values["mysql.host"]; ok {
		config.Host = v
	}
	if v, ok := values["mysql.port"]; ok {
		if port, err := strconv.Atoi(v); err == nil {
			config.Port = port
		}
	}
	if v, ok := values["mysql.database"]; ok {
		config.Database = v
	}
	if v, ok := values["mysql.user"]; ok {
		config.User = v
	}
	if v, ok := values["mysql.password"]; ok {
		config.Password = v
	}
	config.SSL = values["mysql.ssl"] == "true"
	config.Convert = values["convert-from-sqlite"] == "true"
	return config, true, nil
}

// parseSimpleYAML leest "sleutel: waarde" met inspringen, genoeg voor database.yml.
func parseSimpleYAML(file *os.File) map[string]string {
	values := map[string]string{}
	type level struct {
		indent int
		key    string
	}
	var stack []level
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		indent := len(line) - len(strings.TrimLeft(line, " "))
		colon := strings.Index(trimmed, ":")
		if colon <= 0 {
			continue
		}
		key := strings.TrimSpace(trimmed[:colon])
		value := strings.TrimSpace(trimmed[colon+1:])
		for len(stack) > 0 && stack[len(stack)-1].indent >= indent {
			stack = stack[:len(stack)-1]
		}
		full := key
		if len(stack) > 0 {
			full = stack[len(stack)-1].key + "." + key
		}
		if value == "" {
			stack = append(stack, level{indent: indent, key: full})
			continue
		}
		values[full] = unquoteYAML(value)
	}
	return values
}

func unquoteYAML(value string) string {
	if len(value) >= 2 && value[0] == '"' && value[len(value)-1] == '"' {
		var out string
		if err := json.Unmarshal([]byte(value), &out); err == nil {
			return out
		}
		return value[1 : len(value)-1]
	}
	if len(value) >= 2 && value[0] == '\'' && value[len(value)-1] == '\'' {
		return strings.ReplaceAll(value[1:len(value)-1], "''", "'")
	}
	// Commentaar achter een waarde.
	if index := strings.Index(value, " #"); index >= 0 {
		value = strings.TrimSpace(value[:index])
	}
	return value
}

// quoteYAML: een JSON-string is ook geldige YAML met dubbele aanhalingstekens.
func quoteYAML(value string) string {
	data, _ := json.Marshal(value)
	return string(data)
}

// writePluginDB schrijft database.yml (met uitleg) en maakt hem van de Minecraft-gebruiker.
func writePluginDB(pluginDir, owner string, config PluginDB) error {
	if err := os.MkdirAll(pluginDir, 0o755); err != nil {
		return err
	}
	content := fmt.Sprintf(`# ┌──────────────────────────────────────────────┐
# │           PindaFramework - database          │
# └──────────────────────────────────────────────┘
# Waar PindaFramework zijn gegevens bewaart. Dit bestand wordt beheerd door het dev-paneel
# (PindaHost) en staat bewust niet in het webpaneel voor staff: er staat een wachtwoord in.
# Pas het alleen met de hand aan als je weet wat je doet, en herstart daarna de server.

# sqlite = alles in één bestand in deze map (prima voor de meeste servers)
# mysql  = een MySQL- of MariaDB-server
type: %s

sqlite:
  # Het bestand in de pluginmap
  file: %s

mysql:
  host: %s
  port: %d
  database: %s
  user: %s
  password: %s
  # Versleutelde verbinding (alleen nodig als de database op een andere server staat)
  ssl: %t

# Eenmalig: zet bij de volgende start alles uit SQLite (het bestand hierboven) over naar MySQL.
# Gaat het goed, dan wordt dit vanzelf weer false en blijft het oude bestand als backup bewaard.
# Gaat het mis, dan draait de server gewoon op SQLite verder en wordt het de volgende start
# opnieuw geprobeerd. Kijk dan in de console of in het dev-paneel wat er misging.
convert-from-sqlite: %t
`, config.Type, quoteYAML(config.SqliteFile), quoteYAML(config.Host), config.Port, quoteYAML(config.Database),
		quoteYAML(config.User), quoteYAML(config.Password), config.SSL, config.Convert)
	path := filepath.Join(pluginDir, "database.yml")
	if err := writeFileAtomic(path, []byte(content), 0o600); err != nil {
		return err
	}
	chownTo(path, owner)
	chownTo(pluginDir, owner)
	return nil
}

// chownTo maakt een bestand van een gebruiker (als die bestaat; anders blijft het zoals het is).
func chownTo(path, owner string) {
	if owner == "" {
		return
	}
	account, err := user.Lookup(owner)
	if err != nil {
		return
	}
	uid, err1 := strconv.Atoi(account.Uid)
	gid, err2 := strconv.Atoi(account.Gid)
	if err1 != nil || err2 != nil {
		return
	}
	_ = os.Chown(path, uid, gid)
}

// PluginDBStatus komt uit database-status.json en database-conversion.json (geschreven door de plugin).
type PluginDBStatus struct {
	Status     map[string]any `json:"status"`
	Conversion map[string]any `json:"conversion"`
}

func readPluginDBStatus(pluginDir string) PluginDBStatus {
	var result PluginDBStatus
	if data, err := os.ReadFile(filepath.Join(pluginDir, "database-status.json")); err == nil {
		_ = json.Unmarshal(data, &result.Status)
	}
	if data, err := os.ReadFile(filepath.Join(pluginDir, "database-conversion.json")); err == nil {
		_ = json.Unmarshal(data, &result.Conversion)
	}
	return result
}

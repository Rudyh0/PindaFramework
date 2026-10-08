package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// MariaDB beheert de lokale MariaDB-server via de mariadb-opdrachten, als root via de socket
// (de installer zet MariaDB zo neer; root heeft dan geen wachtwoord nodig).
type MariaDB struct {
	// Eén import tegelijk.
	importing sync.Mutex

	sandboxOnce sync.Once
	sandbox     bool
}

func newMariaDB() *MariaDB {
	return &MariaDB{}
}

var (
	dbNamePattern   = regexp.MustCompile(`^[A-Za-z0-9_]{1,64}$`)
	dbUserPattern   = regexp.MustCompile(`^[A-Za-z0-9_]{1,32}$`)
	systemDatabases = map[string]bool{"information_schema": true, "mysql": true, "performance_schema": true, "sys": true}
	systemUsers     = map[string]bool{"root": true, "mysql": true, "mariadb.sys": true, "debian-sys-maint": true, "PUBLIC": true}
)

// importUserPrefix: tijdelijke gebruikers voor een import. Die zie je niet in de lijst en
// die naam kun je zelf niet kiezen.
const importUserPrefix = "pinda_import_"

// Een database: naam, grootte in bytes en aantal tabellen.
type DBInfo struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Tables int    `json:"tables"`
}

// Een gebruiker en de databases waar hij bij mag.
type DBUser struct {
	Name      string   `json:"name"`
	Host      string   `json:"host"`
	Databases []string `json:"databases"`
}

type DBServer struct {
	Installed bool   `json:"installed"`
	Running   bool   `json:"running"`
	Version   string `json:"version,omitempty"`
	Error     string `json:"error,omitempty"`
}

func firstBinary(names ...string) string {
	for _, name := range names {
		if path, err := exec.LookPath(name); err == nil {
			return path
		}
	}
	return ""
}

func (m *MariaDB) client() string {
	return firstBinary("mariadb", "mysql")
}

func (m *MariaDB) dumper() string {
	return firstBinary("mariadb-dump", "mysqldump")
}

func (m *MariaDB) installed() bool {
	return m.client() != "" && firstBinary("mariadbd", "mysqld", "/usr/sbin/mariadbd", "/usr/sbin/mysqld") != ""
}

// run voert SQL uit (via stdin, nooit als argument) en geeft de rijen terug.
func (m *MariaDB) run(sql string, database string) ([][]string, error) {
	client := m.client()
	if client == "" {
		return nil, errors.New("MariaDB is niet geïnstalleerd (draai de installer opnieuw)")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	args := []string{"--protocol=socket", "-uroot", "--batch", "--skip-column-names", "--default-character-set=utf8mb4"}
	if database != "" {
		args = append(args, "--database="+database)
	}
	cmd := exec.CommandContext(ctx, client, args...)
	// Onze manier van tekst escapen gaat uit van de backslash; zet dat vast, wat de server ook zegt.
	cmd.Stdin = strings.NewReader("SET SESSION sql_mode = REPLACE(@@sql_mode, 'NO_BACKSLASH_ESCAPES', '');\n" + sql)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		return nil, errors.New(cleanMariaDBError(message))
	}
	var rows [][]string
	for _, line := range strings.Split(strings.TrimRight(stdout.String(), "\n"), "\n") {
		if line == "" {
			continue
		}
		fields := strings.Split(line, "\t")
		for i, field := range fields {
			fields[i] = unescapeBatch(field)
		}
		rows = append(rows, fields)
	}
	return rows, nil
}

// cleanMariaDBError haalt "ERROR 1007 (HY000) at line 1:" weg; de rest is de echte melding.
func cleanMariaDBError(message string) string {
	if index := strings.Index(message, ": "); strings.HasPrefix(message, "ERROR") && index > 0 {
		return strings.TrimSpace(message[index+2:])
	}
	return message
}

// unescapeBatch maakt de escapes van --batch ongedaan (\t, \n, \\, \0).
func unescapeBatch(value string) string {
	if !strings.Contains(value, `\`) {
		return value
	}
	var out strings.Builder
	for i := 0; i < len(value); i++ {
		if value[i] == '\\' && i+1 < len(value) {
			i++
			switch value[i] {
			case 't':
				out.WriteByte('\t')
			case 'n':
				out.WriteByte('\n')
			case '0':
				out.WriteByte(0)
			default:
				out.WriteByte(value[i])
			}
			continue
		}
		out.WriteByte(value[i])
	}
	return out.String()
}

// sqlString zet een waarde veilig tussen aanhalingstekens (MariaDB-regels voor strings).
func sqlString(value string) (string, error) {
	if strings.ContainsRune(value, 0) {
		return "", errors.New("ongeldig teken in de waarde")
	}
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, `'`, `\'`)
	return "'" + value + "'", nil
}

func (m *MariaDB) status() DBServer {
	server := DBServer{Installed: m.installed()}
	if !server.Installed {
		return server
	}
	rows, err := m.run("SELECT VERSION();", "")
	if err != nil {
		server.Error = err.Error()
		return server
	}
	server.Running = true
	if len(rows) > 0 && len(rows[0]) > 0 {
		// "10.11.14-MariaDB-0ubuntu0.24.04.1" -> "10.11.14"
		server.Version = strings.SplitN(rows[0][0], "-", 2)[0]
	}
	return server
}

func (m *MariaDB) databases() ([]DBInfo, error) {
	rows, err := m.run(`SELECT s.SCHEMA_NAME, COALESCE(SUM(t.DATA_LENGTH + t.INDEX_LENGTH), 0), COUNT(t.TABLE_NAME)
FROM information_schema.SCHEMATA s LEFT JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = s.SCHEMA_NAME
GROUP BY s.SCHEMA_NAME ORDER BY s.SCHEMA_NAME;`, "")
	if err != nil {
		return nil, err
	}
	list := []DBInfo{}
	for _, row := range rows {
		if len(row) < 3 || systemDatabases[row[0]] {
			continue
		}
		size, _ := strconv.ParseInt(row[1], 10, 64)
		tables, _ := strconv.Atoi(row[2])
		list = append(list, DBInfo{Name: row[0], Size: size, Tables: tables})
	}
	return list, nil
}

func (m *MariaDB) databaseExists(name string) (bool, error) {
	list, err := m.databases()
	if err != nil {
		return false, err
	}
	for _, db := range list {
		if db.Name == name {
			return true, nil
		}
	}
	return false, nil
}

func (m *MariaDB) users() ([]DBUser, error) {
	rows, err := m.run("SELECT User, Host FROM mysql.user WHERE Host <> '' ORDER BY User, Host;", "")
	if err != nil {
		return nil, err
	}
	grants, err := m.run("SELECT DISTINCT GRANTEE, TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES;", "")
	if err != nil {
		return nil, err
	}
	byGrantee := map[string][]string{}
	for _, row := range grants {
		if len(row) >= 2 {
			byGrantee[row[0]] = append(byGrantee[row[0]], row[1])
		}
	}
	list := []DBUser{}
	for _, row := range rows {
		if len(row) < 2 || systemUsers[row[0]] || row[0] == "" || strings.HasPrefix(strings.ToLower(row[0]), importUserPrefix) {
			continue
		}
		databases := byGrantee["'"+row[0]+"'@'"+row[1]+"'"]
		sort.Strings(databases)
		if databases == nil {
			databases = []string{}
		}
		list = append(list, DBUser{Name: row[0], Host: row[1], Databases: databases})
	}
	return list, nil
}

func (m *MariaDB) userExists(name, host string) (bool, error) {
	list, err := m.users()
	if err != nil {
		return false, err
	}
	for _, user := range list {
		if user.Name == name && user.Host == host {
			return true, nil
		}
	}
	return false, nil
}

func checkDBName(name string) error {
	if !dbNamePattern.MatchString(name) {
		return errors.New("een databasenaam is 1 tot 64 tekens: letters, cijfers en underscore")
	}
	if systemDatabases[strings.ToLower(name)] {
		return errors.New("dat is een database van MariaDB zelf")
	}
	return nil
}

func checkDBUser(name, host string) error {
	if !dbUserPattern.MatchString(name) {
		return errors.New("een gebruikersnaam is 1 tot 32 tekens: letters, cijfers en underscore")
	}
	if systemUsers[name] {
		return errors.New("die gebruiker is van MariaDB zelf")
	}
	if strings.HasPrefix(strings.ToLower(name), importUserPrefix) {
		return errors.New("namen die met " + importUserPrefix + " beginnen zijn voor het paneel zelf")
	}
	if host != "localhost" && host != "%" && host != "127.0.0.1" {
		return errors.New("onbekende host")
	}
	return nil
}

func (m *MariaDB) createDatabase(name string) error {
	if err := checkDBName(name); err != nil {
		return err
	}
	_, err := m.run(fmt.Sprintf("CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;", name), "")
	return err
}

func (m *MariaDB) dropDatabase(name string) error {
	if err := checkDBName(name); err != nil {
		return err
	}
	_, err := m.run(fmt.Sprintf("DROP DATABASE `%s`;", name), "")
	return err
}

// createUser maakt een nieuwe gebruiker en geeft hem alle rechten op de database, als die is
// opgegeven. Met replace wordt een bestaande gebruiker hergebruikt (nieuw wachtwoord).
func (m *MariaDB) createUser(name, host, password, database string, replace bool) error {
	if err := checkDBUser(name, host); err != nil {
		return err
	}
	quoted, err := sqlString(password)
	if err != nil {
		return err
	}
	sql := fmt.Sprintf("CREATE USER '%s'@'%s' IDENTIFIED BY %s;\n", name, host, quoted)
	if replace {
		sql = fmt.Sprintf("CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY %s;\nALTER USER '%s'@'%s' IDENTIFIED BY %s;\n",
			name, host, quoted, name, host, quoted)
	}
	if database != "" {
		if err := checkDBName(database); err != nil {
			return err
		}
		sql += fmt.Sprintf("GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%s';\n", database, name, host)
	}
	_, err = m.run(sql+"FLUSH PRIVILEGES;", "")
	return err
}

func (m *MariaDB) setPassword(name, host, password string) error {
	if err := checkDBUser(name, host); err != nil {
		return err
	}
	quoted, err := sqlString(password)
	if err != nil {
		return err
	}
	_, err = m.run(fmt.Sprintf("ALTER USER '%s'@'%s' IDENTIFIED BY %s;\nFLUSH PRIVILEGES;", name, host, quoted), "")
	return err
}

func (m *MariaDB) grant(name, host, database string) error {
	if err := checkDBUser(name, host); err != nil {
		return err
	}
	if err := checkDBName(database); err != nil {
		return err
	}
	_, err := m.run(fmt.Sprintf("GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%s';\nFLUSH PRIVILEGES;", database, name, host), "")
	return err
}

func (m *MariaDB) revoke(name, host, database string) error {
	if err := checkDBUser(name, host); err != nil {
		return err
	}
	if err := checkDBName(database); err != nil {
		return err
	}
	_, err := m.run(fmt.Sprintf("REVOKE ALL PRIVILEGES ON `%s`.* FROM '%s'@'%s';\nFLUSH PRIVILEGES;", database, name, host), "")
	return err
}

func (m *MariaDB) dropUser(name, host string) error {
	if err := checkDBUser(name, host); err != nil {
		return err
	}
	_, err := m.run(fmt.Sprintf("DROP USER '%s'@'%s';\nFLUSH PRIVILEGES;", name, host), "")
	return err
}

// export schrijft een .sql-dump van de database naar een bestand.
func (m *MariaDB) export(database, path string) error {
	if err := checkDBName(database); err != nil {
		return err
	}
	dumper := m.dumper()
	if dumper == "" {
		return errors.New("mariadb-dump is niet geïnstalleerd")
	}
	out, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, dumper, "--protocol=socket", "-uroot", "--single-transaction", "--quick",
		"--routines", "--triggers", "--default-character-set=utf8mb4", database)
	var stderr bytes.Buffer
	cmd.Stdout = out
	cmd.Stderr = &stderr
	runErr := cmd.Run()
	closeErr := out.Close()
	if runErr != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = runErr.Error()
		}
		return errors.New(cleanMariaDBError(message))
	}
	return closeErr
}

// importFile leest een .sql-bestand in een database in. Niet als root: een .sql-bestand kan
// van alles bevatten. Daarom een tijdelijke gebruiker die alleen bij deze database mag (geen
// FILE- of SUPER-rechten), en de client zonder eigen opdrachten (\! of source) en zonder
// LOAD DATA LOCAL. dir is een map van alleen root, voor het wachtwoordbestand.
func (m *MariaDB) importFile(database, path, dir string, say func(string, ...any)) error {
	if err := checkDBName(database); err != nil {
		return err
	}
	client := m.client()
	if client == "" {
		return errors.New("MariaDB is niet geïnstalleerd")
	}
	in, err := os.Open(path)
	if err != nil {
		return err
	}
	defer in.Close()

	user := importUserPrefix + randomToken()[:12]
	password := randomToken()[:32]
	if _, err := m.run(fmt.Sprintf("CREATE USER '%s'@'localhost' IDENTIFIED BY '%s';\n"+
		"GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'localhost';\nFLUSH PRIVILEGES;", user, password, database, user), ""); err != nil {
		return err
	}
	defer func() {
		if _, err := m.run(fmt.Sprintf("DROP USER IF EXISTS '%s'@'localhost';\nFLUSH PRIVILEGES;", user), ""); err != nil {
			say("Let op: de tijdelijke gebruiker %s kon niet worden verwijderd: %s", user, err.Error())
		}
	}()

	// Het wachtwoord in een bestand dat alleen root kan lezen, niet op de opdrachtregel (ps).
	options, err := os.CreateTemp(dir, "import-*.cnf")
	if err != nil {
		return err
	}
	defer os.Remove(options.Name())
	_, writeErr := fmt.Fprintf(options, "[client]\nuser=%s\npassword=%s\n", user, password)
	if err := options.Close(); err != nil || writeErr != nil {
		return errors.Join(writeErr, err)
	}

	args := []string{"--defaults-extra-file=" + options.Name(), "--protocol=socket", "--binary-mode",
		"--local-infile=0", "--default-character-set=utf8mb4", "--database=" + database}
	if m.hasSandbox(client) {
		args = append(args, "--sandbox")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Hour)
	defer cancel()
	cmd := exec.CommandContext(ctx, client, args...)
	cmd.Stdin = in
	var stderr bytes.Buffer
	cmd.Stdout = io.Discard
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		message = cleanMariaDBError(message)
		if strings.Contains(message, "SUPER") || strings.Contains(message, "SET USER") || strings.Contains(strings.ToLower(message), "definer") {
			message += " (in het bestand staat een DEFINER van een andere gebruiker; haal die weg of exporteer zonder triggers/views)"
		}
		say("%s", message)
		return errors.New(message)
	}
	return nil
}

// hasSandbox: kent deze client --sandbox (MariaDB 10.5.25, 10.6.18, 10.11.8 en nieuwer)?
func (m *MariaDB) hasSandbox(client string) bool {
	m.sandboxOnce.Do(func() {
		out, _ := runQuiet(10*time.Second, client, "--help")
		m.sandbox = strings.Contains(out, "--sandbox")
	})
	return m.sandbox
}

// cleanupImportUsers ruimt tijdelijke importgebruikers op die zijn blijven staan (paneel gecrasht).
func (m *MariaDB) cleanupImportUsers() {
	if !m.installed() {
		return
	}
	rows, err := m.run("SELECT User, Host FROM mysql.user WHERE User LIKE 'pinda\\_import\\_%';", "")
	if err != nil {
		return
	}
	for _, row := range rows {
		if len(row) >= 2 && strings.HasPrefix(row[0], importUserPrefix) && dbUserPattern.MatchString(row[0]) && row[1] == "localhost" {
			_, _ = m.run(fmt.Sprintf("DROP USER IF EXISTS '%s'@'localhost';", row[0]), "")
		}
	}
}

package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
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

// importUserPrefix: de eigenaars voor imports (zie importUser). Die zie je niet in de lijst en
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

// objectCount: hoeveel tabellen, views, routines en events er in een database staan.
func (m *MariaDB) objectCount(name string) (int, error) {
	if err := checkDBName(name); err != nil {
		return 0, err
	}
	rows, err := m.run(fmt.Sprintf("SELECT (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '%[1]s')"+
		" + (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = '%[1]s')"+
		" + (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = '%[1]s');", name), "")
	if err != nil {
		return 0, err
	}
	if len(rows) != 1 || len(rows[0]) != 1 {
		return 0, errors.New("onverwacht antwoord van MariaDB")
	}
	return strconv.Atoi(rows[0][0])
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
			byGrantee[row[0]] = append(byGrantee[row[0]], unescapeGrantDB(row[1]))
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

// grantDB: de databasenaam zoals hij in GRANT en REVOKE moet. Daar is _ een jokerteken (net als
// in LIKE): GRANT ON `pinda_x` zou ook pindaAx en pindaBx geven. Met \_ geldt hij alleen voor
// precies deze database. (checkDBName laat geen % of backtick toe.)
func grantDB(name string) string {
	return strings.ReplaceAll(name, "_", `\_`)
}

// unescapeGrantDB: van de naam in information_schema.SCHEMA_PRIVILEGES weer de echte naam.
func unescapeGrantDB(name string) string {
	return strings.NewReplacer(`\_`, "_", `\%`, "%", `\\`, `\`).Replace(name)
}

var (
	grantSchemaPattern = regexp.MustCompile(`^[A-Za-z0-9_\\%]{1,128}$`)
	granteePattern     = regexp.MustCompile(`^'[A-Za-z0-9_.\-]{1,80}'@'[A-Za-z0-9_.%:\-]{1,255}'$`)
)

// grantsOn: alle rechten (gebruiker + naam zoals in de GRANT) die bij deze database horen.
func (m *MariaDB) grantsOn(database, grantee string) ([][2]string, error) {
	rows, err := m.run("SELECT DISTINCT GRANTEE, TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES;", "")
	if err != nil {
		return nil, err
	}
	var list [][2]string
	for _, row := range rows {
		if len(row) < 2 || unescapeGrantDB(row[1]) != database || !grantSchemaPattern.MatchString(row[1]) || !granteePattern.MatchString(row[0]) {
			continue
		}
		if grantee != "" && row[0] != grantee {
			continue
		}
		list = append(list, [2]string{row[0], row[1]})
	}
	return list, nil
}

// dropDatabase gooit de database weg, met alle rechten erop (anders gelden die weer voor een
// nieuwe database met dezelfde naam) en de eigenaar van de import.
func (m *MariaDB) dropDatabase(name string) error {
	if err := checkDBName(name); err != nil {
		return err
	}
	if _, err := m.run(fmt.Sprintf("DROP DATABASE `%s`;", name), ""); err != nil {
		return err
	}
	if grants, err := m.grantsOn(name, ""); err == nil && len(grants) > 0 {
		var sql strings.Builder
		for _, grant := range grants {
			fmt.Fprintf(&sql, "REVOKE ALL PRIVILEGES ON `%s`.* FROM %s;\n", grant[1], grant[0])
		}
		_, _ = m.run(sql.String()+"FLUSH PRIVILEGES;", "")
	}
	_, _ = m.run(fmt.Sprintf("DROP USER IF EXISTS '%s'@'localhost';\nFLUSH PRIVILEGES;", importUser(name)), "")
	return nil
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
		sql += fmt.Sprintf("GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%s';\n", grantDB(database), name, host)
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
	_, err := m.run(fmt.Sprintf("GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%s';\nFLUSH PRIVILEGES;", grantDB(database), name, host), "")
	return err
}

// revoke haalt alle rechten op de database weg, ook als die (van vroeger, of van buiten het
// paneel) zonder \_ zijn gegeven.
func (m *MariaDB) revoke(name, host, database string) error {
	if err := checkDBUser(name, host); err != nil {
		return err
	}
	if err := checkDBName(database); err != nil {
		return err
	}
	grants, err := m.grantsOn(database, "'"+name+"'@'"+host+"'")
	if err != nil {
		return err
	}
	if len(grants) == 0 {
		return errors.New("deze gebruiker heeft geen toegang tot die database")
	}
	var sql strings.Builder
	for _, grant := range grants {
		fmt.Fprintf(&sql, "REVOKE ALL PRIVILEGES ON `%s`.* FROM %s;\n", grant[1], grant[0])
	}
	_, err = m.run(sql.String()+"FLUSH PRIVILEGES;", "")
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

// importUser: per database één vaste eigenaar voor imports. Triggers, views en routines uit
// een import krijgen hem als DEFINER, dus hij moet blijven bestaan; tussen imports door staat
// hij op slot met een onbekend wachtwoord. Hij mag alleen bij zijn eigen database.
func importUser(database string) string {
	sum := sha256.Sum256([]byte(database))
	return importUserPrefix + hex.EncodeToString(sum[:])[:12]
}

// lockImportUser zet de eigenaar op slot met een willekeurig wachtwoord. (ACCOUNT LOCK kan pas
// vanaf MariaDB 10.4; ervoor is het onbekende wachtwoord genoeg.)
func (m *MariaDB) lockImportUser(user string) error {
	password := randomToken()[:32]
	if _, err := m.run(fmt.Sprintf("ALTER USER IF EXISTS '%s'@'localhost' IDENTIFIED BY '%s' ACCOUNT LOCK;", user, password), ""); err == nil {
		return nil
	}
	_, err := m.run(fmt.Sprintf("ALTER USER IF EXISTS '%s'@'localhost' IDENTIFIED BY '%s';", user, password), "")
	return err
}

// unlockImportUser maakt de eigenaar aan (als hij er nog niet is), geeft hem alleen rechten op
// deze ene database en een wachtwoord voor deze ene import.
func (m *MariaDB) unlockImportUser(user, password, database string) error {
	create := fmt.Sprintf("CREATE USER IF NOT EXISTS '%s'@'localhost' IDENTIFIED BY '%s';\n", user, password)
	grant := fmt.Sprintf("GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'localhost';\nFLUSH PRIVILEGES;", grantDB(database), user)
	if _, err := m.run(create+fmt.Sprintf("ALTER USER '%s'@'localhost' IDENTIFIED BY '%s' ACCOUNT UNLOCK;\n", user, password)+grant, ""); err == nil {
		return nil
	}
	_, err := m.run(create+fmt.Sprintf("ALTER USER '%s'@'localhost' IDENTIFIED BY '%s';\n", user, password)+grant, "")
	return err
}

// importFile leest een .sql-bestand in een database in. Niet als root: een .sql-bestand kan
// van alles bevatten. Daarom als de eigenaar van deze database (alleen rechten op deze database,
// geen FILE of SUPER), met een eigen optiebestand (--defaults-file: ~/.my.cnf telt dan niet
// mee), zonder eigen opdrachten van de client (\! of source) en zonder LOAD DATA LOCAL.
// DEFINER-regels worden weggehaald, zodat de eigenaar zelf de definer wordt (exports van het
// paneel zelf noemen root). dir is een map van alleen root, voor het optiebestand.
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

	socket := ""
	if rows, err := m.run("SELECT @@socket;", ""); err == nil && len(rows) > 0 && len(rows[0]) > 0 {
		socket = rows[0][0]
	}
	if strings.ContainsAny(socket, "\n\r") {
		socket = ""
	}

	user := importUser(database)
	password := randomToken()[:32]
	// Eerst het weer-op-slot-zetten regelen, dan pas openzetten: zo blijft hij nooit open staan.
	defer func() {
		if err := m.lockImportUser(user); err != nil {
			say("Let op: de importgebruiker kon niet op slot: %s", err.Error())
		}
	}()
	if err := m.unlockImportUser(user, password, database); err != nil {
		return err
	}

	// Het wachtwoord in een bestand dat alleen root kan lezen, niet op de opdrachtregel (ps).
	options, err := os.CreateTemp(dir, "import-*.cnf")
	if err != nil {
		return err
	}
	defer os.Remove(options.Name())
	content := fmt.Sprintf("[client]\nuser=%s\npassword=%s\nprotocol=socket\n", user, password)
	if socket != "" {
		content += "socket=" + socket + "\n"
	}
	_, writeErr := options.WriteString(content)
	if err := options.Close(); err != nil || writeErr != nil {
		return errors.Join(writeErr, err)
	}

	// --defaults-file moet het eerste argument zijn.
	args := []string{"--defaults-file=" + options.Name(), "--binary-mode", "--local-infile=0",
		"--default-character-set=utf8mb4", "--database=" + database}
	if m.hasSandbox(client) {
		args = append(args, "--sandbox")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Hour)
	defer cancel()
	cmd := exec.CommandContext(ctx, client, args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	var stderr bytes.Buffer
	cmd.Stdout = io.Discard
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	filterErr := make(chan error, 1)
	go func() {
		err := stripDefiners(in, stdin)
		stdin.Close()
		filterErr <- err
	}()
	runErr := cmd.Wait()
	if err := <-filterErr; err != nil && runErr == nil {
		runErr = err
	}
	if runErr != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = runErr.Error()
		}
		message = cleanMariaDBError(message)
		say("%s", message)
		return errors.New(message)
	}
	return nil
}

// definerPattern: DEFINER=`root`@`localhost` (ook met '...' of zonder aanhalingstekens).
var definerPattern = regexp.MustCompile("(?i)DEFINER\\s*=\\s*(`[^`]*`|'[^']*'|[^\\s@*/]+)\\s*@\\s*(`[^`]*`|'[^']*'|[^\\s*/]+)\\s*")

// stripDefiners kopieert een .sql-bestand en haalt DEFINER weg uit regels die een object
// aanmaken (die beginnen met CREATE of /*!). Gegevensregels (INSERT, ...) gaan ongewijzigd
// door, hoe lang ze ook zijn.
func stripDefiners(in io.Reader, out io.Writer) error {
	const maxDDL = 4 << 20
	reader := bufio.NewReaderSize(in, 64<<10)
	writer := bufio.NewWriterSize(out, 64<<10)
	for {
		head, err := reader.Peek(6)
		if len(head) == 0 {
			if err == io.EOF {
				return writer.Flush()
			}
			return err
		}
		ddl := bytes.HasPrefix(head, []byte("/*!")) || bytes.EqualFold(head, []byte("CREATE"))
		var line []byte
		for {
			chunk, err := reader.ReadSlice('\n')
			if ddl && len(line)+len(chunk) <= maxDDL {
				line = append(line, chunk...)
			} else {
				if ddl {
					// Te lang voor een gewone CREATE: ongewijzigd doorgeven.
					if _, err := writer.Write(line); err != nil {
						return err
					}
					line, ddl = nil, false
				}
				if _, err := writer.Write(chunk); err != nil {
					return err
				}
			}
			if err == bufio.ErrBufferFull {
				continue
			}
			if err != nil && err != io.EOF {
				return err
			}
			break
		}
		if ddl {
			if _, err := writer.Write(definerPattern.ReplaceAll(line, nil)); err != nil {
				return err
			}
		}
	}
}

// hasSandbox: kent deze client --sandbox (MariaDB 10.5.25, 10.6.18, 10.11.8 en nieuwer)?
func (m *MariaDB) hasSandbox(client string) bool {
	m.sandboxOnce.Do(func() {
		out, _ := runQuiet(10*time.Second, client, "--help")
		m.sandbox = strings.Contains(out, "--sandbox")
	})
	return m.sandbox
}

// cleanupImportUsers zet bij het starten alle importgebruikers op slot (het paneel kan midden in
// een import gestopt zijn) en ruimt die van verdwenen databases op.
func (m *MariaDB) cleanupImportUsers() {
	if !m.installed() {
		return
	}
	rows, err := m.run("SELECT User, Host FROM mysql.user WHERE User LIKE 'pinda\\_import\\_%';", "")
	if err != nil {
		return
	}
	databases, err := m.databases()
	if err != nil {
		return
	}
	owners := map[string]bool{}
	for _, db := range databases {
		owners[importUser(db.Name)] = true
	}
	for _, row := range rows {
		if len(row) < 2 || !strings.HasPrefix(row[0], importUserPrefix) || !dbUserPattern.MatchString(row[0]) || row[1] != "localhost" {
			continue
		}
		if owners[row[0]] {
			_ = m.lockImportUser(row[0])
		} else {
			_, _ = m.run(fmt.Sprintf("DROP USER IF EXISTS '%s'@'localhost';", row[0]), "")
		}
	}
}

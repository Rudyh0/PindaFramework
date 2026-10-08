package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Draait alleen als er een MariaDB is waar root via de socket bij kan (zoals na de installer).
func TestMariaDBManagement(t *testing.T) {
	m := newMariaDB()
	if status := m.status(); !status.Running {
		t.Skip("geen MariaDB beschikbaar: " + status.Error)
	}
	const db, user = "pindahost_test", "pindahost_test"
	_ = m.dropDatabase(db)
	_ = m.dropUser(user, "localhost")
	defer func() {
		_ = m.dropDatabase(db)
		_ = m.dropUser(user, "localhost")
	}()

	if err := m.createDatabase(db); err != nil {
		t.Fatal(err)
	}
	if err := m.createDatabase(db); err == nil || !strings.Contains(err.Error(), "exists") {
		t.Errorf("dubbele database: %v", err)
	}
	password := `a'b\c"d` + randomPassword(10)
	if err := m.createUser(user, "localhost", password, db, false); err != nil {
		t.Fatal(err)
	}
	users, err := m.users()
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, u := range users {
		if u.Name == user && u.Host == "localhost" && len(u.Databases) == 1 && u.Databases[0] == db {
			found = true
		}
		if systemUsers[u.Name] {
			t.Errorf("systeemgebruiker %s in de lijst", u.Name)
		}
	}
	if !found {
		t.Fatalf("gebruiker of toegang niet gevonden: %+v", users)
	}

	// Inloggen met het wachtwoord met rare tekens moet werken.
	if _, err := m.run("CREATE TABLE t (id INT PRIMARY KEY, naam VARCHAR(50)); INSERT INTO t VALUES (1, 'Rudy ''h0'''), (2, 'tab\there');", db); err != nil {
		t.Fatal(err)
	}
	rows, err := m.run("SELECT naam FROM t ORDER BY id;", db)
	if err != nil || len(rows) != 2 || rows[0][0] != "Rudy 'h0'" || rows[1][0] != "tab\there" {
		t.Fatalf("lezen: %v %v", rows, err)
	}

	dir := t.TempDir()
	dump := filepath.Join(dir, "dump.sql")
	if err := m.export(db, dump); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(dump)
	if !strings.Contains(string(data), "CREATE TABLE `t`") {
		t.Fatal("export bevat de tabel niet")
	}
	if _, err := m.run("DROP TABLE t;", db); err != nil {
		t.Fatal(err)
	}
	if err := m.importFile(db, dump, dir, func(string, ...any) {}); err != nil {
		t.Fatal(err)
	}
	rows, _ = m.run("SELECT COUNT(*) FROM t;", db)
	if len(rows) != 1 || rows[0][0] != "2" {
		t.Fatalf("na het inladen: %v", rows)
	}
	broken := filepath.Join(dir, "kapot.sql")
	_ = os.WriteFile(broken, []byte("CREATE TABEL oeps;"), 0o600)
	if err := m.importFile(db, broken, dir, func(string, ...any) {}); err == nil || !strings.Contains(err.Error(), "syntax") {
		t.Errorf("kapotte import gaf geen nette fout: %v", err)
	}
	// Een kwaadaardig .sql-bestand mag niet buiten zijn eigen database komen, ook niet als root
	// een ~/.my.cnf heeft die "user=root" zegt.
	home := t.TempDir()
	if err := os.WriteFile(filepath.Join(home, ".my.cnf"), []byte("[client]\nuser=root\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", home)
	defer func() { _, _ = m.run("DROP USER IF EXISTS evil@'%';", "") }()
	marker := filepath.Join(dir, "shell-uitgevoerd")
	outfile := "/tmp/pinda-import-outfile-" + randomToken()[:8]
	for _, evil := range []string{
		"\\! touch " + marker + "\n",
		"system touch " + marker + "\n",
		"USE mysql;\nSELECT 1;\n",
		"CREATE DATABASE pindahost_evil;\n",
		"SELECT 1 INTO OUTFILE '" + outfile + "';\n",
		"CREATE USER evil@'%' IDENTIFIED BY 'x';\n",
		"SET GLOBAL max_connections = 5;\n",
	} {
		file := filepath.Join(dir, "evil.sql")
		_ = os.WriteFile(file, []byte(evil), 0o600)
		if err := m.importFile(db, file, dir, func(string, ...any) {}); err == nil {
			t.Errorf("gevaarlijke import gelukt: %q", evil)
		}
	}
	if _, err := os.Stat(marker); err == nil {
		t.Error("shell-opdracht uit een import uitgevoerd")
	}
	if _, err := os.Stat(outfile); err == nil {
		os.Remove(outfile)
		t.Error("INTO OUTFILE uit een import gelukt")
	}
	if exists, _ := m.databaseExists("pindahost_evil"); exists {
		_ = m.dropDatabase("pindahost_evil")
		t.Error("andere database gemaakt vanuit een import")
	}
	owner := importUser(db)
	if rows, _ := m.run("SELECT JSON_VALUE(Priv, '$.account_locked') FROM mysql.global_priv WHERE User = '"+owner+"';", ""); len(rows) != 1 || (rows[0][0] != "true" && rows[0][0] != "1") {
		t.Errorf("importgebruiker staat na de import niet op slot: %v", rows)
	}
	if users, _ := m.users(); len(users) > 0 {
		for _, u := range users {
			if strings.HasPrefix(u.Name, importUserPrefix) {
				t.Errorf("importgebruiker zichtbaar in de lijst: %s", u.Name)
			}
		}
	}
	if entries, _ := os.ReadDir(dir); len(entries) > 0 {
		for _, entry := range entries {
			if strings.HasSuffix(entry.Name(), ".cnf") {
				t.Errorf("wachtwoordbestand blijft staan: %s", entry.Name())
			}
		}
	}
	if err := checkDBUser("pinda_import_abc", "localhost"); err == nil {
		t.Error("naam van een tijdelijke importgebruiker toegestaan")
	}

	if err := m.setPassword(user, "localhost", "nieuw-wachtwoord-123"); err != nil {
		t.Fatal(err)
	}
	if err := m.revoke(user, "localhost", db); err != nil {
		t.Fatal(err)
	}
	if err := m.dropUser(user, "localhost"); err != nil {
		t.Fatal(err)
	}
	if err := m.dropDatabase(db); err != nil {
		t.Fatal(err)
	}
	if exists, _ := m.databaseExists(db); exists {
		t.Error("database nog aanwezig")
	}
	if rows, _ := m.run("SELECT COUNT(*) FROM mysql.user WHERE User = '"+owner+"';", ""); len(rows) != 1 || rows[0][0] != "0" {
		t.Errorf("importgebruiker blijft staan na het verwijderen van de database: %v", rows)
	}
	if err := m.dropDatabase("mysql"); err == nil {
		t.Error("systeemdatabase verwijderen toegestaan")
	}
}

// In GRANT is _ een jokerteken: toegang tot pindahost_g mag nooit toegang tot pindahostXg geven.
func TestGrantsAreExact(t *testing.T) {
	m := newMariaDB()
	if status := m.status(); !status.Running {
		t.Skip("geen MariaDB beschikbaar")
	}
	const own, other, user = "pindahost_g", "pindahostXg", "pindahost_gu"
	cleanup := func() {
		_ = m.dropDatabase(own)
		_ = m.dropDatabase(other)
		_ = m.dropUser(user, "localhost")
	}
	cleanup()
	defer cleanup()
	for _, name := range []string{own, other} {
		if err := m.createDatabase(name); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := m.run("CREATE TABLE geheim (x INT); INSERT INTO geheim VALUES (42);", other); err != nil {
		t.Fatal(err)
	}
	password := randomPassword(20)
	if err := m.createUser(user, "localhost", password, own, false); err != nil {
		t.Fatal(err)
	}
	login := func(database, sql string) (string, error) {
		return runQuiet(10_000_000_000, "mariadb", "--protocol=tcp", "-h", "127.0.0.1", "-u", user, "-p"+password, "-N", "-e", sql, database)
	}
	if out, err := login(own, "SELECT DATABASE()"); err != nil || strings.TrimSpace(out) != own {
		t.Fatalf("geen toegang tot de eigen database: %v %s", err, out)
	}
	if out, err := login(other, "SELECT x FROM geheim"); err == nil {
		t.Fatalf("toegang tot een andere database via het jokerteken: %s", out)
	}
	users, err := m.users()
	if err != nil {
		t.Fatal(err)
	}
	for _, u := range users {
		if u.Name == user && (len(u.Databases) != 1 || u.Databases[0] != own) {
			t.Errorf("toegang verkeerd getoond: %v", u.Databases)
		}
	}
	// Ook een oude, niet-geëscapete GRANT moet in te trekken zijn.
	if _, err := m.run("GRANT SELECT ON `"+own+"`.* TO '"+user+"'@'localhost'; FLUSH PRIVILEGES;", ""); err != nil {
		t.Fatal(err)
	}
	if err := m.revoke(user, "localhost", own); err != nil {
		t.Fatal(err)
	}
	if out, err := login(own, "SELECT 1"); err == nil {
		t.Errorf("na intrekken nog toegang: %s", out)
	}
}

// Triggers en views uit een export van het paneel (DEFINER=root) moeten in te laden zijn en
// daarna blijven werken.
func TestImportDefiners(t *testing.T) {
	m := newMariaDB()
	if status := m.status(); !status.Running {
		t.Skip("geen MariaDB beschikbaar")
	}
	const source, target = "pindahost_ds", "pindahost_dt"
	cleanup := func() {
		_ = m.dropDatabase(source)
		_ = m.dropDatabase(target)
	}
	cleanup()
	defer cleanup()
	for _, name := range []string{source, target} {
		if err := m.createDatabase(name); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := m.run("CREATE TABLE t (id INT PRIMARY KEY, n INT); CREATE TABLE log (id INT);\n"+
		"CREATE TRIGGER t_ins AFTER INSERT ON t FOR EACH ROW INSERT INTO log VALUES (NEW.id);\n"+
		"CREATE VIEW v AS SELECT id, n * 2 AS dubbel FROM t;\n"+
		"INSERT INTO t VALUES (1, 5);", source); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	dump := filepath.Join(dir, "dump.sql")
	if err := m.export(source, dump); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(dump); !strings.Contains(string(data), "DEFINER=") {
		t.Fatal("export bevat geen DEFINER; de test test zo niets")
	}
	if err := m.importFile(target, dump, dir, func(string, ...any) {}); err != nil {
		t.Fatalf("export van het paneel niet in te laden: %v", err)
	}
	// Als root (zoals een plugin met een andere gebruiker): trigger en view moeten werken.
	if _, err := m.run("INSERT INTO t VALUES (2, 7);", target); err != nil {
		t.Fatalf("trigger werkt niet na de import: %v", err)
	}
	rows, err := m.run("SELECT (SELECT COUNT(*) FROM log), (SELECT dubbel FROM v WHERE id = 2);", target)
	if err != nil || len(rows) != 1 || rows[0][0] != "2" || rows[0][1] != "14" {
		t.Fatalf("trigger of view klopt niet: %v %v", rows, err)
	}
}

func TestStripDefiners(t *testing.T) {
	long := "INSERT INTO t VALUES ('" + strings.Repeat("DEFINER=`a`@`b` ", 20000) + "');\n"
	input := "/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/ /*!50003 TRIGGER x AFTER INSERT ON t FOR EACH ROW SET @a = 1 */;;\n" +
		"/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */\n" +
		"CREATE DEFINER=root@localhost PROCEDURE p() SELECT 1;\n" +
		"create definer = 'x'@'%' function f() returns int return 1;\n" +
		long +
		"-- DEFINER=`blijft`@`staan` in commentaar\n" +
		"/*!50003 CREATE*/ /*!50017 DEFINER=`zonder`@`einde`*/"
	var out strings.Builder
	if err := stripDefiners(strings.NewReader(input), &out); err != nil {
		t.Fatal(err)
	}
	got := out.String()
	for _, gone := range []string{"`root`@`localhost`", "root@localhost", "'x'@'%'", "`zonder`@`einde`"} {
		if strings.Contains(got, gone) {
			t.Errorf("DEFINER %s niet weggehaald", gone)
		}
	}
	for _, kept := range []string{"SQL SECURITY DEFINER", "CREATE PROCEDURE p()", long, "DEFINER=`blijft`@`staan`", "TRIGGER x AFTER INSERT"} {
		if !strings.Contains(got, kept) {
			t.Errorf("onterecht veranderd: %.60q", kept)
		}
	}
}

func TestSetupPluginMySQL(t *testing.T) {
	m := newMariaDB()
	if status := m.status(); !status.Running {
		t.Skip("geen MariaDB beschikbaar")
	}
	if exists, _ := m.databaseExists("pindacraft"); exists {
		t.Skip("er is al een echte pindacraft-database")
	}
	defer func() {
		_ = m.dropDatabase("pindacraft")
		_ = m.dropUser("pindacraft", "localhost")
	}()
	dir := t.TempDir()
	app, err := openApp(filepath.Join(dir, "panel", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	_ = app.config.update(func(c *Config) { c.BaseDir = filepath.Join(dir, "pinda") })
	plugin, err := app.setupPluginMySQL(true)
	if err != nil {
		t.Fatal(err)
	}
	read, _, _ := app.readPluginDB()
	if read.Type != "mysql" || read.Password != plugin.Password || len(read.Password) != 24 || !read.Convert {
		t.Fatalf("database.yml klopt niet: %+v", read)
	}
	// Kan de plugin-gebruiker echt inloggen (via TCP, zoals de plugin)?
	out, err := runQuiet(10_000_000_000, "mariadb", "--protocol=tcp", "-h", "127.0.0.1", "-u", "pindacraft", "-p"+read.Password, "pindacraft", "-N", "-e", "SELECT DATABASE()")
	if err != nil || strings.TrimSpace(out) != "pindacraft" {
		t.Fatalf("inloggen als pindacraft lukt niet: %v %s", err, out)
	}
	// Nog een keer: de plugin staat al op MySQL, dus er verandert niets (geen nieuw wachtwoord).
	again, err := app.setupPluginMySQL(true)
	if err != nil || again.Password != plugin.Password {
		t.Fatalf("opnieuw instellen veranderde het wachtwoord: %v", err)
	}
}

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
	if err := m.importFile(db, dump, func(string, ...any) {}); err != nil {
		t.Fatal(err)
	}
	rows, _ = m.run("SELECT COUNT(*) FROM t;", db)
	if len(rows) != 1 || rows[0][0] != "2" {
		t.Fatalf("na het inladen: %v", rows)
	}
	broken := filepath.Join(dir, "kapot.sql")
	_ = os.WriteFile(broken, []byte("CREATE TABEL oeps;"), 0o600)
	if err := m.importFile(db, broken, func(string, ...any) {}); err == nil || !strings.Contains(err.Error(), "syntax") {
		t.Errorf("kapotte import gaf geen nette fout: %v", err)
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
	if err := m.dropDatabase("mysql"); err == nil {
		t.Error("systeemdatabase verwijderen toegestaan")
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
	read, _, _ := readPluginDB(app.config.get().pluginDir())
	if read.Type != "mysql" || read.Password != plugin.Password || len(read.Password) != 24 || !read.Convert {
		t.Fatalf("database.yml klopt niet: %+v", read)
	}
	// Kan de plugin-gebruiker echt inloggen (via TCP, zoals de plugin)?
	out, err := runQuiet(10_000_000_000, "mariadb", "--protocol=tcp", "-h", "127.0.0.1", "-u", "pindacraft", "-p"+read.Password, "pindacraft", "-N", "-e", "SELECT DATABASE()")
	if err != nil || strings.TrimSpace(out) != "pindacraft" {
		t.Fatalf("inloggen als pindacraft lukt niet: %v %s", err, out)
	}
	// Nog een keer: wachtwoord wordt vernieuwd, niets gaat stuk.
	again, err := app.setupPluginMySQL(true)
	if err != nil || again.Password == plugin.Password {
		t.Fatalf("opnieuw instellen: %v", err)
	}
}

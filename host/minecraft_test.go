package main

import (
	"archive/zip"
	"bytes"
	"crypto/md5"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeJar maakt een jar met een plugin.yml.
func fakeJar(t *testing.T, name, version string) []byte {
	t.Helper()
	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	w, _ := writer.Create("plugin.yml")
	fmt.Fprintf(w, "name: %s\nversion: '%s'\nmain: nl.test.Main\ndescription: Test van %s\n", name, version, name)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func hexSum(data []byte, kind string) string {
	switch kind {
	case "md5":
		sum := md5.Sum(data)
		return hex.EncodeToString(sum[:])
	case "sha256":
		sum := sha256.Sum256(data)
		return hex.EncodeToString(sum[:])
	}
	sum := sha512.Sum512(data)
	return hex.EncodeToString(sum[:])
}

// fakeAPIs speelt Purpur, Modrinth en GitHub na.
// badHash: voor deze namen (een plugin, of "purpur-2631") geeft de API een verkeerd controlegetal.
var badHash = map[string]bool{}

func fakeAPIs(t *testing.T, jar []byte, plugins map[string][]byte, framework []byte) *httptest.Server {
	t.Helper()
	badHash = map[string]bool{"purpur-2631": true}
	mux := http.NewServeMux()
	var server *httptest.Server
	mux.HandleFunc("/purpur", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"project":"purpur","metadata":{"current":"26.2"},"versions":["1.20.4","1.21.11","26.1.2","26.2","26.3","evil/../x"]}`)
	})
	mux.HandleFunc("/purpur/26.2", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"builds":{"latest":"2633","all":["2631","2632","2633"]}}`)
	})
	mux.HandleFunc("/purpur/26.2/{build}", func(w http.ResponseWriter, r *http.Request) {
		build := r.PathValue("build")
		if build == "latest" {
			build = "2633"
		}
		sum := hexSum(jar, "md5")
		if badHash["purpur-"+build] {
			sum = hexSum([]byte("iets anders"), "md5")
		}
		json.NewEncoder(w).Encode(map[string]any{"version": "26.2", "build": build, "result": "SUCCESS", "md5": sum})
	})
	mux.HandleFunc("/purpur/26.2/{build}/download", func(w http.ResponseWriter, r *http.Request) { w.Write(jar) })
	mux.HandleFunc("/modrinth/project/{slug}/version", func(w http.ResponseWriter, r *http.Request) {
		slug := r.PathValue("slug")
		data, ok := plugins[slug]
		if !ok || !strings.Contains(r.URL.Query().Get("game_versions"), "26.2") {
			fmt.Fprint(w, `[]`)
			return
		}
		name := slug + "-1.0.jar"
		sum := hexSum(data, "sha512")
		if badHash[slug] {
			sum = hexSum([]byte("iets anders"), "sha512")
			name = slug + "-1.1.jar"
		}
		versions := []map[string]any{
			{"version_number": "1.1-SNAPSHOT", "version_type": "beta", "files": []map[string]any{{"url": server.URL + "/files/beta.jar", "filename": "beta.jar", "primary": true, "hashes": map[string]string{"sha512": "00"}}}},
			{"version_number": "1.0", "version_type": "release", "files": []map[string]any{{"url": server.URL + "/files/" + slug, "filename": name, "primary": true, "hashes": map[string]string{"sha512": sum}}}},
		}
		json.NewEncoder(w).Encode(versions)
	})
	mux.HandleFunc("/files/{slug}", func(w http.ResponseWriter, r *http.Request) { w.Write(plugins[r.PathValue("slug")]) })
	mux.HandleFunc("/github/repos/Rudyh0/PindaFramework/releases/latest", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"tag_name": "build-30", "assets": []map[string]any{
			{"name": "pinda-host-linux-amd64", "browser_download_url": server.URL + "/nope"},
			{"name": "PindaFramework-0.1.0-b30.jar", "browser_download_url": server.URL + "/framework", "digest": "sha256:" + hexSum(framework, "sha256")},
		}})
	})
	mux.HandleFunc("/framework", func(w http.ResponseWriter, r *http.Request) { w.Write(framework) })
	server = httptest.NewServer(mux)
	t.Cleanup(server.Close)
	purpurAPI, modrinthAPI, githubAPI = server.URL+"/purpur", server.URL+"/modrinth", server.URL+"/github"
	purpurCache.Lock()
	purpurCache.at = time.Time{}
	purpurCache.Unlock()
	return server
}

// fakeProcess doet alsof hij de server start en stopt.
type fakeProcess struct {
	mu       sync.Mutex
	state    string
	starts   int
	installs int
}

func (f *fakeProcess) Mode() string { return "test" }
func (f *fakeProcess) Status() ProcessStatus {
	f.mu.Lock()
	defer f.mu.Unlock()
	state := f.state
	if state == "" {
		state = "inactive"
	}
	return ProcessStatus{Installed: true, State: state}
}
func (f *fakeProcess) Install(ServerCommand) error {
	f.mu.Lock()
	f.installs++
	f.mu.Unlock()
	return nil
}
func (f *fakeProcess) Start(ServerCommand) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.state, f.starts = "active", f.starts+1
	return nil
}
func (f *fakeProcess) Stop() error                   { f.mu.Lock(); f.state = "inactive"; f.mu.Unlock(); return nil }
func (f *fakeProcess) Restart(c ServerCommand) error { return f.Start(c) }
func (f *fakeProcess) Kill() error                   { return f.Stop() }
func (f *fakeProcess) Recent() string                { return "" }

func testApp(t *testing.T) (*App, *fakeProcess) {
	t.Helper()
	allowRootServer = true
	t.Cleanup(func() { allowRootServer = false })
	dir := t.TempDir()
	app, err := openApp(filepath.Join(dir, "panel", "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	_ = app.config.update(func(c *Config) {
		c.BaseDir = filepath.Join(dir, "pinda")
		c.ServiceUser = ""
		c.ServerName = "PindaCraft"
		c.GamePort = 25565
	})
	process := &fakeProcess{}
	app.process = process
	return app, process
}

func TestVersionsAndJava(t *testing.T) {
	if compareVersions("1.21.11", "26.1") >= 0 || compareVersions("26.1.2", "26.1") <= 0 || compareVersions("26.2", "26.2.0") != 0 {
		t.Error("versies verkeerd vergeleken")
	}
	if requiredJava("26.2") != 25 || requiredJava("1.21.4") != 21 || requiredJava("1.20.4") != 17 || requiredJava("1.16.5") != 8 {
		t.Error("verkeerde Java-versie")
	}
	fakeAPIs(t, []byte("jar"), nil, nil)
	versions, err := purpurVersions(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if versions.Current != "26.2" || len(versions.Versions) != 5 || versions.Versions[0].Version != "26.3" || !versions.Versions[0].Experimental ||
		!versions.Versions[1].Recommended || versions.Versions[4].Framework || !versions.Versions[1].Framework {
		t.Errorf("versies: %+v", versions)
	}
}

func TestInstallServerAndPlugins(t *testing.T) {
	javaInstalls = func() []JavaInstall {
		return []JavaInstall{{Path: "/usr/bin/java", Major: 21}, {Path: "/opt/java25/bin/java", Major: 25}}
	}
	defer func() { javaInstalls = findJavas }()
	jar := []byte("nep-purpur-jar")
	plugins := map[string][]byte{
		"viaversion": fakeJar(t, "ViaVersion", "1.0"), "placeholderapi": fakeJar(t, "PlaceholderAPI", "1.0"),
		"coreprotect": fakeJar(t, "CoreProtect", "1.0"), "worldedit": fakeJar(t, "WorldEdit", "1.0"),
	}
	framework := fakeJar(t, "PindaFramework", "0.1.0-b30")
	fakeAPIs(t, jar, plugins, framework)
	app, process := testApp(t)
	root := app.serverRoot()

	// Al een server.properties met eigen instellingen: die blijven staan.
	if _, err := root.WriteFile("server.properties", strings.NewReader("#Minecraft server properties\nmotd=Mijn server\nrcon.password=kort\nview-distance=12\n"), 0); err != nil {
		t.Fatal(err)
	}
	var log []string
	say := func(format string, args ...any) { log = append(log, fmt.Sprintf(format, args...)) }
	if err := app.installServer(t.Context(), "26.2", "latest", say); err != nil {
		t.Fatalf("installeren: %v\n%s", err, strings.Join(log, "\n"))
	}
	if data, _ := os.ReadFile(filepath.Join(root.Base, serverJar)); string(data) != string(jar) {
		t.Error("server.jar niet neergezet")
	}
	state := app.server.get()
	if !state.Installed || state.Version != "26.2" || state.Build != "2633" || state.JavaMajor < 25 || len(state.RconPassword) != 32 || process.installs != 1 {
		t.Errorf("status na installeren: %+v (installs %d)", state, process.installs)
	}
	properties, _ := os.ReadFile(filepath.Join(root.Base, "server.properties"))
	for _, want := range []string{"motd=Mijn server", "view-distance=12", "enable-rcon=true", "rcon.port=25575", "rcon.password=" + state.RconPassword, "server-port=25565"} {
		if !strings.Contains(string(properties), want+"\n") {
			t.Errorf("server.properties mist %q:\n%s", want, properties)
		}
	}

	// Een download die niet klopt met het controlegetal mag server.jar niet vervangen.
	if err := os.WriteFile(filepath.Join(root.Base, serverJar), []byte("de oude jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := app.installServer(t.Context(), "26.2", "2631", say); err == nil || !strings.Contains(err.Error(), "controlegetal") {
		t.Errorf("download met een verkeerd controlegetal geaccepteerd: %v", err)
	}
	if data, _ := os.ReadFile(filepath.Join(root.Base, serverJar)); string(data) != "de oude jar" {
		t.Error("server.jar vervangen door een download die niet klopt")
	}
	if entries, _ := os.ReadDir(root.Base); len(entries) > 0 {
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), ".pinda-") {
				t.Errorf("tijdelijk bestand blijft staan: %s", entry.Name())
			}
		}
	}
	if err := app.installServer(t.Context(), "26.2", "2632", say); err != nil {
		t.Fatalf("opnieuw installeren: %v", err)
	}

	// EULA en plugins.
	if app.eulaAccepted() {
		t.Error("EULA al geaccepteerd")
	}
	if err := app.acceptEULA("rudy"); err != nil || !app.eulaAccepted() {
		t.Fatalf("EULA: %v", err)
	}
	// Een oude ViaVersion die weg moet, en een uitgezette WorldEdit die uit moet blijven.
	_, _ = root.WriteVerified("plugins/ViaVersion-0.9.jar", func(w io.Writer) (int64, error) {
		n, err := w.Write(fakeJar(t, "ViaVersion", "0.9"))
		return int64(n), err
	})
	_, _ = root.WriteVerified("plugins/worldedit-old.jar.disabled", func(w io.Writer) (int64, error) {
		n, err := w.Write(fakeJar(t, "WorldEdit", "0.1"))
		return int64(n), err
	})
	log = nil
	if err := app.installPlugins(t.Context(), allManagedKeys(), say); err != nil {
		t.Fatalf("plugins: %v\n%s", err, strings.Join(log, "\n"))
	}
	list, err := app.listPlugins()
	if err != nil {
		t.Fatal(err)
	}
	files := map[string]PluginInfo{}
	for _, plugin := range list {
		files[plugin.File] = plugin
	}
	for _, want := range []string{"PindaFramework-0.1.0-b30.jar", "viaversion-1.0.jar", "placeholderapi-1.0.jar", "coreprotect-1.0.jar", "worldedit-1.0.jar.disabled"} {
		if _, ok := files[want]; !ok {
			t.Errorf("plugin %s ontbreekt: %v", want, files)
		}
	}
	if _, ok := files["ViaVersion-0.9.jar"]; ok {
		t.Error("oude ViaVersion niet weggehaald")
	}
	if files["PindaFramework-0.1.0-b30.jar"].Managed != "pindaframework" || files["PindaFramework-0.1.0-b30.jar"].Version != "0.1.0-b30" {
		t.Errorf("PindaFramework niet herkend: %+v", files["PindaFramework-0.1.0-b30.jar"])
	}
	if got := app.server.get().Plugins["coreprotect"]; got.Version != "1.0" || got.Beta {
		t.Errorf("geen gewone release gekozen: %+v", got)
	}
	// Nog een keer: niets dubbel.
	if err := app.installPlugins(t.Context(), []string{"viaversion"}, say); err != nil {
		t.Fatal(err)
	}
	if again, _ := app.listPlugins(); len(again) != len(list) {
		t.Errorf("dubbele plugins na bijwerken: %d -> %d", len(list), len(again))
	}

	// Een plugin waarvan de download niet klopt: melding, en het oude bestand blijft staan.
	badHash["coreprotect"] = true
	log = nil
	if err := app.installPlugins(t.Context(), []string{"coreprotect"}, say); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.Join(log, "\n"), "controlegetal") {
		t.Errorf("geen melding over het controlegetal: %v", log)
	}
	if _, err := os.Stat(filepath.Join(root.Base, "plugins", "coreprotect-1.0.jar")); err != nil {
		t.Error("oude CoreProtect weg na een mislukte download")
	}
	if _, err := os.Stat(filepath.Join(root.Base, "plugins", "coreprotect-1.1.jar")); err == nil {
		t.Error("CoreProtect met een verkeerd controlegetal neergezet")
	}
}

func TestPluginForOlderMinecraft(t *testing.T) {
	plugins := map[string][]byte{"viaversion": fakeJar(t, "ViaVersion", "1.0")}
	fakeAPIs(t, []byte("jar"), plugins, fakeJar(t, "PindaFramework", "1"))
	app, _ := testApp(t)
	_ = app.server.update(func(s *ServerState) { s.Installed, s.Version = true, "1.20.4" })
	var log []string
	say := func(format string, args ...any) { log = append(log, fmt.Sprintf(format, args...)) }
	// PindaFramework kan niet op 1.20.4, maar dat is geen fout: de rest gaat door.
	if err := app.installPlugins(t.Context(), []string{"pindaframework", "viaversion"}, say); err != nil {
		t.Fatalf("plugins op een oude versie: %v", err)
	}
	if !strings.Contains(strings.Join(log, "\n"), "werkt pas vanaf Minecraft 26.1") {
		t.Errorf("geen uitleg over PindaFramework: %v", log)
	}
}

func TestProperties(t *testing.T) {
	content := "#Minecraft server properties\r\nmotd=A Minecraft Server\nlevel-seed=\nrcon.password=x\\\\y\n"
	updated := setProperties(content, map[string]string{"motd": "Pinda Crâft", "enable-rcon": "true"})
	if !strings.Contains(updated, `motd=Pinda Cr\u00e2ft`) || !strings.Contains(updated, "enable-rcon=true\n") || !strings.HasPrefix(updated, "#Minecraft server properties") {
		t.Errorf("setProperties:\n%s", updated)
	}
	if value, _ := propertyValue(updated, "motd"); value != "Pinda Crâft" {
		t.Errorf("motd terug: %q", value)
	}
	if value, _ := propertyValue(updated, "rcon.password"); value != `x\y` {
		t.Errorf("wachtwoord met backslash: %q", value)
	}
	unit := unitFile(ServerCommand{Java: "/usr/lib/jvm/java 25/bin/java", Args: jvmArgs(4096), Dir: "/opt/pinda/server", User: "minecraft", UID: 999})
	for _, want := range []string{"User=minecraft", `ExecStart="/usr/lib/jvm/java 25/bin/java" -Xms4096M -Xmx4096M`, "-jar server.jar --nogui", "Restart=on-failure", "WorkingDirectory=/opt/pinda/server"} {
		if !strings.Contains(unit, want) {
			t.Errorf("unit mist %q:\n%s", want, unit)
		}
	}
	if !strings.Contains(strings.Join(jvmArgs(16384), " "), "G1HeapRegionSize=16M") {
		t.Error("geen vlaggen voor veel geheugen")
	}
}

func TestConsoleLog(t *testing.T) {
	app, _ := testApp(t)
	root := app.serverRoot()
	logPath := filepath.Join(root.Base, "logs", "latest.log")
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(logPath, []byte("[10:00:00 INFO]: Starting\n[10:00:01 INFO]: half"), 0o644); err != nil {
		t.Fatal(err)
	}
	chunk, err := app.readConsole(0, 0)
	if err != nil || !chunk.Reset || chunk.Text != "[10:00:00 INFO]: Starting\n" {
		t.Fatalf("eerste keer: %v %+v", err, chunk)
	}
	file, _ := os.OpenFile(logPath, os.O_APPEND|os.O_WRONLY, 0)
	file.WriteString(" gelezen\n[10:00:02 INFO]: Done (3.1s)!\n")
	file.Close()
	next, err := app.readConsole(chunk.Offset, chunk.Inode)
	if err != nil || next.Reset || next.Text != "[10:00:01 INFO]: half gelezen\n[10:00:02 INFO]: Done (3.1s)!\n" {
		t.Fatalf("daarna: %v %+v", err, next)
	}
	// Herstart: een nieuw bestand (ander inode-nummer).
	os.Rename(logPath, logPath+".old")
	os.WriteFile(logPath, []byte("[11:00:00 INFO]: Opnieuw\n"), 0o644)
	again, err := app.readConsole(next.Offset, next.Inode)
	if err != nil || !again.Reset || again.Text != "[11:00:00 INFO]: Opnieuw\n" {
		t.Fatalf("na herstart: %v %+v", err, again)
	}
	// Een symlink naar buiten als latest.log: niet lezen.
	os.Remove(logPath)
	os.Symlink("/etc/passwd", logPath)
	if chunk, err := app.readConsole(0, 0); err == nil && strings.Contains(chunk.Text, "root:") {
		t.Error("console las een bestand buiten de servermap")
	}
}

// fakeRcon is een RCON-server zoals die van Minecraft.
func fakeRcon(t *testing.T, password string) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
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
						if body == "lang" {
							rconWrite(conn, id, rconResponseValue, strings.Repeat("a", rconMaxBody))
							rconWrite(conn, id, rconResponseValue, "einde")
							continue
						}
						rconWrite(conn, id, rconResponseValue, "§aJe typte: "+body)
					}
				}
			}(conn)
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port
}

func TestRcon(t *testing.T) {
	port := fakeRcon(t, "geheim-wachtwoord")
	response, err := rconCommand(port, "geheim-wachtwoord", "say hoi", 3*time.Second)
	if err != nil || response != "Je typte: say hoi" {
		t.Fatalf("rcon: %q %v", response, err)
	}
	if _, err := rconCommand(port, "fout", "list", 3*time.Second); err != errRconAuth {
		t.Errorf("verkeerd wachtwoord: %v", err)
	}
	if response, err := rconCommand(port, "geheim-wachtwoord", "lang", 3*time.Second); err != nil || !strings.HasSuffix(response, "einde") || len(response) != rconMaxBody+5 {
		t.Errorf("lang antwoord: %d %v", len(response), err)
	}
}

func TestServerListPing(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 512)
		conn.Read(buf)
		status := `{"version":{"name":"Purpur 26.2"},"players":{"max":20,"online":2,"sample":[{"name":"Rudyh0","id":"x"},{"name":"§cLisa","id":"y"}]}}`
		var body bytes.Buffer
		writeVarInt(&body, 0)
		writeVarInt(&body, int32(len(status)))
		body.WriteString(status)
		var packet bytes.Buffer
		writeVarInt(&packet, int32(body.Len()))
		packet.Write(body.Bytes())
		conn.Write(packet.Bytes())
	}()
	players, version, err := pingServer(listener.Addr().(*net.TCPAddr).Port, 3*time.Second)
	if err != nil || players.Online != 2 || players.Max != 20 || len(players.Names) != 2 || players.Names[1] != "Lisa" || version != "Purpur 26.2" {
		t.Fatalf("ping: %+v %q %v", players, version, err)
	}
	var check bytes.Buffer
	writeVarInt(&check, -1)
	if hex.EncodeToString(check.Bytes()) != "ffffffff0f" {
		t.Errorf("varint -1: %x", check.Bytes())
	}
	_ = binary.LittleEndian
}

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"
)

const (
	serverJar = "server.jar"
	eulaURL   = "https://aka.ms/MinecraftEULA"
	rconPort  = 25575
)

// ServerState staat in panel/server.json: wat het paneel over de Minecraft-server weet.
type ServerState struct {
	Installed    bool                     `json:"installed"`
	Version      string                   `json:"version,omitempty"`
	Build        string                   `json:"build,omitempty"`
	InstalledAt  int64                    `json:"installedAt,omitempty"`
	MemoryMB     int                      `json:"memoryMB,omitempty"`
	Java         string                   `json:"java,omitempty"`
	JavaMajor    int                      `json:"javaMajor,omitempty"`
	RconPort     int                      `json:"rconPort,omitempty"`
	RconPassword string                   `json:"rconPassword,omitempty"`
	PluginsReady bool                     `json:"pluginsReady,omitempty"`
	Plugins      map[string]InstalledFrom `json:"plugins,omitempty"`
}

// InstalledFrom: welke versie van een standaardplugin het paneel heeft neergezet.
type InstalledFrom struct {
	Version string `json:"version"`
	File    string `json:"file"`
	At      int64  `json:"at"`
	Beta    bool   `json:"beta,omitempty"`
}

type ServerStore struct {
	mu    sync.Mutex
	path  string
	state ServerState
}

func openServerStore(path string) (*ServerStore, error) {
	store := &ServerStore{path: path}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &store.state); err != nil {
		return nil, fmt.Errorf("%s is geen geldige JSON: %w", path, err)
	}
	return store, nil
}

func (s *ServerStore) get() ServerState {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.state
	plugins := map[string]InstalledFrom{}
	for key, value := range s.state.Plugins {
		plugins[key] = value
	}
	state.Plugins = plugins
	return state
}

func (s *ServerStore) update(change func(*ServerState)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.state
	next.Plugins = map[string]InstalledFrom{}
	for key, value := range s.state.Plugins {
		next.Plugins[key] = value
	}
	change(&next)
	if err := saveJSON(s.path, next); err != nil {
		return err
	}
	s.state = next
	return nil
}

// ============================================================ Java

// JavaInstall: een Java op deze server.
type JavaInstall struct {
	Path  string `json:"path"`
	Major int    `json:"major"`
}

var javaVersionPattern = regexp.MustCompile(`version "([0-9]+)(?:\.([0-9]+))?`)

func javaMajor(path string) int {
	out, err := runQuiet(10*time.Second, path, "-version")
	if err != nil && out == "" {
		return 0
	}
	match := javaVersionPattern.FindStringSubmatch(out)
	if match == nil {
		return 0
	}
	major, _ := strconv.Atoi(match[1])
	if major == 1 && match[2] != "" {
		major, _ = strconv.Atoi(match[2]) // "1.8.0" = Java 8
	}
	return major
}

// javaInstalls: te vervangen in tests.
var javaInstalls = findJavas

// findJavas: alle Java's (in /usr/lib/jvm en op het PATH), lage versie eerst.
func findJavas() []JavaInstall {
	candidates, _ := filepath.Glob("/usr/lib/jvm/*/bin/java")
	if path, err := exec.LookPath("java"); err == nil {
		candidates = append(candidates, path)
	}
	seen := map[string]bool{}
	var list []JavaInstall
	for _, candidate := range candidates {
		real, err := filepath.EvalSymlinks(candidate)
		if err != nil || seen[real] {
			continue
		}
		seen[real] = true
		if major := javaMajor(real); major > 0 {
			list = append(list, JavaInstall{Path: real, Major: major})
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Major < list[j].Major })
	return list
}

// pickJava: de laagste Java die nieuw genoeg is (oude Minecraft-versies houden niet van een
// veel nieuwere Java).
func pickJava(required int) (JavaInstall, error) {
	javas := javaInstalls()
	for _, java := range javas {
		if java.Major >= required {
			return java, nil
		}
	}
	if len(javas) == 0 {
		return JavaInstall{}, fmt.Errorf("er is geen Java geïnstalleerd; draai de installer opnieuw (of: sudo apt install openjdk-%d-jre-headless)", required)
	}
	return JavaInstall{}, fmt.Errorf("deze Minecraft-versie heeft Java %d nodig, maar de nieuwste hier is Java %d (sudo apt install openjdk-%d-jre-headless)",
		required, javas[len(javas)-1].Major, required)
}

// ============================================================ geheugen en opstartregel

// defaultMemoryMB: ongeveer 60% van het geheugen, maar altijd 1 GB over voor de rest.
func defaultMemoryMB() int {
	total, _ := memory()
	totalMB := int(total >> 20)
	if totalMB <= 0 {
		return 2048
	}
	memoryMB := totalMB * 6 / 10 / 512 * 512
	if memoryMB > 16384 {
		memoryMB = 16384
	}
	if totalMB-memoryMB < 1024 {
		memoryMB = (totalMB - 1024) / 512 * 512
	}
	if memoryMB < 1024 {
		memoryMB = 1024
	}
	return memoryMB
}

func maxMemoryMB() int {
	total, _ := memory()
	totalMB := int(total >> 20)
	if totalMB <= 1536 {
		return 1024
	}
	return (totalMB - 512) / 256 * 256
}

// jvmArgs: de vlaggen van Aikar (https://docs.papermc.io/paper/aikars-flags), de standaard
// voor Paper en Purpur.
func jvmArgs(memoryMB int) []string {
	args := []string{fmt.Sprintf("-Xms%dM", memoryMB), fmt.Sprintf("-Xmx%dM", memoryMB),
		"-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
		"-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch"}
	if memoryMB >= 12288 {
		args = append(args, "-XX:G1NewSizePercent=40", "-XX:G1MaxNewSizePercent=50", "-XX:G1HeapRegionSize=16M",
			"-XX:G1ReservePercent=15", "-XX:InitiatingHeapOccupancyPercent=20")
	} else {
		args = append(args, "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
			"-XX:G1ReservePercent=20", "-XX:InitiatingHeapOccupancyPercent=15")
	}
	return append(args, "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:G1MixedGCLiveThresholdPercent=90",
		"-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
		"-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true", "-jar", serverJar, "--nogui")
}

// ServerCommand: hoe de server gestart wordt.
type ServerCommand struct {
	Java string
	Args []string
	Dir  string
	User string
	UID  int
	GID  int
}

func (a *App) serverCommand() (ServerCommand, error) {
	config := a.config.get()
	state := a.server.get()
	if !state.Installed {
		return ServerCommand{}, errors.New("de server is nog niet geïnstalleerd")
	}
	java := state.Java
	if java == "" {
		found, err := pickJava(requiredJava(state.Version))
		if err != nil {
			return ServerCommand{}, err
		}
		java = found.Path
	}
	memoryMB := state.MemoryMB
	if memoryMB <= 0 {
		memoryMB = defaultMemoryMB()
	}
	uid, gid := lookupOwner(config.ServiceUser)
	// Nooit als root: dan kon elke plugin (of een geüploade jar) alles op de VPS.
	if (uid <= 0 || gid <= 0) && !allowRootServer {
		return ServerCommand{}, fmt.Errorf("de gebruiker %q bestaat niet (of is root); draai de installer opnieuw", config.ServiceUser)
	}
	return ServerCommand{Java: java, Args: jvmArgs(memoryMB), Dir: config.serverDir(), User: config.ServiceUser, UID: uid, GID: gid}, nil
}

// allowRootServer: alleen voor tests (daar is geen minecraft-gebruiker).
var allowRootServer = false

// unitFile: de systemd-dienst voor de server.
func unitFile(command ServerCommand) string {
	user := command.User
	if user == "" || command.UID < 0 {
		user = "nobody"
	}
	quoted := []string{systemdQuote(command.Java)}
	for _, arg := range command.Args {
		quoted = append(quoted, systemdQuote(arg))
	}
	return fmt.Sprintf(`# Beheerd door PindaHost (het dev-paneel). Aanpassen gaat via het paneel.
[Unit]
Description=Minecraft-server (PindaHost)
After=network-online.target mariadb.service
Wants=network-online.target

[Service]
Type=simple
User=%s
Group=%s
WorkingDirectory=%s
ExecStart=%s
# Netjes stoppen: Minecraft bewaart de wereld bij SIGTERM.
KillSignal=SIGTERM
TimeoutStopSec=120
SuccessExitStatus=0 143
# Bij een crash na 10 seconden opnieuw starten.
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true

[Install]
WantedBy=multi-user.target
`, user, user, strings.NewReplacer("\n", "", "\r", "").Replace(command.Dir), strings.Join(quoted, " "))
}

// systemdQuote zet een argument tussen aanhalingstekens als dat nodig is.
func systemdQuote(value string) string {
	if value != "" && !strings.ContainsAny(value, " \t\"'\\$%;\n\r") {
		return value
	}
	replacer := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "$", "$$", "%", "%%", "\n", `\n`, "\r", "")
	return `"` + replacer.Replace(value) + `"`
}

// ============================================================ server.properties en eula.txt

// propertyValue: de waarde van een sleutel in een .properties-bestand.
func propertyValue(content, key string) (string, bool) {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimRight(line, "\r")
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") || strings.HasPrefix(trimmed, "!") {
			continue
		}
		name, value, ok := strings.Cut(trimmed, "=")
		if ok && strings.TrimSpace(name) == key {
			return unescapeProperty(strings.TrimSpace(value)), true
		}
	}
	return "", false
}

// setProperties zet sleutels in een .properties-bestand; de rest (en commentaar) blijft staan.
func setProperties(content string, values map[string]string) string {
	done := map[string]bool{}
	lines := strings.Split(strings.TrimRight(content, "\n"), "\n")
	if content == "" {
		lines = nil
	}
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") || strings.HasPrefix(trimmed, "!") {
			continue
		}
		name, _, ok := strings.Cut(trimmed, "=")
		key := strings.TrimSpace(name)
		if value, wanted := values[key]; ok && wanted {
			lines[i] = key + "=" + escapeProperty(value)
			done[key] = true
		}
	}
	var keys []string
	for key := range values {
		if !done[key] {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	for _, key := range keys {
		lines = append(lines, key+"="+escapeProperty(values[key]))
	}
	return strings.Join(lines, "\n") + "\n"
}

func escapeProperty(value string) string {
	var out strings.Builder
	for _, r := range value {
		switch {
		case r == '\\':
			out.WriteString(`\\`)
		case r == '\n':
			out.WriteString(`\n`)
		case r == '\r':
		case r > 0xffff:
			// Buiten het BMP (bijv. emoji): als UTF-16-paar, zoals Java het leest.
			high, low := utf16.EncodeRune(r)
			out.WriteString(fmt.Sprintf(`\u%04x\u%04x`, high, low))
		case r < 0x20 || r > 0x7e:
			out.WriteString(fmt.Sprintf(`\u%04x`, r))
		default:
			out.WriteRune(r)
		}
	}
	return out.String()
}

func unescapeProperty(value string) string {
	if !strings.Contains(value, `\`) {
		return value
	}
	var out strings.Builder
	for i := 0; i < len(value); i++ {
		if value[i] != '\\' || i+1 >= len(value) {
			out.WriteByte(value[i])
			continue
		}
		i++
		switch value[i] {
		case 'n':
			out.WriteByte('\n')
		case 't':
			out.WriteByte('\t')
		case 'u':
			if i+4 < len(value) {
				if code, err := strconv.ParseUint(value[i+1:i+5], 16, 32); err == nil && utf8.ValidRune(rune(code)) {
					out.WriteRune(rune(code))
					i += 4
					continue
				}
			}
			out.WriteByte('u')
		default:
			out.WriteByte(value[i])
		}
	}
	return out.String()
}

func (a *App) serverRoot() FileRoot {
	root, _ := a.fileRoot("server")
	return root
}

// ensureProperties zet de instellingen die het paneel nodig heeft in server.properties (RCON
// voor de console, de poort). De rest maakt Minecraft zelf bij de eerste start.
func (a *App) ensureProperties() error {
	config := a.config.get()
	state := a.server.get()
	root := a.serverRoot()
	content := ""
	existing, err := root.ReadText("server.properties")
	switch {
	case err == nil && !existing.Binary && !existing.TooLarge:
		content = existing.Content
	case err != nil && !errors.Is(err, os.ErrNotExist):
		return err
	}
	password := state.RconPassword
	if password == "" {
		if value, ok := propertyValue(content, "rcon.password"); ok && len(value) >= 16 {
			password = value
		} else {
			password = randomToken()[:32]
		}
	}
	port := config.GamePort
	if port == 0 {
		port = 25565
	}
	values := map[string]string{
		"enable-rcon": "true", "rcon.port": strconv.Itoa(rconPort), "rcon.password": password,
		"broadcast-rcon-to-ops": "false", "server-port": strconv.Itoa(port),
	}
	if content == "" {
		content = "# Gemaakt door PindaHost. Minecraft vult de rest aan bij de eerste start.\n"
		if config.ServerName != "" {
			values["motd"] = config.ServerName
		}
	}
	if _, err := root.WriteFile("server.properties", strings.NewReader(setProperties(content, values)), 0); err != nil {
		return err
	}
	return a.server.update(func(s *ServerState) {
		s.RconPassword = password
		s.RconPort = rconPort
	})
}

// eulaAccepted: staat er eula=true in eula.txt?
func (a *App) eulaAccepted() bool {
	file, err := a.serverRoot().ReadText("eula.txt")
	if err != nil || file.Binary {
		return false
	}
	value, _ := propertyValue(file.Content, "eula")
	return strings.EqualFold(value, "true")
}

func (a *App) acceptEULA(user string) error {
	content := fmt.Sprintf("# Geaccepteerd in het dev-paneel door %s op %s.\n# %s\neula=true\n",
		user, time.Now().Format("02-01-2006 15:04"), eulaURL)
	_, err := a.serverRoot().WriteFile("eula.txt", strings.NewReader(content), 0)
	return err
}

// ============================================================ installeren

// installServer zet Purpur (versie + build, of "latest") neer. De wereld en de plugins blijven
// staan; alleen server.jar wordt vervangen.
func (a *App) installServer(ctx context.Context, version, build string, say func(string, ...any)) error {
	if status := a.process.Status(); status.State == "active" || status.State == "activating" || status.State == "deactivating" {
		return errors.New("stop de server eerst")
	}
	info, err := purpurBuild(ctx, version, build)
	if err != nil {
		return fmt.Errorf("Purpur %s: %w", version, err)
	}
	if info.Result != "" && info.Result != "SUCCESS" {
		return fmt.Errorf("build %s van Purpur %s is mislukt bij Purpur zelf; kies een andere build", info.Build, version)
	}
	if info.MD5 == "" {
		return errors.New("Purpur gaf geen controlegetal voor deze build")
	}
	java, err := pickJava(requiredJava(version))
	if err != nil {
		return err
	}
	say("Java %d: %s", java.Major, java.Path)
	say("Purpur %s (build %s) downloaden…", version, info.Build)
	root := a.serverRoot()
	size, err := root.WriteVerified(serverJar, func(w io.Writer) (int64, error) {
		return download(ctx, purpurDownloadURL(version, info.Build), w, 512<<20, Expected{MD5: info.MD5})
	})
	if err != nil {
		return err
	}
	say("Gedownload (%s), controlegetal klopt.", humanBytes(size))
	if err := root.Create("plugins", true); err != nil && !errors.Is(err, errExists) {
		return err
	}
	if err := a.server.update(func(s *ServerState) {
		s.Installed = true
		s.Version = version
		s.Build = info.Build
		s.InstalledAt = time.Now().UnixMilli()
		s.Java = java.Path
		s.JavaMajor = java.Major
		if s.MemoryMB <= 0 {
			s.MemoryMB = defaultMemoryMB()
		}
	}); err != nil {
		return err
	}
	if err := a.ensureProperties(); err != nil {
		return fmt.Errorf("server.properties: %w", err)
	}
	say("server.properties klaargezet (poort %d, console via RCON).", a.config.get().GamePort)
	command, err := a.serverCommand()
	if err != nil {
		return err
	}
	if err := a.process.Install(command); err != nil {
		return err
	}
	say("Dienst %s ingesteld (%d MB geheugen).", minecraftUnit, a.server.get().MemoryMB)
	a.openGamePort()
	return nil
}

// openGamePort zet de Minecraft-poort open in de firewall (de installer deed alleen 25565).
func (a *App) openGamePort() {
	port := a.config.get().GamePort
	if port == 0 || port == 25565 {
		return
	}
	if _, err := exec.LookPath("ufw"); err == nil {
		_, _ = runQuiet(15*time.Second, "ufw", "allow", fmt.Sprintf("%d/tcp", port), "comment", "Minecraft")
	}
}

// ============================================================ console

// ConsoleChunk: nieuwe regels uit logs/latest.log.
type ConsoleChunk struct {
	Text   string `json:"text"`
	Offset int64  `json:"offset"`
	Inode  uint64 `json:"inode"`
	Reset  bool   `json:"reset"`
	Source string `json:"source"`
}

const consoleTail = 96 << 10

// readConsole geeft wat er sinds offset bij is gekomen. Is het bestand nieuw (herstart) of
// korter geworden, dan begint het opnieuw met het laatste stuk.
func (a *App) readConsole(offset int64, inode uint64) (ConsoleChunk, error) {
	root, err := a.serverRoot().open()
	if err != nil {
		return ConsoleChunk{}, err
	}
	defer root.Close()
	file, info, err := openRegular(root, "logs/latest.log")
	if errors.Is(err, os.ErrNotExist) {
		return ConsoleChunk{Text: a.process.Recent(), Reset: true, Source: a.process.Mode()}, nil
	}
	if err != nil {
		return ConsoleChunk{}, err
	}
	defer file.Close()
	chunk := ConsoleChunk{Inode: inodeOf(info), Source: "log"}
	size := info.Size()
	start := offset
	if chunk.Inode != inode || offset > size || offset < 0 {
		chunk.Reset = true
		start = size - consoleTail
		if start < 0 {
			start = 0
		}
	}
	if _, err := file.Seek(start, io.SeekStart); err != nil {
		return chunk, err
	}
	data, err := io.ReadAll(io.LimitReader(file, 512<<10))
	if err != nil {
		return chunk, err
	}
	// Alleen hele regels; de rest komt de volgende keer. Is één regel langer dan alles wat we
	// lezen, dan toch doorgeven (anders blijft de console daar hangen).
	if cut := strings.LastIndexByte(string(data), '\n'); cut >= 0 {
		data = data[:cut+1]
	} else if len(data) < 512<<10 {
		data = nil
	}
	text := string(data)
	if chunk.Reset && start > 0 {
		if first := strings.IndexByte(text, '\n'); first >= 0 {
			text = text[first+1:]
		}
	}
	chunk.Text = strings.ToValidUTF8(text, "�")
	chunk.Offset = start + int64(len(data))
	return chunk, nil
}

// players: wie er online is (via RCON "list").
type Players struct {
	Online int      `json:"online"`
	Max    int      `json:"max"`
	Names  []string `json:"names"`
}

var listPattern = regexp.MustCompile(`There are (\d+) (?:of a max(?: of)?|out of maximum) (\d+) players online[.:]?\s*(.*)`)

func parsePlayers(response string) (Players, bool) {
	match := listPattern.FindStringSubmatch(strings.ReplaceAll(response, "\n", " "))
	if match == nil {
		return Players{}, false
	}
	players := Players{Names: []string{}}
	players.Online, _ = strconv.Atoi(match[1])
	players.Max, _ = strconv.Atoi(match[2])
	for _, name := range strings.Split(match[3], ",") {
		if name = strings.TrimSpace(name); name != "" {
			players.Names = append(players.Names, name)
		}
	}
	return players, true
}

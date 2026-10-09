package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"time"
)

// ManagedPlugin: een plugin die het paneel zelf neerzet en bijwerkt.
type ManagedPlugin struct {
	Key     string `json:"key"`
	Name    string `json:"name"` // zoals in plugin.yml
	Source  string `json:"source"`
	Project string `json:"project"`
	About   string `json:"about"`
	// Voor GitHub: het begin van de bestandsnaam.
	Prefix string `json:"-"`
	// Minimale Minecraft-versie.
	MinVersion string `json:"minVersion,omitempty"`
	Required   bool   `json:"required"`
}

var managedPlugins = []ManagedPlugin{
	{Key: "pindaframework", Name: "PindaFramework", Source: "github", Project: "Rudyh0/PindaFramework", Prefix: "PindaFramework-",
		MinVersion: minFrameworkVersion, Required: true, About: "De kern van PindaCraft: rangen, economy, moderatie, webpaneel en meer."},
	{Key: "viaversion", Name: "ViaVersion", Source: "modrinth", Project: "viaversion",
		About: "Spelers met een nieuwere Minecraft-versie kunnen ook meedoen."},
	{Key: "placeholderapi", Name: "PlaceholderAPI", Source: "modrinth", Project: "placeholderapi",
		About: "Placeholders zoals %pinda_rank% in andere plugins (TAB, hologrammen, …)."},
	{Key: "coreprotect", Name: "CoreProtect", Source: "modrinth", Project: "coreprotect",
		About: "Houdt bij wie welk blok plaatste of brak; grief terugdraaien. Mods mogen inspecteren en opzoeken."},
	{Key: "worldedit", Name: "WorldEdit", Source: "modrinth", Project: "worldedit",
		About: "Snel bouwen en aanpassen in de wereld. Alleen voor PindaAdmin (operator)."},
}

func managedByKey(key string) (ManagedPlugin, bool) {
	for _, plugin := range managedPlugins {
		if plugin.Key == key {
			return plugin, true
		}
	}
	return ManagedPlugin{}, false
}

func managedByName(name string) (ManagedPlugin, bool) {
	for _, plugin := range managedPlugins {
		if strings.EqualFold(plugin.Name, name) {
			return plugin, true
		}
	}
	return ManagedPlugin{}, false
}

// PluginInfo: een plugin in plugins/.
type PluginInfo struct {
	File        string `json:"file"`
	Name        string `json:"name"`
	Version     string `json:"version"`
	Description string `json:"description,omitempty"`
	Enabled     bool   `json:"enabled"`
	Size        int64  `json:"size"`
	Modified    int64  `json:"modified"`
	Managed     string `json:"managed,omitempty"`
	Error       string `json:"error,omitempty"`
}

func isPluginFile(name string) (bool, bool) {
	lower := strings.ToLower(name)
	switch {
	case strings.HasSuffix(lower, ".jar"):
		return true, true
	case strings.HasSuffix(lower, ".jar.disabled"):
		return true, false
	}
	return false, false
}

// jarPluginInfo leest naam en versie uit plugin.yml (of paper-plugin.yml) in een jar.
func jarPluginInfo(root *os.Root, rel string) (string, string, string, error) {
	file, info, err := openRegular(root, rel)
	if err != nil {
		return "", "", "", err
	}
	defer file.Close()
	if info.Size() > 512<<20 {
		return "", "", "", errors.New("te groot")
	}
	reader, err := openZip(file, info.Size(), 100_000)
	if err != nil {
		return "", "", "", errors.New("geen geldig jar-bestand (of te veel onderdelen)")
	}
	for _, wanted := range []string{"paper-plugin.yml", "plugin.yml"} {
		for _, entry := range reader.File {
			if entry.Name != wanted || entry.UncompressedSize64 > 1<<20 {
				continue
			}
			content, err := entry.Open()
			if err != nil {
				continue
			}
			data, _ := io.ReadAll(io.LimitReader(content, 1<<20))
			content.Close()
			values := parseSimpleYAML(bytes.NewReader(data))
			if values["name"] != "" {
				return values["name"], values["version"], values["description"], nil
			}
		}
	}
	return "", "", "", errors.New("geen plugin.yml in dit bestand")
}

func (a *App) listPlugins() ([]PluginInfo, error) {
	fileRoot := a.serverRoot()
	root, err := fileRoot.open()
	if err != nil {
		return nil, err
	}
	defer root.Close()
	dir, err := openDir(root, "plugins")
	if errors.Is(err, os.ErrNotExist) {
		return []PluginInfo{}, nil
	}
	if err != nil {
		return nil, err
	}
	names, err := dir.Readdirnames(-1)
	dir.Close()
	if err != nil {
		return nil, err
	}
	list := []PluginInfo{}
	for _, name := range names {
		isPlugin, enabled := isPluginFile(name)
		if !isPlugin || strings.HasPrefix(name, ".") {
			continue
		}
		rel := "plugins/" + name
		info, err := root.Lstat(rel)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		plugin := PluginInfo{File: name, Enabled: enabled, Size: info.Size(), Modified: info.ModTime().UnixMilli()}
		if pluginName, version, description, err := jarPluginInfo(root, rel); err == nil {
			plugin.Name, plugin.Version, plugin.Description = pluginName, version, clip(description, 200)
			if managed, ok := managedByName(pluginName); ok {
				plugin.Managed = managed.Key
			}
		} else {
			plugin.Name = strings.TrimSuffix(strings.TrimSuffix(name, ".disabled"), ".jar")
			plugin.Error = err.Error()
		}
		list = append(list, plugin)
	}
	sort.Slice(list, func(i, j int) bool { return strings.ToLower(list[i].Name) < strings.ToLower(list[j].Name) })
	return list, nil
}

// resolvePlugin: welke versie van een standaardplugin er te downloaden is.
func resolvePlugin(ctx context.Context, plugin ManagedPlugin, minecraft string) (RemotePlugin, error) {
	switch plugin.Source {
	case "github":
		return githubLatest(ctx, plugin.Project, plugin.Prefix)
	case "modrinth":
		return modrinthLatest(ctx, plugin.Project, minecraft)
	}
	return RemotePlugin{}, errors.New("onbekende bron")
}

// installPlugin zet de nieuwste versie van een standaardplugin neer en haalt oudere jars van
// dezelfde plugin weg. Stond de plugin uit (.jar.disabled), dan blijft de nieuwe ook uit.
func (a *App) installPlugin(ctx context.Context, plugin ManagedPlugin, minecraft string, say func(string, ...any)) (InstalledFrom, error) {
	if plugin.MinVersion != "" && compareVersions(minecraft, plugin.MinVersion) < 0 {
		return InstalledFrom{}, fmt.Errorf("%s werkt pas vanaf Minecraft %s", plugin.Name, plugin.MinVersion)
	}
	remote, err := resolvePlugin(ctx, plugin, minecraft)
	if err != nil {
		return InstalledFrom{}, err
	}
	filename, err := cleanName(remote.Filename)
	if err != nil || !strings.HasSuffix(strings.ToLower(filename), ".jar") {
		return InstalledFrom{}, fmt.Errorf("vreemde bestandsnaam: %q", remote.Filename)
	}
	current, err := a.listPlugins()
	if err != nil {
		return InstalledFrom{}, err
	}
	var same []PluginInfo
	anyEnabled := false
	for _, existing := range current {
		if strings.EqualFold(existing.Name, plugin.Name) {
			same = append(same, existing)
			anyEnabled = anyEnabled || existing.Enabled
		}
	}
	target := filename
	if len(same) > 0 && !anyEnabled {
		target += ".disabled"
	}
	for _, existing := range same {
		if existing.File == target {
			// Al de nieuwste.
			return InstalledFrom{Version: remote.Version, File: target, At: time.Now().UnixMilli(), Beta: remote.Beta}, nil
		}
	}
	root := a.serverRoot()
	size, err := root.WriteVerified("plugins/"+target, func(w io.Writer) (int64, error) {
		return download(ctx, remote.URL, w, 256<<20, remote.Expected)
	})
	if err != nil {
		return InstalledFrom{}, err
	}
	for _, existing := range same {
		if existing.File != target {
			if _, err := root.Delete("plugins/" + existing.File); err == nil {
				say("  Oude versie weggehaald: %s", existing.File)
			}
		}
	}
	beta := ""
	if remote.Beta {
		beta = " (bèta: er is nog geen gewone release voor deze Minecraft-versie)"
	}
	say("  %s %s neergezet (%s)%s", plugin.Name, remote.Version, humanBytes(size), beta)
	return InstalledFrom{Version: remote.Version, File: target, At: time.Now().UnixMilli(), Beta: remote.Beta}, nil
}

// installPlugins zet de gekozen standaardplugins neer. Lukt een verplichte plugin
// (PindaFramework) niet, dan is dat een fout; bij de rest alleen een melding.
func (a *App) installPlugins(ctx context.Context, keys []string, say func(string, ...any)) error {
	state := a.server.get()
	if !state.Installed {
		return errors.New("de server is nog niet geïnstalleerd")
	}
	var failed []string
	for _, key := range keys {
		plugin, ok := managedByKey(key)
		if !ok {
			continue
		}
		say("%s ophalen…", plugin.Name)
		installed, err := a.installPlugin(ctx, plugin, state.Version, say)
		if err != nil {
			say("  ⚠ %s: %s", plugin.Name, err.Error())
			if plugin.Required && plugin.MinVersion != "" && compareVersions(state.Version, plugin.MinVersion) >= 0 {
				failed = append(failed, plugin.Name)
			}
			continue
		}
		if err := a.server.update(func(s *ServerState) {
			if s.Plugins == nil {
				s.Plugins = map[string]InstalledFrom{}
			}
			s.Plugins[plugin.Key] = installed
		}); err != nil {
			return err
		}
	}
	if len(failed) > 0 {
		return fmt.Errorf("%s kon niet worden neergezet", strings.Join(failed, ", "))
	}
	return nil
}

func allManagedKeys() []string {
	keys := make([]string, 0, len(managedPlugins))
	for _, plugin := range managedPlugins {
		keys = append(keys, plugin.Key)
	}
	return keys
}

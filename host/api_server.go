package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

func (a *App) registerServer(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/server", a.api(member, a.handleServer))
	mux.HandleFunc("GET /api/server/versions", a.api(member, a.handleServerVersions))
	mux.HandleFunc("GET /api/server/versions/{version}", a.api(member, a.handleServerBuilds))
	mux.HandleFunc("POST /api/server/install", a.api(admin, a.handleServerInstall))
	mux.HandleFunc("POST /api/server/power", a.api(member, a.handleServerPower))
	mux.HandleFunc("POST /api/server/eula", a.api(member, a.handleServerEULA))
	mux.HandleFunc("GET /api/server/console", a.api(member, a.handleServerConsole))
	mux.HandleFunc("POST /api/server/command", a.api(member, a.handleServerCommand))
	mux.HandleFunc("POST /api/server/settings", a.api(member, a.handleServerSettings))
	mux.HandleFunc("GET /api/server/plugins", a.api(member, a.handleServerPlugins))
	mux.HandleFunc("POST /api/server/plugins/install", a.api(member, a.handleServerPluginsInstall))
	mux.HandleFunc("POST /api/server/plugins/toggle", a.api(member, a.handleServerPluginToggle))
	mux.HandleFunc("POST /api/server/plugins/delete", a.api(member, a.handleServerPluginDelete))
}

var errServerBusy = &apiError{http.StatusConflict, "Er loopt al iets met de server (installeren of plugins). Wacht tot dat klaar is."}

func (a *App) handleServer(q *Request) (any, error) {
	config := a.config.get()
	state := a.server.get()
	status := a.process.Status()
	memoryMB := state.MemoryMB
	if memoryMB <= 0 {
		memoryMB = defaultMemoryMB()
	}
	port := config.GamePort
	if port == 0 {
		port = 25565
	}
	result := map[string]any{
		"installed": state.Installed, "version": state.Version, "build": state.Build, "installedAt": state.InstalledAt,
		"java": state.JavaMajor, "memoryMB": memoryMB, "maxMemoryMB": maxMemoryMB(), "defaultMemoryMB": defaultMemoryMB(),
		"mode": a.process.Mode(), "state": status.State, "since": status.Since, "memoryUsed": status.Memory,
		"eula": state.Installed && a.eulaAccepted(), "eulaUrl": eulaURL, "pluginsReady": state.PluginsReady,
		"busy": a.serverBusy(), "address": config.GameAddress, "port": port, "players": nil,
	}
	result["memTotal"], _ = memory()
	if status.State == "active" {
		if players, version, err := pingServer(port, 2*time.Second); err == nil {
			result["players"] = players
			result["running"] = version
		}
	}
	return result, nil
}

// serverBusy: loopt er een installatie of plugin-update?
func (a *App) serverBusy() bool {
	return a.serverRunning.Load()
}

// lockServerJob: één taak tegelijk (installeren, plugins, klaarmaken).
func (a *App) lockServerJob() bool {
	if !a.serverJob.TryLock() {
		return false
	}
	a.serverRunning.Store(true)
	return true
}

func (a *App) unlockServerJob() {
	a.serverRunning.Store(false)
	a.serverJob.Unlock()
}

func (a *App) handleServerVersions(q *Request) (any, error) {
	ctx, cancel := context.WithTimeout(q.r.Context(), 20*time.Second)
	defer cancel()
	versions, err := purpurVersions(ctx)
	if err != nil {
		return nil, badRequest("De versies van Purpur zijn nu niet op te halen: %s", err.Error())
	}
	state := a.server.get()
	return map[string]any{"current": versions.Current, "versions": versions.Versions, "installed": state.Version,
		"installedBuild": state.Build, "frameworkMin": minFrameworkVersion}, nil
}

func (a *App) handleServerBuilds(q *Request) (any, error) {
	version := q.r.PathValue("version")
	if !minecraftVersionPattern.MatchString(version) {
		return nil, badRequest("Ongeldige versie.")
	}
	ctx, cancel := context.WithTimeout(q.r.Context(), 20*time.Second)
	defer cancel()
	builds, err := purpurBuilds(ctx, version)
	if err != nil {
		return nil, badRequest("De builds zijn nu niet op te halen: %s", err.Error())
	}
	return map[string]any{"version": version, "builds": builds}, nil
}

func (a *App) handleServerInstall(q *Request) (any, error) {
	var body struct {
		Version string `json:"version"`
		Build   string `json:"build"`
		Confirm bool   `json:"confirm"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if body.Build == "" {
		body.Build = "latest"
	}
	if !minecraftVersionPattern.MatchString(body.Version) || (body.Build != "latest" && !buildPattern.MatchString(body.Build)) {
		return nil, badRequest("Kies een versie en build.")
	}
	state := a.server.get()
	if state.Installed && compareVersions(body.Version, state.Version) < 0 && !body.Confirm {
		return nil, badRequest("Terug naar een oudere versie moet je bevestigen: een wereld van %s werkt niet altijd op %s.", state.Version, body.Version)
	}
	if status := a.process.Status(); status.State == "active" || status.State == "activating" || status.State == "deactivating" {
		return nil, badRequest("Stop de server eerst.")
	}
	if !a.lockServerJob() {
		return nil, errServerBusy
	}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start(fmt.Sprintf("Purpur %s installeren", body.Version), user, func(say func(string, ...any)) error {
		defer a.unlockServerJob()
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		if err := a.installServer(ctx, body.Version, body.Build, say); err != nil {
			a.audit.add(user, ip, "server installeren mislukt", body.Version, err.Error())
			return err
		}
		next := a.server.get()
		a.audit.add(user, ip, "server geïnstalleerd", "Purpur "+next.Version, "build "+next.Build)
		if state.Installed && next.Version != state.Version {
			say("Let op: de plugins zijn niet bijgewerkt. Dat kan bij Plugins → Alles bijwerken.")
		}
		say("Klaar. Start de server met de knop Starten.")
		return nil
	})
	return job, nil
}

func (a *App) handleServerPower(q *Request) (any, error) {
	var body struct {
		Action string `json:"action"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	state := a.server.get()
	if !state.Installed {
		return nil, badRequest("Installeer de server eerst.")
	}
	switch body.Action {
	case "start", "restart":
		if a.serverBusy() {
			return nil, errServerBusy
		}
		if !a.eulaAccepted() {
			return map[string]any{"needsEula": true, "eulaUrl": eulaURL}, nil
		}
		if !state.PluginsReady {
			return a.prepareAndStart(q)
		}
		command, err := a.serverCommand()
		if err != nil {
			return nil, badRequest("%s", capitalize(err.Error())+".")
		}
		if body.Action == "start" {
			err = a.process.Start(command)
		} else {
			err = a.process.Restart(command)
		}
		if err != nil {
			return nil, badRequest("Starten lukte niet: %s", err.Error())
		}
		q.log(map[string]string{"start": "server gestart", "restart": "server herstart"}[body.Action], "", "")
	case "stop":
		if err := a.process.Stop(); err != nil {
			return nil, badRequest("Stoppen lukte niet: %s", err.Error())
		}
		q.log("server gestopt", "", "")
	case "kill":
		if err := a.process.Kill(); err != nil {
			return nil, badRequest("Afbreken lukte niet: %s", err.Error())
		}
		q.log("server afgebroken", "", "geforceerd")
	default:
		return nil, badRequest("Onbekende actie.")
	}
	return map[string]bool{"ok": true}, nil
}

func (a *App) handleServerEULA(q *Request) (any, error) {
	var body struct {
		Accept bool `json:"accept"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if !body.Accept {
		return nil, badRequest("Zonder de EULA te accepteren start de server niet.")
	}
	if !a.server.get().Installed {
		return nil, badRequest("Installeer de server eerst.")
	}
	if a.serverBusy() {
		return nil, errServerBusy
	}
	if err := a.acceptEULA(q.user.Name); err != nil {
		return nil, fileError(err)
	}
	q.log("Minecraft EULA geaccepteerd", "", eulaURL)
	return a.prepareAndStart(q)
}

// prepareAndStart: na de EULA eerst de standaardplugins (de eerste keer), dan starten.
func (a *App) prepareAndStart(q *Request) (any, error) {
	if !a.lockServerJob() {
		return nil, errServerBusy
	}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start("Server klaarmaken en starten", user, func(say func(string, ...any)) error {
		defer a.unlockServerJob()
		state := a.server.get()
		if !state.PluginsReady {
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
			defer cancel()
			say("Standaardplugins neerzetten voor Minecraft %s…", state.Version)
			if err := a.installPlugins(ctx, allManagedKeys(), say); err != nil {
				a.audit.add(user, ip, "plugins neerzetten mislukt", "", err.Error())
				return fmt.Errorf("%w; probeer opnieuw met Starten", err)
			}
			if err := a.server.update(func(s *ServerState) { s.PluginsReady = true }); err != nil {
				return err
			}
			a.audit.add(user, ip, "standaardplugins neergezet", "", "")
			say("Plugins klaar. De rangen van PindaFramework geven ze de juiste rechten (PindaAdmin alles, PindaMod CoreProtect).")
		}
		command, err := a.serverCommand()
		if err != nil {
			return err
		}
		if a.process.Status().State == "active" {
			err = a.process.Restart(command)
		} else {
			err = a.process.Start(command)
		}
		if err != nil {
			return fmt.Errorf("starten lukte niet: %w", err)
		}
		a.audit.add(user, ip, "server gestart", "", "")
		say("De server start. De eerste keer duurt het even: Minecraft wordt klaargezet en de wereld gemaakt. Kijk mee in de console.")
		return nil
	})
	return map[string]any{"job": job}, nil
}

func (a *App) handleServerConsole(q *Request) (any, error) {
	offset, _ := strconv.ParseInt(q.r.URL.Query().Get("offset"), 10, 64)
	inode, _ := strconv.ParseUint(q.r.URL.Query().Get("inode"), 10, 64)
	chunk, err := a.readConsole(offset, inode)
	if err != nil {
		return nil, fileError(err)
	}
	return chunk, nil
}

func (a *App) handleServerCommand(q *Request) (any, error) {
	var body struct {
		Command string `json:"command"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	command := strings.TrimPrefix(strings.TrimSpace(body.Command), "/")
	if command == "" || len(command) > 1000 || strings.ContainsAny(command, "\r\n\x00") {
		return nil, badRequest("Typ een opdracht (zonder enters).")
	}
	if status := a.process.Status(); status.State != "active" {
		return nil, badRequest("De server draait niet.")
	}
	state := a.server.get()
	response, err := rconCommand(state.RconPort, state.RconPassword, command, 10*time.Second)
	q.log("console", "", clip(command, 200))
	if err != nil {
		if errors.Is(err, errRconAuth) {
			return nil, badRequest("%s", capitalize(err.Error())+".")
		}
		return nil, badRequest("De opdracht kwam niet aan: %s. Is de server al helemaal opgestart?", err.Error())
	}
	return map[string]string{"response": response}, nil
}

func (a *App) handleServerSettings(q *Request) (any, error) {
	var body struct {
		MemoryMB int `json:"memoryMB"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if body.MemoryMB < 1024 || body.MemoryMB > maxMemoryMB() {
		return nil, badRequest("Kies tussen 1024 en %d MB.", maxMemoryMB())
	}
	if err := a.server.update(func(s *ServerState) { s.MemoryMB = body.MemoryMB }); err != nil {
		return nil, err
	}
	if a.server.get().Installed {
		if command, err := a.serverCommand(); err == nil {
			if err := a.process.Install(command); err != nil {
				return nil, err
			}
		}
	}
	q.log("servergeheugen ingesteld", "", fmt.Sprintf("%d MB", body.MemoryMB))
	status := a.process.Status()
	return map[string]any{"ok": true, "restartNeeded": status.State == "active"}, nil
}

func (a *App) handleServerPlugins(q *Request) (any, error) {
	plugins, err := a.listPlugins()
	if err != nil {
		return nil, fileError(err)
	}
	state := a.server.get()
	return map[string]any{"plugins": plugins, "managed": managedPlugins, "installed": state.Plugins,
		"minecraft": state.Version, "busy": a.serverBusy(), "running": a.process.Status().State == "active"}, nil
}

func (a *App) handleServerPluginsInstall(q *Request) (any, error) {
	var body struct {
		Keys []string `json:"keys"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	var keys []string
	for _, key := range body.Keys {
		if _, ok := managedByKey(key); ok {
			keys = append(keys, key)
		}
	}
	if len(keys) == 0 {
		return nil, badRequest("Kies welke plugins.")
	}
	if !a.server.get().Installed {
		return nil, badRequest("Installeer de server eerst.")
	}
	if !a.lockServerJob() {
		return nil, errServerBusy
	}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start("Plugins bijwerken", user, func(say func(string, ...any)) error {
		defer a.unlockServerJob()
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
		defer cancel()
		err := a.installPlugins(ctx, keys, say)
		a.audit.add(user, ip, "plugins bijgewerkt", strings.Join(keys, ", "), map[bool]string{true: "", false: "met fouten"}[err == nil])
		if err != nil {
			return err
		}
		if a.process.Status().State == "active" {
			say("Klaar. Herstart de server om de nieuwe versies te gebruiken.")
		} else {
			say("Klaar.")
		}
		return nil
	})
	return job, nil
}

// pluginFile: een .jar of .jar.disabled in plugins/.
func pluginFileParam(name string) (string, error) {
	clean, err := cleanName(name)
	if err != nil {
		return "", badRequest("Ongeldige naam.")
	}
	if ok, _ := isPluginFile(clean); !ok {
		return "", badRequest("Dat is geen plugin (.jar).")
	}
	return clean, nil
}

func (a *App) handleServerPluginToggle(q *Request) (any, error) {
	var body struct {
		File string `json:"file"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	file, err := pluginFileParam(body.File)
	if err != nil {
		return nil, err
	}
	_, enabled := isPluginFile(file)
	target := file + ".disabled"
	if !enabled {
		target = file[:len(file)-len(".disabled")]
	}
	if err := a.serverRoot().Rename("plugins/"+file, "plugins/"+target); err != nil {
		return nil, fileError(err)
	}
	q.log(map[bool]string{true: "plugin uitgezet", false: "plugin aangezet"}[enabled], file, "")
	return map[string]any{"ok": true, "file": target, "restartNeeded": a.process.Status().State == "active"}, nil
}

func (a *App) handleServerPluginDelete(q *Request) (any, error) {
	var body struct {
		File string `json:"file"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	file, err := pluginFileParam(body.File)
	if err != nil {
		return nil, err
	}
	if _, err := a.serverRoot().Delete("plugins/" + file); err != nil {
		return nil, fileError(err)
	}
	q.log("plugin verwijderd", file, "")
	return map[string]any{"ok": true, "restartNeeded": a.process.Status().State == "active"}, nil
}

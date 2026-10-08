package main

import (
	"bufio"
	"context"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Service is iets dat draait (of zou moeten draaien) op de VPS.
type Service struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Unit      string `json:"unit"`
	Installed bool   `json:"installed"`
	Active    bool   `json:"active"`
	State     string `json:"state"`
	Detail    string `json:"detail,omitempty"`
}

const minecraftUnit = "pinda-minecraft"

func runQuiet(timeout time.Duration, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	out, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// unitState vraagt aan systemd hoe het met een dienst gaat: "active", "inactive", "failed", ...
// Is er geen systemd (bijv. in een testomgeving), dan proberen we "service".
func unitState(unit string) (installed bool, state string) {
	if _, err := exec.LookPath("systemctl"); err == nil {
		out, _ := runQuiet(5*time.Second, "systemctl", "show", unit, "--property=LoadState,ActiveState", "--value")
		lines := strings.Split(out, "\n")
		if len(lines) >= 2 && !strings.Contains(out, "System has not been booted") && !strings.Contains(out, "Failed to connect") {
			load := strings.TrimSpace(lines[0])
			return load == "loaded", strings.TrimSpace(lines[1])
		}
	}
	if _, err := exec.LookPath("service"); err == nil {
		if _, err := os.Stat("/etc/init.d/" + unit); err == nil {
			if _, err := runQuiet(5*time.Second, "service", unit, "status"); err == nil {
				return true, "active"
			}
			return true, "inactive"
		}
	}
	return false, "unknown"
}

func (a *App) services() []Service {
	config := a.config.get()
	list := []Service{}

	installed, state := unitState(minecraftUnit)
	game := Service{ID: "game", Name: "Minecraft-server", Unit: minecraftUnit, Installed: installed, State: state, Active: state == "active"}
	if !installed {
		game.Detail = "Nog niet geïnstalleerd"
	}
	list = append(list, game)

	db := a.mariadb.status()
	dbService := Service{ID: "database", Name: "Database (MariaDB)", Unit: "mariadb", Installed: db.Installed, Active: db.Running, Detail: db.Version}
	dbService.State = "inactive"
	if db.Running {
		dbService.State = "active"
	} else if !db.Installed {
		dbService.State = "unknown"
		dbService.Detail = "Niet geïnstalleerd"
	} else if db.Error != "" {
		dbService.Detail = db.Error
	}
	list = append(list, dbService)

	installed, state = unitState("caddy")
	web := Service{ID: "web", Name: "Webserver (Caddy)", Unit: "caddy", Installed: installed, State: state, Active: state == "active"}
	if !installed {
		web.Detail = "Niet geïnstalleerd"
		if config.Mode == "ip" {
			web.Detail = "Niet nodig zonder domein"
		}
	}
	list = append(list, web)

	list = append(list, firewallStatus())

	list = append(list, Service{ID: "panel", Name: "Dev-paneel", Unit: "pinda-host", Installed: true, Active: true, State: "active",
		Detail: "Versie " + Version + ", draait sinds " + started.Format("02-01-2006 15:04")})
	return list
}

func firewallStatus() Service {
	service := Service{ID: "firewall", Name: "Firewall (UFW)", Unit: "ufw"}
	if _, err := exec.LookPath("ufw"); err != nil {
		service.State = "unknown"
		service.Detail = "Niet geïnstalleerd"
		return service
	}
	service.Installed = true
	out, err := runQuiet(5*time.Second, "ufw", "status")
	switch {
	case err != nil:
		service.State = "unknown"
		service.Detail = out
	case strings.Contains(out, "Status: active"):
		service.State = "active"
		service.Active = true
	default:
		service.State = "inactive"
		service.Detail = "Staat uit: alle poorten zijn open"
	}
	return service
}

var started = time.Now()

// SystemInfo: hoe druk de VPS het heeft.
type SystemInfo struct {
	Hostname   string    `json:"hostname"`
	OS         string    `json:"os"`
	Uptime     int64     `json:"uptime"`
	Load       []float64 `json:"load"`
	CPUs       int       `json:"cpus"`
	MemTotal   int64     `json:"memTotal"`
	MemUsed    int64     `json:"memUsed"`
	DiskTotal  int64     `json:"diskTotal"`
	DiskUsed   int64     `json:"diskUsed"`
	Addresses  []string  `json:"addresses"`
	PanelSince int64     `json:"panelSince"`
}

func systemInfo(path string) SystemInfo {
	info := SystemInfo{PanelSince: started.UnixMilli(), CPUs: cpuCount()}
	info.Hostname, _ = os.Hostname()
	info.OS = osName()
	if data, err := os.ReadFile("/proc/uptime"); err == nil {
		if fields := strings.Fields(string(data)); len(fields) > 0 {
			if seconds, err := strconv.ParseFloat(fields[0], 64); err == nil {
				info.Uptime = int64(seconds * 1000)
			}
		}
	}
	if data, err := os.ReadFile("/proc/loadavg"); err == nil && len(strings.Fields(string(data))) >= 3 {
		for _, field := range strings.Fields(string(data))[:3] {
			value, _ := strconv.ParseFloat(field, 64)
			info.Load = append(info.Load, value)
		}
	}
	info.MemTotal, info.MemUsed = memory()
	// De schijf waar alles op staat (of de hoofdschijf, als die map er nog niet is).
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err == nil || syscall.Statfs("/", &stat) == nil {
		info.DiskTotal = int64(stat.Blocks) * int64(stat.Bsize)
		info.DiskUsed = info.DiskTotal - int64(stat.Bavail)*int64(stat.Bsize)
	}
	info.Addresses = publicAddresses()
	return info
}

func cpuCount() int {
	data, err := os.ReadFile("/proc/cpuinfo")
	if err != nil {
		return 0
	}
	return strings.Count(string(data), "\nprocessor") + boolInt(strings.HasPrefix(string(data), "processor"))
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func memory() (total, used int64) {
	file, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	defer file.Close()
	values := map[string]int64{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) >= 2 {
			value, _ := strconv.ParseInt(fields[1], 10, 64)
			values[strings.TrimSuffix(fields[0], ":")] = value * 1024
		}
	}
	total = values["MemTotal"]
	return total, total - values["MemAvailable"]
}

func osName() string {
	data, err := os.ReadFile("/etc/os-release")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "PRETTY_NAME=") {
			return strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), `"`)
		}
	}
	return ""
}

// publicAddresses: de IP-adressen van de VPS zelf (geen interne of loopback-adressen).
func publicAddresses() []string {
	list := []string{}
	interfaces, err := net.Interfaces()
	if err != nil {
		return list
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			ipnet, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			ip := ipnet.IP
			if ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsPrivate() {
				continue
			}
			list = append(list, ip.String())
		}
	}
	return list
}

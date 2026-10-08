package main

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// ServerProcess start en stopt de Minecraft-server. Normaal via systemd (dan draait de server
// los van het paneel door, ook als het paneel herstart). Zonder systemd (bijv. in een container)
// start het paneel de server zelf.
type ServerProcess interface {
	Mode() string
	Status() ProcessStatus
	// Install zet de opstartregel klaar (de systemd-dienst); geldt bij de volgende start.
	Install(command ServerCommand) error
	Start(command ServerCommand) error
	Stop() error
	Restart(command ServerCommand) error
	Kill() error
	// Recent: de laatste regels uitvoer als er (nog) geen logs/latest.log is.
	Recent() string
}

// ProcessStatus: draait de server, sinds wanneer, met hoeveel geheugen.
type ProcessStatus struct {
	Installed bool   `json:"installed"`
	State     string `json:"state"` // active, inactive, failed, activating, deactivating, unknown
	Since     int64  `json:"since,omitempty"`
	Memory    int64  `json:"memory,omitempty"`
	PID       int    `json:"pid,omitempty"`
}

func newServerProcess() ServerProcess {
	if info, err := os.Stat("/run/systemd/system"); err == nil && info.IsDir() {
		if _, err := exec.LookPath("systemctl"); err == nil {
			return &systemdProcess{unitPath: "/etc/systemd/system/" + minecraftUnit + ".service"}
		}
	}
	return &directProcess{}
}

func inodeOf(info fs.FileInfo) uint64 {
	if stat, ok := info.Sys().(*syscall.Stat_t); ok {
		return stat.Ino
	}
	return 0
}

// ============================================================ systemd

type systemdProcess struct {
	unitPath string
}

func (s *systemdProcess) Mode() string { return "systemd" }

func (s *systemdProcess) Status() ProcessStatus {
	out, _ := runQuiet(5*time.Second, "systemctl", "show", minecraftUnit,
		"-p", "LoadState", "-p", "ActiveState", "-p", "ActiveEnterTimestampMonotonic", "-p", "MemoryCurrent", "-p", "MainPID")
	values := map[string]string{}
	for _, line := range strings.Split(out, "\n") {
		if key, value, ok := strings.Cut(line, "="); ok {
			values[key] = strings.TrimSpace(value)
		}
	}
	status := ProcessStatus{Installed: values["LoadState"] == "loaded", State: values["ActiveState"]}
	if status.State == "" {
		status.State = "unknown"
	}
	if !status.Installed {
		status.State = "inactive"
	}
	if status.State == "active" {
		if monotonic, err := strconv.ParseInt(values["ActiveEnterTimestampMonotonic"], 10, 64); err == nil && monotonic > 0 {
			status.Since = time.Now().Add(-(systemUptime() - time.Duration(monotonic)*time.Microsecond)).UnixMilli()
		}
		if memory, err := strconv.ParseInt(values["MemoryCurrent"], 10, 64); err == nil {
			status.Memory = memory
		}
		status.PID, _ = strconv.Atoi(values["MainPID"])
	}
	return status
}

func systemUptime() time.Duration {
	data, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 {
		return 0
	}
	seconds, _ := strconv.ParseFloat(fields[0], 64)
	return time.Duration(seconds * float64(time.Second))
}

func (s *systemdProcess) Install(command ServerCommand) error {
	content := unitFile(command)
	if current, err := os.ReadFile(s.unitPath); err == nil && string(current) == content {
		return nil
	}
	if err := writeFileAtomic(s.unitPath, []byte(content), 0o644); err != nil {
		return fmt.Errorf("systemd-dienst schrijven: %w", err)
	}
	if out, err := runQuiet(30*time.Second, "systemctl", "daemon-reload"); err != nil {
		return fmt.Errorf("systemctl daemon-reload: %s", out)
	}
	_, _ = runQuiet(30*time.Second, "systemctl", "enable", minecraftUnit)
	return nil
}

func (s *systemdProcess) systemctl(args ...string) error {
	if out, err := runQuiet(3*time.Minute, "systemctl", args...); err != nil {
		if out == "" {
			out = err.Error()
		}
		return errors.New(out)
	}
	return nil
}

func (s *systemdProcess) Start(command ServerCommand) error {
	if err := s.Install(command); err != nil {
		return err
	}
	return s.systemctl("start", "--no-block", minecraftUnit)
}

func (s *systemdProcess) Stop() error { return s.systemctl("stop", "--no-block", minecraftUnit) }

func (s *systemdProcess) Restart(command ServerCommand) error {
	if err := s.Install(command); err != nil {
		return err
	}
	return s.systemctl("restart", "--no-block", minecraftUnit)
}

// Kill: eerst een stop klaarzetten (anders start systemd hem na een crash meteen opnieuw), dan
// het proces hard afbreken.
func (s *systemdProcess) Kill() error {
	_ = s.systemctl("stop", "--no-block", minecraftUnit)
	return s.systemctl("kill", "--signal=SIGKILL", minecraftUnit)
}

func (s *systemdProcess) Recent() string {
	out, _ := runQuiet(5*time.Second, "journalctl", "-u", minecraftUnit, "-n", "200", "--no-pager", "-o", "cat")
	if strings.HasPrefix(out, "-- No entries --") {
		return ""
	}
	return out + "\n"
}

// ============================================================ zonder systemd

type directProcess struct {
	mu       sync.Mutex
	cmd      *exec.Cmd
	state    string
	since    time.Time
	stopping bool
	output   ringBuffer
	done     chan struct{}
}

func (d *directProcess) Mode() string { return "direct" }

func (d *directProcess) Status() ProcessStatus {
	d.mu.Lock()
	defer d.mu.Unlock()
	status := ProcessStatus{Installed: true, State: d.state}
	if status.State == "" {
		status.State = "inactive"
	}
	if d.cmd != nil && d.cmd.Process != nil && (d.state == "active" || d.state == "deactivating") {
		status.Since = d.since.UnixMilli()
		status.PID = d.cmd.Process.Pid
		status.Memory = processMemory(status.PID)
	}
	return status
}

func processMemory(pid int) int64 {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				kb, _ := strconv.ParseInt(fields[1], 10, 64)
				return kb * 1024
			}
		}
	}
	return 0
}

func (d *directProcess) Install(ServerCommand) error { return nil }

func (d *directProcess) Start(command ServerCommand) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.state == "active" || d.state == "deactivating" {
		return nil
	}
	cmd := exec.Command(command.Java, command.Args...)
	cmd.Dir = command.Dir
	cmd.Stdout = &d.output
	cmd.Stderr = &d.output
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if command.UID >= 0 && os.Getuid() == 0 {
		cmd.SysProcAttr.Credential = &syscall.Credential{Uid: uint32(command.UID), Gid: uint32(command.GID)}
	}
	d.output.reset()
	if err := cmd.Start(); err != nil {
		d.state = "failed"
		return err
	}
	d.cmd, d.state, d.since, d.stopping = cmd, "active", time.Now(), false
	done := make(chan struct{})
	d.done = done
	go func() {
		err := cmd.Wait()
		d.mu.Lock()
		if d.cmd == cmd {
			if err != nil && !d.stopping {
				d.state = "failed"
			} else {
				d.state = "inactive"
			}
		}
		d.mu.Unlock()
		close(done)
	}()
	return nil
}

func (d *directProcess) Stop() error {
	d.mu.Lock()
	if d.cmd == nil || d.cmd.Process == nil || d.state != "active" {
		d.mu.Unlock()
		return nil
	}
	cmd, done := d.cmd, d.done
	d.state, d.stopping = "deactivating", true
	d.mu.Unlock()
	_ = cmd.Process.Signal(syscall.SIGTERM)
	go func() {
		select {
		case <-done:
		case <-time.After(120 * time.Second):
			_ = cmd.Process.Kill()
		}
	}()
	return nil
}

func (d *directProcess) Restart(command ServerCommand) error {
	d.mu.Lock()
	done := d.done
	running := d.state == "active" || d.state == "deactivating"
	d.mu.Unlock()
	if running {
		if err := d.Stop(); err != nil {
			return err
		}
		go func() {
			<-done
			_ = d.Start(command)
		}()
		return nil
	}
	return d.Start(command)
}

func (d *directProcess) Kill() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.cmd == nil || d.cmd.Process == nil {
		return nil
	}
	d.stopping = true
	return d.cmd.Process.Kill()
}

func (d *directProcess) Recent() string {
	return d.output.String()
}

// ringBuffer bewaart de laatste 64 kB uitvoer.
type ringBuffer struct {
	mu   sync.Mutex
	data []byte
}

func (r *ringBuffer) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.data = append(r.data, p...)
	if len(r.data) > 64<<10 {
		r.data = append([]byte(nil), r.data[len(r.data)-(64<<10):]...)
	}
	return len(p), nil
}

func (r *ringBuffer) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return string(r.data)
}

func (r *ringBuffer) reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.data = nil
}

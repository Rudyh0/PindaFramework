package main

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Config staat in config.json naast de andere gegevens van het paneel.
type Config struct {
	// "domain": achter Caddy op een domein (HTTPS door Caddy, eventueel via Cloudflare).
	// "ip": rechtstreeks op IP:poort, met een eigen HTTPS-certificaat.
	Mode string `json:"mode"`
	// Waar het paneel luistert. Bij "domain" stuurt Caddy het domein hierheen door.
	Listen string `json:"listen"`
	// Het domein van dit dev-paneel, gekozen tijdens de installatie.
	Domain string `json:"domain"`
	// Loopt het domein via Cloudflare met de proxy aan? Dan is de echte IP van bezoekers
	// te vinden in CF-Connecting-IP.
	Cloudflare bool `json:"cloudflare"`
	// De map met alles: server, website, backups.
	BaseDir string `json:"baseDir"`
	// De gebruiker waaronder de Minecraft-server draait.
	ServiceUser string `json:"serviceUser"`

	// Ingevuld in de setup.
	ServerName  string `json:"serverName"`
	SiteDomain  string `json:"siteDomain"`
	GameAddress string `json:"gameAddress"`
	GamePort    int    `json:"gamePort"`
	Database    string `json:"database"`
	SetupDone   bool   `json:"setupDone"`

	// Hoe lang een sessie geldig is.
	SessionHours int `json:"sessionHours"`
	IdleMinutes  int `json:"idleMinutes"`
}

func (c Config) serverDir() string {
	return filepath.Join(c.BaseDir, "server")
}

func (c Config) pluginDir() string {
	return filepath.Join(c.serverDir(), "plugins", "PindaFramework")
}

// ConfigStore leest en bewaart config.json.
type ConfigStore struct {
	mu     sync.RWMutex
	path   string
	config Config
}

func openConfig(path string) (*ConfigStore, error) {
	store := &ConfigStore{path: path, config: Config{
		Listen:       "127.0.0.1:8484",
		BaseDir:      "/opt/pinda",
		ServiceUser:  "minecraft",
		GamePort:     25565,
		SessionHours: 12,
		IdleMinutes:  60,
	}}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store, store.save()
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &store.config); err != nil {
		return nil, fmt.Errorf("%s is geen geldige JSON: %w", path, err)
	}
	return store, nil
}

func (s *ConfigStore) get() Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.config
}

func (s *ConfigStore) update(change func(*Config)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	change(&s.config)
	return s.saveLocked()
}

func (s *ConfigStore) save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

func (s *ConfigStore) saveLocked() error {
	return saveJSON(s.path, s.config)
}

// writeJSON schrijft eerst naar een tijdelijk bestand, zodat er nooit een half bestand staat.
func saveJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(path, append(data, '\n'), 0o600)
}

func writeFileAtomic(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	name := temp.Name()
	defer os.Remove(name)
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Chmod(mode); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}

// setupCode geeft de eenmalige code waarmee de eerste beheerder zich kan aanmelden.
// Zolang de setup niet klaar is, wordt hij gemaakt als hij er nog niet is.
func (a *App) setupCode() (string, error) {
	if a.users.count() > 0 {
		return "", nil
	}
	path := filepath.Join(a.dataDir, "setup-code")
	data, err := os.ReadFile(path)
	if err == nil && len(strings.TrimSpace(string(data))) > 0 {
		return strings.TrimSpace(string(data)), nil
	}
	code := randomCode(12)
	if err := writeFileAtomic(path, []byte(code+"\n"), 0o600); err != nil {
		return "", err
	}
	return code, nil
}

// randomCode maakt een code zonder letters die op elkaar lijken (0/O, 1/I), in groepjes van 4.
func randomCode(length int) string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	buf := make([]byte, length)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	var out strings.Builder
	for i, b := range buf {
		if i > 0 && i%4 == 0 {
			out.WriteByte('-')
		}
		out.WriteByte(alphabet[int(b)%len(alphabet)])
	}
	return out.String()
}

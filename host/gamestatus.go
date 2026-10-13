package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

// gameStatus: wat PindaFramework zelf meldt over de server (plugins/PindaFramework/.status.json,
// elke 5 seconden bijgewerkt). Alleen zo is te zien hoeveel geheugen de game zelf gebruikt: de
// JVM houdt al het toegewezen geheugen vast (-Xms = -Xmx), dus het proces lijkt altijd vol.
type gameStatus struct {
	Time          int64   `json:"time"`
	PID           int     `json:"pid"`
	HeapUsed      int64   `json:"heapUsed"`
	HeapCommitted int64   `json:"heapCommitted"`
	HeapMax       int64   `json:"heapMax"`
	TPS           float64 `json:"tps"`
	MSPT          float64 `json:"mspt"`
	Online        int     `json:"online"`
}

// gameStatusMaxAge: ouder dan dit = de server (of de plugin) loopt niet meer.
const gameStatusMaxAge = 20 * time.Second

func gameStatusPath(serverDir string) string {
	return filepath.Join(serverDir, "plugins", "PindaFramework", ".status.json")
}

// readGameStatus leest het statusbestand. pid: het proces van de server (0 = onbekend); een
// bestand van een eerder proces telt niet.
func readGameStatus(serverDir string, pid int, now time.Time) (gameStatus, bool) {
	path := gameStatusPath(serverDir)
	// Lstat: de servermap is van de minecraft-gebruiker; geen symlinks volgen (wij draaien als root).
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 4096 || now.Sub(info.ModTime()) > gameStatusMaxAge {
		return gameStatus{}, false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return gameStatus{}, false
	}
	var status gameStatus
	if err := json.Unmarshal(data, &status); err != nil {
		return gameStatus{}, false
	}
	if status.HeapMax <= 0 || status.HeapUsed < 0 || status.HeapUsed > status.HeapMax*2 {
		return gameStatus{}, false
	}
	if pid > 0 && status.PID > 0 && status.PID != pid {
		return gameStatus{}, false
	}
	if age := now.Sub(time.UnixMilli(status.Time)); age > gameStatusMaxAge || age < -time.Minute {
		return gameStatus{}, false
	}
	return status, true
}

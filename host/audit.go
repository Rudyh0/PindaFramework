package main

import (
	"bufio"
	"encoding/json"
	"log"
	"os"
	"sync"
	"time"
	"unicode/utf8"
)

// AuditEntry is één regel in het logboek: wie deed wat, wanneer en vanaf welk IP.
type AuditEntry struct {
	Time    int64  `json:"time"`
	User    string `json:"user"`
	IP      string `json:"ip,omitempty"`
	Action  string `json:"action"`
	Target  string `json:"target,omitempty"`
	Details string `json:"details,omitempty"`
}

// AuditLog schrijft naar audit.log (één JSON-regel per actie) en houdt de laatste in het geheugen.
type AuditLog struct {
	mu     sync.Mutex
	path   string
	recent []AuditEntry
}

const auditMemory = 500

func openAudit(path string) *AuditLog {
	audit := &AuditLog{path: path}
	file, err := os.Open(path)
	if err != nil {
		return audit
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		var entry AuditEntry
		if json.Unmarshal(scanner.Bytes(), &entry) == nil {
			audit.recent = append(audit.recent, entry)
			if len(audit.recent) > auditMemory*2 {
				audit.recent = audit.recent[len(audit.recent)-auditMemory:]
			}
		}
	}
	if len(audit.recent) > auditMemory {
		audit.recent = audit.recent[len(audit.recent)-auditMemory:]
	}
	return audit
}

// clip houdt regels in het logboek kort (een aanvaller kan rare, lange namen sturen).
func clip(text string, max int) string {
	if len(text) <= max {
		return text
	}
	cut := max
	for cut > 0 && !utf8.RuneStart(text[cut]) {
		cut--
	}
	return text[:cut] + "…"
}

func (a *AuditLog) add(user, ip, action, target, details string) {
	entry := AuditEntry{Time: time.Now().UnixMilli(), User: clip(user, 64), IP: clip(ip, 64), Action: clip(action, 64),
		Target: clip(target, 200), Details: clip(details, 500)}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.recent = append(a.recent, entry)
	if len(a.recent) > auditMemory {
		a.recent = a.recent[len(a.recent)-auditMemory:]
	}
	line, err := json.Marshal(entry)
	if err != nil {
		return
	}
	file, err := os.OpenFile(a.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		log.Printf("logboek niet bij te werken: %v", err)
		return
	}
	defer file.Close()
	_, _ = file.Write(append(line, '\n'))
}

// list geeft de nieuwste eerst.
func (a *AuditLog) list(limit int) []AuditEntry {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]AuditEntry, 0, limit)
	for i := len(a.recent) - 1; i >= 0 && len(out) < limit; i-- {
		out = append(out, a.recent[i])
	}
	return out
}

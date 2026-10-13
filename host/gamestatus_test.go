package main

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeGameStatus(t *testing.T, dir string, content string, modTime time.Time) {
	t.Helper()
	path := gameStatusPath(dir)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, modTime, modTime); err != nil {
		t.Fatal(err)
	}
}

func TestReadGameStatus(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	if _, ok := readGameStatus(dir, 0, now); ok {
		t.Fatal("zonder bestand: niets")
	}
	good := fmt.Sprintf(`{"time":%d,"pid":4242,"heapUsed":2147483648,"heapCommitted":6442450944,"heapMax":6442450944,"tps":19.97,"mspt":12.5,"online":3}`, now.UnixMilli())
	writeGameStatus(t, dir, good, now)
	status, ok := readGameStatus(dir, 4242, now)
	if !ok || status.HeapUsed != 2147483648 || status.HeapMax != 6442450944 || status.Online != 3 || status.TPS != 19.97 {
		t.Fatalf("goed bestand niet gelezen: %+v %v", status, ok)
	}
	if _, ok := readGameStatus(dir, 0, now); !ok {
		t.Fatal("onbekende pid moet ook werken")
	}
	if _, ok := readGameStatus(dir, 999, now); ok {
		t.Fatal("bestand van een ander proces telt niet")
	}
	// Oud bestand (server hangt of is gestopt)
	writeGameStatus(t, dir, good, now.Add(-time.Minute))
	if _, ok := readGameStatus(dir, 4242, now); ok {
		t.Fatal("oud bestand telt niet")
	}
	// Oude tijd in het bestand zelf
	old := fmt.Sprintf(`{"time":%d,"pid":4242,"heapUsed":1,"heapMax":2}`, now.Add(-time.Hour).UnixMilli())
	writeGameStatus(t, dir, old, now)
	if _, ok := readGameStatus(dir, 4242, now); ok {
		t.Fatal("oude tijd telt niet")
	}
	// Onzin
	for _, bad := range []string{"{", `{"time":1}`, fmt.Sprintf(`{"time":%d,"heapUsed":-5,"heapMax":10}`, now.UnixMilli())} {
		writeGameStatus(t, dir, bad, now)
		if _, ok := readGameStatus(dir, 0, now); ok {
			t.Fatalf("onzin gelezen: %s", bad)
		}
	}
	// Een symlink (bijv. naar een geheim bestand) volgen we niet
	secret := filepath.Join(dir, "secret.json")
	if err := os.WriteFile(secret, []byte(good), 0o644); err != nil {
		t.Fatal(err)
	}
	os.Remove(gameStatusPath(dir))
	if err := os.Symlink(secret, gameStatusPath(dir)); err != nil {
		t.Fatal(err)
	}
	if _, ok := readGameStatus(dir, 0, now); ok {
		t.Fatal("symlink gevolgd")
	}
}

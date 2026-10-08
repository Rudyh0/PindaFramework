package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func testRoot(t *testing.T) (FileRoot, string) {
	t.Helper()
	base := filepath.Join(t.TempDir(), "server")
	outside := t.TempDir()
	if err := os.MkdirAll(filepath.Join(base, "plugins"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(outside, "geheim"), []byte("root-geheim"), 0o600); err != nil {
		t.Fatal(err)
	}
	return FileRoot{Name: "server", Base: base, UID: -1, GID: -1}, outside
}

func TestCleanPath(t *testing.T) {
	good := map[string]string{"": ".", "/": ".", "a/b": "a/b", "/a//b/": "a/b", "./a/./b": "a/b"}
	for in, want := range good {
		if got, err := cleanPath(in); err != nil || got != want {
			t.Errorf("cleanPath(%q) = %q, %v; verwacht %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"..", "a/../b", "../etc", "a/\x00"} {
		if _, err := cleanPath(bad); err == nil {
			t.Errorf("cleanPath(%q) toegestaan", bad)
		}
	}
	for _, bad := range []string{"", ".", "..", "a/b", "x\x00"} {
		if _, err := cleanName(bad); err == nil {
			t.Errorf("cleanName(%q) toegestaan", bad)
		}
	}
}

// Een plugin (de minecraft-gebruiker) kan symlinks naar buiten zetten. Daar mag het paneel
// (root) nooit doorheen.
func TestFileRootSymlinkEscape(t *testing.T) {
	root, outside := testRoot(t)
	if err := os.Symlink(outside, filepath.Join(root.Base, "uit")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(outside, "geheim"), filepath.Join(root.Base, "plugins", "config.yml")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("../../", filepath.Join(root.Base, "plugins", "omhoog")); err != nil {
		t.Fatal(err)
	}
	if _, _, err := root.List("uit"); err == nil {
		t.Error("map buiten de root getoond")
	}
	if _, _, err := root.List("plugins/omhoog"); err == nil {
		t.Error("relatieve symlink naar boven gevolgd")
	}
	if file, err := root.ReadText("plugins/config.yml"); err == nil && strings.Contains(file.Content, "root-geheim") {
		t.Error("bestand buiten de root gelezen")
	}
	if _, err := root.ReadText("uit/geheim"); err == nil {
		t.Error("bestand via een gesymlinkte map gelezen")
	}
	if _, err := root.WriteFile("plugins/config.yml", strings.NewReader("overschreven"), 0); err == nil {
		t.Error("door een symlink heen geschreven")
	}
	if _, err := root.WriteFile("uit/nieuw", strings.NewReader("x"), 0); err == nil {
		t.Error("in een map buiten de root geschreven")
	}
	if err := root.Create("uit/map", true); err == nil {
		t.Error("map buiten de root gemaakt")
	}
	if err := os.WriteFile(filepath.Join(root.Base, "a.txt"), []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := root.Rename("a.txt", "uit/a.txt"); err == nil {
		t.Error("naar buiten de root verplaatst")
	}
	if _, err := root.Delete("uit"); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(filepath.Join(outside, "geheim")); string(data) != "root-geheim" {
		t.Error("bestand buiten de root aangepast")
	}
	if _, err := os.Stat(filepath.Join(outside, "nieuw")); err == nil {
		t.Error("bestand buiten de root aangemaakt")
	}
	if _, err := os.Stat(filepath.Join(outside, "geheim")); err != nil {
		t.Error("verwijderen volgde de symlink")
	}
	if _, err := os.Lstat(filepath.Join(root.Base, "uit")); !errors.Is(err, os.ErrNotExist) {
		t.Error("de symlink zelf is niet weg")
	}
	// Downloaden als zip: de symlink niet volgen.
	var zipped bytes.Buffer
	if err := root.ZipTo(".", &zipped); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(zipped.String(), "root-geheim") {
		t.Error("zip bevat een bestand van buiten de root")
	}
}

func TestFileRootSpecialFiles(t *testing.T) {
	root, outside := testRoot(t)
	// Een hardlink naar een bestand van een ander.
	if err := os.Link(filepath.Join(outside, "geheim"), filepath.Join(root.Base, "link")); err == nil {
		if _, err := root.ReadText("link"); err == nil {
			t.Error("hardlink gelezen")
		}
		if _, err := root.WriteFile("link", strings.NewReader("x"), 0); err == nil {
			t.Error("hardlink overschreven")
		}
	}
	// Een fifo mag het paneel niet laten hangen.
	if err := syscall.Mkfifo(filepath.Join(root.Base, "fifo"), 0o644); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		_, err := root.ReadText("fifo")
		done <- err
	}()
	select {
	case err := <-done:
		if err == nil {
			t.Error("fifo gelezen als bestand")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("lezen van een fifo blijft hangen")
	}
}

func TestFileRootBasics(t *testing.T) {
	root, _ := testRoot(t)
	if err := root.Create("plugins/Pinda", true); err != nil {
		t.Fatal(err)
	}
	if _, err := root.WriteFile("plugins/Pinda/config.yml", strings.NewReader("a: 1\n"), 0); err != nil {
		t.Fatal(err)
	}
	file, err := root.ReadText("plugins/Pinda/config.yml")
	if err != nil || file.Content != "a: 1\n" {
		t.Fatalf("lezen: %v %+v", err, file)
	}
	// Iemand anders (de server) past het bestand aan: opslaan met de oude tijd moet weigeren.
	time.Sleep(10 * time.Millisecond)
	if err := os.WriteFile(filepath.Join(root.Base, "plugins/Pinda/config.yml"), []byte("a: 2\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := root.WriteFile("plugins/Pinda/config.yml", strings.NewReader("a: 3\n"), file.Modified); !errors.Is(err, errChangedOnDisk) {
		t.Errorf("geen conflict bij een tussentijdse wijziging: %v", err)
	}
	if err := os.Symlink("config.yml", filepath.Join(root.Base, "plugins/Pinda/link.yml")); err != nil {
		t.Fatal(err)
	}
	if _, err := root.WriteFile("plugins/Pinda/link.yml", strings.NewReader("x"), 0); !errors.Is(err, errLinkWrite) {
		t.Errorf("schrijven naar een symlink: %v", err)
	}
	entries, _, err := root.List("plugins/Pinda")
	if err != nil || len(entries) != 2 || entries[0].Name != "config.yml" || entries[1].Type != "link" {
		t.Fatalf("lijst: %v %+v", err, entries)
	}
	if err := root.Rename("plugins/Pinda", "plugins/Pinda/sub"); err == nil {
		t.Error("map in zichzelf verplaatst")
	}
	if err := root.Rename("plugins/Pinda/config.yml", "config.yml"); err != nil {
		t.Fatal(err)
	}
	if err := root.Rename("config.yml", "plugins"); !errors.Is(err, errExists) {
		t.Errorf("over een bestaande map heen verplaatst: %v", err)
	}
	if _, err := root.Delete("."); err == nil {
		t.Error("de hoofdmap verwijderd")
	}
	if count, err := root.Delete("plugins"); err != nil || count < 3 {
		t.Errorf("verwijderen: %d %v", count, err)
	}
	if _, err := os.Stat(filepath.Join(root.Base, "config.yml")); err != nil {
		t.Error("verkeerd bestand verwijderd")
	}
	// Binaire bestanden niet in de editor.
	if err := os.WriteFile(filepath.Join(root.Base, "level.dat"), []byte{0x1f, 0x8b, 0, 1, 2}, 0o644); err != nil {
		t.Fatal(err)
	}
	if file, err := root.ReadText("level.dat"); err != nil || !file.Binary {
		t.Errorf("binair bestand niet herkend: %v %+v", err, file)
	}
}

func TestExtractIsSafe(t *testing.T) {
	root, outside := testRoot(t)
	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	add := func(name, content string, mode os.FileMode) {
		header := &zip.FileHeader{Name: name, Method: zip.Deflate}
		header.SetMode(mode)
		w, err := writer.CreateHeader(header)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = w.Write([]byte(content))
	}
	add("../ontsnapt.txt", "x", 0o644)
	add("/absoluut.txt", "x", 0o644)
	add("map/../../ook-ontsnapt.txt", "x", 0o644)
	add("wereld/level.dat", "wereld", 0o644)
	add(`windows\pad.txt`, "w", 0o644)
	add("start.sh", "#!/bin/sh", 0o755)
	add("link", outside, os.ModeSymlink|0o777)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root.Base, "upload.zip"), buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	result, err := root.Extract("upload.zip", "uitgepakt", func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	if result.Files != 4 || result.Skipped != 3 {
		t.Errorf("uitgepakt: %+v", result)
	}
	for _, escaped := range []string{filepath.Join(filepath.Dir(root.Base), "ontsnapt.txt"), "/absoluut.txt", filepath.Join(root.Base, "ontsnapt.txt"), filepath.Join(filepath.Dir(root.Base), "ook-ontsnapt.txt")} {
		if _, err := os.Stat(escaped); err == nil {
			t.Errorf("buiten de map uitgepakt: %s", escaped)
		}
	}
	if data, _ := os.ReadFile(filepath.Join(root.Base, "uitgepakt/wereld/level.dat")); string(data) != "wereld" {
		t.Error("gewoon bestand niet uitgepakt")
	}
	if _, err := os.Stat(filepath.Join(root.Base, "uitgepakt/windows/pad.txt")); err != nil {
		t.Error("Windows-pad niet goed omgezet")
	}
	if info, err := os.Stat(filepath.Join(root.Base, "uitgepakt/start.sh")); err != nil || info.Mode().Perm() != 0o755 {
		t.Error("uitvoerbaar bestand niet uitvoerbaar")
	}
	if _, err := os.Lstat(filepath.Join(root.Base, "uitgepakt/link")); err == nil {
		t.Error("symlink uit een archief aangemaakt")
	}

	// tar.gz met een symlink naar buiten en daarna een bestand "via" die symlink.
	var tgz bytes.Buffer
	gz := gzip.NewWriter(&tgz)
	tw := tar.NewWriter(gz)
	_ = tw.WriteHeader(&tar.Header{Name: "evil", Typeflag: tar.TypeSymlink, Linkname: outside})
	_ = tw.WriteHeader(&tar.Header{Name: "evil/via-link.txt", Typeflag: tar.TypeReg, Mode: 0o644, Size: 1})
	_, _ = tw.Write([]byte("x"))
	_ = tw.WriteHeader(&tar.Header{Name: "plugins/ok.yml", Typeflag: tar.TypeReg, Mode: 0o644, Size: 2})
	_, _ = tw.Write([]byte("ok"))
	_ = tw.Close()
	_ = gz.Close()
	if err := os.WriteFile(filepath.Join(root.Base, "backup.tar.gz"), tgz.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := root.Extract("backup.tar.gz", ".", func(string, ...any) {}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(outside, "via-link.txt")); err == nil {
		t.Error("via een symlink uit het archief buiten de map geschreven")
	}
	if data, _ := os.ReadFile(filepath.Join(root.Base, "plugins/ok.yml")); string(data) != "ok" {
		t.Error("tar.gz niet goed uitgepakt")
	}

	// Inpakken en weer uitpakken.
	if count, err := root.Archive("uitgepakt", []string{"wereld", "start.sh"}, "backup.zip"); err != nil || count != 2 {
		t.Fatalf("inpakken: %d %v", count, err)
	}
	if _, err := root.Archive("uitgepakt", []string{"wereld"}, "backup.zip"); !errors.Is(err, errExists) {
		t.Errorf("bestaand archief overschreven: %v", err)
	}
	if _, err := root.Extract("uitgepakt/backup.zip", "terug", func(string, ...any) {}); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(filepath.Join(root.Base, "terug/wereld/level.dat")); string(data) != "wereld" {
		t.Error("ingepakt archief klopt niet")
	}
}

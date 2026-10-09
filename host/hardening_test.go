package main

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Een fifo waar een map verwacht wordt mag het paneel niet laten hangen.
func TestFifoInsteadOfDirectory(t *testing.T) {
	app, _ := testApp(t)
	root := app.serverRoot()
	if err := os.MkdirAll(root.Base, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := syscall.Mkfifo(filepath.Join(root.Base, "plugins"), 0o644); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = app.listPlugins()
		_, _, _ = root.List("plugins")
		_, _ = root.WriteFile("plugins/x.yml", strings.NewReader("x"), 0)
		_ = root.Create("plugins/map", true)
		_, _ = root.Delete("plugins")
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("het paneel blijft hangen op een fifo")
	}
}

func TestZipDirectoryGuard(t *testing.T) {
	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	for i := 0; i < 3; i++ {
		w, _ := writer.Create(strings.Repeat("a", i+1))
		w.Write([]byte("x"))
	}
	writer.Close()
	entries, directory, err := zipDirectory(bytes.NewReader(buf.Bytes()), int64(buf.Len()), 10)
	if err != nil || entries != 3 || directory == 0 {
		t.Fatalf("zip: %d %d %v", entries, directory, err)
	}
	if _, _, err := zipDirectory(bytes.NewReader([]byte("geen zip")), 8, 10); err == nil {
		t.Error("geen zip geaccepteerd")
	}
	if _, _, err := zipDirectory(bytes.NewReader(buf.Bytes()), int64(buf.Len()), 2); err == nil {
		t.Error("meer onderdelen dan toegestaan geaccepteerd")
	}
	// Het eindrecord liegt (1 onderdeel, kleine inhoudsopgave) terwijl er veel meer staan:
	// zip.NewReader zou ze toch allemaal laden.
	var many bytes.Buffer
	manyWriter := zip.NewWriter(&many)
	for i := 0; i < 500; i++ {
		manyWriter.CreateHeader(&zip.FileHeader{Name: fmt.Sprintf("f%d", i), Method: zip.Store})
	}
	manyWriter.Close()
	lying := append([]byte{}, many.Bytes()...)
	eocd := bytes.LastIndex(lying, []byte{0x50, 0x4b, 0x05, 0x06})
	binary.LittleEndian.PutUint16(lying[eocd+8:], 1)
	binary.LittleEndian.PutUint16(lying[eocd+10:], 1)
	realSize := binary.LittleEndian.Uint32(lying[eocd+12:])
	realOffset := binary.LittleEndian.Uint32(lying[eocd+16:])
	binary.LittleEndian.PutUint32(lying[eocd+12:], 100)
	binary.LittleEndian.PutUint32(lying[eocd+16:], realOffset+realSize-100)
	if _, err := openZip(bytes.NewReader(lying), int64(len(lying)), 10); err == nil {
		t.Error("liegend eindrecord geaccepteerd")
	}
	if reader, err := openZip(bytes.NewReader(many.Bytes()), int64(many.Len()), 1000); err != nil || len(reader.File) != 500 {
		t.Errorf("gewone zip met 500 onderdelen: %v", err)
	}
	// Zip64 (meer dan 65535 onderdelen, zoals een grote backup).
	var big bytes.Buffer
	bigWriter := zip.NewWriter(&big)
	for i := 0; i < 70_000; i++ {
		bigWriter.CreateHeader(&zip.FileHeader{Name: fmt.Sprintf("d/%d", i), Method: zip.Store})
	}
	bigWriter.Close()
	if reader, err := openZip(bytes.NewReader(big.Bytes()), int64(big.Len()), 100_000); err != nil || len(reader.File) != 70_000 {
		t.Errorf("zip64: %v", err)
	}
	if _, err := openZip(bytes.NewReader(big.Bytes()), int64(big.Len()), 60_000); err == nil {
		t.Error("zip64 met te veel onderdelen geaccepteerd")
	}
	// Een jar die alleen uit een gigantische inhoudsopgave bestaat: weigeren zonder te laden.
	app, _ := testApp(t)
	root := app.serverRoot()
	if err := os.MkdirAll(filepath.Join(root.Base, "plugins"), 0o755); err != nil {
		t.Fatal(err)
	}
	fake := append([]byte{}, buf.Bytes()...)
	// Het aantal onderdelen in het eindrecord opblazen.
	at := bytes.LastIndex(fake, []byte{0x50, 0x4b, 0x05, 0x06})
	fake[at+10], fake[at+11] = 0xfe, 0xff
	fake[at+12], fake[at+13], fake[at+14], fake[at+15] = 0, 0, 0, 0x7f
	os.WriteFile(filepath.Join(root.Base, "plugins", "bom.jar"), fake, 0o644)
	plugins, err := app.listPlugins()
	if err != nil || len(plugins) != 1 || plugins[0].Error == "" {
		t.Errorf("rare jar niet geweigerd: %+v %v", plugins, err)
	}
}

// Een nep-RCON-server die blijft sturen: het paneel stopt na 1 MB of de tijd.
func TestRconFlood(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		rconRead(conn)
		rconWrite(conn, 1, rconAuthResponse, "")
		id, _, _, _ := rconRead(conn)
		for {
			if rconWrite(conn, id, rconResponseValue, strings.Repeat("x", rconMaxBody)) != nil {
				return
			}
		}
	}()
	start := time.Now()
	response, err := rconCommand(listener.Addr().(*net.TCPAddr).Port, "pw", "list", 2*time.Second)
	if err != nil || len(response) > 1<<20+rconMaxBody || time.Since(start) > 4*time.Second {
		t.Errorf("rcon-vloed: %d bytes in %s (%v)", len(response), time.Since(start), err)
	}
}

func TestSmallFixes(t *testing.T) {
	if got := escapeProperty("Pinda \U0001F600"); got != "Pinda "+`\`+"ud83d"+`\`+"ude00" {
		t.Errorf("emoji: %q", got)
	}
	if value, _ := propertyValue("motd="+escapeProperty("Pinda \U0001F600"), "motd"); value == "" {
		t.Error("emoji niet terug te lezen")
	}
	for address, ok := range map[string]bool{
		"https://cdn.modrinth.com/x.jar": true, "http://127.0.0.1:8080/x": true,
		"http://127.0.0.1.evil.com/x": false, "http://127.0.0.1@evil.com/x": false, "http://cdn.modrinth.com/x": false, "file:///etc/passwd": false,
	} {
		parsed, _ := url.Parse(address)
		if allowedURL(parsed) != ok {
			t.Errorf("allowedURL(%s) = %v", address, !ok)
		}
	}
	// Een heel lange regel zonder enter: de console moet toch verder.
	app, _ := testApp(t)
	logPath := filepath.Join(app.serverRoot().Base, "logs", "latest.log")
	os.MkdirAll(filepath.Dir(logPath), 0o755)
	os.WriteFile(logPath, bytes.Repeat([]byte("x"), 700<<10), 0o644)
	chunk, _ := app.readConsole(0, 0)
	// Vanaf het begin lezen (zelfde bestand): 512 kB zonder enter moet toch doorgegeven worden.
	next, _ := app.readConsole(0, chunk.Inode)
	if next.Reset || next.Offset != 512<<10 {
		t.Errorf("console blijft hangen op een lange regel: %+v", next.Offset)
	}
	// Nooit als root.
	allowRootServer = false
	_ = app.server.update(func(s *ServerState) { s.Installed, s.Version, s.Java = true, "26.2", "/usr/bin/java" })
	if _, err := app.serverCommand(); err == nil {
		t.Error("server zou als root starten")
	}
	allowRootServer = true
}

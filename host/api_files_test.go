package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// loggedIn maakt een gebruiker met 2FA en logt die in.
func loggedIn(t *testing.T, app *App, server *httptest.Server, name string, isAdmin bool) *client {
	t.Helper()
	secret := newTOTPSecret()
	hash, err := hashPassword("een lang wachtwoord")
	if err != nil {
		t.Fatal(err)
	}
	if err := app.users.add(User{Name: name, PasswordHash: hash, TOTPSecret: secret, Admin: isAdmin}, false); err != nil {
		t.Fatal(err)
	}
	c := newClient(t, server)
	if status, body := c.post("/api/login", map[string]string{"username": name, "password": "een lang wachtwoord"}); status != 200 || body["step"] != "code" {
		t.Fatalf("inloggen %s: %d %v", name, status, body)
	}
	if status, body := c.post("/api/login/verify", map[string]string{"code": currentCode(t, secret)}); status != 200 || body["step"] != "done" {
		t.Fatalf("2FA %s: %d %v", name, status, body)
	}
	return c
}

func (c *client) raw(method, path string, body []byte) (int, string) {
	req, _ := http.NewRequest(method, c.server.URL+path, bytes.NewReader(body))
	req.Header.Set("X-Pinda-Host", "1")
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(data)
}

func TestFilesAPI(t *testing.T) {
	app, _ := testApp(t)
	server := httptest.NewServer(app.routes())
	defer server.Close()
	rudy := loggedIn(t, app, server, "rudy", true)
	lisa := loggedIn(t, app, server, "lisa", false)
	base := app.config.get().serverDir()

	// Upload in twee stukken.
	status, start := rudy.post("/api/files/server/upload", map[string]any{"dir": ".", "name": "wereld.zip", "size": 10})
	if status != 200 || start["id"] == nil {
		t.Fatalf("upload starten: %d %v", status, start)
	}
	id := start["id"].(string)
	if status, _ := rudy.raw("POST", "/api/files/server/upload/"+id+"?offset=5", []byte("12345")); status != 409 {
		t.Errorf("verkeerde offset geaccepteerd: %d", status)
	}
	if status, _ := lisa.raw("POST", "/api/files/server/upload/"+id+"?offset=0", []byte("12345")); status != 404 {
		t.Errorf("iemand anders kan in deze upload schrijven: %d", status)
	}
	if status, _ := rudy.raw("POST", "/api/files/website/upload/"+id+"?offset=0", []byte("12345")); status != 404 {
		t.Errorf("upload in een andere map voortgezet: %d", status)
	}
	if status, body := rudy.raw("POST", "/api/files/server/upload/"+id+"?offset=0", []byte("12345")); status != 200 {
		t.Fatalf("eerste stuk: %d %s", status, body)
	}
	if status, _ := rudy.post("/api/files/server/upload/"+id+"/finish", nil); status != 400 {
		t.Error("afmaken terwijl het bestand nog niet binnen is")
	}
	if status, _ := rudy.raw("POST", "/api/files/server/upload/"+id+"?offset=5", []byte("6789012345")); status != 400 {
		t.Error("meer gestuurd dan aangekondigd")
	}
	if status, body := rudy.raw("POST", "/api/files/server/upload/"+id+"?offset=5", []byte("67890")); status != 409 && status != 200 {
		t.Fatalf("tweede stuk: %d %s", status, body)
	}
	// Na de te grote poging is er misschien al iets geschreven: opnieuw vanaf het goede punt.
	if data, _ := os.ReadFile(filepath.Join(base, "wereld.zip")); data != nil {
		t.Error("bestand al zichtbaar voor de upload klaar is")
	}
	rudy.post("/api/files/server/upload/"+id+"/finish", nil)

	// Opnieuw, netjes.
	_, start = rudy.post("/api/files/server/upload", map[string]any{"dir": ".", "name": "kaart.txt", "size": 10})
	id = start["id"].(string)
	rudy.raw("POST", "/api/files/server/upload/"+id+"?offset=0", []byte("12345"))
	rudy.raw("POST", "/api/files/server/upload/"+id+"?offset=5", []byte("67890"))
	if status, body := rudy.post("/api/files/server/upload/"+id+"/finish", nil); status != 200 {
		t.Fatalf("afmaken: %d %v", status, body)
	}
	if data, _ := os.ReadFile(filepath.Join(base, "kaart.txt")); string(data) != "1234567890" {
		t.Errorf("geüpload bestand: %q", data)
	}
	// Bestaat al: eerst vragen.
	if _, body := rudy.post("/api/files/server/upload", map[string]any{"dir": ".", "name": "kaart.txt", "size": 1}); body["exists"] != true {
		t.Errorf("overschrijven zonder te vragen: %v", body)
	}
	for _, bad := range []string{"../x", ".pinda-upload-x", "", "a/b"} {
		if status, _ := rudy.post("/api/files/server/upload", map[string]any{"dir": ".", "name": bad, "size": 1}); status != 400 {
			t.Errorf("naam %q toegestaan: %d", bad, status)
		}
	}
	entries, _ := os.ReadDir(base)
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".pinda-") && !strings.Contains(entry.Name(), "upload") {
			t.Errorf("tijdelijk bestand blijft staan: %s", entry.Name())
		}
	}

	// Editor: lezen, opslaan, conflict.
	status, file := lisa.get("/api/files/server/read?path=kaart.txt")
	if status != 200 || file["content"] != "1234567890" {
		t.Fatalf("lezen: %d %v", status, file)
	}
	if status, _ := lisa.post("/api/files/server/write", map[string]any{"path": "kaart.txt", "content": "nieuw", "modified": 1}); status != 409 {
		t.Errorf("geen conflict bij een oude wijzigingstijd: %d", status)
	}
	if status, body := lisa.post("/api/files/server/write", map[string]any{"path": "kaart.txt", "content": "nieuw", "modified": file["modified"]}); status != 200 {
		t.Errorf("opslaan: %d %v", status, body)
	}
	// Downloaden met de juiste naam.
	resp, err := lisa.http.Get(server.URL + "/api/files/server/download?path=kaart.txt")
	if err != nil {
		t.Fatal(err)
	}
	data, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(data) != "nieuw" || !strings.Contains(resp.Header.Get("Content-Disposition"), "kaart.txt") {
		t.Errorf("download: %q %s", data, resp.Header.Get("Content-Disposition"))
	}
	// Zonder inloggen niets.
	if status, _ := newClient(t, server).get("/api/files/server/list"); status != 401 {
		t.Errorf("bestanden zonder inloggen: %d", status)
	}
	if status, _ := rudy.get("/api/files/etc/list"); status != 404 {
		t.Errorf("onbekende root: %d", status)
	}
	// Alleen een beheerder installeert de server.
	if status, _ := lisa.post("/api/server/install", map[string]string{"version": "26.2"}); status != 403 {
		t.Errorf("developer mag de server installeren: %d", status)
	}
}

// Eén servertaak tegelijk: een tweede moet wachten (409), daarna mag het weer.
func TestServerJobLock(t *testing.T) {
	app, _ := testApp(t)
	if app.serverBusy() || !app.lockServerJob() || !app.serverBusy() {
		t.Fatal("eerste taak kreeg geen slot")
	}
	if app.lockServerJob() {
		t.Error("twee taken tegelijk")
	}
	app.unlockServerJob()
	if app.serverBusy() || !app.lockServerJob() {
		t.Error("slot niet vrijgegeven")
	}
	app.unlockServerJob()
}

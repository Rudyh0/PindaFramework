package main

import (
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// vanillaRcon doet na wat Minecraft doet: per verbinding een "thread", een onbekend verzoek
// krijgt "Unknown request", en lange antwoorden komen in stukken van 4096 tekens. Met ignoreUnknown
// doet hij alsof hij onbekende verzoeken negeert (zoals sommige andere servers).
type vanillaRcon struct {
	port          int
	connections   atomic.Int32
	commands      atomic.Int32
	ignoreUnknown bool
	mu            sync.Mutex
	open          []net.Conn
}

func newVanillaRcon(t *testing.T, password string, ignoreUnknown bool) *vanillaRcon {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	server := &vanillaRcon{port: listener.Addr().(*net.TCPAddr).Port, ignoreUnknown: ignoreUnknown}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			server.connections.Add(1)
			server.mu.Lock()
			server.open = append(server.open, conn)
			server.mu.Unlock()
			go func(conn net.Conn) {
				defer conn.Close()
				authed := false
				for {
					id, kind, body, err := rconRead(conn)
					if err != nil {
						return
					}
					switch kind {
					case rconAuth:
						authed = body == password
						if !authed {
							id = -1
						}
						rconWrite(conn, id, rconAuthResponse, "")
					case rconExec:
						if !authed {
							return
						}
						server.commands.Add(1)
						answer := "Je typte: " + body
						if body == "help" {
							answer = strings.Repeat("regel van de help\n", 700) // ruim 12.000 tekens
						}
						for len(answer) > rconMaxBody {
							rconWrite(conn, id, rconResponseValue, answer[:rconMaxBody])
							answer = answer[rconMaxBody:]
						}
						rconWrite(conn, id, rconResponseValue, answer)
					default:
						if !server.ignoreUnknown {
							rconWrite(conn, id, rconResponseValue, "Unknown request 64")
						}
					}
				}
			}(conn)
		}
	}()
	return server
}

// closeAll: alsof de server herstart (alle verbindingen dicht).
func (v *vanillaRcon) closeAll() {
	v.mu.Lock()
	defer v.mu.Unlock()
	for _, conn := range v.open {
		conn.Close()
	}
	v.open = nil
}

func TestRconKeepsConnection(t *testing.T) {
	server := newVanillaRcon(t, "pw", false)
	for i := range 5 {
		response, err := rconCommand(server.port, "pw", "say "+string(rune('a'+i)), 3*time.Second)
		if err != nil || response != "Je typte: say "+string(rune('a'+i)) {
			t.Fatalf("opdracht %d: %q %v", i, response, err)
		}
	}
	if n := server.connections.Load(); n != 1 {
		t.Errorf("%d verbindingen voor 5 opdrachten (Minecraft logt er elke keer twee regels van)", n)
	}
	// Een lang antwoord in stukken, heel en zonder te wachten.
	start := time.Now()
	response, err := rconCommand(server.port, "pw", "help", 3*time.Second)
	if err != nil || len(response) != 700*len("regel van de help\n") {
		t.Fatalf("lang antwoord: %d tekens, %v", len(response), err)
	}
	if time.Since(start) > time.Second {
		t.Errorf("lang antwoord duurde %v", time.Since(start))
	}
	// Daarna gaat het gewoon verder op dezelfde verbinding.
	if response, err := rconCommand(server.port, "pw", "list", 3*time.Second); err != nil || response != "Je typte: list" {
		t.Errorf("na het lange antwoord: %q %v", response, err)
	}
	// De server herstart: het paneel maakt een nieuwe verbinding en voert de opdracht één keer uit.
	before := server.commands.Load()
	server.closeAll()
	time.Sleep(50 * time.Millisecond)
	if response, err := rconCommand(server.port, "pw", "say weer", 3*time.Second); err != nil || response != "Je typte: say weer" {
		t.Fatalf("na herstart: %q %v", response, err)
	}
	if n := server.commands.Load() - before; n != 1 {
		t.Errorf("opdracht %d keer uitgevoerd", n)
	}
	if n := server.connections.Load(); n != 2 {
		t.Errorf("%d verbindingen na de herstart", n)
	}
	// Een ander wachtwoord of een andere poort: een nieuwe verbinding.
	if _, err := rconCommand(server.port, "fout", "list", 3*time.Second); err != errRconAuth {
		t.Errorf("verkeerd wachtwoord: %v", err)
	}
	if response, err := rconCommand(server.port, "pw", "list", 3*time.Second); err != nil || response != "Je typte: list" {
		t.Errorf("weer het goede wachtwoord: %q %v", response, err)
	}
	if _, err := rconCommand(server.port, "pw", strings.Repeat("x", 2000), 3*time.Second); err == nil {
		t.Error("te lange opdracht verstuurd")
	}
}

// Is de vaste verbinding bezig (een lange save-all), dan gaat een opdracht via een losse.
func TestRconBusyUsesSecondConnection(t *testing.T) {
	server := newVanillaRcon(t, "pw", false)
	rconCommand(server.port, "pw", "list", 3*time.Second)
	rconShared.mu.Lock()
	response, err := rconCommand(server.port, "pw", "say tussendoor", 3*time.Second)
	rconShared.mu.Unlock()
	if err != nil || response != "Je typte: say tussendoor" {
		t.Errorf("tijdens een lange opdracht: %q %v", response, err)
	}
}

// Een server die onbekende verzoeken negeert: het antwoord komt toch (na een korte wachttijd).
func TestRconWithoutEndMarker(t *testing.T) {
	server := newVanillaRcon(t, "pw", true)
	response, err := rconCommand(server.port, "pw", "help", 5*time.Second)
	if err != nil || len(response) != 700*len("regel van de help\n") {
		t.Fatalf("zonder eindmarkering: %d tekens, %v", len(response), err)
	}
	if response, err := rconCommand(server.port, "pw", "list", 5*time.Second); err != nil || response != "Je typte: list" {
		t.Errorf("daarna: %q %v", response, err)
	}
}

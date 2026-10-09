package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
)

// RCON is hoe het paneel opdrachten aan de draaiende server geeft (het protocol van Source,
// dat Minecraft ook gebruikt). Alleen naar 127.0.0.1; de poort zit dicht in de firewall.
//
// Het paneel houdt één verbinding open en gebruikt die steeds opnieuw. Minecraft schrijft bij
// elke nieuwe RCON-verbinding "Thread RCON Client started" en bij het sluiten "shutting down"
// in het log; met een vaste verbinding gebeurt dat maar één keer per serverstart.

const (
	rconAuth          = 3
	rconAuthResponse  = 2
	rconExec          = 2
	rconResponseValue = 0
	rconMaxBody       = 4096
	// rconEnd: een soort verzoek dat Minecraft niet kent. Het antwoord ("Unknown request")
	// komt pas nadat alle stukken van het vorige antwoord verstuurd zijn: zo weten we waar
	// een lang antwoord ophoudt.
	rconEnd = 100
	// Minecraft leest hooguit 1460 bytes per keer; een langere opdracht komt niet heel aan.
	rconMaxCommand = 1400
)

var errRconAuth = errors.New("RCON-wachtwoord klopt niet (staat het nog goed in server.properties?)")

func rconWrite(conn net.Conn, id, kind int32, body string) error {
	var packet bytes.Buffer
	length := int32(4 + 4 + len(body) + 2)
	_ = binary.Write(&packet, binary.LittleEndian, length)
	_ = binary.Write(&packet, binary.LittleEndian, id)
	_ = binary.Write(&packet, binary.LittleEndian, kind)
	packet.WriteString(body)
	packet.Write([]byte{0, 0})
	_, err := conn.Write(packet.Bytes())
	return err
}

func rconRead(conn net.Conn) (int32, int32, string, error) {
	var length int32
	if err := binary.Read(conn, binary.LittleEndian, &length); err != nil {
		return 0, 0, "", err
	}
	if length < 10 || length > 64<<10 {
		return 0, 0, "", errors.New("ongeldig RCON-antwoord")
	}
	data := make([]byte, length)
	if _, err := io.ReadFull(conn, data); err != nil {
		return 0, 0, "", err
	}
	id := int32(binary.LittleEndian.Uint32(data[0:4]))
	kind := int32(binary.LittleEndian.Uint32(data[4:8]))
	body := strings.TrimRight(string(data[8:]), "\x00")
	return id, kind, body, nil
}

// rconConn is één (ingelogde) RCON-verbinding.
type rconConn struct {
	mu   sync.Mutex
	conn net.Conn
	key  string // poort en wachtwoord: verandert dat, dan een nieuwe verbinding
	id   int32
}

// De vaste verbinding van het paneel.
var rconShared rconConn

// rconCommand voert één opdracht uit en geeft het antwoord van de server.
func rconCommand(port int, password, command string, timeout time.Duration) (string, error) {
	if password == "" {
		return "", errors.New("RCON staat niet aan")
	}
	if len(command) > rconMaxCommand {
		return "", errors.New("die opdracht is te lang voor RCON")
	}
	key := fmt.Sprintf("%d\x00%s", port, password)
	if rconShared.mu.TryLock() {
		defer rconShared.mu.Unlock()
		return rconShared.run(key, port, password, command, timeout)
	}
	// De vaste verbinding is bezig (bijvoorbeeld een lange save-all voor een backup): even een
	// losse verbinding, zodat de console niet hoeft te wachten.
	var single rconConn
	defer single.close()
	return single.run(key, port, password, command, timeout)
}

func (c *rconConn) close() {
	if c.conn != nil {
		c.conn.Close()
		c.conn = nil
	}
}

func (c *rconConn) nextID() int32 {
	c.id++
	if c.id <= 1 || c.id > 1<<30 {
		c.id = 2
	}
	return c.id
}

// closedByServer: de verbinding was al dicht (de server is herstart of gestopt).
func closedByServer(err error) bool {
	return errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, syscall.ECONNRESET) || errors.Is(err, syscall.EPIPE)
}

func (c *rconConn) run(key string, port int, password, command string, timeout time.Duration) (string, error) {
	deadline := time.Now().Add(timeout)
	for attempt := 0; ; attempt++ {
		reused := c.conn != nil && c.key == key
		if !reused {
			c.close()
			if err := c.connect(key, port, password, deadline); err != nil {
				return "", err
			}
		}
		response, answered, err := c.exec(command, deadline)
		if err == nil {
			return response, nil
		}
		c.close()
		// Had de server een oude verbinding intussen gesloten, dan is de opdracht nooit
		// aangekomen: één keer opnieuw met een nieuwe verbinding.
		if reused && !answered && attempt == 0 && closedByServer(err) {
			continue
		}
		return "", err
	}
}

func (c *rconConn) connect(key string, port int, password string, deadline time.Time) error {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Until(deadline))
	if err != nil {
		return errors.New("de server is (nog) niet bereikbaar via RCON")
	}
	_ = conn.SetDeadline(deadline)
	if err := rconWrite(conn, 1, rconAuth, password); err != nil {
		conn.Close()
		return err
	}
	for {
		id, kind, _, err := rconRead(conn)
		if err != nil {
			conn.Close()
			return err
		}
		if kind == rconAuthResponse {
			if id == -1 {
				conn.Close()
				return errRconAuth
			}
			break
		}
	}
	c.conn, c.key = conn, key
	return nil
}

// exec stuurt de opdracht en leest het hele antwoord (lange antwoorden komen in stukken van
// 4096 tekens). answered: er kwam iets terug.
func (c *rconConn) exec(command string, deadline time.Time) (string, bool, error) {
	_ = c.conn.SetDeadline(deadline)
	id, end := c.nextID(), c.nextID()
	if err := rconWrite(c.conn, id, rconExec, command); err != nil {
		return "", false, err
	}
	var out strings.Builder
	answered, marked := false, false
	for {
		if marked {
			// De rest van een antwoord komt meteen; blijft de eindmarkering uit (een server die
			// onbekende verzoeken negeert), dan is dit het hele antwoord.
			wait := time.Now().Add(2 * time.Second)
			if wait.After(deadline) {
				wait = deadline
			}
			_ = c.conn.SetReadDeadline(wait)
		}
		got, _, body, err := rconRead(c.conn)
		var timeout net.Error
		if err != nil && marked && errors.As(err, &timeout) && timeout.Timeout() {
			// Daarna niet meer op deze verbinding vertrouwen: de markering kan nog komen.
			c.close()
			return stripFormatting(out.String()), true, nil
		}
		if err != nil {
			return "", answered, err
		}
		answered = true
		if got == end {
			break
		}
		if got != id {
			continue // iets van eerder
		}
		// Nooit meer dan 1 MB bewaren (een plugin kan zich als RCON-server voordoen).
		if out.Len() < 1<<20 {
			out.WriteString(body)
		}
		if !marked {
			// Pas na het eerste stuk: Minecraft leest wat er binnen is in één keer en zou de
			// eindmarkering missen als die tegelijk met de opdracht aankwam.
			if err := rconWrite(c.conn, end, rconEnd, ""); err != nil {
				return "", answered, err
			}
			marked = true
		}
	}
	return stripFormatting(out.String()), true, nil
}

// stripFormatting haalt de §-kleurcodes van Minecraft weg.
func stripFormatting(text string) string {
	if !strings.ContainsRune(text, '§') {
		return text
	}
	var out strings.Builder
	runes := []rune(text)
	for i := 0; i < len(runes); i++ {
		if runes[i] == '§' && i+1 < len(runes) {
			i++
			continue
		}
		out.WriteRune(runes[i])
	}
	return out.String()
}

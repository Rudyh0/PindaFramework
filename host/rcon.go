package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

// RCON is hoe het paneel opdrachten aan de draaiende server geeft (het protocol van Source,
// dat Minecraft ook gebruikt). Alleen naar 127.0.0.1; de poort zit dicht in de firewall.

const (
	rconAuth          = 3
	rconAuthResponse  = 2
	rconExec          = 2
	rconResponseValue = 0
	rconMaxBody       = 4096
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

// rconCommand voert één opdracht uit en geeft het antwoord van de server.
func rconCommand(port int, password, command string, timeout time.Duration) (string, error) {
	if password == "" {
		return "", errors.New("RCON staat niet aan")
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), timeout)
	if err != nil {
		return "", errors.New("de server is (nog) niet bereikbaar via RCON")
	}
	defer conn.Close()
	deadline := time.Now().Add(timeout)
	_ = conn.SetDeadline(deadline)
	if err := rconWrite(conn, 1, rconAuth, password); err != nil {
		return "", err
	}
	for {
		id, kind, _, err := rconRead(conn)
		if err != nil {
			return "", err
		}
		if kind == rconAuthResponse {
			if id == -1 {
				return "", errRconAuth
			}
			break
		}
	}
	if err := rconWrite(conn, 2, rconExec, command); err != nil {
		return "", err
	}
	_, _, body, err := rconRead(conn)
	if err != nil {
		return "", err
	}
	// Lange antwoorden komen in stukken van 4096 tekens. Nooit meer dan 1 MB en nooit langer
	// dan de afgesproken tijd (een plugin kan zich als RCON-server voordoen).
	var out strings.Builder
	out.WriteString(body)
	for len(body) >= rconMaxBody-16 && out.Len() < 1<<20 {
		wait := time.Now().Add(300 * time.Millisecond)
		if wait.After(deadline) {
			wait = deadline
		}
		_ = conn.SetReadDeadline(wait)
		_, _, body, err = rconRead(conn)
		if err != nil {
			break
		}
		out.WriteString(body)
	}
	return stripFormatting(out.String()), nil
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

package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"time"
)

// pingServer vraagt de status op zoals de serverlijst in Minecraft dat doet (Server List Ping).
// Dat logt niets in de console, anders dan RCON.
func pingServer(port int, timeout time.Duration) (Players, string, error) {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), timeout)
	if err != nil {
		return Players{}, "", err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	var handshake bytes.Buffer
	writeVarInt(&handshake, 0)  // pakket: handshake
	writeVarInt(&handshake, -1) // protocolversie: onbekend
	host := "127.0.0.1"
	writeVarInt(&handshake, int32(len(host)))
	handshake.WriteString(host)
	_ = binary.Write(&handshake, binary.BigEndian, uint16(port))
	writeVarInt(&handshake, 1) // volgende stap: status
	var packets bytes.Buffer
	writeVarInt(&packets, int32(handshake.Len()))
	packets.Write(handshake.Bytes())
	writeVarInt(&packets, 1) // lengte
	writeVarInt(&packets, 0) // pakket: status opvragen
	if _, err := conn.Write(packets.Bytes()); err != nil {
		return Players{}, "", err
	}

	reader := bufio.NewReader(conn)
	length, err := readVarInt(reader)
	if err != nil || length <= 0 || length > 1<<20 {
		return Players{}, "", errors.New("ongeldig antwoord")
	}
	body := io.LimitReader(reader, int64(length))
	bodyReader := bufio.NewReader(body)
	if id, err := readVarInt(bodyReader); err != nil || id != 0 {
		return Players{}, "", errors.New("ongeldig antwoord")
	}
	size, err := readVarInt(bodyReader)
	if err != nil || size < 0 || size > 1<<20 {
		return Players{}, "", errors.New("ongeldig antwoord")
	}
	data := make([]byte, size)
	if _, err := io.ReadFull(bodyReader, data); err != nil {
		return Players{}, "", err
	}
	var status struct {
		Version struct {
			Name string `json:"name"`
		} `json:"version"`
		Players struct {
			Online int `json:"online"`
			Max    int `json:"max"`
			Sample []struct {
				Name string `json:"name"`
			} `json:"sample"`
		} `json:"players"`
	}
	if err := json.Unmarshal(data, &status); err != nil {
		return Players{}, "", err
	}
	players := Players{Online: status.Players.Online, Max: status.Players.Max, Names: []string{}}
	for _, sample := range status.Players.Sample {
		if len(players.Names) < 50 && sample.Name != "" {
			players.Names = append(players.Names, clip(stripFormatting(sample.Name), 32))
		}
	}
	return players, clip(status.Version.Name, 64), nil
}

func writeVarInt(buffer *bytes.Buffer, value int32) {
	unsigned := uint32(value)
	for {
		if unsigned&^0x7f == 0 {
			buffer.WriteByte(byte(unsigned))
			return
		}
		buffer.WriteByte(byte(unsigned&0x7f | 0x80))
		unsigned >>= 7
	}
}

func readVarInt(reader io.ByteReader) (int32, error) {
	var result uint32
	for i := 0; i < 5; i++ {
		b, err := reader.ReadByte()
		if err != nil {
			return 0, err
		}
		result |= uint32(b&0x7f) << (7 * i)
		if b&0x80 == 0 {
			return int32(result), nil
		}
	}
	return 0, errors.New("varint te lang")
}

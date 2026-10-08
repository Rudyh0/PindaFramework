package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base32"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"net/url"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// Tweestapsverificatie met een authenticator-app (RFC 6238: 30 seconden, 6 cijfers, SHA-1).

var base32NoPadding = base32.StdEncoding.WithPadding(base32.NoPadding)

func newTOTPSecret() string {
	secret := make([]byte, 20)
	if _, err := rand.Read(secret); err != nil {
		panic(err)
	}
	return base32NoPadding.EncodeToString(secret)
}

func totpCode(secret []byte, step int64) string {
	mac := hmac.New(sha1.New, secret)
	var counter [8]byte
	binary.BigEndian.PutUint64(counter[:], uint64(step))
	mac.Write(counter[:])
	sum := mac.Sum(nil)
	offset := sum[len(sum)-1] & 0x0f
	value := binary.BigEndian.Uint32(sum[offset:offset+4]) & 0x7fffffff
	return fmt.Sprintf("%06d", value%1_000_000)
}

// verifyTOTP controleert een code (één stap ervoor of erna mag ook, voor een klok die iets
// afwijkt) en geeft de gebruikte stap terug. Een code die al gebruikt is, telt niet nog eens.
func verifyTOTP(secret, code string, lastStep int64, now time.Time) (int64, bool) {
	code = strings.ReplaceAll(strings.TrimSpace(code), " ", "")
	if len(code) != 6 {
		return 0, false
	}
	key, err := base32NoPadding.DecodeString(strings.ToUpper(secret))
	if err != nil {
		return 0, false
	}
	current := now.Unix() / 30
	for _, step := range []int64{current - 1, current, current + 1} {
		if step <= lastStep {
			continue
		}
		if subtle.ConstantTimeCompare([]byte(totpCode(key, step)), []byte(code)) == 1 {
			return step, true
		}
	}
	return 0, false
}

func totpURI(issuer, account, secret string) string {
	label := url.PathEscape(issuer) + ":" + url.PathEscape(account)
	values := url.Values{}
	values.Set("secret", secret)
	values.Set("issuer", issuer)
	values.Set("algorithm", "SHA1")
	values.Set("digits", "6")
	values.Set("period", "30")
	return "otpauth://totp/" + label + "?" + values.Encode()
}

// totpQR is een PNG als data-URL, om zo in een <img> te zetten.
func totpQR(uri string) (string, error) {
	png, err := qrcode.Encode(uri, qrcode.Medium, 256)
	if err != nil {
		return "", err
	}
	return "data:image/png;base64," + base64.StdEncoding.EncodeToString(png), nil
}

// groupSecret zet een geheim in groepjes van vier, makkelijker over te typen.
func groupSecret(secret string) string {
	var out strings.Builder
	for i, r := range secret {
		if i > 0 && i%4 == 0 {
			out.WriteByte(' ')
		}
		out.WriteRune(r)
	}
	return out.String()
}

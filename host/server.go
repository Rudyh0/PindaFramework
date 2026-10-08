package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"embed"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

//go:embed web
var webFiles embed.FS

// apiError is een fout die we netjes aan de browser laten zien.
type apiError struct {
	status  int
	message string
}

func (e *apiError) Error() string { return e.message }

func badRequest(format string, args ...any) error {
	return &apiError{http.StatusBadRequest, fmt.Sprintf(format, args...)}
}

func forbidden(message string) error {
	return &apiError{http.StatusForbidden, message}
}

func notFound(message string) error {
	return &apiError{http.StatusNotFound, message}
}

// Request is alles wat een handler nodig heeft.
type Request struct {
	w       http.ResponseWriter
	r       *http.Request
	app     *App
	ip      string
	session *Session
	user    User
}

func (q *Request) body(target any) error {
	return q.bodyMax(target, 1<<20)
}

// bodyMax: zoals body, maar met een eigen grens (bijv. voor een bestand uit de editor).
func (q *Request) bodyMax(target any, max int64) error {
	reader := http.MaxBytesReader(q.w, q.r.Body, max)
	decoder := json.NewDecoder(reader)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil && !errors.Is(err, io.EOF) {
		return badRequest("Ongeldig verzoek.")
	}
	return nil
}

func (q *Request) log(action, target, details string) {
	q.app.audit.add(q.user.Name, q.ip, action, target, details)
}

type access int

const (
	public access = iota // ook zonder inloggen
	member               // ingelogd
	admin                // ingelogd als beheerder
)

type handler func(q *Request) (any, error)

// api maakt van een handler een HTTP-handler: inloggen controleren, JSON, fouten.
func (a *App) api(level access, handle handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		q := &Request{w: w, r: r, app: a, ip: a.clientIP(r)}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			// Alleen onze eigen pagina mag iets aanpassen (de browser stuurt deze header niet mee
			// bij verzoeken van andere sites).
			if r.Header.Get("X-Pinda-Host") != "1" || !sameOrigin(r) {
				writeError(w, forbidden("Verzoek geweigerd."))
				return
			}
		}
		if level >= member {
			session, user, ok := a.currentSession(r)
			if !ok {
				writeError(w, &apiError{http.StatusUnauthorized, "Je bent niet (meer) ingelogd."})
				return
			}
			if level == admin && !user.Admin {
				writeError(w, forbidden("Dit mag alleen een beheerder."))
				return
			}
			q.session, q.user = session, user
		}
		result, err := handle(q)
		if err != nil {
			writeError(w, err)
			return
		}
		if result == nil {
			return // de handler heeft zelf geantwoord (bijv. een download)
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	host := r.Host
	if forwarded := r.Header.Get("X-Forwarded-Host"); forwarded != "" && isLoopback(remoteHost(r)) {
		host = forwarded
	}
	return strings.TrimPrefix(strings.TrimPrefix(origin, "https://"), "http://") == host
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, err error) {
	var api *apiError
	if errors.As(err, &api) {
		writeJSON(w, api.status, map[string]string{"error": api.message})
		return
	}
	log.Printf("fout: %v", err)
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "Er ging iets mis: " + err.Error()})
}

// ============================================================ wie is het

func remoteHost(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func isLoopback(host string) bool {
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// clientIP: de echte IP van de bezoeker. Achter nginx staat die in X-Forwarded-For (het laatste
// adres is wie nginx zag); is dat Cloudflare, dan staat de bezoeker in CF-Connecting-IP.
func (a *App) clientIP(r *http.Request) string {
	host := remoteHost(r)
	if !isLoopback(host) {
		return host
	}
	forwarded := r.Header.Get("X-Forwarded-For")
	if forwarded == "" {
		return host
	}
	parts := strings.Split(forwarded, ",")
	peer := net.ParseIP(strings.TrimSpace(parts[len(parts)-1]))
	if peer == nil {
		return host
	}
	if a.config.get().Cloudflare && isCloudflare(peer) {
		if real := net.ParseIP(strings.TrimSpace(r.Header.Get("CF-Connecting-IP"))); real != nil {
			return real.String()
		}
	}
	return peer.String()
}

func secureRequest(r *http.Request) bool {
	return r.TLS != nil || (isLoopback(remoteHost(r)) && r.Header.Get("X-Forwarded-Proto") == "https")
}

// ============================================================ starten

func (a *App) routes() http.Handler {
	mux := http.NewServeMux()
	a.registerAuth(mux)
	a.registerSetup(mux)
	a.registerUsers(mux)
	a.registerDashboard(mux)
	a.registerDatabase(mux)
	a.registerFiles(mux)
	a.registerServer(mux)
	a.registerWebsite(mux)

	static, err := fs.Sub(webFiles, "web")
	if err != nil {
		panic(err)
	}
	files := http.FileServer(http.FS(static))
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/", "/app.js", "/files.js", "/server.js", "/app.css", "/favicon.svg":
			w.Header().Set("Cache-Control", "no-cache")
			files.ServeHTTP(w, r)
		default:
			http.NotFound(w, r)
		}
	})
	return securityHeaders(mux)
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := w.Header()
		header.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; "+
			"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		header.Set("X-Content-Type-Options", "nosniff")
		header.Set("X-Frame-Options", "DENY")
		header.Set("Referrer-Policy", "no-referrer")
		header.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		if secureRequest(r) {
			header.Set("Strict-Transport-Security", "max-age=31536000")
		}
		next.ServeHTTP(w, r)
	})
}

func (a *App) serve() error {
	config := a.config.get()
	if a.users.count() == 0 {
		if _, err := a.setupCode(); err != nil {
			return err
		}
		log.Printf("Nog geen gebruikers. Maak de eerste beheerder met de setupcode: sudo pinda-host setup-code")
	}
	go a.mariadb.cleanupImportUsers()
	server := &http.Server{
		Addr:              config.Listen,
		Handler:           a.routes(),
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	if config.Mode == "ip" {
		certFile, keyFile, err := a.ensureCertificate()
		if err != nil {
			return fmt.Errorf("certificaat maken mislukt: %w", err)
		}
		server.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		log.Printf("Dev-paneel luistert op https://%s (eigen certificaat)", config.Listen)
		return server.ListenAndServeTLS(certFile, keyFile)
	}
	log.Printf("Dev-paneel luistert op http://%s (achter nginx voor %s)", config.Listen, config.Domain)
	return server.ListenAndServe()
}

// ensureCertificate maakt (eenmalig) een eigen certificaat voor gebruik zonder domein.
func (a *App) ensureCertificate() (string, string, error) {
	dir := filepath.Join(a.dataDir, "tls")
	certFile := filepath.Join(dir, "cert.pem")
	keyFile := filepath.Join(dir, "key.pem")
	if _, err := os.Stat(certFile); err == nil {
		if _, err := os.Stat(keyFile); err == nil {
			return certFile, keyFile, nil
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", "", err
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	hostname, _ := os.Hostname()
	template := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "PindaHost dev-paneel", Organization: []string{"PindaHost"}},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	if hostname != "" {
		template.DNSNames = append(template.DNSNames, hostname)
	}
	for _, address := range publicAddresses() {
		template.IPAddresses = append(template.IPAddresses, net.ParseIP(address))
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return "", "", err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return "", "", err
	}
	if err := writeFileAtomic(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		return "", "", err
	}
	if err := writeFileAtomic(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
		return "", "", err
	}
	return certFile, keyFile, nil
}

// certificateFingerprint: om in de browser te controleren dat je echt op je eigen paneel zit.
func (a *App) certificateFingerprint() string {
	data, err := os.ReadFile(filepath.Join(a.dataDir, "tls", "cert.pem"))
	if err != nil {
		return ""
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return ""
	}
	sum := sha256.Sum256(block.Bytes)
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = strings.ToUpper(hex.EncodeToString([]byte{b}))
	}
	return strings.Join(parts, ":")
}

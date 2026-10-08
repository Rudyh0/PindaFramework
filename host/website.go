package main

import (
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// De website (/opt/pinda/website) wordt door nginx geserveerd. Via Cloudflare werkt dat meteen
// (nginx heeft een eigen certificaat, Cloudflare in modus Full). Zonder Cloudflare vraagt het
// paneel een certificaat aan bij Let's Encrypt en zet het een eigen nginx-blok voor het domein.

const websiteSite = "/etc/nginx/sites-available/pinda-website"

func (a *App) registerWebsite(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/website", a.api(member, a.handleWebsite))
	mux.HandleFunc("POST /api/website/certificate", a.api(admin, a.handleWebsiteCertificate))
}

// CertInfo: een certificaat van Let's Encrypt.
type CertInfo struct {
	Domains []string `json:"domains"`
	Expires int64    `json:"expires"`
}

func letsEncryptCert(domain string) (*CertInfo, error) {
	data, err := os.ReadFile(filepath.Join("/etc/letsencrypt/live", domain, "fullchain.pem"))
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("geen certificaat")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, err
	}
	return &CertInfo{Domains: cert.DNSNames, Expires: cert.NotAfter.UnixMilli()}, nil
}

func (a *App) handleWebsite(q *Request) (any, error) {
	config := a.config.get()
	result := map[string]any{
		"domain": config.SiteDomain, "mode": config.Mode, "cloudflare": config.Cloudflare,
		"addresses": publicAddresses(), "https": "none", "certificate": nil,
	}
	installed, state := unitState("nginx")
	result["nginx"] = map[string]any{"installed": installed, "active": state == "active"}
	_, managed := os.Stat(websiteSite)
	result["managedSite"] = managed == nil
	switch {
	case config.SiteDomain == "":
		result["https"] = "none"
	case config.Cloudflare:
		result["https"] = "cloudflare"
	default:
		if cert, err := letsEncryptCert(config.SiteDomain); err == nil {
			result["https"] = "letsencrypt"
			result["certificate"] = cert
		}
	}
	if config.SiteDomain != "" {
		result["dns"] = dnsRecordsFor(config, config.SiteDomain)
	}
	if root, err := a.fileRoot("website"); err == nil {
		result["free"] = root.Free()
	}
	return result, nil
}

// dnsRecordsFor: alleen de regels voor één naam.
func dnsRecordsFor(config Config, name string) []DNSRecord {
	var list []DNSRecord
	for _, record := range dnsRecords(config, publicAddresses()) {
		if record.Name == name {
			list = append(list, record)
		}
	}
	return list
}

func (a *App) handleWebsiteCertificate(q *Request) (any, error) {
	config := a.config.get()
	if config.SiteDomain == "" {
		return nil, badRequest("Er is nog geen domein voor de website ingesteld.")
	}
	if config.Cloudflare {
		return nil, badRequest("Via Cloudflare is geen eigen certificaat nodig: Cloudflare regelt HTTPS (modus Full).")
	}
	if _, err := exec.LookPath("certbot"); err != nil {
		return nil, badRequest("certbot is niet geïnstalleerd: sudo apt install certbot")
	}
	user, ip := q.user.Name, q.ip
	job := a.jobs.start("Certificaat voor "+config.SiteDomain, user, func(say func(string, ...any)) error {
		domains := []string{config.SiteDomain}
		www := "www." + config.SiteDomain
		if pointsHere(www) {
			domains = append(domains, www)
		} else {
			say("%s wijst niet naar deze server; het certificaat is alleen voor %s.", www, config.SiteDomain)
		}
		if !pointsHere(config.SiteDomain) {
			say("Let op: %s lijkt (nog) niet naar deze server te wijzen. Let's Encrypt kan dan niet controleren.", config.SiteDomain)
		}
		say("Certificaat aanvragen bij Let's Encrypt voor %s…", strings.Join(domains, " en "))
		args := []string{"certonly", "--webroot", "-w", filepath.Join(config.BaseDir, "acme"), "--non-interactive", "--agree-tos",
			"--register-unsafely-without-email", "--keep-until-expiring", "--cert-name", config.SiteDomain,
			"--deploy-hook", "systemctl reload nginx"}
		for _, domain := range domains {
			args = append(args, "-d", domain)
		}
		if out, err := runQuiet(5*time.Minute, "certbot", args...); err != nil {
			lines := strings.Split(strings.TrimSpace(out), "\n")
			if len(lines) > 8 {
				lines = lines[len(lines)-8:]
			}
			say("%s", strings.Join(lines, "\n"))
			a.audit.add(user, ip, "certificaat mislukt", config.SiteDomain, "")
			return errors.New("Let's Encrypt gaf geen certificaat; kijk of het A-record klopt")
		}
		say("Certificaat ontvangen; nginx instellen…")
		if err := writeWebsiteSite(config, domains); err != nil {
			return err
		}
		a.audit.add(user, ip, "certificaat aangevraagd", config.SiteDomain, strings.Join(domains, ", "))
		say("Klaar: https://%s werkt. Het certificaat wordt vanzelf verlengd.", config.SiteDomain)
		return nil
	})
	return job, nil
}

// pointsHere: wijst deze naam naar een van de IP-adressen van deze server?
func pointsHere(name string) bool {
	addresses, err := net.LookupHost(name)
	if err != nil {
		return false
	}
	own := map[string]bool{}
	for _, address := range publicAddresses() {
		own[address] = true
	}
	for _, address := range addresses {
		if own[address] {
			return true
		}
	}
	return false
}

// nginxHTTP2: vanaf nginx 1.25.1 is http2 een eigen regel.
func nginxHTTP2() (listen, line string) {
	out, _ := runQuiet(5*time.Second, "nginx", "-v")
	version := ""
	if index := strings.Index(out, "nginx/"); index >= 0 {
		version = strings.Fields(out[index+len("nginx/"):] + " ")[0]
	}
	if version != "" && compareVersions(version, "1.25.1") >= 0 {
		return "ssl", "\thttp2 on;\n"
	}
	return "ssl http2", ""
}

// websiteSiteConfig: het nginx-blok voor de website met een certificaat van Let's Encrypt.
func websiteSiteConfig(config Config, domains []string, listenSSL, http2Line string, ipv6 bool) string {
	names := strings.Join(domains, " ")
	live := filepath.Join("/etc/letsencrypt/live", config.SiteDomain)
	listen6 := func(port string) string {
		if !ipv6 {
			return ""
		}
		return "\tlisten [::]:" + port + ";\n"
	}
	return fmt.Sprintf(`# Beheerd door PindaHost (dev-paneel): de website op %[1]s met Let's Encrypt.
server {
	listen 80;
%[2]s	server_name %[1]s;
	server_tokens off;
	location /.well-known/acme-challenge/ {
		root %[3]s;
	}
	location / {
		return 301 https://$host$request_uri;
	}
}

server {
	listen 443 %[4]s;
%[5]s%[6]s	server_name %[1]s;
	server_tokens off;
	ssl_certificate %[7]s/fullchain.pem;
	ssl_certificate_key %[7]s/privkey.pem;
	ssl_protocols TLSv1.2 TLSv1.3;
	root %[8]s;
	index index.html;
	# Verborgen bestanden (zoals .env of de tijdelijke bestanden van een upload) nooit laten zien.
	location ~ /\.(?!well-known/) {
		deny all;
	}
	location / {
		try_files $uri $uri/ =404;
	}
}
`, names, listen6("80"), filepath.Join(config.BaseDir, "acme"), listenSSL, listen6("443 "+listenSSL), http2Line, live, config.websiteDir())
}

// writeWebsiteSite schrijft het blok, test nginx en laadt opnieuw. Klopt de test niet, dan
// komt het vorige blok terug en blijft alles zoals het was.
func writeWebsiteSite(config Config, domains []string) error {
	listenSSL, http2Line := nginxHTTP2()
	_, err := os.Stat("/proc/net/if_inet6")
	content := websiteSiteConfig(config, domains, listenSSL, http2Line, err == nil)
	previous, readErr := os.ReadFile(websiteSite)
	enabled := "/etc/nginx/sites-enabled/pinda-website"
	_, linkErr := os.Lstat(enabled)
	restore := func() {
		if readErr == nil {
			_ = writeFileAtomic(websiteSite, previous, 0o644)
		} else {
			_ = os.Remove(websiteSite)
		}
		if linkErr != nil {
			_ = os.Remove(enabled)
		}
	}
	if err := writeFileAtomic(websiteSite, []byte(content), 0o644); err != nil {
		return err
	}
	if linkErr != nil {
		if err := os.Symlink(websiteSite, enabled); err != nil {
			restore()
			return err
		}
	}
	if out, err := runQuiet(30*time.Second, "nginx", "-t"); err != nil {
		restore()
		return fmt.Errorf("de nginx-instellingen kloppen niet: %s", out)
	}
	if out, err := runQuiet(30*time.Second, "systemctl", "reload", "nginx"); err != nil {
		return fmt.Errorf("nginx herladen: %s", out)
	}
	return nil
}

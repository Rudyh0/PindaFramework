package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// Het nginx-blok voor de website moet door "nginx -t" komen (als nginx er is).
func TestWebsiteSiteConfig(t *testing.T) {
	config := Config{BaseDir: "/opt/pinda", SiteDomain: "pindacraft.nl"}
	for _, ipv6 := range []bool{true, false} {
		content := websiteSiteConfig(config, []string{"pindacraft.nl", "www.pindacraft.nl"}, "ssl http2", "", ipv6)
		for _, want := range []string{"server_name pindacraft.nl www.pindacraft.nl;", "ssl_certificate /etc/letsencrypt/live/pindacraft.nl/fullchain.pem;",
			"root /opt/pinda/website;", "root /opt/pinda/acme;", "listen 443 ssl http2;", "return 301 https://$host$request_uri;"} {
			if !strings.Contains(content, want) {
				t.Errorf("mist %q:\n%s", want, content)
			}
		}
		if strings.Contains(content, "[::]") != ipv6 {
			t.Errorf("IPv6 (%v) klopt niet:\n%s", ipv6, content)
		}
	}
	if _, err := exec.LookPath("nginx"); err != nil {
		t.Skip("geen nginx")
	}
	if _, err := exec.LookPath("openssl"); err != nil {
		t.Skip("geen openssl")
	}
	dir := t.TempDir()
	if out, err := exec.Command("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-days", "1",
		"-subj", "/CN=pindacraft.nl", "-keyout", filepath.Join(dir, "privkey.pem"), "-out", filepath.Join(dir, "fullchain.pem")).CombinedOutput(); err != nil {
		t.Fatalf("openssl: %s", out)
	}
	listenSSL, http2 := nginxHTTP2()
	_, err := os.Stat("/proc/net/if_inet6")
	site := websiteSiteConfig(config, []string{"pindacraft.nl"}, listenSSL, http2, err == nil)
	site = strings.ReplaceAll(site, "/etc/letsencrypt/live/pindacraft.nl", dir)
	os.WriteFile(filepath.Join(dir, "site.conf"), []byte(site), 0o644)
	conf := "pid " + dir + "/nginx.pid;\nerror_log " + dir + "/error.log;\nevents {}\nhttp {\n access_log off;\n include " + dir + "/site.conf;\n}\n"
	os.WriteFile(filepath.Join(dir, "nginx.conf"), []byte(conf), 0o644)
	if out, err := exec.Command("nginx", "-t", "-c", filepath.Join(dir, "nginx.conf")).CombinedOutput(); err != nil {
		t.Fatalf("nginx -t: %s\n%s", out, site)
	}
}

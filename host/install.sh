#!/usr/bin/env bash
# ┌──────────────────────────────────────────────────────────────┐
# │  PindaHost - installer voor een kale Ubuntu-server (VPS)      │
# └──────────────────────────────────────────────────────────────┘
# Installeert alles wat nodig is: Java, MariaDB, nginx (webserver), UFW (firewall) en het
# dev-paneel (pinda-host). De rest (Minecraft-server, database, DNS) doe je daarna in het
# dev-paneel zelf.
#
# Gebruik:
#   curl -fsSL https://github.com/Rudyh0/PindaFramework/releases/latest/download/install.sh | sudo bash
# of met opties (zonder vragen):
#   sudo bash install.sh --domain dev.jouwdomein.nl [--no-cloudflare]
#   sudo bash install.sh --ip [--port 8443]
# Opnieuw draaien mag altijd: het werkt alles bij en laat je instellingen staan.

set -euo pipefail

REPO="Rudyh0/PindaFramework"
BASE="/opt/pinda"
PANEL_DIR="$BASE/panel"
CONFIG="$PANEL_DIR/config.json"
SERVICE_USER="minecraft"
PANEL_PORT_LOCAL=8484

MODE=""
DOMAIN=""
CLOUDFLARE=""
PORT=""
ASSUME_YES=0
LOCAL_BINARY=""

# ------------------------------------------------------------------ uitvoer

if [ -t 1 ]; then
  BOLD=$'\e[1m'; DIM=$'\e[2m'; YELLOW=$'\e[33m'; GREEN=$'\e[32m'; RED=$'\e[31m'; RESET=$'\e[0m'
else
  BOLD=""; DIM=""; YELLOW=""; GREEN=""; RED=""; RESET=""
fi
step() { echo; echo "${BOLD}${YELLOW}▸ $*${RESET}"; }
ok()   { echo "  ${GREEN}✓${RESET} $*"; }
info() { echo "  ${DIM}$*${RESET}"; }
fail() { echo; echo "${RED}✗ $*${RESET}" >&2; exit 1; }

# Vragen lezen we van de terminal, ook als het script via "curl | bash" binnenkomt.
ask() {
  local prompt="$1" default="${2:-}" answer=""
  if [ "$ASSUME_YES" = 1 ] || ! { : < /dev/tty; } 2>/dev/null; then
    echo "$default"
    return
  fi
  if [ -n "$default" ]; then
    read -r -p "$prompt [$default]: " answer < /dev/tty || true
  else
    read -r -p "$prompt: " answer < /dev/tty || true
  fi
  echo "${answer:-$default}"
}

# ------------------------------------------------------------------ opties

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) MODE="domain"; DOMAIN="${2:-}"; shift 2 ;;
    --ip) MODE="ip"; shift ;;
    --port) PORT="${2:-}"; shift 2 ;;
    --cloudflare) CLOUDFLARE="true"; shift ;;
    --no-cloudflare) CLOUDFLARE="false"; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --binary) LOCAL_BINARY="${2:-}"; shift 2 ;;  # voor testen: een eigen gebouwde pinda-host
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) fail "Onbekende optie: $1 (zie --help)" ;;
  esac
done

[ "$(id -u)" = 0 ] || fail "Draai de installer als root, bijvoorbeeld met: sudo bash install.sh"
[ -r /etc/os-release ] || fail "Dit lijkt geen Ubuntu te zijn."
. /etc/os-release
if [ "${ID:-}" != "ubuntu" ]; then
  echo "${YELLOW}Let op: gemaakt voor Ubuntu; dit is ${PRETTY_NAME:-onbekend}. Het kan werken, maar zonder garantie.${RESET}"
fi
CODENAME="${VERSION_CODENAME:-${UBUNTU_CODENAME:-}}"

case "$(uname -m)" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) fail "Processor $(uname -m) wordt niet ondersteund (alleen amd64 en arm64)." ;;
esac

echo "${BOLD}PindaHost installer${RESET} ${DIM}(${PRETTY_NAME:-Linux}, $ARCH)${RESET}"

# ------------------------------------------------------------------ keuzes

EXISTING=0
if [ -f "$CONFIG" ]; then
  EXISTING=1
  info "Er staat al een dev-paneel. Het wordt bijgewerkt; je instellingen en gebruikers blijven staan."
  if command -v python3 >/dev/null 2>&1; then
    [ -n "$MODE" ] || MODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("mode",""))' "$CONFIG" 2>/dev/null || true)"
    [ -n "$DOMAIN" ] || DOMAIN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("domain",""))' "$CONFIG" 2>/dev/null || true)"
    # Cloudflare ja/nee ook overnemen: anders zou opnieuw draaien (met --yes of Enter) Cloudflare
    # aanzetten en poort 80/443 voor iedereen behalve Cloudflare dichtzetten.
    [ -n "$CLOUDFLARE" ] || CLOUDFLARE="$(python3 -c 'import json,sys; c=json.load(open(sys.argv[1])); print("true" if c.get("cloudflare") else "false") if "cloudflare" in c else print("")' "$CONFIG" 2>/dev/null || true)"
  fi
fi

if [ -z "$MODE" ]; then
  echo
  echo "Hoe wil je het dev-paneel bereiken?"
  echo "  1) Via een domeinnaam, bijv. dev.jouwdomein.nl ${DIM}(aanbevolen; HTTPS, ook via Cloudflare)${RESET}"
  echo "  2) Alleen via IP-adres en poort, bijv. https://1.2.3.4:8443 ${DIM}(eigen certificaat)${RESET}"
  choice="$(ask "Keuze" "1")"
  case "$choice" in
    2) MODE="ip" ;;
    *) MODE="domain" ;;
  esac
fi

if [ "$MODE" = "domain" ]; then
  while [ -z "$DOMAIN" ]; do
    DOMAIN="$(ask "Domeinnaam voor het dev-paneel (bijv. dev.jouwdomein.nl)" "")"
    [ -n "$DOMAIN" ] || [ "$ASSUME_YES" = 0 ] || fail "Geef een domein op met --domain."
  done
  DOMAIN="${DOMAIN,,}"
  DOMAIN="${DOMAIN#http://}"
  DOMAIN="${DOMAIN#https://}"
  DOMAIN="${DOMAIN%%/*}"
  # Met [[ =~ ]] wordt de hele waarde getest (grep keurt ook iets goed waarvan maar één regel klopt).
  DOMAIN_PATTERN='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$'
  [[ "$DOMAIN" =~ $DOMAIN_PATTERN ]] || fail "\"$DOMAIN\" is geen geldige domeinnaam."
  if [ -z "$CLOUDFLARE" ]; then
    answer="$(ask "Loopt dit domein via Cloudflare met de proxy aan (oranje wolk)? (j/n)" "j")"
    case "$answer" in n|N|nee|no) CLOUDFLARE="false" ;; *) CLOUDFLARE="true" ;; esac
  fi
else
  CLOUDFLARE="false"
  [ -n "$PORT" ] || PORT="$(ask "Poort voor het dev-paneel" "8443")"
  [[ "$PORT" =~ ^[0-9]{1,5}$ ]] && [ "$PORT" -ge 1024 ] && [ "$PORT" -le 65535 ] || fail "Kies een poort tussen 1024 en 65535."
fi

# ------------------------------------------------------------------ software

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -q -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

# Een eerdere versie van deze installer gebruikte Caddy, uit een eigen pakketbron. Die bron kon
# kapot zijn (sleutel niet te controleren), en dan stopt elke "apt-get update". Opruimen.
if [ -f /etc/apt/sources.list.d/caddy-stable.list ] && ! command -v caddy >/dev/null 2>&1; then
  rm -f /etc/apt/sources.list.d/caddy-stable.list /etc/apt/keyrings/caddy-stable.gpg /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  info "Oude Caddy-pakketbron weggehaald (PindaHost gebruikt nu nginx)."
fi
if grep -qs "PindaHost" /etc/caddy/Caddyfile; then
  systemctl disable --now caddy >/dev/null 2>&1 || true
  info "Caddy van een eerdere installatie uitgezet (PindaHost gebruikt nu nginx)."
fi

step "Systeem bijwerken en basispakketten installeren"
apt-get update -q
apt-get install "${APT_OPTS[@]}" ca-certificates curl gnupg openssl ufw tar unzip python3 >/dev/null
ok "Basispakketten"

step "Java 25 installeren (voor Minecraft)"
if java -version 2>&1 | grep -Eq 'version "(2[5-9]|[3-9][0-9])'; then
  ok "Java is er al: $(java -version 2>&1 | head -n 1)"
elif apt-get install "${APT_OPTS[@]}" openjdk-25-jre-headless >/dev/null 2>&1; then
  ok "OpenJDK 25 uit Ubuntu"
else
  info "Niet in Ubuntu zelf; via Adoptium (Eclipse Temurin)."
  install -d -m 0755 /etc/apt/keyrings
  curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $CODENAME main" > /etc/apt/sources.list.d/adoptium.list
  apt-get update -q
  apt-get install "${APT_OPTS[@]}" temurin-25-jre >/dev/null 2>&1 || apt-get install "${APT_OPTS[@]}" temurin-25-jdk >/dev/null
  ok "Temurin 25"
fi

step "MariaDB installeren (database)"
apt-get install "${APT_OPTS[@]}" mariadb-server mariadb-client >/dev/null
systemctl enable --now mariadb >/dev/null 2>&1 || true
# Zelfde als mariadb-secure-installation: geen anonieme gebruikers en geen testdatabase.
# root logt alleen in via de socket (als root op deze server), dus zonder wachtwoord van buitenaf onbereikbaar.
mariadb -uroot <<'SQL' || true
DELETE FROM mysql.global_priv WHERE User = '';
DELETE FROM mysql.global_priv WHERE User = 'root' AND Host NOT IN ('localhost', '127.0.0.1', '::1');
DROP DATABASE IF EXISTS test;
DELETE FROM mysql.db WHERE Db = 'test' OR Db = 'test\_%';
FLUSH PRIVILEGES;
SQL
ok "MariaDB $(mariadb -uroot -N -e 'SELECT VERSION()' 2>/dev/null | cut -d- -f1) (alleen bereikbaar vanaf deze server)"

step "nginx installeren (webserver)"
# Gewoon uit Ubuntu zelf: geen extra pakketbron of sleutel nodig.
if ! apt-get install "${APT_OPTS[@]}" nginx >/dev/null 2>&1; then
  # Zonder IPv6 kan de standaardpagina van Ubuntu (listen [::]:80) niet starten en blijft de
  # installatie hangen. Die pagina hebben we niet nodig.
  rm -f /etc/nginx/sites-enabled/default
  dpkg --configure -a >/dev/null 2>&1 || true
  apt-get install "${APT_OPTS[@]}" nginx >/dev/null || fail "nginx installeren mislukt."
fi
NGINX_VERSION="$(nginx -v 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)"
ok "nginx $NGINX_VERSION"
if [ "$MODE" = "domain" ] && [ "$CLOUDFLARE" != "true" ]; then
  # Zonder Cloudflare: een echt certificaat van Let's Encrypt.
  apt-get install "${APT_OPTS[@]}" certbot >/dev/null
  ok "certbot (Let's Encrypt)"
fi

# ------------------------------------------------------------------ mappen en gebruiker

step "Mappen en de gebruiker '$SERVICE_USER' maken"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$BASE/server" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
install -d -m 0755 "$BASE"
install -d -m 0700 "$PANEL_DIR"
install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$BASE/server" "$BASE/server/plugins"
install -d -m 0755 "$BASE/website" "$BASE/acme"
install -d -m 0700 "$BASE/backups"
if [ ! -f "$BASE/website/index.html" ]; then
  cat > "$BASE/website/index.html" <<'HTML'
<!doctype html><html lang="nl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Binnenkort</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0e0c0a;color:#f0eadf;font-family:system-ui,sans-serif}h1{font-size:28px}</style></head>
<body><h1>Hier komt de website van de server.</h1></body></html>
HTML
fi
ok "$BASE (server, website, backups, panel)"

# ------------------------------------------------------------------ dev-paneel

step "Dev-paneel (pinda-host) installeren"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ -n "$LOCAL_BINARY" ]; then
  cp "$LOCAL_BINARY" "$TMP/pinda-host"
else
  URL="https://github.com/$REPO/releases/latest/download/pinda-host-linux-$ARCH"
  curl -fsSL -o "$TMP/pinda-host" "$URL" || fail "Downloaden van het dev-paneel mislukt. Is GitHub bereikbaar?"
  curl -fsSL -o "$TMP/pinda-host.sha256" "$URL.sha256" || fail "Downloaden van het controlegetal mislukt. Is GitHub bereikbaar?"
  EXPECTED="$(awk '{print $1; exit}' "$TMP/pinda-host.sha256")"
  ACTUAL="$(sha256sum "$TMP/pinda-host" | awk '{print $1}')"
  [[ "$EXPECTED" =~ ^[0-9a-f]{64}$ ]] && [ "$EXPECTED" = "$ACTUAL" ] \
    || fail "De download klopt niet met het controlegetal (SHA-256). Probeer het opnieuw."
  ok "Controlegetal klopt"
fi
chmod 0755 "$TMP/pinda-host"
"$TMP/pinda-host" version >/dev/null || fail "Het gedownloade dev-paneel werkt niet op deze server."
install -m 0755 "$TMP/pinda-host" "$BASE/pinda-host"
ln -sf "$BASE/pinda-host" /usr/local/bin/pinda-host
ok "$("$BASE/pinda-host" version)"

if [ "$MODE" = "domain" ]; then
  LISTEN="127.0.0.1:$PANEL_PORT_LOCAL"
else
  LISTEN="0.0.0.0:$PORT"
fi
python3 - "$CONFIG" "$MODE" "$LISTEN" "$DOMAIN" "$CLOUDFLARE" "$BASE" "$SERVICE_USER" <<'PY'
import json, os, sys
path, mode, listen, domain, cloudflare, base, user = sys.argv[1:]
config = {}
if os.path.exists(path):
    with open(path) as f:
        config = json.load(f)
config.update({"mode": mode, "listen": listen, "domain": domain if mode == "domain" else "",
               "cloudflare": cloudflare == "true", "baseDir": base, "serviceUser": user})
config.setdefault("gamePort", 25565)
config.setdefault("sessionHours", 12)
config.setdefault("idleMinutes", 60)
tmp = path + ".tmp"
with open(tmp, "w") as f:
    json.dump(config, f, indent=2)
os.chmod(tmp, 0o600)
os.replace(tmp, path)
PY
ok "Instellingen in $CONFIG"

cat > /etc/systemd/system/pinda-host.service <<UNIT
[Unit]
Description=PindaHost dev-paneel
After=network-online.target mariadb.service
Wants=network-online.target

[Service]
ExecStart=$BASE/pinda-host serve --config $CONFIG
Restart=always
RestartSec=3
# Het dev-paneel beheert de server, MariaDB, nginx en de firewall, en draait daarom als root.
User=root
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable pinda-host >/dev/null 2>&1
systemctl restart pinda-host
ok "Draait als dienst (pinda-host)"

# ------------------------------------------------------------------ webserver

step "Webserver (nginx) instellen"
CF_RANGES="173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20 197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13 104.24.0.0/14 172.64.0.0/13 131.0.72.0/22 2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32 2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32"
NGINX_SITE="/etc/nginx/sites-available/pindahost"
SSL_DIR="/etc/nginx/pinda-ssl"
install -d -m 0700 "$SSL_DIR"

# Draait er al iets anders op poort 80 of 443 (bijv. Apache), dan kan nginx niet starten.
BUSY="$(ss -Htlnp 2>/dev/null | awk '$4 ~ /:(80|443)$/' | grep -v '"nginx"' | grep -oE '\(\("[^"]+"' | tr -d '("' | sort -u | tr '\n' ' ' || true)"
[ -z "$BUSY" ] || fail "Poort 80 of 443 is al in gebruik door: $BUSY. Zet dat eerst uit (bijv. sudo systemctl disable --now apache2) en draai de installer opnieuw."

# Een eigen certificaat (10 jaar). Achter Cloudflare (SSL/TLS-modus "Full") is dat genoeg:
# Cloudflare versleutelt naar deze server, en bezoekers zien het certificaat van Cloudflare.
self_signed() {
  local name="$1"
  if [ ! -s "$SSL_DIR/$name.crt" ] || [ ! -s "$SSL_DIR/$name.key" ]; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
      -subj "/CN=$name" -addext "subjectAltName=DNS:$name" \
      -keyout "$SSL_DIR/$name.key" -out "$SSL_DIR/$name.crt" >/dev/null 2>&1 \
      || fail "Certificaat maken mislukt (openssl)."
    chmod 0600 "$SSL_DIR/$name.key"
  fi
}
self_signed "pindahost.website"

# http2: vanaf nginx 1.25.1 een eigen regel, daarvoor achter "listen".
if [ "$(printf '%s\n' "1.25.1" "${NGINX_VERSION:-0}" | sort -V | head -n 1)" = "1.25.1" ]; then
  LISTEN_SSL="ssl"; HTTP2_LINE="	http2 on;"
else
  LISTEN_SSL="ssl http2"; HTTP2_LINE=""
fi

write_nginx() {
  local cert="$1" key="$2"
  {
    echo "# Beheerd door PindaHost (install.sh). Eigen aanpassingen worden bij opnieuw installeren overschreven."
    echo
    echo "map \$http_upgrade \$pinda_connection_upgrade {"
    echo "	default upgrade;"
    echo "	''      close;"
    echo "}"
    echo
    if [ "$MODE" = "domain" ]; then
      cat <<NGINX
# Dev-paneel: $DOMAIN
server {
	listen 80;
	listen [::]:80;
	server_name $DOMAIN;
	server_tokens off;
	location /.well-known/acme-challenge/ {
		root $BASE/acme;
	}
	location / {
		return 301 https://\$host\$request_uri;
	}
}

server {
	listen 443 $LISTEN_SSL;
	listen [::]:443 $LISTEN_SSL;
$HTTP2_LINE
	server_name $DOMAIN;
	server_tokens off;
	ssl_certificate $cert;
	ssl_certificate_key $key;
	ssl_protocols TLSv1.2 TLSv1.3;
	# Uploads (databases, later bestanden); via Cloudflare is de grens 100 MB per keer.
	client_max_body_size 4g;

	location / {
		proxy_pass http://127.0.0.1:$PANEL_PORT_LOCAL;
		proxy_http_version 1.1;
		proxy_set_header Host \$host;
		# Het paneel kijkt zelf of dit Cloudflare is (en leest dan CF-Connecting-IP).
		proxy_set_header X-Forwarded-For \$remote_addr;
		proxy_set_header X-Forwarded-Proto \$scheme;
		proxy_set_header Upgrade \$http_upgrade;
		proxy_set_header Connection \$pinda_connection_upgrade;
		proxy_request_buffering off;
		proxy_buffering off;
		proxy_read_timeout 1h;
		proxy_send_timeout 1h;
	}
}

NGINX
    fi
    cat <<NGINX
# De website (later te beheren in het dev-paneel)
server {
	listen 80 default_server;
	listen [::]:80 default_server;
	listen 443 $LISTEN_SSL default_server;
	listen [::]:443 $LISTEN_SSL default_server;
$HTTP2_LINE
	server_name _;
	server_tokens off;
	ssl_certificate $SSL_DIR/pindahost.website.crt;
	ssl_certificate_key $SSL_DIR/pindahost.website.key;
	ssl_protocols TLSv1.2 TLSv1.3;
	root $BASE/website;
	index index.html;
	location /.well-known/acme-challenge/ {
		root $BASE/acme;
	}
	# Verborgen bestanden (zoals .env of de tijdelijke bestanden van een upload) nooit laten zien.
	location ~ /\.(?!well-known/) {
		deny all;
	}
	location / {
		try_files \$uri \$uri/ =404;
	}
}
NGINX
  } > "$NGINX_SITE"
  # Een VPS zonder IPv6: dan kan nginx niet op [::] luisteren.
  [ -e /proc/net/if_inet6 ] || sed -i '/listen \[::\]/d' "$NGINX_SITE"
  ln -sf "$NGINX_SITE" /etc/nginx/sites-enabled/pindahost
  # De standaardpagina van Ubuntu wil ook "default_server" zijn.
  [ -L /etc/nginx/sites-enabled/default ] && rm -f /etc/nginx/sites-enabled/default
  nginx -t >/dev/null 2>&1 || fail "De nginx-configuratie klopt niet: $(nginx -t 2>&1 | tail -n 2 | tr '\n' ' ')"
  systemctl enable nginx >/dev/null 2>&1 || true
  systemctl reload nginx 2>/dev/null || systemctl restart nginx
}

CERT_NOTE=""
if [ "$MODE" = "domain" ]; then
  LE_DIR="/etc/letsencrypt/live/$DOMAIN"
  if [ "$CLOUDFLARE" != "true" ] && [ -s "$LE_DIR/fullchain.pem" ]; then
    write_nginx "$LE_DIR/fullchain.pem" "$LE_DIR/privkey.pem"
    CERT_NOTE="letsencrypt"
  else
    self_signed "$DOMAIN"
    write_nginx "$SSL_DIR/$DOMAIN.crt" "$SSL_DIR/$DOMAIN.key"
    CERT_NOTE="eigen"
    if [ "$CLOUDFLARE" != "true" ]; then
      # Let's Encrypt lukt alleen als het domein al naar deze server wijst.
      if certbot certonly --webroot -w "$BASE/acme" -d "$DOMAIN" --non-interactive --agree-tos \
          --register-unsafely-without-email --keep-until-expiring \
          --deploy-hook "systemctl reload nginx" >/dev/null 2>&1 && [ -s "$LE_DIR/fullchain.pem" ]; then
        write_nginx "$LE_DIR/fullchain.pem" "$LE_DIR/privkey.pem"
        CERT_NOTE="letsencrypt"
      fi
    fi
  fi
else
  write_nginx "" ""
fi
case "$CERT_NOTE" in
  letsencrypt) ok "nginx, met een certificaat van Let's Encrypt (wordt vanzelf verlengd)" ;;
  eigen) if [ "$CLOUDFLARE" = "true" ]; then ok "nginx, met een eigen certificaat voor Cloudflare (modus Full)"; else ok "nginx (nog met een eigen certificaat, zie hieronder)"; fi ;;
  *) ok "nginx (website op poort 80)" ;;
esac

# ------------------------------------------------------------------ firewall

step "Firewall (UFW) instellen"
# SSH mag nooit dichtgaan: we zoeken de poort op alle manieren die er zijn.
SSH_PORTS=""
add_ssh_port() { [[ "$1" =~ ^[0-9]{1,5}$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ] && SSH_PORTS="$SSH_PORTS $1"; return 0; }
for port in $(sshd -T 2>/dev/null | awk '/^port /{print $2}'); do add_ssh_port "$port"; done
# Wat sshd (of systemd voor ssh.socket) nu echt open heeft.
for port in $(ss -Htlnp 2>/dev/null | awk '/"sshd/ {n=split($4,a,":"); print a[n]}'); do add_ssh_port "$port"; done
for port in $(systemctl show ssh.socket -p Listen --value 2>/dev/null | grep -oE '[0-9]+ \(Stream\)' | awk '{print $1}'); do add_ssh_port "$port"; done
# De verbinding waarmee je nu bent ingelogd (als sudo die doorgeeft).
[ -n "${SSH_CONNECTION:-}" ] && add_ssh_port "$(echo "$SSH_CONNECTION" | awk '{print $4}')"
SSH_PORTS="$(echo $SSH_PORTS | tr ' ' '\n' | sort -un | tr '\n' ' ')"
SSH_PORTS="${SSH_PORTS% }"
[ -n "$SSH_PORTS" ] || SSH_PORTS="22"
for port in $SSH_PORTS; do
  ufw allow "$port/tcp" comment "SSH" >/dev/null
done
if [ "$MODE" = "domain" ] && [ "$CLOUDFLARE" = "true" ]; then
  # Alles loopt via de Cloudflare-proxy: 80 en 443 alleen open voor Cloudflare zelf.
  ufw delete allow 80/tcp >/dev/null 2>&1 || true
  ufw delete allow 443/tcp >/dev/null 2>&1 || true
  for range in $CF_RANGES; do
    ufw allow proto tcp from "$range" to any port 80,443 comment "Cloudflare" >/dev/null
  done
  WEB_OPEN="80 en 443 (alleen voor Cloudflare)"
else
  ufw allow 80/tcp comment "Website" >/dev/null
  ufw allow 443/tcp comment "HTTPS" >/dev/null
  WEB_OPEN="80, 443"
fi
ufw allow 25565/tcp comment "Minecraft" >/dev/null
if [ "$MODE" = "ip" ]; then
  ufw allow "$PORT/tcp" comment "PindaHost dev-paneel" >/dev/null
fi
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw --force enable >/dev/null
ok "Open: SSH (${SSH_PORTS// /, }), $WEB_OPEN, 25565$([ "$MODE" = "ip" ] && echo ", $PORT") — de rest is dicht"

# ------------------------------------------------------------------ klaar

sleep 2
systemctl is-active --quiet pinda-host || fail "Het dev-paneel start niet. Kijk met: journalctl -u pinda-host -n 50"
CODE="$("$BASE/pinda-host" setup-code --config "$CONFIG" 2>/dev/null || true)"
IP="$(curl -fsS4 --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"

echo
echo "${BOLD}${GREEN}Klaar!${RESET}"
echo
if [ "$MODE" = "domain" ]; then
  echo "  Dev-paneel:  ${BOLD}https://$DOMAIN${RESET}"
  echo
  echo "  Zorg dat ${BOLD}$DOMAIN${RESET} naar ${BOLD}$IP${RESET} wijst (A-record)."
  if [ "$CLOUDFLARE" = "true" ]; then
    echo "  In Cloudflare: proxy aan (oranje wolk) en bij SSL/TLS de modus ${BOLD}Full${RESET} (niet \"Full (strict)\")."
  elif [ "$CERT_NOTE" != "letsencrypt" ]; then
    echo "  ${YELLOW}Nog geen Let's Encrypt-certificaat:${RESET} het domein wijst (nog) niet naar deze server."
    echo "  Zet het A-record goed en draai de installer daarna nog een keer; tot die tijd waarschuwt je browser."
  fi
else
  echo "  Dev-paneel:  ${BOLD}https://$IP:$PORT${RESET}"
  echo
  echo "  Het paneel gebruikt een eigen certificaat; je browser waarschuwt daar één keer voor."
  FINGERPRINT="$("$BASE/pinda-host" fingerprint --config "$CONFIG" 2>/dev/null || true)"
  [ -n "$FINGERPRINT" ] && echo "  Vingerafdruk (SHA-256): ${DIM}$FINGERPRINT${RESET}"
fi
echo
if [ -n "$CODE" ] && ! echo "$CODE" | grep -q "al gedaan"; then
  echo "  Setupcode:   ${BOLD}${YELLOW}$CODE${RESET}"
  echo "  ${DIM}Hiermee maak je in het dev-paneel de eerste beheerder. Kwijt? sudo pinda-host setup-code${RESET}"
else
  echo "  ${DIM}Log in met je bestaande account.${RESET}"
fi
echo

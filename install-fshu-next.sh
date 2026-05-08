#!/bin/bash
# 4shu Messaging Server — Installation Script
# Run as root on Ubuntu 22.04 / 24.04
# Usage: bash install-fshu-next.sh
#
# Required files in the same directory as this script:
#   server.js, admin.js, package.json
# Optional:
#   firebase-adminsdk.json  (only if Firebase push is needed)

set -e

# ── Colors ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()    { echo -e "${CYAN}[4shu]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC} $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ── Root check ──────────────────────────────────────────────────────────────
[ "$EUID" -ne 0 ] && error "Please run as root: sudo bash install-fshu-next.sh"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for f in server.js admin.js package.json; do
    [ -f "$SCRIPT_DIR/$f" ] || error "Missing required file: $f (must be next to install-fshu-next.sh)"
done

# ── Banner ──────────────────────────────────────────────────────────────────
echo ""
echo "  ██╗  ██╗███████╗██╗  ██╗██╗   ██╗"
echo "  ██║  ██║██╔════╝██║  ██║██║   ██║"
echo "  ███████║███████╗███████║██║   ██║"
echo "  ╚════██║╚════██║██╔══██║██║   ██║"
echo "       ██║███████║██║  ██║╚██████╔╝"
echo "       ╚═╝╚══════╝╚═╝  ╚═╝ ╚═════╝"
echo ""
echo "  4shu Messaging Server — Installer"
echo "  ─────────────────────────────────"
echo ""

# ── Prompts ─────────────────────────────────────────────────────────────────
read -p "Instance name [fshu5]: " INSTANCE_NAME
INSTANCE_NAME=${INSTANCE_NAME:-fshu5}
[[ "$INSTANCE_NAME" =~ ^[a-z0-9_-]+$ ]] || error "Instance name must be lowercase letters, numbers, hyphens or underscores"

DEFAULT_DIR="/opt/$INSTANCE_NAME"
read -p "Install directory [$DEFAULT_DIR]: " INSTALL_DIR
INSTALL_DIR=${INSTALL_DIR:-$DEFAULT_DIR}

read -p "Port [8083]: " PORT
PORT=${PORT:-8083}

read -p "Domain [localhost]: " DOMAIN
DOMAIN=${DOMAIN:-localhost}

DEFAULT_WS_PATH="/$INSTANCE_NAME/"
read -p "WebSocket path [$DEFAULT_WS_PATH]: " WS_PATH
WS_PATH=${WS_PATH:-$DEFAULT_WS_PATH}
[[ "$WS_PATH" != /* ]] && WS_PATH="/$WS_PATH"
[[ "$WS_PATH" != */ ]] && WS_PATH="$WS_PATH/"

read -p "TURN port [3478]: " TURN_PORT
TURN_PORT=${TURN_PORT:-3478}

read -p "TURN relay port range start [49152]: " TURN_MIN_PORT
TURN_MIN_PORT=${TURN_MIN_PORT:-49152}

read -p "TURN relay port range end [49200]: " TURN_MAX_PORT
TURN_MAX_PORT=${TURN_MAX_PORT:-49200}

read -p "TURN username (leave blank to auto-generate): " TURN_USER
if [ -z "$TURN_USER" ]; then
    TURN_USER="$(openssl rand -hex 6)"
    info "Auto-generated TURN username: $TURN_USER"
fi

read -p "TURN password (leave blank to auto-generate): " TURN_PASS
if [ -z "$TURN_PASS" ]; then
    TURN_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"
    info "Auto-generated TURN password: $TURN_PASS"
fi

while true; do
    read -p "Admin username: " ADMIN_USER
    [ -z "$ADMIN_USER" ] && { warn "Admin username is required"; continue; }
    [ "${#ADMIN_USER}" -lt 3 ] && { warn "Admin username must be at least 3 characters"; continue; }
    break
done

while true; do
    read -p "Admin password: " ADMIN_PASS
    echo ""
    [ -z "$ADMIN_PASS" ] && { warn "Admin password is required"; continue; }
    [ "${#ADMIN_PASS}" -lt 6 ] && { warn "Admin password must be at least 6 characters"; continue; }
    read -p "Confirm admin password: " ADMIN_PASS_CONFIRM
    echo ""
    [ "$ADMIN_PASS" = "$ADMIN_PASS_CONFIRM" ] && { success "Password confirmed"; break; }
    warn "Passwords do not match — try again"
done

read -p "Enable Firebase push notifications? [n]: " USE_FIREBASE
USE_FIREBASE=${USE_FIREBASE:-n}
FIREBASE_PATH=""
if [[ "$USE_FIREBASE" =~ ^[Yy]$ ]]; then
    read -p "Path to firebase-adminsdk.json [/root/firebase-adminsdk.json]: " FIREBASE_PATH
    FIREBASE_PATH=${FIREBASE_PATH:-/root/firebase-adminsdk.json}
fi

# ── Summary ─────────────────────────────────────────────────────────────────
echo ""
info "Configuration:"
echo "  Instance:    $INSTANCE_NAME"
echo "  Directory:   $INSTALL_DIR"
echo "  Port:        $PORT"
echo "  Domain:      $DOMAIN"
echo "  WebSocket:   wss://$DOMAIN$WS_PATH"
echo "  TURN port:   $TURN_PORT  relay: ${TURN_MIN_PORT}-${TURN_MAX_PORT}"
echo "  TURN user:   $TURN_USER"
echo "  Firebase:    $USE_FIREBASE"
echo ""
read -p "Proceed? (y/N): " CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || { info "Aborted."; exit 0; }
echo ""

# ── Install system packages ──────────────────────────────────────────────────
info "Updating package list..."
DEBIAN_FRONTEND=noninteractive apt-get update -qq

for pkg in curl nginx coturn certbot python3-certbot-nginx; do
    if ! dpkg -s "$pkg" &>/dev/null; then
        info "Installing $pkg..."
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$pkg"
    fi
done

if ! command -v node &>/dev/null || [[ $(node -v | cut -d. -f1 | tr -d 'v') -lt 20 ]]; then
    info "Installing Node.js 20..."
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - &>/dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs
fi
success "Node.js $(node -v) ready"

# ── Create directories ───────────────────────────────────────────────────────
info "Creating directories at $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/data" "$INSTALL_DIR/files" "$INSTALL_DIR/avatars"
success "Directories created"

# ── Copy server files ────────────────────────────────────────────────────────
info "Copying server files..."
cp "$SCRIPT_DIR/server.js"   "$INSTALL_DIR/server.js"
cp "$SCRIPT_DIR/admin.js"    "$INSTALL_DIR/admin.js"
cp "$SCRIPT_DIR/package.json" "$INSTALL_DIR/package.json"

if [[ "$USE_FIREBASE" =~ ^[Yy]$ ]] && [ -f "$FIREBASE_PATH" ]; then
    cp "$FIREBASE_PATH" "$INSTALL_DIR/firebase-adminsdk.json"
    success "firebase-adminsdk.json copied"
elif [[ "$USE_FIREBASE" =~ ^[Yy]$ ]]; then
    warn "firebase-adminsdk.json not found at $FIREBASE_PATH"
    warn "Place it at $INSTALL_DIR/firebase-adminsdk.json and restart the service"
fi
success "Server files copied"

# ── npm install ──────────────────────────────────────────────────────────────
info "Installing Node.js dependencies..."
cd "$INSTALL_DIR"
npm install --quiet --no-audit --no-fund
success "Dependencies installed"

# ── config.json ─────────────────────────────────────────────────────────────
info "Writing config.json..."
cat > "$INSTALL_DIR/data/config.json" << EOF
{
  "historyRetentionDays": 90,
  "fileRetentionDays": 90,
  "maxHistoryRequestDays": 90,
  "turnUsername": "$TURN_USER",
  "turnPassword": "$TURN_PASS",
  "publicUrl": "https://$DOMAIN",
  "features": {
    "multiDevice": true,
    "ecdh": false,
    "groups": true,
    "voiceMessages": true,
    "reactions": true,
    "editMessages": true,
    "deleteForAll": true,
    "typingIndicators": true,
    "inviteLinks": true,
    "userSearch": false
  },
  "limits": {
    "maxFileSizeMB": 50,
    "maxVoiceDurationSeconds": 300,
    "maxGroupSize": 500,
    "fileRetentionDays": 90,
    "historyRetentionDays": 90,
    "localCacheRetentionDays": 30
  }
}
EOF
success "config.json written"

# ── systemd service ──────────────────────────────────────────────────────────
info "Creating systemd service $INSTANCE_NAME..."
cat > "/etc/systemd/system/$INSTANCE_NAME.service" << EOF
[Unit]
Description=4shu messaging server ($INSTANCE_NAME)
After=network.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
Environment=FSHU_BASE_DIR=$INSTALL_DIR
Environment=PORT=$PORT
ExecStart=/usr/bin/node $INSTALL_DIR/server.js
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$INSTANCE_NAME"
systemctl start "$INSTANCE_NAME"
success "Service $INSTANCE_NAME started"

# ── coturn ───────────────────────────────────────────────────────────────────
info "Configuring TURN server..."

PUBLIC_IP=$(curl -s --max-time 5 https://api.ipify.org 2>/dev/null || \
            curl -s --max-time 5 https://ifconfig.me 2>/dev/null || \
            hostname -I | awk '{print $1}')

if ss -ulnp 2>/dev/null | grep -q ":$TURN_PORT "; then
    warn "Port $TURN_PORT already in use — adding user to existing coturn config"
    turnadmin -a -u "$TURN_USER" -r "$DOMAIN" -p "$TURN_PASS" 2>/dev/null || \
        echo "user=$TURN_USER:$TURN_PASS" >> /etc/turnserver.conf
else
    TURN_REALM="$DOMAIN"
    [ "$DOMAIN" = "localhost" ] && TURN_REALM="$PUBLIC_IP"
    cat >> /etc/turnserver.conf << EOF

# --- $INSTANCE_NAME ---
listening-port=$TURN_PORT
external-ip=$PUBLIC_IP
min-port=$TURN_MIN_PORT
max-port=$TURN_MAX_PORT
fingerprint
lt-cred-mech
realm=$TURN_REALM
# -------------------
EOF
    # Enable coturn daemon
    sed -i 's/^#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null || true
    turnadmin -a -u "$TURN_USER" -r "$TURN_REALM" -p "$TURN_PASS" 2>/dev/null || \
        echo "user=$TURN_USER:$TURN_PASS" >> /etc/turnserver.conf
fi

systemctl restart coturn
success "coturn configured"

# ── nginx ────────────────────────────────────────────────────────────────────
info "Inserting nginx location block for $WS_PATH..."

export _FSHU_WS_PATH="$WS_PATH"
export _FSHU_PORT="$PORT"
export _FSHU_INSTANCE="$INSTANCE_NAME"

python3 <<'PYEOF'
import os, sys

ws_path  = os.environ['_FSHU_WS_PATH']
port     = os.environ['_FSHU_PORT']
instance = os.environ['_FSHU_INSTANCE']
nginx_file = '/etc/nginx/sites-enabled/default'

try:
    with open(nginx_file, 'r') as f:
        content = f.read()
except FileNotFoundError:
    # Create minimal default if missing
    content = 'server {\n    listen 80 default_server;\n    server_name _;\n}\n'

if f'location {ws_path}' in content:
    print(f'  location {ws_path} already exists — skipping')
    sys.exit(0)

block = f"""
    # 4shu {instance}
    location {ws_path} {{
        proxy_pass http://127.0.0.1:{port}/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }}"""

# Insert before the last closing brace of the last server{} block
idx = content.rfind('\n}')
if idx == -1:
    print('ERROR: could not find closing brace in nginx config', file=sys.stderr)
    sys.exit(1)

content = content[:idx] + block + '\n' + content[idx:]
with open(nginx_file, 'w') as f:
    f.write(content)
print(f'  location {ws_path} → 127.0.0.1:{port} added')
PYEOF

nginx -t && systemctl reload nginx
success "nginx configured"

# ── certbot ──────────────────────────────────────────────────────────────────
if [ "$DOMAIN" != "localhost" ]; then
    info "Requesting TLS certificate for $DOMAIN..."
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos \
        -m "admin@$DOMAIN" || warn "Certbot failed — run manually: certbot --nginx -d $DOMAIN"
fi

# ── Wait for service ready ───────────────────────────────────────────────────
info "Waiting for service to be ready..."
for i in $(seq 1 15); do
    FSHU_BASE_DIR="$INSTALL_DIR" node "$INSTALL_DIR/admin.js" list &>/dev/null && break
    sleep 1
done

# ── Create admin user ────────────────────────────────────────────────────────
info "Creating admin user '$ADMIN_USER'..."
if FSHU_BASE_DIR="$INSTALL_DIR" node "$INSTALL_DIR/admin.js" add "$ADMIN_USER" "$ADMIN_PASS"; then
    FSHU_BASE_DIR="$INSTALL_DIR" node "$INSTALL_DIR/admin.js" setadmin "$ADMIN_USER"
    success "Admin user '$ADMIN_USER' created"
else
    warn "Could not create admin user. Run manually:"
    warn "  FSHU_BASE_DIR=$INSTALL_DIR node $INSTALL_DIR/admin.js add $ADMIN_USER <password>"
    warn "  FSHU_BASE_DIR=$INSTALL_DIR node $INSTALL_DIR/admin.js setadmin $ADMIN_USER"
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "  ─────────────────────────────────────────────"
echo -e "  ${GREEN}=== 4shu installation complete ===${NC}"
echo "  ─────────────────────────────────────────────"
echo ""
echo "  Instance:   $INSTANCE_NAME"
echo "  Directory:  $INSTALL_DIR"
echo "  Port:       $PORT"
echo "  WebSocket:  wss://$DOMAIN$WS_PATH"
echo "  Admin user: $ADMIN_USER"
echo "  TURN:       $DOMAIN:$TURN_PORT  user=$TURN_USER"
echo ""
echo "  Next steps:"
echo "    - Set your app server URL to: wss://$DOMAIN$WS_PATH"
echo "    - Forward ports: $PORT TCP, 443 TCP, $TURN_PORT UDP+TCP, ${TURN_MIN_PORT}-${TURN_MAX_PORT} UDP"
echo ""
echo "  Manage users:"
echo "    FSHU_BASE_DIR=$INSTALL_DIR node $INSTALL_DIR/admin.js list"
echo "    FSHU_BASE_DIR=$INSTALL_DIR node $INSTALL_DIR/admin.js add <user> <pass>"
echo ""
echo "  ─────────────────────────────────────────────"
echo ""

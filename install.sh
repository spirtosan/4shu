Create new file: install.sh in the project root 
C:\Users\spirt\fshu\install.sh

Content:
#!/bin/bash
# 4shu Messaging Server — Installation Script
# Supports multiple instances on the same server
# Run as root on Ubuntu 24.04
# Usage: bash install.sh

set -e

# ── Colors ────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()    { echo -e "${CYAN}[4shu]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC} $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ── Root check ────────────────────────────────────────────────────────
[ "$EUID" -ne 0 ] && error "Please run as root: sudo bash install.sh"

# ── Banner ────────────────────────────────────────────────────────────
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

# ── Gather configuration ──────────────────────────────────────────────
read -p "Instance name (default: fshu): " INSTANCE_NAME
INSTANCE_NAME=${INSTANCE_NAME:-fshu}

# Validate instance name
[[ "$INSTANCE_NAME" =~ ^[a-z0-9_-]+$ ]] || error "Instance name must be lowercase letters, numbers, hyphens, underscores only"

DEFAULT_DIR="/opt/$INSTANCE_NAME"
read -p "Install directory (default: $DEFAULT_DIR): " INSTALL_DIR
INSTALL_DIR=${INSTALL_DIR:-$DEFAULT_DIR}

DEFAULT_PORT=8080
read -p "Internal Node.js port (default: $DEFAULT_PORT): " NODE_PORT
NODE_PORT=${NODE_PORT:-$DEFAULT_PORT}

read -p "Domain name (e.g. example.com — leave blank to skip TLS): " DOMAIN

read -p "WebSocket path (default: /$INSTANCE_NAME/): " WS_PATH
WS_PATH=${WS_PATH:-/$INSTANCE_NAME/}
# Ensure path starts and ends with /
[[ "$WS_PATH" != /* ]] && WS_PATH="/$WS_PATH"
[[ "$WS_PATH" != */ ]] && WS_PATH="$WS_PATH/"

read -p "TURN port (default: 3478): " TURN_PORT
TURN_PORT=${TURN_PORT:-3478}

read -p "TURN relay ports min (default: 49152): " TURN_MIN_PORT
TURN_MIN_PORT=${TURN_MIN_PORT:-49152}

read -p "TURN relay ports max (default: 49200): " TURN_MAX_PORT
TURN_MAX_PORT=${TURN_MAX_PORT:-49200}

read -p "TURN username (default: $INSTANCE_NAME): " TURN_USER
TURN_USER=${TURN_USER:-$INSTANCE_NAME}

read -p "TURN password (leave blank to auto-generate): " TURN_PASS
if [ -z "$TURN_PASS" ]; then
    TURN_PASS=$(openssl rand -base64 32)
    info "Auto-generated TURN password: $TURN_PASS"
fi

read -p "First admin username: " ADMIN_USER
[ -z "$ADMIN_USER" ] && error "Admin username is required"

read -s -p "First admin password: " ADMIN_PASS
echo ""
[ -z "$ADMIN_PASS" ] && error "Admin password is required"

read -p "Enable Firebase push notifications? (y/N): " USE_FIREBASE
USE_FIREBASE=${USE_FIREBASE:-n}

echo ""
info "Configuration summary:"
echo "  Instance:      $INSTANCE_NAME"
echo "  Directory:     $INSTALL_DIR"
echo "  Node.js port:  $NODE_PORT"
echo "  WebSocket:     ${DOMAIN:+wss://$DOMAIN}${WS_PATH}"
echo "  TURN user:     $TURN_USER"
echo "  TURN port:     $TURN_PORT"
echo "  Relay ports:   $TURN_MIN_PORT-$TURN_MAX_PORT"
echo "  Firebase:      $USE_FIREBASE"
echo ""
read -p "Proceed with installation? (y/N): " CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || { info "Aborted."; exit 0; }

# ── Check for port conflicts ──────────────────────────────────────────
info "Checking for port conflicts..."
if ss -tlnp | grep -q ":$NODE_PORT "; then
    error "Port $NODE_PORT is already in use. Choose a different Node.js port."
fi
success "Port $NODE_PORT is available"

# ── Install dependencies ──────────────────────────────────────────────
info "Updating package list..."
apt-get update -qq

info "Installing nginx..."
apt-get install -y -qq nginx

info "Installing coturn..."
apt-get install -y -qq coturn

info "Installing Node.js 20..."
if ! command -v node &>/dev/null || [[ $(node -v | cut -d. -f1 | tr -d 'v') -lt 20 ]]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - &>/dev/null
    apt-get install -y -qq nodejs
fi
success "Node.js $(node -v) installed"

info "Installing certbot..."
apt-get install -y -qq certbot python3-certbot-nginx

# ── Create directory structure ────────────────────────────────────────
info "Creating directory structure at $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/data"
mkdir -p "$INSTALL_DIR/history"
mkdir -p "$INSTALL_DIR/files"
mkdir -p "$INSTALL_DIR/avatars"
success "Directories created"

# ── Copy server files ─────────────────────────────────────────────────
info "Copying server files..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Server files should be in the same directory as this script
for f in server.js admin.js package.json; do
    if [ ! -f "$SCRIPT_DIR/$f" ]; then
        error "Missing required file: $f (must be in the same directory as install.sh)"
    fi
    cp "$SCRIPT_DIR/$f" "$INSTALL_DIR/$f"
done
success "Server files copied"

# ── npm install ───────────────────────────────────────────────────────
info "Installing Node.js dependencies..."
cd "$INSTALL_DIR"
npm install --quiet
success "Dependencies installed"

# ── Firebase setup ────────────────────────────────────────────────────
if [[ "$USE_FIREBASE" =~ ^[Yy]$ ]]; then
    info "Firebase push notifications enabled."
    if [ -f "$SCRIPT_DIR/firebase-adminsdk.json" ]; then
        cp "$SCRIPT_DIR/firebase-adminsdk.json" "$INSTALL_DIR/firebase-adminsdk.json"
        success "firebase-adminsdk.json copied"
    else
        warn "firebase-adminsdk.json not found in script directory."
        warn "Place your Firebase service account file at:"
        warn "  $INSTALL_DIR/firebase-adminsdk.json"
        warn "Push notifications will not work until this file is added."
    fi
else
    info "Skipping Firebase — push notifications disabled."
    # Create dummy firebase file so server.js require() doesn't crash
    echo '{"type":"service_account","project_id":"disabled"}' \
        > "$INSTALL_DIR/firebase-adminsdk.json"
fi

# ── Create config.json ────────────────────────────────────────────────
info "Creating config.json..."
cat > "$INSTALL_DIR/data/config.json" << EOF
{
  "turnUsername": "$TURN_USER",
  "turnPassword": "$TURN_PASS",
  "historyRetentionDays": 365,
  "fileRetentionDays": 90,
  "maxHistoryRequestDays": 30
}
EOF
success "config.json created"

# ── Initialize data files ─────────────────────────────────────────────
info "Initializing data files..."
[ -f "$INSTALL_DIR/data/users.json" ] || echo '{}' > "$INSTALL_DIR/data/users.json"
[ -f "$INSTALL_DIR/data/queue.json" ] || echo '{}' > "$INSTALL_DIR/data/queue.json"
[ -f "$INSTALL_DIR/data/lists.json" ] || echo '{}' > "$INSTALL_DIR/data/lists.json"
success "Data files initialized"

# ── Update server.js paths ────────────────────────────────────────────
info "Configuring server.js paths..."
sed -i "s|/opt/fshu/data/|$INSTALL_DIR/data/|g" "$INSTALL_DIR/server.js"
sed -i "s|/opt/fshu/history|$INSTALL_DIR/history|g" "$INSTALL_DIR/server.js"
sed -i "s|/opt/fshu/files|$INSTALL_DIR/files|g" "$INSTALL_DIR/server.js"
sed -i "s|/opt/fshu/avatars|$INSTALL_DIR/avatars|g" "$INSTALL_DIR/server.js"
sed -i "s|/opt/fshu/firebase-adminsdk.json|$INSTALL_DIR/firebase-adminsdk.json|g" "$INSTALL_DIR/server.js"
sed -i "s|df -h /opt/fshu|df -h $INSTALL_DIR|g" "$INSTALL_DIR/server.js"
success "server.js paths configured"

# ── Configure coturn ──────────────────────────────────────────────────
info "Configuring coturn..."

# Get public IP
PUBLIC_IP=$(curl -s https://api.ipify.org 2>/dev/null || \
            curl -s https://ifconfig.me 2>/dev/null || \
            hostname -I | awk '{print $1}')
PRIVATE_IP=$(hostname -I | awk '{print $1}')

TURN_CONF="/etc/turnserver_${INSTANCE_NAME}.conf"
cat > "$TURN_CONF" << EOF
listening-port=$TURN_PORT
listening-ip=$PRIVATE_IP
relay-ip=$PRIVATE_IP
external-ip=$PUBLIC_IP/$PRIVATE_IP
min-port=$TURN_MIN_PORT
max-port=$TURN_MAX_PORT
fingerprint
lt-cred-mech
user=$TURN_USER:$TURN_PASS
realm=${DOMAIN:-$PUBLIC_IP}
syslog
no-rfc5780
no-stun-backward-compatibility
response-origin-only-with-rfc5780
EOF

# Create systemd service for this coturn instance
cat > "/etc/systemd/system/coturn_${INSTANCE_NAME}.service" << EOF
[Unit]
Description=coturn TURN server for $INSTANCE_NAME
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/turnserver -c $TURN_CONF
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "coturn_${INSTANCE_NAME}"
systemctl start "coturn_${INSTANCE_NAME}"
success "coturn configured and started"

# ── Create systemd service ────────────────────────────────────────────
info "Creating systemd service..."
cat > "/etc/systemd/system/${INSTANCE_NAME}.service" << EOF
[Unit]
Description=4shu Messaging Server ($INSTANCE_NAME)
After=network.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=/usr/bin/node $INSTALL_DIR/server.js
Restart=always
RestartSec=5
Environment=NODE_ENV=production
Environment=PORT=$NODE_PORT

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$INSTANCE_NAME"
systemctl start "$INSTANCE_NAME"
success "Systemd service created and started"

# ── Configure nginx ───────────────────────────────────────────────────
info "Configuring nginx..."

if [ -n "$DOMAIN" ]; then
    # Check if nginx config for this domain already exists
    NGINX_CONF="/etc/nginx/sites-available/$DOMAIN"
    
    if [ -f "$NGINX_CONF" ]; then
        # Domain config exists — add new location block
        info "Adding WebSocket location to existing nginx config for $DOMAIN..."
        
        # Insert new location block before the last closing brace of the ssl server block
        LOCATION_BLOCK="
    # 4shu $INSTANCE_NAME WebSocket
    location $WS_PATH {
        proxy_pass http://127.0.0.1:$NODE_PORT/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \"upgrade\";
        proxy_set_header Host \$host;
        proxy_read_timeout 86400;
    }"
        
        # Check if this path already exists in config
        if grep -q "location $WS_PATH" "$NGINX_CONF"; then
            warn "Location $WS_PATH already exists in nginx config — skipping"
        else
            # Add before the last closing brace
            sed -i "$ i\\$LOCATION_BLOCK" "$NGINX_CONF"
            success "Location block added to existing nginx config"
        fi
    else
        # Create new nginx config for this domain
        cat > "$NGINX_CONF" << EOF2
server {
    listen 80;
    server_name $DOMAIN www.$DOMAIN;
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl;
    server_name $DOMAIN;

    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;

    # 4shu $INSTANCE_NAME WebSocket
    location $WS_PATH {
        proxy_pass http://127.0.0.1:$NODE_PORT/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_read_timeout 86400;
    }
}
EOF2
        ln -sf "$NGINX_CONF" "/etc/nginx/sites-enabled/$DOMAIN"
    fi
    
    nginx -t && systemctl reload nginx
    success "nginx configured"
    
    # TLS certificate
    if [ ! -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
        info "Obtaining TLS certificate from Let's Encrypt..."
        certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos \
            --email "admin@$DOMAIN" --redirect || \
            warn "Certbot failed — run manually: certbot --nginx -d $DOMAIN"
    else
        success "TLS certificate already exists for $DOMAIN"
    fi
else
    warn "No domain provided — skipping TLS setup"
    warn "App can connect via ws://$PUBLIC_IP:$NODE_PORT for testing"
    
    # Create simple nginx config without TLS for testing
    NGINX_CONF="/etc/nginx/sites-available/${INSTANCE_NAME}_nossl"
    cat > "$NGINX_CONF" << EOF2
server {
    listen 8080;
    server_name _;

    location $WS_PATH {
        proxy_pass http://127.0.0.1:$NODE_PORT/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_read_timeout 86400;
    }
}
EOF2
    ln -sf "$NGINX_CONF" "/etc/nginx/sites-enabled/${INSTANCE_NAME}_nossl"
    nginx -t && systemctl reload nginx
fi

# ── Firewall ──────────────────────────────────────────────────────────
info "Configuring firewall..."
if command -v ufw &>/dev/null; then
    ufw allow 443/tcp comment "4shu $INSTANCE_NAME HTTPS" 2>/dev/null || true
    ufw allow 80/tcp comment "4shu $INSTANCE_NAME HTTP" 2>/dev/null || true
    ufw allow "$TURN_PORT/tcp" comment "4shu $INSTANCE_NAME TURN TCP" 2>/dev/null || true
    ufw allow "$TURN_PORT/udp" comment "4shu $INSTANCE_NAME TURN UDP" 2>/dev/null || true
    ufw allow "$TURN_MIN_PORT:$TURN_MAX_PORT/udp" \
        comment "4shu $INSTANCE_NAME TURN relay" 2>/dev/null || true
    success "Firewall rules added"
else
    warn "ufw not found — please configure firewall manually"
    warn "Required ports: 443/tcp, 80/tcp, $TURN_PORT/tcp+udp, ${TURN_MIN_PORT}-${TURN_MAX_PORT}/udp"
fi

# ── Create first admin user ───────────────────────────────────────────
info "Creating admin user '$ADMIN_USER'..."
sleep 2  # Wait for service to be ready
node "$INSTALL_DIR/admin.js" add "$ADMIN_USER" "$ADMIN_PASS" && \
    node "$INSTALL_DIR/admin.js" admin "$ADMIN_USER" true 2>/dev/null || true
success "Admin user created"

# ── Final summary ─────────────────────────────────────────────────────
echo ""
echo "  ─────────────────────────────────────────"
echo -e "  ${GREEN}4shu installation complete!${NC}"
echo "  ─────────────────────────────────────────"
echo ""
echo "  Instance:     $INSTANCE_NAME"
echo "  Directory:    $INSTALL_DIR"
if [ -n "$DOMAIN" ]; then
echo "  Server URL:   wss://$DOMAIN$WS_PATH"
else
echo "  Server URL:   ws://$PUBLIC_IP:8080$WS_PATH (no TLS)"
fi
echo "  TURN server:  $PUBLIC_IP:$TURN_PORT"
echo "  TURN user:    $TURN_USER"
echo "  TURN pass:    $TURN_PASS"
echo "  Admin user:   $ADMIN_USER"
echo ""
echo "  Services:"
echo "    systemctl status $INSTANCE_NAME"
echo "    systemctl status coturn_$INSTANCE_NAME"
echo ""
echo "  Manage users:"
echo "    node $INSTALL_DIR/admin.js list"
echo "    node $INSTALL_DIR/admin.js add <username> <password>"
echo "    node $INSTALL_DIR/admin.js remove <username>"
echo ""
if [[ "$USE_FIREBASE" =~ ^[Yy]$ ]] && [ ! -f "$INSTALL_DIR/firebase-adminsdk.json" ]; then
echo -e "  ${YELLOW}ACTION REQUIRED:${NC}"
echo "    Place firebase-adminsdk.json at:"
echo "    $INSTALL_DIR/firebase-adminsdk.json"
echo "    Then: systemctl restart $INSTANCE_NAME"
echo ""
fi
echo "  ─────────────────────────────────────────"
echo ""
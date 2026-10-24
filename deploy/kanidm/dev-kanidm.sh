#!/usr/bin/env bash
# Throwaway Kanidm for local YNAY development and end-to-end login tests.
#
#   deploy/kanidm/dev-kanidm.sh start   # (default) start kanidmd on https://localhost:8443 and provision it
#   deploy/kanidm/dev-kanidm.sh stop    # stop it
#   deploy/kanidm/dev-kanidm.sh reset   # stop and delete all state
#   deploy/kanidm/dev-kanidm.sh env     # print the YNAY_* / JAVA_TOOL_OPTIONS exports for the backend
#
# What "start" does:
#   * writes a kanidmd config + self-signed CA/cert (kanidmd cert-generate) into $STATE_DIR
#   * starts kanidmd in the background (pid in $STATE_DIR/kanidmd.pid, log in $STATE_DIR/kanidmd.log)
#   * recovers the idm_admin account (kanidmd recover-account)
#   * creates person "$DEV_USER" with a password, group "ynay_users", oauth2 client "ynay"
#     (redirect http://localhost:8080/login/oauth2/code/kanidm, scope map openid profile email)
#   * builds a Java truststore ($STATE_DIR/truststore.p12) = JVM default CAs + the dev CA
#   * writes $STATE_DIR/ynay.env with everything the backend needs and prints it
#
# kanidmd comes from $KANIDMD / PATH, or is fetched with nix from the flake's pinned nixpkgs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_DIR="${YNAY_KANIDM_DEV_DIR:-$REPO_ROOT/.kanidm-dev}"
PORT="${YNAY_KANIDM_DEV_PORT:-8443}"
ORIGIN="https://localhost:$PORT"
APP_URL="${YNAY_PUBLIC_URL:-http://localhost:8080}"
CLIENT_ID="${YNAY_KANIDM_CLIENT_ID:-ynay}"
DEV_USER="${YNAY_DEV_USER:-alice}"
DEV_USER_DISPLAY="${YNAY_DEV_USER_DISPLAY:-Alice Example}"
DEV_USER_MAIL="${YNAY_DEV_USER_MAIL:-alice@example.com}"

log() { printf '\033[1;34m[dev-kanidm]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[dev-kanidm]\033[0m %s\n' "$*" >&2; exit 1; }

find_kanidm() {
    if [[ -n "${KANIDMD:-}" ]]; then
        KANIDM_BIN_DIR="$(dirname "$KANIDMD")"
    elif command -v kanidmd >/dev/null 2>&1 && command -v kanidm >/dev/null 2>&1; then
        KANIDM_BIN_DIR="$(dirname "$(command -v kanidmd)")"
    elif command -v nix >/dev/null 2>&1; then
        log "kanidmd not on PATH; fetching kanidm_1_11 from the flake's nixpkgs (one-time download)"
        local out
        out="$(nix build --inputs-from "$REPO_ROOT" nixpkgs#kanidm_1_11 --no-link --print-out-paths)"
        KANIDM_BIN_DIR="$out/bin"
    else
        die "kanidmd not found. Install kanidm (e.g. 'nix shell nixpkgs#kanidm_1_11') or set KANIDMD=/path/to/kanidmd"
    fi
    KANIDMD_BIN="$KANIDM_BIN_DIR/kanidmd"
    KANIDM_CLI="$KANIDM_BIN_DIR/kanidm"
    [[ -x "$KANIDMD_BIN" && -x "$KANIDM_CLI" ]] || die "kanidmd/kanidm not executable in $KANIDM_BIN_DIR"
}

pid_running() {
    [[ -f "$STATE_DIR/kanidmd.pid" ]] && kill -0 "$(cat "$STATE_DIR/kanidmd.pid")" 2>/dev/null
}

# The kanidm CLI keeps its session tokens under $HOME/.cache; isolate it in the state dir.
cli() {
    HOME="$STATE_DIR/home" KANIDM_URL="$ORIGIN" KANIDM_CA_PATH="$STATE_DIR/ca.pem" \
        "$KANIDM_CLI" "$@"
}

write_config() {
    mkdir -p "$STATE_DIR/home"
    cat >"$STATE_DIR/server.toml" <<EOF
version = "2"
bindaddress = "127.0.0.1:$PORT"
db_path = "$STATE_DIR/kanidm.db"
tls_chain = "$STATE_DIR/chain.pem"
tls_key = "$STATE_DIR/key.pem"
domain = "localhost"
origin = "$ORIGIN"
adminbindpath = "$STATE_DIR/kanidmd.sock"
log_level = "info"
EOF
}

recover() { # recover <account> -> prints the new password
    local out
    out="$("$KANIDMD_BIN" scripting recover-account "$1" -c "$STATE_DIR/server.toml" 2>/dev/null)"
    # {"status":"success","output":"<password>"}
    sed -n 's/.*"output":"\([^"]*\)".*/\1/p' <<<"$out" | tail -n1
}

start() {
    find_kanidm
    if pid_running; then
        log "kanidmd already running (pid $(cat "$STATE_DIR/kanidmd.pid"))"
    else
        mkdir -p "$STATE_DIR"
        write_config
        if [[ ! -f "$STATE_DIR/chain.pem" ]]; then
            log "generating self-signed CA + certificate"
            "$KANIDMD_BIN" cert-generate -c "$STATE_DIR/server.toml" >>"$STATE_DIR/kanidmd.log" 2>&1
        fi
        log "starting kanidmd on $ORIGIN"
        nohup "$KANIDMD_BIN" server -c "$STATE_DIR/server.toml" >>"$STATE_DIR/kanidmd.log" 2>&1 &
        echo $! >"$STATE_DIR/kanidmd.pid"
    fi
    [[ -f "$STATE_DIR/ca.pem" ]] || die "expected $STATE_DIR/ca.pem from cert-generate"

    for _ in $(seq 1 60); do
        if curl -fsS --cacert "$STATE_DIR/ca.pem" "$ORIGIN/status" >/dev/null 2>&1; then break; fi
        pid_running || die "kanidmd exited; see $STATE_DIR/kanidmd.log"
        sleep 1
    done
    curl -fsS --cacert "$STATE_DIR/ca.pem" "$ORIGIN/status" >/dev/null || die "kanidmd not answering on $ORIGIN"

    provision
    build_truststore
    write_env
    log "ready. Log in at $APP_URL as '$DEV_USER' / password in $STATE_DIR/ynay.env (YNAY_DEV_USER_PASSWORD)"
    cat "$STATE_DIR/ynay.env"
}

provision() {
    if [[ -f "$STATE_DIR/provisioned" ]]; then
        log "already provisioned"
        return
    fi
    log "recovering idm_admin"
    local idm_pw
    idm_pw="$(recover idm_admin)"
    [[ -n "$idm_pw" ]] || die "could not recover idm_admin (see $STATE_DIR/kanidmd.log)"
    echo "$idm_pw" >"$STATE_DIR/idm_admin.password"

    KANIDM_PASSWORD="$idm_pw" cli login -D idm_admin >/dev/null

    log "creating person $DEV_USER, group ynay_users, oauth2 client $CLIENT_ID"
    cli person create "$DEV_USER" "$DEV_USER_DISPLAY" -D idm_admin >/dev/null
    cli person update "$DEV_USER" --mail "$DEV_USER_MAIL" -D idm_admin >/dev/null
    cli group create ynay_users -D idm_admin >/dev/null
    cli group add-members ynay_users "$DEV_USER" -D idm_admin >/dev/null

    cli system oauth2 create "$CLIENT_ID" "YNAY (dev)" "$APP_URL" -D idm_admin >/dev/null
    cli system oauth2 add-redirect-url "$CLIENT_ID" "$APP_URL/login/oauth2/code/kanidm" -D idm_admin >/dev/null
    cli system oauth2 update-scope-map "$CLIENT_ID" ynay_users openid profile email -D idm_admin >/dev/null
    # Tokens should carry "alice" rather than "alice@localhost" as preferred_username.
    cli system oauth2 prefer-short-username "$CLIENT_ID" -D idm_admin >/dev/null

    # A password for the test person: recover-account works for any account and returns a
    # generated password (password-only credentials are allowed by the default account policy).
    local user_pw
    user_pw="$(recover "$DEV_USER")"
    [[ -n "$user_pw" ]] || die "could not set a password for $DEV_USER"
    echo "$user_pw" >"$STATE_DIR/user.password"

    cli system oauth2 show-basic-secret "$CLIENT_ID" -D idm_admin | tail -n1 | tr -d '[:space:]' >"$STATE_DIR/client.secret"
    touch "$STATE_DIR/provisioned"
}

build_truststore() {
    local ts="$STATE_DIR/truststore.p12"
    [[ -f "$ts" ]] && return
    command -v keytool >/dev/null 2>&1 || { log "keytool not found (run inside 'nix develop'); skipping truststore"; return; }
    local base="${YNAY_BASE_TRUSTSTORE:-}"
    if [[ -z "$base" ]]; then
        # Respect a truststore already configured via JAVA_TOOL_OPTIONS (e.g. corporate proxies).
        base="$(sed -n 's/.*-Djavax\.net\.ssl\.trustStore=\([^ ]*\).*/\1/p' <<<"${JAVA_TOOL_OPTIONS:-}")"
    fi
    if [[ -z "$base" && -n "${JAVA_HOME:-}" ]]; then base="$JAVA_HOME/lib/security/cacerts"; fi
    if [[ -n "$base" && -f "$base" ]]; then
        keytool -importkeystore -noprompt -srckeystore "$base" -srcstorepass changeit \
            -destkeystore "$ts" -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1 || true
    fi
    keytool -importcert -noprompt -alias ynay-dev-kanidm-ca -file "$STATE_DIR/ca.pem" \
        -keystore "$ts" -storetype PKCS12 -storepass changeit >/dev/null
    log "truststore with dev CA: $ts"
}

write_env() {
    local java_opts="${JAVA_TOOL_OPTIONS:-}"
    java_opts="$(sed -e 's/-Djavax\.net\.ssl\.trustStore=[^ ]*//' -e 's/-Djavax\.net\.ssl\.trustStoreType=[^ ]*//' \
        -e 's/-Djavax\.net\.ssl\.trustStorePassword=[^ ]*//' <<<"$java_opts")"
    java_opts="-Djavax.net.ssl.trustStore=$STATE_DIR/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit $java_opts"
    cat >"$STATE_DIR/ynay.env" <<EOF
export YNAY_PUBLIC_URL='$APP_URL'
export YNAY_KANIDM_URL='$ORIGIN'
export YNAY_KANIDM_CLIENT_ID='$CLIENT_ID'
export YNAY_KANIDM_CLIENT_SECRET='$(cat "$STATE_DIR/client.secret")'
export JAVA_TOOL_OPTIONS='${java_opts% }'
# test login for the browser:
export YNAY_DEV_USER='$DEV_USER'
export YNAY_DEV_USER_PASSWORD='$(cat "$STATE_DIR/user.password")'
EOF
}

stop() {
    if pid_running; then
        log "stopping kanidmd (pid $(cat "$STATE_DIR/kanidmd.pid"))"
        kill "$(cat "$STATE_DIR/kanidmd.pid")"
    fi
    rm -f "$STATE_DIR/kanidmd.pid"
}

case "${1:-start}" in
    start) start ;;
    stop) stop ;;
    reset) stop; rm -rf "$STATE_DIR"; log "removed $STATE_DIR" ;;
    env) cat "$STATE_DIR/ynay.env" ;;
    *) die "usage: $0 [start|stop|reset|env]" ;;
esac

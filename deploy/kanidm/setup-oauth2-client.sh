#!/usr/bin/env bash
# Registers YNAY as an OAuth2/OIDC client in an existing (production) Kanidm.
#
#   KANIDM_URL=https://idm.example.com YNAY_URL=https://ynay.example.com \
#       deploy/kanidm/setup-oauth2-client.sh
#
# Optional environment:
#   CLIENT_ID      OAuth2 client name                         (default: ynay)
#   GROUP          Kanidm group allowed to use YNAY           (default: ynay_users)
#   MEMBERS        space separated accounts to add to GROUP   (default: none)
#   ADMIN          account used for the changes               (default: idm_admin)
#   SHORT_USERNAME "true" => preferred_username "alice" instead of "alice@idm.example.com"
#                  (default: true; YNAY shows the username and uses it for budget sharing)
#   KANIDM_CA_PATH CA file if Kanidm uses a private CA
#
# Each step is idempotent enough to re-run: "already exists" errors are ignored.
# At the end the client secret is printed; put it into YNAY_KANIDM_CLIENT_SECRET.
set -euo pipefail

: "${KANIDM_URL:?set KANIDM_URL, e.g. https://idm.example.com}"
: "${YNAY_URL:?set YNAY_URL (= YNAY_PUBLIC_URL), e.g. https://ynay.example.com}"
CLIENT_ID="${CLIENT_ID:-ynay}"
GROUP="${GROUP:-ynay_users}"
ADMIN="${ADMIN:-idm_admin}"
SHORT_USERNAME="${SHORT_USERNAME:-true}"
YNAY_URL="${YNAY_URL%/}"
export KANIDM_URL

command -v kanidm >/dev/null || { echo "kanidm CLI not found (nix shell nixpkgs#kanidm_1_11)" >&2; exit 1; }

k() { kanidm "$@" -D "$ADMIN"; }
try() { "$@" || echo "  (ignored: probably exists already)" >&2; }

echo "== logging in as $ADMIN (you will be asked for the password)"
kanidm login -D "$ADMIN"

echo "== group $GROUP"
try k group create "$GROUP"
for m in ${MEMBERS:-}; do
    k group add-members "$GROUP" "$m"
done

echo "== oauth2 client $CLIENT_ID (landing page $YNAY_URL)"
try k system oauth2 create "$CLIENT_ID" "YNAY" "$YNAY_URL"
# Must match exactly what YNAY sends: <YNAY_PUBLIC_URL>/login/oauth2/code/kanidm
k system oauth2 add-redirect-url "$CLIENT_ID" "$YNAY_URL/login/oauth2/code/kanidm"
k system oauth2 update-scope-map "$CLIENT_ID" "$GROUP" openid profile email
if [[ "$SHORT_USERNAME" == "true" ]]; then
    k system oauth2 prefer-short-username "$CLIENT_ID"
fi
# PKCE stays enabled (YNAY always sends S256) and ID tokens stay ES256 (YNAY's default).

echo "== client secret (YNAY_KANIDM_CLIENT_SECRET):"
k system oauth2 show-basic-secret "$CLIENT_ID"

cat <<EOF

Configure YNAY with:
  YNAY_PUBLIC_URL=$YNAY_URL
  YNAY_KANIDM_URL=$KANIDM_URL
  YNAY_KANIDM_CLIENT_ID=$CLIENT_ID
  YNAY_KANIDM_CLIENT_SECRET=<secret above>
OIDC issuer: $KANIDM_URL/oauth2/openid/$CLIENT_ID
EOF

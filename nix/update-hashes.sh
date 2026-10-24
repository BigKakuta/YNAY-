#!/usr/bin/env bash
# Refresh the dependency pins used by the Nix build:
#
#   nix/frontend-npm-deps.hash  <- prefetch-npm-deps frontend/package-lock.json
#   nix/backend-deps.json       <- mitm-cache recording of a Gradle dependency resolution
#
# Usage (from anywhere inside the repository):
#   nix/update-hashes.sh            # both, frontend first (the backend build bundles the SPA)
#   nix/update-hashes.sh frontend
#   nix/update-hashes.sh backend
#   nix run .#update-hashes -- backend
#
# Flakes only see files known to git: `git add` new files (e.g. a new package-lock.json) first.
# Set NO_VERIFY=1 to skip the final `nix build .#backend`.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
what="${1:-all}"

log() { printf '\033[1m==> %s\033[0m\n' "$*" >&2; }

# Retry wrapper: Maven Central and the npm registry occasionally answer HTTP 429.
retry() {
  local n=1 max=4
  until "$@"; do
    if ((n >= max)); then return 1; fi
    log "attempt $n failed, retrying in $((n * 15))s: $*"
    sleep $((n * 15))
    n=$((n + 1))
  done
}

update_frontend() {
  if [[ ! -f frontend/package-lock.json ]]; then
    log "frontend/package-lock.json not found; skipping frontend hash"
    return
  fi
  log "Computing npm dependency hash for frontend/package-lock.json"
  local hash
  hash="$(retry nix run --inputs-from "$root" nixpkgs#prefetch-npm-deps -- frontend/package-lock.json)"
  echo "$hash" >nix/frontend-npm-deps.hash
  log "nix/frontend-npm-deps.hash = $hash"
}

update_backend() {
  log "Building the Gradle dependency update script"
  local script
  script="$(nix build --no-link --print-out-paths "$root#backend.mitmCache.updateScript")"
  # The script runs Gradle in a bubblewrap sandbox that talks to the nix daemon. Single-user Nix
  # installs (no daemon socket) cannot build from inside it, so run without bwrap there.
  if [[ -z "${USE_BWRAP:-}" && ! -S /nix/var/nix/daemon-socket/socket ]]; then
    export USE_BWRAP=0
  fi
  # mitm-cache fetches upstream itself; behind a TLS-intercepting proxy point SSL_CERT_FILE at
  # the proxy's CA bundle before running this script.
  log "Recording Gradle dependencies into nix/backend-deps.json (takes a minute or two)"
  "$script"
  log "nix/backend-deps.json updated ($(wc -c <nix/backend-deps.json) bytes)"
}

verify() {
  [[ -n "${NO_VERIFY:-}" ]] && return
  git add -N nix/backend-deps.json nix/frontend-npm-deps.hash 2>/dev/null || true
  log "Verifying: nix build .#backend"
  # --keep-going + retry: lets rate-limited artifact downloads succeed on a later pass.
  retry nix build --no-link --keep-going "$root#backend"
  log "OK"
}

case "$what" in
  frontend) update_frontend ;;
  backend) update_backend ;;
  all)
    update_frontend
    update_backend
    ;;
  *)
    echo "usage: $0 [all|frontend|backend]" >&2
    exit 2
    ;;
esac
verify

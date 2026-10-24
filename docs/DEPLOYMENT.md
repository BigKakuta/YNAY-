# Deploying YNAY

YNAY is one JVM process (Spring Boot, with the React SPA bundled into the jar) and a PostgreSQL
database. Login goes through Kanidm (OIDC). Everything is built reproducibly by the Nix flake;
there is no registry image and no Dockerfile.

| `nix build .#…`      | result                                                                   |
|----------------------|--------------------------------------------------------------------------|
| `default` / `backend`| `bin/ynay` (wrapper on JDK 25) + `share/ynay/ynay.jar`, SPA included      |
| `frontend`           | the built SPA (`frontend/dist`)                                          |
| `dockerImage`        | `docker load`-able image `ynay:<version>` (≈85 MB compressed, ≈200 MB unpacked) |

Other outputs: `nixosModules.default` (`services.ynay`), `overlays.default` (`pkgs.ynay`),
`apps.default` (`nix run .` starts the server), `apps.update-hashes`, `checks` (package builds and
a NixOS VM test), `devShells.default`.

The flake pins nixpkgs to the `nixos-26.05` channel tarball, which is equivalent to
`github:NixOS/nixpkgs/nixos-26.05`. You can switch the input URL to either form.

Contents: [Configuration](#configuration) · [Option A: Docker Compose](#option-a-docker-compose) ·
[Reverse proxy](#reverse-proxy-and-tls) · [Option B: NixOS](#option-b-nixos-module) ·
[Kanidm](#kanidm) · [Backups](#backups) · [Upgrades](#upgrades) ·
[Dependency hashes](#regenerating-dependency-hashes) · [Troubleshooting](#troubleshooting)

## Configuration

The application reads environment variables. The full table is in
[ARCHITECTURE.md](ARCHITECTURE.md#configuration-environment-variables). These are the important ones:

| variable | notes |
|---|---|
| `YNAY_DB_URL`, `YNAY_DB_USER`, `YNAY_DB_PASSWORD` | Postgres connection (JDBC URL) |
| `YNAY_PUBLIC_URL` | public origin, e.g. `https://ynay.example.com`. It must be exactly what browsers use, because it builds the OIDC redirect URI |
| `YNAY_KANIDM_URL`, `YNAY_KANIDM_CLIENT_ID`, `YNAY_KANIDM_CLIENT_SECRET` | OIDC client |
| `YNAY_AGENT_PROVIDER`, `YNAY_AGENT_API_KEY`, `YNAY_AGENT_MODEL`, `YNAY_AGENT_BASE_URL` | optional import agent |

`GET /actuator/health` is public and returns `{"status":"UP"}` once the app is running.

## Option A: Docker Compose

You need Nix (with flakes) on the build machine and Docker or Podman on the server. They can be
the same machine.

```sh
git clone https://github.com/BigKakuta/YNAY-.git ynay && cd ynay

# 1. Build and load the image (tag = version from backend/build.gradle.kts)
nix build .#dockerImage
docker load < result                       # -> Loaded image: ynay:0.1.0
# building elsewhere?  nix build .#dockerImage && ssh server docker load < result

# 2. Configure
cp deploy/.env.example deploy/.env
$EDITOR deploy/.env                        # YNAY_DB_PASSWORD, YNAY_PUBLIC_URL, Kanidm settings
chmod 600 deploy/.env

# 3. Start
docker compose -f deploy/docker-compose.yml up -d
curl -s http://127.0.0.1:8080/actuator/health   # {"groups":["liveness","readiness"],"status":"UP"}
```

`deploy/docker-compose.yml` runs:

* `postgres` (`postgres:17-alpine`). Data lives in the named volume `ynay_postgres-data`. Compose
  starts the app only after `pg_isready` succeeds.
* `ynay` (`ynay:${YNAY_VERSION}`, `pull_policy: never`, `restart: unless-stopped`). Every
  variable in `deploy/.env` is passed through. `YNAY_DB_URL` always points at the `postgres`
  service.

About the image:

* It runs as the non-root user `ynay` (uid/gid 1000). `/tmp` and `/home/ynay` are writable. The
  app keeps no local state; everything is in Postgres.
* It contains a jlink'ed JRE 25 with only the modules the app needs, plus CA certificates and
  tzdata. There is no shell and no package manager.
* Extra JVM flags go in `JDK_JAVA_OPTIONS` in `.env` (for example `-Xmx512m`). The defaults are
  `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, so the heap follows the container memory
  limit.
* If Kanidm uses a private CA, mount a truststore and point the JVM at it:

  ```yaml
  # docker-compose.override.yml
  services:
    ynay:
      volumes: ["./truststore.p12:/certs/truststore.p12:ro"]
  ```
  ```sh
  # .env
  JDK_JAVA_OPTIONS=-Djavax.net.ssl.trustStore=/certs/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit
  # create it: keytool -importcert -noprompt -alias homelab-ca -file ca.crt -keystore truststore.p12 -storepass changeit
  # (add -cacerts certificates too if the app must also reach public HTTPS APIs, e.g. the agent provider)
  ```

### Reverse proxy and TLS

YNAY speaks plain HTTP on port 8080. Put a TLS-terminating proxy in front of it. The proxy must
forward the original host and scheme: the app sets `server.forward-headers-strategy=framework`, so
it trusts `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For`. Set
`YNAY_PUBLIC_URL=https://ynay.example.com` to the same origin. The proxy must not be bypassable,
so publish the port only on loopback (`YNAY_BIND=127.0.0.1`) or on an internal Docker network.

**Caddy** (it sends the forwarded headers automatically):

```caddyfile
ynay.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8080 {
        health_uri /actuator/health
    }
}
```

**Traefik** (Docker provider). Drop the `ports:` mapping and add the service to Traefik's network:

```yaml
# deploy/docker-compose.override.yml
services:
  ynay:
    ports: !reset []
    networks: [default, traefik]
    labels:
      traefik.enable: "true"
      traefik.http.routers.ynay.rule: Host(`ynay.example.com`)
      traefik.http.routers.ynay.entrypoints: websecure
      traefik.http.routers.ynay.tls.certresolver: letsencrypt
      traefik.http.services.ynay.loadbalancer.server.port: "8080"
      traefik.http.services.ynay.loadbalancer.healthcheck.path: /actuator/health
networks:
  traefik:
    external: true
```

**nginx**:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
}
```

## Option B: NixOS module

```nix
# flake.nix of your NixOS configuration
{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
  inputs.ynay.url = "github:BigKakuta/YNAY-";

  outputs = { nixpkgs, ynay, ... }: {
    nixosConfigurations.homelab = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [ ynay.nixosModules.default ./configuration.nix ];
    };
  };
}
```

```nix
# configuration.nix
{
  services.ynay = {
    enable = true;
    listenAddress = "127.0.0.1";          # only reachable through the reverse proxy
    port = 8080;
    publicUrl = "https://ynay.example.com";

    # database.createLocally = true (default): local PostgreSQL, role + db "ynay"
    kanidm = {
      url = "https://idm.example.com";
      clientId = "ynay";
      clientSecretFile = "/run/secrets/ynay-kanidm-secret";   # sops-nix / agenix / manual 0400 file
    };

    agent = {                               # optional
      provider = "anthropic";
      apiKeyFile = "/run/secrets/ynay-anthropic-key";
      # provider = "openai"; baseUrl = "http://127.0.0.1:11434/v1"; model = "qwen3:14b";
    };
    # environmentFile = "/run/secrets/ynay.env";  # extra KEY=value lines (secrets as one file)
    # javaOptions = [ "-Xmx512m" ];
  };

  services.caddy = {
    enable = true;
    virtualHosts."ynay.example.com".extraConfig = "reverse_proxy 127.0.0.1:8080";
  };
  networking.firewall.allowedTCPPorts = [ 80 443 ];

  services.postgresqlBackup = { enable = true; databases = [ "ynay" ]; };  # see Backups
}
```

How it works:

* `ynay.service` runs with `DynamicUser`, `ProtectSystem=strict`, `PrivateTmp`,
  `NoNewPrivileges`, no capabilities, a syscall filter and `RestrictAddressFamilies`.
  `MemoryDenyWriteExecute` is not set because the JIT needs it.
* Secrets (`*File` options) are passed with systemd `LoadCredential`. A small start script exports
  them as `YNAY_DB_PASSWORD`, `YNAY_KANIDM_CLIENT_SECRET` and `YNAY_AGENT_API_KEY` just before it
  execs the JVM. They never enter the Nix store or the unit file.
* Local database: pgjdbc cannot use unix sockets without an extra native library, so the app
  connects over TCP to `localhost` with SCRAM password auth for the `ynay` role. The oneshot
  `ynay-db-setup.service` sets that password on every start. It uses `database.passwordFile` if you
  set it. Otherwise it generates a random 40-character password once into
  `/var/lib/ynay-db/password` (0600, owned by `postgres`). Peer or `trust` auth is not used.
* Remote database: set `database.createLocally = false`, `database.url`, `database.user` and
  `database.passwordFile`.
* The package defaults to this flake's build for the host system. To use another build, set
  `services.ynay.package`.

Check it with `systemctl status ynay`, `journalctl -u ynay -f` and
`curl -s localhost:8080/actuator/health`.

The VM test in `nix/test.nix` boots this module with a local Postgres. It asserts that health is
`UP` and that `/api/me` returns 401:

```sh
nix build .#checks.x86_64-linux.nixos -L     # needs /dev/kvm to run at normal speed
```

## Kanidm

Register YNAY as an OAuth2 client in Kanidm. The scripts and details are in `deploy/kanidm/`. The
redirect URL is `${YNAY_PUBLIC_URL}/login/oauth2/code/kanidm`. Put the client secret
(`kanidm system oauth2 show-basic-secret ynay`) in `YNAY_KANIDM_CLIENT_SECRET` (compose) or
`kanidm.clientSecretFile` (NixOS). The `kanidm` CLI is in the dev shell (`nix develop`).

## Backups

All state is in the `ynay` Postgres database. Use `pg_dump` in custom format and keep copies off
the host (restic, borg, rsync, ...).

**Compose**: a host crontab entry for a nightly dump with 14 days of retention:

```cron
# m h dom mon dow  command
15 3 * * *  cd /srv/ynay && docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U ynay -Fc ynay > backups/ynay-$(date +\%F).dump && find backups -name 'ynay-*.dump' -mtime +14 -delete
```

Restore into a fresh stack:

```sh
docker compose -f deploy/docker-compose.yml up -d postgres
docker compose -f deploy/docker-compose.yml exec -T postgres pg_restore -U ynay -d ynay --clean --if-exists < backups/ynay-2026-10-24.dump
docker compose -f deploy/docker-compose.yml up -d
```

**NixOS**: `services.postgresqlBackup = { enable = true; databases = [ "ynay" ]; };` writes
`/var/backup/postgresql/ynay.sql.gz` every night. To restore:

```sh
systemctl stop ynay
sudo -u postgres dropdb ynay && sudo -u postgres createdb -O ynay ynay
zcat /var/backup/postgresql/ynay.sql.gz | sudo -u postgres psql -d ynay
systemctl start ynay
```

## Upgrades

Database migrations (Flyway) run automatically when the app starts. Take a backup first.

**Compose**:

```sh
git pull
nix build .#dockerImage && docker load < result
sed -i "s/^YNAY_VERSION=.*/YNAY_VERSION=$(nix eval --raw .#backend.version)/" deploy/.env
docker compose -f deploy/docker-compose.yml up -d     # recreates ynay with the new tag
docker image prune                                     # optional: drop old tags
```

**NixOS**: `nix flake update ynay && nixos-rebuild switch`.

PostgreSQL major versions: compose pins `postgres:17-alpine`. For a major upgrade, dump with the
old version and restore into a new volume. Don't just change the tag on an existing volume. On
NixOS, follow the `services.postgresql` upgrade procedure in the NixOS manual.

## Regenerating dependency hashes

The Nix build is offline and pinned, so dependency changes need two lockfiles refreshed:

| file | refresh when |
|---|---|
| `nix/frontend-npm-deps.hash` | `frontend/package-lock.json` changes |
| `nix/backend-deps.json` | dependencies or plugins in `backend/build.gradle.kts` change |

Run one command from the repository root:

```sh
nix/update-hashes.sh            # both (frontend first, because the backend bundles the SPA)
nix/update-hashes.sh frontend   # or: backend
nix run .#update-hashes          # same thing via the flake
```

The script ends with `nix build .#backend` to check the result. Commit the changed files. The
flake only sees files that git knows about, so `git add` a new `package-lock.json` first.

How it works:

* The frontend hash comes from `prefetch-npm-deps frontend/package-lock.json`.
* The backend lockfile comes from the nixpkgs Gradle `mitmCache.updateScript`
  (`nix build .#backend.mitmCache.updateScript`). It runs Gradle through a recording proxy and
  stores the hash of every fetched artifact.
* On single-user Nix (no daemon socket) the script sets `USE_BWRAP=0` automatically.
* Behind a TLS-intercepting proxy, export `SSL_CERT_FILE=<proxy CA bundle>` first, because
  mitm-cache fetches upstream itself.
* Maven Central sometimes answers HTTP 429. The verification build retries with `--keep-going`.

`build.gradle.kts` is the single source of the version. The flake reads it from there for package
and image tags.

## Troubleshooting

* **`hash mismatch in fixed-output derivation … ynay-frontend-<version>-npm-deps`**: run
  `nix/update-hashes.sh frontend`.
* **Gradle `Could not resolve …` / `--offline` errors in `nix build`**: a dependency is missing
  from `nix/backend-deps.json`. Run `nix/update-hashes.sh backend`.
* **`NoClassDefFoundError: java/…` or `jdk/…` only in the Docker image**: a new library needs a JDK
  module that the minimal JRE leaves out. Add it to the `modules` list in `nix/docker.nix`.
* **Login loops or redirect URI mismatch**: `YNAY_PUBLIC_URL` doesn't match the browser origin, or
  the proxy doesn't send `X-Forwarded-Proto`/`X-Forwarded-Host`.
* **`PKIX path building failed` contacting Kanidm**: the private CA is missing from the truststore
  (see Option A).

# Kanidm login for YNAY

YNAY has **no local accounts and no passwords**. People sign in with [Kanidm](https://kanidm.com)
through OpenID Connect (authorization code flow with PKCE). MCP clients use personal API tokens
(`ynay_pat_...`) created in the web UI, or optionally Kanidm access tokens.

```
Browser ──/oauth2/authorization/kanidm──▶ Kanidm (login, consent) ──code──▶ /login/oauth2/code/kanidm
                                                                                │ token + userinfo
                                                                                ▼
                                                    app_user upserted by (issuer, subject); session cookie
MCP client ──Authorization: Bearer ynay_pat_…──▶ /mcp      (or a Kanidm access token, via introspection)
```

## 1. Register YNAY in Kanidm (homelab / production)

You need the `kanidm` CLI (`nix shell nixpkgs#kanidm_1_11`) and the `idm_admin` account.
`deploy/kanidm/setup-oauth2-client.sh` runs exactly these steps:

```sh
export KANIDM_URL=https://idm.example.com
kanidm login -D idm_admin

# who may use YNAY
kanidm group create ynay_users -D idm_admin
kanidm group add-members ynay_users alice bob -D idm_admin

# the OAuth2 client; the name ("ynay") becomes YNAY_KANIDM_CLIENT_ID
kanidm system oauth2 create ynay "YNAY" https://ynay.example.com -D idm_admin
kanidm system oauth2 add-redirect-url ynay https://ynay.example.com/login/oauth2/code/kanidm -D idm_admin
kanidm system oauth2 update-scope-map ynay ynay_users openid profile email -D idm_admin

# recommended: preferred_username "alice" instead of "alice@idm.example.com"
kanidm system oauth2 prefer-short-username ynay -D idm_admin

# the client secret -> YNAY_KANIDM_CLIENT_SECRET
kanidm system oauth2 show-basic-secret ynay -D idm_admin
```

Or: `KANIDM_URL=https://idm.example.com YNAY_URL=https://ynay.example.com MEMBERS="alice bob" deploy/kanidm/setup-oauth2-client.sh`.

Leave PKCE enabled and keep the default ES256 signing. YNAY handles both.

## 2. Configure YNAY

| variable | example | notes |
|---|---|---|
| `YNAY_PUBLIC_URL` | `https://ynay.example.com` | Must be the URL users type. The OIDC redirect URI is `<this>/login/oauth2/code/kanidm`. With `https://`, cookies are marked `Secure`. |
| `YNAY_KANIDM_URL` | `https://idm.example.com` | Kanidm origin, no path. **Login is disabled when unset**: `/api/**` then always answers 401 and only API tokens work on `/mcp`. |
| `YNAY_KANIDM_CLIENT_ID` | `ynay` | OAuth2 client name in Kanidm (default `ynay`). |
| `YNAY_KANIDM_CLIENT_SECRET` | `…` | Output of `show-basic-secret`. |
| `YNAY_KANIDM_ID_TOKEN_ALG` | `ES256` | ID token signature algorithm. Set it to `RS256` only if you ran `kanidm system oauth2 warning-enable-legacy-crypto ynay`. |
| `YNAY_MCP_INTROSPECTION` | `false` | `true`: `/mcp` also accepts Kanidm access tokens, checked with RFC 7662 introspection at `<YNAY_KANIDM_URL>/oauth2/token/introspect` using the client id and secret. |
| `YNAY_MCP_TOKEN_AUDIENCES` | `ynay,claude` | Kanidm client ids whose access tokens `/mcp` accepts (default: `YNAY_KANIDM_CLIENT_ID`). Tokens for other applications are rejected. |

The OIDC issuer that YNAY derives is `<YNAY_KANIDM_URL>/oauth2/openid/<client id>`, because Kanidm
uses one issuer per client. Discovery is **lazy**: YNAY starts even when Kanidm is down. The
discovery document is fetched on the first login and retried on every login until it succeeds.
While Kanidm is unreachable, a login attempt lands on `/?login_error=idp_unavailable`.

If Kanidm uses a private CA, the JVM has to trust it. Import the CA into a copy of the JDK
truststore and point the JVM at that copy:

```sh
cp "$JAVA_HOME/lib/security/cacerts" ynay-truststore.p12   # (or your existing truststore)
keytool -importcert -noprompt -alias kanidm-ca -file ca.pem -keystore ynay-truststore.p12 -storepass changeit
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$PWD/ynay-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit"
```

If `JAVA_TOOL_OPTIONS` already sets a truststore (corporate proxy, CI), add the CA to a copy of
*that* truststore. A second `-Djavax.net.ssl.trustStore` would replace it.

## 3. What happens on login

* Scopes `openid profile email`, PKCE `S256` (Kanidm requires it even for confidential clients),
  client authentication `client_secret_basic`, ID token verified as ES256 against Kanidm's JWKS.
* On every login, `app_user` is upserted by `(oidc_issuer, oidc_subject)`. `subject` is the person's
  Kanidm UUID. `username` = `preferred_username` (stored as-is, e.g. `alice` or `alice@idm.example.com`),
  `display_name` = `name`, `email` = `email`, and `last_login_at` is set.
* The session cookie `YNAY_SESSION` is `HttpOnly`, `SameSite=Lax`, and `Secure` with an https
  public URL. After login the browser goes to `/`.
* The SPA reads the CSRF token from the `XSRF-TOKEN` cookie (emitted on the first GET) and sends it
  as `X-XSRF-TOKEN` on every non-GET request.
* `GET /api/me` returns `{userId, username, displayName, email}`, or 401 with
  `{"error":{"code":"UNAUTHENTICATED",...}}`. The SPA then links to `/oauth2/authorization/kanidm`.
* `POST /logout` (with CSRF header): a form post gets a 302 to `/`. A request with
  `Accept: application/json` gets `200 {"redirectUrl": "/"}`. If Kanidm's discovery document ever
  advertises an `end_session_endpoint`, `redirectUrl` points there (RP-initiated logout).
  Kanidm 1.11 does not advertise one, so the Kanidm session itself stays alive.

## 4. MCP clients

* **API tokens** (default): in the web UI, create a token (`create_api_token`, shown once). The
  client then sends `Authorization: Bearer ynay_pat_…`. Tokens can expire (`expiresInDays`) and be
  revoked. YNAY stores only their SHA-256 hash and records `last_used_at`, at most once per minute.
* **Kanidm access tokens** (`YNAY_MCP_INTROSPECTION=true`): unauthenticated `/mcp` calls answer
  `401` with `WWW-Authenticate: Bearer resource_metadata="<public-url>/.well-known/oauth-protected-resource"`.
  That RFC 9728 document names the Kanidm issuer as authorization server, so an OAuth-capable MCP
  client can get a token from Kanidm. A token's `sub` maps to the same `app_user` as a web login
  (unknown users are provisioned). A client registered in Kanidm under its own name (for example a
  public client `kanidm system oauth2 create-public claude "Claude" <url>`) must be listed in
  `YNAY_MCP_TOKEN_AUDIENCES`. Introspection results are cached for 30 s.

## 5. Local development with a throwaway Kanidm

```sh
deploy/kanidm/dev-kanidm.sh            # kanidmd on https://localhost:8443, provisioned
set -a; source .kanidm-dev/ynay.env; set +a
nix develop -c bash -c 'cd backend && ./gradlew bootRun'
# open http://localhost:8080/oauth2/authorization/kanidm, log in as $YNAY_DEV_USER / $YNAY_DEV_USER_PASSWORD
deploy/kanidm/dev-kanidm.sh stop       # or: reset (deletes all state)
```

The script fetches `kanidm_1_11` with nix from the flake's nixpkgs if `kanidmd` isn't on `PATH`.
It then:

* generates a self-signed CA and certificate (`kanidmd cert-generate`) and starts kanidmd;
* recovers `idm_admin` (`kanidmd scripting recover-account`);
* creates person `alice` (mail `alice@example.com`) and gives her a generated password by
  recovering her account the same way;
* creates group `ynay_users` and OAuth2 client `ynay` with redirect
  `http://localhost:8080/login/oauth2/code/kanidm`, scope map `openid profile email` and
  `prefer-short-username`;
* builds `.kanidm-dev/truststore.p12`: the JVM's current truststore plus the dev CA;
* writes `.kanidm-dev/ynay.env`: `YNAY_KANIDM_*`, the client secret, a `JAVA_TOOL_OPTIONS` that
  uses that truststore, and the test login.

`deploy/kanidm/e2e-login.js` is a Playwright script that logs in through the real Kanidm UI and
checks the whole flow. Run it with the app started with `YNAY_MCP_INTROSPECTION=true`.

## 6. Troubleshooting

| symptom | cause / fix |
|---|---|
| After Kanidm login: `/?login_error`, log says `invalid_id_token` / `Signed JWT rejected` / `Another algorithm expected` | Algorithm mismatch. Kanidm signs with ES256 by default, which is YNAY's default. If the client has legacy crypto enabled (RS256), set `YNAY_KANIDM_ID_TOKEN_ALG=RS256`, or run `kanidm system oauth2 disable-legacy-crypto ynay`. |
| Kanidm shows an error about PKCE / `code_challenge` | YNAY always sends PKCE S256. If you see this, something in front of Kanidm strips query parameters. Never use `warning-insecure-client-disable-pkce`. |
| Kanidm: "redirect_uri invalid" / "Invalid OAuth2 redirect" | The redirect must match exactly: `<YNAY_PUBLIC_URL>/login/oauth2/code/kanidm` (scheme, host, port, no trailing slash). Fix `YNAY_PUBLIC_URL`, or `kanidm system oauth2 add-redirect-url`. Behind a reverse proxy, set `YNAY_PUBLIC_URL` to the external URL. |
| Kanidm: "access denied" / no consent screen for a user | The user is not in a group that has a scope map (`update-scope-map ynay ynay_users openid profile email`). |
| Usernames look like `alice@idm.example.com` | That is Kanidm's SPN. Run `kanidm system oauth2 prefer-short-username ynay`; usernames update on the next login. Budget sharing uses this username. |
| `/oauth2/authorization/kanidm` returns 404 | `YNAY_KANIDM_URL` is not set, so login is disabled. |
| Login redirects to `/?login_error=idp_unavailable` | YNAY could not fetch `<YNAY_KANIDM_URL>/oauth2/openid/<client>/.well-known/openid-configuration`. Check DNS and TLS trust (see the truststore section; log: `PKIX path building failed`). |
| Token request fails with `invalid_client` | Wrong `YNAY_KANIDM_CLIENT_SECRET`, possibly after `reset-secrets`. Check with `show-basic-secret`. |
| `/mcp` rejects every Kanidm access token | `YNAY_MCP_INTROSPECTION` is not `true`; or the token was minted for another client id that is missing from `YNAY_MCP_TOKEN_AUDIENCES`; or the client secret is wrong. Kanidm answers introspection with a bare 401 both for unparseable tokens and for bad client credentials. |
| The session cookie is not kept over plain http on a LAN IP | Expected only if `YNAY_PUBLIC_URL` is `https://…` (cookies are `Secure`). Use the public https URL. |

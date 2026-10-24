// End-to-end check of YNAY's Kanidm login against the dev Kanidm from deploy/kanidm/dev-kanidm.sh.
// Needs Playwright (`npm i -g playwright && npx playwright install chromium`) and a running backend
// started with the env from dev-kanidm.sh plus YNAY_MCP_INTROSPECTION=true:
//
//   set -a; source .kanidm-dev/ynay.env; set +a
//   node deploy/kanidm/e2e-login.js
//
// Checks: JSON 401 + CSRF cookie before login, PKCE S256 + scopes on the authorization request,
// real login (username/password, consent), session cookie flags, /api/me, CSRF enforcement, a
// Kanidm access token accepted on /mcp via introspection, RFC 9728 metadata, logout.
const { chromium, request } = require('playwright');
const crypto = require('crypto');

const APP = process.env.YNAY_PUBLIC_URL || 'http://localhost:8080';
const KANIDM = process.env.YNAY_KANIDM_URL;
const USER = process.env.YNAY_DEV_USER;
const PASSWORD = process.env.YNAY_DEV_USER_PASSWORD;
const CLIENT_ID = process.env.YNAY_KANIDM_CLIENT_ID || 'ynay';
const SECRET = process.env.YNAY_KANIDM_CLIENT_SECRET;

function check(cond, msg) {
  if (!cond) { console.error('FAIL: ' + msg); process.exitCode = 1; throw new Error(msg); }
  console.log('ok   ' + msg);
}

async function kanidmLogin(page) {
  await page.waitForSelector('input[name=username]', { timeout: 15000 });
  await page.fill('input[name=username]', USER);
  await page.click('button[type=submit]');
  await page.waitForSelector('input[name=password]', { timeout: 15000 });
  await page.fill('input[name=password]', PASSWORD);
  await page.click('button[type=submit]');
}

async function maybeConsent(page) {
  // First login for a client shows a consent page ("Proceed").
  try {
    const proceed = page.getByRole('button', { name: /proceed|allow|consent/i });
    await proceed.waitFor({ timeout: 5000 });
    console.log('     consent page: ' + page.url());
    await proceed.click();
  } catch (e) { /* no consent needed */ }
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const navigations = [];
  page.on('framenavigated', f => { if (f === page.mainFrame()) navigations.push(f.url()); });

  // 0. unauthenticated: /api/me is a JSON 401, CSRF cookie is emitted on first GET
  let res = await page.request.get(APP + '/api/me');
  check(res.status() === 401, 'GET /api/me before login -> 401');
  check((await res.json()).error.code === 'UNAUTHENTICATED', '401 body is {"error":{"code":"UNAUTHENTICATED"}}');
  check((await context.cookies(APP)).some(c => c.name === 'XSRF-TOKEN'), 'XSRF-TOKEN cookie emitted on first GET');

  // 1. login through Kanidm
  await page.goto(APP + '/oauth2/authorization/kanidm');
  const authUrl = navigations.find(u => u.startsWith(KANIDM + '/ui/oauth2')) || '';
  const q = new URL(authUrl || page.url()).searchParams;
  console.log('     authorize request: ' + authUrl.split('?')[0]);
  check(q.get('code_challenge_method') === 'S256' && q.get('code_challenge'), 'authorization request uses PKCE S256');
  check(q.get('scope') === 'openid profile email', 'scopes are "openid profile email"');
  await kanidmLogin(page);
  await maybeConsent(page);
  await page.waitForURL(u => u.toString().startsWith(APP), { timeout: 20000 });
  console.log('     landed on ' + page.url());
  check(new URL(page.url()).pathname === '/' && !page.url().includes('error'), 'redirected to / after login');

  const cookies = await context.cookies(APP);
  const session = cookies.find(c => c.name === 'YNAY_SESSION');
  check(session && session.httpOnly && session.sameSite === 'Lax', 'session cookie YNAY_SESSION is HttpOnly + SameSite=Lax');
  const xsrf = cookies.find(c => c.name === 'XSRF-TOKEN');
  check(xsrf && !xsrf.httpOnly, 'XSRF-TOKEN cookie readable by JS');

  // 2. /api/me returns the Kanidm user
  res = await page.request.get(APP + '/api/me');
  check(res.status() === 200, 'GET /api/me after login -> 200');
  const me = await res.json();
  console.log('     /api/me = ' + JSON.stringify(me));
  check(me.username === USER, `username is "${USER}"`);
  check(me.email === 'alice@example.com', 'email from Kanidm');
  check(me.displayName === 'Alice Example', 'displayName from Kanidm');

  // 3. CSRF: POST without header is rejected, with header passes the filter
  res = await page.request.post(APP + '/api/me', { headers: { Accept: 'application/json' } });
  check(res.status() === 403, 'POST /api/me without X-XSRF-TOKEN -> 403');
  const freshXsrf = (await context.cookies(APP)).find(c => c.name === 'XSRF-TOKEN').value;
  res = await page.request.post(APP + '/api/me', { headers: { 'X-XSRF-TOKEN': freshXsrf } });
  check(res.status() === 405, 'POST /api/me with X-XSRF-TOKEN passes CSRF (405 from MVC)');

  // 4. A Kanidm access token for the ynay client, obtained via a separate auth code + PKCE flow,
  //    is accepted on /mcp through introspection (YNAY_MCP_INTROSPECTION=true).
  const verifier = crypto.randomBytes(32).toString('base64url');
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
  const redirect = APP + '/login/oauth2/code/kanidm';
  let code;
  // Redirect targets are not routable in Playwright; read the code off the request. YNAY rejects
  // the callback (unknown state) before redeeming the code, so the code stays usable.
  page.on('request', r => {
    if (r.url().startsWith(redirect)) code = new URL(r.url()).searchParams.get('code');
  });
  const authorize = `${KANIDM}/ui/oauth2?response_type=code&client_id=${CLIENT_ID}` +
    `&redirect_uri=${encodeURIComponent(redirect)}&scope=${encodeURIComponent('openid profile email')}` +
    `&state=e2e&code_challenge=${challenge}&code_challenge_method=S256`;
  await page.goto(authorize);
  await maybeConsent(page);
  for (let i = 0; i < 50 && !code; i++) await page.waitForTimeout(200);
  if (!code) console.log('     no code; page at ' + page.url() + '\n' + (await page.content()).slice(0, 2000));
  check(!!code, 'got an authorization code for a direct token request');
  const kanidmApi = await request.newContext({ ignoreHTTPSErrors: true });
  res = await kanidmApi.post(KANIDM + '/oauth2/token', {
    form: { grant_type: 'authorization_code', code, redirect_uri: redirect, code_verifier: verifier, client_id: CLIENT_ID },
    headers: { Authorization: 'Basic ' + Buffer.from(`${CLIENT_ID}:${SECRET}`).toString('base64') },
  });
  const tokenBody = await res.json();
  check(res.status() === 200 && tokenBody.access_token, 'token endpoint returned an access token');
  res = await kanidmApi.post(KANIDM + '/oauth2/token/introspect', {
    form: { token: tokenBody.access_token },
    headers: { Authorization: 'Basic ' + Buffer.from(`${CLIENT_ID}:${SECRET}`).toString('base64') },
  });
  console.log('     introspection response: ' + JSON.stringify(await res.json()));

  const anon = await request.newContext();
  res = await anon.post(APP + '/mcp', { headers: { 'Content-Type': 'application/json' }, data: '{}' });
  check(res.status() === 401, 'POST /mcp without token -> 401');
  console.log('     WWW-Authenticate: ' + res.headers()['www-authenticate']);
  check((res.headers()['www-authenticate'] || '').includes(`resource_metadata="${APP}/.well-known/oauth-protected-resource"`),
    'WWW-Authenticate has resource_metadata');
  res = await anon.post(APP + '/mcp', { headers: { 'Content-Type': 'application/json', Authorization: 'Bearer not-a-real-token' }, data: '{}' });
  check(res.status() === 401, 'POST /mcp with bogus token -> 401 (introspection says inactive)');
  res = await anon.post(APP + '/mcp', {
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tokenBody.access_token }, data: '{}' });
  console.log('     POST /mcp with Kanidm access token -> ' + res.status());
  check(res.status() !== 401 && res.status() !== 403, 'Kanidm access token accepted on /mcp (past the security filter)');

  res = await anon.get(APP + '/.well-known/oauth-protected-resource');
  console.log('     metadata: ' + JSON.stringify(await res.json()));

  // 5. logout
  res = await page.request.post(APP + '/logout', { headers: { 'X-XSRF-TOKEN': (await context.cookies(APP)).find(c => c.name === 'XSRF-TOKEN').value, Accept: 'application/json' } });
  check(res.status() === 200, 'POST /logout (JSON) -> 200 ' + (await res.text()));
  res = await page.request.get(APP + '/api/me');
  check(res.status() === 401, 'GET /api/me after logout -> 401');

  await browser.close();
  console.log(process.exitCode ? 'E2E FAILED' : 'E2E PASSED');
})().catch(e => { console.error(e); process.exit(1); });

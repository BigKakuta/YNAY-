// In-browser mock backend. Enabled with VITE_MOCK=1 (`npm run dev:mock`); it replaces `fetch`
// for the backend's paths so the real API client (CSRF header, error mapping) is exercised.
//
// Handy URL switches (dev only):  ?mock-reset   reseed the data
//                                 ?mock-agent=off   pretend the import agent isn't configured
import type { AgentChatRequest } from '../api/types';
import { createHandlers, MockError, type Handlers } from './actions';
import { mockAgentChat } from './agent';
import { seedState } from './seed';
import type { MockState } from './state';

const STORAGE_KEY = 'ynay.mock.state.v2';
const LOGGED_OUT_KEY = 'ynay.mock.loggedOut';
const AGENT_OFF_KEY = 'ynay.mock.agentOff';
const CSRF = 'mock-csrf-token';

function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function loadState(): MockState {
  try {
    const raw = storage()?.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as MockState;
  } catch {
    // fall through to a fresh seed
  }
  return seedState();
}

function saveState(s: MockState) {
  try {
    storage()?.setItem(STORAGE_KEY, JSON.stringify(s));
  } catch {
    // ignore quota / privacy mode
  }
}

const json = (status: number, body: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

export interface MockBackend {
  state: MockState;
  handlers: Handlers;
  fetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
}

export function createMockBackend(options: { state?: MockState; latencyMs?: number; persist?: boolean } = {}): MockBackend {
  const state = options.state ?? seedState();
  const handlers = createHandlers(state);
  const latency = options.latencyMs ?? 0;

  async function mockFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, window.location.href);
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = new Headers(init.headers);
    const path = url.pathname;
    if (latency) await delay(latency);

    const loggedOut = storage()?.getItem(LOGGED_OUT_KEY) === '1';
    if (method !== 'GET' && headers.get('X-XSRF-TOKEN') !== CSRF) {
      return json(403, { error: { code: 'forbidden', message: 'Invalid CSRF token' } });
    }
    if (path === '/logout' && method === 'POST') {
      storage()?.setItem(LOGGED_OUT_KEY, '1');
      return new Response(null, { status: 204 });
    }
    if (loggedOut) return json(401, { error: { code: 'unauthenticated', message: 'Not signed in' } });

    if (path === '/api/me' && method === 'GET') return json(200, handlers.whoami!({}));
    if (path === '/api/agent/status' && method === 'GET') {
      const off = storage()?.getItem(AGENT_OFF_KEY) === '1';
      return json(200, off ? { enabled: false, provider: null, model: null } : { enabled: true, provider: 'mock', model: 'scripted-importer' });
    }
    if (path === '/api/agent/chat' && method === 'POST') {
      const req = JSON.parse(String(init.body ?? '{}')) as AgentChatRequest;
      try {
        handlers.get_budget!({ budgetId: req.budgetId });
        await delay(600);
        const res = mockAgentChat(handlers, req);
        if (options.persist) saveState(state);
        return json(200, res);
      } catch (e) {
        return errorResponse(e);
      }
    }
    const m = path.match(/^\/api\/actions\/([a-z_]+)$/);
    if (m && method === 'POST') {
      const handler = handlers[m[1]!];
      if (!handler) return json(404, { error: { code: 'not_found', message: `Unknown action ${m[1]}` } });
      try {
        const body = init.body ? JSON.parse(String(init.body)) : {};
        const out = handler(body);
        if (options.persist) saveState(state);
        return json(200, JSON.parse(JSON.stringify(out)));
      } catch (e) {
        return errorResponse(e);
      }
    }
    return json(404, { error: { code: 'not_found', message: `No mock for ${method} ${path}` } });
  }

  return { state, handlers, fetch: mockFetch };
}

function errorResponse(e: unknown) {
  if (e instanceof MockError) return json(e.status, { error: { code: e.code, message: e.message } });
  console.error('[mock]', e);
  return json(500, { error: { code: 'internal', message: (e as Error).message } });
}

/** Install the mock in the browser (dev:mock). */
export function installMock() {
  const params = new URLSearchParams(window.location.search);
  const store = storage();
  if (params.has('mock-reset')) store?.removeItem(STORAGE_KEY);
  if (params.has('mock-agent')) store?.setItem(AGENT_OFF_KEY, params.get('mock-agent') === 'off' ? '1' : '0');
  // "Sign in with Kanidm" lands here in mock mode: pretend the OIDC dance succeeded.
  if (window.location.pathname.startsWith('/oauth2/authorization/')) {
    store?.removeItem(LOGGED_OUT_KEY);
    window.history.replaceState(null, '', '/');
  }
  document.cookie = `XSRF-TOKEN=${CSRF}; path=/; SameSite=Strict`;
  const backend = createMockBackend({ state: loadState(), latencyMs: 80, persist: true });
  saveState(backend.state);
  const realFetch = window.fetch.bind(window);
  window.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const path = new URL(url, window.location.href).pathname;
    if (/^\/(api|logout|mcp)(\/|$)/.test(path)) return backend.fetch(input, init);
    return realFetch(input, init);
  };
  console.info('[ynay] mock backend active — data lives in sessionStorage (add ?mock-reset to reseed)');
}

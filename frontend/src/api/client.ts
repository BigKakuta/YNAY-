import type {
  ActionInput,
  ActionName,
  ActionOutput,
  AgentChatRequest,
  AgentChatResponse,
  AgentStatus,
  Me,
} from './types';

/** An error from the backend, mapped from `{"error": {"code", "message"}}` or the HTTP status. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export const LOGIN_URL = '/oauth2/authorization/kanidm';

type Listener = () => void;
const unauthorizedListeners = new Set<Listener>();

/** Called whenever any request comes back 401 (session expired). */
export function onUnauthorized(listener: Listener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

export function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const c = part.trim();
    if (c.startsWith(prefix)) return decodeURIComponent(c.slice(prefix.length));
  }
  return null;
}

const statusCodes: Record<number, string> = {
  400: 'invalid',
  401: 'unauthenticated',
  403: 'forbidden',
  404: 'not_found',
  409: 'conflict',
  500: 'internal',
  502: 'bad_gateway',
  503: 'unavailable',
};

async function toError(res: Response): Promise<ApiError> {
  let code = statusCodes[res.status] ?? 'http_' + res.status;
  let message = res.statusText || `Request failed (${res.status})`;
  try {
    const text = await res.text();
    if (text) {
      const body = JSON.parse(text) as unknown;
      const err = (body as { error?: { code?: unknown; message?: unknown } } | null)?.error;
      if (err && typeof err === 'object') {
        if (typeof err.code === 'string') code = err.code;
        if (typeof err.message === 'string') message = err.message;
      }
    }
  } catch {
    // Not JSON (e.g. a proxy error page): keep the status-derived error.
  }
  if (res.status === 401) message = 'Your session has expired. Please sign in again.';
  return new ApiError(res.status, code, message);
}

/** Low-level JSON request. Same-origin credentials; CSRF header on every non-GET request. */
export async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'network', 'Could not reach the server. Check your connection.');
  }

  if (!res.ok) {
    const err = await toError(res);
    if (res.status === 401) unauthorizedListeners.forEach((l) => l());
    throw err;
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Invoke a domain action: `POST /api/actions/{name}`. */
export function call<N extends ActionName>(name: N, input: ActionInput<N>): Promise<ActionOutput<N>> {
  return request<ActionOutput<N>>('POST', `/api/actions/${encodeURIComponent(name)}`, stripUndefined(input));
}

function stripUndefined(input: object): object {
  return Object.fromEntries(Object.entries(input).filter(([, v]) => v !== undefined));
}

/** `GET /api/me`; resolves to null when not signed in (401). */
export async function fetchMe(): Promise<Me | null> {
  try {
    return await request<Me>('GET', '/api/me');
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return null;
    throw e;
  }
}

export async function logout(): Promise<void> {
  const token = readCookie('XSRF-TOKEN');
  try {
    await fetch('/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: token ? { 'X-XSRF-TOKEN': token } : {},
      redirect: 'manual',
    });
  } finally {
    window.location.assign('/');
  }
}

export const agent = {
  status: () => request<AgentStatus>('GET', '/api/agent/status'),
  chat: (req: AgentChatRequest) => request<AgentChatResponse>('POST', '/api/agent/chat', req),
};

import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, agent, call, fetchMe, onUnauthorized, readCookie, request } from './client';

function mockFetch(status: number, body?: unknown, contentType = 'application/json') {
  const fn = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
    new Response(body === undefined ? null : typeof body === 'string' ? body : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': contentType },
    }),
  );
  vi.stubGlobal('fetch', fn);
  return fn;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('api client', () => {
  it('posts the action input as JSON with the CSRF header from the cookie', async () => {
    document.cookie = 'XSRF-TOKEN=abc%2D123; path=/';
    const fetch = mockFetch(200, [{ id: 'b1' }]);
    const out = await call('list_budgets', {});
    expect(out).toEqual([{ id: 'b1' }]);
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe('/api/actions/list_budgets');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('same-origin');
    const headers = init?.headers as Record<string, string>;
    expect(headers['X-XSRF-TOKEN']).toBe('abc-123');
    expect(headers['Content-Type']).toBe('application/json');
    expect(init?.body).toBe('{}');
  });

  it('drops undefined fields but keeps nulls', async () => {
    const fetch = mockFetch(200, {});
    await call('update_category', { budgetId: 'b', categoryId: 'c', name: undefined, note: null });
    expect(JSON.parse(fetch.mock.calls[0]![1]!.body as string)).toEqual({ budgetId: 'b', categoryId: 'c', note: null });
  });

  it('does not send the CSRF header on GET', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const fetch = mockFetch(200, { enabled: false, provider: null, model: null });
    await agent.status();
    const headers = fetch.mock.calls[0]![1]!.headers as Record<string, string>;
    expect(headers['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('maps {"error": {code, message}} to ApiError', async () => {
    mockFetch(404, { error: { code: 'not_found', message: 'Budget not found' } });
    const err = await call('get_budget', { budgetId: 'x' }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 404, code: 'not_found', message: 'Budget not found' });
  });

  it('maps non-JSON errors by status', async () => {
    mockFetch(502, '<html>Bad gateway</html>', 'text/html');
    await expect(request('GET', '/api/x')).rejects.toMatchObject({ status: 502, code: 'bad_gateway' });
  });

  it('maps network failures', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))));
    await expect(call('list_budgets', {})).rejects.toMatchObject({ status: 0, code: 'network' });
  });

  it('notifies listeners on 401 and fetchMe resolves to null', async () => {
    mockFetch(401, { error: { code: 'unauthenticated', message: 'nope' } });
    const listener = vi.fn();
    const off = onUnauthorized(listener);
    expect(await fetchMe()).toBeNull();
    await expect(call('list_budgets', {})).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalledTimes(2);
    off();
  });

  it('reads cookies by exact name', () => {
    document.cookie = 'XSRF-TOKEN-OLD=nope; path=/';
    document.cookie = 'XSRF-TOKEN=yes; path=/';
    expect(readCookie('XSRF-TOKEN')).toBe('yes');
    expect(readCookie('MISSING')).toBeNull();
  });
});

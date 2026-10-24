import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { clearQueryCache } from '../api/query';
import type { Budget } from '../api/types';
import { AssignInput } from '../components/inputs';
import { currentMonth } from '../lib/dates';
import { createMockBackend, type MockBackend } from '../mock';

let backend: MockBackend;

beforeEach(() => {
  clearQueryCache();
  backend = createMockBackend();
  document.cookie = 'XSRF-TOKEN=mock-csrf-token; path=/';
  vi.stubGlobal('fetch', vi.fn(backend.fetch));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

const household = () => (backend.handlers.list_budgets!({}) as Budget[]).find((b) => b.name === 'Household')!;

describe('App', () => {
  it('shows the Kanidm sign-in screen when not logged in', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"error":{"code":"unauthenticated","message":"no"}}', { status: 401 })));
    renderAt('/');
    const link = await screen.findByRole('link', { name: /sign in with kanidm/i });
    expect(link).toHaveAttribute('href', '/oauth2/authorization/kanidm');
  });

  it('lists budgets and creates a new one', async () => {
    const user = userEvent.setup();
    renderAt('/');
    expect(await screen.findByText('Household')).toBeInTheDocument();
    expect(screen.getByText('Holiday flat')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /new budget/i }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('Name'), 'Side hustle');
    await user.click(within(dialog).getByRole('button', { name: /create budget/i }));

    // Navigates into the new, empty budget: nothing to assign yet.
    expect(await screen.findByText('All money assigned')).toBeInTheDocument();
    expect((backend.handlers.list_budgets!({}) as Budget[]).map((b) => b.name)).toContain('Side hustle');
    // Every POST carried the CSRF header.
    const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts.length).toBeGreaterThan(0);
    for (const [, init] of posts) expect((init!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('mock-csrf-token');
  });

  it('assigns money inline and updates Ready to Assign', async () => {
    const user = userEvent.setup();
    const b = household();
    renderAt(`/budgets/${b.id}/budget/${currentMonth()}`);
    const rta = await screen.findByTestId('rta');
    const before = rta.textContent;

    const input = await screen.findByLabelText('Assigned to Shopping');
    await user.click(input);
    await user.clear(input);
    await user.type(input, '12.50{Enter}');

    await waitFor(() => expect(screen.getByTestId('rta').textContent).not.toBe(before));
    expect(screen.getByTestId('rta')).toHaveTextContent(/400[.,]00/);
    expect((screen.getByLabelText('Assigned to Shopping') as HTMLInputElement).value).toMatch(/^12[.,]50$/);
  });

  it('shows the register with unapproved imports and approves them', async () => {
    const user = userEvent.setup();
    const b = household();
    const checking = (backend.handlers.list_accounts!({ budgetId: b.id }) as { id: string; name: string }[]).find((a) => a.name === 'Checking')!;
    renderAt(`/budgets/${b.id}/accounts/${checking.id}`);
    const approveAll = await screen.findByRole('button', { name: /approve all \(3\)/i });
    expect(screen.getByText('Hardware Hut')).toBeInTheDocument();
    await user.click(approveAll);
    await waitFor(() => expect(screen.queryByRole('button', { name: /approve all/i })).not.toBeInTheDocument());
    expect(screen.queryAllByText('New')).toHaveLength(0);
  });
});

describe('AssignInput', () => {
  it('parses decimals into milliunits on Enter and reverts on Escape', async () => {
    const user = userEvent.setup();
    const onCommit = vi.fn(async () => {});
    render(<AssignInput value={5000} currency="USD" label="Assigned" onCommit={onCommit} />);
    const input = screen.getByLabelText('Assigned');
    expect(input).toHaveValue('5.00');

    await user.click(input);
    await user.clear(input);
    await user.type(input, '1,234.5{Enter}');
    expect(onCommit).toHaveBeenCalledWith(1234500);

    onCommit.mockClear();
    await user.click(input);
    await user.clear(input);
    await user.type(input, '99{Escape}');
    expect(onCommit).not.toHaveBeenCalled();
    expect(input).toHaveValue('5.00');
  });

  it('ignores invalid input', async () => {
    const user = userEvent.setup();
    const onCommit = vi.fn(async () => {});
    render(<AssignInput value={0} currency="USD" label="Assigned" onCommit={onCommit} />);
    const input = screen.getByLabelText('Assigned');
    await user.click(input);
    await user.clear(input);
    await user.type(input, 'abc');
    await user.tab();
    expect(onCommit).not.toHaveBeenCalled();
    expect(input).toHaveValue('0.00');
  });
});

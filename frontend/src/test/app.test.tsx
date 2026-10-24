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

  it('flags uncategorized transactions only where the budget needs a category', async () => {
    const b = household();
    const account = (name: string) =>
      (backend.handlers.list_accounts!({ budgetId: b.id }) as { id: string; name: string }[]).find((a) => a.name === name)!;
    const card = backend.handlers.create_account!({ budgetId: b.id, name: 'Amex', type: 'CREDIT_CARD', startingBalance: -200_000 }) as { id: string };
    backend.handlers.create_transaction!({ budgetId: b.id, accountId: card.id, date: '2026-01-15', amount: -9_000, payeeName: 'Kiosk' });

    // a credit card's opening debt has no budget effect; a new uncategorized purchase does
    const { unmount } = renderAt(`/budgets/${b.id}/accounts/${card.id}`);
    const kiosk = (await screen.findByText('Kiosk')).closest('tr')!;
    expect(within(kiosk).getByText('Uncategorized')).toBeInTheDocument();
    const opening = screen.getByText('Starting Balance').closest('tr')!;
    expect(within(opening).queryByText('Uncategorized')).not.toBeInTheDocument();
    unmount();

    // tracking accounts never take categories
    renderAt(`/budgets/${b.id}/accounts/${account('Brokerage').id}`);
    await screen.findByText('Starting Balance');
    expect(screen.queryByText('Uncategorized')).not.toBeInTheDocument();
  });

  // update_* actions treat null as "unchanged"; clearing a text field has to send "".
  const lastBody = (action: string) => {
    const call = vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith(`/api/actions/${action}`)).at(-1);
    return JSON.parse(String(call![1]!.body)) as Record<string, unknown>;
  };

  it('clears a category note', async () => {
    const user = userEvent.setup();
    const b = household();
    renderAt(`/budgets/${b.id}/budget/${currentMonth()}`);
    await user.click(await screen.findByRole('button', { name: 'Medical' }));
    const panel = await screen.findByRole('complementary', { name: 'Medical details' });
    const note = within(panel).getByLabelText('Note');
    expect(note).toHaveValue('Co-pays and prescriptions');
    await user.clear(note);
    await user.click(within(panel).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(lastBody('update_category').note).toBe(''));
    const groups = backend.handlers.list_categories!({ budgetId: b.id }) as { categories: { name: string; note: string | null }[] }[];
    expect(groups.flatMap((g) => g.categories).find((c) => c.name === 'Medical')!.note).toBeNull();
  });

  it('clears an account note', async () => {
    const user = userEvent.setup();
    const b = household();
    const accounts = () => backend.handlers.list_accounts!({ budgetId: b.id }) as { id: string; name: string; note: string | null }[];
    const checking = accounts().find((a) => a.name === 'Checking')!;
    backend.handlers.update_account!({ budgetId: b.id, accountId: checking.id, note: 'joint account' });
    renderAt(`/budgets/${b.id}/accounts/${checking.id}`);
    await user.click(await screen.findByRole('button', { name: 'Account settings' }));
    const dialog = await screen.findByRole('dialog');
    await user.clear(within(dialog).getByLabelText('Note'));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(lastBody('update_account').note).toBe(''));
    expect(accounts().find((a) => a.id === checking.id)!.note).toBeNull();
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

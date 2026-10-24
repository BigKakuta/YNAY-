import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type InputHTMLAttributes } from 'react';
import type { CategoryGroup, Payee } from '../api/types';
import { formatAmount, formatPlain, parseMoney } from '../lib/money';

type MoneyInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'> & {
  value: string;
  onChange: (text: string) => void;
};

/** Text input for amounts; the caller keeps the raw text and parses with `parseMoney`. */
export function MoneyInput({ value, onChange, className = '', ...rest }: MoneyInputProps) {
  const invalid = value.trim() !== '' && parseMoney(value) === null;
  return (
    <input
      {...rest}
      className={`input money-input ${invalid ? 'invalid' : ''} ${className}`}
      inputMode="decimal"
      autoComplete="off"
      aria-invalid={invalid || undefined}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onFocus={(e) => e.target.select()}
    />
  );
}

/**
 * Inline "Assigned" cell: shows the amount as a plain decimal; on Enter/blur the text is parsed and
 * `onCommit(milliunits)` is called if it changed. Escape reverts.
 */
export function AssignInput({
  value,
  currency,
  onCommit,
  label,
}: {
  value: number;
  currency: string;
  onCommit: (milliunits: number) => Promise<unknown>;
  label: string;
}) {
  const [text, setText] = useState(() => formatPlain(value, currency));
  const [focused, setFocused] = useState(false);
  const [saving, setSaving] = useState(false);
  const skipBlur = useRef(false);
  const inputRef = useRef<HTMLInputElement>(null);
  // Select the (now plain) text right after focusing, before the next keystroke is handled.
  useLayoutEffect(() => {
    if (focused) inputRef.current?.select();
  }, [focused]);

  useEffect(() => {
    if (!focused && !saving) setText(formatPlain(value, currency));
  }, [value, currency, focused, saving]);

  async function commit() {
    const parsed = parseMoney(text);
    if (parsed === null) {
      setText(formatPlain(value, currency));
      return;
    }
    setText(formatPlain(parsed, currency));
    if (parsed === value) return;
    setSaving(true);
    try {
      await onCommit(parsed);
    } catch {
      setText(formatPlain(value, currency));
    } finally {
      setSaving(false);
    }
  }

  return (
    <input
      className={`assign-input ${saving ? 'saving' : ''}`}
      aria-label={label}
      inputMode="decimal"
      autoComplete="off"
      value={focused || saving ? text : formatAmount(value, currency)}
      onChange={(e) => setText(e.target.value)}
      ref={inputRef}
      onFocus={() => setFocused(true)}
      onBlur={() => {
        setFocused(false);
        if (skipBlur.current) {
          skipBlur.current = false;
          return;
        }
        void commit();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          void commit();
          skipBlur.current = true;
          // Move to the next assign input, like a spreadsheet.
          const all = [...document.querySelectorAll<HTMLInputElement>('.assign-input')];
          const i = all.indexOf(e.currentTarget);
          const next = all[i + (e.shiftKey ? -1 : 1)];
          if (next) next.focus();
          else e.currentTarget.blur();
        } else if (e.key === 'Escape') {
          setText(formatPlain(value, currency));
          skipBlur.current = true;
          e.currentTarget.blur();
        }
      }}
    />
  );
}

export const RTA_VALUE = '__rta__';

/**
 * Grouped category <select>. Includes "Ready to Assign" (for inflows) when `rtaId` is given.
 * Hidden categories are omitted unless currently selected.
 */
export function CategorySelect({
  groups,
  value,
  onChange,
  includeRta = true,
  emptyLabel = 'Uncategorized',
  allowEmpty = true,
  className = 'select',
  disabled,
  id,
  ariaLabel,
  excludeId,
}: {
  groups: CategoryGroup[];
  value: string | null;
  onChange: (categoryId: string | null) => void;
  includeRta?: boolean;
  emptyLabel?: string;
  allowEmpty?: boolean;
  className?: string;
  disabled?: boolean;
  id?: string;
  ariaLabel?: string;
  excludeId?: string;
}) {
  const internal = groups.find((g) => g.systemKind === 'INTERNAL');
  const rta = internal?.categories.find((c) => c.systemKind === 'READY_TO_ASSIGN');
  const visible = groups.filter((g) => g.systemKind !== 'INTERNAL');
  return (
    <select
      id={id}
      aria-label={ariaLabel}
      className={className}
      value={value ?? ''}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value || null)}
    >
      {allowEmpty && <option value="">{emptyLabel}</option>}
      {includeRta && rta && (
        <optgroup label="Inflow">
          <option value={rta.id}>Ready to Assign</option>
        </optgroup>
      )}
      {visible.map((g) => {
        const cats = g.categories.filter((c) => c.id !== excludeId && (!c.hidden || c.id === value));
        if (cats.length === 0) return null;
        return (
          <optgroup key={g.id} label={g.name}>
            {cats.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </optgroup>
        );
      })}
    </select>
  );
}

/** Payee autocomplete: pick an existing payee (incl. transfer payees) or type a new name. */
export function PayeeInput({
  payees,
  value,
  onChange,
  currentAccountId,
  autoFocus,
  className = 'input',
}: {
  payees: Payee[];
  /** The current text; `payeeId` is set when it matches a known payee. */
  value: { name: string; payeeId: string | null };
  onChange: (v: { name: string; payeeId: string | null; transferAccountId: string | null }) => void;
  currentAccountId?: string;
  autoFocus?: boolean;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listId = useId();
  const candidates = useMemo(
    () => payees.filter((p) => p.transferAccountId !== currentAccountId && p.name !== 'Starting Balance'),
    [payees, currentAccountId],
  );
  const q = value.name.trim().toLowerCase();
  const matches = useMemo(() => {
    const list = q
      ? candidates.filter((p) => p.name.toLowerCase().includes(q)).sort((a, b) => rank(a.name, q) - rank(b.name, q))
      : candidates;
    return [...list.filter((p) => !p.transferAccountId), ...list.filter((p) => p.transferAccountId)].slice(0, 12);
  }, [candidates, q]);
  const exact = candidates.find((p) => p.name.toLowerCase() === q);
  const showCreate = q.length > 0 && !exact;
  const options = [...matches.map((p) => ({ kind: 'payee' as const, payee: p })), ...(showCreate ? [{ kind: 'new' as const }] : [])];

  function choose(i: number) {
    const opt = options[i];
    if (!opt) return;
    if (opt.kind === 'payee') onChange({ name: opt.payee.name, payeeId: opt.payee.id, transferAccountId: opt.payee.transferAccountId });
    else onChange({ name: value.name.trim(), payeeId: null, transferAccountId: null });
    setOpen(false);
  }

  return (
    <div className="combo">
      <input
        className={className}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-label="Payee"
        placeholder="Payee"
        autoComplete="off"
        autoFocus={autoFocus}
        value={value.name}
        onChange={(e) => {
          const name = e.target.value;
          const match = candidates.find((p) => p.name.toLowerCase() === name.trim().toLowerCase());
          onChange({ name, payeeId: match?.id ?? null, transferAccountId: match?.transferAccountId ?? null });
          setOpen(true);
          setActive(0);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={(e) => {
          if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
            setOpen(true);
            return;
          }
          if (!open) return;
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActive((a) => Math.min(a + 1, options.length - 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActive((a) => Math.max(a - 1, 0));
          } else if ((e.key === 'Enter' || e.key === 'Tab') && options.length > 0 && q) {
            if (e.key === 'Enter') e.preventDefault();
            choose(active);
          } else if (e.key === 'Escape') {
            e.stopPropagation();
            setOpen(false);
          }
        }}
      />
      {open && options.length > 0 && (
        <ul className="combo-list" id={listId} role="listbox">
          {options.map((o, i) => (
            <li
              key={o.kind === 'payee' ? o.payee.id : '__new'}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(i);
              }}
              onMouseEnter={() => setActive(i)}
            >
              {o.kind === 'payee' ? (
                <>
                  <span>{o.payee.name}</span>
                  {o.payee.transferAccountId && <span className="hint">transfer</span>}
                </>
              ) : (
                <>
                  <span>Create “{value.name.trim()}”</span>
                  <span className="hint">new payee</span>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function rank(name: string, q: string) {
  const n = name.toLowerCase();
  return n.startsWith(q) ? 0 : n.includes(' ' + q) ? 1 : 2;
}

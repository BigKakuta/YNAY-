import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { call } from './client';
import type { ActionInput, ActionName, ActionOutput } from './types';

// A deliberately tiny stale-while-revalidate cache around `call()`.
// Queries are keyed by action name + input. Every mutation made through `mutate()` refetches
// all mounted queries (and drops unmounted ones), which keeps the UI consistent without
// hand-written invalidation rules: the backend is the single source of truth.

type Snapshot<T> = { data: T | undefined; error: Error | undefined; loading: boolean };

type Entry = {
  snapshot: Snapshot<unknown>;
  listeners: Set<() => void>;
  run: () => Promise<void>;
  inflight: Promise<void> | null;
};

const cache = new Map<string, Entry>();
const EMPTY: Snapshot<never> = { data: undefined, error: undefined, loading: false };

function keyOf(name: string, input: unknown): string {
  return name + ':' + JSON.stringify(input);
}

function getEntry(name: ActionName, input: object, key: string): Entry {
  let entry = cache.get(key);
  if (entry) return entry;
  const e: Entry = {
    snapshot: { data: undefined, error: undefined, loading: true },
    listeners: new Set(),
    inflight: null,
    run: () => {
      if (e.inflight) return e.inflight;
      if (!e.snapshot.loading) set(e, { ...e.snapshot, loading: true });
      e.inflight = call(name, input as never)
        .then(
          (data) => set(e, { data, error: undefined, loading: false }),
          (error: Error) => set(e, { data: e.snapshot.data, error, loading: false }),
        )
        .finally(() => {
          e.inflight = null;
        });
      return e.inflight;
    },
  };
  entry = e;
  cache.set(key, entry);
  return entry;
}

function set(e: Entry, s: Snapshot<unknown>) {
  e.snapshot = s;
  e.listeners.forEach((l) => l());
}

/** Refetch every mounted query; forget the rest. */
export function invalidateAll(): Promise<void> {
  const runs: Promise<void>[] = [];
  for (const [key, e] of cache) {
    if (e.listeners.size === 0) cache.delete(key);
    else {
      e.inflight = null;
      runs.push(e.run());
    }
  }
  return Promise.all(runs).then(() => undefined);
}

/** Test helper. */
export function clearQueryCache() {
  cache.clear();
}

export type QueryResult<T> = Snapshot<T> & { reload: () => Promise<void> };

/** Subscribe to an action's result. Pass `null` as input to skip the query. */
export function useQuery<N extends ActionName>(name: N, input: ActionInput<N> | null): QueryResult<ActionOutput<N>> {
  const key = input === null ? null : keyOf(name, input);
  const entry = key === null ? null : getEntry(name, input as object, key);

  const subscribe = useCallback(
    (cb: () => void) => {
      if (!entry) return () => {};
      entry.listeners.add(cb);
      return () => {
        entry.listeners.delete(cb);
      };
    },
    [entry],
  );
  const snapshot = useSyncExternalStore(subscribe, () => (entry ? entry.snapshot : EMPTY)) as Snapshot<ActionOutput<N>>;

  useEffect(() => {
    if (entry && entry.snapshot.data === undefined && !entry.inflight && !entry.snapshot.error) void entry.run();
  }, [entry]);

  const reload = useCallback(() => (entry ? entry.run() : Promise.resolve()), [entry]);
  return { ...snapshot, reload };
}

/** Run a mutating action, then refresh all mounted queries. */
export async function mutate<N extends ActionName>(name: N, input: ActionInput<N>): Promise<ActionOutput<N>> {
  const result = await call(name, input);
  await invalidateAll();
  return result;
}

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRefreshVersion } from "../app/RefreshContext";
import type { Page } from "../lib/workspace-api";
import { errorMessage } from "./lib";

type DirectorySnapshot<Row> = {
  identity: symbol;
  rows: Row[];
  total: number;
  loading: boolean;
  error: string | null;
  hasMore: boolean;
};
type DirectoryRun<Row> = DirectorySnapshot<Row> & {
  active: boolean;
  nextPage: number;
  load: (page: number) => Promise<Page<Row>>;
};

/** Capture one loader per resource identity; obsolete pages never replace it. */
export function usePagedDirectory<Row extends { id: string }>(
  loadPage: (page: number) => Promise<Page<Row>>,
  dependencies: readonly unknown[],
  enabled = true,
) {
  const refresh = useRefreshVersion();
  const [version, setVersion] = useState(0);
  const identity = useMemo(() => Symbol(), [...dependencies, enabled, refresh, version]);
  const currentIdentity = useRef(identity);
  currentIdentity.current = identity;
  const runRef = useRef<DirectoryRun<Row> | null>(null);
  const [snapshot, setSnapshot] = useState<DirectorySnapshot<Row> | null>(null);

  const requestPage = useCallback(async (run: DirectoryRun<Row>, retry = false) => {
    const isCurrent = () => run.active && currentIdentity.current === run.identity;
    if (!isCurrent() || run.loading || !run.hasMore || (run.error !== null && !retry)) return;
    // Guard before awaiting or rendering so repeated scroll callbacks cannot
    // request the same next page concurrently.
    run.loading = true;
    run.error = null;
    const publish = () => setSnapshot({
      identity: run.identity, rows: run.rows, total: run.total,
      loading: run.loading, error: run.error, hasMore: run.hasMore,
    });
    publish();
    const page = run.nextPage;
    try {
      const result = await run.load(page);
      if (!isCurrent()) return;
      const rowsById = new Map(run.rows.map(row => [row.id, row]));
      for (const row of result.items) rowsById.set(row.id, row);
      run.rows = [...rowsById.values()];
      run.total = result.total;
      run.nextPage = page + 1;
      run.hasMore = result.items.length > 0 && run.nextPage < result.totalPages;
    } catch (error) {
      if (!isCurrent()) return;
      // Keep loaded rows and nextPage; only an explicit retry repeats the failed
      // request, so scrolling cannot create an automatic retry loop.
      run.error = errorMessage(error);
    } finally {
      if (isCurrent()) {
        run.loading = false;
        publish();
      }
    }
  }, []);

  useEffect(() => {
    if (!enabled) {
      runRef.current = null;
      return;
    }
    const run: DirectoryRun<Row> = {
      identity, active: true, nextPage: 0, load: loadPage,
      rows: [], total: 0, loading: false, error: null, hasMore: true,
    };
    runRef.current = run;
    void requestPage(run);
    return () => {
      run.active = false;
      if (runRef.current === run) runRef.current = null;
    };
  }, [identity, requestPage]);

  const loadMore = useCallback(() => {
    if (runRef.current) void requestPage(runRef.current);
  }, [requestPage]);
  const retry = useCallback(() => {
    const run = runRef.current;
    if (run && run.error !== null) void requestPage(run, true);
  }, [requestPage]);
  const reload = useCallback(() => {
    if (runRef.current) runRef.current.active = false;
    runRef.current = null;
    setVersion(value => value + 1);
  }, []);

  const current = snapshot?.identity === identity ? snapshot : null;
  return {
    rows: current?.rows ?? [],
    total: current?.total ?? 0,
    loading: enabled && (current?.loading ?? true),
    error: current?.error ?? null,
    hasMore: current?.hasMore ?? false,
    loadMore, retry, reload,
  };
}

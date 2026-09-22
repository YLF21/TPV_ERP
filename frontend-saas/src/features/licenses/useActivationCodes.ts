import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRefreshVersion } from "../../app/RefreshContext";
import { ApiError, request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import type { Page } from "../../lib/workspace-api";
import { errorMessage } from "../../shared/lib";

export type ActivationCode = {
  id: string; licenseId: string; reference: string;
  companyId: string; companyName: string; storeId: string; storeName: string; internalCode: string | null; storeCode: string;
  pairingCode: string; pairingExpiresAt: string;
};
type ActivationPage = Page<ActivationCode> & { serverNow: string };
type Snapshot = { identity: symbol; data: ActivationPage | null; requestedAt: number; loading: boolean; error: string | null; denied: boolean };

export function useActivationCodes(credentials: Credentials, page: number) {
  const refresh = useRefreshVersion();
  const identity = useMemo(() => Symbol(), [credentials.accessToken, page, refresh]);
  const currentIdentity = useRef(identity); currentIdentity.current = identity;
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const refreshCurrent = useRef<(() => void) | null>(null);
  useEffect(() => {
    let active = true, pending = false, queued = false;
    const current = () => active && currentIdentity.current === identity;
    async function load(force = false) {
      if (!current()) return;
      if (pending) { if (force) queued = true; return; }
      pending = true;
      setSnapshot(previous => ({ identity, data: previous?.identity === identity ? previous.data : null,
        requestedAt: previous?.identity === identity ? previous.requestedAt : 0, loading: true, error: null,
        denied: previous?.identity === identity ? previous.denied : false }));
      const requestedAt = performance.now();
      try {
        const data = await request<ActivationPage>(credentials, `/api/v1/admin/license-workspace/activation-codes?page=${page}&size=25`);
        if (current()) setSnapshot({ identity, data, requestedAt, loading: false, error: null, denied: false });
      } catch (failure) {
        const denied = failure instanceof ApiError && (failure.status === 401 || failure.status === 403);
        if (current()) setSnapshot(previous => ({ identity, data: !denied && previous?.identity === identity ? previous.data : null,
          requestedAt: previous?.identity === identity ? previous.requestedAt : 0, loading: false, error: errorMessage(failure), denied }));
      } finally {
        pending = false;
        if (queued && current()) { queued = false; void load(); }
      }
    }
    const reload = () => { void load(true); };
    const refreshVisible = () => { if (document.visibilityState === "visible") void load(); };
    refreshCurrent.current = reload; reload();
    // Consumption in a store or regeneration in another session can invalidate
    // a displayed code before its expiry. Refresh without overlapping requests.
    const interval = window.setInterval(refreshVisible, 30_000);
    window.addEventListener("focus", refreshVisible);
    document.addEventListener("visibilitychange", refreshVisible);
    return () => {
      active = false; window.clearInterval(interval);
      window.removeEventListener("focus", refreshVisible);
      document.removeEventListener("visibilitychange", refreshVisible);
      if (refreshCurrent.current === reload) refreshCurrent.current = null;
    };
  }, [identity]);
  const current = snapshot?.identity === identity ? snapshot : null;
  const reload = useCallback(() => refreshCurrent.current?.(), []);
  return { data: current?.data ?? null, requestedAt: current?.requestedAt ?? 0, loading: current?.loading ?? true, error: current?.error ?? null, denied: current?.denied ?? false, reload };
}

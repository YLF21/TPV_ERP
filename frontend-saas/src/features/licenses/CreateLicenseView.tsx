import { useEffect, useRef, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { api, request } from "../../lib/api";
import { workspaceApi, type StoreRow } from "../../lib/workspace-api";
import type { Credentials } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { EmptyState } from "../../shared/ui";
import { LoadState, PageButtons } from "../../shared/workspace-ui";
import { copyText, errorMessage, formatDate } from "../../shared/lib";
import { usePagedDirectory } from "../../shared/usePagedDirectory";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { CompanyPicker } from "../../shared/companies/CompanyPicker";
import { useLicenseLabels } from "./license-labels";
import { useActivationCodes, type ActivationCode } from "./useActivationCodes";
import { activationCountdown, remainingActivationSeconds } from "./activation-time.mjs";
import "./create-license.css";

type Props = { credentials: Credentials; permissions: Set<string>; onChanged: () => Promise<boolean> };
type CreatedLicense = { id: string; reference: string; companyId: string; storeId: string;
  pairingCodeId: string; pairingCode: string; pairingExpiresAt: string; serverNow: string };
type IssuedCode = { row: ActivationCode; serverNow: string; requestedAt: number; confirmedAt: number };

export function CreateLicenseView(props: Props) {
  return props.permissions.has("ADD_COMPANY") ? <LicenseCreator key={props.credentials.accessToken} {...props} /> : null;
}

function LicenseCreator({ credentials, permissions, onChanged }: Props) {
  const l = useWorkspaceLabels(); const own = useLicenseLabels();
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const [companyId, setCompanyId] = useState("");
  const [codePage, setCodePage] = useState(0);
  const codes = useActivationCodes(credentials, codePage);
  const stores = usePagedDirectory<StoreRow>(page => workspaceApi.stores(credentials, {
    companyId, page, size: 25, sortBy: "internalCode", sortDirection: "ASC",
  }), [credentials.accessToken, companyId], Boolean(companyId));
  const [busyStore, setBusyStore] = useState<string | null>(null);
  const [deletingCode, setDeletingCode] = useState<string | null>(null);
  const [removedCodeIds, setRemovedCodeIds] = useState<Set<string>>(() => new Set());
  const [notice, setNotice] = useState<Notice | null>(null);
  const [issued, setIssued] = useState<IssuedCode | null>(null);
  const [invalidatedAt, setInvalidatedAt] = useState<Record<string, number>>({});
  const [tick, setTick] = useState(() => performance.now());
  const mounted = useRef(false); const writing = useRef(false);
  const storeViewport = useRef<HTMLDivElement>(null);
  const codesHeading = useRef<HTMLHeadingElement>(null);
  const busy = Boolean(busyStore || deletingCode);
  useEffect(() => {
    mounted.current = true;
    const timer = window.setInterval(() => setTick(performance.now()), 1000);
    const update = () => setTick(performance.now());
    document.addEventListener("visibilitychange", update);
    return () => { mounted.current = false; window.clearInterval(timer); document.removeEventListener("visibilitychange", update); };
  }, []);
  useEffect(() => { if (stores.rows.length === 0) storeViewport.current?.scrollTo({ top: 0 }); }, [stores.rows.length]);
  useEffect(() => {
    const viewport = storeViewport.current;
    if (!viewport || stores.loading || stores.error || !stores.hasMore || busy) return;
    const loadNearEnd = () => { if (viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 100) stores.loadMore(); };
    const observer = new ResizeObserver(loadNearEnd);
    observer.observe(viewport); viewport.addEventListener("scroll", loadNearEnd, { passive: true }); loadNearEnd();
    return () => { observer.disconnect(); viewport.removeEventListener("scroll", loadNearEnd); };
  }, [companyId, stores.loading, stores.error, stores.hasMore, stores.rows.length, stores.loadMore, busy]);
  useEffect(() => {
    if (codes.data && codePage > 0 && codePage >= codes.data.totalPages) setCodePage(Math.max(0, codes.data.totalPages - 1));
  }, [codes.data, codePage]);

  async function generate(store: StoreRow) {
    if (writing.current || !store.active || store.companyId !== companyId || companies.loading || companies.error) return;
    writing.current = true; setBusyStore(store.id); setNotice(null);
    const requestedAt = performance.now();
    try {
      const result = await request<CreatedLicense>(credentials, "/api/v1/admin/license-workspace", { method: "POST", body: { storeId: store.id } });
      if (!mounted.current) return;
      const confirmedAt = performance.now();
      // Older snapshots must not revive a replaced code, even if refreshing
      // continues to fail beyond the lifetime of the newly issued code.
      setInvalidatedAt(current => ({ ...current, [result.storeId]: confirmedAt }));
      setIssued({ row: { id: result.pairingCodeId, licenseId: result.id, reference: result.reference,
        companyId: result.companyId, companyName: store.companyName, storeId: result.storeId, storeName: store.name,
        storeCode: store.code, internalCode: store.internalCode, pairingCode: result.pairingCode, pairingExpiresAt: result.pairingExpiresAt },
        serverNow: result.serverNow, requestedAt, confirmedAt });
      setCodePage(0);
      // The global refresh invalidates this view's resources on success.
      // Recover the codes independently if an unrelated dashboard read fails.
      void onChanged().then(refreshed => { if (mounted.current && !refreshed) codes.reload(); });
      setNotice({ type: "success", text: own("generated") });
    } catch (failure) { if (mounted.current) setNotice({ type: "error", text: errorMessage(failure) }); }
    finally { if (mounted.current) { writing.current = false; setBusyStore(null); } }
  }
  async function removeCode(row: ActivationCode, trigger: HTMLButtonElement) {
    if (writing.current || !permissions.has("REGENERATE_PAIRING_CODE") || removedCodeIds.has(row.id)) return;
    writing.current = true; setDeletingCode(row.id); setNotice(null);
    try {
      await request<void>(credentials, `/api/v1/admin/license-workspace/activation-codes/${encodeURIComponent(row.id)}`, { method: "DELETE" });
      if (!mounted.current) return;
      const restoreFocus = document.activeElement === trigger;
      // Replayed reads cannot revive this exact code. A newer code for the same
      // store remains visible, including one generated from another session.
      setRemovedCodeIds(current => new Set([...current, row.id]));
      setIssued(current => current?.row.id === row.id ? null : current);
      setNotice({ type: "success", text: own("removedCode") });
      codes.reload();
      if (restoreFocus) requestAnimationFrame(() => codesHeading.current?.focus());
    } catch (failure) { if (mounted.current) setNotice({ type: "error", text: errorMessage(failure) }); }
    finally { if (mounted.current) { writing.current = false; setDeletingCode(null); } }
  }
  async function copyCode(row: ActivationCode, serverNow: string, requestedAt: number) {
    if (writing.current || removedCodeIds.has(row.id)) return;
    if (remainingActivationSeconds(row.pairingExpiresAt, serverNow, requestedAt, performance.now()) <= 0) {
      setTick(performance.now()); setNotice({ type: "error", text: own("codeExpired") }); codes.reload(); return;
    }
    try {
      await copyText(row.pairingCode);
      if (mounted.current && !writing.current) setNotice({ type: "success", text: own("copiedCode") });
    } catch { if (mounted.current && !writing.current) setNotice({ type: "error", text: own("copyCodeFailed") }); }
  }
  function codeActions(row: ActivationCode, serverNow: string, requestedAt: number) {
    return <span className="saas-activation-code-actions">
      <strong className="saas-activation-code">{row.pairingCode}</strong>
      <button type="button" className="small-button" disabled={busy} onClick={() => void copyCode(row, serverNow, requestedAt)}>{own("copyCode")}</button>
      {permissions.has("REGENERATE_PAIRING_CODE") && <button type="button" className="small-button" disabled={busy}
        onClick={event => void removeCode(row, event.currentTarget)}>{own(deletingCode === row.id ? "removingCode" : "removeCode")}</button>}
    </span>;
  }
  const monotonicNow = Math.max(tick, performance.now());
  const visibleCodes = (codes.data?.items ?? []).filter(row => remainingActivationSeconds(row.pairingExpiresAt, codes.data!.serverNow, codes.requestedAt, monotonicNow) > 0
    && !removedCodeIds.has(row.id) && codes.requestedAt >= (invalidatedAt[row.storeId] ?? 0));
  const issuedSeconds = issued ? remainingActivationSeconds(issued.row.pairingExpiresAt, issued.serverNow, issued.requestedAt, monotonicNow) : 0;
  // Only a GET started after the POST response can observe its committed write.
  const reconciled = Boolean(issued && codes.data && codes.requestedAt >= issued.confirmedAt);
  useEffect(() => {
    if (issued && (reconciled || codes.denied || issuedSeconds <= 0)) setIssued(null);
  }, [issued, reconciled, codes.denied, issuedSeconds]);
  const showIssued = issued && issuedSeconds > 0 && !reconciled && !codes.denied
    && !visibleCodes.some(row => row.pairingCode === issued.row.pairingCode);
  return <section className="content-section saas-create-license-workspace">
    <h2>{l("createLicense")}</h2>
    <p className="saas-activation-hint">{own("expiresSoon")}</p>
    {notice && <div className={"notice " + notice.type} role={notice.type === "error" ? "alert" : "status"}>{notice.text}</div>}
    <div className="saas-activation-company">
      <CompanyPicker companies={companies.data ?? []} value={companyId} onChange={value => { if (!writing.current) { setCompanyId(value); setNotice(null); } }} disabled={busy || companies.loading || Boolean(companies.error)} />
      <LoadState {...companies} />
    </div>
    {!companyId ? <p>{own("chooseCompany")}</p> : <section className="saas-activation-stores" aria-label={own("companyStores")}>
      <h3>{own("companyStores")}</h3>
      <div className="table-wrap saas-activation-store-scroll" ref={storeViewport}>
        <table aria-label={own("companyStores")}>
          <thead><tr><th>{l("internalCode")}</th><th>{l("store")}</th><th>{l("status")}</th><th>{own("activationCode")}</th></tr></thead>
          <tbody>{stores.rows.map(store => <tr key={store.id}>
            <td>{store.internalCode ?? store.code}</td><td>{store.name}</td><td>{l(store.active ? "active" : "inactive")}</td>
            <td><button type="button" className="small-button" disabled={!store.active || busy || companies.loading || Boolean(companies.error)}
              title={!store.active ? own("inactiveStore") : undefined} onClick={() => void generate(store)}>
              {busyStore === store.id ? own("generating") : own("generate")}
            </button></td>
          </tr>)}</tbody>
        </table>
      </div>
      <LoadState loading={stores.loading} error={stores.error} reload={stores.retry} />
      {!stores.loading && !stores.error && stores.rows.length === 0 && <EmptyState text={l("empty")} />}
      {stores.hasMore && !stores.error && <button type="button" className="small-button" disabled={stores.loading || busy} onClick={stores.loadMore}>{own("moreStores")}</button>}
    </section>}
    <section className="saas-active-codes" aria-label={own("activeCodes")}>
      <div className="saas-active-codes-heading"><h3 ref={codesHeading} tabIndex={-1}>{own("activeCodes")}</h3>
        <button type="button" className="small-button" onClick={codes.reload} disabled={codes.loading || busy}>{own("refreshCodes")}</button>
      </div>
      {showIssued && <div className="saas-issued-code">
        <span>{issued.row.companyName} · {issued.row.internalCode ?? issued.row.storeCode} · {issued.row.storeName}</span>
        {codeActions(issued.row, issued.serverNow, issued.requestedAt)}
        <span>{own("remaining")}: <time role="timer" aria-live="off">{activationCountdown(issuedSeconds)}</time></span>
      </div>}
      <LoadState loading={codes.loading} error={codes.error} reload={codes.reload} />
      {visibleCodes.length > 0 && <div className="table-wrap saas-active-code-scroll"><table aria-label={own("activeCodes")}>
        <thead><tr><th>{l("company")}</th><th>{l("store")}</th><th>{l("reference")}</th><th>{own("activationCode")}</th><th>{own("codeExpiry")}</th><th>{own("remaining")}</th></tr></thead>
        <tbody>{visibleCodes.map(row => <tr key={row.id}>
          <td>{row.companyName}</td><td>{row.internalCode ?? row.storeCode} · {row.storeName}</td><td>{row.reference}</td>
          <td>{codeActions(row, codes.data!.serverNow, codes.requestedAt)}</td><td>{formatDate(row.pairingExpiresAt)}</td>
          <td><time role="timer" aria-live="off">{activationCountdown(remainingActivationSeconds(row.pairingExpiresAt, codes.data!.serverNow, codes.requestedAt, monotonicNow))}</time></td>
        </tr>)}</tbody>
      </table></div>}
      {!codes.loading && !codes.error && visibleCodes.length === 0 && !showIssued && <EmptyState text={l("empty")} />}
      {codes.data && codes.data.totalPages > 1 && <PageButtons page={codePage} totalPages={codes.data.totalPages} onPage={page => { if (!writing.current) setCodePage(page); }} />}
    </section>
  </section>;
}

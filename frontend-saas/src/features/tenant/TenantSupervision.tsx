import { FormEvent, useEffect, useState } from "react";
import { DOCUMENT_STATUSES, DOCUMENT_TYPES, tenantApi, type DocumentFilters, type DocumentStatus, type DocumentType, type TenantDocument, type TenantPage, type TenantStock, type TenantStoreAccess, type TenantSync } from "../../lib/tenant-api";
import type { Credentials } from "../../lib/types";
import { errorMessage, formatCurrency, formatDate, formatQuantity } from "../../shared/lib";
import { EmptyState, Metric, RetryError, SectionHeader, Segmented } from "../../shared/ui";
import { activeLocale } from "../../i18n";
import { authorizedStoreSelection } from "./access-selection.mjs";
import { useTenantLabels } from "./labels";

type Mode = "documents" | "stock" | "sync";
type Result = { documents?: TenantPage<TenantDocument>; stock?: TenantPage<TenantStock>; sync?: TenantSync };
const initialFilters: DocumentFilters = { types: DOCUMENT_TYPES, statuses: DOCUMENT_STATUSES, from: null, to: null, numberContains: null };

export function TenantSupervision({ credentials, stores, revision }: { credentials: Credentials; stores: TenantStoreAccess[]; revision: number }) {
  const l = useTenantLabels();
  const [mode, setMode] = useState<Mode>("documents");
  const [selectedIds, setSelectedIds] = useState<string[]>(() => stores.length === 1 ? [stores[0].storeId] : []);
  const [type, setType] = useState<DocumentType | "">("");
  const [status, setStatus] = useState<DocumentStatus | "">("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [number, setNumber] = useState("");
  const [filters, setFilters] = useState(initialFilters);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [retry, setRetry] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ key: string; value: Result } | null>(null);
  const storeIds = authorizedStoreSelection(stores, selectedIds);
  const canQuery = storeIds.length > 0 && (mode === "documents" || storeIds.length === 1);
  const cursor = cursors[cursors.length - 1];
  const requestKey = JSON.stringify([credentials.accessToken, credentials.companyId, mode, storeIds, filters, cursor, revision, retry]);
  const value = result?.key === requestKey ? result.value : null;
  const page = value?.documents ?? value?.stock;

  useEffect(() => {
    let cancelled = false;
    setError(null);
    if (!canQuery) { setLoading(false); setResult(null); return; }
    setLoading(true);
    const query: Promise<Result> = mode === "documents"
      ? tenantApi.documents(credentials, storeIds, filters, cursor).then(documents => ({ documents }))
      : mode === "stock" ? tenantApi.stock(credentials, storeIds[0], cursor).then(stock => ({ stock }))
        : tenantApi.sync(credentials, storeIds[0]).then(sync => ({ sync }));
    query.then(next => { if (!cancelled) setResult({ key: requestKey, value: next }); })
      .catch(failure => { if (!cancelled) { setResult(null); setError(errorMessage(failure)); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [requestKey]);

  function changeStores(id: string, checked: boolean) {
    setSelectedIds(previous => checked ? [...previous, id] : previous.filter(value => value !== id));
    setCursors([null]);
  }
  function search(event: FormEvent) {
    event.preventDefault(); setCursors([null]);
    setFilters({ types: type ? [type] : DOCUMENT_TYPES, statuses: status ? [status] : DOCUMENT_STATUSES, from: from || null, to: to || null, numberContains: number.trim() || null });
    setRetry(value => value + 1);
  }

  return <section id="tenant-supervision" className="content-section tenant-supervision">
    <SectionHeader title={l("supervision")} subtitle={l("readOnly")} />
    <fieldset className="tenant-store-selector"><legend>{l("stores")}</legend>
      {stores.map(store => <label key={store.storeId}>
        <input type="checkbox" checked={storeIds.includes(store.storeId)} onChange={event => changeStores(store.storeId, event.target.checked)} />
        <span>{store.internalCode ?? store.code} · {store.name}{!store.active ? ` · ${l("inactive")}` : ""}</span>
      </label>)}
      {!stores.length && <EmptyState text={l("empty")} />}
    </fieldset>
    <Segmented value={mode} options={[["documents", l("documents")], ["stock", l("stock")], ["sync", l("sync")]]}
      onChange={next => { setMode(next as Mode); setCursors([null]); }} />
    {mode === "documents" && <form className="compact-form-grid tenant-document-filters" onSubmit={search}>
      <label>{l("type")}<select className="control-input" aria-label={l("type")} value={type} onChange={event => setType(event.target.value as DocumentType | "")}>
        <option value="">{l("all")}</option>{DOCUMENT_TYPES.map(value => <option value={value} key={value}>{l(value)}</option>)}
      </select></label>
      <label>{l("status")}<select className="control-input" aria-label={l("status")} value={status} onChange={event => setStatus(event.target.value as DocumentStatus | "")}>
        <option value="">{l("all")}</option>{DOCUMENT_STATUSES.map(value => <option value={value} key={value}>{l(value)}</option>)}
      </select></label>
      <label>{l("from")}<input className="control-input" type="date" value={from} max={to || undefined} onChange={event => setFrom(event.target.value)} /></label>
      <label>{l("to")}<input className="control-input" type="date" value={to} min={from || undefined} onChange={event => setTo(event.target.value)} /></label>
      <label>{l("number")}<input className="control-input" value={number} maxLength={120} onChange={event => setNumber(event.target.value)} /></label>
      <button type="submit" className="secondary-button" disabled={loading || !canQuery}>{l("search")}</button>
    </form>}
    {!canQuery ? <EmptyState text={storeIds.length === 0 ? l("chooseStores") : l("oneStore")} /> : <>
      {loading && <p role="status">{l("loading")}</p>}
      {error && <RetryError message={error} onRetry={() => setRetry(value => value + 1)} />}
      {value?.documents && (value.documents.items.length ? <div className="table-wrap"><table>
        <thead><tr>{(["date", "number", "type", "status", "store", "customer", "total"] as const).map(key => <th key={key}>{l(key)}</th>)}</tr></thead>
        <tbody>{value.documents.items.map(row => <tr key={`${row.storeId}:${row.documentId}`}>
          <td><time dateTime={row.date}>{new Intl.DateTimeFormat(activeLocale, { timeZone: "UTC" }).format(new Date(row.date))}</time></td><td>{row.number}</td><td>{l(row.type)}</td><td>{l(row.status)}</td>
          <td>{stores.find(store => store.storeId === row.storeId)?.name ?? row.storeCode ?? row.storeId}</td>
          <td>{row.customerName ?? "—"}</td><td>{formatCurrency(row.total, row.currency)}</td>
        </tr>)}</tbody>
      </table></div> : <EmptyState text={l("empty")} />)}
      {value?.stock && (value.stock.items.length ? <div className="table-wrap"><table>
        <thead><tr><th>{l("product")}</th><th>{l("warehouse")}</th><th>{l("quantity")}</th></tr></thead>
        <tbody>{value.stock.items.map(row => <tr key={`${row.productId}:${row.warehouseId}`}><td>{row.productId}</td><td>{row.warehouseId}</td><td>{formatQuantity(row.quantity)}</td></tr>)}</tbody>
      </table></div> : <EmptyState text={l("empty")} />)}
      {value?.sync && <div className="metric-grid">
        <Metric label={l("received")} value={value.sync.received} /><Metric label={l("projected")} value={value.sync.projected} />
        <Metric label={l("ignored")} value={value.sync.ignored} /><Metric label={l("errors")} value={value.sync.error} tone={value.sync.error ? "warning" : undefined} />
        <Metric label={l("oldest")} value={value.sync.oldestReceivedAt ? formatDate(value.sync.oldestReceivedAt) : "—"} />
      </div>}
      {mode !== "sync" && <nav className="pagination-controls" aria-label={l("page")}>
        <button className="small-button" type="button" disabled={loading || cursors.length === 1} onClick={() => setCursors(previous => previous.slice(0, -1))}>{l("previous")}</button>
        <span>{l("page")} {cursors.length}</span>
        <button className="small-button" type="button" disabled={loading || !page?.hasMore || !page.nextCursor} onClick={() => { if (page?.nextCursor) setCursors(previous => [...previous, page.nextCursor]); }}>{l("next")}</button>
      </nav>}
    </>}
  </section>;
}

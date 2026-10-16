import { useCallback, useEffect, useRef, useState } from "react";
import { Eye, FilePdf, FileXls, Plus, Trash } from "@phosphor-icons/react";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";
import { apiRequest } from "../../../packages/app-common/src/api/client";
import { ErpSelect } from "../../../packages/app-common/src/components/ErpSelect";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { WarehouseSearchFrame } from "../../../packages/app-common/src/components/WarehouseSearchFrame";
import { ReportDateRangeFilter, type ReportDateRange } from "../../../packages/app-common/src/components/ReportDateRangeFilter";
import { useReportDateOptions } from "../../../packages/app-common/src/components/useReportDateOptions";
import { WindowCloseButton } from "../../../packages/app-common/src/components/WindowCloseButton";
import { useWarehouseListShortcuts, WAREHOUSE_LIST_SHORTCUTS } from "../../../packages/app-common/src/components/useWarehouseListShortcuts";
import { StockCountDocumentWindow } from "./StockCountDocumentWindow";
import { StockCountTable } from "./StockCountTable";
import { countText } from "./stockCountMessages";
import { cancelStockCount, exportStockCount, loadStockCount, loadStockCounts, type StockCountDetail, type StockCountSummary, type WarehouseOption } from "./warehouseOperationsApi";
import "./stockCount.css";

export function StockCountScreen({ session, locale = "es", t }: { session: UserSession; locale?: LocaleCode; t: (key: string) => string }) {
  const rootRef = useRef<HTMLElement>(null);
  const token = session.accessToken ?? ""; const c = countText(locale);
  const allowed = session.permissions.some((permission) => ["ADMIN", "GESTION_ALMACEN", "STOCK_ADJUST"].includes(permission));
  const [rows, setRows] = useState<StockCountSummary[]>([]); const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [search, setSearch] = useState(""); const [warehouse, setWarehouse] = useState(""); const [status, setStatus] = useState("");
  const [from, setFrom] = useState(""); const [to, setTo] = useState(""); const [limit, setLimit] = useState(50);
  const [quickRange, setQuickRange] = useState<ReportDateRange>({ from: "", to: "", label: "" });
  const { dateOptions, dateOptionsFailed, retryDateOptions } = useReportDateOptions("stockCounts", token, allowed);
  const [selected, setSelected] = useState(""); const [editor, setEditor] = useState<{ document: StockCountDetail | null } | null>(null);
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false); const [loading, setLoading] = useState(true); const [cancelPrompt, setCancelPrompt] = useState(false);
  useWarehouseListShortcuts(rootRef, allowed, Boolean(editor) || cancelPrompt);
  const refresh = useCallback(async () => {
    if (!allowed) return;
    setLoading(true);
    try { const [documents, options] = await Promise.all([loadStockCounts(token), apiRequest<{ warehouses: WarehouseOption[] }>("/stock-counts/resources", { token })]); setRows(documents); setWarehouses(options.warehouses); }
    catch (cause) { setError(cause instanceof Error ? cause.message : c("error")); }
    finally { setLoading(false); }
  }, [token, allowed]);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => { setLimit(50); setSelected(""); }, [search, warehouse, status, from, to]);
  const filtered = rows.filter((row) => {
    const date = row.documentDate ?? row.createdAt.slice(0, 10);
    return (!warehouse || row.warehouseId === warehouse) && (!status || row.status === status)
      && (!from || date >= from) && (!to || date <= to)
      && (!search.trim() || `${row.number} ${row.notes ?? ""}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  });
  const selection = filtered.find((row) => row.id === selected);
  const warehouseName = (id: string) => warehouses.find((item) => item.id === id)?.name ?? "—";
  async function run(action: () => Promise<void>) { if (busy) return; setBusy(true); setError(""); try { await action(); } catch (cause) { setError(cause instanceof Error ? cause.message : c("error")); } finally { setBusy(false); } }
  function open(id: string) { void run(async () => setEditor({ document: await loadStockCount(id, token) })); }
  async function exportFile(format: "pdf" | "xlsx") {
    if (!selection) return; const blob = await exportStockCount(selection.id, format, token); const url = URL.createObjectURL(blob);
    const link = document.createElement("a"); link.href = url; link.download = `${selection.number}.${format}`; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 60000);
  }
  if (!allowed) return <p role="alert">{c("noAccess")}</p>;
  const columns = [ ["number", 185], ["date", 160], ["warehouse", 210], ["status", 140], ["articles", 100], ["user", 180], ["notes", 250] ]
    .map(([key, width]) => ({ key: String(key), defaultWidth: Number(width), label: c(key as "number") }));
  return <section ref={rootRef} data-warehouse-heading="unified" className="inventory-screen gestion-warehouse-screen">
    <div className="inventory-module-title"><h1>{t("home.warehouse")}</h1></div>
    <header className="gestion-warehouse-titlebar"><div><h2>{c("title")}</h2><p>{c("subtitle")}</p></div>
      <div className="gestion-warehouse-title-actions gestion-warehouse-actions"><button className="primary gestion-warehouse-command" aria-keyshortcuts={WAREHOUSE_LIST_SHORTCUTS.create} disabled={busy || loading || !warehouses.some((item) => item.active !== false)} onClick={() => setEditor({ document: null })}><span><Plus size={16} aria-hidden="true" />{c("create")}</span><kbd aria-hidden="true">{WAREHOUSE_LIST_SHORTCUTS.create}</kbd></button></div>
    </header>
    {error && <p role="alert" className="inventory-error">{error}</p>}
    <div className="inventory-filters gestion-warehouse-toolbar gestion-toolbar-layout">
      <label>{t("warehouse.management.search")}<WarehouseSearchFrame><input className="erp-search-input" type="search" value={search} placeholder={c("search")} onChange={(event) => setSearch(event.target.value)} /></WarehouseSearchFrame></label>
      <label>{c("warehouse")}<ErpSelect aria-label={c("warehouse")} value={warehouse} options={[{ value: "", label: c("all") }, ...warehouses.map((item) => ({ value: item.id, label: item.name }))]} onChange={setWarehouse} /></label>
      <label className="gestion-warehouse-status-filter" data-filter-active={Boolean(status)}>{c("status")}<ErpSelect aria-label={c("status")} value={status} options={[{ value: "", label: c("all") }, ...["DRAFT", "CONFIRMED", "CANCELLED"].map((value) => ({ value, label: t(`warehouse.count.status.${value}`) }))]} onChange={setStatus} /></label>
      <div className="inventory-list-actions gestion-warehouse-actions gestion-warehouse-list-actions gestion-toolbar-end">
        <button disabled={busy || !selection} onClick={() => open(selected)}><span><Eye size={16} aria-hidden="true" />{t("warehouseDocument.open")}</span></button>
        <button disabled={busy || selection?.status !== "DRAFT"} onClick={() => setCancelPrompt(true)}><span><Trash size={16} aria-hidden="true" />{c("cancelDocument")}</span></button>
        <button className="primary gestion-warehouse-command" aria-keyshortcuts={WAREHOUSE_LIST_SHORTCUTS.excel} disabled={busy || !selection} onClick={() => void run(() => exportFile("xlsx"))}><span><FileXls size={16} aria-hidden="true" />{t("warehouse.count.exportExcel")}</span><kbd aria-hidden="true">{WAREHOUSE_LIST_SHORTCUTS.excel}</kbd></button>
        <button className="primary gestion-warehouse-command" aria-keyshortcuts={WAREHOUSE_LIST_SHORTCUTS.pdf} disabled={busy || !selection} onClick={() => void run(() => exportFile("pdf"))}><span><FilePdf size={16} aria-hidden="true" />{t("warehouse.count.exportPdf")}</span><kbd aria-hidden="true">{WAREHOUSE_LIST_SHORTCUTS.pdf}</kbd></button>
      </div>
    </div>
    <ErpFilterChips translate={t} chips={[
      { key: "search", label: t("warehouse.management.search"), value: search, onRemove: () => setSearch("") },
      { key: "warehouse", label: c("warehouse"), value: warehouse ? warehouseName(warehouse) : "", onRemove: () => setWarehouse("") },
      { key: "status", label: c("status"), value: status ? t(`warehouse.count.status.${status}`) : "", onRemove: () => setStatus("") },
      { key: "from", label: t("warehouse.report.from"), value: from, onRemove: () => setFrom("") },
      { key: "to", label: t("warehouse.report.to"), value: to, onRemove: () => setTo("") }
    ].filter((chip) => chip.value)} onClear={() => { setSearch(""); setWarehouse(""); setStatus(""); setFrom(""); setTo(""); }} />
    {loading && <p role="status">{t("common.loading")}</p>}
    <div className="inventory-list-scroll" onScroll={(event) => { const node = event.currentTarget; if (node.scrollHeight - node.scrollTop - node.clientHeight < 200) setLimit((value) => value + 50); }}>
      <StockCountTable rows={filtered} limit={limit} locale={locale}
        sortValue={(row, key) => key === "warehouse" ? warehouseName(row.warehouseId) : key === "status" ? t(`warehouse.count.status.${row.status}`) : key === "articles" ? row.lineCount : key === "date" ? row.documentDate ?? row.createdAt : key === "user" ? row.createdByName : key === "notes" ? row.notes : row.number} columns={columns} session={session} tableKey="warehouse.inventory.documents" empty={c("empty")} selected={selected} onSelect={setSelected} onOpen={open}
        cell={(row, key) => key === "warehouse" ? warehouseName(row.warehouseId) : key === "status" ? t(`warehouse.count.status.${row.status}`) : key === "articles" ? row.lineCount
          : key === "date" ? new Date(`${row.documentDate ?? row.createdAt.slice(0, 10)}T12:00:00`).toLocaleDateString(locale)
          : key === "user" ? row.createdByName ?? "—" : key === "notes" ? row.notes || "—" : row.number} />
    </div>
    {editor && <StockCountDocumentWindow initial={editor.document} warehouses={warehouses} session={session} locale={locale} t={t} onSaved={() => void refresh()} onClose={() => { setEditor(null); void refresh(); }} />}
    {cancelPrompt && <div className="inventory-confirm-overlay"><section role="alertdialog" aria-label={c("cancelDocument")}><header className="inventory-confirm-header"><h2>{c("cancelDocument")}</h2><WindowCloseButton type="button" aria-label={t("common.close")} disabled={busy} onClick={() => setCancelPrompt(false)} /></header><p>{c("cancelText")}</p><footer className="erp-dialog-actions-row"><button className="erp-dialog-action-cancel erp-dialog-dismiss" autoFocus disabled={busy} onClick={() => setCancelPrompt(false)}>{c("cancel")}</button><button className="erp-dialog-action-cancel" disabled={busy} onClick={() => void run(async () => { await cancelStockCount(selected, token); setCancelPrompt(false); await refresh(); })}>{c("cancelDocument")}</button></footer></section></div>}
    {dateOptionsFailed && <div className="report-date-options-error" role="alert"><span>{t("salesReport.loadError")}</span><button type="button" onClick={retryDateOptions}>{t("salesReport.retry")}</button></div>}
    <ReportDateRangeFilter locale={locale} today={dateOptions?.currentDate ?? ""} earliestDate={dateOptions?.earliestDate ?? ""}
      value={{ from, to, label: "", preset: quickRange.from === from && quickRange.to === to ? quickRange.preset : undefined }}
      onChange={(range) => { setQuickRange(range); setFrom(range.from); setTo(range.to); }} />
  </section>;
}

import { sortTableRows, useTableSortPreference } from "../../../packages/app-common/src/components/tableSorting";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Eye, FilePdf, FileXls, Plus, Trash } from "@phosphor-icons/react";
import type { LocaleCode, TerminalContext, UserSession } from "../../../packages/app-common/src/types";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { ErpSelect } from "../../../packages/app-common/src/components/ErpSelect";
import { WarehouseSearchFrame } from "../../../packages/app-common/src/components/WarehouseSearchFrame";
import { ReportDateRangeFilter, type ReportDateRange } from "../../../packages/app-common/src/components/ReportDateRangeFilter";
import { useReportDateOptions } from "../../../packages/app-common/src/components/useReportDateOptions";
import { TableLayoutHeaderCell } from "../../../packages/app-common/src/components/TableLayoutHeaderCell";
import { visibleTableColumns, type TableColumnDefinition } from "../../../packages/app-common/src/components/tableLayoutPreferences";
import { useTableLayoutPreference } from "../../../packages/app-common/src/components/useTableLayoutPreference";
import { useWarehouseListShortcuts, WAREHOUSE_LIST_SHORTCUTS } from "../../../packages/app-common/src/components/useWarehouseListShortcuts";
import "../../../packages/app-common/src/components/ErpSearchField.css";
import { WarehouseDocumentDialog, buildWarehouseDocumentCommand,
  type WarehouseDocumentDraft, type WarehouseDocumentView } from "../../../packages/app-common/src/components/WarehouseDocumentDialog";
import type { WarehouseImportProduct } from "../../../packages/app-common/src/components/warehouseDocumentImport";
import "../../../packages/app-common/src/components/WarehouseClassicTables.css";
import {
  cancelTransferDocument, confirmTransferDocument, loadTransferDocument,
  exportTransferList,
  loadTransferDocuments, loadWarehouseOptions, loadWarehouseDocumentProducts, saveTransferDocument,
  type TransferDocument, type TransferListItem, type TransferDocumentFilters,
  type TransferDocumentView, type WarehouseOption
} from "./warehouseOperationsApi";

type Props = { session: UserSession; t: (key: string) => string; createOnMount?: boolean;
  locale?: LocaleCode; terminalContext?: TerminalContext };

type TransferColumn = "number" | "date" | "source" | "target" | "notes" | "status" | "lines" | "units";
const transferColumns: TableColumnDefinition<TransferColumn>[] = [
  { key: "number", defaultWidth: 190 }, { key: "date", defaultWidth: 175 },
  { key: "source", defaultWidth: 200 }, { key: "target", defaultWidth: 200 },
  { key: "notes", defaultWidth: 260 }, { key: "status", defaultWidth: 140 },
  { key: "lines", defaultWidth: 100 }, { key: "units", defaultWidth: 110 }
];

export function transferEditorDocument(view: TransferDocumentView): WarehouseDocumentView {
  const document = view.document;
  return {
    id: document.id, number: document.number, warehouseId: document.sourceWarehouseId,
    targetWarehouseId: document.targetWarehouseId, version: document.version,
    date: document.date ?? document.createdAt.slice(0, 10), externalNumber: document.externalNumber,
    concept: document.notes, priceSource: document.priceSource ?? "PURCHASE",
    globalDiscount: document.globalDiscount ?? 0, subtotal: document.subtotal, total: document.total,
    status: document.status === "DRAFT" ? "BORRADOR" : document.status === "CONFIRMED" ? "CONFIRMADA" : "ANULADA",
    lines: view.lines.map((line) => ({ productId: line.productId, productCode: line.code,
      productName: line.productName ?? line.name, quantity: line.quantity,
      purchaseUnitPrice: line.unitPrice ?? 0, discount: line.discount ?? 0, priceOverridden: line.priceOverridden }))
  };
}

export function WarehouseTransferScreen({ session, t, createOnMount = false, locale = "es", terminalContext }: Props) {
  const rootRef = useRef<HTMLElement>(null);
  const token = session.accessToken ?? "";
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [documents, setDocuments] = useState<TransferListItem[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [hasMore, setHasMore] = useState(false);
  const [statusFilter, setStatusFilter] = useState<TransferDocument["status"] | "">("");
  const [searchInput, setSearchInput] = useState("");
  const [searchFilter, setSearchFilter] = useState("");
  const [sourceFilter, setSourceFilter] = useState("");
  const [targetFilter, setTargetFilter] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [quickRange, setQuickRange] = useState<ReportDateRange>({ from: "", to: "", label: "" });
  const { dateOptions, dateOptionsFailed, retryDateOptions } = useReportDateOptions("warehouseTransfers", token);
  const [selectedDocumentId, setSelectedDocumentId] = useState("");
  const [editor, setEditor] = useState<WarehouseDocumentView | null>(null);
  const [editorOpen, setEditorOpen] = useState(createOnMount);
  useWarehouseListShortcuts(rootRef, Boolean(token), editorOpen);
  const [products, setProducts] = useState<WarehouseImportProduct[]>([]);
  const [resourcesReady, setResourcesReady] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [exporting, setExporting] = useState(false);
  const exportBusy = useRef(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const searchRef = useRef<HTMLInputElement>(null);
  const listRequestId = useRef(0);
  const listBusy = useRef(false);
  const nextPage = useRef(0);
  const moreAvailable = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  const tableLayout = useTableLayoutPreference({ app: "gestion", username: session.username,
    accessToken: token, tableKey: "warehouse.transfers.documents", definitions: transferColumns });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const columnLabels: Record<TransferColumn, string> = {
    number: t("warehouse.transfer.number"), date: t("warehouse.transfer.date"),
    source: t("warehouse.transfer.source"), target: t("warehouse.transfer.target"),
    notes: t("warehouse.count.notes"), status: t("warehouse.count.status"),
    lines: t("warehouse.transfer.lines"), units: t("warehouse.transfer.units")
  };

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      const value = searchInput.trim();
      if (value !== searchFilter) setSearchFilter(value);
    }, 250);
    return () => window.clearTimeout(timeout);
  }, [searchFilter, searchInput]);

  const loadDocuments = useCallback(async (append = false) => {
    if (append && (listBusy.current || !moreAvailable.current)) return;
    const requestId = ++listRequestId.current;
    const requestedPage = append ? nextPage.current : 0;
    listBusy.current = true;
    if (!append) {
      nextPage.current = 0;
      moreAvailable.current = false;
      setHasMore(false);
      setDocuments([]);
      if (scrollRef.current) scrollRef.current.scrollTop = 0;
    }
    setError("");
    setListLoading(true);
    const filters: TransferDocumentFilters = { status: statusFilter, search: searchFilter,
      sourceWarehouseId: sourceFilter, targetWarehouseId: targetFilter, dateFrom, dateTo };
    try {
      const result = await loadTransferDocuments(requestedPage, filters, token);
      if (requestId !== listRequestId.current) return;
      setDocuments((current) => append
        ? Array.from(new Map([...current, ...result.items].map((item) => [item.id, item])).values())
        : result.items);
      nextPage.current = requestedPage + 1;
      moreAvailable.current = result.hasMore && result.items.length > 0;
      setHasMore(moreAvailable.current);
      if (!append) setSelectedDocumentId((current) => result.items.some((item) => item.id === current) ? current : "");
    } catch (cause) {
      if (requestId === listRequestId.current) setError(cause instanceof Error ? cause.message : t("warehouse.transfer.loadError"));
    } finally {
      if (requestId === listRequestId.current) { listBusy.current = false; setListLoading(false); }
    }
  }, [dateFrom, dateTo, searchFilter, sourceFilter, statusFilter, t, targetFilter, token]);

  useEffect(() => { void loadDocuments(); return () => { listRequestId.current += 1; }; }, [loadDocuments]);
  useEffect(() => {
    const table = scrollRef.current;
    if (table && table.clientHeight > 0 && table.scrollHeight <= table.clientHeight
      && hasMore && !listLoading && !error && searchInput.trim() === searchFilter) {
      void loadDocuments(true);
    }
  }, [documents, hasMore, listLoading, error, searchInput, searchFilter, loadDocuments]);
  useEffect(() => {
    let cancelled = false;
    void Promise.all([loadWarehouseOptions(token), loadWarehouseDocumentProducts(token)]).then(([all, catalog]) => {
      if (cancelled) return;
      setWarehouses(all);
      setProducts(catalog.filter((product) => product.productType !== "SERVICE"));
      setResourcesReady(true);
    }).catch(() => { if (!cancelled) setError(t("warehouse.operations.loadError")); });
    return () => { cancelled = true; };
  }, [t, token]);

  function newDocument() {
    setEditor(null); setError(""); setEditorOpen(true);
  }

  async function openDocument(id: string) {
    setError("");
    try {
      setEditor(transferEditorDocument(await loadTransferDocument(id, token)));
      setEditorOpen(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("warehouse.transfer.loadError"));
    }
  }

  const persistence = useMemo(() => ({
    save: async (draft: WarehouseDocumentDraft, id?: string, version?: number) => {
      const saved = await saveTransferDocument({ ...buildWarehouseDocumentCommand("transfer", draft),
        sourceWarehouseId: draft.warehouseId, targetWarehouseId: draft.targetWarehouseId ?? "",
        notes: draft.concept, expectedVersion: version }, token, id);
      return transferEditorDocument(saved);
    },
    confirm: async (saved: WarehouseDocumentView) => transferEditorDocument(
      await confirmTransferDocument(saved.id, saved.version!, token))
  }), [token]);

  const tableSort = useTableSortPreference({ app: "gestion", username: session.username, tableKey: "warehouse.transfers.documents", columns: transferColumns.map(c => c.key), defaultSort: null });
  const sortedDocuments = sortTableRows(documents, tableSort.sort, (doc, key) => key === "date" ? doc.createdAt : key === "source" ? warehouseName(doc.sourceWarehouseId) : key === "target" ? warehouseName(doc.targetWarehouseId) : key === "lines" ? doc.lineCount : key === "units" ? doc.totalUnits : key === "status" ? t(`warehouse.count.status.${doc.status}`) : key === "notes" ? doc.notes : doc.number, locale);

  async function cancelSelectedDocument() {
    if (!selectedDocumentId || cancelling) return;
    setCancelling(true); setError("");
    try {
      await cancelTransferDocument(selectedDocumentId, token);
      await loadDocuments();
    } catch (cause) { setError(cause instanceof Error ? cause.message : t("warehouse.transfer.error")); }
    finally { setCancelling(false); }
  }

  const selectedDocument = documents.find((document) => document.id === selectedDocumentId);
  const warehouseName = (id: string) => warehouses.find((item) => item.id === id)?.name ?? id;
  const formatQuantity = (value: number) => new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(value);
  function moveRowFocus(event: React.KeyboardEvent<HTMLTableRowElement>, id: string) {
    if (event.key === "Enter") { event.preventDefault(); void openDocument(id); return; }
    const index = sortedDocuments.findIndex((item) => item.id === id);
    const next = event.key === "ArrowDown" ? Math.min(documents.length - 1, index + 1)
      : event.key === "ArrowUp" ? Math.max(0, index - 1)
        : event.key === "Home" ? 0 : event.key === "End" ? documents.length - 1 : -1;
    if (next < 0) return;
    event.preventDefault();
    const nextId = sortedDocuments[next]?.id;
    if (nextId) { setSelectedDocumentId(nextId); rowRefs.current.get(nextId)?.focus(); }
  }

  async function exportList(format: "pdf" | "xlsx") {
    if (!token || exportBusy.current) return;
    exportBusy.current = true; setExporting(true); setError("");
    try {
      const file = await exportTransferList(format, { status: statusFilter, search: searchInput.trim(),
        sourceWarehouseId: sourceFilter, targetWarehouseId: targetFilter, dateFrom, dateTo }, locale, token);
      const url = URL.createObjectURL(file);
      const link = document.createElement("a");
      link.href = url; link.download = `traspasos-almacen.${format}`; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch { setError(t("warehouse.report.exportError")); }
    finally { exportBusy.current = false; setExporting(false); }
  }
  return <section ref={rootRef} data-warehouse-heading="unified" className="stock-sales-history-panel warehouse-operations-panel--classic gestion-warehouse-transfer-screen gestion-warehouse-screen" aria-labelledby="warehouse-transfer-title">
    <div className="gestion-warehouse-transfer-module-title"><h1>{t("home.warehouse")}</h1></div>
    <header className="gestion-warehouse-operations-header gestion-warehouse-titlebar"><div>
      <h2 id="warehouse-transfer-title">{t("warehouse.transfer.navigation")}</h2>
      <p>{t("warehouse.transfer.title.subtitle")}</p></div>
      <div className="gestion-warehouse-title-actions gestion-warehouse-actions"><button type="button" className="primary gestion-warehouse-command" aria-keyshortcuts={WAREHOUSE_LIST_SHORTCUTS.create} disabled={!resourcesReady} onClick={newDocument}><span><Plus size={16} aria-hidden="true" />{t("warehouseDocument.create")}</span><kbd aria-hidden="true">{WAREHOUSE_LIST_SHORTCUTS.create}</kbd></button></div>
    </header>
    {error && <p role="alert" className="gestion-warehouse-operations-message error">{error}</p>}
    {notice && <p role="status" className="gestion-warehouse-operations-message success">{notice}</p>}
    <div className="stock-history-toolbar gestion-warehouse-transfer-toolbar gestion-warehouse-toolbar">
      <label><span>{t("warehouse.transfer.search")}</span>
        <WarehouseSearchFrame><input ref={searchRef} className="erp-search-input" type="search" maxLength={120} value={searchInput}
          placeholder={t("warehouse.transfer.searchPlaceholder")}
          onChange={(event) => setSearchInput(event.target.value)} /></WarehouseSearchFrame></label>
      <div className="stock-history-filter-field gestion-warehouse-status-filter" data-filter-active={Boolean(statusFilter)}><span>{t("warehouse.count.status")}</span>
        <ErpSelect aria-label={t("warehouse.count.status")} value={statusFilter}
          onChange={(value) => { setStatusFilter(value as typeof statusFilter); }}
          options={[{ value: "", label: t("warehouse.count.all") },
            ...(["DRAFT", "CONFIRMED", "CANCELLED"] as const).map((value) =>
              ({ value, label: t(`warehouse.count.status.${value}`) }))]} /></div>
      <div className="stock-history-filter-field"><span>{t("warehouse.transfer.source")}</span>
        <ErpSelect aria-label={t("warehouse.transfer.source")} value={sourceFilter}
          onChange={(value) => { setSourceFilter(value); }}
          options={[{ value: "", label: t("warehouse.count.all") },
            ...warehouses.map((item) => ({ value: item.id, label: item.name }))]} /></div>
      <div className="stock-history-filter-field"><span>{t("warehouse.transfer.target")}</span>
        <ErpSelect aria-label={t("warehouse.transfer.target")} value={targetFilter}
          onChange={(value) => { setTargetFilter(value); }}
          options={[{ value: "", label: t("warehouse.count.all") },
            ...warehouses.map((item) => ({ value: item.id, label: item.name }))]} /></div>
      <div className="warehouse-document-actions gestion-warehouse-transfer-actions gestion-warehouse-actions gestion-warehouse-list-actions">
        <button type="button" className="primary gestion-warehouse-command" aria-keyshortcuts={WAREHOUSE_LIST_SHORTCUTS.excel} disabled={!token || exporting} onClick={() => void exportList("xlsx")}><span><FileXls size={16} aria-hidden="true" />{t("warehouse.count.exportExcel")}</span><kbd aria-hidden="true">{WAREHOUSE_LIST_SHORTCUTS.excel}</kbd></button>
        <button type="button" className="primary gestion-warehouse-command" aria-keyshortcuts={WAREHOUSE_LIST_SHORTCUTS.pdf} disabled={!token || exporting} onClick={() => void exportList("pdf")}><span><FilePdf size={16} aria-hidden="true" />{t("warehouse.count.exportPdf")}</span><kbd aria-hidden="true">{WAREHOUSE_LIST_SHORTCUTS.pdf}</kbd></button>
        <button type="button" disabled={!resourcesReady || !selectedDocument || listLoading || searchInput.trim() !== searchFilter}
          onClick={() => selectedDocument && void openDocument(selectedDocument.id)}>
          <span><Eye size={16} aria-hidden="true" />{t("warehouseDocument.open")}</span></button>
        <button type="button" disabled={selectedDocument?.status !== "DRAFT" || cancelling || listLoading}
          onClick={() => void cancelSelectedDocument()}><span><Trash size={16} aria-hidden="true" />{t("warehouse.transfer.cancelDocument")}</span></button>
      </div>
    </div>
    <ErpFilterChips translate={t} focusRef={searchRef} className="gestion-warehouse-transfer-chips"
      chips={[
        { key: "search", label: t("warehouse.transfer.search"), value: searchFilter,
          onRemove: () => { setSearchInput(""); setSearchFilter(""); } },
        { key: "status", label: t("warehouse.count.status"), value: statusFilter ? t(`warehouse.count.status.${statusFilter}`) : "",
          onRemove: () => { setStatusFilter(""); } },
        { key: "source", label: t("warehouse.transfer.source"), value: sourceFilter ? warehouseName(sourceFilter) : "",
          onRemove: () => { setSourceFilter(""); } },
        { key: "target", label: t("warehouse.transfer.target"), value: targetFilter ? warehouseName(targetFilter) : "",
          onRemove: () => { setTargetFilter(""); } },
        { key: "from", label: t("warehouse.report.from"), value: dateFrom,
          onRemove: () => { setDateFrom(""); } },
        { key: "to", label: t("warehouse.report.to"), value: dateTo,
          onRemove: () => { setDateTo(""); } }
      ]} onClear={() => { setSearchInput(""); setSearchFilter(""); setStatusFilter("");
        setSourceFilter(""); setTargetFilter(""); setDateFrom(""); setDateTo(""); }} />
    <div className="stock-history-context gestion-warehouse-transfer-context"><strong>{t("warehouse.transfer.navigation")}</strong>
      <span>{t("warehouse.transfer.results").replace("{count}", String(documents.length))}</span></div>
    <div className="stock-history-table-scroll gestion-warehouse-transfer-table erp-classic-tables warehouse-classic-table"
      ref={scrollRef} onScroll={(event) => {
        const table = event.currentTarget;
        if (searchInput.trim() === searchFilter && table.scrollHeight - table.scrollTop - table.clientHeight <= 240) {
          void loadDocuments(true);
        }
      }}
      aria-busy={listLoading || searchInput.trim() !== searchFilter}>
      <table className="report-table warehouse-document-table" style={{ minWidth: visibleColumns.reduce((total, column) => total + column.width, 0) }}>
        <colgroup>{visibleColumns.map((column) => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
        <thead><tr>{visibleColumns.map((column) => <TableLayoutHeaderCell key={column.key} column={column}
          sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null} onSort={tableSort.toggleSort}
          sortLabel={`${t("party.sortBy")} ${columnLabels[column.key]}`}
          resizeLabel={`${t("stock.columns.resize")} ${columnLabels[column.key]}`}
          onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}
          onToggleVisibility={tableLayout.toggleColumnVisibility}
          columnVisibilityOptions={tableLayout.layout.map((item) => ({ key: item.key,
            label: columnLabels[item.key], visible: item.visible }))}>
          {columnLabels[column.key]}</TableLayoutHeaderCell>)}</tr></thead>
        <tbody>{searchInput.trim() === searchFilter && sortedDocuments.map((document) => <tr key={document.id} tabIndex={0}
          ref={(element) => { if (element) rowRefs.current.set(document.id, element); else rowRefs.current.delete(document.id); }}
          className={selectedDocumentId === document.id ? "selected" : ""}
          aria-selected={selectedDocumentId === document.id}
          onClick={() => setSelectedDocumentId(document.id)}
          onFocus={() => setSelectedDocumentId(document.id)}
          onDoubleClick={() => void openDocument(document.id)}
          onKeyDown={(event) => moveRowFocus(event, document.id)}>
          {visibleColumns.map((column) => <td key={column.key} className={column.key === "lines" || column.key === "units" ? "is-numeric" : undefined}>
            {column.key === "number" && (document.number || t("warehouse.count.status.DRAFT"))}
            {column.key === "date" && new Date(document.createdAt).toLocaleString(locale)}
            {column.key === "source" && warehouseName(document.sourceWarehouseId)}
            {column.key === "target" && warehouseName(document.targetWarehouseId)}
            {column.key === "notes" && (document.notes || "—")}
            {column.key === "status" && t(`warehouse.count.status.${document.status}`)}
            {column.key === "lines" && formatQuantity(document.lineCount)}
            {column.key === "units" && formatQuantity(document.totalUnits)}
          </td>)}</tr>)}
          {(listLoading || searchInput.trim() !== searchFilter) &&
            <tr><td colSpan={visibleColumns.length} role="status">{t("common.loading")}</td></tr>}
          {!listLoading && searchInput.trim() === searchFilter && documents.length === 0 &&
            <tr><td colSpan={visibleColumns.length}>{t("warehouse.adjustment.empty")}</td></tr>}
        </tbody></table></div>

    {editorOpen && resourcesReady && createPortal(<WarehouseDocumentDialog mode="transfer" open app="gestion"
      username={session.username} accessToken={token} token={token} session={session} locale={locale}
      title={t("warehouse.transfer.create")} terminalContext={terminalContext} canConfirm
      products={products} warehouses={warehouses} suppliers={[]} customers={[]} document={editor}
      persistence={persistence} onClose={() => setEditorOpen(false)}
      onSaved={(saved) => { setEditor(saved); setNotice(t("warehouse.transfer.saved")); void loadDocuments(); }}
      onConfirmed={() => { setEditorOpen(false); setNotice(t("warehouse.transfer.completed"));
        void loadDocuments(); }} />, document.body)}

    {dateOptionsFailed && <div className="report-date-options-error" role="alert"><span>{t("salesReport.loadError")}</span><button type="button" onClick={retryDateOptions}>{t("salesReport.retry")}</button></div>}
    <ReportDateRangeFilter locale={locale} today={dateOptions?.currentDate ?? ""} earliestDate={dateOptions?.earliestDate ?? ""}
      value={{ from: dateFrom, to: dateTo, label: "", preset: quickRange.from === dateFrom && quickRange.to === dateTo ? quickRange.preset : undefined }}
      onChange={(range) => { setQuickRange(range); setDateFrom(range.from); setDateTo(range.to); }} />

  </section>;
}

export default WarehouseTransferScreen;

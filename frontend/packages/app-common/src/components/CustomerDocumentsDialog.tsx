import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { apiProblemCode, apiRequest, classifyApiFailure } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, Permission, UserSession } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { nextTableSort, type TableSort } from "./tableSorting";
import { ErpSelect } from "./ErpSelect";
import { CustomerModel347Dialog } from "./CustomerModel347Dialog";
import { customerDocumentAmount } from "./customerDocumentAmount";
import "./CustomerDocumentsDialog.css";

const tabs = [
  { key: "tickets", shortcut: "F1", permission: "TICKETS_READ" },
  { key: "invoices", shortcut: "F2", permission: "INVOICES_READ" },
  { key: "delivery-notes", shortcut: "F3", permission: "DELIVERY_NOTES_READ" },
] as const;
type Tab = typeof tabs[number]["key"];
const columns = [
  { key: "store", defaultWidth: 90 },
  { key: "number", defaultWidth: 210 }, { key: "date", defaultWidth: 115 },
  { key: "type", defaultWidth: 165 }, { key: "status", defaultWidth: 145 },
  { key: "base", defaultWidth: 115 }, { key: "tax", defaultWidth: 100 },
  { key: "total", defaultWidth: 115 }, { key: "terminal", defaultWidth: 150 },
  { key: "user", defaultWidth: 160 },
  { key: "currency", defaultWidth: 90 },
] as const;
type Column = typeof columns[number]["key"];
type DocumentRow = {
  id: string; storeId: string; storeCode: string; documentId: string;
  customerId: string; number: string; date: string; type: string; status: string;
  subtotal: string; taxTotal: string; total: string; currency: string;
  terminalName?: string | null; userName?: string | null;
};
type DocumentPage = { localCustomerId: string; customer: { id: string }; coverage: string;
  items: DocumentRow[]; nextCursor?: string | null; hasMore: boolean };
const statuses = ["CONFIRMADO", "PENDIENTE", "PARCIAL", "PAGADO", "ANULADO"] as const;
const emptyFilters = { search: "", status: "", dateFrom: "", dateTo: "" };
type Props = {
  customer: { id: string; clientId: string; fiscalName: string };
  session: UserSession; locale: LocaleCode; app?: AppKind; canEdit: boolean; active?: boolean;
  onEdit: () => void; onClose: () => void;
};

export function canReadCustomerDocuments(permissions: Permission[], tab: Tab): boolean {
  return permissions.some((permission) => permission === "ADMIN" || permission === "GESTION_VENTAS"
    || permission === "VENTA" || permission === tabs.find((item) => item.key === tab)?.permission);
}

export function CustomerDocumentsDialog({ customer, session, locale, app = "venta", canEdit, active = true, onEdit, onClose }: Props) {
  const t = createTranslator(locale);
  const [tab, setTab] = useState<Tab>(() => tabs.find((item) => canReadCustomerDocuments(session.permissions, item.key))?.key ?? "tickets");
  const [cursor, setCursor] = useState<string | null>(null);
  const [filters, setFilters] = useState(emptyFilters);
  const [draftFilters, setDraftFilters] = useState(emptyFilters);
  const [filterError, setFilterError] = useState(false);
  const [sort, setSort] = useState<TableSort<Column>>({ column: "date", direction: "desc" });
  const [page, setPage] = useState<DocumentPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorKey, setErrorKey] = useState("");
  const [retry, setRetry] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const tableLayout = useTableLayoutPreference({
    app, username: session.username, accessToken: session.accessToken,
    tableKey: "customers.documents", definitions: columns,
  });
  const { layout } = tableLayout;
  const [exportBusy, setExportBusy] = useState(false);
  const [exportErrorKey, setExportErrorKey] = useState("");
  const exportController = useRef<AbortController | null>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(600);
  const dialogRef = useRef<HTMLElement>(null);
  const movedHeaderRef = useRef<HTMLElement | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const selectedRowRef = useRef<HTMLDivElement>(null);
  const allowed = canReadCustomerDocuments(session.permissions, tab);
  const readContextRef = useRef<{ key: string; active: boolean } | null>(null);
  const tableStateRef = useRef({ selectedId, page });
  tableStateRef.current = { selectedId, page };
  const [model347CustomerId, setModel347CustomerId] = useState<string | null>(null);
  const model347Open = active && allowed && tab === "invoices" && model347CustomerId === customer.id;
  // Let the last real column fill spare width instead of drawing an empty tail.
  const gridStyle = { gridTemplateColumns: layout.map((column, index) => index === layout.length - 1
    ? `minmax(${column.width}px, 1fr)` : `${column.width}px`).join(" ") };
  const hasFilters = Object.values(filters).some(Boolean);
  const filtersDirty = Object.keys(filters).some((key) => filters[key as keyof typeof filters] !== draftFilters[key as keyof typeof filters]);
  const rows = allowed ? page?.items ?? [] : [];
  const rowHeight = 44;
  const start = Math.max(0, Math.floor(scrollTop / rowHeight) - 5);
  const end = Math.min(rows.length, start + Math.ceil(viewportHeight / rowHeight) + 12);

  useLayoutEffect(() => {
    // Moving a DOM node can clear browser focus even though React keeps its key.
    movedHeaderRef.current?.focus({ preventScroll: true });
    movedHeaderRef.current = null;
  }, [layout]);

  function resetRows() {
    setCursor(null); setPage(null); setSelectedId(null); setErrorKey("");
    setScrollTop(0); setExportErrorKey("");
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }

  function applyFilters(clear = false) {
    const next = clear ? emptyFilters : { ...draftFilters, search: draftFilters.search.trim() };
    if (next.dateFrom && next.dateTo && next.dateFrom > next.dateTo) { setFilterError(true); return; }
    setFilterError(false); setDraftFilters(next); setFilters(next); resetRows(); setRetry((value) => value + 1);
  }

  function loadMore() {
    if (loading || errorKey || !page?.hasMore || !page.nextCursor || cursor === page.nextCursor) return;
    setCursor(page.nextCursor);
  }

  function selectTab(next: Tab, focus = true) {
    if (!canReadCustomerDocuments(session.permissions, next)) return;
    if (next !== tab) { setTab(next); resetRows(); }
    if (focus) dialogRef.current?.querySelector<HTMLButtonElement>(`#customer-documents-tab-${next}`)?.focus();
  }

  useEffect(() => {
    const key = JSON.stringify([customer.id, session.accessToken, tab, allowed, filters, sort]);
    const previous = readContextRef.current;
    const contextChanged = previous?.key !== key;
    const resumed = !contextChanged && previous?.active === false;
    readContextRef.current = { key, active };
    if (contextChanged) resetRows();
    if (!active) return;
    // A cursor belongs to its customer/filter/access context, never to the next dataset.
    if (contextChanged && cursor) return;
    if (resumed && cursor && !tableStateRef.current.page) { setCursor(null); return; }
    const controller = new AbortController();
    if ((!cursor && !resumed) || !allowed) { setPage(null); setSelectedId(null); }
    setErrorKey(""); setLoading(allowed);
    if (!allowed) return;
    const query = new URLSearchParams({ size: "50", sortBy: sort.column, sortDirection: sort.direction });
    for (const [key, value] of Object.entries(filters)) if (value) query.set(key, value);
    if (cursor) query.set("cursor", cursor);
    void apiRequest<DocumentPage>(`/customer-document-reports/saas/${encodeURIComponent(customer.id)}/${tab}?${query}`, { token: session.accessToken, signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        // Keep remote identities separate from the local customer and require the expected dataset.
        if (result.localCustomerId !== customer.id || result.coverage !== "RECEIVED_V2_ONLY"
          || !result.customer?.id || result.items.some((row) => row.customerId !== result.customer.id
            || row.id !== `${row.storeId}/${row.documentId}`)) {
          setPage(null); setSelectedId(null); setScrollTop(0);
          setErrorKey("customerDocuments.filterUnavailable"); return;
        }
        setPage((current) => {
          if (!cursor || !current) return result;
          const items = new Map(current.items.map((row) => [row.id, row]));
          for (const row of result.items) items.set(row.id, row);
          return { ...result, items: [...items.values()] };
        });
        if (!cursor) {
          // F7 still refreshes the page, but an unchanged selected row keeps its viewport.
          if (!resumed || !result.items.some((row) => row.id === tableStateRef.current.selectedId)) {
            setSelectedId(result.items[0]?.id ?? null);
            setScrollTop(0);
            if (bodyRef.current) bodyRef.current.scrollTop = 0;
          }
        }
      }).catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        const bindingRequired = apiProblemCode(failure) === "SAAS_CUSTOMER_BINDING_REQUIRED";
        const forbidden = classifyApiFailure(failure) === "forbidden";
        if (bindingRequired || forbidden) { setPage(null); setSelectedId(null); setScrollTop(0); }
        setErrorKey(bindingRequired ? "customerDocuments.bindingRequired"
          : forbidden ? "customerDocuments.noAccess" : "customerDocuments.loadError");
      }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [active, customer.id, session.accessToken, tab, cursor, allowed, retry, filters, sort]);

  useLayoutEffect(() => {
    const body = bodyRef.current;
    if (!active || !body) return;
    // F7 temporarily unmounts this viewport: restore its scroll offset on return.
    body.scrollTop = scrollTop;
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => setViewportHeight(body.clientHeight));
    observer.observe(body);
    return () => observer.disconnect();
  }, [active]);

  useEffect(() => () => { exportController.current?.abort(); }, [active, customer.id, session.accessToken, tab, filters, sort]);

  useEffect(() => { setModel347CustomerId(null); }, [active, customer.id, session.accessToken, locale, tab, allowed]);

  useLayoutEffect(() => {
    const opener = document.activeElement;
    return () => { if (opener instanceof HTMLElement && opener.isConnected) opener.focus({ preventScroll: true }); };
  }, []);
  // Suspending for F7 must not steal focus from the existing edit form.
  useLayoutEffect(() => active && !model347Open && dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document, { restoreFocus: false }) : undefined, [active, model347Open]);

  useEffect(() => {
    if (!active || model347Open) return;
    function handleKey(event: KeyboardEvent) {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      const targetTab = tabs.find((item) => item.shortcut === event.key);
      if (!targetTab && event.key !== "F7" && event.key !== "Escape") return;
      // Close an open filter/header menu before closing this window.
      if (event.key === "Escape" && dialogRef.current?.querySelector('[aria-haspopup][aria-expanded="true"]')) return;
      event.preventDefault(); event.stopImmediatePropagation();
      if (event.repeat) return;
      if (targetTab) selectTab(targetTab.key);
      else if (event.key === "F7") { if (canEdit) onEdit(); }
      else onClose();
    }
    window.addEventListener("keydown", handleKey, true);
    return () => window.removeEventListener("keydown", handleKey, true);
  }, [active, model347Open, canEdit, onEdit, onClose, tab, session.permissions]);

  useLayoutEffect(() => {
    const body = bodyRef.current;
    const index = rows.findIndex((row) => row.id === selectedId);
    if (!body || index < 0 || !body.clientHeight) return;
    const top = index * rowHeight;
    if (top < body.scrollTop) body.scrollTop = top;
    else if (top + rowHeight > body.scrollTop + body.clientHeight) body.scrollTop = top + rowHeight - body.clientHeight;
    setScrollTop(body.scrollTop);
  }, [selectedId]);

  function label(column: Column) {
    if (column === "number") return t("receivables.column.document");
    if (column === "type") return t("customerDocuments.type");
    if (column === "store" || column === "currency") return t(`customerDocuments.${column}`);
    return t(`salesReport.column.${column}`);
  }
  function cell(row: DocumentRow, column: Column): string {
    if (column === "store") return row.storeCode;
    if (column === "currency") return row.currency;
    if (column === "number") return row.number;
    if (column === "date") return new Date(`${row.date}T00:00:00`).toLocaleDateString(locale === "zh" ? "zh-CN" : locale);
    if (column === "type") return t(`customerDocuments.type.${row.type}`);
    if (column === "status") return t(`salesReport.activity.documentStatus.${row.status}`);
    if (column === "terminal") return row.terminalName || "—";
    if (column === "user") return row.userName || "—";
    return customerDocumentAmount(column === "tax" ? row.taxTotal : column === "base" ? row.subtotal : row.total, row.currency, locale);
  }

  async function exportExcel() {
    if (exportController.current || loading || filtersDirty || !allowed || !rows.length || errorKey) return;
    const controller = new AbortController();
    exportController.current = controller; setExportBusy(true); setExportErrorKey("");
    try {
      const blob = await apiRequest<Blob>("/customer-document-reports/saas/export.xlsx", {
        token: session.accessToken, responseType: "blob", signal: controller.signal,
        body: {
          customerId: customer.id, reportKey: tab,
          filters: Object.fromEntries(Object.entries(filters).filter(([, value]) => value)),
          sortBy: sort.column, sortDirection: sort.direction,
          ...(hasFilters ? {} : { documentKeys: rows.map((row) => ({ storeId: row.storeId, documentId: row.documentId })) }),
          columns: layout.map((column) => ({ key: column.key, label: label(column.key) })),
          labels: {
            sheetName: t(`customerDocuments.${tab}`),
            customerCode: t("customerDocuments.exportCustomerCode"),
            customerTaxId: t("customerDocuments.exportCustomerTaxId"),
            customerName: t("customerDocuments.exportCustomerName"),
            grandTotal: t("customerDocuments.exportGrandTotal"),
            filters: {
              title: t("customerDocuments.exportFilters"), none: t("customerDocuments.exportNoFilters"),
              search: t("party.searchLabel"), status: t("salesReport.filter.status"),
              dateFrom: t("salesReport.filter.dateFrom"), dateTo: t("salesReport.filter.dateTo"),
            },
            types: Object.fromEntries(["TICKET", "FACTURA_VENTA", "RECTIFICATIVA_VENTA", "ALBARAN_VENTA"].map((type) => [type, t(`customerDocuments.type.${type}`)])),
            statuses: Object.fromEntries(statuses.map((status) => [status, t(`salesReport.activity.documentStatus.${status}`)])),
          },
        },
      });
      if (controller.signal.aborted) return;
      const fileName = [customer.clientId, customer.fiscalName, t(`customerDocuments.${tab}`)]
        .map((part) => part.trim().replace(/[<>:"/\\|?*\u0000-\u001f]+/g, "-")
          .replace(/\s+/g, " ").slice(0, 80).replace(/[. ]+$/, ""))
        .join("-") + ".xlsx";
      if (window.tpvDesktop?.reports) {
        const bytes = new Uint8Array(await blob.arrayBuffer());
        if (controller.signal.aborted) return;
        const result = await window.tpvDesktop.reports.saveFile({ defaultFileName: fileName, filters: [{ name: "Excel", extensions: ["xlsx"] }], bytes });
        if (!result.ok) throw new Error("export_save_failed");
      } else {
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a"); link.href = url; link.download = fileName; link.click();
        URL.revokeObjectURL(url);
      }
    } catch (failure: unknown) {
      if (!controller.signal.aborted) setExportErrorKey(apiProblemCode(failure) === "customer_documents_export_limit_exceeded"
        ? "customerDocuments.exportLimit" : classifyApiFailure(failure) === "forbidden"
          ? "customerDocuments.noAccess" : "customerDocuments.exportError");
    } finally { exportController.current = null; setExportBusy(false); }
  }

  if (!active) return null;
  return <><div className="filter-overlay customer-documents-overlay" role="dialog" aria-modal="true" aria-labelledby="customer-documents-title"
    aria-hidden={model347Open || undefined}>
    <section className="filter-dialog customer-documents-dialog" ref={dialogRef} inert={model347Open || undefined}>
      <header className="filter-header">
        <div><h2 id="customer-documents-title">{t("customerDocuments.title")}</h2><span>{customer.clientId} · {customer.fiscalName}</span></div>
        <button type="button" onClick={onClose}>{t("common.close")}</button>
      </header>
      <div className="customer-documents-navigation">
        <div role="tablist" aria-label={t("customerDocuments.title")} onKeyDown={(event) => {
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
          event.preventDefault(); event.stopPropagation();
          const available = tabs.filter((item) => canReadCustomerDocuments(session.permissions, item.key));
          if (!available.length) return;
          const index = available.findIndex((item) => item.key === tab);
          selectTab(available[event.key === "Home" ? 0 : event.key === "End" ? available.length - 1
            : (index + (event.key === "ArrowRight" ? 1 : -1) + available.length) % available.length].key);
        }}>
          {tabs.map((item) => <button key={item.key} id={`customer-documents-tab-${item.key}`} type="button" role="tab"
            aria-selected={tab === item.key} aria-controls="customer-documents-panel" tabIndex={tab === item.key ? 0 : -1}
            disabled={!canReadCustomerDocuments(session.permissions, item.key)}
            title={!canReadCustomerDocuments(session.permissions, item.key) ? t("customerDocuments.noAccess") : undefined}
            onClick={() => selectTab(item.key)}>{t(`customerDocuments.${item.key}`)} <kbd>{item.shortcut}</kbd></button>)}
        </div>
        <button type="button" disabled={!canEdit} onClick={onEdit}>{t("customerDocuments.edit")}</button>
      </div>
      <form className="customer-documents-filters" onSubmit={(event) => { event.preventDefault(); applyFilters(); }}>
        <label className="customer-documents-search"><span>{t("party.searchLabel")}</span>
          <input type="search" maxLength={120} placeholder={t("customerDocuments.search")}
            value={draftFilters.search} onChange={(event) => setDraftFilters((current) => ({ ...current, search: event.target.value }))} />
        </label>
        <label><span>{t("salesReport.filter.status")}</span>
          <ErpSelect className="erp-select--compact" aria-label={t("salesReport.filter.status")} value={draftFilters.status}
            options={[{ value: "", label: t("salesReport.filter.all") }, ...statuses.map((value) => ({ value, label: t(`salesReport.activity.documentStatus.${value}`) }))]}
            onChange={(status) => setDraftFilters((current) => ({ ...current, status }))} />
        </label>
        <label><span>{t("salesReport.filter.dateFrom")}</span><input type="date" value={draftFilters.dateFrom}
          onChange={(event) => setDraftFilters((current) => ({ ...current, dateFrom: event.target.value }))} /></label>
        <label><span>{t("salesReport.filter.dateTo")}</span><input type="date" value={draftFilters.dateTo}
          onChange={(event) => setDraftFilters((current) => ({ ...current, dateTo: event.target.value }))} /></label>
        <button type="submit" disabled={!allowed}>{t("salesReport.filter.apply")}</button>
        <button type="button" onClick={() => applyFilters(true)}>{t("party.filter.clear")}</button>
      </form>
      {filterError && <p className="customer-documents-notice" role="alert">{t("customerDocuments.invalidDates")}</p>}
      {filtersDirty && <p className="customer-documents-notice" role="status">{t("customerDocuments.applyPending")}</p>}
      {exportErrorKey && <p className="customer-documents-notice" role="alert">{t(exportErrorKey)}</p>}
      {errorKey && <div className="customer-documents-notice" role="alert"><span>{t(errorKey)}</span> <button type="button" onClick={() => {
        // A discarded dataset cannot be resumed from its former tail cursor.
        if (!page) resetRows();
        setRetry((value) => value + 1);
      }}>{t("party.retry")}</button></div>}
      <p className="customer-documents-scope">{t("customerDocuments.scope")}</p>
      <div id="customer-documents-panel" className="customer-documents-panel" role="tabpanel" aria-labelledby={`customer-documents-tab-${tab}`} aria-busy={loading}>
        <div className="customer-documents-table-scroll">
          <div role="table" className="customer-documents-table" aria-rowcount={page?.hasMore ? -1 : rows.length + 1} aria-label={t(`customerDocuments.${tab}`)} style={{ minWidth: layout.reduce((width, column) => width + column.width, 20) }}>
            <div role="row" className="customer-documents-row customer-documents-table-header" style={gridStyle}>
              {layout.map((column) => <TableLayoutHeaderCell key={`${tab}-${column.key}`} as="span" column={column} showColumnMenu
                sortDirection={sort.column === column.key ? sort.direction : null} sortLabel={`${t("party.sortBy")} ${label(column.key)}`}
                onSort={(key) => { setSort((current) => nextTableSort(current, key)); resetRows(); }}
                onMove={(key, direction) => {
                  movedHeaderRef.current = dialogRef.current?.querySelector<HTMLElement>(`[data-column-key="${key}"]`) ?? null;
                  tableLayout.moveColumn(key, direction);
                }} onReorder={tableLayout.reorderColumns} resizeLabel={`${t("stock.columns.resize")} ${label(column.key)}`}
                onResize={tableLayout.resizeColumn}>
                {label(column.key)}
              </TableLayoutHeaderCell>)}
            </div>
            <div role="rowgroup" className="customer-documents-table-body" ref={bodyRef} tabIndex={0} aria-label={t("customerDocuments.navigate")}
              onScroll={(event) => { const body = event.currentTarget; setScrollTop(body.scrollTop); if (body.scrollHeight - body.scrollTop - body.clientHeight < 160) loadMore(); }}
              onKeyDown={(event) => {
                if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key) || event.ctrlKey || event.altKey || event.metaKey) return;
                event.preventDefault(); event.stopPropagation();
                const rows = page?.items ?? [];
                const index = rows.findIndex((row) => row.id === selectedId);
                const next = event.key === "Home" ? 0 : event.key === "End" ? rows.length - 1
                  : Math.max(0, Math.min(rows.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)));
                setSelectedId(rows[next]?.id ?? null);
                if (next >= rows.length - 1) loadMore();
              }}>
              {!allowed && <p className="customer-documents-state" role="status">{t("customerDocuments.noAccess")}</p>}
              {!loading && !errorKey && allowed && page?.items.length === 0 && <p className="customer-documents-state">{t(hasFilters ? "customerDocuments.noMatches" : "customerDocuments.empty")}</p>}
              {start > 0 && <div aria-hidden="true" style={{ height: start * rowHeight }} />}
              {rows.slice(start, end).map((row, index) => <div key={row.id} role="row" aria-rowindex={start + index + 2} className={`customer-documents-row${(start + index) % 2 ? " even" : ""}${selectedId === row.id ? " selected" : ""}`}
                ref={selectedId === row.id ? selectedRowRef : undefined} style={gridStyle}
                onClick={() => { setSelectedId(row.id); bodyRef.current?.focus({ preventScroll: true }); }}>
                {layout.map((column) => <span key={column.key} role="cell" className={["base", "tax", "total"].includes(column.key) ? "money" : undefined} title={cell(row, column.key)}>{cell(row, column.key)}</span>)}
              </div>)}
              {end < rows.length && <div aria-hidden="true" style={{ height: (rows.length - end) * rowHeight }} />}
              {loading && <p className="customer-documents-state" role="status">{t("common.loading")}</p>}
            </div>
          </div>
        </div>
      </div>
      <footer className="customer-documents-footer">
        <span role="status">{t("customerDocuments.loaded").replace("{count}", String(page?.items.length ?? 0))}
          {page?.hasMore ? ` · ${t("customerDocuments.scrollMore")}` : ""}</span>
        <div>
          {tab === "invoices" && <button type="button" disabled={!allowed}
            onClick={() => setModel347CustomerId(customer.id)}>{t("customerModel347.button")}</button>}
          <button type="button" disabled={exportBusy || loading || filtersDirty || !allowed || !rows.length || Boolean(errorKey)}
            title={t(hasFilters ? "customerDocuments.exportFiltered" : "customerDocuments.exportLoaded")}
            onClick={() => void exportExcel()}>{t(exportBusy ? "stock.history.exporting" : "stock.history.exportExcel")}</button>
          <button type="button" onClick={onClose}>{t("customerDocuments.close")}</button>
        </div>
      </footer>
    </section>
  </div>
    {model347Open && <CustomerModel347Dialog customer={customer} session={session} locale={locale}
      onClose={() => setModel347CustomerId(null)} />}
  </>;
}

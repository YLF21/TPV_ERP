import { WindowCloseButton } from "../../../packages/app-common/src/components/WindowCloseButton";
import "../../../packages/app-common/src/components/SalesReportClassicTables.css";
import { ArrowClockwise } from "@phosphor-icons/react";
import {
  ErpSelect,
  TableLayoutHeaderCell,
  outputIssuedVoucher,
  tableLayoutGridTemplate,
  useTableLayoutPreference,
  visibleTableColumns,
  type LocaleCode,
  type TableColumnDefinition,
  type TerminalContext,
  type UserSession
} from "@tpverp/app-common";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type FormEvent } from "react";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { GestionSearchFrame } from "../../../packages/app-common/src/components/GestionSearchFrame";
import { ReportDateRangeFilter, type ReportDateRange } from "../../../packages/app-common/src/components/ReportDateRangeFilter";
import { useReportDateOptions } from "../../../packages/app-common/src/components/useReportDateOptions";
import {
  loadVoucherDetail,
  loadVoucherPrintDocument,
  loadVouchers,
  reactivateVoucher,
  recordVoucherPrintResult,
  type Voucher,
  type VoucherDetail,
  type VoucherFilters,
  type VoucherStatus
} from "./vouchersApi";

type Translator = (key: string) => string;
type Props = {
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  t: Translator;
};
type ColumnKey = "identifier" | "code" | "createdAt" | "expiresOn" | "initialAmount" | "balance" | "status" | "origin";

const initialFilters: VoucherFilters = { query: "", status: "", from: "", to: "" };
export const voucherTableKey = "gestion.sales.vouchers";
export const voucherColumnDefinitions = [
  { key: "identifier", defaultWidth: 125 },
  { key: "code", defaultWidth: 175 },
  { key: "createdAt", defaultWidth: 145 },
  { key: "expiresOn", defaultWidth: 145 },
  { key: "initialAmount", defaultWidth: 125 },
  { key: "balance", defaultWidth: 125 },
  { key: "status", defaultWidth: 120 },
  { key: "origin", defaultWidth: 230 }
] as const satisfies readonly TableColumnDefinition<ColumnKey>[];

export function VoucherManagementScreen({ locale, session, terminalContext, t }: Props) {
  const token = session.accessToken;
  const admin = session.permissions.includes("ADMIN");
  const [searchInput, setSearchInput] = useState("");
  const [filters, setFilters] = useState(initialFilters);
  const [quickRange, setQuickRange] = useState<ReportDateRange>({ from: "", to: "", label: "" });
  const { dateOptions, dateOptionsFailed, retryDateOptions } = useReportDateOptions("vouchers", token);
  const [rows, setRows] = useState<Voucher[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [totalElements, setTotalElements] = useState(0);
  const [selected, setSelected] = useState<VoucherDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [printing, setPrinting] = useState(false);
  const [reactivationOpen, setReactivationOpen] = useState(false);
  const [reactivationDate, setReactivationDate] = useState("");
  const [reactivationReason, setReactivationReason] = useState("");
  const [reactivating, setReactivating] = useState(false);
  const [reload, setReload] = useState(0);
  const tableRef = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const listBusy = useRef(false);
  const nextPage = useRef(0);
  const moreAvailable = useRef(false);
  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username: session.username,
    accessToken: token,
    tableKey: voucherTableKey,
    definitions: voucherColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableStyle = { gridTemplateColumns: tableLayoutGridTemplate(tableLayout.layout) } as CSSProperties;

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      const query = searchInput.trim();
      setFilters((current) => current.query === query ? current : { ...current, query });
    }, 250);
    return () => window.clearTimeout(timeout);
  }, [searchInput]);

  const loadPage = useCallback(async (append = false) => {
    if (append && (listBusy.current || !moreAvailable.current)) return;
    const revision = ++requestId.current;
    const requestedPage = append ? nextPage.current : 0;
    listBusy.current = true;
    if (!append) {
      nextPage.current = 0;
      moreAvailable.current = false;
      setHasMore(false);
      setRows([]);
      if (tableRef.current) tableRef.current.scrollTop = 0;
    }
    setLoading(true);
    setError(false);
    try {
      const result = await loadVouchers(filters, requestedPage, token);
      if (requestId.current !== revision) return;
      setRows((current) => append
        ? Array.from(new Map([...current, ...result.items].map((row) => [row.code, row])).values())
        : result.items);
      nextPage.current = requestedPage + 1;
      moreAvailable.current = nextPage.current < result.totalPages && result.items.length > 0;
      setHasMore(moreAvailable.current);
      setTotalElements(result.totalElements);
      setSelected((current) => {
        if (!current) return null;
        const replacement = result.items.find((row) => row.code === current.voucher.code);
        return replacement ? { ...current, voucher: replacement } : append ? current : null;
      });
    } catch {
      if (requestId.current === revision) setError(true);
    } finally {
      if (requestId.current === revision) {
        listBusy.current = false;
        setLoading(false);
      }
    }
  }, [filters, token]);

  useEffect(() => {
    void loadPage();
    return () => { requestId.current += 1; };
  }, [loadPage, reload]);

  useEffect(() => {
    const table = tableRef.current;
    if (table && table.clientHeight > 0 && table.scrollHeight <= table.clientHeight
      && hasMore && !loading && !error && searchInput.trim() === filters.query) {
      void loadPage(true);
    }
  }, [rows, hasMore, loading, error, searchInput, filters.query, loadPage]);

  const money = useMemo(
    () => new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }),
    [locale]
  );
  const dateTime = useMemo(
    () => new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" }),
    [locale]
  );
  const dateOnly = useMemo(
    () => new Intl.DateTimeFormat(locale, { dateStyle: "short" }),
    [locale]
  );

  function removeFilter(field: keyof VoucherFilters) {
    if (field === "query") setSearchInput("");
    setFilters((current) => ({ ...current, [field]: "" }));
  }

  async function openDetail(code: string) {
    setMessage(null);
    try {
      setSelected(await loadVoucherDetail(code, token));
      setReactivationOpen(false);
    } catch {
      setMessage({ kind: "error", text: t("gestion.vouchers.detailLoadError") });
    }
  }

  async function printSelected() {
    if (!selected || printing) return;
    setPrinting(true);
    setMessage(null);
    let success = false;
    try {
      const snapshot = await loadVoucherPrintDocument(selected.voucher.code, token);
      const outcome = await outputIssuedVoucher(snapshot, terminalContext, locale);
      success = outcome.status === "PRINTED";
      setMessage({
        kind: success ? "success" : "error",
        text: t(success ? "gestion.vouchers.printed" : "gestion.vouchers.printError")
      });
    } catch {
      setMessage({ kind: "error", text: t("gestion.vouchers.printError") });
    } finally {
      try {
        setSelected(await recordVoucherPrintResult(
          selected.voucher.code,
          success,
          token
        ));
      } catch {
        setMessage({ kind: "error", text: t("gestion.vouchers.printAuditError") });
      }
      setPrinting(false);
    }
  }

  async function reactivate(event: FormEvent) {
    event.preventDefault();
    if (!selected || !admin || !reactivationDate || !reactivationReason.trim()) return;
    setReactivating(true);
    setMessage(null);
    try {
      const detail = await reactivateVoucher(
        selected.voucher.code,
        reactivationDate,
        reactivationReason.trim(),
        token
      );
      setSelected(detail);
      setReactivationOpen(false);
      setReactivationDate("");
      setReactivationReason("");
      setReload((value) => value + 1);
      setMessage({ kind: "success", text: t("gestion.vouchers.reactivated") });
    } catch {
      setMessage({ kind: "error", text: t("gestion.vouchers.reactivationError") });
    } finally {
      setReactivating(false);
    }
  }

  function renderCell(voucher: Voucher, column: ColumnKey) {
    if (column === "identifier") return <strong>{voucher.familyIdentifier}</strong>;
    if (column === "code") return <strong>{voucher.code}</strong>;
    if (column === "createdAt") return dateTime.format(new Date(voucher.createdAt));
    if (column === "expiresOn") return voucher.expiresOn ? dateOnly.format(localDate(voucher.expiresOn)) : t("gestion.vouchers.noExpiration");
    if (column === "initialAmount") return money.format(voucher.initialAmount);
    if (column === "balance") return <strong>{money.format(voucher.balance)}</strong>;
    if (column === "status") return <span className={`gestion-voucher-status ${voucher.status.toLowerCase()}`}>{t(`gestion.vouchers.status.${voucher.status}`)}</span>;
    return voucher.originTickets.join(" · ");
  }

  return (
    <main className="report-screen gestion-embedded-module report-screen--desktop">
      <section className="report-shell" aria-label={t("gestion.navigation.group.customerDocuments")}>
        <header className="report-topbar">
          <h1 className="report-title">{t("gestion.navigation.group.customerDocuments")}</h1>
        </header>
        <section className="report-workspace">
          <header className="report-options">
            <div className="report-heading"><h1>{t("gestion.vouchers.title")}</h1></div>
          </header>
          <div className="report-data gestion-vouchers-workspace">
            {message && <p className={`gestion-voucher-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}

            <div className="report-data-toolbar gestion-voucher-filters gestion-filter-toolbar">
              <label className="query"><span>{t("gestion.vouchers.search")}</span><GestionSearchFrame><input type="search" value={searchInput} placeholder={t("gestion.vouchers.searchPlaceholder")} onChange={(event) => setSearchInput(event.target.value)} /></GestionSearchFrame></label>
              <label data-filter-active={Boolean(filters.status)}><span>{t("gestion.vouchers.status")}</span><ErpSelect value={filters.status} options={statusOptions(t)} onChange={(status) => setFilters((current) => ({ ...current, status: status as VoucherFilters["status"] }))} aria-label={t("gestion.vouchers.status")} /></label>
              <div className="gestion-toolbar-end"><button type="button" onClick={() => setReload((value) => value + 1)} disabled={loading}><ArrowClockwise size={17} aria-hidden="true" />{t("common.refresh")}</button></div>
            </div>

            <ErpFilterChips locale={locale} translate={t} chips={[
              { key: "query", label: t("gestion.vouchers.search"), value: filters.query, onRemove: () => removeFilter("query") },
              { key: "status", label: t("gestion.vouchers.status"), value: filters.status ? t(`gestion.vouchers.status.${filters.status}`) : "", onRemove: () => removeFilter("status") },
              { key: "from", label: t("gestion.vouchers.from"), value: filters.from, onRemove: () => removeFilter("from") },
              { key: "to", label: t("gestion.vouchers.to"), value: filters.to, onRemove: () => removeFilter("to") }
            ]} />

            <div className={`report-table-region gestion-voucher-content ${selected ? "has-detail" : ""}`}>
              <section className="gestion-voucher-list" aria-label={t("gestion.vouchers.title")}>
                <div className="gestion-voucher-table" role="table" aria-rowcount={rows.length} ref={tableRef} onScroll={(event) => {
                  const table = event.currentTarget;
                  if (searchInput.trim() === filters.query && table.scrollHeight - table.scrollTop - table.clientHeight <= 240) void loadPage(true);
                }}>
                  <div className="gestion-voucher-row head" role="row" style={tableStyle}>
                    {visibleColumns.map((column) => <TableLayoutHeaderCell as="span" column={column} key={column.key} resizeLabel={`${t("gestion.vouchers.resize")} ${t(`gestion.vouchers.column.${column.key}`)}`} onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}>{t(`gestion.vouchers.column.${column.key}`)}</TableLayoutHeaderCell>)}
                  </div>
                  {rows.map((voucher) => <button type="button" className={`gestion-voucher-row ${selected?.voucher.code === voucher.code ? "selected" : ""}`} role="row" style={tableStyle} key={voucher.code} onClick={() => void openDetail(voucher.code)}>{visibleColumns.map((column) => <span role="cell" data-column-key={column.key} key={column.key}>{renderCell(voucher, column.key)}</span>)}</button>)}
                  {loading && <div className={`gestion-voucher-state${rows.length ? " gestion-voucher-state--more" : ""}`} role="status">{t("common.loading")}</div>}
                  {!loading && error && <div className={`gestion-voucher-state error${rows.length ? " gestion-voucher-state--more" : ""}`} role="alert">{t("gestion.vouchers.loadError")}</div>}
                  {!loading && !error && rows.length === 0 && <div className="gestion-voucher-state">{t("gestion.vouchers.empty")}</div>}
                </div>
              </section>

              {selected && <aside className="gestion-voucher-detail">
                <header><div><span>{t("gestion.vouchers.detail")}</span><h3>{selected.voucher.code}</h3></div><WindowCloseButton type="button" aria-label={t("common.close")} onClick={() => setSelected(null)} >×</WindowCloseButton></header>
                <dl><div><dt>{t("gestion.vouchers.identifier")}</dt><dd>{selected.voucher.familyIdentifier}</dd></div><div><dt>{t("gestion.vouchers.balance")}</dt><dd>{money.format(selected.voucher.balance)}</dd></div><div><dt>{t("gestion.vouchers.status")}</dt><dd>{t(`gestion.vouchers.status.${selected.voucher.status}`)}</dd></div><div><dt>{t("gestion.vouchers.issuedAt")}</dt><dd>{dateTime.format(new Date(selected.voucher.createdAt))}</dd></div><div><dt>{t("gestion.vouchers.expiresOn")}</dt><dd>{selected.voucher.expiresOn ? dateOnly.format(localDate(selected.voucher.expiresOn)) : t("gestion.vouchers.noExpiration")}</dd></div></dl>
                <section><h4>{t("gestion.vouchers.originTickets")}</h4><p>{selected.voucher.originTickets.join(" · ")}</p></section>
                <div className="gestion-voucher-detail-actions"><button className="erp-dialog-action-confirm" type="button" onClick={() => void printSelected()} disabled={printing}>{t("gestion.vouchers.reprint")}</button>{admin && selected.voucher.status === "EXPIRED" && <button type="button" className="primary" onClick={() => setReactivationOpen((value) => !value)}>{t("gestion.vouchers.reactivate")}</button>}</div>
                {reactivationOpen && <form className="gestion-voucher-reactivation" onSubmit={reactivate}><h4>{t("gestion.vouchers.reactivationTitle")}</h4><label><span>{t("gestion.vouchers.newExpiration")}</span><input type="date" required value={reactivationDate} onChange={(event) => setReactivationDate(event.target.value)} /></label><label><span>{t("gestion.vouchers.reason")}</span><textarea required maxLength={500} value={reactivationReason} onChange={(event) => setReactivationReason(event.target.value)} /></label><button type="submit" className="primary erp-dialog-action-confirm" disabled={reactivating}>{t("gestion.vouchers.confirmReactivation")}</button></form>}
                <section className="gestion-voucher-events"><h4>{t("gestion.vouchers.events")}</h4>{selected.events.length === 0 ? <p>{t("gestion.vouchers.noEvents")}</p> : selected.events.map((event, index) => <article key={`${event.occurredAt}-${index}`}><strong>{t(`gestion.vouchers.event.${event.type}`)}</strong><time>{dateTime.format(new Date(event.occurredAt))}</time><span>{event.operatorUsername ?? event.userId}</span>{event.reason && <p>{event.reason}</p>}</article>)}</section>
              </aside>}
            </div>
            <div className="report-total-row">
              <span>{`${t("salesReport.visibleLines")}: ${rows.length}`}</span>
              <strong className="report-main-total">{t("gestion.vouchers.total").replace("{count}", String(totalElements))}</strong>
            </div>
            {dateOptionsFailed && <div className="report-date-options-error" role="alert">
              <span>{t("salesReport.loadError")}</span>
              <button type="button" onClick={retryDateOptions}>{t("salesReport.retry")}</button>
            </div>}
            <ReportDateRangeFilter locale={locale} today={dateOptions?.currentDate ?? ""} earliestDate={dateOptions?.earliestDate ?? ""}
              value={{ from: filters.from, to: filters.to, label: "", preset: quickRange.from === filters.from && quickRange.to === filters.to ? quickRange.preset : undefined }}
              onChange={(range) => {
                setQuickRange(range);
                setFilters((current) => ({ ...current, from: range.from, to: range.to }));
              }} />
          </div>
        </section>
      </section>
    </main>
  );
}

function statusOptions(t: Translator) {
  const statuses: VoucherStatus[] = ["ACTIVE", "EXPIRED", "CONSUMED", "INVALIDATED"];
  return [{ value: "", label: t("gestion.vouchers.allStatuses") }, ...statuses.map((status) => ({ value: status, label: t(`gestion.vouchers.status.${status}`) }))];
}

function localDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day, 12);
}

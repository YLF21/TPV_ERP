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
import { useEffect, useMemo, useState, type CSSProperties, type FormEvent } from "react";
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
  const [draft, setDraft] = useState(initialFilters);
  const [filters, setFilters] = useState(initialFilters);
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<Voucher[]>([]);
  const [totalPages, setTotalPages] = useState(0);
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
    let active = true;
    setLoading(true);
    setError(false);
    void loadVouchers(filters, page, token).then((result) => {
      if (!active) return;
      setRows(result.items);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
      if (selected) {
        const replacement = result.items.find((row) => row.code === selected.voucher.code);
        if (replacement) setSelected((current) => current ? { ...current, voucher: replacement } : null);
      }
    }).catch(() => {
      if (active) setError(true);
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [filters, page, reload, token]);

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

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    setFilters({ ...draft });
  }

  function clearFilters() {
    setDraft(initialFilters);
    setFilters(initialFilters);
    setPage(0);
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
    <section className="gestion-workspace gestion-vouchers-workspace">
      <header className="gestion-dashboard-toolbar gestion-vouchers-header">
        <div>
          <span className="gestion-eyebrow">{t("gestion.vouchers.eyebrow")}</span>
          <h2>{t("gestion.vouchers.title")}</h2>
          <p>{t("gestion.vouchers.subtitle")}</p>
        </div>
        <button type="button" onClick={() => setReload((value) => value + 1)} disabled={loading}>{t("common.refresh")}</button>
      </header>

      {message && <p className={`gestion-voucher-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}

      <form className="gestion-voucher-filters" onSubmit={applyFilters}>
        <label className="query"><span>{t("gestion.vouchers.search")}</span><input type="search" value={draft.query} placeholder={t("gestion.vouchers.searchPlaceholder")} onChange={(event) => setDraft({ ...draft, query: event.target.value })} /></label>
        <label><span>{t("gestion.vouchers.status")}</span><ErpSelect value={draft.status} options={statusOptions(t)} onChange={(status) => setDraft({ ...draft, status: status as VoucherFilters["status"] })} aria-label={t("gestion.vouchers.status")} /></label>
        <label><span>{t("gestion.vouchers.from")}</span><input type="date" value={draft.from} max={draft.to || undefined} onChange={(event) => setDraft({ ...draft, from: event.target.value })} /></label>
        <label><span>{t("gestion.vouchers.to")}</span><input type="date" value={draft.to} min={draft.from || undefined} onChange={(event) => setDraft({ ...draft, to: event.target.value })} /></label>
        <div><button type="button" onClick={clearFilters}>{t("gestion.vouchers.clear")}</button><button className="primary" type="submit">{t("gestion.vouchers.apply")}</button></div>
      </form>

      <div className={`gestion-voucher-content ${selected ? "has-detail" : ""}`}>
        <section className="gestion-voucher-list" aria-label={t("gestion.vouchers.title")}>
          <div className="gestion-voucher-table" role="table" aria-rowcount={rows.length}>
            <div className="gestion-voucher-row head" role="row" style={tableStyle}>
              {visibleColumns.map((column) => <TableLayoutHeaderCell as="span" column={column} key={column.key} resizeLabel={`${t("gestion.vouchers.resize")} ${t(`gestion.vouchers.column.${column.key}`)}`} onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}>{t(`gestion.vouchers.column.${column.key}`)}</TableLayoutHeaderCell>)}
            </div>
            {rows.map((voucher) => <button type="button" className={`gestion-voucher-row ${selected?.voucher.code === voucher.code ? "selected" : ""}`} role="row" style={tableStyle} key={voucher.code} onClick={() => void openDetail(voucher.code)}>{visibleColumns.map((column) => <span role="cell" data-column-key={column.key} key={column.key}>{renderCell(voucher, column.key)}</span>)}</button>)}
            {loading && <div className="gestion-voucher-state">{t("common.loading")}</div>}
            {!loading && error && <div className="gestion-voucher-state error">{t("gestion.vouchers.loadError")}</div>}
            {!loading && !error && rows.length === 0 && <div className="gestion-voucher-state">{t("gestion.vouchers.empty")}</div>}
          </div>
          <footer><span>{t("gestion.vouchers.total").replace("{count}", String(totalElements))}</span><div><button type="button" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>{t("gestion.vouchers.previous")}</button><span>{t("gestion.vouchers.page").replace("{current}", String(page + 1)).replace("{total}", String(Math.max(totalPages, 1)))}</span><button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage((value) => value + 1)}>{t("gestion.vouchers.next")}</button></div></footer>
        </section>

        {selected && <aside className="gestion-voucher-detail">
          <header><div><span>{t("gestion.vouchers.detail")}</span><h3>{selected.voucher.code}</h3></div><button type="button" aria-label={t("common.close")} onClick={() => setSelected(null)}>×</button></header>
          <dl><div><dt>{t("gestion.vouchers.identifier")}</dt><dd>{selected.voucher.familyIdentifier}</dd></div><div><dt>{t("gestion.vouchers.balance")}</dt><dd>{money.format(selected.voucher.balance)}</dd></div><div><dt>{t("gestion.vouchers.status")}</dt><dd>{t(`gestion.vouchers.status.${selected.voucher.status}`)}</dd></div><div><dt>{t("gestion.vouchers.issuedAt")}</dt><dd>{dateTime.format(new Date(selected.voucher.createdAt))}</dd></div><div><dt>{t("gestion.vouchers.expiresOn")}</dt><dd>{selected.voucher.expiresOn ? dateOnly.format(localDate(selected.voucher.expiresOn)) : t("gestion.vouchers.noExpiration")}</dd></div></dl>
          <section><h4>{t("gestion.vouchers.originTickets")}</h4><p>{selected.voucher.originTickets.join(" · ")}</p></section>
          <div className="gestion-voucher-detail-actions"><button type="button" onClick={() => void printSelected()} disabled={printing}>{t("gestion.vouchers.reprint")}</button>{admin && selected.voucher.status === "EXPIRED" && <button type="button" className="primary" onClick={() => setReactivationOpen((value) => !value)}>{t("gestion.vouchers.reactivate")}</button>}</div>
          {reactivationOpen && <form className="gestion-voucher-reactivation" onSubmit={reactivate}><h4>{t("gestion.vouchers.reactivationTitle")}</h4><label><span>{t("gestion.vouchers.newExpiration")}</span><input type="date" required value={reactivationDate} onChange={(event) => setReactivationDate(event.target.value)} /></label><label><span>{t("gestion.vouchers.reason")}</span><textarea required maxLength={500} value={reactivationReason} onChange={(event) => setReactivationReason(event.target.value)} /></label><button type="submit" className="primary" disabled={reactivating}>{t("gestion.vouchers.confirmReactivation")}</button></form>}
          <section className="gestion-voucher-events"><h4>{t("gestion.vouchers.events")}</h4>{selected.events.length === 0 ? <p>{t("gestion.vouchers.noEvents")}</p> : selected.events.map((event, index) => <article key={`${event.occurredAt}-${index}`}><strong>{t(`gestion.vouchers.event.${event.type}`)}</strong><time>{dateTime.format(new Date(event.occurredAt))}</time><span>{event.operatorUsername ?? event.userId}</span>{event.reason && <p>{event.reason}</p>}</article>)}</section>
        </aside>}
      </div>
    </section>
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

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import "./TicketSerialNumberSearchDialog.css";
import type { AppKind, LocaleCode } from "../types";
import { formatEuroAmount } from "../money";
import { createTranslator } from "../i18n/LocalizedMessages";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableInitialFocus } from "./useTableInitialFocus";
import { useTableArrowRepeat } from "./useTableArrowRepeat";
import { ensureTableRowVisible } from "./ensureTableRowVisible";

type ColumnKey = "number" | "date" | "productCode" | "productName" | "serialNumber" | "total" | "status" | "action";
type SearchSort = { key: Exclude<ColumnKey, "action">; direction: "asc" | "desc" };
const columns: readonly TableColumnDefinition<ColumnKey>[] = [
  { key: "number", defaultWidth: 164, minWidth: 140 },
  { key: "date", defaultWidth: 108, minWidth: 90 },
  { key: "productCode", defaultWidth: 116, minWidth: 90 },
  { key: "productName", defaultWidth: 390, minWidth: 160 },
  { key: "serialNumber", defaultWidth: 110, minWidth: 80 },
  { key: "total", defaultWidth: 100, minWidth: 80 },
  { key: "status", defaultWidth: 130, minWidth: 100 },
  { key: "action", defaultWidth: 154, minWidth: 140 },
];

export type SerialNumberTicketMatch = {
  ticketId: string; lineId: string; number: string; date: string; status: string;
  total: number | string; productCode: string; productName: string; serialNumber: string;
};
type SearchResult = { items: SerialNumberTicketMatch[]; nextPage: number | null };
type Props = {
  locale: LocaleCode; token?: string; app?: AppKind; username?: string;
  request: <T>(path: string, options?: { token?: string }) => Promise<T>;
  onClose: () => void; onOpenTicket: (match: SerialNumberTicketMatch) => void;
};

export function TicketSerialNumberSearchDialog({ locale, token, app = "venta", username = "", request, onClose, onOpenTicket }: Props) {
  const t = createTranslator(locale);
  const root = useRef<HTMLElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const generation = useRef(0);
  const scrollRoot = useRef<HTMLDivElement>(null);
  const loading = useRef(false);
  const debounce = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [serial, setSerial] = useState("");
  const [searched, setSearched] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [sort, setSort] = useState<SearchSort | null>(null);
  const [selected, setSelected] = useState("");
  const matches = result?.items ?? [];
  useTableInitialFocus(scrollRoot, "tbody tr[data-match-key]", {
    ready: !busy && matches.length > 0,
    scopeKey: `${serial}:${sort?.key ?? ""}:${sort?.direction ?? ""}`,
    onSelect: (row) => setSelected(row.dataset.matchKey ?? ""),
    focusTarget: () => serial.trim() ? null : scrollRoot.current?.querySelector<HTMLElement>("tbody tr[data-match-key]") ?? null,
  });
  useLayoutEffect(() => {
    const container = scrollRoot.current;
    const row = Array.from(container?.querySelectorAll<HTMLElement>("tbody tr[data-match-key]") ?? [])
      .find((candidate) => candidate.dataset.matchKey === selected);
    if (!container || !row) return;
    const focused = document.activeElement;
    if (focused instanceof HTMLElement && container.contains(focused)
      && focused.closest("tbody tr[data-match-key]") && focused !== row) row.focus({ preventScroll: true });
    ensureTableRowVisible(container, row);
  }, [selected, result]);
  const repeatMatchArrow = useTableArrowRepeat((direction) => {
    const focused = document.activeElement;
    const container = scrollRoot.current;
    const focusedRow = focused instanceof HTMLElement && container?.contains(focused)
      ? focused.closest<HTMLElement>("tbody tr[data-match-key]") : null;
    if (!focusedRow && focused !== input.current) return false;
    if (busy || matches.length < 2) return false;
    const key = focusedRow?.dataset.matchKey ?? selected;
    const index = matches.findIndex((match) => `${match.ticketId}-${match.lineId}-${match.serialNumber}` === key);
    const nextIndex = Math.max(0, Math.min(matches.length - 1, index + (direction === "ArrowDown" ? 1 : -1)));
    if (index < 0 || nextIndex === index) return false;
    const next = matches[nextIndex];
    setSelected(`${next.ticketId}-${next.lineId}-${next.serialNumber}`);
    return true;
  }, !busy && matches.length > 0);
  const tableLayout = useTableLayoutPreference({ app, username, accessToken: username ? token : undefined,
    tableKey: "sales.serial-number-search", definitions: columns });
  const labels: Record<ColumnKey, string> = {
    number: t("salesReport.column.ticket"), date: t("salesReport.column.date"),
    productCode: t("sale.searchDialog.code"), productName: t("salesReport.column.product"),
    serialNumber: t("salesReport.column.serialNumber"), total: t("sale.main.total"), status: t("salesReport.column.status"), action: "",
  };
  function renderCell(match: SerialNumberTicketMatch, key: ColumnKey) {
    if (key === "action") return <button className="serial-search-primary" type="button"
      onClick={() => onOpenTicket(match)}>{t("salesReport.openDocument")}</button>;
    if (key === "date") return match.date.split("-").reverse().join("/");
    if (key === "total") return formatEuroAmount(match.total, locale);
    if (key === "status") return t(`salesReport.activity.documentStatus.${match.status}`);
    return match[key];
  }
  function toggleSort(key: ColumnKey) {
    if (key === "action" || busy) return;
    const next: SearchSort = { key, direction: sort?.key === key && sort.direction === "asc" ? "desc" : "asc" };
    setSort(next);
  }

  useEffect(() => {
    const release = root.current
      ? activateModalFocusTrap(root.current as unknown as ModalFocusRoot, document) : undefined;
    input.current?.focus();
    return () => { generation.current++; release?.(); };
  }, []);
  useEffect(() => {
    const current = ++generation.current;
    loading.current = true;
    setBusy(Boolean(token)); setError(""); setResult(null);
    debounce.current = setTimeout(() => { void search(serial, 0, sort, false, current); }, serial.trim() ? 180 : 0);
    return () => clearTimeout(debounce.current);
  }, [serial, sort?.key, sort?.direction, token]);

  async function search(value: string, requestedPage = 0, requestedSort = sort,
    append = false, expectedGeneration?: number) {
    if (!token) return;
    const normalized = value.trim();
    const current = expectedGeneration ?? ++generation.current;
    loading.current = true;
    setBusy(true); setError("");
    if (!append) { setResult(null); setSearched(normalized); if (scrollRoot.current) scrollRoot.current.scrollTop = 0; }
    try {
      const response = await request<SearchResult>(
        `/tickets/serial-number-search?serialNumber=${encodeURIComponent(normalized)}&page=${requestedPage}${requestedSort ? `&sortBy=${requestedSort.key}&sortDirection=${requestedSort.direction}` : ""}`,
        { token });
      if (generation.current === current) setResult((previous) => {
        if (!append || !previous) return response;
        const keys = new Set(previous.items.map((item) => `${item.ticketId}-${item.lineId}-${item.serialNumber}`));
        return { ...response, items: [...previous.items, ...response.items.filter((item) =>
          !keys.has(`${item.ticketId}-${item.lineId}-${item.serialNumber}`))] };
      });
    } catch {
      if (generation.current === current) setError(t("salesReport.serialSearch.error"));
    } finally {
      if (generation.current === current) { setBusy(false); loading.current = false; }
    }
  }
  function loadMore() {
    if (result?.nextPage != null && !loading.current) void search(searched, result.nextPage, sort, true);
  }
  return <div className="document-activity-overlay" role="presentation">
    <section ref={root} className="document-activity-dialog ticket-serial-search-dialog" role="dialog"
      aria-modal="true" aria-labelledby="serial-ticket-search-title"
      onKeyDown={(event) => {
        if (event.key !== "Escape" || event.defaultPrevented) return;
        if (event.currentTarget.querySelector('[aria-haspopup="menu"][aria-expanded="true"]')) return;
        event.preventDefault(); event.stopPropagation(); onClose();
      }}>
      <header><h2 id="serial-ticket-search-title">{t("salesReport.serialSearch.title")}</h2>
        <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose}>×</WindowCloseButton>
      </header>
      <section className="serial-search-body">
        <form className="serial-search-toolbar" onSubmit={(event) => { event.preventDefault(); clearTimeout(debounce.current); void search(serial); }}>
          <label>{t("salesReport.serialSearch.input")} <input ref={input} value={serial} maxLength={128}
            onKeyDown={(event) => {
              if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
              event.preventDefault();
              repeatMatchArrow(event);
            }}
            onChange={(event) => { generation.current++; loading.current = true; setSerial(event.target.value); setResult(null); setError(""); }} /></label>
          <button className="serial-search-primary" type="submit" disabled={busy || !token}>{t("salesReport.serialSearch.search")}</button>
        </form>
        <div className="serial-search-results" ref={scrollRoot} onScroll={(event) => {
          const element = event.currentTarget;
          if (element.scrollHeight - element.scrollTop - element.clientHeight < 100) loadMore();
        }}><table
          style={{ width: tableLayout.layout.reduce((total, column) => total + column.width, 0), minWidth: "100%" }}>
          <colgroup>{tableLayout.layout.map((column) => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
          <thead><tr>{tableLayout.layout.map((column) => <TableLayoutHeaderCell key={column.key} column={column}
            movable={false} constrainColumnMenuToViewport showColumnMenu={column.key !== "action"}
            sortDirection={sort?.key === column.key ? sort.direction : null}
            sortLabel={`${labels[column.key]} ${t("salesReport.sort")}`}
            onSort={column.key === "action" ? undefined : toggleSort}
            resizeLabel={`${t("stock.columns.resize")} ${labels[column.key]}`}
            onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}>
            {labels[column.key]}</TableLayoutHeaderCell>)}</tr></thead>
          <tbody>{(result?.items ?? []).map((match) => {
            const key = `${match.ticketId}-${match.lineId}-${match.serialNumber}`;
            return <tr key={key} data-match-key={key} tabIndex={0} className={selected === key ? "selected" : ""}
              onFocus={(event) => { setSelected(key); if (scrollRoot.current) ensureTableRowVisible(scrollRoot.current, event.currentTarget); }} onClick={() => setSelected(key)}
              onDoubleClick={() => onOpenTicket(match)} onKeyDown={(event) => {
                if (event.target !== event.currentTarget) return;
                if (event.key === "Enter" && !event.repeat) { event.preventDefault(); onOpenTicket(match); }
                if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                  event.preventDefault();
                  repeatMatchArrow(event);
                }
              }}>
              {tableLayout.layout.map((column) => <td key={column.key} data-column-key={column.key}>{renderCell(match, column.key)}</td>)}
            </tr>;
          })}</tbody>
        </table>
          {busy && <p role="status">{t("common.loading")}</p>}
          {error && <p className="sale-action-error" role="alert">{error}</p>}
          {result?.items.length === 0 && <p role="status">{t("salesReport.serialSearch.empty")}</p>}
        </div>
      </section>
      <footer className="erp-dialog-actions-row"><DialogDismissButton type="button"
        className="erp-dialog-action-cancel erp-dialog-dismiss" onClick={onClose}>{t("common.close")}</DialogDismissButton></footer>
    </section>
  </div>;
}

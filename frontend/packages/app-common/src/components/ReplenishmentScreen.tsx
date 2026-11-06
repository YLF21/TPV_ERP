import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowCounterClockwise, CheckCircle, FilePdf, FileXls, Funnel, PencilSimple, Trash } from "@phosphor-icons/react";
import type { AppKind, LocaleCode } from "../types";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpListSelect } from "./ErpListSelect";
import { listFilterChips, matchesListFilter } from "./listFilterValues";
import { WarehouseSearchFrame } from "./WarehouseSearchFrame";
import { ErpFilterChips, type ErpFilterChip } from "./ErpFilterChips";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { sortTableRows, useTableSortPreference } from "./tableSorting";
import { useWarehouseListShortcuts } from "./useWarehouseListShortcuts";
import { deleteReplenishments, editReplenishment, exportReplenishments, listReplenishments, setReplenishmentsOrdered, type ReplenishmentFilters, type ReplenishmentView } from "./replenishmentApi";
import "./WarehouseClassicTables.css";
import "./ReplenishmentScreen.css";

type Warehouse = { id: string; name: string; active?: boolean };
type Props = { token: string; locale: LocaleCode; app?: AppKind; username?: string; showModuleHeading?: boolean };
const definitions = [
  { key: "code", defaultWidth: 110 }, { key: "barcode", defaultWidth: 150 },
  { key: "name", defaultWidth: 240 }, { key: "quantity", defaultWidth: 90 },
  { key: "reason", defaultWidth: 190 }, { key: "stock", defaultWidth: 80 },
  { key: "purchasePrice", defaultWidth: 115 }, { key: "supplier", defaultWidth: 170 },
  { key: "family", defaultWidth: 145 }, { key: "subfamily", defaultWidth: 145 },
  { key: "warehouse", defaultWidth: 130 }, { key: "status", defaultWidth: 110 },
  { key: "orderDate", defaultWidth: 130 },
] as const;
type Column = typeof definitions[number]["key"];

export function ReplenishmentScreen({ token, locale, app = "gestion", username = "replenishment", showModuleHeading = true }: Props) {
  const t = createTranslator(locale);
  const rootRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const filterButtonRef = useRef<HTMLButtonElement>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [rows, setRows] = useState<ReplenishmentView[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [filters, setFilters] = useState<ReplenishmentFilters>({});
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [editing, setEditing] = useState<ReplenishmentView | null>(null);
  const [quantity, setQuantity] = useState("1");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [options, setOptions] = useState<ReplenishmentView[]>([]);
  const layout = useTableLayoutPreference({ app, username, accessToken: token, tableKey: "warehouse.replenishment", definitions });
  const sort = useTableSortPreference({ app, username, tableKey: "warehouse.replenishment", columns: definitions.map(item => item.key), defaultSort: null });
  const columns = visibleTableColumns(layout.layout);
  useWarehouseListShortcuts(rootRef, true, Boolean(editing));

  useEffect(() => { void apiRequest<Warehouse[]>("/warehouses", { token }).then(setWarehouses).catch(() => setWarehouses([])); }, [token]);
  useEffect(() => {
    const controller = new AbortController();
    setError(""); setLoading(true);
    void listReplenishments(token, filters, controller.signal).then(value => {
      if (controller.signal.aborted) return;
      setRows(value);
      if (!Object.values(filters).some(Boolean)) setOptions(value);
      setSelected(current => current.filter(id => value.some(item => item.id === id)));
      setLoading(false);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      setRows([]); setSelected([]);
      setError(failure instanceof Error ? failure.message : t("replenishment.loadError"));
      setLoading(false);
    });
    return () => controller.abort();
  }, [token, filters, refresh]);

  const chosen = rows.filter(row => selected.includes(row.id));
  const unique = (id: "supplierId" | "familyId" | "subfamilyId", name: "supplierName" | "familyName" | "subfamilyName") =>
    Array.from(new Map(options.filter(row => row[id]).map(row => [row[id], { id: row[id]!, name: row[name] || row[id]! }])).values())
      .sort((a, b) => a.name.localeCompare(b.name, locale));
  const suppliers = useMemo(() => unique("supplierId", "supplierName"), [options, locale]);
  const families = useMemo(() => unique("familyId", "familyName"), [options, locale]);
  const subfamilies = useMemo(() => unique("subfamilyId", "subfamilyName")
    .filter(item => options.some(row => row.subfamilyId === item.id && matchesListFilter(filters.familyIds, row.familyId))), [options, locale, filters.familyIds]);

  function cell(row: ReplenishmentView, column: Column) {
    switch (column) {
      case "supplier": return row.supplierName || "—";
      case "family": return row.familyName || "—";
      case "subfamily": return row.subfamilyName || "—";
      case "warehouse": return row.warehouseName;
      case "purchasePrice": return row.purchasePrice ?? "—";
      case "status": return t(`replenishment.${row.status.toLowerCase()}`);
      case "orderDate": return row.orderDate ? new Date(row.orderDate.includes("T") ? row.orderDate : `${row.orderDate}T00:00:00`)
        .toLocaleDateString(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES") : "";
      default: return row[column];
    }
  }
  const sortedRows = sortTableRows(rows, sort.sort, (row, key) => {
    if (key === "orderDate") return row.orderDate;
    if (key === "status") return row.status;
    return cell(row, key);
  }, locale);
  function changeFilter<K extends keyof ReplenishmentFilters>(name: K, value: ReplenishmentFilters[K]) {
    setSelected([]);
    setFilters(current => ({
      ...current, [name]: value,
      ...(name === "familyIds" ? { subfamilyIds: undefined } : {})
    }));
  }

  const optionFilters = [
    { key: "warehouseIds", label: "warehouse", choices: warehouses },
    { key: "supplierIds", label: "supplier", choices: suppliers },
    { key: "familyIds", label: "family", choices: families },
    { key: "subfamilyIds", label: "subfamily", choices: subfamilies },
  ] as const;
  const chips: ErpFilterChip[] = optionFilters.flatMap(({ key, label, choices }) =>
    listFilterChips(key, t("replenishment." + label), filters[key] ?? [], values => changeFilter(key, values.length ? values : undefined),
      id => choices.find(item => item.id === id)?.name || id));
  if (filters.search) chips.push({
    key: "search", label: t("replenishment.search"), value: filters.search,
    onRemove: () => { setSearch(""); changeFilter("search", undefined); },
  });
  async function action(operation: () => Promise<unknown>) {
    setBusy(true); setError("");
    try { await operation(); setSelected([]); setEditing(null); setRefresh(value => value + 1); }
    catch (failure) { setError(failure instanceof Error ? failure.message : t("replenishment.saveError")); }
    finally { setBusy(false); }
  }
  async function download(format: "xlsx" | "pdf") {
    setBusy(true); setError("");
    try {
      const blob = await exportReplenishments(token, format, locale, filters, selected.length ? selected : undefined);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = `solicitudes-reposicion.${format}`;
      document.body.append(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url);
    } catch (failure) { setError(failure instanceof Error ? failure.message : t("replenishment.exportError")); }
    finally { setBusy(false); }
  }
  function toggle(id: string) { setSelected(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id]); }
  function editSelected() { if (chosen.length !== 1 || chosen[0].status !== "PENDING") return;
    setEditing(chosen[0]); setQuantity(String(chosen[0].quantity)); setReason(chosen[0].reason); }
  function clearFilters() { setSelected([]); setSearch(""); setFilters({}); }
  const blocked = busy || loading;

  return <div ref={rootRef} data-warehouse-heading={app === "gestion" && showModuleHeading ? "unified" : undefined} data-document-heading={app === "gestion" && showModuleHeading ? "warehouse" : undefined} className="replenishment-screen gestion-warehouse-screen gestion-warehouse-documents erp-classic-tables">
    {showModuleHeading && <div className="gestion-warehouse-transfer-module-title replenishment-module-title"><h1>{t("stock.warehouse")}</h1></div>}
    <header className="work-panel-heading stock-panel-heading gestion-warehouse-titlebar replenishment-titlebar">
      <div><h2>{t("replenishment.title")}</h2><span>{t("replenishment.subtitle")}</span></div>
      <button type="button" className="gestion-warehouse-action--open replenishment-refresh" onClick={() => setRefresh(value => value + 1)} disabled={blocked}>
        <ArrowCounterClockwise size={16} aria-hidden="true" />{t("replenishment.refresh")}
      </button>
    </header>
    <div className="stock-history-toolbar warehouse-purchase-toolbar gestion-warehouse-toolbar gestion-toolbar-layout replenishment-toolbar">
      <label className="replenishment-search-field"><span>{t("replenishment.search")}</span>
        <WarehouseSearchFrame><input ref={searchRef} className="erp-search-input" type="search" placeholder={t("warehouse.adjustment.productSearch")} value={search} onChange={event => { const value = event.target.value; setSearch(value); changeFilter("search", value.trim() || undefined); }}
          onKeyDown={event => { if (event.key === "Enter") event.preventDefault(); }} /></WarehouseSearchFrame>
      </label>
      <button ref={filterButtonRef} type="button" className="replenishment-filter-button" aria-expanded={filtersOpen} aria-controls="replenishment-filters" onClick={() => setFiltersOpen(current => !current)}><Funnel size={18} aria-hidden="true" />{t("salesReport.filter")}</button>
      <div className="gestion-warehouse-list-actions replenishment-actions">
        <button type="button" className="gestion-warehouse-action--open" disabled={blocked || chosen.length !== 1 || chosen[0].status !== "PENDING"} onClick={editSelected}><PencilSimple size={16} aria-hidden="true" />{t("replenishment.edit")}</button>
        <button type="button" disabled={blocked || !chosen.length} onClick={() => void action(() => setReplenishmentsOrdered(token, chosen, true))}><CheckCircle size={16} aria-hidden="true" />{t("replenishment.markOrdered")}</button>
        <button type="button" disabled={blocked || !chosen.length} onClick={() => void action(() => setReplenishmentsOrdered(token, chosen, false))}><ArrowCounterClockwise size={16} aria-hidden="true" />{t("replenishment.markPending")}</button>
        <button type="button" className="gestion-warehouse-action--delete" disabled={blocked || !chosen.length} onClick={() => { if (window.confirm(t("replenishment.deleteConfirm").replace("{count}", String(chosen.length)))) void action(() => deleteReplenishments(token, chosen)); }}><Trash size={16} aria-hidden="true" />{t("replenishment.delete")}</button>
      </div>
      <div className="gestion-warehouse-list-actions replenishment-output-actions">
        <button type="button" className="gestion-warehouse-action--export gestion-warehouse-action--excel" aria-keyshortcuts="F6" disabled={blocked || !!error} onClick={() => void download("xlsx")}><span><FileXls size={16} aria-hidden="true" />{t("replenishment.excel")}</span><kbd aria-hidden="true">F6</kbd></button>
        <button type="button" className="gestion-warehouse-action--export gestion-warehouse-action--pdf" aria-keyshortcuts="F7" disabled={blocked || !!error} onClick={() => void download("pdf")}><span><FilePdf size={16} aria-hidden="true" />{t("replenishment.pdf")}</span><kbd aria-hidden="true">F7</kbd></button>
      </div>
      <div id="replenishment-filters" className="replenishment-filter-fields" hidden={!filtersOpen} onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); setFiltersOpen(false); filterButtonRef.current?.focus(); } }}>
      {optionFilters.map(({ key, label, choices }) => <label className="stock-history-filter-field" key={key}>
        <span>{t("replenishment." + label)}</span>
        <ErpListSelect aria-label={t("replenishment." + label)} values={filters[key] ?? []}
          onChange={values => changeFilter(key, values.length ? values : undefined)}
          options={[{ value: "", label: t("replenishment.all") }, ...choices.map(item => ({ value: item.id, label: item.name }))]} />
      </label>)}
      </div>
    </div>
    <div className="replenishment-results">
    <ErpFilterChips locale={locale} chips={chips} focusRef={searchRef} onClear={clearFilters} />
    <div className="replenishment-context"><span className="replenishment-selected-count">{t("replenishment.selected").replace("{count}", String(selected.length))}</span></div>
    {loading && <p className="stock-operation-status" role="status">{t("common.loading")}</p>}
    {error && <p role="alert" className="stock-operation-status error replenishment-error">{error}</p>}
    <div className="stock-history-table-scroll replenishment-table-wrap warehouse-classic-table erp-classic-tables">
      <table className="report-table" style={{ minWidth: 32 + columns.reduce((sum, column) => sum + column.width, 0) }}>
        <colgroup><col style={{ width: 32 }} />{columns.map(column => <col key={column.key} style={{ width: column.width }} />)}</colgroup>
        <thead><tr><th className="replenishment-select-cell"><input type="checkbox" aria-label={t("replenishment.selectAll")} disabled={blocked} checked={!!rows.length && selected.length === rows.length} onChange={event => setSelected(event.target.checked ? rows.map(row => row.id) : [])} /></th>
          {columns.map(column => <TableLayoutHeaderCell key={column.key} column={column} resizeLabel={t(`replenishment.${column.key}`)}
            sortDirection={sort.sort?.column === column.key ? sort.sort.direction : null} sortLabel={t(`replenishment.${column.key}`)} onSort={sort.toggleSort}
            onReorder={layout.reorderColumns} onMove={layout.moveColumn} onResize={layout.resizeColumn} onToggleVisibility={layout.toggleColumnVisibility}
            columnVisibilityOptions={layout.layout.map(item => ({ key: item.key, label: t(`replenishment.${item.key}`), visible: item.visible, disabled: item.visible && columns.length <= 1 }))}>
            {t(`replenishment.${column.key}`)}
          </TableLayoutHeaderCell>)}
        </tr></thead>
        <tbody>{sortedRows.map((row, index) => <tr key={row.id} className={selected.includes(row.id) ? "selected" : ""} aria-selected={selected.includes(row.id)} aria-disabled={blocked || undefined}
          onClick={event => {
            if (blocked || (event.target instanceof Element && event.target.closest("input, button, a, select, textarea"))) return;
            toggle(row.id);
            event.currentTarget.querySelector<HTMLInputElement>('input[type="checkbox"]')?.focus({ preventScroll: true });
          }}>
          <td className="replenishment-select-cell"><input type="checkbox" aria-label={`${t("replenishment.select")} ${row.name}`} disabled={blocked} checked={selected.includes(row.id)} onChange={() => toggle(row.id)}
            onKeyDown={event => { if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return; event.preventDefault();
              const target = Math.max(0, Math.min(sortedRows.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)));
              event.currentTarget.closest("tbody")?.querySelectorAll<HTMLInputElement>("input[type=checkbox]")[target]?.focus(); }} /></td>
          {columns.map(column => <td key={column.key} title={String(cell(row, column.key) ?? "")}>{cell(row, column.key)}</td>)}
        </tr>)}</tbody>
      </table>
      {!rows.length && !loading && <p className="replenishment-empty">{t("replenishment.empty")}</p>}
    </div>
    </div>
    {editing && <div className="replenishment-dialog-backdrop"><form role="dialog" aria-modal="true" aria-label={t("replenishment.edit")} onSubmit={(event: FormEvent) => { event.preventDefault(); const value = Number(quantity);
      if (Number.isFinite(value) && value > 0 && Number(value.toFixed(3)) === value && reason.trim()) void action(() => editReplenishment(token, editing, value, reason.trim())); }}>
      <h3>{t("replenishment.edit")} · {editing.name}</h3>
      <label>{t("replenishment.quantity")}<input type="number" min="0.001" step="0.001" required value={quantity} onChange={event => setQuantity(event.target.value)} /></label>
      <label>{t("replenishment.reason")}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
      <div><button type="button" onClick={() => setEditing(null)}>{t("replenishment.cancel")}</button><button type="submit" disabled={blocked}>{t("replenishment.save")}</button></div>
    </form></div>}
  </div>;
}

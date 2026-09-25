import "../../../packages/app-common/src/components/ErpClassicTables.css";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  ErpSelect,
  PartyDirectoryPanel,
  SafeRetirementDialog,
  TableLayoutHeaderCell,
  apiRequest,
  createTranslator,
  sortTableRows,
  useTableLayoutPreference,
  useTableSortPreference,
  visibleTableColumns
} from "../../../packages/app-common/src";
import type {
  LocaleCode,
  RetirementResult,
  TableColumnDefinition,
  UserSession
} from "../../../packages/app-common/src";
import "./safe-management.css";
import "./supplier-management-classic.css";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { ErpConfirmDialog } from "../../../packages/app-common/src/components/ErpConfirmDialog";

type SupplierManagementScreenProps = {
  locale: LocaleCode;
  session: UserSession;
};

type SupplierLinkView = {
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  primary: boolean;
};

type SalesRepresentativeView = {
  id: string;
  version: number;
  commercialId: string;
  name: string;
  phone?: string | null;
  email?: string | null;
  otherContact?: string | null;
  active: boolean;
  suppliers: SupplierLinkView[];
};

type SupplierOption = {
  id: string;
  supplierId: string;
  legalName: string;
  active: boolean;
};

type PagedResult<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

type RepresentativeForm = {
  name: string;
  phone: string;
  email: string;
  otherContact: string;
};

const emptyRepresentativeForm: RepresentativeForm = {
  name: "",
  phone: "",
  email: "",
  otherContact: ""
};

type RepresentativeColumn = "code" | "name" | "phone" | "email" | "status";

const representativeColumns: readonly TableColumnDefinition<RepresentativeColumn>[] = [
  { key: "code", defaultWidth: 120, minWidth: 90 },
  { key: "name", defaultWidth: 250, minWidth: 150 },
  { key: "phone", defaultWidth: 160, minWidth: 110 },
  { key: "email", defaultWidth: 250, minWidth: 150 },
  { key: "status", defaultWidth: 120, minWidth: 90 }
];

type RepresentativeConfirmation =
  | { type: "toggle" }
  | { type: "unlink"; link: SupplierLinkView };

function representativeForm(view: SalesRepresentativeView): RepresentativeForm {
  return {
    name: view.name,
    phone: view.phone ?? "",
    email: view.email ?? "",
    otherContact: view.otherContact ?? ""
  };
}

function representativePagePath(query: string, active: "all" | "active" | "inactive", cursor?: string | null) {
  const parameters = new URLSearchParams({ size: "50" });
  if (query.trim()) parameters.set("search", query.trim());
  if (active !== "all") parameters.set("active", String(active === "active"));
  if (cursor) parameters.set("cursor", cursor);
  return `/sales-representatives/management/page?${parameters.toString()}`;
}

function supplierSearchPath(query: string) {
  const parameters = new URLSearchParams({ size: "25", active: "true" });
  if (query.trim()) parameters.set("search", query.trim());
  return `/suppliers/management/page?${parameters.toString()}`;
}

export function SupplierManagementScreen({ locale, session }: SupplierManagementScreenProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [tab, setTab] = useState<"suppliers" | "representatives">("suppliers");

  if (!session.permissions.includes("ADMIN")) {
    return <div className="gestion-security-state error" role="alert">{t("safeManagement.noAccess")}</div>;
  }

  const tabs = (<div className="gestion-safe-management-tabs" role="tablist" aria-label={t("safeManagement.suppliers.title")}>
        <button type="button" role="tab" aria-selected={tab === "suppliers"} className={tab === "suppliers" ? "selected" : ""} onClick={() => setTab("suppliers")}>
          {t("safeManagement.suppliers.tab.suppliers")}
        </button>
        <button type="button" role="tab" aria-selected={tab === "representatives"} className={tab === "representatives" ? "selected" : ""} onClick={() => setTab("representatives")}>
          {t("safeManagement.suppliers.tab.representatives")}
        </button>
      </div>);

  return (
    <section className="gestion-safe-management erp-classic-tables" aria-labelledby="supplier-management-title">
      <header className="gestion-safe-management-heading">
        <h1 id="supplier-management-title">{t("home.product").toLocaleUpperCase(locale === "zh" ? "zh-CN" : locale)}</h1>
      </header>

      <div role="tabpanel" className={tab === "suppliers" ? "supplier-directory-tab" : undefined}>
        {tab === "suppliers"
          ? <PartyDirectoryPanel app="gestion" kind="suppliers" locale={locale} session={session} allowSafeRetirement headerExtra={tabs} />
          : <SalesRepresentativeManagementPanel locale={locale} session={session} headerExtra={tabs} />}
      </div>
    </section>
  );
}

function SalesRepresentativeManagementPanel({ locale, session, headerExtra }: SupplierManagementScreenProps & { headerExtra?: ReactNode }) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const [rows, setRows] = useState<SalesRepresentativeView[]>([]);
  const [query, setQuery] = useState("");
  const [activeFilter, setActiveFilter] = useState<"all" | "active" | "inactive">("all");
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [status, setStatus] = useState("");
  const [selected, setSelected] = useState<SalesRepresentativeView | null>(null);
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<RepresentativeConfirmation | null>(null);
  const [form, setForm] = useState<RepresentativeForm>(emptyRepresentativeForm);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [retirementOpen, setRetirementOpen] = useState(false);
  const [supplierQuery, setSupplierQuery] = useState("");
  const [supplierOptions, setSupplierOptions] = useState<SupplierOption[]>([]);
  const [supplierId, setSupplierId] = useState("");
  const [primaryLink, setPrimaryLink] = useState(false);
  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username: session.username,
    accessToken: session.accessToken,
    tableKey: "suppliers.representatives",
    definitions: representativeColumns
  });
  const tableSort = useTableSortPreference({
    app: "gestion",
    username: session.username,
    tableKey: "suppliers.representatives",
    columns: representativeColumns.map((column) => column.key),
    defaultSort: { column: "code", direction: "asc" },
    persistent: true
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const gridTemplateColumns = visibleColumns.map(column => `minmax(${column.width}px, ${column.width}fr)`).join(" ");
  const sortedRows = sortTableRows(rows, tableSort.sort, (row, column) => {
    switch (column) {
      case "code": return row.commercialId;
      case "name": return row.name;
      case "phone": return row.phone;
      case "email": return row.email;
      case "status": return t(row.active ? "safeManagement.representatives.active" : "safeManagement.representatives.inactive");
    }
  }, locale);

  function representativeCell(row: SalesRepresentativeView, column: RepresentativeColumn) {
    switch (column) {
      case "code": return <strong>{row.commercialId}</strong>;
      case "name": return row.name;
      case "phone": return row.phone || "-";
      case "email": return row.email || "-";
      case "status": return <span className={`representative-status ${row.active ? "representative-status--active" : "representative-status--inactive"}`}>{t(row.active ? "safeManagement.representatives.active" : "safeManagement.representatives.inactive")}</span>;
    }
  }

  async function load(append = false, propagateError = false) {
    if (append) setLoadingMore(true);
    else setLoading(true);
    setLoadError(false);
    try {
      const page = await apiRequest<PagedResult<SalesRepresentativeView>>(
        representativePagePath(query, activeFilter, append ? nextCursor : null),
        { token: session.accessToken }
      );
      setRows((current) => append ? [...current, ...page.items] : page.items);
      setNextCursor(page.nextCursor ?? null);
      setHasMore(Boolean(page.hasMore));
      if (selected) {
        const refreshed = page.items.find((item) => item.id === selected.id);
        if (refreshed) {
          setSelected(refreshed);
          setForm(representativeForm(refreshed));
        }
      }
    } catch (error) {
      setLoadError(true);
      setStatus(t("safeManagement.representatives.loadError"));
      if (propagateError) throw error;
    } finally {
      if (append) setLoadingMore(false);
      else setLoading(false);
    }
  }

  useEffect(() => {
    const timeoutId = window.setTimeout(() => void load(), 250);
    return () => window.clearTimeout(timeoutId);
  }, [activeFilter, query, session.accessToken]);

  useEffect(() => {
    if (!dialogOpen || !selected) {
      setSupplierOptions([]);
      return;
    }
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => {
      void apiRequest<PagedResult<SupplierOption>>(supplierSearchPath(supplierQuery), {
        token: session.accessToken,
        signal: controller.signal
      }).then((page) => {
        const linked = new Set(selected.suppliers.map((link) => link.supplierId));
        setSupplierOptions(page.items.filter((supplier) => !linked.has(supplier.id)));
      }).catch(() => {
        if (!controller.signal.aborted) setSupplierOptions([]);
      });
    }, 250);
    return () => {
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [dialogOpen, selected, session.accessToken, supplierQuery]);

  function openNew() {
    setSelected(null);
    setForm(emptyRepresentativeForm);
    setSupplierQuery("");
    setSupplierId("");
    setStatus("");
    setDialogOpen(true);
  }

  function openRepresentative(row: SalesRepresentativeView) {
    setSelectedRowId(row.id);
    setSelected(row);
    setForm(representativeForm(row));
    setSupplierQuery("");
    setSupplierId("");
    setPrimaryLink(false);
    setStatus("");
    setDialogOpen(true);
  }

  async function saveRepresentative(event: React.FormEvent) {
    event.preventDefault();
    if (!form.name.trim()) {
      setStatus(t("safeManagement.representatives.nameRequired"));
      return;
    }
    setSaving(true);
    setStatus("");
    try {
      await apiRequest(selected ? `/sales-representatives/${selected.id}` : "/sales-representatives", {
        method: selected ? "PUT" : "POST",
        token: session.accessToken,
        body: {
          name: form.name.trim(),
          phone: form.phone.trim() || null,
          email: form.email.trim() || null,
          otherContact: form.otherContact.trim() || null
        }
      });
      setDialogOpen(false);
      setSelected(null);
      await load();
      setStatus(t("safeManagement.representatives.saved"));
    } catch {
      setStatus(t("safeManagement.representatives.saveError"));
    } finally {
      setSaving(false);
    }
  }

  async function toggleActive() {
    if (!selected || saving) return;
    const action = selected.active ? "deactivate" : "activate";
    setSaving(true);
    setStatus("");
    try {
      await apiRequest(`/sales-representatives/${selected.id}/${action}`, {
        method: "PATCH",
        token: session.accessToken
      });
      setDialogOpen(false);
      setSelected(null);
      await load();
      setStatus(t(`safeManagement.representatives.${action}d`));
    } catch {
      setStatus(t("safeManagement.representatives.saveError"));
    } finally {
      setSaving(false);
      setConfirmation(null);
    }
  }

  async function linkSupplier() {
    if (!selected || !supplierId || saving) return;
    setSaving(true);
    setStatus("");
    try {
      await apiRequest(`/suppliers/${supplierId}/sales-representatives/${selected.id}`, {
        method: "PUT",
        token: session.accessToken,
        body: { primary: primaryLink }
      });
      await refreshSelected(selected.id);
      setSupplierId("");
      setPrimaryLink(false);
      setStatus(t("safeManagement.representatives.linkSuccess"));
    } catch {
      setStatus(t("safeManagement.representatives.linkError"));
    } finally {
      setSaving(false);
    }
  }

  async function unlinkSupplier(link: SupplierLinkView) {
    if (!selected || saving) return;
    setSaving(true);
    setStatus("");
    try {
      await apiRequest(`/suppliers/${link.supplierId}/sales-representatives/${selected.id}`, {
        method: "DELETE",
        token: session.accessToken
      });
      await refreshSelected(selected.id);
      setStatus(t("safeManagement.representatives.unlinkSuccess"));
    } catch {
      setStatus(t("safeManagement.representatives.linkError"));
    } finally {
      setSaving(false);
      setConfirmation(null);
    }
  }

  async function refreshSelected(id: string) {
    const refreshed = await apiRequest<SalesRepresentativeView>(`/sales-representatives/management/${id}`, {
      token: session.accessToken
    });
    setSelected(refreshed);
    setRows((current) => current.map((row) => row.id === refreshed.id ? refreshed : row));
  }

  function openRetirement() {
    if (!selected) return;
    setDialogOpen(false);
    setRetirementOpen(true);
  }

  const toolbarRepresentative = rows.find(row => row.id === selectedRowId) ?? null;
  function retireToolbarRepresentative() {
    if (!toolbarRepresentative || loading || saving) return;
    setSelected(toolbarRepresentative);
    setForm(representativeForm(toolbarRepresentative));
    setStatus("");
    setRetirementOpen(true);
  }
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || !["F7", "F8", "F9"].includes(event.key)) return;
      if (dialogOpen || retirementOpen || confirmation || document.querySelector('[aria-modal="true"]')) return;
      event.preventDefault();
      if (event.key === "F8") openNew();
      if (event.key === "F7" && toolbarRepresentative && !loading) openRepresentative(toolbarRepresentative);
      if (event.key === "F9") retireToolbarRepresentative();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  });

  async function completeRetirement(result: RetirementResult) {
    await load(false, true);
    setRetirementOpen(false);
    setSelected(null);
    setStatus(t(`safeManagement.result.${result.outcome}`));
  }

  return (
    <section className="representative-management erp-classic-tables" aria-labelledby="representative-management-title">
      <header className="work-panel-heading stock-panel-heading">
        <div>
          <h2 id="representative-management-title">{t("safeManagement.representatives.title")}</h2>
          <span>{t("safeManagement.representatives.subtitle")}</span>
        </div>
        <div className="management-record-actions">
          <button type="button" aria-keyshortcuts="F7" disabled={!toolbarRepresentative || loading} onClick={() => toolbarRepresentative && openRepresentative(toolbarRepresentative)}>{t("safeManagement.shortcut.modify")} {t("safeManagement.representatives.edit")}</button>
          <button type="button" aria-keyshortcuts="F8" onClick={openNew}>{t("safeManagement.shortcut.add")} {t("safeManagement.representatives.new")}</button>
          <button type="button" aria-keyshortcuts="F9" className="safe-retirement-open" disabled={!toolbarRepresentative || loading} onClick={retireToolbarRepresentative}>{t("safeManagement.shortcut.retire")} {t("safeManagement.action.retire")}</button>
        </div>
      </header>
      {headerExtra}
      <div className="party-directory-toolbar party-directory-toolbar--classic">
        <label className="party-directory-search-label" htmlFor="representative-search">{t("party.searchLabel")}</label>
        <input id="representative-search" type="search" aria-label={t("safeManagement.representatives.search")} placeholder={t("safeManagement.representatives.search")} value={query} onChange={(event) => setQuery(event.target.value)} />
        <label className="party-directory-status-filter"><span>{t("party.column.status")}</span><ErpSelect
          className="erp-select--compact"
          aria-label={t("safeManagement.representatives.column.status")}
          value={activeFilter}
          onChange={(value) => setActiveFilter(value as "all" | "active" | "inactive")}
          options={["all", "active", "inactive"].map((value) => ({ value, label: t(`party.filter.status.${value}`) }))}
        />
        </label>
      </div>
      <ErpFilterChips translate={t} chips={[
        { key: "search", label: t("party.searchLabel"), value: query, onRemove: () => setQuery("") },
        { key: "active", label: t("party.status"), value: activeFilter === "all" ? "" : t(`party.filter.status.${activeFilter}`), onRemove: () => setActiveFilter("all") }
      ]} onClear={() => { setQuery(""); setActiveFilter("all"); }} />
      {status && <p className="product-create-status safe-management-notice" role={loadError ? "alert" : "status"}>{status}</p>}
      <div className="representative-management-table-wrap">
      <div className="representative-management-table" role="table" aria-label={t("safeManagement.representatives.title")}
        style={{ minWidth: visibleColumns.reduce((total, column) => total + column.width, 0) }}>
        <div className="representative-management-row header" role="row" style={{ gridTemplateColumns }}>
          {visibleColumns.map((column) => {
            const label = t(`safeManagement.representatives.column.${column.key}`);
            return <TableLayoutHeaderCell
              as="div"
              column={column}
              key={column.key}
              sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null}
              sortLabel={`${t("party.sortBy")} ${label}`}
              onSort={tableSort.toggleSort}
              resizeLabel={`${t("stock.columns.resize")} ${label}`}
              onReorder={tableLayout.reorderColumns}
              onMove={tableLayout.moveColumn}
              onResize={tableLayout.resizeColumn}
            >{label}</TableLayoutHeaderCell>;
          })}
        </div>
        {loading && <div className="party-directory-state">{t("common.loading")}</div>}
        {!loading && loadError && <div className="party-directory-state error" role="alert"><button type="button" onClick={() => void load()}>{t("party.retry")}</button></div>}
        {!loading && !loadError && sortedRows.map((row) => (
          <button type="button" className={`representative-management-row${selectedRowId === row.id ? " selected" : ""}`}
            role="row" aria-selected={selectedRowId === row.id} key={row.id} style={{ gridTemplateColumns }} onClick={(event) => { setSelectedRowId(row.id); if (event.detail === 0) openRepresentative(row); }} onDoubleClick={() => openRepresentative(row)}>
            {visibleColumns.map((column) => <span role="cell" data-column-key={column.key} key={column.key}>{representativeCell(row, column.key)}</span>)}
          </button>
        ))}
        {!loading && !loadError && rows.length === 0 && <div className="party-directory-state">{t("safeManagement.representatives.empty")}</div>}
      </div>
      </div>
      {!loading && !loadError && hasMore && <div className="party-directory-pagination"><button type="button" onClick={() => void load(true)} disabled={loadingMore || !nextCursor}>{t(loadingMore ? "safeManagement.pagination.loading" : "safeManagement.pagination.more")}</button></div>}

      {dialogOpen && <div className="filter-overlay erp-classic-overlay" role="dialog" aria-modal="true" aria-labelledby="representative-form-title">
        <section className="filter-dialog product-create-dialog representative-management-dialog erp-classic-window" inert={confirmation !== null || undefined}>
          <header className="filter-header">
            <div><h3 id="representative-form-title">{selected ? t("safeManagement.representatives.detail") : t("safeManagement.representatives.new")}</h3><span>{selected?.commercialId ?? t("safeManagement.representatives.subtitle")}</span></div>
            <button type="button" onClick={() => setDialogOpen(false)} disabled={saving}>{t("common.close")}</button>
          </header>
          <form className="representative-management-form" onSubmit={saveRepresentative}>
            <label><span>{t("safeManagement.representatives.column.name")}</span><input autoFocus required value={form.name} onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))} /></label>
            <label><span>{t("safeManagement.representatives.column.phone")}</span><input type="tel" value={form.phone} onChange={(event) => setForm((current) => ({ ...current, phone: event.target.value }))} /></label>
            <label><span>{t("safeManagement.representatives.column.email")}</span><input type="email" value={form.email} onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))} /></label>
            <label className="representative-management-form-wide"><span>{t("safeManagement.representatives.otherContact")}</span><input value={form.otherContact} onChange={(event) => setForm((current) => ({ ...current, otherContact: event.target.value }))} /></label>
            {selected && <section className="representative-supplier-links" aria-labelledby="representative-links-title">
              <h4 id="representative-links-title">{t("safeManagement.representatives.linkedSuppliers")}</h4>
              {selected.suppliers.length === 0 && <p>{t("safeManagement.representatives.noLinkedSuppliers")}</p>}
              {selected.suppliers.map((link) => <div className="representative-supplier-link" key={link.supplierId}><span><strong>{link.supplierCode}</strong> · {link.supplierName}{link.primary ? ` · ${t("safeManagement.representatives.primary")}` : ""}</span><button type="button" onClick={() => setConfirmation({ type: "unlink", link })} disabled={saving}>{t("safeManagement.representatives.unlink")}</button></div>)}
              <div className="representative-link-editor">
                <label><span>{t("safeManagement.representatives.supplierSearch")}</span><input type="search" value={supplierQuery} onChange={(event) => { setSupplierQuery(event.target.value); setSupplierId(""); }} /></label>
                <ErpSelect aria-label={t("safeManagement.representatives.selectSupplier")} value={supplierId} onChange={setSupplierId} options={[{ value: "", label: t("safeManagement.representatives.selectSupplier") }, ...supplierOptions.map((supplier) => ({ value: supplier.id, label: `${supplier.supplierId} · ${supplier.legalName}` }))]} />
                <label className="representative-primary-link"><input type="checkbox" checked={primaryLink} onChange={(event) => setPrimaryLink(event.target.checked)} /><span>{t("safeManagement.representatives.primary")}</span></label>
                <button type="button" onClick={() => void linkSupplier()} disabled={!supplierId || saving}>{t("safeManagement.representatives.link")}</button>
              </div>
            </section>}
            {status && <p className="product-create-status" role="status">{status}</p>}
            <footer className="filter-actions">
              {selected && <button type="button" className="safe-retirement-open" onClick={openRetirement} disabled={saving}>{t("safeManagement.action.retire")}</button>}
              {selected && <button type="button" onClick={() => setConfirmation({ type: "toggle" })} disabled={saving}>{t(selected.active ? "safeManagement.representatives.deactivate" : "safeManagement.representatives.activate")}</button>}
              <button type="button" onClick={() => setDialogOpen(false)} disabled={saving}>{t("common.cancel")}</button>
              <button type="submit" disabled={saving}>{saving ? t("party.saving") : t("common.save")}</button>
            </footer>
          </form>
        </section>
      </div>}

      {confirmation && selected && <ErpConfirmDialog
        title={t("safeManagement.representatives.detail")}
        message={confirmation.type === "toggle"
          ? t(`safeManagement.representatives.confirm.${selected.active ? "deactivate" : "activate"}`)
          : t("safeManagement.representatives.confirmUnlink")}
        confirmLabel={confirmation.type === "toggle"
          ? t(selected.active ? "safeManagement.representatives.deactivate" : "safeManagement.representatives.activate")
          : t("safeManagement.representatives.unlink")}
        cancelLabel={t("common.cancel")}
        busy={saving}
        onCancel={() => setConfirmation(null)}
        onConfirm={() => void (confirmation.type === "toggle" ? toggleActive() : unlinkSupplier(confirmation.link))}
      />}

      {retirementOpen && selected && <SafeRetirementDialog
        open
        classicWindow
        entityPath="sales-representatives"
        entityId={selected.id}
        entityLabel={`${selected.commercialId} · ${selected.name}`}
        locale={locale}
        token={session.accessToken}
        onClose={() => { setRetirementOpen(false); setDialogOpen(true); }}
        onRetired={completeRetirement}
      />}
    </section>
  );
}

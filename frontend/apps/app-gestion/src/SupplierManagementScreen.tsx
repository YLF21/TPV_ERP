import { useEffect, useMemo, useState } from "react";
import {
  ErpSelect,
  PartyDirectoryPanel,
  SafeRetirementDialog,
  apiRequest,
  createTranslator
} from "../../../packages/app-common/src";
import type {
  LocaleCode,
  RetirementResult,
  UserSession
} from "../../../packages/app-common/src";
import "./safe-management.css";

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

  return (
    <section className="gestion-safe-management" aria-labelledby="supplier-management-title">
      <header className="gestion-safe-management-heading">
        <div>
          <h2 id="supplier-management-title">{t("safeManagement.suppliers.title")}</h2>
          <p>{t("safeManagement.suppliers.subtitle")}</p>
        </div>
      </header>
      <div className="gestion-safe-management-tabs" role="tablist" aria-label={t("safeManagement.suppliers.title")}>
        <button type="button" role="tab" aria-selected={tab === "suppliers"} className={tab === "suppliers" ? "selected" : ""} onClick={() => setTab("suppliers")}>
          {t("safeManagement.suppliers.tab.suppliers")}
        </button>
        <button type="button" role="tab" aria-selected={tab === "representatives"} className={tab === "representatives" ? "selected" : ""} onClick={() => setTab("representatives")}>
          {t("safeManagement.suppliers.tab.representatives")}
        </button>
      </div>
      <div role="tabpanel">
        {tab === "suppliers"
          ? <PartyDirectoryPanel app="gestion" kind="suppliers" locale={locale} session={session} allowSafeRetirement />
          : <SalesRepresentativeManagementPanel locale={locale} session={session} />}
      </div>
    </section>
  );
}

function SalesRepresentativeManagementPanel({ locale, session }: SupplierManagementScreenProps) {
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
  const [form, setForm] = useState<RepresentativeForm>(emptyRepresentativeForm);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [retirementOpen, setRetirementOpen] = useState(false);
  const [supplierQuery, setSupplierQuery] = useState("");
  const [supplierOptions, setSupplierOptions] = useState<SupplierOption[]>([]);
  const [supplierId, setSupplierId] = useState("");
  const [primaryLink, setPrimaryLink] = useState(false);

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
    if (!window.confirm(t(`safeManagement.representatives.confirm.${action}`))) return;
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
    if (!window.confirm(t("safeManagement.representatives.confirmUnlink"))) return;
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

  async function completeRetirement(result: RetirementResult) {
    await load(false, true);
    setRetirementOpen(false);
    setSelected(null);
    setStatus(t(`safeManagement.result.${result.outcome}`));
  }

  return (
    <section className="representative-management" aria-labelledby="representative-management-title">
      <header className="work-panel-heading stock-panel-heading">
        <div>
          <h3 id="representative-management-title">{t("safeManagement.representatives.title")}</h3>
          <span>{t("safeManagement.representatives.subtitle")}</span>
        </div>
        <button type="button" className="stock-add-product-button" onClick={openNew}>{t("safeManagement.representatives.new")}</button>
      </header>
      <div className="party-directory-toolbar">
        <input type="search" aria-label={t("safeManagement.representatives.search")} placeholder={t("safeManagement.representatives.search")} value={query} onChange={(event) => setQuery(event.target.value)} />
        <ErpSelect
          className="erp-select--compact"
          aria-label={t("safeManagement.representatives.column.status")}
          value={activeFilter}
          onChange={(value) => setActiveFilter(value as "all" | "active" | "inactive")}
          options={["all", "active", "inactive"].map((value) => ({ value, label: t(`party.filter.status.${value}`) }))}
        />
      </div>
      {status && <p className="product-create-status safe-management-notice" role={loadError ? "alert" : "status"}>{status}</p>}
      <div className="representative-management-table" role="table" aria-label={t("safeManagement.representatives.title")}>
        <div className="representative-management-row header" role="row">
          <span role="columnheader">{t("safeManagement.representatives.column.code")}</span>
          <span role="columnheader">{t("safeManagement.representatives.column.name")}</span>
          <span role="columnheader">{t("safeManagement.representatives.column.phone")}</span>
          <span role="columnheader">{t("safeManagement.representatives.column.email")}</span>
          <span role="columnheader">{t("safeManagement.representatives.column.status")}</span>
        </div>
        {loading && <div className="party-directory-state">{t("common.loading")}</div>}
        {!loading && loadError && <div className="party-directory-state error" role="alert"><button type="button" onClick={() => void load()}>{t("party.retry")}</button></div>}
        {!loading && !loadError && rows.map((row) => (
          <button type="button" className="representative-management-row" role="row" key={row.id} onClick={() => openRepresentative(row)}>
            <strong>{row.commercialId}</strong>
            <span>{row.name}</span>
            <span>{row.phone || "-"}</span>
            <span>{row.email || "-"}</span>
            <span className={row.active ? "party-status active" : "party-status"}>{t(row.active ? "safeManagement.representatives.active" : "safeManagement.representatives.inactive")}</span>
          </button>
        ))}
        {!loading && !loadError && rows.length === 0 && <div className="party-directory-state">{t("safeManagement.representatives.empty")}</div>}
      </div>
      {!loading && !loadError && hasMore && <div className="party-directory-pagination"><button type="button" onClick={() => void load(true)} disabled={loadingMore || !nextCursor}>{t(loadingMore ? "safeManagement.pagination.loading" : "safeManagement.pagination.more")}</button></div>}

      {dialogOpen && <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="representative-form-title">
        <section className="filter-dialog product-create-dialog representative-management-dialog">
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
              {selected.suppliers.map((link) => <div className="representative-supplier-link" key={link.supplierId}><span><strong>{link.supplierCode}</strong> · {link.supplierName}{link.primary ? ` · ${t("safeManagement.representatives.primary")}` : ""}</span><button type="button" onClick={() => void unlinkSupplier(link)} disabled={saving}>{t("safeManagement.representatives.unlink")}</button></div>)}
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
              {selected && <button type="button" onClick={() => void toggleActive()} disabled={saving}>{t(selected.active ? "safeManagement.representatives.deactivate" : "safeManagement.representatives.activate")}</button>}
              <button type="button" onClick={() => setDialogOpen(false)} disabled={saving}>{t("common.cancel")}</button>
              <button type="submit" disabled={saving}>{saving ? t("party.saving") : t("common.save")}</button>
            </footer>
          </form>
        </section>
      </div>}

      {retirementOpen && selected && <SafeRetirementDialog
        open
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

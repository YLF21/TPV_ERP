import { FormEvent, ReactNode, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";

import type { Credentials, SupportTicket, TenantPortalData } from "../../lib/types";
import { Notice, MasterMode } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { errorMessage, formatDate, ticketStatusLabel, ticketPriorityLabel } from "../../shared/lib";
import { LanguageSelector, EmptyState, Metric, SectionHeader, Segmented, Input, StatusPill } from "../../shared/ui";
import { LicenseTable } from "../../shared/license-tables";
import { InvoiceTable } from "../billing/BillingView";
import { MasterTable } from "../masters/MastersView";
import { canWriteTenantMasters } from "./access-selection.mjs";
import { useTenantLabels } from "./labels";

export function TenantPortal({
  credentials,
  data,
  loading,
  notice,
  onRefresh,
  onLogout,
  onNotice,
  contextSelector,
  companyPrivileges,
  supervision,
  supportConversation
}: {
  credentials: Credentials;
  data: TenantPortalData | null;
  loading: boolean;
  notice: Notice;
  onRefresh: () => void;
  onLogout: () => void;
  onNotice: (notice: Notice) => void;
  contextSelector: ReactNode;
  companyPrivileges: readonly string[];
  supervision: ReactNode;
  supportConversation: ReactNode;
}) {
  const { t } = useI18n();
  const l = useTenantLabels();
  const can = (privilege: string) => companyPrivileges.includes(privilege);
  const navigation = [
    ["tenant-company", t("myCompany")],
    ["tenant-supervision", l("supervision")],
    ...(can("READ_COMPANY") ? [["tenant-licenses", t("myLicenses")]] : []),
    ...(can("READ_BILLING") ? [["tenant-invoices", t("invoices")]] : []),
    ...(can("READ_MASTERS") ? [["tenant-masters", t("myMasters")]] : []),
    ...(can("SUPPORT") ? [["tenant-support", t("mySupport")]] : []),
  ];
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState("NORMAL");
  const [busy, setBusy] = useState(false);
  const [tenantSection, setTenantSection] = useState(() => {
    const id = window.location.hash.slice(1);
    return navigation.some(([value]) => value === id) ? id : "tenant-company";
  });

  function navigateTenantSection(id: string) {
    setTenantSection(id);
    const nextHash = `#${id}`;
    if (window.location.hash !== nextHash) window.history.pushState({ tenantSection: id }, "", nextHash);
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  useEffect(() => {
    if (!data) return;
    const syncSection = () => {
      const id = window.location.hash.slice(1);
      if (navigation.some(([value]) => value === id)) {
        setTenantSection(id);
        document.getElementById(id)?.scrollIntoView({ block: "start" });
      } else {
        setTenantSection("tenant-company");
      }
    };
    syncSection(); window.addEventListener("popstate", syncSection); window.addEventListener("hashchange", syncSection);
    return () => { window.removeEventListener("popstate", syncSection); window.removeEventListener("hashchange", syncSection); };
  }, [data, companyPrivileges]);

  async function submitTicket(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api.createTenantTicket(credentials, { title, description, priority });
      setTitle("");
      setDescription("");
      setPriority("NORMAL");
      onNotice({ type: "success", text: t("supportRequestCreated") });
      onRefresh();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell tenant-shell">
      <header className="app-header" aria-label={t("clientPortal")}>
        <div className="brand">
          <div>
            <strong>{data?.session.companyName ?? "ERP SaaS"}</strong>
            <span>{t("clientPortal")}</span>
          </div>
        </div>
        <nav className="nav-list top-nav-list tenant-top-nav" aria-label={t("mainNavigation")}>
          {navigation.map(([id, label]) => (
            <button className={tenantSection === id ? "nav-button active" : "nav-button"} type="button" key={id} aria-current={tenantSection === id ? "page" : undefined} onClick={() => navigateTenantSection(id)}>{label}</button>
          ))}
        </nav>
        <div className="app-actions" aria-label={t("clientPortal")}>
          <LanguageSelector variant="floating" />
          <button className="login-round-action" type="button" aria-label={t("logout")} onClick={onLogout}>
            <svg className="power-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M12 3v8" />
              <path d="M7.05 7.05a7 7 0 1 0 9.9 0" />
            </svg>
          </button>
        </div>
      </header>

      <main className="main-panel tenant-main">
        <header className="tenant-hero">
          <p className="eyebrow">{t("clientPortal")}</p>
          <h1>{data?.session.companyName ?? t("myCompany")}</h1>
          <p>{t("tenantWelcome")}</p>
          {contextSelector}
          <button className="secondary-button" type="button" onClick={onRefresh} disabled={loading}>
            {loading ? t("refreshing") : t("refresh")}
          </button>
        </header>

        {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}

        {!data ? (
          <EmptyState text={loading ? t("loadingSaas") : t("noLoadedData")} />
        ) : (
          <div className="view-grid tenant-view">
            <section id="tenant-company" className="metric-grid tenant-metrics">
              {can("READ_COMPANY") && <Metric label={t("licenses")} value={data.dashboard.licenses ?? "—"} />}
              <Metric label={t("stores")} value={data.dashboard.stores} />
              <Metric label={t("installations")} value={data.dashboard.installations} />
              {can("SUPPORT") && <Metric label={t("openTickets")} value={data.dashboard.openTickets ?? "—"} />}
              {can("READ_BILLING") && <><Metric label={t("billingStatus")} value={data.dashboard.billingStatus ?? "—"} />
              <Metric label={t("monthlyPrice")} value={data.dashboard.monthlyPrice ?? "-"} detail={data.dashboard.renewalDate ? `${t("renewalDate")}: ${formatDate(data.dashboard.renewalDate)}` : undefined} /></>}
            </section>

            {supervision}

            {can("READ_COMPANY") && <section id="tenant-licenses" className="content-section">
              <SectionHeader title={t("myLicenses")} subtitle={`${data.licenses.length} ${t("records")}`} />
              <LicenseTable licenses={data.licenses} compact />
            </section>}

            {can("READ_BILLING") && <section id="tenant-invoices" className="content-section">
              <SectionHeader title={t("invoices")} subtitle={`${data.invoices.length} ${t("records")}`} />
              <InvoiceTable invoices={data.invoices} />
            </section>}

            {can("READ_MASTERS") && <section id="tenant-masters" className="content-section">
              <SectionHeader title={t("myMasters")} subtitle={t("erpMastersSubtitle")} />
              {canWriteTenantMasters(data.session.roleName, companyPrivileges) && <>
                <TenantMasterCreate credentials={credentials} onRefresh={onRefresh} onNotice={onNotice} />
                <TenantMasterCsvTools credentials={credentials} onRefresh={onRefresh} onNotice={onNotice} />
              </>}
              <div className="tenant-master-grid">
                <div>
                  <h3>{t("customers")}</h3>
                  <MasterTable mode="customers" customers={data.customers} products={[]} suppliers={[]} warehouses={[]} />
                </div>
                <div>
                  <h3>{t("products")}</h3>
                  <MasterTable mode="products" customers={[]} products={data.products} suppliers={[]} warehouses={[]} />
                </div>
                <div>
                  <h3>{t("suppliers")}</h3>
                  <MasterTable mode="suppliers" customers={[]} products={[]} suppliers={data.suppliers} warehouses={[]} />
                </div>
                <div>
                  <h3>{t("warehouses")}</h3>
                  <MasterTable mode="warehouses" customers={[]} products={[]} suppliers={[]} warehouses={data.warehouses} />
                </div>
              </div>
            </section>}

            <section id="tenant-support" className="content-section two-column tenant-two-column">
              <div>
                <SectionHeader title={t("myStores")} subtitle={`${data.stores.length} ${t("records")}`} />
                <div className="tenant-store-list">
                  {data.stores.map((store) => (
                    <div className="tenant-store" key={store.storeId}>
                      <strong>{store.name}</strong>
                      <span>{store.code}</span>
                      <small>{formatDate(store.createdAt)}</small>
                    </div>
                  ))}
                  {data.stores.length === 0 && <EmptyState text={t("noLoadedData")} />}
                </div>
              </div>
              {can("SUPPORT") && <div>
                <SectionHeader title={t("mySupport")} subtitle={`${data.tickets.length} ${t("records")}`} />
                <form className="ticket-form tenant-ticket-form" onSubmit={submitTicket}>
                  <label>
                    {t("title")}
                    <input value={title} onChange={(event) => setTitle(event.target.value)} required />
                  </label>
                  <label>
                    {t("priority")}
                    <select value={priority} onChange={(event) => setPriority(event.target.value)}>
                      <option value="NORMAL">{t("normal")}</option>
                      <option value="ALTA">{t("high")}</option>
                      <option value="URGENTE">{t("urgent")}</option>
                    </select>
                  </label>
                  <label className="wide-field">
                    {t("description")}
                    <textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={4} />
                  </label>
                  <button className="primary-button" type="submit" disabled={busy}>
                    {busy ? t("saving") : t("createSupportRequest")}
                  </button>
                </form>
                {supportConversation}
              </div>}
            </section>
          </div>
        )}
      </main>
    </div>
  );
}

export function TenantMasterCsvTools({ credentials, onRefresh, onNotice }: { credentials: Credentials; onRefresh: () => void; onNotice: (notice: Notice) => void }) {
  const { t } = useI18n();
  const [resource, setResource] = useState<MasterMode>("customers");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState<"import" | "export" | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);

  async function exportCsv() {
    setBusy("export");
    try {
      const csv = await api.exportTenantMasterCsv(credentials, resource);
      const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=UTF-8" }));
      const link = document.createElement("a");
      link.href = url; link.download = `${resource}.csv`; link.click();
      URL.revokeObjectURL(url);
      onNotice(null);
    } catch (error) { onNotice({ type: "error", text: errorMessage(error) }); }
    finally { setBusy(null); }
  }

  async function importCsv(event: FormEvent) {
    event.preventDefault();
    if (!file || !file.name.toLowerCase().endsWith(".csv") || file.size === 0) {
      onNotice({ type: "error", text: t("csvInvalid") }); return;
    }
    setBusy("import");
    try {
      const csv = await file.text();
      if (!csv.trim()) { onNotice({ type: "error", text: t("csvInvalid") }); return; }
      if (!window.confirm(t("confirmCsvImport"))) return;
      const result = await api.importTenantMasterCsv(credentials, resource, csv);
      setFile(null); if (fileRef.current) fileRef.current.value = "";
      onNotice({ type: "success", text: t("csvImported").replace("{processed}", String(result.processed)).replace("{inserted}", String(result.inserted)).replace("{updated}", String(result.updated)) });
      onRefresh();
    } catch (error) { onNotice({ type: "error", text: errorMessage(error) }); }
    finally { setBusy(null); }
  }

  return <form className="compact-form-grid" onSubmit={importCsv} aria-label={t("csvTools")}>
    <label>{t("masters")}<select className="control-input" value={resource} onChange={(event) => { setResource(event.target.value as MasterMode); setFile(null); if (fileRef.current) fileRef.current.value = ""; }}>
      <option value="customers">{t("customers")}</option><option value="products">{t("products")}</option><option value="suppliers">{t("suppliers")}</option><option value="warehouses">{t("warehouses")}</option>
    </select></label>
    <label>{t("csvFile")}<input ref={fileRef} type="file" accept=".csv,text/csv" onChange={(event) => setFile(event.target.files?.[0] ?? null)} /></label>
    <button className="secondary-button" type="button" disabled={busy !== null} onClick={() => void exportCsv()}>{t("exportCsv")}</button>
    <button className="primary-button" type="submit" disabled={busy !== null || !file}>{t("importCsv")}</button>
  </form>;
}

export function TenantMasterCreate({ credentials, onRefresh, onNotice }: { credentials: Credentials; onRefresh: () => void; onNotice: (notice: Notice) => void }) {
  const { t } = useI18n();
  const [mode, setMode] = useState<MasterMode>("customers");
  const [party, setParty] = useState({ code: "", name: "", taxId: "", email: "", phone: "" });
  const [product, setProduct] = useState({ sku: "", name: "", category: "", price: "0.00", taxRate: "21.00", minStock: "0.00" });
  const [warehouse, setWarehouse] = useState({ code: "", name: "", address: "" });
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      if (mode === "customers") await api.createTenantErpCustomer(credentials, party);
      else if (mode === "products") await api.createTenantErpProduct(credentials, product);
      else if (mode === "suppliers") await api.createTenantErpSupplier(credentials, party);
      else await api.createTenantErpWarehouse(credentials, warehouse);
      setParty({ code: "", name: "", taxId: "", email: "", phone: "" });
      setProduct({ sku: "", name: "", category: "", price: "0.00", taxRate: "21.00", minStock: "0.00" });
      setWarehouse({ code: "", name: "", address: "" });
      onNotice({ type: "success", text: t("masterCreated") });
      onRefresh();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally { setBusy(false); }
  }

  return <form className="compact-form-grid masters-form tenant-master-create" onSubmit={submit}>
    <Segmented value={mode} options={[["customers", t("customers")], ["products", t("products")], ["suppliers", t("suppliers")], ["warehouses", t("warehouses")]]} onChange={(value) => setMode(value as MasterMode)} />
    {mode === "products" ? <>
      <Input label={t("sku")} value={product.sku} onChange={(sku) => setProduct({ ...product, sku })} required />
      <Input label={t("name")} value={product.name} onChange={(name) => setProduct({ ...product, name })} required />
      <Input label={t("category")} value={product.category} onChange={(category) => setProduct({ ...product, category })} />
      <Input label={t("price")} value={product.price} onChange={(price) => setProduct({ ...product, price })} required />
      <Input label={t("taxRate")} value={product.taxRate} onChange={(taxRate) => setProduct({ ...product, taxRate })} required />
      <Input label={t("minStock")} value={product.minStock} onChange={(minStock) => setProduct({ ...product, minStock })} required />
    </> : mode === "warehouses" ? <>
      <Input label={t("code")} value={warehouse.code} onChange={(code) => setWarehouse({ ...warehouse, code })} required />
      <Input label={t("name")} value={warehouse.name} onChange={(name) => setWarehouse({ ...warehouse, name })} required />
      <Input label={t("address")} value={warehouse.address} onChange={(address) => setWarehouse({ ...warehouse, address })} />
    </> : <>
      <Input label={t("code")} value={party.code} onChange={(code) => setParty({ ...party, code })} required />
      <Input label={t("name")} value={party.name} onChange={(name) => setParty({ ...party, name })} required />
      <Input label={t("taxId")} value={party.taxId} onChange={(taxId) => setParty({ ...party, taxId })} />
      <Input label={t("email")} type="email" value={party.email} onChange={(email) => setParty({ ...party, email })} />
      <Input label={t("phone")} value={party.phone} onChange={(phone) => setParty({ ...party, phone })} />
    </>}
    <button className="primary-button" type="submit" disabled={busy}>{busy ? t("saving") : t("tenantMasterCreate")}</button>
  </form>;
}

export function TenantTicketList({ tickets }: { tickets: SupportTicket[] }) {
  const { t } = useI18n();
  if (tickets.length === 0) return <EmptyState text={t("noTenantTickets")} />;
  return (
    <div className="ticket-list tenant-ticket-list">
      {tickets.map((ticket) => (
        <article className="ticket-card" key={ticket.id}>
          <div className="ticket-main">
            <div>
              <strong>{ticket.title}</strong>
              <span>{formatDate(ticket.createdAt)}</span>
            </div>
            <div className="ticket-badges">
              <StatusPill status={ticketStatusLabel(ticket.status, t)} tone={ticket.status === "RESUELTO" ? "ok" : "warning"} />
              <StatusPill status={ticketPriorityLabel(ticket.priority, t)} tone={ticket.priority === "URGENTE" ? "warning" : "muted"} />
            </div>
          </div>
          {ticket.description && <p>{ticket.description}</p>}
        </article>
      ))}
    </div>
  );
}

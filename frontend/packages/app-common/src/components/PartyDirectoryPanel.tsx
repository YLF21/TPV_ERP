import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { LocaleCode, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpSelect } from "./ErpSelect";
import { MemberLoyaltyPanel } from "./MemberLoyaltyPanel";

export type PartyDirectoryKind = "customers" | "members" | "suppliers";
type PartyStatusFilter = "all" | "active" | "inactive";

type FiscalAddress = { address?: string | null; postalCode?: string | null; city?: string | null; province?: string | null; country?: string | null };

export type CustomerView = {
  id: string; clientId: string; fiscalName: string; documentType: string; documentNumber: string;
  address?: FiscalAddress | null; phone?: string | null; email?: string | null; notes?: string | null;
  discount?: number | string | null; isMember: boolean; numMember?: string | null; memberSince?: string | null;
  memberUuid?: string | null;
  balance?: number | string | null; birthday?: string | null; gender?: string | null; commercialConsent?: boolean;
  preferredCommercialChannelId?: string | null; active: boolean; fiscalDataComplete?: boolean;
};

export type SupplierView = {
  id: string; supplierId: string; legalName: string; tradeName?: string | null; documentType: string;
  documentNumber: string; address?: FiscalAddress | null; phone?: string | null; email?: string | null;
  notes?: string | null; active: boolean;
};

type CommercialChannel = { id: string; code: string; name: string; active: boolean };

export type PartyForm = {
  name: string; tradeName: string; documentType: string; documentNumber: string; phone: string; email: string;
  address: string; postalCode: string; city: string; province: string; country: string; notes: string;
  discount: string; numMember: string; birthday: string; gender: string; commercialConsent: boolean;
  preferredCommercialChannelId: string;
};

export const emptyPartyForm: PartyForm = {
  name: "", tradeName: "", documentType: "NIF", documentNumber: "", phone: "", email: "", address: "",
  postalCode: "", city: "", province: "", country: "ES", notes: "", discount: "0", numMember: "",
  birthday: "", gender: "", commercialConsent: false, preferredCommercialChannelId: ""
};

export function partyFormFromView(entry: CustomerView | SupplierView, supplier: boolean): PartyForm {
  const customer = entry as CustomerView;
  const provider = entry as SupplierView;
  return {
    ...emptyPartyForm,
    name: supplier ? provider.legalName : customer.fiscalName,
    tradeName: supplier ? provider.tradeName ?? "" : "",
    documentType: entry.documentType,
    documentNumber: entry.documentNumber,
    phone: entry.phone ?? "", email: entry.email ?? "", address: entry.address?.address ?? "",
    postalCode: entry.address?.postalCode ?? "", city: entry.address?.city ?? "", province: entry.address?.province ?? "",
    country: entry.address?.country ?? "ES", notes: entry.notes ?? "",
    discount: supplier ? "0" : String(customer.discount ?? 0), numMember: supplier ? "" : customer.numMember ?? "",
    birthday: supplier ? "" : customer.birthday ?? "", gender: supplier ? "" : customer.gender ?? "",
    commercialConsent: supplier ? false : Boolean(customer.commercialConsent),
    preferredCommercialChannelId: supplier ? "" : customer.preferredCommercialChannelId ?? ""
  };
}

export function buildPartyRequest(form: PartyForm, supplier: boolean, member: boolean) {
  const address = {
    address: form.address.trim() || null, postalCode: form.postalCode.trim() || null, city: form.city.trim() || null,
    province: form.province.trim() || null, country: form.country.trim().toUpperCase() || null
  };
  if (supplier) return {
    legalName: form.name.trim(), tradeName: form.tradeName.trim() || null, documentType: form.documentType,
    documentNumber: form.documentNumber.trim(), address, phone: form.phone.trim() || null, email: form.email.trim() || null,
    notes: form.notes.trim() || null
  };
  return {
    fiscalName: form.name.trim(), documentType: form.documentType, documentNumber: form.documentNumber.trim(), address,
    phone: form.phone.trim() || null, email: form.email.trim() || null, notes: form.notes.trim() || null,
    discount: Number(form.discount) || 0, isMember: member, numMember: member ? form.numMember.trim() || null : null,
    birthday: form.birthday || null, gender: form.gender || null, commercialConsent: form.commercialConsent,
    preferredCommercialChannelId: form.commercialConsent ? form.preferredCommercialChannelId || null : null
  };
}

export function validatePartyForm(form: PartyForm, supplier: boolean): string[] {
  const errors: string[] = [];
  if (!form.name.trim()) errors.push("name");
  if (!form.documentNumber.trim()) errors.push("documentNumber");
  if (form.country.trim().length !== 2) errors.push("country");
  if (!supplier && (Number(form.discount) < 0 || Number(form.discount) > 100)) errors.push("discount");
  if (!supplier && form.commercialConsent && !form.preferredCommercialChannelId) errors.push("preferredCommercialChannelId");
  return errors;
}

export function PartyDirectoryPanel({ kind, locale, session }: { kind: PartyDirectoryKind; locale: LocaleCode; session: UserSession }) {
  const t = createTranslator(locale);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierView[]>([]);
  const [channels, setChannels] = useState<CommercialChannel[]>([]);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<PartyStatusFilter>("all");
  const [documentFilter, setDocumentFilter] = useState("all");
  const [locationFilter, setLocationFilter] = useState("");
  const [consentFilter, setConsentFilter] = useState("all");
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form, setForm] = useState<PartyForm>(emptyPartyForm);
  const [saving, setSaving] = useState(false);

  const endpoint = kind === "suppliers" ? "/suppliers" : "/customers";
  const isSupplier = kind === "suppliers";
  const isMember = kind === "members";
  const title = t(`party.${kind}.title`);
  const canWrite = session.permissions.includes("ADMIN") || session.permissions.includes(isSupplier ? "SUPPLIERS_WRITE" : "CUSTOMERS_WRITE");
  const selected = (isSupplier ? suppliers : customers).find((entry) => entry.id === selectedId) ?? null;

  async function load() {
    setLoading(true); setStatus("");
    try {
      if (isSupplier) setSuppliers(await apiRequest<SupplierView[]>(endpoint, { token: session.accessToken }));
      else {
        const [customerRows, channelRows] = await Promise.all([
          apiRequest<CustomerView[]>(endpoint, { token: session.accessToken }),
          apiRequest<CommercialChannel[]>("/commercial-contact-channels", { token: session.accessToken })
        ]);
        setCustomers(customerRows); setChannels(channelRows.filter((channel) => channel.active));
      }
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.loadError")); }
    finally { setLoading(false); }
  }

  useEffect(() => { void load(); }, [kind, session.accessToken]);

  const rows = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase(locale);
    const location = locationFilter.trim().toLocaleLowerCase(locale);
    const source: Array<CustomerView | SupplierView> = isSupplier ? suppliers : customers.filter((customer) => !isMember || customer.isMember);
    return source.filter((entry) => {
      const searchable = isSupplier
        ? [(entry as SupplierView).supplierId, (entry as SupplierView).legalName, (entry as SupplierView).tradeName]
        : [(entry as CustomerView).clientId, (entry as CustomerView).fiscalName];
      searchable.push(entry.documentNumber, entry.phone, entry.email);
      const matchesQuery = !normalized || searchable.some((value) => value?.toLocaleLowerCase(locale).includes(normalized));
      const matchesStatus = statusFilter === "all" || entry.active === (statusFilter === "active");
      const matchesDocument = documentFilter === "all" || entry.documentType === documentFilter;
      const matchesLocation = !location || [entry.address?.city, entry.address?.province].some((value) => value?.toLocaleLowerCase(locale).includes(location));
      const matchesConsent = isSupplier || consentFilter === "all" || Boolean((entry as CustomerView).commercialConsent) === (consentFilter === "with");
      return matchesQuery && matchesStatus && matchesDocument && matchesLocation && matchesConsent;
    });
  }, [customers, suppliers, query, statusFilter, documentFilter, locationFilter, consentFilter, kind, locale]);

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) { setForm((current) => ({ ...current, [field]: value })); }
  function openNew() { setSelectedId(null); setForm(emptyPartyForm); setStatus(""); setDialogOpen(true); }
  function openEntry(entry: CustomerView | SupplierView) { setSelectedId(entry.id); setForm(partyFormFromView(entry, isSupplier)); setStatus(""); setDialogOpen(true); }
  function closeDialog() { if (!saving) { setDialogOpen(false); setSelectedId(null); } }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (validatePartyForm(form, isSupplier).length) { setStatus(t("party.form.invalid")); return; }
    setSaving(true); setStatus("");
    try {
      await apiRequest(selectedId ? `${endpoint}/${selectedId}` : endpoint, {
        method: selectedId ? "PUT" : "POST", token: session.accessToken,
        body: buildPartyRequest(form, isSupplier, isMember || (!isSupplier && Boolean((selected as CustomerView | null)?.isMember)))
      });
      setDialogOpen(false); await load();
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.saveError")); }
    finally { setSaving(false); }
  }

  async function toggleActive() {
    if (!selected || !canWrite || saving) return;
    const action = selected.active ? "deactivate" : "activate";
    if (!window.confirm(t(`party.confirm.${action}`))) return;
    setSaving(true); setStatus("");
    try {
      await apiRequest(`${endpoint}/${selected.id}/${action}`, { method: "PATCH", token: session.accessToken });
      setDialogOpen(false); await load();
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.saveError")); }
    finally { setSaving(false); }
  }

  return <>
    <header className="work-panel-heading stock-panel-heading party-directory-heading">
      <div><h2>{title}</h2><span>{t(`party.${kind}.subtitle`)}</span></div>
      {canWrite && <button type="button" className="stock-add-product-button" onClick={openNew}>{t(`party.${kind}.new`)}</button>}
    </header>
    <div className="party-directory-toolbar">
      <input aria-label={t("party.search")} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("party.search")} />
      <ErpSelect value={statusFilter} onChange={(value) => setStatusFilter(value as PartyStatusFilter)} options={["all", "active", "inactive"].map((value) => ({ value, label: t(`party.filter.status.${value}`) }))} />
      <ErpSelect value={documentFilter} onChange={setDocumentFilter} options={["all", "NIF", "CIF", "NIE", "PASAPORTE", "OTRO"].map((value) => ({ value, label: value === "all" ? t("party.filter.document.all") : value }))} />
      <input aria-label={t("party.filter.location")} value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)} placeholder={t("party.filter.location")} />
      {!isSupplier && !isMember && <ErpSelect value={consentFilter} onChange={setConsentFilter} options={["all", "with", "without"].map((value) => ({ value, label: t(`party.filter.consent.${value}`) }))} />}
      <span>{t("party.results").replace("{count}", String(rows.length))}</span>
    </div>
    <div className="party-directory-table" role="table" aria-label={title}>
      <div className="party-directory-row header" role="row"><span>{t("party.column.code")}</span><span>{t("party.column.name")}</span><span>{t("party.column.document")}</span><span>{t("party.column.phone")}</span><span>{t("party.column.email")}</span><span>{isMember ? t("party.column.balance") : t("party.column.location")}</span><span>{t("party.column.status")}</span></div>
      {loading && <div className="stock-empty-state">{t("common.loading")}</div>}
      {!loading && rows.map((entry) => {
        const customer = entry as CustomerView; const supplier = entry as SupplierView;
        return <button type="button" className="party-directory-row party-directory-selectable-row" role="row" key={entry.id} onClick={() => openEntry(entry)}>
          <strong>{isSupplier ? supplier.supplierId : isMember ? customer.numMember || customer.clientId : customer.clientId}</strong>
          <span>{isSupplier ? supplier.legalName : customer.fiscalName}{isSupplier && supplier.tradeName ? <small>{supplier.tradeName}</small> : null}</span>
          <span>{entry.documentType} · {entry.documentNumber}</span><span>{entry.phone || "-"}</span><span>{entry.email || "-"}</span>
          <span>{isMember ? Number(customer.balance || 0).toLocaleString(locale, { style: "currency", currency: "EUR" }) : [entry.address?.city, entry.address?.province].filter(Boolean).join(", ") || "-"}</span>
          <span className={entry.active ? "party-status active" : "party-status"}>{t(entry.active ? "party.active" : "party.inactive")}</span>
        </button>;
      })}
      {!loading && rows.length === 0 && <div className="stock-empty-state">{t("party.empty")}</div>}
    </div>
    {status && !dialogOpen && <p className="product-create-status" role="status">{status}</p>}

    {dialogOpen && <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="party-form-title">
      <section className="filter-dialog product-create-dialog party-create-dialog">
        <header className="filter-header"><div><h2 id="party-form-title">{selectedId ? t(`party.${kind}.detail`) : t(`party.${kind}.new`)}</h2><span>{selected ? `${isSupplier ? (selected as SupplierView).supplierId : (selected as CustomerView).clientId} · ${selected.active ? t("party.active") : t("party.inactive")}` : t("party.form.subtitle")}</span></div><button type="button" onClick={closeDialog}>{t("common.close")}</button></header>
        <form className="product-create-form party-create-form" onSubmit={submit}>
          <fieldset disabled={!canWrite || saving}>
            <div className="product-create-row product-create-row-two"><label><span>{t(isSupplier ? "party.field.legalName" : "party.field.fiscalName")}</span><input required autoFocus value={form.name} onChange={(e) => update("name", e.target.value)} /></label>{isSupplier ? <label><span>{t("party.field.tradeName")}</span><input value={form.tradeName} onChange={(e) => update("tradeName", e.target.value)} /></label> : <label><span>{t("party.field.discount")}</span><input type="number" min="0" max="100" step="0.01" value={form.discount} onChange={(e) => update("discount", e.target.value)} /></label>}</div>
            <div className="product-create-row product-create-row-two"><label><span>{t("party.field.documentType")}</span><ErpSelect value={form.documentType} onChange={(value) => update("documentType", value)} options={["NIF", "CIF", "NIE", "PASAPORTE", "OTRO"].map((value) => ({ value, label: value }))} /></label><label><span>{t("party.field.documentNumber")}</span><input required value={form.documentNumber} onChange={(e) => update("documentNumber", e.target.value)} /></label></div>
            <div className="product-create-row product-create-row-two"><label><span>{t("party.field.phone")}</span><input value={form.phone} onChange={(e) => update("phone", e.target.value)} /></label><label><span>{t("party.field.email")}</span><input type="email" value={form.email} onChange={(e) => update("email", e.target.value)} /></label></div>
            <label><span>{t("party.field.address")}</span><input value={form.address} onChange={(e) => update("address", e.target.value)} /></label>
            <div className="product-create-row product-create-row-three"><label><span>{t("party.field.postalCode")}</span><input value={form.postalCode} onChange={(e) => update("postalCode", e.target.value)} /></label><label><span>{t("party.field.city")}</span><input value={form.city} onChange={(e) => update("city", e.target.value)} /></label><label><span>{t("party.field.province")}</span><input value={form.province} onChange={(e) => update("province", e.target.value)} /></label></div>
            <div className="product-create-row product-create-row-two"><label><span>{t("party.field.country")}</span><input required maxLength={2} value={form.country} onChange={(e) => update("country", e.target.value.toUpperCase())} /></label><label><span>{t("party.field.notes")}</span><input value={form.notes} onChange={(e) => update("notes", e.target.value)} /></label></div>
            {!isSupplier && !isMember && <><div className="product-create-row product-create-row-two"><label><span>{t("party.field.birthday")}</span><input type="date" value={form.birthday} onChange={(e) => update("birthday", e.target.value)} /></label><label><span>{t("party.field.gender")}</span><ErpSelect value={form.gender} onChange={(value) => update("gender", value)} options={["", "MASCULINO", "FEMENINO", "OTRO"].map((value) => ({ value, label: value ? t(`party.gender.${value.toLowerCase()}`) : t("party.gender.unspecified") }))} /></label></div><label className="party-commercial-consent"><input type="checkbox" checked={form.commercialConsent} onChange={(e) => update("commercialConsent", e.target.checked)} /><span>{t("party.field.commercialConsent")}</span></label>{form.commercialConsent && <label><span>{t("party.field.preferredCommercialChannel")}</span><ErpSelect value={form.preferredCommercialChannelId} onChange={(value) => update("preferredCommercialChannelId", value)} options={[{ value: "", label: t("party.channel.select") }, ...channels.map((channel) => ({ value: channel.id, label: channel.name }))]} /></label>}</>}
          </fieldset>
          {status && <p className="product-create-status" role="status">{status}</p>}
          {isMember && selected && (selected as CustomerView).memberUuid && (
            <MemberLoyaltyPanel memberId={(selected as CustomerView).memberUuid!} session={session} t={t} />
          )}
          <footer className="filter-actions">{selected && canWrite && <button type="button" className={selected.active ? "party-deactivate-button" : "party-activate-button"} onClick={() => void toggleActive()} disabled={saving}>{t(selected.active ? "party.action.deactivate" : "party.action.activate")}</button>}<button type="button" onClick={closeDialog}>{t("common.cancel")}</button>{canWrite && <button type="submit" disabled={saving}>{saving ? t("party.saving") : t("common.save")}</button>}</footer>
        </form>
      </section>
    </div>}
  </>;
}

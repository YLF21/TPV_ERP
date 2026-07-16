import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpSelect } from "./ErpSelect";
import { MemberLoyaltyPanel } from "./MemberLoyaltyPanel";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { tableLayoutGridTemplate, visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

export type PartyDirectoryKind = "customers" | "members" | "suppliers";
export type PartyStatusFilter = "all" | "active" | "inactive";
export type PartyDirectoryColumnKey = "code" | "name" | "document" | "phone" | "email" | "location" | "balance" | "status";

export type PartyDirectoryPanelProps = {
  app?: AppKind;
  kind: PartyDirectoryKind;
  locale: LocaleCode;
  session: UserSession;
};

const sharedPartyColumnDefinitions = [
  { key: "code", defaultWidth: 105 },
  { key: "name", defaultWidth: 250 },
  { key: "document", defaultWidth: 160 },
  { key: "phone", defaultWidth: 130 },
  { key: "email", defaultWidth: 190 }
] as const satisfies readonly TableColumnDefinition<PartyDirectoryColumnKey>[];

export function partyDirectoryColumnDefinitions(
  kind: PartyDirectoryKind
): readonly TableColumnDefinition<PartyDirectoryColumnKey>[] {
  return [
    ...sharedPartyColumnDefinitions,
    { key: kind === "members" ? "balance" : "location", defaultWidth: 150 },
    { key: "status", defaultWidth: 88 }
  ];
}

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

export type MemberDirectoryView = {
  id: string; customerId: string; memberId: string; numMember?: string | null; memberSince: string;
  balance: number | string; points: number; categoryId?: string | null; categoryName?: string | null;
  active: boolean; customerActive: boolean; clientId: string; fiscalName: string; documentType: string;
  documentNumber: string; phone?: string | null; email?: string | null;
};

export type PartyDirectoryEntry = CustomerView | SupplierView | MemberDirectoryView;

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

export function buildPartyRequest(form: PartyForm, supplier: boolean, preserveMember = false) {
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
    discount: Number(form.discount) || 0, isMember: preserveMember,
    numMember: preserveMember ? form.numMember.trim() || null : null,
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

function normalizedText(value: string | null | undefined, locale: LocaleCode): string {
  return value?.toLocaleLowerCase(locale) ?? "";
}

export function partyDirectorySearchValues(entry: PartyDirectoryEntry, kind: PartyDirectoryKind): Array<string | null | undefined> {
  if (kind === "members") {
    const member = entry as MemberDirectoryView;
    return [member.memberId, member.numMember, member.clientId, member.fiscalName, member.documentNumber,
      member.phone, member.email, member.categoryName];
  }
  if (kind === "suppliers") {
    const supplier = entry as SupplierView;
    return [supplier.supplierId, supplier.legalName, supplier.tradeName, supplier.documentNumber,
      supplier.phone, supplier.email, supplier.address?.city, supplier.address?.province];
  }
  const customer = entry as CustomerView;
  return [customer.clientId, customer.fiscalName, customer.documentNumber, customer.phone, customer.email,
    customer.address?.city, customer.address?.province];
}

export function filterPartyDirectoryEntries(
  entries: PartyDirectoryEntry[],
  kind: PartyDirectoryKind,
  query: string,
  statusFilter: PartyStatusFilter,
  locale: LocaleCode
): PartyDirectoryEntry[] {
  const normalized = normalizedText(query.trim(), locale);
  return entries.filter((entry) => {
    const matchesQuery = !normalized || partyDirectorySearchValues(entry, kind)
      .some((value) => normalizedText(value, locale).includes(normalized));
    const matchesStatus = statusFilter === "all" || entry.active === (statusFilter === "active");
    return matchesQuery && matchesStatus;
  });
}

export function availableMemberCustomers(customers: CustomerView[], query: string, locale: LocaleCode): CustomerView[] {
  const normalized = normalizedText(query.trim(), locale);
  return customers.filter((customer) => customer.active && !customer.isMember)
    .filter((customer) => !normalized || [customer.clientId, customer.fiscalName, customer.documentNumber,
      customer.phone, customer.email].some((value) => normalizedText(value, locale).includes(normalized)));
}

export function memberActivationPath(customerId: string, action: "activate" | "deactivate" = "activate"): string {
  return `/customers/${customerId}/member/${action}`;
}

export function PartyDirectoryPanel({ app = "venta", kind, locale, session }: PartyDirectoryPanelProps) {
  const t = createTranslator(locale);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [members, setMembers] = useState<MemberDirectoryView[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierView[]>([]);
  const [channels, setChannels] = useState<CommercialChannel[]>([]);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<PartyStatusFilter>("all");
  const [memberCandidateQuery, setMemberCandidateQuery] = useState("");
  const [memberCandidateId, setMemberCandidateId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form, setForm] = useState<PartyForm>(emptyPartyForm);
  const [saving, setSaving] = useState(false);

  const endpoint = kind === "suppliers" ? "/suppliers" : kind === "members" ? "/members" : "/customers";
  const isSupplier = kind === "suppliers";
  const isMember = kind === "members";
  const title = t(`party.${kind}.title`);
  const canWrite = session.permissions.includes("ADMIN") || session.permissions.includes(isSupplier ? "SUPPLIERS_WRITE" : "CUSTOMERS_WRITE");
  const entries: PartyDirectoryEntry[] = isSupplier ? suppliers : isMember ? members : customers;
  const selected = entries.find((entry) => entry.id === selectedId) ?? null;
  const memberCandidate = customers.find((customer) => customer.id === memberCandidateId) ?? null;
  const columnDefinitions = useMemo(() => partyDirectoryColumnDefinitions(kind), [kind]);
  const tableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: session.accessToken,
    tableKey: `party.${kind}`,
    definitions: columnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const gridStyle = { gridTemplateColumns: tableLayoutGridTemplate(tableLayout.layout) };

  function columnLabel(column: PartyDirectoryColumnKey): string {
    if (column === "code") return t("party.column.code");
    if (column === "name") return t("party.column.name");
    if (column === "document") return t("party.column.document");
    if (column === "phone") return t("party.column.phone");
    if (column === "email") return t("party.column.email");
    if (column === "balance") return t("party.column.balance");
    if (column === "location") return t("party.column.location");
    return t("party.column.status");
  }

  function renderCell(column: PartyDirectoryColumnKey, entry: PartyDirectoryEntry) {
    const customer = entry as CustomerView;
    const supplier = entry as SupplierView;
    const member = entry as MemberDirectoryView;
    if (column === "code") {
      return <strong data-column-key={column} key={column}>{isSupplier ? supplier.supplierId : isMember ? member.numMember || member.memberId : customer.clientId}</strong>;
    }
    if (column === "name") {
      return <span data-column-key={column} key={column}>{isSupplier ? supplier.legalName : isMember ? member.fiscalName : customer.fiscalName}{isSupplier && supplier.tradeName ? <small>{supplier.tradeName}</small> : null}</span>;
    }
    if (column === "document") return <span data-column-key={column} key={column}>{entry.documentType} · {entry.documentNumber}</span>;
    if (column === "phone") return <span data-column-key={column} key={column}>{entry.phone || "-"}</span>;
    if (column === "email") return <span data-column-key={column} key={column}>{entry.email || "-"}</span>;
    if (column === "balance") {
      return <span data-column-key={column} key={column}>{Number(member.balance || 0).toLocaleString(locale, { style: "currency", currency: "EUR" })}</span>;
    }
    if (column === "location") {
      const locatedEntry = entry as CustomerView | SupplierView;
      return <span data-column-key={column} key={column}>{[locatedEntry.address?.city, locatedEntry.address?.province].filter(Boolean).join(", ") || "-"}</span>;
    }
    return <span data-column-key={column} key={column} className={entry.active ? "party-status active" : "party-status"}>
      {t(entry.active ? "party.active" : "party.inactive")}
      {isMember && !member.customerActive ? <small>{t("party.members.customerInactive")}</small> : null}
    </span>;
  }

  async function load() {
    setLoading(true); setStatus("");
    try {
      if (isSupplier) setSuppliers(await apiRequest<SupplierView[]>(endpoint, { token: session.accessToken }));
      else if (isMember) {
        const [memberRows, customerRows] = await Promise.all([
          apiRequest<MemberDirectoryView[]>(endpoint, { token: session.accessToken }),
          apiRequest<CustomerView[]>("/customers", { token: session.accessToken })
        ]);
        setMembers(memberRows);
        setCustomers(customerRows);
      }
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

  const rows = useMemo(
    () => filterPartyDirectoryEntries(entries, kind, query, statusFilter, locale),
    [customers, members, suppliers, query, statusFilter, kind, locale]
  );
  const memberCandidates = useMemo(
    () => availableMemberCustomers(customers, memberCandidateQuery, locale),
    [customers, memberCandidateQuery, locale]
  );

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) { setForm((current) => ({ ...current, [field]: value })); }
  function openNew() {
    setSelectedId(null); setForm(emptyPartyForm); setStatus("");
    setMemberCandidateQuery(""); setMemberCandidateId(null); setDialogOpen(true);
  }
  function openEntry(entry: PartyDirectoryEntry) {
    setSelectedId(entry.id);
    if (!isMember) setForm(partyFormFromView(entry as CustomerView | SupplierView, isSupplier));
    setStatus(""); setDialogOpen(true);
  }
  function closeDialog() {
    if (!saving) {
      setDialogOpen(false); setSelectedId(null); setMemberCandidateId(null); setMemberCandidateQuery("");
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (isMember) return;
    if (validatePartyForm(form, isSupplier).length) { setStatus(t("party.form.invalid")); return; }
    setSaving(true); setStatus("");
    try {
      await apiRequest(selectedId ? `${endpoint}/${selectedId}` : endpoint, {
        method: selectedId ? "PUT" : "POST", token: session.accessToken,
        body: buildPartyRequest(form, isSupplier, !isSupplier && Boolean((selected as CustomerView | null)?.isMember))
      });
      setDialogOpen(false); await load();
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.saveError")); }
    finally { setSaving(false); }
  }

  async function toggleActive() {
    if (!selected || !canWrite || saving) return;
    const action = selected.active ? "deactivate" : "activate";
    if (isMember && !(selected as MemberDirectoryView).customerActive && action === "activate") {
      setStatus(t("party.members.customerInactiveHint"));
      return;
    }
    if (!window.confirm(t(`party.confirm.${action}`))) return;
    setSaving(true); setStatus("");
    try {
      const path = isMember
        ? memberActivationPath((selected as MemberDirectoryView).customerId, action)
        : `${endpoint}/${selected.id}/${action}`;
      await apiRequest(path, { method: isMember ? "POST" : "PATCH", token: session.accessToken });
      setDialogOpen(false); await load();
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.saveError")); }
    finally { setSaving(false); }
  }

  async function activateSelectedCustomer() {
    if (!memberCandidate || !canWrite || saving) return;
    setSaving(true); setStatus("");
    try {
      await apiRequest(memberActivationPath(memberCandidate.id), {
        method: "POST", token: session.accessToken
      });
      setDialogOpen(false); setMemberCandidateId(null); await load();
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.saveError")); }
    finally { setSaving(false); }
  }

  const selectedMember = isMember ? selected as MemberDirectoryView | null : null;
  const selectedCode = selected
    ? isSupplier ? (selected as SupplierView).supplierId
      : isMember ? selectedMember?.memberId
        : (selected as CustomerView).clientId
    : null;
  const memberDialogContent = selectedMember ? <>
    <div className="party-member-directory-detail">
      <section className="party-member-customer-summary" aria-label={t("party.members.customerIdentity")}>
        <strong>{selectedMember.fiscalName}</strong>
        <span>{selectedMember.clientId} · {selectedMember.documentType} {selectedMember.documentNumber}</span>
        <span>{selectedMember.phone || "-"} · {selectedMember.email || "-"}</span>
        {!selectedMember.customerActive && <span className="party-member-customer-warning">{t("party.members.customerInactiveHint")}</span>}
      </section>
      <MemberLoyaltyPanel app={app} memberId={selectedMember.id} session={session} t={t} />
    </div>
    {status && <p className="product-create-status" role="status">{status}</p>}
    <footer className="filter-actions">
      {canWrite && (selectedMember.active || selectedMember.customerActive) && <button type="button" className={selectedMember.active ? "party-deactivate-button" : "party-activate-button"} onClick={() => void toggleActive()} disabled={saving}>{t(selectedMember.active ? "party.action.deactivate" : "party.action.activate")}</button>}
      <button type="button" onClick={closeDialog}>{t("common.cancel")}</button>
    </footer>
  </> : <>
    <div className="party-member-customer-picker">
      <input autoFocus aria-label={t("party.members.customerSearch")} type="search" value={memberCandidateQuery} onChange={(event) => { setMemberCandidateQuery(event.target.value); setMemberCandidateId(null); }} placeholder={t("party.members.customerSearch")} />
      <div className="party-member-candidate-list" role="listbox" aria-label={t("party.members.selectCustomerTitle")}>
        {memberCandidates.map((customer) => <button
          type="button"
          role="option"
          aria-selected={memberCandidateId === customer.id}
          className={memberCandidateId === customer.id ? "is-selected" : ""}
          key={customer.id}
          onClick={() => setMemberCandidateId(customer.id)}
        >
          <strong>{customer.clientId} · {customer.fiscalName}</strong>
          <span>{customer.documentType} {customer.documentNumber} · {customer.phone || customer.email || "-"}</span>
          <small>{t(customer.memberUuid ? "party.members.reactivate" : "party.members.convert")}</small>
        </button>)}
        {memberCandidates.length === 0 && <div className="stock-empty-state">{t("party.members.noCandidates")}</div>}
      </div>
    </div>
    {status && <p className="product-create-status" role="status">{status}</p>}
    <footer className="filter-actions">
      <button type="button" onClick={closeDialog}>{t("common.cancel")}</button>
      {canWrite && <button type="button" onClick={() => void activateSelectedCustomer()} disabled={!memberCandidate || saving}>{saving ? t("party.saving") : t(memberCandidate?.memberUuid ? "party.members.reactivate" : "party.members.convert")}</button>}
    </footer>
  </>;

  return <>
    <header className="work-panel-heading stock-panel-heading party-directory-heading">
      <div><h2>{title}</h2><span>{t(`party.${kind}.subtitle`)}</span></div>
      {canWrite && <button type="button" className="stock-add-product-button" onClick={openNew}>{t(`party.${kind}.new`)}</button>}
    </header>
    <div className="party-directory-toolbar">
      <input aria-label={t("party.search")} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("party.search")} />
      <ErpSelect value={statusFilter} onChange={(value) => setStatusFilter(value as PartyStatusFilter)} options={["all", "active", "inactive"].map((value) => ({ value, label: t(`party.filter.status.${value}`) }))} />
      <span>{t("party.results").replace("{count}", String(rows.length))}</span>
    </div>
    <div className="party-directory-table" role="table" aria-label={title}>
      <div className="party-directory-row header" role="row" style={gridStyle}>
        {visibleColumns.map((column) => (
          <TableLayoutHeaderCell
            as="span"
            column={column}
            key={column.key}
            resizeLabel={`${t("stock.columns.resize")} ${columnLabel(column.key)}`}
            onReorder={tableLayout.reorderColumns}
            onMove={tableLayout.moveColumn}
            onResize={tableLayout.resizeColumn}
          >
            {columnLabel(column.key)}
          </TableLayoutHeaderCell>
        ))}
      </div>
      {loading && <div className="stock-empty-state">{t("common.loading")}</div>}
      {!loading && rows.map((entry) => {
        return <button type="button" className="party-directory-row party-directory-selectable-row" role="row" style={gridStyle} key={entry.id} onClick={() => openEntry(entry)}>
          {visibleColumns.map((column) => renderCell(column.key, entry))}
        </button>;
      })}
      {!loading && rows.length === 0 && <div className="stock-empty-state">{t("party.empty")}</div>}
    </div>
    {status && !dialogOpen && <p className="product-create-status" role="status">{status}</p>}

    {dialogOpen && <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="party-form-title">
      <section className="filter-dialog product-create-dialog party-create-dialog">
        <header className="filter-header"><div><h2 id="party-form-title">{selectedId ? t(`party.${kind}.detail`) : t(`party.${kind}.new`)}</h2><span>{selected ? `${selectedCode} · ${selected.active ? t("party.active") : t("party.inactive")}` : isMember ? t("party.members.selectCustomerSubtitle") : t("party.form.subtitle")}</span></div><button type="button" onClick={closeDialog}>{t("common.close")}</button></header>
        {isMember ? memberDialogContent : <form className="product-create-form party-create-form" onSubmit={submit}>
          <fieldset disabled={!canWrite || saving}>
            <div className="product-create-row product-create-row-two"><label><span>{t(isSupplier ? "party.field.legalName" : "party.field.fiscalName")}</span><input required autoFocus value={form.name} onChange={(e) => update("name", e.target.value)} /></label>{isSupplier ? <label><span>{t("party.field.tradeName")}</span><input value={form.tradeName} onChange={(e) => update("tradeName", e.target.value)} /></label> : <label><span>{t("party.field.discount")}</span><input type="number" min="0" max="100" step="0.01" value={form.discount} onChange={(e) => update("discount", e.target.value)} /></label>}</div>
            <div className="product-create-row product-create-row-two"><label><span>{t("party.field.documentType")}</span><ErpSelect value={form.documentType} onChange={(value) => update("documentType", value)} options={["NIF", "CIF", "NIE", "PASAPORTE", "OTRO"].map((value) => ({ value, label: value }))} /></label><label><span>{t("party.field.documentNumber")}</span><input required value={form.documentNumber} onChange={(e) => update("documentNumber", e.target.value)} /></label></div>
            <div className="product-create-row product-create-row-two"><label><span>{t("party.field.phone")}</span><input value={form.phone} onChange={(e) => update("phone", e.target.value)} /></label><label><span>{t("party.field.email")}</span><input type="email" value={form.email} onChange={(e) => update("email", e.target.value)} /></label></div>
            <label><span>{t("party.field.address")}</span><input value={form.address} onChange={(e) => update("address", e.target.value)} /></label>
            <div className="product-create-row product-create-row-three"><label><span>{t("party.field.postalCode")}</span><input value={form.postalCode} onChange={(e) => update("postalCode", e.target.value)} /></label><label><span>{t("party.field.city")}</span><input value={form.city} onChange={(e) => update("city", e.target.value)} /></label><label><span>{t("party.field.province")}</span><input value={form.province} onChange={(e) => update("province", e.target.value)} /></label></div>
            <div className="product-create-row product-create-row-two"><label><span>{t("party.field.country")}</span><input required maxLength={2} value={form.country} onChange={(e) => update("country", e.target.value.toUpperCase())} /></label><label><span>{t("party.field.notes")}</span><input value={form.notes} onChange={(e) => update("notes", e.target.value)} /></label></div>
            {!isSupplier && <><div className="product-create-row product-create-row-two"><label><span>{t("party.field.birthday")}</span><input type="date" value={form.birthday} onChange={(e) => update("birthday", e.target.value)} /></label><label><span>{t("party.field.gender")}</span><ErpSelect value={form.gender} onChange={(value) => update("gender", value)} options={["", "MASCULINO", "FEMENINO", "OTRO"].map((value) => ({ value, label: value ? t(`party.gender.${value.toLowerCase()}`) : t("party.gender.unspecified") }))} /></label></div><label className="party-commercial-consent"><input type="checkbox" checked={form.commercialConsent} onChange={(e) => update("commercialConsent", e.target.checked)} /><span>{t("party.field.commercialConsent")}</span></label>{form.commercialConsent && <label><span>{t("party.field.preferredCommercialChannel")}</span><ErpSelect value={form.preferredCommercialChannelId} onChange={(value) => update("preferredCommercialChannelId", value)} options={[{ value: "", label: t("party.channel.select") }, ...channels.map((channel) => ({ value: channel.id, label: channel.name }))]} /></label>}</>}
          </fieldset>
          {status && <p className="product-create-status" role="status">{status}</p>}
          <footer className="filter-actions">{selected && canWrite && <button type="button" className={selected.active ? "party-deactivate-button" : "party-activate-button"} onClick={() => void toggleActive()} disabled={saving}>{t(selected.active ? "party.action.deactivate" : "party.action.activate")}</button>}<button type="button" onClick={closeDialog}>{t("common.cancel")}</button>{canWrite && <button type="submit" disabled={saving}>{saving ? t("party.saving") : t("common.save")}</button>}</footer>
        </form>}
      </section>
    </div>}
  </>;
}

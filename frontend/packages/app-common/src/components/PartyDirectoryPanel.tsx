import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, Permission, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpSelect } from "./ErpSelect";
import { MemberLoyaltyPanel } from "./MemberLoyaltyPanel";
import { PartyFormFields, type CommercialChannelOption } from "./PartyFormFields";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { clampTableColumnWidth, visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition, TableLayout } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { SafeRetirementDialog, type RetirementResult } from "./SafeRetirementDialog";

export type PartyDirectoryKind = "customers" | "members" | "suppliers";
export type PartyStatusFilter = "all" | "active" | "inactive";
export type PartyDirectoryColumnKey = "code" | "name" | "document" | "phone" | "email" | "location" | "balance" | "status";
export type PartyDirectorySort = { column: PartyDirectoryColumnKey; direction: "asc" | "desc" };

type PartyDirectoryPreferences = {
  query: string;
  statusFilter: PartyStatusFilter;
  sort: PartyDirectorySort;
};

export type PartyDirectoryPanelProps = {
  app?: AppKind;
  kind: PartyDirectoryKind;
  locale: LocaleCode;
  session: UserSession;
  onOpenCustomerReceivables?: (customerId: string) => void;
  allowSafeRetirement?: boolean;
};

const sharedPartyColumnDefinitions = [
  { key: "code", defaultWidth: 105 },
  { key: "name", defaultWidth: 250 },
  { key: "document", defaultWidth: 160 },
  { key: "phone", defaultWidth: 130 },
  { key: "email", defaultWidth: 240 }
] as const satisfies readonly TableColumnDefinition<PartyDirectoryColumnKey>[];

export function partyDirectoryColumnDefinitions(
  kind: PartyDirectoryKind
): readonly TableColumnDefinition<PartyDirectoryColumnKey>[] {
  return [
    ...sharedPartyColumnDefinitions,
    { key: kind === "members" ? "balance" : "location", defaultWidth: kind === "members" ? 150 : 260 },
    { key: "status", defaultWidth: 88 }
  ];
}

export function partyDirectoryGridTemplate(layout: TableLayout<PartyDirectoryColumnKey>): string {
  return visibleTableColumns(layout)
    .map((column) => {
      const minimumWidth = `${clampTableColumnWidth(column.width)}px`;
      if (column.key === "location") return `minmax(${minimumWidth}, 1.5fr)`;
      if (column.key === "email") return `minmax(${minimumWidth}, 1.35fr)`;
      if (column.key === "name") return `minmax(${minimumWidth}, 1fr)`;
      return minimumWidth;
    })
    .join(" ");
}

type FiscalAddress = { address?: string | null; postalCode?: string | null; city?: string | null; province?: string | null; country?: string | null };

export type CustomerView = {
  id: string; clientId: string; fiscalName: string; documentType: string; documentNumber: string;
  version?: number | null;
  address?: FiscalAddress | null; phone?: string | null; email?: string | null; notes?: string | null;
  discount?: number | string | null; isMember: boolean; numMember?: string | null; memberSince?: string | null;
  memberUuid?: string | null;
  balance?: number | string | null; birthday?: string | null; gender?: string | null; commercialConsent?: boolean;
  preferredCommercialChannelId?: string | null; active: boolean; fiscalDataComplete?: boolean;
  creditEnabled?: boolean; creditLimit?: number | string | null; paymentTermDays?: number | null;
  creditBlocked?: boolean; blockOnOverdue?: boolean;
};

export type SupplierView = {
  id: string; supplierId: string; legalName: string; tradeName?: string | null; documentType: string;
  version?: number | null;
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

type PartyManagementPage<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

export type PartyForm = {
  name: string; tradeName: string; documentType: string; documentNumber: string; phone: string; email: string;
  address: string; postalCode: string; city: string; province: string; country: string; notes: string;
  discount: string; numMember: string; birthday: string; gender: string; commercialConsent: boolean;
  preferredCommercialChannelId: string;
  creditEnabled: boolean; creditLimit: string; paymentTermDays: string; creditBlocked: boolean; blockOnOverdue: boolean;
};

export const emptyPartyForm: PartyForm = {
  name: "", tradeName: "", documentType: "NIF", documentNumber: "", phone: "", email: "", address: "",
  postalCode: "", city: "", province: "", country: "ES", notes: "", discount: "0", numMember: "",
  birthday: "", gender: "", commercialConsent: false, preferredCommercialChannelId: "",
  creditEnabled: true, creditLimit: "", paymentTermDays: "30", creditBlocked: false, blockOnOverdue: false
};

export function customerReceivablesActionVisible(kind: PartyDirectoryKind, selected: boolean, permissions: Permission[]) {
  return kind !== "suppliers" && selected && (permissions.includes("ADMIN") || permissions.includes("CUSTOMER_RECEIVABLES_READ"));
}

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
    preferredCommercialChannelId: supplier ? "" : customer.preferredCommercialChannelId ?? "",
    creditEnabled: supplier ? true : customer.creditEnabled ?? true,
    creditLimit: supplier ? "" : customer.creditLimit == null ? "" : String(customer.creditLimit),
    paymentTermDays: supplier ? "30" : String(customer.paymentTermDays ?? 30),
    creditBlocked: supplier ? false : customer.creditBlocked ?? false,
    blockOnOverdue: supplier ? false : customer.blockOnOverdue ?? false
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
    preferredCommercialChannelId: form.commercialConsent ? form.preferredCommercialChannelId || null : null,
    creditEnabled: form.creditEnabled,
    creditLimit: form.creditLimit.trim() ? Number(form.creditLimit) : null,
    unlimitedCredit: !form.creditLimit.trim(),
    paymentTermDays: Number(form.paymentTermDays),
    creditBlocked: form.creditBlocked,
    blockOnOverdue: form.blockOnOverdue
  };
}

export function validatePartyForm(form: PartyForm, supplier: boolean): string[] {
  const errors: string[] = [];
  if (!form.name.trim()) errors.push("name");
  if (!form.documentNumber.trim()) errors.push("documentNumber");
  if (form.country.trim().length !== 2) errors.push("country");
  if (!supplier && (Number(form.discount) < 0 || Number(form.discount) > 100)) errors.push("discount");
  if (!supplier && form.commercialConsent && !form.preferredCommercialChannelId) errors.push("preferredCommercialChannelId");
  if (!supplier && form.creditLimit.trim() && (!Number.isFinite(Number(form.creditLimit)) || Number(form.creditLimit) < 0)) errors.push("creditLimit");
  const paymentTermDays = Number(form.paymentTermDays);
  if (!supplier && (!Number.isInteger(paymentTermDays) || paymentTermDays < 0 || paymentTermDays > 3650)) errors.push("paymentTermDays");
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

export function partyDirectoryPreferenceStorageKey(app: AppKind, username: string, kind: PartyDirectoryKind) {
  return `tpv.party.directory.${app}.${username}.${kind}`;
}

function readPartyDirectoryPreferences(app: AppKind, username: string, kind: PartyDirectoryKind): PartyDirectoryPreferences {
  const fallback: PartyDirectoryPreferences = { query: "", statusFilter: "all", sort: { column: "name", direction: "asc" } };
  if (typeof localStorage === "undefined") return fallback;
  try {
    const saved = JSON.parse(localStorage.getItem(partyDirectoryPreferenceStorageKey(app, username, kind)) ?? "null") as Partial<PartyDirectoryPreferences> | null;
    const validColumns = new Set(partyDirectoryColumnDefinitions(kind).map((column) => column.key));
    return {
      query: typeof saved?.query === "string" ? saved.query : "",
      statusFilter: saved?.statusFilter === "active" || saved?.statusFilter === "inactive" ? saved.statusFilter : "all",
      sort: saved?.sort && validColumns.has(saved.sort.column as PartyDirectoryColumnKey)
        ? { column: saved.sort.column as PartyDirectoryColumnKey, direction: saved.sort.direction === "desc" ? "desc" : "asc" }
        : fallback.sort
    };
  } catch {
    return fallback;
  }
}

function partyDirectorySortValue(entry: PartyDirectoryEntry, kind: PartyDirectoryKind, column: PartyDirectoryColumnKey): string | number {
  const customer = entry as CustomerView;
  const supplier = entry as SupplierView;
  const member = entry as MemberDirectoryView;
  if (column === "code") return kind === "suppliers" ? supplier.supplierId : kind === "members" ? member.numMember || member.memberId : customer.clientId;
  if (column === "name") return kind === "suppliers" ? supplier.legalName : kind === "members" ? member.fiscalName : customer.fiscalName;
  if (column === "document") return entry.documentNumber;
  if (column === "phone") return entry.phone ?? "";
  if (column === "email") return entry.email ?? "";
  if (column === "balance") return Number(member.balance || 0);
  if (column === "location") return `${customer.address?.city ?? supplier.address?.city ?? ""} ${customer.address?.province ?? supplier.address?.province ?? ""}`;
  return entry.active ? 1 : 0;
}

export function sortPartyDirectoryEntries(entries: PartyDirectoryEntry[], kind: PartyDirectoryKind, sort: PartyDirectorySort, locale: LocaleCode) {
  const multiplier = sort.direction === "asc" ? 1 : -1;
  return [...entries].sort((left, right) => {
    const leftValue = partyDirectorySortValue(left, kind, sort.column);
    const rightValue = partyDirectorySortValue(right, kind, sort.column);
    if (typeof leftValue === "number" && typeof rightValue === "number") return (leftValue - rightValue) * multiplier;
    return String(leftValue).localeCompare(String(rightValue), locale, { numeric: true, sensitivity: "base" }) * multiplier;
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

export function partyManagementPagePath(
  entityPath: "customers" | "suppliers",
  query: string,
  statusFilter: PartyStatusFilter,
  cursor: string | null = null,
  sort: PartyDirectorySort = { column: "name", direction: "asc" }
): string {
  const parameters = new URLSearchParams({ size: "50" });
  if (cursor) parameters.set("cursor", cursor);
  if (query.trim()) parameters.set("search", query.trim());
  if (statusFilter !== "all") parameters.set("active", String(statusFilter === "active"));
  parameters.set("sort", sort.column);
  parameters.set("direction", sort.direction);
  return `/${entityPath}/management/page?${parameters.toString()}`;
}

export function PartyDirectoryPanel({
  app = "venta",
  kind,
  locale,
  session,
  onOpenCustomerReceivables,
  allowSafeRetirement = false
}: PartyDirectoryPanelProps) {
  const t = createTranslator(locale);
  const initialPreferences = readPartyDirectoryPreferences(app, session.username, kind);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [members, setMembers] = useState<MemberDirectoryView[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierView[]>([]);
  const [channels, setChannels] = useState<CommercialChannelOption[]>([]);
  const [query, setQuery] = useState(initialPreferences.query);
  const [statusFilter, setStatusFilter] = useState<PartyStatusFilter>(initialPreferences.statusFilter);
  const [sort, setSort] = useState<PartyDirectorySort>(initialPreferences.sort);
  const [memberCandidateQuery, setMemberCandidateQuery] = useState("");
  const [memberCandidateId, setMemberCandidateId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [status, setStatus] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form, setForm] = useState<PartyForm>(emptyPartyForm);
  const [initialForm, setInitialForm] = useState<PartyForm>(emptyPartyForm);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [retirementOpen, setRetirementOpen] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const endpoint = kind === "suppliers" ? "/suppliers" : kind === "members" ? "/members" : "/customers";
  const isSupplier = kind === "suppliers";
  const isMember = kind === "members";
  const managementMode = allowSafeRetirement && !isMember;
  const title = t(`party.${kind}.title`);
  const canWrite = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_CLIENTE_PROVEEDOR")
    || session.permissions.includes(isSupplier ? "SUPPLIERS_WRITE" : "CUSTOMERS_WRITE")
    || (isSupplier && session.permissions.includes("GESTION_ALMACEN"));
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
  const gridStyle = { gridTemplateColumns: partyDirectoryGridTemplate(tableLayout.layout) };

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
    const cellClassName = `party-directory-cell party-directory-cell-${column}`;
    if (column === "code") {
      const code = isSupplier ? supplier.supplierId : isMember ? member.numMember || member.memberId : customer.clientId;
      return <strong className={cellClassName} data-column-key={column} key={column} title={code}>{code}</strong>;
    }
    if (column === "name") {
      const name = isSupplier ? supplier.legalName : isMember ? member.fiscalName : customer.fiscalName;
      return <span className={cellClassName} data-column-key={column} key={column} title={name}>{name}{isSupplier && supplier.tradeName ? <small>{supplier.tradeName}</small> : null}</span>;
    }
    if (column === "document") {
      const document = `${entry.documentType} · ${entry.documentNumber}`;
      return <span className={cellClassName} data-column-key={column} key={column} title={document}>{document}</span>;
    }
    if (column === "phone") {
      const phone = entry.phone || "-";
      return <span className={cellClassName} data-column-key={column} key={column} title={phone}>{phone}</span>;
    }
    if (column === "email") {
      const email = entry.email || "-";
      return <span className={cellClassName} data-column-key={column} key={column} title={email}>{email}</span>;
    }
    if (column === "balance") {
      const balance = Number(member.balance || 0).toLocaleString(locale, { style: "currency", currency: "EUR" });
      return <span className={cellClassName} data-column-key={column} key={column} title={balance}>{balance}</span>;
    }
    if (column === "location") {
      const locatedEntry = entry as CustomerView | SupplierView;
      const location = [locatedEntry.address?.city, locatedEntry.address?.province].filter(Boolean).join(", ") || "-";
      return <span className={cellClassName} data-column-key={column} key={column} title={location}>{location}</span>;
    }
    return <span data-column-key={column} key={column} className={`${cellClassName} ${entry.active ? "party-status active" : "party-status"}`}>
      {t(entry.active ? "party.active" : "party.inactive")}
      {isMember && !member.customerActive ? <small>{t("party.members.customerInactive")}</small> : null}
    </span>;
  }

  function managementPagePath(cursor: string | null = null) {
    return partyManagementPagePath(isSupplier ? "suppliers" : "customers", query, statusFilter, cursor, sort);
  }

  async function load(clearStatus = true, append = false, propagateError = false) {
    if (append) setLoadingMore(true);
    else setLoading(true);
    setLoadError(false); if (clearStatus) setStatus("");
    try {
      if (managementMode && isSupplier) {
        const page = await apiRequest<PartyManagementPage<SupplierView>>(managementPagePath(append ? nextCursor : null), { token: session.accessToken });
        setSuppliers((current) => append ? [...current, ...page.items] : page.items);
        setNextCursor(page.nextCursor ?? null);
        setHasMore(Boolean(page.hasMore));
      }
      else if (managementMode) {
        const [page, channelRows] = await Promise.all([
          apiRequest<PartyManagementPage<CustomerView>>(managementPagePath(append ? nextCursor : null), { token: session.accessToken }),
          apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", { token: session.accessToken })
        ]);
        setCustomers((current) => append ? [...current, ...page.items] : page.items);
        setChannels(channelRows.filter((channel) => channel.active));
        setNextCursor(page.nextCursor ?? null);
        setHasMore(Boolean(page.hasMore));
      }
      else if (isSupplier) setSuppliers(await apiRequest<SupplierView[]>(endpoint, { token: session.accessToken }));
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
          apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", { token: session.accessToken })
        ]);
        setCustomers(customerRows); setChannels(channelRows.filter((channel) => channel.active));
      }
    } catch (error) {
      setLoadError(true);
      setStatus(error instanceof Error ? error.message : t("party.loadError"));
      if (propagateError) throw error;
    }
    finally {
      if (append) setLoadingMore(false);
      else setLoading(false);
    }
  }

  useEffect(() => {
    if (managementMode) return;
    void load();
  }, [kind, managementMode, session.accessToken]);

  useEffect(() => {
    if (!managementMode) return;
    const timeoutId = window.setTimeout(() => void load(), 250);
    return () => window.clearTimeout(timeoutId);
  }, [kind, managementMode, query, session.accessToken, sort.column, sort.direction, statusFilter]);

  useEffect(() => {
    if (typeof localStorage === "undefined") return;
    try {
      localStorage.setItem(
        partyDirectoryPreferenceStorageKey(app, session.username, kind),
        JSON.stringify({ query, statusFilter, sort })
      );
    } catch {
      // The directory remains usable when browser storage is unavailable.
    }
  }, [app, kind, query, session.username, sort, statusFilter]);

  const rows = useMemo(() => {
    const filtered = filterPartyDirectoryEntries(entries, kind, query, statusFilter, locale);
    return managementMode ? filtered : sortPartyDirectoryEntries(filtered, kind, sort, locale);
  }, [customers, members, suppliers, query, statusFilter, kind, locale, managementMode, sort]);
  const memberCandidates = useMemo(
    () => availableMemberCustomers(customers, memberCandidateQuery, locale),
    [customers, memberCandidateQuery, locale]
  );

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) {
    setForm((current) => ({ ...current, [field]: value }));
    setFormErrors((current) => current.filter((candidate) => candidate !== field));
  }
  function openNew() {
    setSelectedId(null); setForm(emptyPartyForm); setInitialForm(emptyPartyForm); setFormErrors([]); setStatus("");
    setMemberCandidateQuery(""); setMemberCandidateId(null); setDialogOpen(true);
  }
  function openEntry(entry: PartyDirectoryEntry) {
    setSelectedId(entry.id);
    if (!isMember) {
      const nextForm = partyFormFromView(entry as CustomerView | SupplierView, isSupplier);
      setForm(nextForm); setInitialForm(nextForm); setFormErrors([]);
    }
    setStatus(""); setDialogOpen(true);
  }
  function closeDialog() {
    if (!saving) {
      if (!isMember && JSON.stringify(form) !== JSON.stringify(initialForm) && !window.confirm(t("party.confirm.discard"))) return;
      setDialogOpen(false); setSelectedId(null); setMemberCandidateId(null); setMemberCandidateQuery("");
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (isMember) return;
    const nextErrors = validatePartyForm(form, isSupplier);
    if (nextErrors.length) { setFormErrors(nextErrors); setStatus(t("party.form.invalid")); return; }
    setSaving(true); setStatus("");
    try {
      await apiRequest(selectedId ? `${endpoint}/${selectedId}` : endpoint, {
        method: selectedId ? "PUT" : "POST", token: session.accessToken,
        body: buildPartyRequest(form, isSupplier, !isSupplier && Boolean((selected as CustomerView | null)?.isMember))
      });
      setDialogOpen(false); await load(false); setStatus(t("party.saveSuccess"));
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
      setDialogOpen(false); await load(false); setStatus(t("party.saveSuccess"));
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
      setDialogOpen(false); setMemberCandidateId(null); await load(false); setStatus(t("party.saveSuccess"));
    } catch (error) { setStatus(error instanceof Error ? error.message : t("party.saveError")); }
    finally { setSaving(false); }
  }

  function openSafeRetirement() {
    if (!selected || isMember || !allowSafeRetirement || !session.permissions.includes("ADMIN")) return;
    setDialogOpen(false);
    setRetirementOpen(true);
  }

  async function completeSafeRetirement(result: RetirementResult) {
    await load(false, false, true);
    setRetirementOpen(false);
    setDialogOpen(false);
    setSelectedId(null);
    setStatus(t(`safeManagement.result.${result.outcome}`));
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
      <label className="party-directory-status-filter">
        <span>{t("party.column.status")}</span>
        <ErpSelect
          className="erp-select--compact"
          value={statusFilter}
          aria-label={t("party.column.status")}
          onChange={(value) => setStatusFilter(value as PartyStatusFilter)}
          options={["all", "active", "inactive"].map((value) => ({
            value,
            label: t(`party.filter.status.${value}`)
          }))}
        />
      </label>
      {(query || statusFilter !== "all") && (
        <button
          type="button"
          className="party-directory-clear-filters"
          onClick={() => {
            setQuery("");
            setStatusFilter("all");
          }}
        >
          {t("party.filter.clear")}
        </button>
      )}
      <span className="party-directory-result-count">
        {t("party.results").replace("{count}", String(rows.length))}
      </span>
      {(query || statusFilter !== "all") && <div className="party-directory-active-filters" aria-label={t("party.filter.active")}>
        {query && <button type="button" onClick={() => setQuery("")}>{t("party.searchLabel")}: {query}<span aria-hidden="true"> ×</span></button>}
        {statusFilter !== "all" && <button type="button" onClick={() => setStatusFilter("all")}>{t("party.column.status")}: {t(`party.filter.status.${statusFilter}`)}<span aria-hidden="true"> ×</span></button>}
      </div>}
    </div>
    <div className="party-directory-table" role="table" aria-label={title}>
      <div className="party-directory-row header" role="row" style={gridStyle}>
        {visibleColumns.map((column) => (
          <TableLayoutHeaderCell
            as="span"
            column={column}
            key={column.key}
            sortDirection={sort.column === column.key ? sort.direction : null}
            sortLabel={`${t("party.sortBy")} ${columnLabel(column.key)}`}
            onSort={(columnKey) => setSort((current) => ({
              column: columnKey,
              direction: current.column === columnKey && current.direction === "asc" ? "desc" : "asc"
            }))}
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
      {!loading && loadError && <div className="party-directory-state error" role="alert"><span>{status || t("party.loadError")}</span><button type="button" onClick={() => void load()}>{t("party.retry")}</button></div>}
      {!loading && !loadError && rows.map((entry) => {
        return <button type="button" className="party-directory-row party-directory-selectable-row" role="row" style={gridStyle} key={entry.id} onClick={() => openEntry(entry)}>
          {visibleColumns.map((column) => renderCell(column.key, entry))}
        </button>;
      })}
      {!loading && !loadError && rows.length === 0 && <div className="party-directory-state"><span>{t("party.empty")}</span>{canWrite && <button type="button" onClick={openNew}>{t(`party.${kind}.new`)}</button>}</div>}
    </div>
    {!loading && !loadError && managementMode && hasMore && <div className="party-directory-pagination">
      <button type="button" onClick={() => void load(false, true)} disabled={loadingMore || !nextCursor}>
        {t(loadingMore ? "safeManagement.pagination.loading" : "safeManagement.pagination.more")}
      </button>
    </div>}
    {status && !dialogOpen && !loadError && <p className="product-create-status party-directory-toast" role="status">{status}</p>}

    {dialogOpen && <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="party-form-title">
      <section className="filter-dialog product-create-dialog party-create-dialog">
        <header className="filter-header"><div><h2 id="party-form-title">{selectedId ? t(`party.${kind}.detail`) : t(`party.${kind}.new`)}</h2><span>{selected ? `${selectedCode} · ${selected.active ? t("party.active") : t("party.inactive")}` : isMember ? t("party.members.selectCustomerSubtitle") : t("party.form.subtitle")}</span></div><button type="button" onClick={closeDialog}>{t("common.close")}</button></header>
        {isMember ? memberDialogContent : <form className="product-create-form party-create-form" onSubmit={submit}>
          <fieldset disabled={!canWrite || saving}>
            <PartyFormFields
              form={form}
              errors={formErrors}
              channels={channels}
              supplier={isSupplier}
              autoFocusName
              t={t}
              onChange={update}
            />
          </fieldset>
          {status && <p className="product-create-status" role="status">{status}</p>}
          {isMember && selected && (selected as CustomerView).memberUuid && (
            <MemberLoyaltyPanel app={app} memberId={(selected as CustomerView).memberUuid!} session={session} t={t} />
          )}
          <footer className="filter-actions">{selected && allowSafeRetirement && session.permissions.includes("ADMIN") && <button type="button" className="safe-retirement-open" onClick={openSafeRetirement} disabled={saving}>{t("safeManagement.action.retire")}</button>}{selected && customerReceivablesActionVisible(kind, true, session.permissions) && onOpenCustomerReceivables && <button type="button" onClick={() => onOpenCustomerReceivables(selected.id)}>{t("party.action.viewReceivables")}</button>}{selected && canWrite && <button type="button" className={selected.active ? "party-deactivate-button" : "party-activate-button"} onClick={() => void toggleActive()} disabled={saving}>{t(selected.active ? "party.action.deactivate" : "party.action.activate")}</button>}<button type="button" onClick={closeDialog}>{t("common.cancel")}</button>{canWrite && <button type="submit" disabled={saving}>{saving ? t("party.saving") : t("common.save")}</button>}</footer>
        </form>}
      </section>
    </div>}
    {retirementOpen && selected && !isMember && <SafeRetirementDialog
      open
      entityPath={isSupplier ? "suppliers" : "customers"}
      entityId={selected.id}
      entityLabel={isSupplier ? (selected as SupplierView).legalName : (selected as CustomerView).fiscalName}
      locale={locale}
      token={session.accessToken}
      onClose={() => {
        setRetirementOpen(false);
        setDialogOpen(true);
      }}
      onRetired={completeSafeRetirement}
    />}
  </>;
}

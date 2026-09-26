import { ErpConfirmDialog } from "./ErpConfirmDialog";
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, Permission, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpSelect } from "./ErpSelect";
import { ErpFilterChips, type ErpFilterChip } from "./ErpFilterChips";
import { MemberLoyaltyPanel } from "./MemberLoyaltyPanel";
import { PartyFormFields, type CommercialChannelOption } from "./PartyFormFields";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { clampTableColumnWidth, visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition, TableLayout } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { SafeRetirementDialog, type RetirementResult } from "./SafeRetirementDialog";
import { CustomerDocumentsDialog } from "./CustomerDocumentsDialog";
import { CentralCustomerReuse } from "./CentralCustomerReuse";
import { customerDocumentType, customerIdentityFailure } from "./customerDocumentIdentity";
import stockFilterIcon from "../assets/stock/filter.png";
import "./PartyDirectoryFilters.css";
import "./PartyDirectoryDesktop.css";

export type PartyDirectoryKind = "customers" | "members" | "suppliers";
export type PartyStatusFilter = "all" | "active" | "inactive";
export type PartyDirectoryColumnKey = "code" | "name" | "document" | "phone" | "email" | "location" | "balance" | "status" | "debt" | "creditEnabled" | "creditLimit" | "creditBlocked" | "paymentTermDays" | "discount" | "isMember" | "category" | "points" | "memberSince" | "tradeName" | "address" | "postalCode" | "country" | "notes" | "commercialConsent";
export type PartyDirectorySort = { column: PartyDirectoryColumnKey; direction: "asc" | "desc" };
export type PartyDirectoryFieldFilters = Partial<Record<"code" | "name" | "document" | "phone" | "email" | "location" | "category" | "postalCode" | "country" | "creditEnabled" | "creditBlocked" | "isMember" | "commercialConsent" | "debt", string>>;

function partyDirectoryFilterFields(kind: PartyDirectoryKind, extended = false): Array<keyof PartyDirectoryFieldFilters> {
  const fields: Array<keyof PartyDirectoryFieldFilters> = ["code", "name", "document", "phone", "email", kind === "members" ? "category" : "location"];
  if (extended) fields.push("postalCode", "country", ...(kind === "suppliers" ? [] : ["debt", "creditEnabled", "creditBlocked"] as const), ...(kind === "customers" ? ["isMember", "commercialConsent"] as const : []));
  return fields;
}

type PartyDirectoryPreferences = {
  query: string;
  statusFilter: PartyStatusFilter;
  sort: PartyDirectorySort;
  fieldFilters: PartyDirectoryFieldFilters;
};

export type PartyDirectoryPanelProps = {
  app?: AppKind;
  kind: PartyDirectoryKind;
  locale: LocaleCode;
  session: UserSession;
  onOpenCustomerReceivables?: (customerId: string) => void;
  allowSafeRetirement?: boolean;
  headerExtra?: ReactNode;
};

const sharedPartyColumnDefinitions = [
  { key: "code", defaultWidth: 105 },
  { key: "name", defaultWidth: 250 },
  { key: "document", defaultWidth: 160 },
  { key: "phone", defaultWidth: 130 },
  { key: "email", defaultWidth: 240 }
] as const satisfies readonly TableColumnDefinition<PartyDirectoryColumnKey>[];

export function partyDirectoryColumnDefinitions(
  kind: PartyDirectoryKind, extended = false
): readonly TableColumnDefinition<PartyDirectoryColumnKey>[] {
  return [
    ...sharedPartyColumnDefinitions,
    { key: kind === "members" ? "balance" : "location", defaultWidth: kind === "members" ? 150 : 260 },
    ...(extended ? [
      ...(kind === "suppliers" ? [{ key: "tradeName", defaultWidth: 180, defaultVisible: false }] : [
        { key: "debt", defaultWidth: 135 }, { key: "creditEnabled", defaultWidth: 155 },
        { key: "creditLimit", defaultWidth: 135, defaultVisible: false },
        { key: "creditBlocked", defaultWidth: 135, defaultVisible: false },
        { key: "paymentTermDays", defaultWidth: 115, defaultVisible: false },
        { key: "discount", defaultWidth: 110, defaultVisible: false },
        ...(kind === "customers" ? [{ key: "commercialConsent", defaultWidth: 210 }, { key: "isMember", defaultWidth: 100 }] : [
          { key: "category", defaultWidth: 150 }, { key: "points", defaultWidth: 100 },
          { key: "memberSince", defaultWidth: 125, defaultVisible: false }
        ])
      ]),
      { key: "address", defaultWidth: 260, defaultVisible: kind !== "members" },
      { key: "postalCode", defaultWidth: 110, defaultVisible: kind === "customers" },
      { key: "country", defaultWidth: 85, defaultVisible: false },
      { key: "notes", defaultWidth: 230, defaultVisible: false }
    ] as TableColumnDefinition<PartyDirectoryColumnKey>[] : []),
    { key: "status", defaultWidth: extended ? 110 : 88 }
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
  creditBlocked?: boolean; blockOnOverdue?: boolean; outstandingDebt?: number | string | null;
};

export type SupplierView = {
  id: string; supplierId: string; legalName: string; tradeName?: string | null; documentType: string;
  version?: number | null;
  documentNumber: string; address?: FiscalAddress | null; phone?: string | null; email?: string | null;
  notes?: string | null; active: boolean;
};

export type MemberDirectoryView = Partial<Pick<CustomerView, "address" | "notes" | "outstandingDebt" | "creditEnabled" | "creditLimit" | "creditBlocked" | "paymentTermDays" | "discount">> & {
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
    documentType: supplier ? entry.documentType : customerDocumentType(entry.documentType),
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
    fiscalName: form.name.trim(), documentType: customerDocumentType(form.documentType), documentNumber: form.documentNumber.trim(), address,
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
  locale: LocaleCode,
  fieldFilters: PartyDirectoryFieldFilters = {},
  extended = false
): PartyDirectoryEntry[] {
  const normalized = normalizedText(query.trim(), locale);
  return entries.filter((entry) => {
    const matchesQuery = !normalized || partyDirectorySearchValues(entry, kind)
      .some((value) => normalizedText(value, locale).includes(normalized));
    const matchesStatus = statusFilter === "all" || entry.active === (statusFilter === "active");
    const matchesFields = partyDirectoryFilterFields(kind, extended).every((field) => {
      const criterion = normalizedText(fieldFilters[field]?.trim(), locale);
      if (!criterion) return true;
      const customer = entry as CustomerView;
      if (field === "debt") return customer.outstandingDebt != null && (Number(customer.outstandingDebt) > 0) === (criterion === "with");
      if (field === "creditEnabled" || field === "creditBlocked" || field === "isMember" || field === "commercialConsent") return customer[field] != null && customer[field] === (criterion === "true");
      let values: Array<string | null | undefined>;
      if (field === "category") values = [(entry as MemberDirectoryView).categoryName];
      else if (field === "name" && kind === "suppliers") {
        const supplier = entry as SupplierView;
        values = [supplier.legalName, supplier.tradeName];
      } else values = [String(partyDirectorySortValue(entry, kind, field as PartyDirectoryColumnKey))];
      return values.some(value => normalizedText(value, locale).includes(criterion));
    });
    return matchesQuery && matchesStatus && matchesFields;
  });
}

export function partyDirectoryPreferenceStorageKey(app: AppKind, username: string, kind: PartyDirectoryKind) {
  return `tpv.party.directory.${app}.${username}.${kind}`;
}

function readPartyDirectoryPreferences(app: AppKind, username: string, kind: PartyDirectoryKind): PartyDirectoryPreferences {
  const fallback: PartyDirectoryPreferences = { query: "", statusFilter: "all", sort: { column: app === "venta" ? "code" : "name", direction: "asc" }, fieldFilters: {} };
  if (typeof localStorage === "undefined") return fallback;
  try {
    const saved = JSON.parse(localStorage.getItem(partyDirectoryPreferenceStorageKey(app, username, kind)) ?? "null") as Partial<PartyDirectoryPreferences> | null;
    const validColumns = new Set(partyDirectoryColumnDefinitions(kind, app !== "pda").map((column) => column.key));
    return {
      query: typeof saved?.query === "string" ? saved.query : "",
      statusFilter: saved?.statusFilter === "active" || saved?.statusFilter === "inactive" ? saved.statusFilter : "all",
      fieldFilters: app !== "pda" ? Object.fromEntries(partyDirectoryFilterFields(kind, true).flatMap(field => {
        const value = saved?.fieldFilters?.[field];
        return typeof value === "string" ? [[field, value]] : [];
      })) : {},
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
  if (column === "debt") return Number(customer.outstandingDebt ?? 0);
  if (column === "category") return member.categoryName ?? "";
  if (column === "points") return member.points ?? 0;
  if (column === "memberSince") return member.memberSince ?? "";
  if (column === "tradeName") return supplier.tradeName ?? "";
  if (column === "address" || column === "postalCode" || column === "country") return customer.address?.[column] ?? "";
  if (column === "notes") return customer.notes ?? "";
  if (column === "creditEnabled" || column === "creditBlocked" || column === "isMember" || column === "commercialConsent") return customer[column] ? 1 : 0;
  if (column === "creditLimit" || column === "discount" || column === "paymentTermDays") return Number(customer[column] ?? 0);
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
  sort: PartyDirectorySort = { column: "name", direction: "asc" },
  fieldFilters: PartyDirectoryFieldFilters = {}
): string {
  const parameters = new URLSearchParams({ size: "50" });
  if (cursor) parameters.set("cursor", cursor);
  if (query.trim()) parameters.set("search", query.trim());
  if (statusFilter !== "all") parameters.set("active", String(statusFilter === "active"));
  parameters.set("sort", sort.column);
  parameters.set("direction", sort.direction);
  for (const [key, value] of Object.entries(fieldFilters)) if (value?.trim()) parameters.set(`field.${key}`, value.trim());
  return `/${entityPath}/management/page?${parameters.toString()}`;
}

export function PartyDirectoryPanel({
  app = "venta",
  kind,
  locale,
  session,
  onOpenCustomerReceivables,
  allowSafeRetirement = false,
  headerExtra
}: PartyDirectoryPanelProps) {
  const t = createTranslator(locale);
  const initialPreferences = readPartyDirectoryPreferences(app, session.username, kind);
  const [customers, setCustomers] = useState<CustomerView[]>([]);
  const [members, setMembers] = useState<MemberDirectoryView[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierView[]>([]);
  const [channels, setChannels] = useState<CommercialChannelOption[]>([]);
  const [query, setQuery] = useState(initialPreferences.query);
  const [statusFilter, setStatusFilter] = useState<PartyStatusFilter>(initialPreferences.statusFilter);
  const [fieldFilters, setFieldFilters] = useState<PartyDirectoryFieldFilters>(initialPreferences.fieldFilters);
  const [fieldFiltersOpen, setFieldFiltersOpen] = useState(false);
  const fieldFiltersId = useId();
  const filterButtonRef = useRef<HTMLButtonElement>(null);
  const [sort, setSort] = useState<PartyDirectorySort>(initialPreferences.sort);
  const [memberCandidateQuery, setMemberCandidateQuery] = useState("");
  const [memberCandidateId, setMemberCandidateId] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const loadRequestRef = useRef(0);
  const memberSearchRef = useRef<HTMLInputElement>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [status, setStatus] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [memberTab, setMemberTab] = useState<"customer" | "loyalty">("customer");
  const memberTabId = useId();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);
  const [historyCustomer, setHistoryCustomer] = useState<CustomerView | null>(null);
  const selectedRowRef = useRef<HTMLButtonElement>(null);
  const [form, setForm] = useState<PartyForm>(emptyPartyForm);
  const [initialForm, setInitialForm] = useState<PartyForm>(emptyPartyForm);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [documentError, setDocumentError] = useState("");
  const [saving, setSaving] = useState(false);
  const [centralBusy, setCentralBusy] = useState(false);
  const [retirementOpen, setRetirementOpen] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const endpoint = kind === "suppliers" ? "/suppliers" : kind === "members" ? "/members" : "/customers";
  const isSupplier = kind === "suppliers";
  const isMember = kind === "members";
  const managementMode = allowSafeRetirement && !isMember;
  const classicWindow = app !== "pda";
  const [confirmation, setConfirmation] = useState<"discard" | "active" | null>(null);
  const supportsFieldFilters = app !== "pda";
  const directoryFilterFields = partyDirectoryFilterFields(kind, app !== "pda");
  const title = t(`party.${kind}.title`);
  const canWrite = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_CLIENTE_PROVEEDOR")
    || session.permissions.includes(isSupplier ? "SUPPLIERS_WRITE" : "CUSTOMERS_WRITE")
    || (isSupplier && session.permissions.includes("GESTION_ALMACEN"));
  const enrichedMembers = useMemo(() => {
    const byId = new Map(customers.map(customer => [customer.id, customer]));
    return members.map(member => {
      const customer = byId.get(member.customerId);
      return { ...customer, ...member, id: member.id };
    });
  }, [customers, members]);
  const entries: PartyDirectoryEntry[] = isSupplier ? suppliers : isMember && app !== "pda" ? enrichedMembers : isMember ? members : customers;
  const selected = entries.find((entry) => entry.id === selectedId) ?? null;
  const memberCandidate = customers.find((customer) => customer.id === memberCandidateId) ?? null;
  const columnDefinitions = useMemo(() => partyDirectoryColumnDefinitions(kind, app !== "pda"), [kind, app]);
  const tableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: session.accessToken,
    tableKey: `party.${kind}`,
    definitions: columnDefinitions
  });
  const migratedLayouts = useRef(new Set<string>());
  useEffect(() => {
    if (app === "pda" || isMember || !tableLayout.ready) return;
    const migrations: Array<{ key: string; columns: PartyDirectoryColumnKey[] }> = [
      { key: "contact-consent.v1", columns: isSupplier
        ? ["address"] : ["address", "postalCode", "commercialConsent", "creditEnabled"] },
      ...(!isSupplier ? [{ key: "member.v1", columns: ["isMember"] as PartyDirectoryColumnKey[] }] : [])
    ];
    const pendingMigrations = migrations.filter(migration => {
      const key = `tpv.party.columns.${app === "venta" ? "venta." : ""}${migration.key}.${session.username}.${kind}`;
      if (migratedLayouts.current.has(key)) return false;
      migratedLayouts.current.add(key);
      try { return !localStorage.getItem(key); }
      catch { return true; } // Apply once in this session when storage is unavailable.
    });
    if (!pendingMigrations.length) return;
    // Expose only newly requested fields, retaining previous choices, widths and order.
    const requestedColumns = new Set(pendingMigrations.flatMap(migration => migration.columns));
    const nextLayout = tableLayout.layout.map(column => requestedColumns.has(column.key)
      ? { ...column, visible: true } : column);
    if (nextLayout.some((column, index) => column.visible !== tableLayout.layout[index].visible)) {
      tableLayout.replaceLayout(nextLayout);
    }
    for (const migration of pendingMigrations) {
      try { localStorage.setItem(`tpv.party.columns.${app === "venta" ? "venta." : ""}${migration.key}.${session.username}.${kind}`, "1"); }
      catch { /* Optional local marker. */ }
    }
  }, [app, isMember, isSupplier, kind, session.username, tableLayout.ready, tableLayout.layout, tableLayout.replaceLayout]);
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const gridStyle = { gridTemplateColumns: partyDirectoryGridTemplate(tableLayout.layout), ...(app !== "pda" ? { minWidth: visibleColumns.reduce((width, column) => width + clampTableColumnWidth(column.width), 0) } : {}) };

  function columnLabel(column: PartyDirectoryColumnKey): string {
    if (column === "code") return t("party.column.code");
    if (column === "name") return t("party.column.name");
    if (column === "document") return t("party.column.document");
    if (column === "phone") return t("party.column.phone");
    if (column === "email") return t("party.column.email");
    if (column === "balance") return t("party.column.balance");
    if (column === "location") return t("party.column.location");
    if (column === "status") return t("party.column.status");
    return t(`party.gestion.column.${column}`);
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
    if (column !== "status") {
      const value = partyDirectorySortValue(entry, kind, column);
      let text = String(value || "—");
      if (column === "debt" || column === "creditLimit") text = column === "creditLimit" && customer.creditLimit == null ? t("party.credit.unlimited") : customer[column === "debt" ? "outstandingDebt" : "creditLimit"] == null ? "—" : Number(value).toLocaleString(locale, { style: "currency", currency: "EUR" });
      if (["creditEnabled", "creditBlocked", "isMember", "commercialConsent"].includes(column)) text = customer[column as "creditEnabled"] == null ? "—" : t(value ? "common.yes" : "common.no");
      if (["points", "discount", "paymentTermDays"].includes(column)) text = Number(value).toLocaleString(locale) + (column === "discount" ? " %" : "");
      if (column === "memberSince" && value) text = new Date(`${value}T12:00:00`).toLocaleDateString(locale);
      return <span data-column-key={column} key={column} className={`${cellClassName}${column === "debt" && Number(value) > 0 ? " has-debt" : ""}`} title={text}>{text}</span>;
    }
    if (app !== "pda") {
      return <span data-column-key={column} key={column} className={cellClassName}>
        <span className={`party-desktop-status ${entry.active ? "is-active" : "is-inactive"}`}>{t(entry.active ? "party.active" : "party.inactive")}</span>
        {isMember && !member.customerActive && <small>{t("party.members.customerInactive")}</small>}
      </span>;
    }
    return <span data-column-key={column} key={column} className={`${cellClassName} ${entry.active ? "party-status active" : "party-status"}`}>
      {t(entry.active ? "party.active" : "party.inactive")}
      {isMember && !member.customerActive ? <small>{t("party.members.customerInactive")}</small> : null}
    </span>;
  }

  function managementPagePath(cursor: string | null = null) {
    return partyManagementPagePath(isSupplier ? "suppliers" : "customers", query, statusFilter, cursor, sort, fieldFilters);
  }

  async function load(clearStatus = true, append = false, propagateError = false) {
    const requestId = ++loadRequestRef.current;
    if (append) setLoadingMore(true);
    else setLoading(true);
    setLoadError(false); if (clearStatus) setStatus("");
    try {
      if (managementMode && isSupplier) {
        const page = await apiRequest<PartyManagementPage<SupplierView>>(managementPagePath(append ? nextCursor : null), { token: session.accessToken });
        if (requestId !== loadRequestRef.current) return;
        setSuppliers((current) => append ? [...current, ...page.items] : page.items);
        setNextCursor(page.nextCursor ?? null);
        setHasMore(Boolean(page.hasMore));
      }
      else if (managementMode) {
        const [page, channelRows] = await Promise.all([
          apiRequest<PartyManagementPage<CustomerView>>(managementPagePath(append ? nextCursor : null), { token: session.accessToken }),
          apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", { token: session.accessToken })
        ]);
        if (requestId !== loadRequestRef.current) return;
        setCustomers((current) => append ? [...current, ...page.items] : page.items);
        setChannels(channelRows.filter((channel) => channel.active));
        setNextCursor(page.nextCursor ?? null);
        setHasMore(Boolean(page.hasMore));
      }
      else if (isSupplier) {
        const rows = await apiRequest<SupplierView[]>(endpoint, { token: session.accessToken });
        if (requestId !== loadRequestRef.current) return;
        setSuppliers(rows);
      }
      else if (isMember) {
        const [memberRows, customerRows, channelRows] = await Promise.all([
          apiRequest<MemberDirectoryView[]>(endpoint, { token: session.accessToken }),
          apiRequest<CustomerView[]>("/customers", { token: session.accessToken }),
          app !== "pda" ? apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", { token: session.accessToken }) : Promise.resolve([])
        ]);
        if (requestId !== loadRequestRef.current) return;
        setMembers(memberRows);
        setCustomers(customerRows);
        setChannels(channelRows.filter(channel => channel.active));
      }
      else {
        const [customerRows, channelRows] = await Promise.all([
          apiRequest<CustomerView[]>(endpoint, { token: session.accessToken }),
          apiRequest<CommercialChannelOption[]>("/commercial-contact-channels", { token: session.accessToken })
        ]);
        if (requestId !== loadRequestRef.current) return;
        setCustomers(customerRows); setChannels(channelRows.filter((channel) => channel.active));
      }
    } catch (error) {
      if (requestId !== loadRequestRef.current) return;
      setLoadError(true);
      setStatus(error instanceof Error ? error.message : t("party.loadError"));
      if (propagateError) throw error;
    }
    finally {
      if (requestId === loadRequestRef.current) {
        setLoadingMore(false);
        setLoading(false);
      }
    }
  }

  useEffect(() => {
    if (managementMode) return;
    void load();
    return () => { loadRequestRef.current++; };
  }, [kind, managementMode, session.accessToken]);

  useEffect(() => {
    if (!managementMode) return;
    setNextCursor(null);
    setHasMore(false);
    const timeoutId = window.setTimeout(() => void load(), 250);
    return () => { window.clearTimeout(timeoutId); loadRequestRef.current++; };
  }, [kind, managementMode, query, session.accessToken, sort.column, sort.direction, statusFilter, fieldFilters]);

  useEffect(() => {
    if (typeof localStorage === "undefined") return;
    try {
      localStorage.setItem(
        partyDirectoryPreferenceStorageKey(app, session.username, kind),
        JSON.stringify({ query, statusFilter, sort, ...(supportsFieldFilters ? { fieldFilters } : {}) })
      );
    } catch {
      // The directory remains usable when browser storage is unavailable.
    }
  }, [app, kind, query, session.username, sort, statusFilter, supportsFieldFilters, fieldFilters]);

  const rows = useMemo(() => {
    if (managementMode) return entries;
    const filtered = filterPartyDirectoryEntries(entries, kind, query, statusFilter, locale, supportsFieldFilters ? fieldFilters : {}, app !== "pda");
    return managementMode ? filtered : sortPartyDirectoryEntries(filtered, kind, sort, locale);
  }, [customers, members, suppliers, enrichedMembers, app, query, statusFilter, kind, locale, managementMode, sort, supportsFieldFilters, fieldFilters]);
  const memberCandidates = useMemo(
    () => availableMemberCustomers(customers, memberCandidateQuery, locale),
    [customers, memberCandidateQuery, locale]
  );

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) {
    setForm((current) => ({ ...current, [field]: value }));
    const identityChanged = field === "documentType" || field === "documentNumber";
    if (identityChanged) setDocumentError("");
    setFormErrors((current) => current.filter((candidate) => candidate !== field && !(identityChanged && candidate === "documentNumber")));
  }
  function openNew() {
    setSelectedId(null); setForm(emptyPartyForm); setInitialForm(emptyPartyForm); setFormErrors([]); setDocumentError(""); setStatus("");
    setMemberCandidateQuery(""); setMemberCandidateId(null); setDialogOpen(true);
  }
  function openEntry(entry: PartyDirectoryEntry) {
    setSelectedId(entry.id);
    setMemberTab("customer");
    const editableEntry = isMember ? customers.find(customer => customer.id === (entry as MemberDirectoryView).customerId) : entry;
    if (editableEntry && (!isMember || app !== "pda")) {
      const nextForm = partyFormFromView(editableEntry as CustomerView | SupplierView, isSupplier);
      setForm(nextForm); setInitialForm(nextForm); setFormErrors([]); setDocumentError("");
    }
    setStatus(""); setDialogOpen(true);
  }
  const toolbarEntry = rows.find(entry => entry.id === selectedRowId) ?? null;
  function retireToolbarEntry() {
    if (!toolbarEntry || !classicWindow || !allowSafeRetirement || isMember || !session.permissions.includes("ADMIN")) return;
    setSelectedId(toolbarEntry.id); setRetirementOpen(true);
  }
  useEffect(() => {
    if (!classicWindow) return;
    const handler = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || !["F8", "F9", "F7"].includes(event.key)) return;
      if (document.querySelector('[aria-modal="true"]') || dialogOpen || retirementOpen || historyCustomer) return;
      if (event.key === "F9" && (!allowSafeRetirement || isMember || !session.permissions.includes("ADMIN"))) return;
      event.preventDefault();
      if (event.key === "F8" && canWrite) openNew();
      if (event.key === "F9") retireToolbarEntry();
      if (event.key === "F7" && canWrite && toolbarEntry) openEntry(toolbarEntry);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  });

  function closeDialog(confirmed = false) {
    if (!saving && !centralBusy) {
      if (!confirmed && (!isMember || (app !== "pda" && selectedId)) && JSON.stringify(form) !== JSON.stringify(initialForm)) {
        if (classicWindow) { setConfirmation("discard"); return; }
        if (!window.confirm(t("party.confirm.discard"))) return;
      }
      setDialogOpen(false); setSelectedId(null); setMemberCandidateId(null); setMemberCandidateQuery("");
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if ((isMember && (app === "pda" || !selectedMemberCustomer)) || saving || centralBusy || !canWrite) return;
    setDocumentError("");
    const nextErrors = validatePartyForm(form, isSupplier);
    if (nextErrors.length) { setFormErrors(nextErrors); setStatus(t("party.form.invalid")); return; }
    setSaving(true); setStatus(""); setFormErrors([]);
    try {
      const editId = isMember ? selectedMemberCustomer?.id : selectedId;
      const editEndpoint = isMember ? "/customers" : endpoint;
      const customer = isMember ? selectedMemberCustomer : selected as CustomerView | null;
      await apiRequest(editId ? `${editEndpoint}/${editId}` : editEndpoint, {
        method: editId ? "PUT" : "POST", token: session.accessToken,
        body: buildPartyRequest(form, isSupplier, !isSupplier && Boolean(customer?.isMember))
      });
      setDialogOpen(false); await load(false); setStatus(t("party.saveSuccess"));
    } catch (error) {
      const identityFailure = !isSupplier ? customerIdentityFailure(error) : undefined;
      if (identityFailure) {
        const message = t(identityFailure.messageKey);
        if (identityFailure.documentField) { setFormErrors(["documentNumber"]); setDocumentError(message); }
        setStatus(message);
      } else setStatus(error instanceof Error ? error.message : t("party.saveError"));
    }
    finally { setSaving(false); }
  }

  async function toggleActive(confirmed = false) {
    if (!selected || !canWrite || saving) return;
    const action = selected.active ? "deactivate" : "activate";
    if (isMember && !(selected as MemberDirectoryView).customerActive && action === "activate") {
      setStatus(t("party.members.customerInactiveHint"));
      return;
    }
    if (!confirmed) {
      if (classicWindow) { setConfirmation("active"); return; }
      if (!window.confirm(t(`party.confirm.${action}`))) return;
    }
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
    setHistoryCustomer(null);
    setStatus(t(`safeManagement.result.${result.outcome}`));
  }

  const selectedMember = isMember ? selected as MemberDirectoryView | null : null;
  const selectedMemberCustomer = selectedMember ? customers.find(customer => customer.id === selectedMember.customerId) : undefined;
  const selectedCode = selected
    ? isSupplier ? (selected as SupplierView).supplierId
      : isMember ? selectedMember?.memberId
        : (selected as CustomerView).clientId
    : null;
  function fieldFilterLabel(field: keyof PartyDirectoryFieldFilters) {
    return field === "category" ? t("party.members.category") : columnLabel(field);
  }
  function filterOptions(field: keyof PartyDirectoryFieldFilters) {
    if (["creditEnabled", "creditBlocked", "isMember", "commercialConsent"].includes(field)) return [
      { value: "", label: t("party.filter.status.all") }, { value: "true", label: t("common.yes") }, { value: "false", label: t("common.no") }
    ];
    if (field === "debt") return [
      { value: "", label: t("party.filter.status.all") }, { value: "with", label: t("party.gestion.debt.with") }, { value: "without", label: t("party.gestion.debt.without") }
    ];
    return null;
  }
  function filterValueLabel(field: keyof PartyDirectoryFieldFilters, value: string) {
    return value ? filterOptions(field)?.find(option => option.value === value)?.label ?? value : "";
  }
  function clearDirectoryFilters() {
    setQuery(""); setStatusFilter("all"); setFieldFilters({});
  }
  const activeFieldFilterCount = directoryFilterFields.filter(field => fieldFilters[field]?.trim()).length;
  const filterChips: ErpFilterChip[] = [
    { key: "query", label: t("party.searchLabel"), value: query.trim(), onRemove: () => setQuery("") },
    { key: "status", label: t("party.column.status"), value: statusFilter === "all" ? "" : t(`party.filter.status.${statusFilter}`), onRemove: () => setStatusFilter("all") },
    ...(supportsFieldFilters ? directoryFilterFields.map(field => ({
      key: field, label: fieldFilterLabel(field), value: filterValueLabel(field, fieldFilters[field]?.trim() ?? ""),
      onRemove: () => setFieldFilters(current => ({ ...current, [field]: "" }))
    })) : [])
  ].filter((chip) => chip.value !== "");
  function creditOverview(customer: CustomerView | MemberDirectoryView) {
    return <section className="party-desktop-credit-overview" aria-label={t("party.gestion.section.account")}>
      <h3>{t("party.gestion.section.account")}</h3>
      <dl>
        <div><dt>{t("party.gestion.column.debt")}</dt><dd className={Number(customer.outstandingDebt) > 0 ? "has-debt" : ""}>{customer.outstandingDebt == null ? "—" : Number(customer.outstandingDebt).toLocaleString(locale, { style: "currency", currency: "EUR" })}</dd></div>
        <div><dt>{t("party.gestion.column.creditEnabled")}</dt><dd>{customer.creditEnabled == null ? "—" : t(customer.creditEnabled ? "common.yes" : "common.no")}</dd></div>
        <div><dt>{t("party.gestion.column.creditLimit")}</dt><dd>{customer.creditLimit == null ? t("party.credit.unlimited") : Number(customer.creditLimit).toLocaleString(locale, { style: "currency", currency: "EUR" })}</dd></div>
        <div><dt>{t("party.gestion.column.creditBlocked")}</dt><dd>{customer.creditBlocked == null ? "—" : t(customer.creditBlocked ? "common.yes" : "common.no")}</dd></div>
      </dl>
    </section>;
  }
  const partyEditForm = <form className="product-create-form party-create-form" onSubmit={submit}>
          {app !== "pda" && !isSupplier && selected && creditOverview(isMember && selectedMemberCustomer ? selectedMemberCustomer : selected as CustomerView)}
          <fieldset disabled={!canWrite || saving || centralBusy}>
            <PartyFormFields
              form={form}
              errors={formErrors}
              documentError={documentError}
              identityAction={kind === "customers" && !selectedId && canWrite && <CentralCustomerReuse
                documentType={form.documentType} documentNumber={form.documentNumber}
                session={session} locale={locale} disabled={saving}
                onBusyChange={setCentralBusy}
                onAdopted={(customer) => {
                  setDialogOpen(false); setSelectedRowId(customer.id);
                  void load(false); setStatus(t("party.saveSuccess"));
                }}
              />}
              channels={channels}
              supplier={isSupplier}
              autoFocusName
              grouped={app !== "pda"}
              locale={locale}
              t={t}
              onChange={update}
            />
          </fieldset>
          {status && <p className="product-create-status" role="status">{status}</p>}
          <footer className="filter-actions">{selected && !isMember && allowSafeRetirement && session.permissions.includes("ADMIN") && <button type="button" className="safe-retirement-open" onClick={openSafeRetirement} disabled={saving || centralBusy}>{t("safeManagement.action.retire")}</button>}{selected && customerReceivablesActionVisible(kind, true, session.permissions) && onOpenCustomerReceivables && <button type="button" onClick={() => onOpenCustomerReceivables(isMember ? (selected as MemberDirectoryView).customerId : selected.id)}>{t("party.action.viewReceivables")}</button>}{selected && canWrite && (!isMember || selected.active || selectedMember?.customerActive) && <button type="button" className={selected.active ? "party-deactivate-button" : "party-activate-button"} onClick={() => void toggleActive()} disabled={saving || centralBusy}>{t(selected.active ? "party.action.deactivate" : "party.action.activate")}</button>}<button type="button" disabled={saving || centralBusy} onClick={() => closeDialog()}>{t("common.cancel")}</button>{canWrite && <button type="submit" disabled={saving || centralBusy}>{saving ? t("party.saving") : t("common.save")}</button>}</footer>
        </form>;
  const memberLoyaltyContent = selectedMember ? <>
    <div className="party-member-directory-detail">
      <section className="party-member-customer-summary" aria-label={t("party.members.customerIdentity")}>
        <strong>{selectedMember.fiscalName}</strong>
        <span>{selectedMember.clientId} · {selectedMember.documentType} {selectedMember.documentNumber}</span>
        {app !== "pda" ? <dl className="party-member-profile">
          <div><dt>{t("party.column.phone")}</dt><dd>{selectedMember.phone || "—"}</dd></div>
          <div><dt>{t("party.column.email")}</dt><dd>{selectedMember.email || "—"}</dd></div>
          <div><dt>{t("party.gestion.column.category")}</dt><dd>{selectedMember.categoryName || "—"}</dd></div>
          <div><dt>{t("party.gestion.column.memberSince")}</dt><dd>{new Date(`${selectedMember.memberSince}T12:00:00`).toLocaleDateString(locale)}</dd></div>
          <div><dt>{t("party.gestion.column.address")}</dt><dd>{[selectedMemberCustomer?.address?.address, selectedMemberCustomer?.address?.postalCode, selectedMemberCustomer?.address?.city].filter(Boolean).join(", ") || "—"}</dd></div>
        </dl> : <span>{selectedMember.phone || "-"} · {selectedMember.email || "-"}</span>}
        {!selectedMember.customerActive && <span className="party-member-customer-warning">{t("party.members.customerInactiveHint")}</span>}
      </section>
      {app !== "pda" && selectedMemberCustomer && creditOverview(selectedMemberCustomer)}
      <MemberLoyaltyPanel app={app} memberId={selectedMember.id} session={session} t={t} />
    </div>
    {status && <p className="product-create-status" role="status">{status}</p>}
    <footer className="filter-actions">
      {canWrite && (selectedMember.active || selectedMember.customerActive) && <button type="button" className={selectedMember.active ? "party-deactivate-button" : "party-activate-button"} onClick={() => void toggleActive()} disabled={saving}>{t(selectedMember.active ? "party.action.deactivate" : "party.action.activate")}</button>}
      <button type="button" onClick={() => closeDialog()}>{t("common.cancel")}</button>
    </footer>
  </> : null;
  const memberDialogContent = selectedMember ? app !== "pda" && selectedMemberCustomer ? <>
    <div className="party-member-tabs" role="tablist" aria-label={t("party.members.customerIdentity")}>
      {(["customer", "loyalty"] as const).map(tab => <button key={tab} type="button" role="tab"
        id={`${memberTabId}-${tab}-tab`} aria-controls={`${memberTabId}-${tab}-panel`}
        aria-selected={memberTab === tab} tabIndex={memberTab === tab ? 0 : -1}
        onClick={() => setMemberTab(tab)}
        onKeyDown={event => {
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
          event.preventDefault();
          const next = event.key === "Home" ? "customer" : event.key === "End" ? "loyalty" : tab === "customer" ? "loyalty" : "customer";
          setMemberTab(next);
          document.getElementById(`${memberTabId}-${next}-tab`)?.focus();
        }}>{t(`party.gestion.tab.${tab}`)}</button>)}
    </div>
    <div className="party-member-tab-panel" role="tabpanel" id={`${memberTabId}-customer-panel`}
      aria-labelledby={`${memberTabId}-customer-tab`} hidden={memberTab !== "customer"}>{partyEditForm}</div>
    <div className="party-member-tab-panel" role="tabpanel" id={`${memberTabId}-loyalty-panel`}
      aria-labelledby={`${memberTabId}-loyalty-tab`} hidden={memberTab !== "loyalty"}>{memberLoyaltyContent}</div>
  </> : memberLoyaltyContent : <>
    <div className="party-member-customer-picker" style={app !== "pda" ? { alignContent: "start" } : undefined}>
      <input ref={memberSearchRef} autoFocus aria-label={t("party.members.customerSearch")} type="search" value={memberCandidateQuery} onChange={(event) => { setMemberCandidateQuery(event.target.value); setMemberCandidateId(null); }} placeholder={t("party.members.customerSearch")} />
      {app !== "pda" && <ErpFilterChips locale={locale} focusRef={memberSearchRef} chips={memberCandidateQuery.trim() ? [{
        key: "search", label: t("party.searchLabel"), value: memberCandidateQuery.trim(),
        onRemove: () => { setMemberCandidateQuery(""); setMemberCandidateId(null); }
      }] : []} onClear={() => { setMemberCandidateQuery(""); setMemberCandidateId(null); }} />}
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
      <button type="button" onClick={() => closeDialog()}>{t("common.cancel")}</button>
      {canWrite && <button type="button" onClick={() => void activateSelectedCustomer()} disabled={!memberCandidate || saving}>{saving ? t("party.saving") : t(memberCandidate?.memberUuid ? "party.members.reactivate" : "party.members.convert")}</button>}
    </footer>
  </>;

  return <>
    {confirmation && <ErpConfirmDialog
      title={confirmation === "discard" ? t("common.close") : t(selected?.active ? "party.action.deactivate" : "party.action.activate")}
      message={t(confirmation === "discard" ? "party.confirm.discard" : selected?.active ? "party.confirm.deactivate" : "party.confirm.activate")}
      confirmLabel={t("common.confirm")} cancelLabel={t("common.cancel")}
      onCancel={() => setConfirmation(null)}
      onConfirm={() => { const action = confirmation; setConfirmation(null); if (action === "discard") closeDialog(true); else void toggleActive(true); }}
    />}
    <header className="work-panel-heading stock-panel-heading party-directory-heading">
      <div><h2>{title}</h2><span>{t(`party.${kind}.subtitle`)}</span></div>
      {classicWindow ? <div className="management-record-actions">
        {canWrite && <button type="button" aria-keyshortcuts="F7" disabled={!toolbarEntry} onClick={() => toolbarEntry && openEntry(toolbarEntry)}>F7 {t(`party.${kind}.edit`)}</button>}
        {canWrite && <button type="button" aria-keyshortcuts="F8" onClick={openNew}>F8 {t(`party.${kind}.new`)}</button>}
        {allowSafeRetirement && !isMember && session.permissions.includes("ADMIN") && <button type="button" className="safe-retirement-open" aria-keyshortcuts="F9" disabled={!toolbarEntry} onClick={retireToolbarEntry}>{t("safeManagement.shortcut.retire")} {t("safeManagement.action.retire")}</button>}
      </div> : canWrite && <button type="button" className="stock-add-product-button" onClick={openNew}>{t(`party.${kind}.new`)}</button>}
    </header>
    {headerExtra}
    <div className={`party-directory-toolbar${classicWindow ? " party-directory-toolbar--classic" : ""}${supportsFieldFilters ? " party-directory-toolbar--field-filters" : ""}`}>
      {classicWindow && <label className="party-directory-search-label" htmlFor="party-management-search">{t("party.searchLabel")}</label>}
      <input id={classicWindow ? "party-management-search" : undefined} ref={searchRef} aria-label={t("party.search")} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("party.search")} />
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
      {supportsFieldFilters && <button ref={filterButtonRef} type="button" className="stock-filter-button party-directory-filter-button"
        aria-label={t("salesReport.filter")} aria-expanded={fieldFiltersOpen} aria-controls={fieldFiltersId}
        onClick={() => setFieldFiltersOpen(current => !current)}>
        <img alt="" className="report-action-icon" src={stockFilterIcon} />
        {t("salesReport.filter")}{activeFieldFilterCount > 0 ? ` (${activeFieldFilterCount})` : ""}
      </button>}
      {app === "pda" && (query || statusFilter !== "all") && (
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
      {supportsFieldFilters && fieldFiltersOpen && <div id={fieldFiltersId} className="party-directory-filter-fields"
        role="group" aria-label={t("party.filter.fields")}
        onKeyDown={event => {
          if (event.key === "Escape") {
            event.preventDefault(); event.stopPropagation(); setFieldFiltersOpen(false); filterButtonRef.current?.focus();
          }
        }}>
        {directoryFilterFields.map(field => <label key={field}>
          <span>{fieldFilterLabel(field)}</span>
          {filterOptions(field) ? <ErpSelect aria-label={fieldFilterLabel(field)} value={fieldFilters[field] ?? ""} options={filterOptions(field)!} onChange={value => setFieldFilters(current => ({ ...current, [field]: value }))} /> : <input type="text" maxLength={120} value={fieldFilters[field] ?? ""} onChange={event => {
            const value = event.target.value;
            setFieldFilters(current => ({ ...current, [field]: value }));
          }} />}
        </label>)}
      </div>}
      {app === "pda" && (query || statusFilter !== "all") && <div className="party-directory-active-filters" aria-label={t("party.filter.active")}>
        {query && <button type="button" onClick={() => setQuery("")}>{t("party.searchLabel")}: {query}<span aria-hidden="true"> ×</span></button>}
        {statusFilter !== "all" && <button type="button" onClick={() => setStatusFilter("all")}>{t("party.column.status")}: {t(`party.filter.status.${statusFilter}`)}<span aria-hidden="true"> ×</span></button>}
      </div>}
      {app !== "pda" && <ErpFilterChips locale={locale} chips={filterChips} focusRef={searchRef}
        className="party-directory-active-filters" onClear={clearDirectoryFilters} />}
    </div>
    <div className={`party-directory-table${app !== "pda" ? " party-desktop-table" : ""}`} role="table" aria-label={title}>
      <div className="party-directory-row header" role="row" style={gridStyle}>
        {visibleColumns.map((column) => (
          <TableLayoutHeaderCell
            as="span"
            column={column}
            key={column.key}
            sortDirection={sort.column === column.key ? sort.direction : null}
            sortLabel={`${t("party.sortBy")} ${columnLabel(column.key)}`}
            onSort={managementMode && column.key === "notes" ? undefined : (columnKey) => setSort((current) => ({
              column: columnKey,
              direction: current.column === columnKey && current.direction === "asc" ? "desc" : "asc"
            }))}
            resizeLabel={`${t("stock.columns.resize")} ${columnLabel(column.key)}`}
            onReorder={tableLayout.reorderColumns}
            onMove={tableLayout.moveColumn}
            onResize={tableLayout.resizeColumn}
            onToggleVisibility={app !== "pda" ? tableLayout.toggleColumnVisibility : undefined}
            columnVisibilityOptions={tableLayout.layout.map(candidate => ({ key: candidate.key, label: columnLabel(candidate.key), visible: candidate.visible, disabled: candidate.visible && visibleColumns.length <= 1 }))}
          >
            {columnLabel(column.key)}
          </TableLayoutHeaderCell>
        ))}
      </div>
      {loading && <div className="stock-empty-state">{t("common.loading")}</div>}
      {!loading && loadError && <div className="party-directory-state error" role="alert"><span>{status || t("party.loadError")}</span><button type="button" onClick={() => void load()}>{t("party.retry")}</button></div>}
      {!loading && !loadError && rows.map((entry) => {
        return <button type="button" className={`party-directory-row party-directory-selectable-row${selectedRowId === entry.id ? " selected" : ""}`} role="row" style={gridStyle} key={entry.id}
          ref={selectedRowId === entry.id ? selectedRowRef : undefined}
          onClick={(event) => {
            if (classicWindow) setSelectedRowId(entry.id);
            if (app === "gestion" && classicWindow && kind === "suppliers") { setSelectedRowId(entry.id); if (event.detail === 0) openEntry(entry); return; }
            if (kind !== "customers") { openEntry(entry); return; }
            setSelectedRowId(entry.id);
            // Keyboard activation (Enter/Space) has no pointer click count.
            if (event.detail === 0) setHistoryCustomer(entry as CustomerView);
          }}
          onDoubleClick={() => { if (app === "gestion" && classicWindow && kind === "suppliers") openEntry(entry); if (kind === "customers") { setSelectedRowId(entry.id); setHistoryCustomer(entry as CustomerView); } }}>
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

    {historyCustomer && <CustomerDocumentsDialog
      key={historyCustomer.id}
      customer={customers.find((customer) => customer.id === historyCustomer.id) ?? historyCustomer}
      app={app} locale={locale} session={session} canEdit={canWrite} active={!dialogOpen && !retirementOpen}
      onClose={() => { setHistoryCustomer(null); selectedRowRef.current?.focus({ preventScroll: true }); }}
      onEdit={() => { if (canWrite) openEntry(customers.find((customer) => customer.id === historyCustomer.id) ?? historyCustomer); }}
    />}
    {dialogOpen && <div className={`filter-overlay${classicWindow ? " erp-classic-overlay" : ""}`} role="dialog" aria-modal="true" aria-labelledby="party-form-title">
      <section className={`filter-dialog party-create-dialog${app !== "pda" ? ` party-desktop-dialog party-desktop-dialog--${kind}` : " product-create-dialog"}${classicWindow ? " erp-classic-window" : ""}`} inert={confirmation !== null || undefined}>
        <header className="filter-header"><div><h2 id="party-form-title">{selectedId ? t(`party.${kind}.detail`) : t(`party.${kind}.new`)}</h2><span>{selected && app !== "pda" ? <>{selectedCode} <span className={`party-desktop-status ${selected.active ? "is-active" : "is-inactive"}`}>{t(selected.active ? "party.active" : "party.inactive")}</span></> : selected ? `${selectedCode} · ${selected.active ? t("party.active") : t("party.inactive")}` : isMember ? t("party.members.selectCustomerSubtitle") : t("party.form.subtitle")}</span></div><button type="button" onClick={() => closeDialog()}>{t("common.close")}</button></header>
        {isMember ? memberDialogContent : partyEditForm}
      </section>
    </div>}
    {retirementOpen && selected && !isMember && <SafeRetirementDialog
      classicWindow={classicWindow}
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

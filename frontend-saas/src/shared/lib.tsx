
import { ApiError, extractApiErrorMessage } from "../lib/api";
import { formatCurrency as formatCurrencyValue, formatQuantity as formatQuantityValue } from "../lib/frontend-runtime.mjs";
import type { DashboardData, LicenseSummary, SyncEventView, TaxpayerType, FiscalAddress, FiscalStatusAdmin } from "../lib/types";
import { SaasAdminRoleName, View, LicenseWorkspaceSection, GlobalSearchCriterion, GlobalSearchSuggestion } from "./types";
import { activeLocale, TRANSLATIONS } from "../i18n/index";

export const SAAS_ADMIN_ROLES: Array<{ value: SaasAdminRoleName; label: string; description: string }> = [
  { value: "ADMIN", label: "ADMIN", description: "Gestion completa del SaaS" },
  { value: "VIEWER", label: "VIEWER", description: "Solo consulta de datos admin" },
  { value: "SUPPORT", label: "SUPPORT", description: "Soporte tecnico y codigos de enlace" },
  { value: "BILLING", label: "BILLING", description: "Licencias, renovaciones y facturacion" },
  { value: "AUDITOR", label: "AUDITOR", description: "Solo auditoria y lectura" }
];

export function emptyFiscalAddress(): FiscalAddress {
  return { linea1: "", ciudad: "", codigoPostal: "", provincia: "", pais: "ES" };
}

export function taxpayerLabel(taxpayerType: TaxpayerType, t: (key: string) => string) {
  return taxpayerType === "SOCIEDAD" ? t("verifactuCompany") : t("verifactuSelfEmployed");
}

export function fiscalModeLabel(mode: string, t: (key: string) => string) {
  if (mode === "VERIFACTU") return t("fiscalVerifactu");
  if (mode === "NO_VERIFACTU") return t("fiscalNoVerifactu");
  if (mode === "PRE_SIF") return t("fiscalPreSif");
  if (mode === "MIXED") return t("fiscalMixed");
  return t("fiscalUnknown");
}

export function fiscalStateLabel(state: string, t: (key: string) => string) {
  if (state === "ACTIVE") return t("fiscalActive");
  if (state === "PENDING") return t("fiscalPending");
  if (state === "DUE_REVIEW") return t("fiscalDueReview");
  if (state === "MIXED") return t("fiscalMixed");
  return t("fiscalUnknown");
}

export function licenseStatusPresentation(
  status: LicenseSummary["status"],
  t: (key: string) => string
): { label: string; tone: "ok" | "warning" } {
  if (status === "CADUCADA") return { label: t("expiredStatus"), tone: "warning" };
  if (status === "BLOQUEADA_MANUAL") return { label: t("blockedStatus"), tone: "warning" };
  return { label: t("valid"), tone: "ok" };
}

export const VALID_VIEWS: View[] = ["dashboard", "companies", "stores", "create-license", "failures", "access", "integrations", "licenses", "sync", "fiscal", "fiscal-policy", "users", "audit", "support", "health", "billing", "outbox", "reports"];

export function readViewFromLocation(): View {
  const candidate = window.location.hash.replace(/^#\/?/, "").split("/")[0] as View;
  return VALID_VIEWS.includes(candidate) ? candidate : "dashboard";
}

export function readLicenseWorkspaceSection(): LicenseWorkspaceSection {
  const [, section] = window.location.hash.replace(/^#\/?/, "").split("/");
  return section === "licenses" || section === "verifactu" || section === "companies"
    ? section
    : "companies";
}

export function viewTitle(view: View, t: (key: string) => string) {
  return {
    companies: t("companies"),
    stores: t("stores"),
    "create-license": t("createLicense"),
    failures: t("failures"),
    access: t("access"),
    integrations: t("integrations"),
    dashboard: t("dashboard"),
    licenses: t("activeLicenses"),
    sync: t("sync"),
    fiscal: t("fiscal"),
    "fiscal-policy": t("verifactuPolicy"),
    users: t("adminUsers"),
    support: t("supportCenter"),
    health: t("customerHealth"),
    billing: t("billing"),
    outbox: t("outboxRecovery"),
    reports: t("reports"),
    audit: t("audit")
  }[view];
}

export function uniqueCompanies(licenses: LicenseSummary[]) {
  return Array.from(new Map(licenses.map((license) => [license.companyId, license])).values()).map((license) => ({
    companyId: license.companyId,
    companyName: license.companyName
  }));
}

export function riskLabel(riskLevel: string, t: (key: string) => string) {
  if (riskLevel === "DANGER") return t("riskDanger");
  if (riskLevel === "WARNING") return t("riskWarning");
  return t("riskOk");
}

export function billingStatusLabel(status: string, t: (key: string) => string) {
  const normalized = status.toUpperCase();
  if (normalized === "PAGADO") return t("paid");
  if (normalized === "PENDIENTE") return t("pending");
  if (normalized === "IMPAGADO" || normalized === "VENCIDO") return t("overdue");
  return status;
}

export function parseAmount(value: string | null | undefined) {
  if (!value) return 0;
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

export function isPositiveAmount(value: string | null | undefined) {
  return parseAmount(value) > 0;
}

export function isValidUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

export function formatCurrency(value: string | number, currency = "EUR") {
  return formatCurrencyValue(value, currency, activeLocale);
}

export function formatQuantity(value: string | number) {
  return formatQuantityValue(value, activeLocale);
}

export function formatMoney(value: string | number) {
  return formatCurrency(value, "EUR");
}

export function buildGlobalSearchSuggestions(
  data: DashboardData,
  storeIndex: FiscalStatusAdmin[],
  criterion: GlobalSearchCriterion,
  query: string
): GlobalSearchSuggestion[] {
  const normalized = normalizeSearch(query);
  if (!normalized) return [];

  if (criterion === "company") {
    return Array.from(new Map(data.licenses.map((license) => [license.companyId, license])).values())
      .filter((license) => normalizeSearch(license.companyName).includes(normalized))
      .map((license) => ({
        key: license.companyId,
        value: license.companyName,
        label: license.companyName,
        detail: license.taxId
      }))
      .slice(0, 8);
  }

  if (criterion === "taxId") {
    return Array.from(new Map(data.licenses.map((license) => [license.companyId, license])).values())
      .filter((license) => normalizeSearch(license.taxId).includes(normalized))
      .map((license) => ({
        key: license.companyId,
        value: license.taxId,
        label: license.taxId,
        detail: license.companyName
      }))
      .slice(0, 8);
  }

  const stores = new Map<string, GlobalSearchSuggestion>();
  storeIndex.forEach((store) => {
    stores.set(store.storeId, {
      key: store.storeId,
      value: store.storeName || store.storeId,
      label: store.storeName || store.storeId,
      detail: store.companyName
    });
  });
  data.installations.forEach((installation) => {
    if (stores.has(installation.storeId)) return;
    const company = data.licenses.find((license) => license.companyId === installation.companyId);
    stores.set(installation.storeId, {
      key: installation.storeId,
      value: installation.storeId,
      label: installation.storeId,
      detail: company?.companyName ?? installation.companyId
    });
  });
  return Array.from(stores.values())
    .filter((store) => normalizeSearch(store.label + " " + store.detail + " " + store.key).includes(normalized))
    .slice(0, 8);
}

export function filterDashboardData(
  data: DashboardData,
  query: string,
  criterion: GlobalSearchCriterion,
  storeIndex: FiscalStatusAdmin[]
): DashboardData {
  const normalized = normalizeSearch(query);
  if (!normalized) return data;

  const companyIds = new Set<string>();
  const storeIds = new Set<string>();

  if (criterion === "company" || criterion === "taxId") {
    data.licenses.forEach((license) => {
      const candidate = criterion === "company" ? license.companyName : license.taxId;
      if (normalizeSearch(candidate).includes(normalized)) companyIds.add(license.companyId);
    });
  } else {
    storeIndex.forEach((store) => {
      if (normalizeSearch(store.storeName + " " + store.storeId).includes(normalized)) {
        companyIds.add(store.companyId);
        storeIds.add(store.storeId);
      }
    });
    data.installations.forEach((installation) => {
      if (normalizeSearch(installation.storeId).includes(normalized)) {
        companyIds.add(installation.companyId);
        storeIds.add(installation.storeId);
      }
    });
    data.events.forEach((event) => {
      if (normalizeSearch(event.storeId).includes(normalized)) {
        companyIds.add(event.companyId);
        storeIds.add(event.storeId);
      }
    });
  }

  const matchingLicenses = data.licenses.filter((license) => companyIds.has(license.companyId));
  const licenseReferences = new Set(matchingLicenses.map((license) => license.licenseReference));
  const matchingInstallations = data.installations.filter((installation) =>
    companyIds.has(installation.companyId) ||
    licenseReferences.has(installation.licenseReference) ||
    storeIds.has(installation.storeId)
  );
  matchingInstallations.forEach((installation) => {
    companyIds.add(installation.companyId);
    licenseReferences.add(installation.licenseReference);
  });

  return {
    ...data,
    licenses: data.licenses.filter((license) => companyIds.has(license.companyId) || licenseReferences.has(license.licenseReference)),
    installations: data.installations.filter((installation) => companyIds.has(installation.companyId) || licenseReferences.has(installation.licenseReference)),
    events: data.events.filter((event) => companyIds.has(event.companyId) || storeIds.has(event.storeId)),
    audit: data.audit.filter((item) =>
      [item.username, item.action, item.targetType, item.targetId].some((value) => normalizeSearch(value).includes(normalized))
    )
  };
}

export function operationalAlerts(data: DashboardData, t: (key: string) => string) {
  const alerts: Array<{ tone: "warning" | "danger"; title: string; detail: string }> = [];
  const soon = data.licenses.filter((license) => license.status === "VALIDA" && daysUntil(license.validUntil) <= 30);
  const blocked = data.licenses.filter((license) => license.status === "BLOQUEADA_MANUAL");
  const stale = data.installations.filter((installation) => installation.active
    && (!installation.lastValidatedAt || hoursSince(installation.lastValidatedAt) > 48));

  soon.slice(0, 3).forEach((license) => {
    alerts.push({
      tone: "warning",
      title: t("expiringLicenseAlert"),
      detail: `${license.companyName} - ${license.licenseReference} - ${formatDate(license.validUntil)}`
    });
  });
  blocked.slice(0, 3).forEach((license) => {
    alerts.push({
      tone: "danger",
      title: t("blockedLicenseAlert"),
      detail: `${license.companyName} - ${license.licenseReference}`
    });
  });
  stale.slice(0, 3).forEach((installation) => {
    alerts.push({
      tone: "warning",
      title: t("staleInstallationAlert"),
      detail: `${installation.installationReference} - ${installation.lastValidatedAt ? formatDate(installation.lastValidatedAt) : "pendiente"}`
    });
  });

  return alerts;
}

export function auditActionLabel(action: string) {
  const labels: Record<string, string> = {
    CREATE_COMPANY: "Empresa creada",
    UPDATE_COMPANY: "Empresa actualizada",
    CREATE_LICENSE: "Licencia creada",
    RENEW_LICENSE: "Licencia renovada",
    BLOCK_LICENSE: "Licencia bloqueada",
    UNBLOCK_LICENSE: "Licencia desbloqueada",
    REGENERATE_PAIRING_CODE: "Codigo de enlace regenerado",
    CREATE_ADMIN_USER: "Usuario admin creado",
    UPDATE_ADMIN_PASSWORD: "Password admin actualizada",
    CHANGE_ADMIN_PASSWORD: "Password admin actualizada",
        DELETE_ADMIN_USER: "Usuario admin desactivado",
        DEACTIVATE_ADMIN_USER: "Usuario admin desactivado",
        ACTIVATE_ADMIN_USER: "Usuario admin activado",
        UPDATE_COMPANY_OPERATIONS: "Datos SaaS de empresa actualizados",
    CREATE_SUPPORT_TICKET: "Ticket de soporte creado",
    UPDATE_SUPPORT_TICKET: "Ticket de soporte actualizado",
    UPDATE_VERIFACTU_ACTIVATION_POLICY: "Politica de activacion de VeriFactu actualizada"
  };
  return labels[action] ?? action.replaceAll("_", " ").toLowerCase().replace(/^\w/, (value) => value.toUpperCase());
}

export function ticketStatusLabel(status: string, t: (key: string) => string) {
  const labels: Record<string, string> = {
    ABIERTO: t("open"),
    EN_CURSO: t("inProgress"),
    RESUELTO: t("resolve")
  };
  return labels[status] ?? status;
}

export function ticketPriorityLabel(priority: string, t: (key: string) => string) {
  const labels: Record<string, string> = {
    NORMAL: t("normal"),
    ALTA: t("high"),
    URGENTE: t("urgent")
  };
  return labels[priority] ?? priority;
}

export function normalizeSearch(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

export function uniqueStrings(values: string[]) {
  return Array.from(new Set(values.filter(Boolean))).sort((left, right) => left.localeCompare(right));
}

export function daysUntil(value: string) {
  return Math.ceil((new Date(value).getTime() - Date.now()) / 86_400_000);
}

export function hoursSince(value: string) {
  return (Date.now() - new Date(value).getTime()) / 3_600_000;
}

export function isToday(value: string) {
  const date = new Date(value);
  const now = new Date();
  return date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
}

export function latestDate(values: string[]) {
  return values.reduce<string | null>((latest, value) => {
    if (!latest || new Date(value).getTime() > new Date(latest).getTime()) return value;
    return latest;
  }, null);
}

export function eventSummary(event: SyncEventView) {
  const payload = event.payload;
  const company = stringPayload(payload.empresa);
  if (event.entityType === "DOCUMENTO") {
    return `${company} - ${stringPayload(payload.numero)} - ${stringPayload(payload.cliente)} - ${stringPayload(payload.total)} EUR`;
  }
  if (event.entityType === "STOCK_MOVEMENT") {
    return `${company} - ${stringPayload(payload.productoId)} - ${stringPayload(payload.cantidad)} uds - ${stringPayload(payload.motivo)}`;
  }
  if (event.entityType === "CIERRE_CAJA") {
    return `${company} - ${stringPayload(payload.terminal)} - total ${stringPayload(payload.totalCobrado)} EUR - descuadre ${stringPayload(payload.descuadre)}`;
  }
  return company || "Evento sincronizado";
}

export function stringPayload(value: unknown) {
  return value == null ? "" : String(value);
}

export function formatDate(value: string) {
  return new Intl.DateTimeFormat(activeLocale, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    timeZoneName: "short"
  }).format(new Date(value));
}

export function toLocalInput(date: Date) {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

export function parseLocalDateTime(value: string) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function parsePickerDate(value: string, dateOnly: boolean) {
  if (dateOnly) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
  }
  return parseLocalDateTime(value);
}

export function formatPickerDate(value: string, dateOnly: boolean) {
  const date = parsePickerDate(value, dateOnly);
  if (!date) return "";
  return new Intl.DateTimeFormat(activeLocale, dateOnly
    ? { dateStyle: "medium" }
    : { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function toDateInput(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function monthStart(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

export function addMonths(date: Date, amount: number) {
  return new Date(date.getFullYear(), date.getMonth() + amount, 1);
}

export function calendarDays(month: Date) {
  const mondayOffset = (month.getDay() + 6) % 7;
  const first = new Date(month.getFullYear(), month.getMonth(), 1 - mondayOffset);
  return Array.from({ length: 42 }, (_, index) =>
    new Date(first.getFullYear(), first.getMonth(), first.getDate() + index));
}

export function calendarWeekDays() {
  const monday = new Date(2026, 0, 5);
  return Array.from({ length: 7 }, (_, index) =>
    new Intl.DateTimeFormat(activeLocale, { weekday: "short" })
      .format(new Date(2026, 0, monday.getDate() + index))
      .replace(".", ""));
}

export function sameCalendarDay(left: Date, right: Date) {
  return left.getFullYear() === right.getFullYear()
    && left.getMonth() === right.getMonth()
    && left.getDate() === right.getDate();
}

export function addYears(date: Date, years: number) {
  const next = new Date(date);
  next.setFullYear(next.getFullYear() + years);
  return next;
}

export function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

export async function copyText(text: string) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Fall back to a temporary selection for browsers that block Clipboard API.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  textarea.style.top = "0";
  const focused = document.activeElement;
  document.body.appendChild(textarea);
  try {
    textarea.select();
    if (!document.execCommand("copy")) throw new Error("Copy command rejected");
  } finally {
    const restoreFocus = document.activeElement === textarea;
    textarea.remove();
    if (restoreFocus && focused instanceof HTMLElement && focused.isConnected) focused.focus();
  }
}

export function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if ([502, 503, 504].includes(error.status)) return TRANSLATIONS.es.serviceUnavailable;
    if (error.status >= 500) return TRANSLATIONS.es.internalServerError;
    const actionableMessage = extractApiErrorMessage(error.message);
    if (actionableMessage) return actionableMessage;
    if (error.status === 401) return TRANSLATIONS.es.invalidCredentials;
    if (error.status === 403) return TRANSLATIONS.es.forbiddenAction;
    if (error.status === 404) return TRANSLATIONS.es.resourceNotFound;
    if (error.status === 400) return "La solicitud no es válida.";
    if (error.status === 409) return "La operación entra en conflicto con el estado actual.";
    if (error.status === 429) return "Demasiadas solicitudes. Espera un momento y vuelve a intentarlo.";
    return cleanTechnicalText(error.message);
  }
  if (error instanceof TypeError) return TRANSLATIONS.es.networkError;
  if (error instanceof Error) return cleanTechnicalText(error.message);
  return "Operacion no completada";
}

export function cleanTechnicalText(value: string) {
  const text = value.trim();
  if (!text) return "Operacion no completada";
  if (text.startsWith("{") || text.includes("\"timestamp\"")) return TRANSLATIONS.es.backendNotUpdated;
  if (text.includes("Failed to fetch") || text.includes("NetworkError")) return TRANSLATIONS.es.networkError;
  return text;
}

export function isMissingPhase3Endpoint(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

export function isRecoverableBackendDataError(error: unknown) {
  return error instanceof ApiError && error.status >= 500;
}

export function userManagementErrorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 403) {
    return "No tienes permiso para gestionar usuarios. Entra con un usuario ADMIN.";
  }
  return errorMessage(error);
}

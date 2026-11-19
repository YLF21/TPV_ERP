import { useEffect, useLayoutEffect } from "react";
import type { Credentials } from "../../lib/types";

type Filters = Record<string, string>;
type Context = { common: Filters; sections: Record<string, Filters> };
const commonKeys = ["from", "to", "comparison", "comparisonFrom", "comparisonTo", "companyId", "storeId", "province"];
const permitted = new Set([...commonKeys, "groupBy", "page", "sort", "direction", "currency", "chartCurrency", "search", "productId", "productCode", "productName", "type", "warehouseId", "warehouseName", "active", "mode", "detail", "selectedStore", "snapshotId", "comparisonSnapshotId", "comparisonCurrentSnapshotId", "return", "movementGroupBy", "movementType", "installationId", "overview", "ledgerPage", "summaryPage", "movementGroup", "openedCompanyId", "openedStoreId", "warehouseSnapshotId", "warehousePage", "catalogSort", "catalogDirection", "stockSort", "stockDirection", "changedOnly", "details", "syncTypesExpanded"]);
const volatile = new Map<string, Context>();

function contextKey(credentials: Credentials) {
  return `espos.analytics.context.v1.${encodeURIComponent(credentials.username)}.${credentials.mode}.${credentials.companyId ?? ""}`;
}
function clean(value: unknown): Filters {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value).filter(([key, entry]) => permitted.has(key) && typeof entry === "string" && entry.length < 4000)) as Filters;
}
function read(credentials: Credentials): Context {
  const key = contextKey(credentials);
  const memory = volatile.get(key);
  if (memory) return memory;
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "null");
    if (value?.sections && typeof value.sections === "object") return {
      common: clean(value.common),
      sections: Object.fromEntries(Object.entries(value.sections).slice(0, 30).map(([section, fields]) => [section, clean(fields)])),
    };
  } catch { /* The current session still works without browser storage. */ }
  return volatile.get(key) ?? { common: {}, sections: {} };
}
function routeSection(hash = window.location.hash) {
  const path = hash.split("?")[0];
  if (path === "#/dashboard") return "summary";
  if (path === "#/reports") return "sales";
  const value = path.split("/")[2];
  return value === "stock" ? "inventory" : value;
}

/** Only applied filters are remembered. A dated URL is an explicit scope, including omitted (all) scopes. */
export function readAnalyticsFilters(credentials: Credentials, section: string, options: { shareScope?: boolean } = {}): Filters {
  const context = read(credentials);
  const saved = context.sections[section] ?? {};
  if (options.shareScope === false) return saved;
  const changed = commonKeys.some(key => key in saved && key in context.common && saved[key] !== context.common[key]);
  const result: Filters = { ...saved, ...context.common, ...(changed ? { page: "0" } : {}) };
  const changedStore = ["companyId", "storeId"].some(field => field in context.common && (saved[field] ?? "") !== context.common[field]);
  if (changedStore) for (const field of ["productId", "productCode", "productName", "warehouseId", "warehouseName", "snapshotId", "warehouseSnapshotId", "comparisonSnapshotId", "comparisonCurrentSnapshotId", "openedCompanyId", "openedStoreId"]) delete result[field];
  if (routeSection() !== section || !window.location.hash.includes("?")) return section === "documents" ? { ...context.common } : result;
  const linked = clean(Object.fromEntries(new URLSearchParams(window.location.hash.split("?")[1])));
  if (!Object.keys(linked).length) return result;
  // An explicit drill-down must never inherit a product, currency or store from a different query.
  const period = Object.fromEntries(commonKeys.filter(field => !["companyId", "storeId", "province"].includes(field))
    .filter(field => field in context.common).map(field => [field, context.common[field]]));
  return { ...period, companyId: "", storeId: "", province: "", ...linked };
}

export function useAnalyticsContext(credentials: Credentials, section: string, applied: Filters,
  options: { syncUrl?: boolean; shareScope?: boolean; route?: string; urlFilters?: Filters; enabled?: boolean } = {}) {
  const serialized = JSON.stringify(clean(applied));
  const serializedUrl = options.urlFilters ? JSON.stringify(clean(options.urlFilters)) : serialized;
  const route = options.route ?? section;
  const key = contextKey(credentials);
  useEffect(() => {
    if (options.enabled === false) return;
    const filters = JSON.parse(serialized) as Filters;
    const current = read(credentials);
    const common = { ...current.common };
    if (section !== "documents" && options.shareScope !== false) for (const field of commonKeys) if (field in filters) common[field] = filters[field];
    const sections = { ...current.sections };
    delete sections[section];
    const next = { common, sections: Object.fromEntries([...Object.entries(sections), [section, filters]].slice(-30)) };
    volatile.set(key, next);
    try { localStorage.setItem(key, JSON.stringify(next)); } catch { /* Keep in-memory preferences. */ }
    if (options.syncUrl !== false && routeSection() === route) {
      const query = new URLSearchParams(JSON.parse(serializedUrl) as Filters);
      const path = window.location.hash.split("?")[0];
      window.history.replaceState(window.history.state, "", `${path}?${query}`);
    }
  }, [key, section, serialized, serializedUrl, route, options.syncUrl, options.shareScope, options.enabled]);
}

export function analyticsHref(section: string, filters: Filters) {
  return `#/reports/${section === "stock" ? "inventory" : section}?${new URLSearchParams(clean(filters))}`;
}

type Position = { top: number; left: number; focus: string; tables: { name: string; left: number }[] };
const positions = new Map<string, Position>();
function focusKey(element: Element | null) {
  if (!(element instanceof HTMLElement)) return "";
  return [element.tagName, element.getAttribute("aria-label") ?? "", element.getAttribute("href") ?? "", element.textContent?.trim().slice(0, 150) ?? ""].join("|");
}

function positionPath() {
  const section = routeSection();
  const [path, query] = window.location.hash.split("?");
  if (section === "movements" && new URLSearchParams(query).get("detail") === "ledger") return "movements-ledger";
  return section ?? path;
}
/** Capture before React removes the old view; restore after its asynchronous table has appeared. */
export function useAnalyticsNavigationPosition(credentials: Credentials) {
  const key = contextKey(credentials);
  useLayoutEffect(() => {
    const panel = document.querySelector<HTMLElement>(".main-panel");
    if (!panel) return;
    const path = positionPath();
    const position = positions.get(key + path);
    let observer: ResizeObserver | undefined;
    let mutation: MutationObserver | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let lastFocus = "";
    let leaving: Position | undefined;
    let interacted = false;
    function snapshot(): Position {
      const active = panel!.contains(document.activeElement) ? focusKey(document.activeElement) : "";
      return { top: panel!.scrollTop, left: panel!.scrollLeft, focus: active || lastFocus,
        tables: [...panel!.querySelectorAll<HTMLElement>(".table-wrap")].map(table => ({ name: table.getAttribute("aria-label") ?? table.querySelector("table")?.getAttribute("aria-label") ?? "", left: table.scrollLeft })) };
    }
    const stopRestore = () => { observer?.disconnect(); mutation?.disconnect(); };
    const userInteraction = () => { interacted = true; stopRestore(); };
    const rememberFocus = (event: Event) => { lastFocus = focusKey(event.target as Element); };
    const rememberNavigation = (event: Event) => {
      const element = (event.target as Element)?.closest?.<HTMLElement>('a[href^="#/"],.reports-sections button');
      if (element) { lastFocus = focusKey(element); leaving = { ...snapshot(), focus: lastFocus }; }
    };
    panel.addEventListener("focusin", rememberFocus);
    panel.addEventListener("click", rememberNavigation, true);
    for (const event of ["pointerdown", "keydown", "wheel"]) panel.addEventListener(event, userInteraction, { passive: true });
    const restore = () => {
      if (!position) return;
      const focusRestored = !position.focus || [...panel.querySelectorAll<HTMLElement>("a,button,input,select,summary,[tabindex]")].some(element => {
        if (focusKey(element) !== position.focus || element.matches(":disabled") || element.checkVisibility?.({ visibilityProperty: true }) === false) return false;
        element.focus({ preventScroll: true });
        return document.activeElement === element;
      });
      panel.scrollTo({ top: position.top, left: position.left });
      let tablesReady = true;
      for (const saved of position.tables.filter(table => table.left > 0)) {
        if (![...panel.querySelectorAll<HTMLElement>(".table-wrap")].some(table => (table.getAttribute("aria-label") ?? table.querySelector("table")?.getAttribute("aria-label") ?? "") === saved.name)) tablesReady = false;
      }
      for (const table of panel.querySelectorAll<HTMLElement>(".table-wrap")) {
        const left = position.tables.find(item => item.name === (table.getAttribute("aria-label") ?? table.querySelector("table")?.getAttribute("aria-label") ?? ""))?.left;
        if (left != null) { table.scrollLeft = left; if (table.scrollLeft < left) tablesReady = false; }
      }
      if (panel.scrollTop >= position.top && tablesReady && focusRestored) stopRestore();
    };
    if (position) {
      observer = new ResizeObserver(restore);
      if (panel.firstElementChild) observer.observe(panel.firstElementChild);
      for (const child of panel.querySelectorAll(".view-grid")) observer.observe(child);
      mutation = new MutationObserver(restore);
      mutation.observe(panel, { childList: true, subtree: true });
      restore();
      timer = setTimeout(stopRestore, 5000);
    } else panel.scrollTo({ top: 0, left: 0 });
    return () => {
      stopRestore();
      if (timer) clearTimeout(timer);
      panel.removeEventListener("focusin", rememberFocus);
      panel.removeEventListener("click", rememberNavigation, true);
      for (const event of ["pointerdown", "keydown", "wheel"]) panel.removeEventListener(event, userInteraction);
      // StrictMode rehearses mount cleanup before asynchronous rows exist; keep the previous destination in that case.
      if (leaving || interacted || positionPath() !== path) positions.set(key + path, leaving ?? snapshot());
      if (positions.size > 60) positions.delete(positions.keys().next().value!);
    };
  }, [key]);
}

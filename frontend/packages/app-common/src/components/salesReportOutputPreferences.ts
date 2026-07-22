import type { AppKind, TerminalContext } from "../types";

export type SalesReportDensity = "comfortable" | "compact";
export type SalesReportPrimaryAction = "menu" | "print" | "pdf" | "excel";

export type SalesReportOutputPreferences = {
  density: SalesReportDensity;
  primaryAction: SalesReportPrimaryAction;
};

export const defaultSalesReportOutputPreferences: SalesReportOutputPreferences = {
  density: "comfortable",
  primaryAction: "menu"
};

export function salesReportOutputPreferencesStorageKey(
  app: AppKind,
  username: string,
  terminalContext: TerminalContext
) {
  const terminalKey = terminalContext.terminalId || terminalContext.terminalCode || "unknown";
  return `tpv-erp:${app}:terminal:${terminalKey}:user:${username || "anonymous"}:report-output`;
}

function isDensity(value: unknown): value is SalesReportDensity {
  return value === "comfortable" || value === "compact";
}

function isPrimaryAction(value: unknown): value is SalesReportPrimaryAction {
  return value === "menu" || value === "print" || value === "pdf" || value === "excel";
}

function browserStorage() {
  if (typeof window === "undefined") {
    return undefined;
  }
  return window.localStorage;
}

export function readSalesReportOutputPreferences(
  app: AppKind,
  username: string,
  terminalContext: TerminalContext,
  storage = browserStorage()
): SalesReportOutputPreferences {
  if (!storage) {
    return { ...defaultSalesReportOutputPreferences };
  }
  try {
    const saved = JSON.parse(storage.getItem(
      salesReportOutputPreferencesStorageKey(app, username, terminalContext)
    ) ?? "null") as Partial<SalesReportOutputPreferences> | null;
    return {
      density: isDensity(saved?.density) ? saved.density : defaultSalesReportOutputPreferences.density,
      primaryAction: isPrimaryAction(saved?.primaryAction)
        ? saved.primaryAction
        : defaultSalesReportOutputPreferences.primaryAction
    };
  } catch {
    return { ...defaultSalesReportOutputPreferences };
  }
}

export function saveSalesReportOutputPreferences(
  app: AppKind,
  username: string,
  terminalContext: TerminalContext,
  preferences: SalesReportOutputPreferences,
  storage = browserStorage()
) {
  if (!storage) {
    return;
  }
  storage.setItem(
    salesReportOutputPreferencesStorageKey(app, username, terminalContext),
    JSON.stringify(preferences)
  );
}

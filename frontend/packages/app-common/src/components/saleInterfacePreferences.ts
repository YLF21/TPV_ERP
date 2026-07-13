import type { AppKind, TerminalContext } from "../types";

const touchModeValue = "enabled";

export function saleInterfaceTouchModeStorageKey(app: AppKind, terminalContext: TerminalContext) {
  const terminalKey = terminalContext.terminalId || terminalContext.terminalCode || "unknown";
  return `tpv-erp:${app}:terminal:${terminalKey}:sale-interface:touch-mode`;
}

export function readSaleInterfaceTouchMode(app: AppKind, terminalContext: TerminalContext) {
  if (typeof window === "undefined") {
    return false;
  }

  return window.localStorage.getItem(saleInterfaceTouchModeStorageKey(app, terminalContext)) === touchModeValue;
}

export function saveSaleInterfaceTouchMode(
  app: AppKind,
  terminalContext: TerminalContext,
  enabled: boolean
) {
  if (typeof window === "undefined") {
    return;
  }

  const key = saleInterfaceTouchModeStorageKey(app, terminalContext);
  if (enabled) {
    window.localStorage.setItem(key, touchModeValue);
  } else {
    window.localStorage.removeItem(key);
  }
}

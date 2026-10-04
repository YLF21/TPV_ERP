export type CanonicalSaleSettingsDestination =
  | "account"
  | "visualization"
  | "printers"
  | "devices"
  | "connection"
  | "cash"
  | "diagnostics";

// Preserve callers of the previous settings destinations while presenting one menu.
export type SaleSettingsDestination = CanonicalSaleSettingsDestination
  | "language"
  | "security"
  | "reports"
  | "sale"
  | "printing";

export function normalizeSaleSettingsDestination(destination: SaleSettingsDestination): CanonicalSaleSettingsDestination {
  if (destination === "language" || destination === "security") return "account";
  if (destination === "reports" || destination === "sale") return "visualization";
  if (destination === "printing") return "printers";
  return destination;
}

// Let the mounted settings screen apply its own unsaved-change guard to Escape.
export function requestSaleSettingsBack(): boolean {
  return !window.dispatchEvent(new Event("tpv-sale-settings-back", { cancelable: true }));
}

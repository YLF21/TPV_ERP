import type { View } from "../shared/types";

// Deferred modules stay available for future releases, disabled in the current build.
export const SUPERVISION_ENABLED = import.meta.env.VITE_SAAS_SUPERVISION_ENABLED === "true";
const supervisionViews = new Set<string>(["health", "failures", "sync", "fiscal", "outbox", "support"]);

export function isViewEnabled(view: string): boolean {
  return SUPERVISION_ENABLED || !supervisionViews.has(view);
}

export function availableView(view: View): View {
  return isViewEnabled(view) ? view : "dashboard";
}

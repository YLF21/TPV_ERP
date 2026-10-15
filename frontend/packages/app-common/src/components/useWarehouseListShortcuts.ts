import { useEffect, type RefObject } from "react";

export const WAREHOUSE_LIST_SHORTCUTS = { create: "F5", excel: "F6", pdf: "F7" } as const;

/** Route warehouse list shortcuts through the visible buttons and their existing guards. */
export function useWarehouseListShortcuts(
  rootRef: RefObject<HTMLElement | null>,
  enabled: boolean,
  blocked = false
) {
  useEffect(() => {
    if (!enabled || blocked) return;
    function handleShortcut(event: KeyboardEvent) {
      if (event.defaultPrevented || event.repeat || event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return;
      if (!Object.values(WAREHOUSE_LIST_SHORTCUTS).some((key) => key === event.key)) return;
      const root = rootRef.current;
      if (!root || root.closest('[hidden], [inert], [aria-hidden="true"]') || document.querySelector('[aria-modal="true"]')) return;
      const button = root.querySelector<HTMLButtonElement>(`button[aria-keyshortcuts="${event.key}"]`);
      if (!button || button.closest('[hidden], [inert], [aria-hidden="true"]')) return;
      event.preventDefault();
      event.stopPropagation();
      if (!button.disabled) button.click();
    }
    window.addEventListener("keydown", handleShortcut, true);
    return () => window.removeEventListener("keydown", handleShortcut, true);
  }, [rootRef, enabled, blocked]);
}

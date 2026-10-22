import type { RefObject, KeyboardEvent as ReactKeyboardEvent } from "react";
import { useTableArrowRepeat } from "./useTableArrowRepeat";
import { ensureTableRowVisible } from "./ensureTableRowVisible";
import { useTableInitialFocus } from "./useTableInitialFocus";

/** Navigate the row cursor without activating buttons or changing a multi-selection. */
export function useTableRowArrowFocus(
  containerRef: RefObject<HTMLElement | null>,
  rowSelector: string,
  enabled = true,
  scopeKey: unknown = "",
) {
  useTableInitialFocus(containerRef, rowSelector, { enabled, scopeKey });
  const repeatArrow = useTableArrowRepeat((direction) => {
    const container = containerRef.current;
    if (!container) return false;
    const rows = Array.from(container.querySelectorAll<HTMLElement>(rowSelector));
    const index = rows.findIndex((row) => row === document.activeElement);
    if (index < 0) return false;
    const next = Math.max(0, Math.min(rows.length - 1, index + (direction === "ArrowDown" ? 1 : -1)));
    if (next === index) return false;
    const row = rows[next];
    row.focus({ preventScroll: true });
    ensureTableRowVisible(container, row);
    return true;
  }, enabled);

  return (event: ReactKeyboardEvent<HTMLElement>) => {
    if (!enabled || event.target !== event.currentTarget || (event.key !== "ArrowUp" && event.key !== "ArrowDown")) return;
    if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    repeatArrow(event);
  };
}

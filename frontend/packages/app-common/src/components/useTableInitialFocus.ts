import { useEffect, useLayoutEffect, useRef, type RefObject } from "react";
import { ensureTableRowVisible } from "./ensureTableRowVisible";

type Options = {
  enabled?: boolean;
  ready?: boolean;
  scopeKey?: unknown;
  onSelect?: (row: HTMLElement) => void;
  focusTarget?: "row" | "container" | (() => HTMLElement | null);
};

/** Prepare the first data row once on entry, without activating it or interrupting typing. */
export function useTableInitialFocus(
  containerRef: RefObject<HTMLElement | null>,
  rowSelector: string,
  { enabled = true, ready = true, scopeKey = "", onSelect, focusTarget = "row" }: Options = {},
) {
  const entry = useRef({ scopeKey, initialized: false, mayFocus: true });
  if (!Object.is(entry.current.scopeKey, scopeKey)) {
    entry.current = { scopeKey, initialized: false, mayFocus: true };
  }

  useEffect(() => {
    const interacted = (event: Event) => {
      if (entry.current.initialized) return;
      if (event instanceof KeyboardEvent && ["ArrowUp", "ArrowDown"].includes(event.key)
        && event.target === containerRef.current) return;
      entry.current.mayFocus = false;
    };
    document.addEventListener("pointerdown", interacted, true);
    document.addEventListener("keydown", interacted, true);
    return () => {
      document.removeEventListener("pointerdown", interacted, true);
      document.removeEventListener("keydown", interacted, true);
    };
  }, [containerRef]);

  useLayoutEffect(() => {
    if (!enabled || !ready || entry.current.initialized) return;
    const container = containerRef.current;
    const row = container?.querySelector<HTMLElement>(rowSelector);
    if (!container || !row) return;
    // A background table must never take focus from an open dialog.
    const modal = Array.from(document.querySelectorAll('[aria-modal="true"], dialog[open]'))
      .filter(element => !element.closest('[hidden], [aria-hidden="true"]')).at(-1);
    if (modal && !modal.contains(container)) return;
    entry.current.initialized = true;
    onSelect?.(row);
    if (entry.current.mayFocus) {
      const target = typeof focusTarget === "function" ? focusTarget()
        : focusTarget === "container" ? container : row;
      target?.focus({ preventScroll: true });
      ensureTableRowVisible(container, row);
    }
  });
}

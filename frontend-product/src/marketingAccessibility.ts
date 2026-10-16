/** Returns the selected tab for the standard horizontal tab-list keys. */
export function getRovingTabIndex(key: string, currentIndex: number, count: number): number | null {
  if (count < 1) return null;
  if (key === "Home") return 0;
  if (key === "End") return count - 1;
  if (key === "ArrowRight") return (currentIndex + 1 + count) % count;
  if (key === "ArrowLeft") return (currentIndex - 1 + count) % count;
  return null;
}

/** Only interrupts native Tab navigation when focus must enter or wrap a dialog. */
export function getTrappedTabIndex(activeIndex: number, shiftKey: boolean, count: number): number | null {
  if (count < 1) return null;
  if (activeIndex < 0) return shiftKey ? count - 1 : 0;
  if (shiftKey && activeIndex === 0) return count - 1;
  if (!shiftKey && activeIndex === count - 1) return 0;
  return null;
}

export function isAvailableFocusTarget(element: HTMLElement): boolean {
  if (!element.isConnected || element.closest('[hidden], [inert], [aria-hidden="true"]')) return false;
  if (element.matches(':disabled, input[type="hidden"]') || element.getAttribute("aria-disabled") === "true") return false;
  if (!element.getClientRects().length) return false;
  const style = element.ownerDocument.defaultView?.getComputedStyle(element);
  return style?.display !== "none" && style?.visibility !== "hidden" && style?.visibility !== "collapse";
}

export function getDialogFocusableElements(dialog: HTMLElement): HTMLElement[] {
  return [...dialog.querySelectorAll<HTMLElement>('a[href], area[href], button, select, input, textarea, summary, [contenteditable="true"], [tabindex]')]
    .filter((element) => element.tabIndex >= 0 && isAvailableFocusTarget(element));
}

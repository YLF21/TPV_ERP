import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { getDialogFocusableElements, getTrappedTabIndex, isAvailableFocusTarget } from "./marketingAccessibility";

export function useModalDialog(onClose: () => void) {
  const dialogRef = useRef<HTMLElement | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);
  const closeHandlerRef = useRef(onClose);

  // Parent updates must not refocus the close button or reset a scrolled dialog.
  useEffect(() => {
    closeHandlerRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const dialog = dialogRef.current;
    if (!dialog) return;
    const page = dialog.closest<HTMLElement>(".mk-page");
    const background = page?.querySelector<HTMLElement>("[data-marketing-background]");
    const previousInert = background?.inert ?? false;
    const previousAriaHidden = background?.getAttribute("aria-hidden") ?? null;
    const scrollContainers = [page, document.body, document.documentElement].filter((element): element is HTMLElement => Boolean(element));
    const previousOverflow = scrollContainers.map((element) => element.style.overflow);
    const focusFirst = () => (closeRef.current ?? getDialogFocusableElements(dialog)[0] ?? dialog).focus({ preventScroll: true });
    focusFirst();
    // The portal is a sibling of this background, never its descendant.
    if (background) { background.inert = true; background.setAttribute("aria-hidden", "true"); }
    scrollContainers.forEach((element) => { element.style.overflow = "hidden"; });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeHandlerRef.current();
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = getDialogFocusableElements(dialog);
      if (!focusable.length) { event.preventDefault(); dialog.focus({ preventScroll: true }); return; }
      const activeIndex = focusable.findIndex((element) => element === document.activeElement);
      if (activeIndex < 0 && document.activeElement !== dialog && dialog.contains(document.activeElement)) return;
      const nextIndex = getTrappedTabIndex(activeIndex, event.shiftKey, focusable.length);
      if (nextIndex !== null) { event.preventDefault(); focusable[nextIndex].focus({ preventScroll: true }); }
    };
    const keepFocusInDialog = (event: FocusEvent) => { if (event.target instanceof Node && !dialog.contains(event.target)) focusFirst(); };
    document.addEventListener("keydown", handleKeyDown);
    document.addEventListener("focusin", keepFocusInDialog);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.removeEventListener("focusin", keepFocusInDialog);
      if (background) {
        background.inert = previousInert;
        if (previousAriaHidden === null) background.removeAttribute("aria-hidden");
        else background.setAttribute("aria-hidden", previousAriaHidden);
      }
      scrollContainers.forEach((element, index) => { element.style.overflow = previousOverflow[index]; });
      if (previousFocus && isAvailableFocusTarget(previousFocus)) previousFocus.focus({ preventScroll: true });
      else {
        const main = page?.querySelector<HTMLElement>("#mk-main-content");
        if (main && isAvailableFocusTarget(main)) main.focus({ preventScroll: true });
      }
    };
  }, []);

  return { dialogRef, closeRef };
}

export function MarketingModalLayer({ children, onClose, className = "mk-product-overlay" }: {
  children: ReactNode;
  onClose: () => void;
  className?: string;
}) {
  const root = document.getElementById("mk-modal-root");
  return root ? createPortal(<div className={className} role="presentation" onMouseDown={(event) => {
    if (event.target === event.currentTarget) { event.preventDefault(); onClose(); }
  }}>{children}</div>, root) : null;
}

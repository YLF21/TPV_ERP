export type FocusableNode = { focus: () => void };
export type ModalKeyEvent = { key: string; shiftKey: boolean; preventDefault: () => void };
export type ModalFocusRoot = {
  querySelectorAll: (selector: string) => ArrayLike<FocusableNode>;
  contains: (node: unknown) => boolean;
  addEventListener: (type: string, listener: (event: ModalKeyEvent) => void) => void;
  removeEventListener: (type: string, listener: (event: ModalKeyEvent) => void) => void;
};
export type ModalFocusDocument = { activeElement: unknown };
export type ModalFocusTrapOptions = { restoreFocus?: boolean };

const focusableSelector = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function modalFocusTarget(focusables: readonly FocusableNode[], activeElement: unknown, backwards: boolean) {
  if (focusables.length === 0) return null;
  if (backwards && activeElement === focusables[0]) return focusables[focusables.length - 1];
  if (!backwards && activeElement === focusables[focusables.length - 1]) return focusables[0];
  return null;
}

export function activateModalFocusTrap(
  root: ModalFocusRoot,
  doc: ModalFocusDocument,
  { restoreFocus = true }: ModalFocusTrapOptions = {},
) {
  const previouslyFocused = isFocusable(doc.activeElement) ? doc.activeElement : null;
  if (!root.contains(doc.activeElement)) {
    Array.from(root.querySelectorAll(focusableSelector))[0]?.focus();
  }
  const handleKeyDown = (event: ModalKeyEvent) => {
    if (event.key !== "Tab") return;
    const target = modalFocusTarget(Array.from(root.querySelectorAll(focusableSelector)), doc.activeElement, event.shiftKey);
    if (!target) return;
    event.preventDefault();
    target.focus();
  };
  root.addEventListener("keydown", handleKeyDown);
  return () => {
    root.removeEventListener("keydown", handleKeyDown);
    if (restoreFocus) previouslyFocused?.focus();
  };
}

function isFocusable(value: unknown): value is FocusableNode {
  return typeof value === "object" && value !== null && "focus" in value && typeof value.focus === "function";
}

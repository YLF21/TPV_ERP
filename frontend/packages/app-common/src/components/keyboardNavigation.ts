export type EnterNavigationIntent = "next" | "previous";

type EnterKeyModifiers = {
  shiftKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
  metaKey?: boolean;
  isComposing?: boolean;
};

export function enterNavigationIntent(
  key: string,
  modifiers: EnterKeyModifiers
): EnterNavigationIntent | null {
  if (key !== "Enter"
      || modifiers.ctrlKey
      || modifiers.altKey
      || modifiers.metaKey
      || modifiers.isComposing) {
    return null;
  }
  return modifiers.shiftKey ? "previous" : "next";
}

export function nextEnterTargetIndex(
  currentIndex: number,
  targetCount: number,
  intent: EnterNavigationIntent
) {
  if (targetCount <= 0) return -1;
  const safeIndex = currentIndex >= 0 && currentIndex < targetCount ? currentIndex : 0;
  return intent === "next"
    ? Math.min(targetCount - 1, safeIndex + 1)
    : Math.max(0, safeIndex - 1);
}

export function focusRelativeEnterTarget(
  container: ParentNode | null,
  currentTarget: Element,
  intent: EnterNavigationIntent,
  selector: string
) {
  const targets = Array.from(container?.querySelectorAll<HTMLElement>(selector) ?? []);
  const currentIndex = targets.findIndex((target) => target === currentTarget || target.contains(currentTarget));
  const nextIndex = nextEnterTargetIndex(currentIndex, targets.length, intent);
  if (nextIndex < 0 || nextIndex === currentIndex) return false;
  targets[nextIndex]?.focus();
  return true;
}

import { describe, expect, it } from "vitest";
import { enterNavigationIntent, nextEnterTargetIndex } from "./keyboardNavigation";

describe("keyboardNavigation", () => {
  it("uses plain Enter to move forward and Shift+Enter to move backward", () => {
    expect(enterNavigationIntent("Enter", {})).toBe("next");
    expect(enterNavigationIntent("Enter", { shiftKey: true })).toBe("previous");
  });

  it("leaves modified Enter and composition events available to explicit shortcuts", () => {
    expect(enterNavigationIntent("Enter", { ctrlKey: true })).toBeNull();
    expect(enterNavigationIntent("Enter", { altKey: true })).toBeNull();
    expect(enterNavigationIntent("Enter", { metaKey: true })).toBeNull();
    expect(enterNavigationIntent("Enter", { isComposing: true })).toBeNull();
    expect(enterNavigationIntent("Tab", {})).toBeNull();
  });

  it("moves through a field sequence without leaving its bounds", () => {
    expect(nextEnterTargetIndex(0, 4, "next")).toBe(1);
    expect(nextEnterTargetIndex(3, 4, "next")).toBe(3);
    expect(nextEnterTargetIndex(2, 4, "previous")).toBe(1);
    expect(nextEnterTargetIndex(0, 4, "previous")).toBe(0);
    expect(nextEnterTargetIndex(0, 0, "next")).toBe(-1);
  });
});

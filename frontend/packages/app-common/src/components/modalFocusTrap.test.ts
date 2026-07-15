import { describe, expect, it, vi } from "vitest";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

function trapRoot(activeElement: { focus: () => void }): ModalFocusRoot {
  return {
    querySelectorAll: vi.fn(() => [activeElement]),
    contains: vi.fn(() => true),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  };
}

describe("activateModalFocusTrap", () => {
  it("restores focus by default for existing modal consumers", () => {
    const activeElement = { focus: vi.fn() };
    const cleanup = activateModalFocusTrap(trapRoot(activeElement), { activeElement });

    cleanup();

    expect(activeElement.focus).toHaveBeenCalledOnce();
  });

  it("can remove the trap without restoring focus", () => {
    const activeElement = { focus: vi.fn() };
    const cleanup = activateModalFocusTrap(trapRoot(activeElement), { activeElement }, { restoreFocus: false });

    cleanup();

    expect(activeElement.focus).not.toHaveBeenCalled();
  });
});

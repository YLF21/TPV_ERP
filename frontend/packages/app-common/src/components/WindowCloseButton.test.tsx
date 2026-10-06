// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { createRef } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WindowCloseButton } from "./WindowCloseButton";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("WindowCloseButton", () => {
  it("preserves the accessible name and close action without submitting its form", () => {
    const close = vi.fn();
    const submit = vi.fn();
    render(<form onSubmit={submit}><WindowCloseButton aria-label="Cerrar" onClick={close}>×</WindowCloseButton></form>);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(close).toHaveBeenCalledTimes(1);
    expect(submit).not.toHaveBeenCalled();
  });

  it("preserves the disabled guard", () => {
    const close = vi.fn();
    render(<WindowCloseButton aria-label="Cerrar" disabled onClick={close}>×</WindowCloseButton>);
    const button = screen.getByRole("button", { name: "Cerrar" });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(close).not.toHaveBeenCalled();
  });

  it("forwards the native button ref for existing focus traps", () => {
    const ref = createRef<HTMLButtonElement>();
    render(<WindowCloseButton aria-label="Cerrar" ref={ref}>×</WindowCloseButton>);
    ref.current?.focus();
    expect(screen.getByRole("button", { name: "Cerrar" })).toHaveFocus();
  });

  it("retains the original close content and classes for the PDA build", () => {
    vi.stubGlobal("__TPV_APP_KIND__", "pda");
    render(<WindowCloseButton aria-label="Cerrar" className="original-close">Cerrar</WindowCloseButton>);
    const button = screen.getByRole("button", { name: "Cerrar" });
    expect(button).toHaveTextContent("Cerrar");
    expect(button).toHaveClass("original-close");
    expect(button).not.toHaveClass("erp-window-close");
  });

  it("keeps new desktop header controls out of the PDA build", () => {
    vi.stubGlobal("__TPV_APP_KIND__", "pda");
    render(<WindowCloseButton desktopOnly aria-label="Cerrar" />);
    expect(screen.queryByRole("button", { name: "Cerrar" })).not.toBeInTheDocument();
  });
});

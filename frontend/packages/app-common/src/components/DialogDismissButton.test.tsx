// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { createRef } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DialogDismissButton } from "./DialogDismissButton";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("DialogDismissButton", () => {
  it("removes ordinary footer dismissals from the desktop DOM and tab order", () => {
    const dismiss = vi.fn();
    render(<DialogDismissButton onClick={dismiss}>Cerrar</DialogDismissButton>);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(dismiss).not.toHaveBeenCalled();
  });

  it("retains a notice action, its ref and disabled guard without submitting", () => {
    const dismiss = vi.fn();
    const submit = vi.fn();
    const ref = createRef<HTMLButtonElement>();
    const { rerender } = render(<form onSubmit={submit}><DialogDismissButton notice ref={ref}
      disabled onClick={dismiss}>Cancelar</DialogDismissButton></form>);
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(dismiss).not.toHaveBeenCalled();
    rerender(<form onSubmit={submit}><DialogDismissButton notice ref={ref}
      onClick={dismiss}>Cancelar</DialogDismissButton></form>);
    ref.current?.focus();
    expect(screen.getByRole("button", { name: "Cancelar" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(dismiss).toHaveBeenCalledOnce();
    expect(submit).not.toHaveBeenCalled();
  });

  it("preserves the existing footer action in the PDA build", () => {
    vi.stubGlobal("__TPV_APP_KIND__", "pda");
    const dismiss = vi.fn();
    render(<DialogDismissButton onClick={dismiss}>Cerrar</DialogDismissButton>);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(dismiss).toHaveBeenCalledOnce();
  });
});

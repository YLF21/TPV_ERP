// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FiscalWorkspaceDialog } from "./FiscalWorkspaceDialog";

describe("FiscalWorkspaceDialog", () => {
  afterEach(cleanup);

  it("renderiza en portal, enfoca el primer control, atrapa Tab y restaura foco al cerrar", async () => {
    const onClose = vi.fn();
    const trigger = document.createElement("button");
    document.body.appendChild(trigger);
    trigger.focus();
    render(<FiscalWorkspaceDialog id="filters" title="Filtros" closeLabel="Cerrar" onClose={onClose}><button type="button">Primero</button><button type="button">Último</button></FiscalWorkspaceDialog>);
    expect(screen.getByRole("dialog", { name: "Filtros" })).toBeTruthy();
    await new Promise((resolve) => window.requestAnimationFrame(resolve));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Cerrar" }));
    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Primero" }));
    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Cerrar" }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    cleanup();
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });

  it("no cierra por Escape ni backdrop mientras closeDisabled", () => {
    const onClose = vi.fn();
    render(<FiscalWorkspaceDialog id="export" title="Exportar" closeLabel="Cerrar" closeDisabled onClose={onClose}><button type="button">Acción</button></FiscalWorkspaceDialog>);
    fireEvent.keyDown(document, { key: "Escape" });
    fireEvent.mouseDown(screen.getByRole("presentation"), { target: screen.getByRole("presentation") });
    expect(onClose).not.toHaveBeenCalled();
  });

  it("conserva el foco y usa el callback actual tras un rerender", async () => {
    const firstClose = vi.fn();
    const secondClose = vi.fn();
    const view = render(<FiscalWorkspaceDialog id="stable" title="Filtros" closeLabel="Cerrar" onClose={firstClose}><input aria-label="Número" defaultValue="" /></FiscalWorkspaceDialog>);
    await new Promise((resolve) => window.requestAnimationFrame(resolve));
    const input = screen.getByRole("textbox", { name: "Número" });
    input.focus();
    view.rerender(<FiscalWorkspaceDialog id="stable" title="Filtros actualizados" closeLabel="Cerrar" onClose={secondClose}><input aria-label="Número" defaultValue="A" /></FiscalWorkspaceDialog>);
    expect(document.activeElement).toBe(input);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(firstClose).not.toHaveBeenCalled();
    expect(secondClose).toHaveBeenCalledTimes(1);
  });

  it("expone una clase semántica de propósito sin perder la variante modal", () => {
    render(<FiscalWorkspaceDialog id="filters" title="Filtros" closeLabel="Cerrar" purpose="filters" onClose={vi.fn()}>
      <input aria-label="Número" />
    </FiscalWorkspaceDialog>);

    const dialog = screen.getByRole("dialog", { name: "Filtros" });
    expect(dialog.classList.contains("fiscal-workspace-dialog")).toBe(true);
    expect(dialog.classList.contains("fiscal-workspace-dialog-purpose-filters")).toBe(true);
    expect(dialog.classList.contains("fiscal-workspace-dialog-drawer")).toBe(false);
  });
});

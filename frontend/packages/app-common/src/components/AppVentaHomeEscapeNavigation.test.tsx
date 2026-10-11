// @vitest-environment jsdom
import { useEffect } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppVentaHomeEscapeNavigation } from "./AppVentaHomeEscapeNavigation";
import { AppBrand } from "./AppBrand";
import userEvent from "@testing-library/user-event";

afterEach(cleanup);

describe("AppVentaHomeEscapeNavigation", () => {
  it("uses the Escape confirmation for the logo instead of calling its direct back handler", async () => {
    const onConfirmHome = vi.fn();
    const directBack = vi.fn();
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome}>
        <button type="button" className="report-brand-back" onClick={directBack}>
          <AppBrand app="venta" label="esPOS VENTA" />
        </button>
      </AppVentaHomeEscapeNavigation>,
    );
    fireEvent.click(screen.getByRole("img", { name: "esPOS VENTA" }));
    expect(screen.getByRole("alertdialog").textContent).toMatch(/volver al inicio/i);
    expect(directBack).not.toHaveBeenCalled();
    expect(onConfirmHome).not.toHaveBeenCalled();
    fireEvent.keyDown(window, { key: "Escape" });
    await Promise.resolve();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "esPOS VENTA" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "esPOS VENTA" }));
    fireEvent.keyDown(window, { key: "Enter" });
    expect(onConfirmHome).toHaveBeenCalledOnce();
    expect(directBack).not.toHaveBeenCalled();
  });

  it.each(["{Enter}", " "])("confirms keyboard activation of the logo with %s", async key => {
    const user = userEvent.setup();
    const onConfirmHome = vi.fn();
    const directBack = vi.fn();
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome}>
        <button type="button" className="report-brand-back" onClick={directBack}>Logo</button>
      </AppVentaHomeEscapeNavigation>,
    );
    screen.getByRole("button", { name: "Logo" }).focus();
    await user.keyboard(key);
    expect(screen.getByRole("alertdialog")).toBeTruthy();
    expect(onConfirmHome).not.toHaveBeenCalled();
    expect(directBack).not.toHaveBeenCalled();
  });

  it.each(["blocked", "disabled", "dialog", "dropdown"])("blocks logo navigation with %s", state => {
    const onConfirmHome = vi.fn();
    const directBack = vi.fn();
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome} navigationBlocked={state === "blocked"}>
        <button type="button" className="report-brand-back" disabled={state === "disabled"} onClick={directBack}>Logo</button>
        {state === "dialog" && <section role="dialog" aria-modal="true">Operación</section>}
        {state === "dropdown" && <button type="button" aria-expanded="true">Menú</button>}
      </AppVentaHomeEscapeNavigation>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Logo" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onConfirmHome).not.toHaveBeenCalled();
    expect(directBack).not.toHaveBeenCalled();
  });

  it("leaves explicit back buttons and screens outside the APP VENTA guard unchanged", () => {
    const onBack = vi.fn();
    const { unmount } = render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={vi.fn()}>
        <button type="button" className="report-back" onClick={onBack}>Volver</button>
      </AppVentaHomeEscapeNavigation>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Volver" }));
    expect(onBack).toHaveBeenCalledOnce();
    expect(screen.queryByRole("alertdialog")).toBeNull();
    unmount();
    render(<button type="button" className="report-brand-back" onClick={onBack}>Logo de Gestión</button>);
    fireEvent.click(screen.getByRole("button", { name: "Logo de Gestión" }));
    expect(onBack).toHaveBeenCalledTimes(2);
  });

  it("silently blocks Escape until navigation is allowed again", () => {
    const onConfirmHome = vi.fn();
    const view = (navigationBlocked: boolean) => (
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome} navigationBlocked={navigationBlocked}>
        <button type="button">Producto</button>
      </AppVentaHomeEscapeNavigation>
    );
    const { rerender } = render(view(true));
    const product = screen.getByRole("button", { name: "Producto" });
    product.focus();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onConfirmHome).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(product);

    rerender(view(false));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("alertdialog")).toBeTruthy();
    fireEvent.keyDown(window, { key: "Enter" });
    expect(onConfirmHome).toHaveBeenCalledOnce();
  });

  it("dismisses an existing confirmation when navigation becomes blocked", () => {
    const onConfirmHome = vi.fn();
    const view = (navigationBlocked: boolean) => (
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome} navigationBlocked={navigationBlocked}>
        <main>Venta</main>
      </AppVentaHomeEscapeNavigation>
    );
    const { rerender } = render(view(false));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("alertdialog")).toBeTruthy();
    rerender(view(true));
    fireEvent.keyDown(window, { key: "Enter" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onConfirmHome).not.toHaveBeenCalled();
    rerender(view(false));
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("asks for confirmation before returning to Home", () => {
    const onConfirmHome = vi.fn();
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome}>
        <main>Pantalla de venta</main>
      </AppVentaHomeEscapeNavigation>,
    );

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("alertdialog")).toBeTruthy();
    expect(onConfirmHome).not.toHaveBeenCalled();

    fireEvent.keyDown(window, { key: "Enter" });
    expect(onConfirmHome).toHaveBeenCalledOnce();
  });

  it("cancels the navigation with a second Escape and restores focus", async () => {
    const onConfirmHome = vi.fn();
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={onConfirmHome}>
        <button type="button">Producto</button>
      </AppVentaHomeEscapeNavigation>,
    );
    const product = screen.getByRole("button", { name: "Producto" });
    product.focus();

    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.keyDown(window, { key: "Escape" });

    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onConfirmHome).not.toHaveBeenCalled();
    await Promise.resolve();
    expect(document.activeElement).toBe(product);
  });

  it("restores focus if a screen guard keeps the screen open after confirming Home", async () => {
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={vi.fn()}>
        <input aria-label="Configuración pendiente" />
      </AppVentaHomeEscapeNavigation>,
    );
    const input = screen.getByRole("textbox", { name: "Configuración pendiente" });
    input.focus();
    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.keyDown(window, { key: "Enter" });
    await Promise.resolve();
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(document.activeElement).toBe(input);
  });

  it("leaves Escape to an open functional dialog without showing the Home confirmation", () => {
    const onClose = vi.fn();
    function FunctionalDialog() {
      useEffect(() => {
        const close = (event: KeyboardEvent) => {
          if (event.key === "Escape") onClose();
        };
        window.addEventListener("keydown", close);
        return () => window.removeEventListener("keydown", close);
      }, []);
      return <section role="dialog" aria-modal="true"><button type="button">Cerrar</button></section>;
    }

    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={vi.fn()} navigationBlocked>
        <FunctionalDialog />
      </AppVentaHomeEscapeNavigation>,
    );
    fireEvent.keyDown(window, { key: "Escape" });

    expect(onClose).toHaveBeenCalledOnce();
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("leaves Escape to an expanded dropdown", () => {
    render(
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={vi.fn()}>
        <button type="button" aria-expanded="true">Menú</button>
      </AppVentaHomeEscapeNavigation>,
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });
});

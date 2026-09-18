// @vitest-environment jsdom
import { useEffect } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppVentaHomeEscapeNavigation } from "./AppVentaHomeEscapeNavigation";

afterEach(cleanup);

describe("AppVentaHomeEscapeNavigation", () => {
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

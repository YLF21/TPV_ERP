// @vitest-environment jsdom
import { useEffect } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppVentaHomeEscapeNavigation } from "./AppVentaHomeEscapeNavigation";

describe("AppVentaHomeEscapeNavigation", () => {
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
      <AppVentaHomeEscapeNavigation locale="es" onConfirmHome={vi.fn()}>
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

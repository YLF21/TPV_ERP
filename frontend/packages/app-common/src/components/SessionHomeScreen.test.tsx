// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionHomeScreen } from "./SessionHomeScreen";
import type { TerminalContext, UserSession } from "../types";

const moduleFilename = (import.meta as ImportMeta & { filename: string }).filename;
const tpvStyles = readFileSync(new URL("../styles/tpv.css", pathToFileURL(moduleFilename)), "utf8");

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

afterEach(cleanup);

describe("SessionHomeScreen", () => {
  it("renders the formal home with user language and shutdown controls", () => {
    render(
      <SessionHomeScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        canOpenSalesReport
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenSalesReport={vi.fn()}
        onOpenSettings={vi.fn()}
      />
    );

    expect(document.querySelector(".report-user-button")).toBeInTheDocument();
    expect(document.querySelector(".top-date-time")).toBeInTheDocument();
    expect(document.querySelector(".language-button")).toBeInTheDocument();
    expect(document.querySelector(".shutdown-button")).toBeInTheDocument();
    expect(document.querySelector(".entry-topbar")).toBeInTheDocument();
    expect(document.querySelector(".report-footer-context")).toBeInTheDocument();
    expect(screen.getByText(/DB: local/)).toBeInTheDocument();
    expect(screen.getByText("Conexión")).toBeInTheDocument();
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /configuraci/i })).toBeInTheDocument();
  });

  it("renders the approved APP VENTA launcher and shortcut labels", () => {
    render(<SessionHomeScreen
      app="venta"
      locale="es"
      session={{ ...session, permissions: ["ADMIN", "CUSTOMER_RECEIVABLES_READ"] }}
      terminalContext={terminalContext}
      canOpenSalesReport
      onLocaleChange={vi.fn()}
      onOpenSales={vi.fn()}
      onOpenStock={vi.fn()}
      onOpenSalesReport={vi.fn()}
      onOpenSettings={vi.fn()}
      onOpenCustomerReceivables={vi.fn()}
    />);

    expect(within(screen.getByRole("button", { name: /venta/i })).getByText("F1")).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: /producto|stock/i })).getByText("F2")).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: /informe/i })).getByText("F3")).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: /configuraci|ajustes/i })).getByText("F4")).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: /deudas clientes/i })).getByText("F5")).toBeInTheDocument();

    const sale = screen.getByRole("button", { name: /venta/i });
    expect(sale).toHaveAttribute("data-home-action", "sale");
    expect(sale.querySelector(".home-action-icon-panel")).not.toBeNull();
    expect(sale.querySelector(".home-action-label")).not.toBeNull();
    expect(sale.querySelector(".home-action-shortcut")).not.toBeNull();
  });

  it("keeps the single-column launcher reachable at narrow widths", () => {
    const narrowStyles = tpvStyles.slice(tpvStyles.lastIndexOf("@media (max-width: 1023px)"));

    expect(narrowStyles).toMatch(/body:has\(\.home-screen\)\s*\{[^}]*min-width: 0;/s);
    expect(narrowStyles).toMatch(/\.home-screen\s*\{[^}]*overflow-y: auto;/s);
    expect(narrowStyles).toMatch(/\.home-screen \.home-actions\s*\{[^}]*position: relative !important;[^}]*transform: none !important;/s);
    expect(narrowStyles).toMatch(/\.home-screen \.home-action-sale\s*\{[^}]*height: auto !important;/s);
  });

  it("wins the legacy span cascade for launcher panels and labels", () => {
    const finalStyles = tpvStyles.slice(tpvStyles.lastIndexOf("/* APP VENTA home launcher:"));

    expect(finalStyles).toMatch(/\.home-screen \.home-action-side \.home-action \.home-action-icon-panel\s*\{[^}]*align-self: stretch !important;[^}]*justify-self: stretch !important;/s);
    expect(finalStyles).toMatch(/\.home-screen \.home-action \.home-action-label\s*\{[^}]*justify-self: start !important;[^}]*font-size: clamp\(22px, 2\.2vw, 36px\) !important;/s);
  });

  it("uses the full launcher width when Venta is the only visible action", () => {
    render(<SessionHomeScreen app="venta" locale="es"
      session={{ ...session, permissions: ["VENTA"] }} terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onOpenSales={vi.fn()} />);

    expect(screen.getByRole("region", { name: /inicio|home/i })).toHaveClass("home-actions-sale-only");
    expect(screen.getByRole("button", { name: /venta/i })).toBeInTheDocument();
    expect(document.querySelector(".home-action-side")).not.toBeInTheDocument();
  });

  it("uses the full launcher width when only secondary actions are visible", () => {
    render(<SessionHomeScreen app="venta" locale="es"
      session={{ ...session, permissions: [] }} terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onOpenSales={vi.fn()} onOpenSettings={vi.fn()} />);

    expect(screen.getByRole("region", { name: /inicio|home/i })).toHaveClass("home-actions-side-only");
    expect(screen.queryByRole("button", { name: /venta/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /configuraci|ajustes/i })).toBeInTheDocument();
    expect(document.querySelector(".home-action-side")).toBeInTheDocument();
  });

  it("defines full-width partial layouts and a collision-safe mobile card contract", () => {
    const finalStyles = tpvStyles.slice(tpvStyles.lastIndexOf("/* APP VENTA home launcher:"));
    const mobileStyles = finalStyles.slice(finalStyles.lastIndexOf("@media (max-width: 480px)"));

    expect(finalStyles).toMatch(/\.home-screen \.home-actions-sale-only,\s*\.home-screen \.home-actions-side-only\s*\{[^}]*grid-template-columns: minmax\(0, 1fr\) !important;/s);
    expect(mobileStyles).toMatch(/\.home-screen \.home-action-side \.home-action\s*\{[^}]*grid-template-columns: 72px minmax\(0, 1fr\) 54px !important;/s);
    expect(mobileStyles).toMatch(/\.home-screen \.home-action \.home-action-label\s*\{[^}]*min-width: 0;[^}]*padding: 0 10px;[^}]*font-size: clamp\(16px, 4\.6vw, 20px\) !important;[^}]*overflow-wrap: anywhere;/s);
    expect(mobileStyles).toMatch(/\.home-screen \.home-action-side \.home-action \.home-action-icon\s*\{[^}]*width: 52px !important;[^}]*height: 52px !important;/s);
    expect(mobileStyles).toMatch(/\.home-screen \.home-action-shortcut\s*\{[^}]*padding: 7px 8px;/s);
  });

  it("wires product and warehouse actions to their callbacks", () => {
    const onOpenStock = vi.fn();
    const onOpenWarehouse = vi.fn();
    render(
      <SessionHomeScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onOpenStock={onOpenStock}
        onOpenWarehouse={onOpenWarehouse}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /producto/i }));
    fireEvent.click(screen.getByRole("button", { name: /almacén/i }));
    expect(onOpenStock).toHaveBeenCalledOnce();
    expect(onOpenWarehouse).toHaveBeenCalledOnce();
  });

  it("shows customer receivables only with CUSTOMER_RECEIVABLES_READ", () => {
    const onOpenCustomerReceivables = vi.fn();
    const { rerender } = render(
      <SessionHomeScreen app="venta" locale="es" session={{ ...session, permissions: ["CUSTOMER_RECEIVABLES_READ"] }} terminalContext={terminalContext} onLocaleChange={vi.fn()} onOpenCustomerReceivables={onOpenCustomerReceivables} />
    );
    fireEvent.click(screen.getByRole("button", { name: /deudas clientes/i }));
    expect(onOpenCustomerReceivables).toHaveBeenCalledOnce();

    rerender(<SessionHomeScreen app="venta" locale="es" session={{ ...session, permissions: [] }} terminalContext={terminalContext} onLocaleChange={vi.fn()} onOpenCustomerReceivables={onOpenCustomerReceivables} />);
    expect(screen.queryByRole("button", { name: /deudas clientes/i })).not.toBeInTheDocument();
  });

  it("opens visible home actions with F1-F5 and ignores repeats", () => {
    const callbacks = {
      sale: vi.fn(), stock: vi.fn(), report: vi.fn(), settings: vi.fn(), receivables: vi.fn(),
    };
    render(<SessionHomeScreen app="venta" locale="es"
      session={{ ...session, permissions: ["ADMIN", "CUSTOMER_RECEIVABLES_READ"] }}
      terminalContext={terminalContext} canOpenSalesReport onLocaleChange={vi.fn()}
      onOpenSales={callbacks.sale} onOpenStock={callbacks.stock}
      onOpenSalesReport={callbacks.report} onOpenSettings={callbacks.settings}
      onOpenCustomerReceivables={callbacks.receivables} />);

    ["F1", "F2", "F3", "F4", "F5"].forEach((key) => fireEvent.keyDown(window, { key }));
    expect(callbacks.sale).toHaveBeenCalledOnce();
    expect(callbacks.stock).toHaveBeenCalledOnce();
    expect(callbacks.report).toHaveBeenCalledOnce();
    expect(callbacks.settings).toHaveBeenCalledOnce();
    expect(callbacks.receivables).toHaveBeenCalledOnce();
    fireEvent.keyDown(window, { key: "F1", repeat: true });
    expect(callbacks.sale).toHaveBeenCalledOnce();
  });

  it("does not run a shortcut for an action hidden by permissions", () => {
    const receivables = vi.fn();
    render(<SessionHomeScreen app="venta" locale="es" session={{ ...session, permissions: [] }}
      terminalContext={terminalContext} onLocaleChange={vi.fn()}
      onOpenCustomerReceivables={receivables} />);
    fireEvent.keyDown(window, { key: "F5" });
    expect(receivables).not.toHaveBeenCalled();
  });
});

// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionHomeScreen } from "./SessionHomeScreen";
import type { TerminalContext, UserSession } from "../types";

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

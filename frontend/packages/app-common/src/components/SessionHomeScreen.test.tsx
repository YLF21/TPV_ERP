// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionHomeScreen } from "./SessionHomeScreen";
import type { TerminalContext, UserSession } from "../types";

const { loadCashSessionReadiness } = vi.hoisted(() => ({
  loadCashSessionReadiness: vi.fn()
}));

vi.mock("../sale/cashSessions", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../sale/cashSessions")>()),
  loadCashSessionReadiness
}));

vi.mock("./SaleCashSessionDialog", () => ({
  SaleCashSessionDialog: ({ onOpened, onExitSales }: {
    onOpened: () => void;
    onExitSales: () => void;
  }) => (
    <section role="dialog" aria-modal="true" aria-label="Abrir caja">
      <button type="button" onClick={onOpened}>Confirmar apertura</button>
      <button type="button" onClick={onExitSales}>Cancelar apertura</button>
    </section>
  )
}));

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "token"
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
  terminalId: "terminal-1"
};

function renderHome(overrides: Partial<Parameters<typeof SessionHomeScreen>[0]> = {}) {
  const callbacks = {
    onOpenSales: vi.fn(),
    onOpenStock: vi.fn(),
    onOpenWarehouse: vi.fn(),
    onOpenSalesReport: vi.fn(),
    onOpenSettings: vi.fn()
  };
  render(
    <SessionHomeScreen
      app="venta"
      locale="es"
      session={session}
      terminalContext={terminalContext}
      canOpenSalesReport
      onLocaleChange={vi.fn()}
      {...callbacks}
      {...overrides}
    />
  );
  return callbacks;
}

function dispatchShortcut(key: string, init: KeyboardEventInit = {}, target: EventTarget = window) {
  const event = new KeyboardEvent("keydown", {
    key,
    bubbles: true,
    cancelable: true,
    ...init
  });
  target.dispatchEvent(event);
  return event;
}

describe("SessionHomeScreen", () => {
  afterEach(() => cleanup());
  beforeEach(() => {
    loadCashSessionReadiness.mockReset().mockResolvedValue({
      cashSessionRequired: true,
      open: true,
      session: { id: "cash-1" },
      requireWithdrawalBreakdown: false,
      withdrawalDenominations: []
    });
  });

  it("renders the formal home and contextual F1-F5 indicators", () => {
    const html = renderToStaticMarkup(
      <SessionHomeScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        canOpenSalesReport
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenSales={vi.fn()}
        onOpenStock={vi.fn()}
        onOpenWarehouse={vi.fn()}
        onOpenSalesReport={vi.fn()}
        onOpenSettings={vi.fn()}
      />
    );

    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="entry-topbar"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain('class="home-action-shortcut"');
    for (const shortcut of ["F1", "F2", "F3", "F4", "F5"]) {
      expect(html).toContain(`>${shortcut}</kbd>`);
    }
  });

  it("wires product and warehouse buttons to their callbacks", () => {
    const callbacks = renderHome();

    fireEvent.click(screen.getByRole("button", { name: "PRODUCTO" }));
    fireEvent.click(screen.getByRole("button", { name: "ALMACÉN" }));

    expect(callbacks.onOpenStock).toHaveBeenCalledOnce();
    expect(callbacks.onOpenWarehouse).toHaveBeenCalledOnce();
  });

  it("opens each available Home destination with F1-F5 and prevents the browser action", async () => {
    const callbacks = renderHome();
    await waitFor(() => expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled());
    const cases = [
      ["F1", callbacks.onOpenSales],
      ["F2", callbacks.onOpenStock],
      ["F3", callbacks.onOpenWarehouse],
      ["F4", callbacks.onOpenSalesReport],
      ["F5", callbacks.onOpenSettings]
    ] as const;

    for (const [key, callback] of cases) {
      const event = dispatchShortcut(key);
      expect(event.defaultPrevented).toBe(true);
      expect(callback).toHaveBeenCalledOnce();
    }
  });

  it("does not expose or reserve shortcuts for unavailable destinations", async () => {
    const onOpenSales = vi.fn();
    const onOpenStock = vi.fn();
    renderHome({
      session: { ...session, username: "venta", displayName: "CAJERO", permissions: ["VENTA"] },
      canOpenSalesReport: false,
      onOpenSales,
      onOpenStock,
      onOpenWarehouse: undefined,
      onOpenSalesReport: undefined,
      onOpenSettings: undefined
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled());

    const saleEvent = dispatchShortcut("F1");
    const stockEvent = dispatchShortcut("F2");
    const reportEvent = dispatchShortcut("F4");

    expect(saleEvent.defaultPrevented).toBe(true);
    expect(onOpenSales).toHaveBeenCalledOnce();
    expect(stockEvent.defaultPrevented).toBe(false);
    expect(reportEvent.defaultPrevented).toBe(false);
    expect(onOpenStock).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "PRODUCTO" })).toBeNull();
    expect(screen.queryByRole("button", { name: "INFORME VENTAS" })).toBeNull();
  });

  it("ignores shortcuts with Ctrl Alt Meta Shift or key repeat", async () => {
    const callbacks = renderHome();
    await waitFor(() => expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled());

    for (const init of [
      { ctrlKey: true },
      { altKey: true },
      { metaKey: true },
      { shiftKey: true },
      { repeat: true }
    ]) {
      expect(dispatchShortcut("F1", init).defaultPrevented).toBe(false);
    }

    expect(callbacks.onOpenSales).not.toHaveBeenCalled();
  });

  it("ignores shortcuts from editable controls and contentEditable regions", async () => {
    const callbacks = renderHome();
    await waitFor(() => expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled());
    const targets = [
      document.createElement("input"),
      document.createElement("select"),
      document.createElement("textarea"),
      document.createElement("div")
    ];
    targets[3].setAttribute("contenteditable", "true");

    for (const target of targets) {
      document.body.appendChild(target);
      expect(dispatchShortcut("F1", {}, target).defaultPrevented).toBe(false);
      target.remove();
    }

    expect(callbacks.onOpenSales).not.toHaveBeenCalled();
  });

  it("ignores Home shortcuts while a modal dialog is open", async () => {
    const callbacks = renderHome();
    await waitFor(() => expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled());
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");
    document.body.appendChild(dialog);

    const event = dispatchShortcut("F1");

    expect(event.defaultPrevented).toBe(false);
    expect(callbacks.onOpenSales).not.toHaveBeenCalled();
    dialog.remove();
  });

  it("locks Sales and offers cash opening only while the register is closed", async () => {
    loadCashSessionReadiness.mockResolvedValueOnce({
      cashSessionRequired: true,
      open: false,
      session: null,
      requireWithdrawalBreakdown: false,
      withdrawalDenominations: []
    });
    const callbacks = renderHome();

    expect(screen.getByRole("button", { name: "VENTA" })).toBeDisabled();
    expect(await screen.findByRole("button", { name: "Abrir caja y turno" })).toBeVisible();
    expect(dispatchShortcut("F1").defaultPrevented).toBe(false);
    expect(callbacks.onOpenSales).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Abrir caja y turno" }));
    expect(screen.getByRole("dialog", { name: "Abrir caja" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar apertura" }));

    expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Abrir caja y turno" })).toBeNull();
  });

  it("keeps Sales locked and retries when cash status cannot be checked", async () => {
    loadCashSessionReadiness
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce({
        cashSessionRequired: true,
        open: true,
        session: { id: "cash-2" },
        requireWithdrawalBreakdown: false,
        withdrawalDenominations: []
      });
    renderHome();

    expect(await screen.findByRole("alert")).toHaveTextContent("Venta permanece bloqueada");
    expect(screen.getByRole("button", { name: "VENTA" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "VENTA" })).toBeEnabled());
    expect(loadCashSessionReadiness).toHaveBeenCalledTimes(2);
  });
});

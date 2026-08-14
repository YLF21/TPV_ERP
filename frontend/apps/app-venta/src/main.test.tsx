// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";
import { saleUserLocaleStorageKey } from "./saleUserLocale";

const session: UserSession = {
  userId: " CASHIER-1 ",
  username: "cashier",
  displayName: "Cashier",
  permissions: ["CUSTOMER_RECEIVABLES_READ"],
};
let loginSession = session;

vi.mock("react-dom/client", () => ({
  createRoot: vi.fn(() => ({ render: vi.fn() })),
}));

vi.mock("../../../packages/app-common/src/components/LoginScreen", () => ({
  LoginScreen: ({
    locale,
    onLocaleChange,
    onLogin,
  }: {
    locale: LocaleCode;
    onLocaleChange: (locale: LocaleCode) => void;
    onLogin: (session: UserSession) => void;
  }) => (
    <section aria-label="login">
      <output aria-label="login locale">{locale}</output>
      <button type="button" onClick={() => onLocaleChange("zh")}>Change login locale</button>
      <button type="button" onClick={() => onLogin(loginSession)}>Log in</button>
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/SessionHomeScreen", () => ({
  SessionHomeScreen: ({
    locale,
    onLocaleChange,
    onLogout,
    onOpenSales,
    onOpenSettings,
  }: {
    locale: LocaleCode;
    onLocaleChange: (locale: LocaleCode) => void;
    onLogout: () => void;
    onOpenSales?: () => void;
    onOpenSettings?: () => void;
  }) => (
    <section aria-label="home">
      <output aria-label="home locale">{locale}</output>
      <button type="button" onClick={() => onLocaleChange("zh")}>Change home locale</button>
      <button type="button" onClick={onLogout}>Log out</button>
      <button type="button" onClick={onOpenSales}>Open sales</button>
      <button type="button" onClick={onOpenSettings}>Open settings</button>
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/CustomerReceivablesScreen", () => ({
  CustomerReceivablesScreen: ({ initialCustomerId, onBack }: { initialCustomerId?: string; onBack: () => void }) => <section role="dialog" aria-label="receivables"><output>{initialCustomerId}</output><button onClick={onBack}>Close receivables</button></section>
}));

vi.mock("../../../packages/app-common/src/components/SaleScreen", () => ({
  SaleScreen: ({
    onOpenCustomerReceivables,
    onOpenSalesDocumentWindow
  }: {
    onOpenCustomerReceivables?: (customerId?: string) => void;
    onOpenSalesDocumentWindow?: () => void;
  }) => (
    <section aria-label="sale">
      <button type="button" onClick={() => onOpenCustomerReceivables?.("customer-from-sale")}>Open sale receivables</button>
      {onOpenSalesDocumentWindow && (
        <button type="button" onClick={onOpenSalesDocumentWindow}>Open sales document window</button>
      )}
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/SettingsScreen", () => ({
  SettingsScreen: ({
    initialDestination,
    onOpenHardware,
    onOpenDocumentPrinting,
    onOpenDiagnostics,
  }: {
    initialDestination?: string;
    onOpenHardware?: () => void;
    onOpenDocumentPrinting?: () => void;
    onOpenDiagnostics?: () => void;
  }) => (
    <section aria-label="settings">
      <output aria-label="settings destination">{initialDestination}</output>
      <button type="button" onClick={onOpenHardware}>Open devices</button>
      <button type="button" onClick={onOpenDocumentPrinting}>Open printing</button>
      <button type="button" onClick={onOpenDiagnostics}>Open diagnostics</button>
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/HardwareSettingsScreen", () => ({
  HardwareSettingsScreen: ({
    mode,
    onNavigateSettings,
    onOpenProductLabels,
  }: {
    mode?: string;
    onNavigateSettings?: (destination: "account") => void;
    onOpenProductLabels?: () => void;
  }) => (
    <section aria-label="hardware settings">
      <output aria-label="hardware mode">{mode}</output>
      <button type="button" onClick={() => onNavigateSettings?.("account")}>Open account settings</button>
      {onOpenProductLabels ? <button type="button" onClick={onOpenProductLabels}>Open product labels</button> : null}
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/SaleProductLabelDialog", () => ({
  SaleProductLabelDialog: ({
    onClose,
    onPrinted,
  }: {
    onClose: () => void;
    onPrinted: (pdf: boolean) => void;
  }) => (
    <section aria-label="product label utility">
      <button type="button" onClick={() => onPrinted(false)}>Print label</button>
      <button type="button" onClick={onClose}>Close label utility</button>
    </section>
  ),
}));

import { App, AppLoadingFallback, SalesUtilityWindowApp } from "./main";

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
});

beforeEach(() => {
  loginSession = session;
  vi.stubGlobal("tpvDesktop", {
    terminalIdentity: {
      load: vi.fn().mockResolvedValue({
        ok: true,
        identity: {
          storeName: "TIENDA DEMO",
          terminalCode: "SERVIDOR",
          terminalId: "terminal-real",
          terminalCredential: "protected-secret"
        }
      })
    }
  });
});

describe("APP VENTA locale wiring", () => {
  it("shows a centered localized loading experience", () => {
    render(<AppLoadingFallback locale="zh" />);

    expect(screen.getByRole("status")).toHaveTextContent("正在加载 APP VENTA");
    expect(screen.getByRole("progressbar", { name: "正在加载 APP VENTA" })).toBeInTheDocument();
    expect(screen.getByText("TPV ERP")).toBeInTheDocument();
  });

  it("loads the user's preference on login, persists changes, and resets to Spanish on logout", async () => {
    localStorage.setItem(saleUserLocaleStorageKey(session), "en");
    render(<App />);

    expect(await screen.findByLabelText("login locale")).toHaveTextContent("es");
    fireEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(screen.getByLabelText("home locale")).toHaveTextContent("en");

    fireEvent.click(screen.getByRole("button", { name: "Change home locale" }));
    expect(screen.getByLabelText("home locale")).toHaveTextContent("zh");
    expect(localStorage.getItem(saleUserLocaleStorageKey(session))).toBe("zh");

    fireEvent.click(screen.getByRole("button", { name: "Log out" }));
    expect(screen.getByLabelText("login locale")).toHaveTextContent("es");
  });

  it("opens filtered customer receivables from the sale sidebar", async () => {
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open sales" }));
    fireEvent.click(await screen.findByRole("button", { name: "Open sale receivables" }));
    expect(await screen.findByLabelText("receivables")).toHaveTextContent("customer-from-sale");
    expect(screen.getByLabelText("sale")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Close receivables" }));
    expect(screen.queryByLabelText("receivables")).not.toBeInTheDocument();
    expect(await screen.findByLabelText("sale")).toBeVisible();
  });

  it("routes permitted terminal settings and opens the real product-label utility", async () => {
    loginSession = {
      ...session,
      permissions: ["CONFIGURACION_TERMINAL"],
    };
    const openSalesUtility = vi.fn().mockResolvedValue({ ok: true, printed: true, pdf: false });
    vi.stubGlobal("tpvDesktop", {
      terminalIdentity: {
        load: vi.fn().mockResolvedValue({
          ok: true,
          identity: {
            storeName: "TIENDA DEMO",
            terminalCode: "SERVIDOR",
            terminalId: "terminal-real",
            terminalCredential: "protected-secret",
          },
        }),
      },
      salesUtilities: { open: openSalesUtility },
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open settings" }));
    expect(await screen.findByLabelText("settings destination")).toHaveTextContent("sale");

    fireEvent.click(screen.getByRole("button", { name: "Open devices" }));
    expect(await screen.findByLabelText("hardware mode")).toHaveTextContent("devices");
    fireEvent.click(screen.getByRole("button", { name: "Open account settings" }));
    expect(await screen.findByLabelText("settings destination")).toHaveTextContent("account");

    fireEvent.click(screen.getByRole("button", { name: "Open diagnostics" }));
    expect(await screen.findByLabelText("hardware mode")).toHaveTextContent("diagnostics");
    fireEvent.click(screen.getByRole("button", { name: "Open account settings" }));

    fireEvent.click(screen.getByRole("button", { name: "Open printing" }));
    expect(await screen.findByLabelText("hardware mode")).toHaveTextContent("printing");
    fireEvent.click(screen.getByRole("button", { name: "Open product labels" }));

    await waitFor(() => expect(openSalesUtility).toHaveBeenCalledWith(expect.objectContaining({
      kind: "PRODUCT_LABEL",
      locale: "es",
      session: loginSession,
      terminalContext: expect.objectContaining({ terminalId: "terminal-real" }),
    })));
    expect(await screen.findByRole("alert")).toHaveTextContent("Etiqueta enviada a la impresora");
  });

  it("does not mount protected terminal screens without CONFIGURACION_TERMINAL", async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open settings" }));
    expect(await screen.findByLabelText("settings destination")).toHaveTextContent("account");

    fireEvent.click(screen.getByRole("button", { name: "Open devices" }));
    expect(await screen.findByLabelText("settings")).toBeVisible();
    expect(screen.getByLabelText("settings destination")).toHaveTextContent("account");
    expect(screen.queryByLabelText("hardware settings")).not.toBeInTheDocument();
  });

  it("does not expose the product-label CTA when the desktop bridge is unavailable", async () => {
    loginSession = { ...session, permissions: ["CONFIGURACION_TERMINAL"] };
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open settings" }));
    fireEvent.click(await screen.findByRole("button", { name: "Open printing" }));

    expect(await screen.findByLabelText("hardware mode")).toHaveTextContent("printing");
    expect(screen.queryByRole("button", { name: "Open product labels" })).not.toBeInTheDocument();
  });

  it("asks for confirmation before Escape returns from a main screen to Home", async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open sales" }));
    expect(await screen.findByLabelText("sale")).toBeVisible();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("alertdialog")).toHaveTextContent(/volver al inicio/i);

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByLabelText("sale")).toBeVisible();

    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.keyDown(window, { key: "Enter" });
    expect(await screen.findByLabelText("home")).toBeVisible();
  });

  it("shows an accessible notice when the sales document window cannot open", async () => {
    const openSalesDocuments = vi.fn().mockResolvedValue({ ok: false, message: "private technical detail" });
    vi.stubGlobal("tpvDesktop", {
      terminalIdentity: {
        load: vi.fn().mockResolvedValue({
          ok: true,
          identity: {
            storeName: "TIENDA DEMO",
            terminalCode: "SERVIDOR",
            terminalId: "terminal-real",
            terminalCredential: "protected-secret"
          }
        })
      },
      salesDocuments: {
        open: openSalesDocuments,
        close: vi.fn().mockResolvedValue(undefined)
      }
    });
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open sales" }));
    fireEvent.click(await screen.findByRole("button", { name: "Open sales document window" }));

    const notice = await screen.findByRole("alert");
    expect(openSalesDocuments).toHaveBeenCalledWith(expect.objectContaining({
      interfaceMode: "KEYBOARD",
    }));
    expect(notice).toHaveTextContent("No se pudo abrir la ventana de venta documental");
    expect(notice).not.toHaveTextContent("private technical detail");
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("waits for a successful backend compatibility check before opening APP VENTA", async () => {
    loginSession = { ...session, accessToken: "token" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      backendVersion: "2.0.0", apiVersion: "1", minimumFrontendVersion: "0.0.1",
      capabilities: ["PAYMENT_IDEMPOTENCY", "PAYMENT_RECOVERY", "PAYMENT_STATUS_QUERY", "PAYMENT_VOID",
        "PAYMENT_REFUND", "PAYMENT_RECONCILIATION", "CORRELATION_ID"], paymentStates: {}
    }), { status: 200 })));
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    expect(screen.getByRole("status")).toHaveTextContent("Comprobando compatibilidad");
    await waitFor(() => expect(screen.getByLabelText("home")).toBeVisible());
  });

  it("blocks payments when the backend is too old to expose compatibility", async () => {
    loginSession = { ...session, accessToken: "token" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 404 })));
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("no son compatibles"));
    expect(screen.getByRole("alert")).toHaveTextContent("BACKEND_TOO_OLD");
    expect(screen.queryByLabelText("home")).not.toBeInTheDocument();
  });

  it("blocks payments when required recovery capabilities are missing", async () => {
    loginSession = { ...session, accessToken: "token" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      backendVersion: "1.0.0", apiVersion: "1", minimumFrontendVersion: "0.0.1",
      capabilities: ["PAYMENT_IDEMPOTENCY"], paymentStates: {}
    }), { status: 200 })));
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("MISSING_CAPABILITIES"));
    expect(screen.queryByLabelText("home")).not.toBeInTheDocument();
  });

  it("blocks login when the protected terminal identity is missing", async () => {
    vi.stubGlobal("tpvDesktop", {
      terminalIdentity: { load: vi.fn().mockResolvedValue({ ok: true, identity: null }) }
    });

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Terminal no configurado");
    expect(screen.queryByRole("button", { name: "Log in" })).not.toBeInTheDocument();
  });

  it("keeps the Ctrl+I label window open after printing until manual close", async () => {
    const complete = vi.fn().mockResolvedValue({ ok: true });
    const close = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([]), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    vi.stubGlobal("tpvDesktop", {
      salesUtilities: {
        consumeBootstrap: vi.fn().mockResolvedValue({
          kind: "PRODUCT_LABEL",
          locale: "es",
          session: { ...session, accessToken: "token" },
          terminalContext: { storeName: "TIENDA DEMO", terminalCode: "SERVIDOR" },
        }),
        complete,
        close,
      },
    });

    render(<SalesUtilityWindowApp />);
    const utility = await screen.findByLabelText("product label utility");

    fireEvent.click(screen.getByRole("button", { name: "Print label" }));
    expect(utility).toBeInTheDocument();
    expect(complete).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Close label utility" }));
    expect(complete).toHaveBeenCalledWith({ printed: true, pdf: false });
    expect(close).not.toHaveBeenCalled();
  });
});

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
    onOpenCustomerReceivables,
    onOpenSales,
  }: {
    locale: LocaleCode;
    onLocaleChange: (locale: LocaleCode) => void;
    onLogout: () => void;
    onOpenCustomerReceivables?: () => void;
    onOpenSales?: () => void;
  }) => (
    <section aria-label="home">
      <output aria-label="home locale">{locale}</output>
      <button type="button" onClick={() => onLocaleChange("zh")}>Change home locale</button>
      <button type="button" onClick={onLogout}>Log out</button>
      <button type="button" onClick={onOpenCustomerReceivables}>Open receivables</button>
      <button type="button" onClick={onOpenSales}>Open sales</button>
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/CustomerReceivablesScreen", () => ({
  CustomerReceivablesScreen: ({ initialCustomerId, onBack }: { initialCustomerId?: string; onBack: () => void }) => <section aria-label="receivables"><output>{initialCustomerId}</output><button onClick={onBack}>Back home</button></section>
}));

vi.mock("../../../packages/app-common/src/components/SaleScreen", () => ({
  SaleScreen: ({ onOpenCustomerReceivables }: { onOpenCustomerReceivables?: (customerId?: string) => void }) => (
    <section aria-label="sale">
      <button type="button" onClick={() => onOpenCustomerReceivables?.("customer-from-sale")}>Open sale receivables</button>
    </section>
  ),
}));

import { App, AppLoadingFallback } from "./main";

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

  it("opens the customer receivables screen from home", async () => {
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open receivables" }));
    expect(await screen.findByLabelText("receivables")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Back home" }));
    expect(screen.getByLabelText("home")).toBeVisible();
  });

  it("opens filtered customer receivables from the sale sidebar", async () => {
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Log in" }));
    fireEvent.click(screen.getByRole("button", { name: "Open sales" }));
    fireEvent.click(await screen.findByRole("button", { name: "Open sale receivables" }));
    expect(await screen.findByLabelText("receivables")).toHaveTextContent("customer-from-sale");
    fireEvent.click(screen.getByRole("button", { name: "Back home" }));
    expect(await screen.findByLabelText("sale")).toBeVisible();
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
});

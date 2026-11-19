// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import { loadWebTerminalStatus, type WebTerminalStatus } from "../../../packages/app-common/src/webTerminalIdentity";
import { loadBackendCompatibility } from "../../../packages/app-common/src/api/compatibility";
import { ApiConnectionError } from "../../../packages/app-common/src/api/client";

vi.mock("react-dom/client", () => ({ createRoot: vi.fn(() => ({ render: vi.fn() })) }));
vi.mock("../../../packages/app-common/src/webTerminalIdentity", async importOriginal => ({
  ...await importOriginal<typeof import("../../../packages/app-common/src/webTerminalIdentity")>(), loadWebTerminalStatus: vi.fn(),
}));
vi.mock("../../../packages/app-common/src/api/compatibility", () => ({
  loadBackendCompatibility: vi.fn(), evaluateCompatibility: () => ({ compatible: true }),
  InvalidCompatibilityContractError: class extends Error {},
}));
vi.mock("../../../packages/app-common/src/sale/useSaleControlDelivery", () => ({ useSaleControlDelivery: () => ({ delivery: undefined }) }));
vi.mock("../../../packages/app-common/src/components/BackendConnectionRecovery", () => ({ BackendConnectionRecovery: () => null }));
let user: UserSession = { username: "cashier", displayName: "Cashier", accessToken: "user-token", permissions: ["VENTA"] };
vi.mock("./VentaLoginScreen", () => ({ default: ({ onLogin }: { onLogin: (user: UserSession) => Promise<void> }) =>
  <button onClick={() => void onLogin(user)}>Sign in</button> }));
vi.mock("../../../packages/app-common/src/components/SaleScreen", () => ({ SaleScreen: ({ onLogout }: { onLogout: () => void }) =>
  <section aria-label="web sale"><button onClick={onLogout}>Sign out</button></section> }));
vi.mock("../../../packages/app-common/src/components/SessionHomeScreen", () => ({ SessionHomeScreen: () => <section aria-label="web home" /> }));

import { App } from "./main";
const active: WebTerminalStatus = { status: "ACTIVE", slots: [], context: {
  credentialMode: "browser-cookie", terminalCode: "002", terminalId: "terminal", installationId: "installation", bindingId: "binding", storeId: "store", storeName: "Store",
} };
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.clearAllMocks(); vi.stubEnv("DEV", true); vi.stubEnv("VITE_TPV_WEB_TERMINAL", "true"); vi.stubGlobal("tpvDesktop", undefined);
  vi.mocked(loadWebTerminalStatus).mockResolvedValue(active);
  vi.mocked(loadBackendCompatibility).mockResolvedValue({} as Awaited<ReturnType<typeof loadBackendCompatibility>>);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 200 })));
  user = { username: "cashier", displayName: "Cashier", accessToken: "user-token", permissions: ["VENTA"] };
});
afterEach(() => { cleanup(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

describe("browser Venta terminal integration", () => {
  it("shows registration for an unapproved browser instead of using the development terminal", async () => {
    vi.mocked(loadWebTerminalStatus).mockResolvedValue({ status: "UNREGISTERED", slots: [] });
    render(<App />);
    expect(await screen.findByRole("heading", { name: "Prepara tu punto de venta" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("waits for terminal revalidation and compatibility before opening the TPV", async () => {
    const status = deferred<WebTerminalStatus>(); const compatibility = deferred<Awaited<ReturnType<typeof loadBackendCompatibility>>>();
    vi.mocked(loadWebTerminalStatus).mockResolvedValueOnce(active).mockReturnValueOnce(status.promise);
    vi.mocked(loadBackendCompatibility).mockReturnValue(compatibility.promise);
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    expect(screen.queryByLabelText("web sale")).not.toBeInTheDocument();
    expect(loadBackendCompatibility).not.toHaveBeenCalled();
    await act(async () => status.resolve(active));
    await waitFor(() => expect(loadBackendCompatibility).toHaveBeenCalledWith("user-token"));
    expect(screen.queryByLabelText("web sale")).not.toBeInTheDocument();
    await act(async () => compatibility.resolve({} as Awaited<ReturnType<typeof loadBackendCompatibility>>));
    expect(await screen.findByLabelText("web sale")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/logout"), expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer user-token" }) })));
  });

  it("keeps a user without sale permission on the permitted home screen", async () => {
    user = { ...user, permissions: ["GESTION_PRODUCTO"] };
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    expect(await screen.findByLabelText("web home")).toBeInTheDocument();
    expect(screen.queryByLabelText("web sale")).not.toBeInTheDocument();
  });

  it("revokes a just-issued user token if the registration was released during login", async () => {
    vi.mocked(loadWebTerminalStatus).mockResolvedValueOnce(active).mockResolvedValue({ status: "RELEASED", slots: [] });
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("heading", { name: "Este navegador necesita un nuevo registro" })).toBeInTheDocument();
    expect(screen.queryByLabelText("web sale")).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/logout"), expect.objectContaining({ method: "POST" }));
  });

  it("revokes a pending login token when the app unmounts", async () => {
    const status = deferred<WebTerminalStatus>();
    vi.mocked(loadWebTerminalStatus).mockResolvedValueOnce(active).mockReturnValueOnce(status.promise);
    const view = render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    view.unmount(); await act(async () => status.resolve(active));
    expect(loadBackendCompatibility).not.toHaveBeenCalled();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/logout"), expect.objectContaining({ method: "POST" }));
  });

  it("returns to registration when a visible tab discovers that its terminal was revoked", async () => {
    const visible = vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    expect(await screen.findByLabelText("web sale")).toBeInTheDocument();
    vi.mocked(loadWebTerminalStatus).mockResolvedValue({ status: "DISABLED", slots: [] });
    fireEvent(window, new Event("focus"));
    expect(await screen.findByRole("heading", { name: "Puesto desactivado" })).toBeInTheDocument();
    expect(screen.queryByLabelText("web sale")).not.toBeInTheDocument();
    visible.mockRestore();
  });

  it("keeps the active cart mounted during a transient background connection failure", async () => {
    const visible = vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    render(<App />); fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    const sale = await screen.findByLabelText("web sale");
    vi.mocked(loadWebTerminalStatus).mockRejectedValue(new ApiConnectionError());
    await act(async () => fireEvent(window, new Event("focus")));
    expect(screen.getByLabelText("web sale")).toBe(sale);
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining("/auth/logout"), expect.anything());
    visible.mockRestore();
  });
});

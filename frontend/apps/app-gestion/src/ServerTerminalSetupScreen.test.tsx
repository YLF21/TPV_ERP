/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { StrictMode, useState } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ServerTerminalSetupScreen } from "./ServerTerminalSetupScreen";

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
function deferred() {
  let resolve!: (value: Response) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<Response>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}
type Route = (options?: RequestInit) => Response | Promise<Response>;
function backend(overrides: Record<string, Route> = {}) {
  const routes: Record<string, Route> = {
    "GET /auth/login": () => new Response(null, { status: 200 }),
    "GET /installation/status": () => response({ organizationProvisioned: false, initialAdminPasswordChangeRequired: true }),
    "GET /__tpv/backend-address": () => response({ backendLabel: "LOCAL", backendUrl: "http://127.0.0.1:8080" }),
    "POST /licenses/link-saas/bootstrap-initial": () => response({ licenseReference: "LIC-1" }),
    "POST /auth/installation-login": () => response({ accessToken: "installation-token", mustChangePassword: true }),
    "PUT /auth/installation-password": () => response({ accessToken: "changed-token", mustChangePassword: false }),
    "POST /licenses/link-saas/bootstrap-empty": () => response({ licenseReference: "LIC-1" }),
    "POST /auth/logout": () => new Response(null, { status: 204 }),
    ...overrides,
  };
  return vi.spyOn(globalThis, "fetch").mockImplementation((url, options) => {
    const pathname = new URL(String(url), "http://localhost").pathname.replace(/^\/api\/v1/, "");
    const key = `${options?.method ?? "GET"} ${pathname}`;
    const route = routes[key];
    if (!route) throw new Error(`Unexpected request: ${key}`);
    return Promise.resolve(route(options));
  });
}
function setup(strict = false) {
  const onOrganizationReady = vi.fn();
  const onConfigureConnection = vi.fn();
  const element = <ServerTerminalSetupScreen locale="es" onOrganizationReady={onOrganizationReady} onConfigureConnection={onConfigureConnection} />;
  const view = render(strict ? <StrictMode>{element}</StrictMode> : element);
  return { ...view, onOrganizationReady, onConfigureConnection };
}
function callsFor(fetchMock: ReturnType<typeof backend>, path: string) {
  return fetchMock.mock.calls.filter(call => String(call[0]).endsWith(path));
}
async function link() {
  fireEvent.change(await screen.findByLabelText("Código de emparejamiento"), { target: { value: " PAIR-NEW " } });
  await waitFor(() => expect(screen.getByRole("button", { name: "Comprobar y vincular" })).toBeEnabled());
  fireEvent.click(screen.getByRole("button", { name: "Comprobar y vincular" }));
}
function passwords(value: string, confirmation = value) {
  fireEvent.change(screen.getByLabelText("Nueva contraseña del administrador"), { target: { value } });
  fireEvent.change(screen.getByLabelText("Confirmar nueva contraseña"), { target: { value: confirmation } });
  fireEvent.click(screen.getByRole("button", { name: "Guardar contraseña y continuar" }));
}

function translatedSetup() {
  function Harness() {
    const [locale, setLocale] = useState<"es" | "en" | "zh">("es");
    return <ServerTerminalSetupScreen locale={locale} onLocaleChange={setLocale}
      onOrganizationReady={() => undefined} onConfigureConnection={() => undefined} />;
  }
  return render(<Harness />);
}

describe("ServerTerminalSetupScreen", () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); delete window.tpvDesktop; });

  it("translates the existing error and keeps the code without checking the backend again", async () => {
    const fetchMock = backend({ "POST /licenses/link-saas/bootstrap-initial": () => response({ code: "INVALID_LICENSE" }, 422) });
    translatedSetup(); await link();
    expect(await screen.findByRole("alert")).toHaveTextContent("Comprueba el código");
    const statusCalls = callsFor(fetchMock, "/installation/status").length;
    fireEvent.click(screen.getByRole("button", { name: "Cambiar idioma" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "English EN" }));
    expect(screen.getByRole("heading", { name: "Set up installation" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Check the code and retry");
    expect(screen.getByLabelText("Pairing code")).toHaveValue(" PAIR-NEW ");
    fireEvent.click(screen.getByRole("button", { name: "Change language" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "中文 ZH" }));
    expect(screen.getByRole("heading", { name: "配置安装实例" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("请检查配对码后重试");
    expect(callsFor(fetchMock, "/installation/status")).toHaveLength(statusCalls);
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-initial")).toHaveLength(1);
  });

  it("allows language changes while pairing is pending and while replacing the ADMIN password", async () => {
    const pairing = deferred();
    const fetchMock = backend({ "POST /licenses/link-saas/bootstrap-initial": () => pairing.promise });
    translatedSetup(); await link();
    fireEvent.click(screen.getByRole("button", { name: "Cambiar idioma" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "English EN" }));
    await act(async () => pairing.resolve(response({ licenseReference: "LIC-1" })));
    await screen.findByLabelText("New administrator password");
    fireEvent.change(screen.getByLabelText("New administrator password"), { target: { value: "1234" } });
    fireEvent.change(screen.getByLabelText("Confirm new password"), { target: { value: "4321" } });
    fireEvent.click(screen.getByRole("button", { name: "Save password and continue" }));
    expect(screen.getByRole("alert")).toHaveTextContent("The passwords do not match");
    fireEvent.click(screen.getByRole("button", { name: "Change language" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "中文 ZH" }));
    expect(screen.getByLabelText("管理员新密码")).toHaveValue("1234");
    expect(screen.getByLabelText("确认新密码")).toHaveValue("4321");
    expect(screen.getByRole("alert")).toHaveTextContent("两次输入的密码不一致");
    expect(callsFor(fetchMock, "/installation/status")).toHaveLength(1);
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-initial")).toHaveLength(1);
    expect(callsFor(fetchMock, "/auth/installation-login")).toHaveLength(1);
  });

  it("waits for the connection and installation status before offering only a pairing code", async () => {
    const health = deferred(); const status = deferred();
    const fetchMock = backend({ "GET /auth/login": () => health.promise, "GET /installation/status": () => status.promise });
    const view = setup();
    expect(screen.queryByLabelText("Código de emparejamiento")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "CONFIGURAR CONEXIÓN" })).toBeEnabled();
    await act(async () => health.resolve(new Response(null, { status: 200 })));
    await waitFor(() => expect(callsFor(fetchMock, "/installation/status")).toHaveLength(1));
    expect(screen.queryByLabelText("Código de emparejamiento")).not.toBeInTheDocument();
    await act(async () => status.resolve(response({ organizationProvisioned: false, initialAdminPasswordChangeRequired: true })));
    expect(await screen.findByLabelText("Código de emparejamiento")).toBeInTheDocument();
    expect(screen.queryByLabelText("Contraseña")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Administrador de la instalación")).not.toBeInTheDocument();
    expect(callsFor(fetchMock, "/auth/installation-login")).toHaveLength(0);
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
  });

  it.each(["LOCAL", "192.168.1.42:8080"])("shows the real connected address %s without consulting SaaS connectivity", async label => {
    const fetchMock = backend({ "GET /__tpv/backend-address": () => response({ backendLabel: label }) });
    setup();
    expect(await screen.findByText(label)).toBeInTheDocument();
    expect(screen.queryByText("/api/v1")).not.toBeInTheDocument();
    expect(callsFor(fetchMock, "/connectivity")).toHaveLength(0);
  });

  it("opens connection settings offline and retries before showing setup", async () => {
    let online = false;
    backend({ "GET /auth/login": () => new Response(null, { status: online ? 200 : 503 }) });
    const view = setup();
    await screen.findByRole("button", { name: "Reintentar conexion" });
    expect(screen.queryByLabelText("Código de emparejamiento")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "CONFIGURAR CONEXIÓN" }));
    expect(view.onConfigureConnection).toHaveBeenCalledOnce();
    online = true;
    fireEvent.click(screen.getByRole("button", { name: "Reintentar conexion" }));
    expect(await screen.findByText("LOCAL")).toBeInTheDocument();
    expect(await screen.findByLabelText("Código de emparejamiento")).toBeInTheDocument();
  });

  it("links with only the code then automatically signs in ADMIN/0000 and requires a password change", async () => {
    const fetchMock = backend(); const view = setup();
    await link();
    await screen.findByLabelText("Nueva contraseña del administrador");
    const bootstrap = callsFor(fetchMock, "/licenses/link-saas/bootstrap-initial")[0];
    expect(JSON.parse(String(bootstrap[1]?.body))).toEqual({ pairingCode: "PAIR-NEW" });
    expect(bootstrap[1]?.headers).not.toHaveProperty("Authorization");
    expect(JSON.parse(String(callsFor(fetchMock, "/auth/installation-login")[0][1]?.body))).toEqual({ userName: "ADMIN", password: "0000" });
    expect(screen.queryByLabelText("Código de emparejamiento")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "CONFIGURAR CONEXIÓN" })).not.toBeInTheDocument();
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
    passwords("123456");
    await waitFor(() => expect(view.onOrganizationReady).toHaveBeenCalledOnce());
    const changed = callsFor(fetchMock, "/auth/installation-password")[0];
    expect(changed[1]?.method).toBe("PUT");
    expect(changed[1]?.headers).toHaveProperty("Authorization", "Bearer installation-token");
    expect(JSON.parse(String(changed[1]?.body))).toEqual({ currentPassword: "0000", newPassword: "123456" });
    expect(callsFor(fetchMock, "/auth/logout").at(-1)?.[1]?.headers).toHaveProperty("Authorization", "Bearer changed-token");
    expect(callsFor(fetchMock, "/terminals/server/provision")).toHaveLength(0);
  });

  it.each([
    ["123", "123", "La nueva contraseña debe contener entre 4 y 12 cifras."],
    ["abcd", "abcd", "La nueva contraseña debe contener entre 4 y 12 cifras."],
    ["1234567890123", "1234567890123", "La nueva contraseña debe contener entre 4 y 12 cifras."],
    ["0000", "0000", "La nueva contraseña debe ser diferente de la actual."],
    ["1234", "4321", "Las contraseñas no coinciden."],
  ])("blocks invalid new password %s/%s", async (value, confirmation, message) => {
    const fetchMock = backend(); const view = setup();
    await link(); await screen.findByLabelText("Nueva contraseña del administrador");
    passwords(value, confirmation);
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(callsFor(fetchMock, "/auth/installation-password")).toHaveLength(0);
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
  });

  it("keeps an invalid code available for correction without exposing manual credentials", async () => {
    const fetchMock = backend({ "POST /licenses/link-saas/bootstrap-initial": () => response({ code: "PAIRING_INVALID" }, 400) });
    const view = setup(); await link();
    expect(await screen.findByRole("alert")).toHaveTextContent("Comprueba el código");
    expect(screen.getByLabelText("Código de emparejamiento")).toHaveValue(" PAIR-NEW ");
    expect(screen.queryByLabelText("Contraseña actual de ADMIN")).not.toBeInTheDocument();
    expect(callsFor(fetchMock, "/installation/status")).toHaveLength(2);
    expect(callsFor(fetchMock, "/auth/installation-login")).toHaveLength(0);
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
  });

  it("shows waiting after two seconds and prevents duplicate pairing requests", async () => {
    const request = deferred();
    const fetchMock = backend({ "POST /licenses/link-saas/bootstrap-initial": () => request.promise });
    const view = setup(); await screen.findByText("LOCAL");
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText("Código de emparejamiento"), { target: { value: "PAIR" } });
    const button = screen.getByRole("button", { name: "Comprobar y vincular" });
    fireEvent.click(button); fireEvent.submit(button.closest("form")!);
    await act(async () => vi.advanceTimersByTime(2001));
    expect(screen.getByText("Espera mientras se completa la operación.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Código de emparejamiento")).not.toBeInTheDocument();
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-initial")).toHaveLength(1);
    await act(async () => request.resolve(response({ licenseReference: "LIC-1" })));
    vi.useRealTimers();
    await screen.findByLabelText("Nueva contraseña del administrador");
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
  });

  it("resumes a provisioned installation still using the initial temporary password", async () => {
    const fetchMock = backend({ "GET /installation/status": () => response({ organizationProvisioned: true, initialAdminPasswordChangeRequired: true }) });
    const view = setup();
    await screen.findByLabelText("Nueva contraseña del administrador");
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-initial")).toHaveLength(0);
    passwords("5555");
    await waitFor(() => expect(view.onOrganizationReady).toHaveBeenCalledOnce());
  });

  it("opens an existing provisioned installation without trying the initial password", async () => {
    const fetchMock = backend({ "GET /installation/status": () => response({ organizationProvisioned: true, initialAdminPasswordChangeRequired: false }) });
    const view = setup(true);
    await waitFor(() => expect(view.onOrganizationReady).toHaveBeenCalledOnce());
    expect(callsFor(fetchMock, "/auth/installation-login")).toHaveLength(0);
  });

  it("recovers a lost pairing response from installation status without relinking", async () => {
    let provisioned = false;
    const fetchMock = backend({
      "GET /installation/status": () => response({ organizationProvisioned: provisioned, initialAdminPasswordChangeRequired: true }),
      "POST /licenses/link-saas/bootstrap-initial": () => { provisioned = true; return Promise.reject(new TypeError("connection lost")); },
    });
    const view = setup(); await link();
    await screen.findByLabelText("Nueva contraseña del administrador");
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-initial")).toHaveLength(1);
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
  });

  it("allows manual ADMIN recovery only after the explicit initial setup rejection", async () => {
    const fetchMock = backend({
      "GET /installation/status": () => response({ organizationProvisioned: false, initialAdminPasswordChangeRequired: false }),
      "POST /licenses/link-saas/bootstrap-initial": () => response({ code: "INITIAL_ADMIN_SETUP_UNAVAILABLE" }, 409),
      "POST /auth/installation-login": () => response({ accessToken: "existing-admin-token", mustChangePassword: false }),
    });
    const view = setup(); await link();
    fireEvent.change(await screen.findByLabelText("Contraseña actual de ADMIN"), { target: { value: "4321" } });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar y vincular" }));
    await waitFor(() => expect(view.onOrganizationReady).toHaveBeenCalledOnce());
    expect(JSON.parse(String(callsFor(fetchMock, "/auth/installation-login")[0][1]?.body))).toEqual({ userName: "ADMIN", password: "4321" });
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-empty")).toHaveLength(1);
    expect(callsFor(fetchMock, "/auth/installation-password")).toHaveLength(0);
  });

  it("still requires password replacement for manual temporary credentials before authenticated linking", async () => {
    const fetchMock = backend({
      "GET /installation/status": () => response({ organizationProvisioned: false, initialAdminPasswordChangeRequired: false }),
      "POST /licenses/link-saas/bootstrap-initial": () => response({ code: "INITIAL_ADMIN_SETUP_UNAVAILABLE" }, 409),
    });
    const view = setup(); await link();
    fireEvent.change(await screen.findByLabelText("Contraseña actual de ADMIN"), { target: { value: "1111" } });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar y vincular" }));
    await screen.findByLabelText("Nueva contraseña del administrador");
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-empty")).toHaveLength(0);
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
    passwords("2222");
    await waitFor(() => expect(view.onOrganizationReady).toHaveBeenCalledOnce());
    expect(JSON.parse(String(callsFor(fetchMock, "/auth/installation-password")[0][1]?.body))).toEqual({ currentPassword: "1111", newPassword: "2222" });
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-empty")[0][1]?.headers).toHaveProperty("Authorization", "Bearer changed-token");
  });

  it("reauthenticates the current ADMIN password when a manual password-change response is lost", async () => {
    let passwordChanged = false;
    const fetchMock = backend({
      "GET /installation/status": () => response({ organizationProvisioned: false, initialAdminPasswordChangeRequired: false }),
      "POST /licenses/link-saas/bootstrap-initial": () => response({ code: "INITIAL_ADMIN_SETUP_UNAVAILABLE" }, 409),
      "POST /auth/installation-login": options => {
        const body = JSON.parse(String(options?.body));
        if (body.password !== (passwordChanged ? "2222" : "1111")) return response({ code: "INVALID_CREDENTIALS" }, 401);
        return response({ accessToken: passwordChanged ? "reauthenticated-token" : "old-token", mustChangePassword: !passwordChanged });
      },
      "PUT /auth/installation-password": () => {
        passwordChanged = true;
        return Promise.reject(new TypeError("password response lost"));
      },
    });
    const view = setup(); await link();
    fireEvent.change(await screen.findByLabelText("Contraseña actual de ADMIN"), { target: { value: "1111" } });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar y vincular" }));
    await screen.findByLabelText("Nueva contraseña del administrador");
    passwords("2222");
    expect(await screen.findByLabelText("Contraseña actual de ADMIN")).toHaveValue("");
    expect(screen.queryByLabelText("Nueva contraseña del administrador")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Confirmar nueva contraseña")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Introduce esa contraseña para continuar");
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-empty")).toHaveLength(0);
    expect(callsFor(fetchMock, "/auth/logout")[0][1]?.headers).toHaveProperty("Authorization", "Bearer old-token");
    fireEvent.change(screen.getByLabelText("Contraseña actual de ADMIN"), { target: { value: "2222" } });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar y vincular" }));
    await waitFor(() => expect(view.onOrganizationReady).toHaveBeenCalledOnce());
    expect(JSON.parse(String(callsFor(fetchMock, "/auth/installation-login").at(-1)?.[1]?.body))).toEqual({ userName: "ADMIN", password: "2222" });
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-empty")).toHaveLength(1);
    expect(callsFor(fetchMock, "/licenses/link-saas/bootstrap-empty")[0][1]?.headers).toHaveProperty("Authorization", "Bearer reauthenticated-token");
    expect(callsFor(fetchMock, "/auth/installation-password")).toHaveLength(1);
  });

  it("does not continue after unmount while pairing is pending", async () => {
    const request = deferred();
    const fetchMock = backend({ "POST /licenses/link-saas/bootstrap-initial": () => request.promise });
    const view = setup(); await link(); view.unmount();
    await act(async () => request.resolve(response({ licenseReference: "LIC-1" })));
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
    expect(callsFor(fetchMock, "/auth/installation-login")).toHaveLength(0);
  });

  it("revokes a late installation token without opening the next screen after unmount", async () => {
    const request = deferred();
    const fetchMock = backend({ "POST /auth/installation-login": () => request.promise });
    const view = setup(); await link();
    await waitFor(() => expect(callsFor(fetchMock, "/auth/installation-login")).toHaveLength(1));
    view.unmount();
    await act(async () => request.resolve(response({ accessToken: "late-token", mustChangePassword: false })));
    expect(view.onOrganizationReady).not.toHaveBeenCalled();
    expect(callsFor(fetchMock, "/auth/logout")[0][1]?.headers).toHaveProperty("Authorization", "Bearer late-token");
  });
});

/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { connectionUrl, TerminalConnectionScreen } from "./TerminalConnectionScreen";

afterEach(() => { cleanup(); vi.useRealTimers(); delete window.tpvDesktop; });

const sharedIdentity = { installationId: "installation", bindingId: "binding", terminalId: "terminal",
  terminalCode: "002", terminalName: "CAJA 2", storeName: "Tienda" };
const activeLink = { requestId: "request", bindingId: "binding", terminalId: "terminal", terminalCode: "002",
  terminalName: "CAJA 2", storeId: "store", storeName: "Tienda", installationId: "installation", status: "ACTIVE" as const };
const verifiedIdentity = { ...sharedIdentity, terminalCredential: "verified-secret" };

function setBridge(load: ReturnType<typeof vi.fn>, overrides: Record<string, unknown> = {}) {
  const bridge = { load, discover: vi.fn(), probe: vi.fn(), requestLink: vi.fn(), refreshLink: vi.fn(),
    cancelLink: vi.fn(), restart: vi.fn().mockResolvedValue({ ok: true }), saveAddress: vi.fn(), ...overrides };
  window.tpvDesktop = { closeApplication: vi.fn(), backendConnection: bridge } as unknown as Window["tpvDesktop"];
  return bridge;
}

const unlinkedLoad = { ok: true, deviceName: "PC 2", identity: null,
  configuration: { backendUrl: "https://store.example:8443" }, link: null };

describe("terminal connection address", () => {
  it("requires HTTPS for remote servers and only allows local HTTP", () => {
    expect(connectionUrl("localhost", "8080")).toBe("http://localhost:8080");
    expect(connectionUrl("store.example", "8443")).toBe("https://store.example:8443");
    expect(connectionUrl("10.0.0.5", "8443")).toBe("https://10.0.0.5:8443");
  });
  it("rejects credentials, paths, embedded ports and invalid ports", () => {
    expect(connectionUrl("user@store.example", "8443")).toBeNull();
    expect(connectionUrl("store.example/path", "8443")).toBeNull();
    expect(connectionUrl("store.example:8443", "8443")).toBeNull();
    expect(connectionUrl("store.example", "65536")).toBeNull();
  });
});

describe("terminal linking restart", () => {
  it("embeds connection after support in the shared settings navigation", async () => {
    const load = vi.fn().mockResolvedValue(unlinkedLoad);
    setBridge(load);
    const onNavigate = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={sharedIdentity} onReady={vi.fn()}
      settingsShell={{app: "venta", session: {username: "admin", displayName: "ADMIN", permissions: ["ADMIN"]},
        terminalContext: sharedIdentity, onNavigate, onBack: vi.fn(), onLocaleChange: vi.fn()}} />);
    expect(await screen.findByDisplayValue("store.example")).toBeVisible();
    const navigation = screen.getByRole("complementary", {name: "Secciones"});
    const buttons = Array.from(navigation.querySelectorAll("button"));
    expect(buttons.at(-2)).toHaveTextContent("CONFIGURAR CONEXIÓN");
    expect(screen.getByText("Conexión", {selector: ".sale-settings-nav-heading"})).toBeVisible();
    expect(screen.getByRole("button", {name: "CONFIGURAR CONEXIÓN"})).toHaveAttribute("aria-current", "page");
    expect(document.querySelector(".terminal-link-screen")).toBeNull();
    expect(screen.getAllByRole("button", {name: "Volver"})).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", {name: "Impresoras"}));
    expect(onNavigate).toHaveBeenCalledWith("printers");
  });

  it("blocks shared navigation while a connection operation is running", async () => {
    let resolveDiscovery!: (result: unknown) => void;
    setBridge(vi.fn().mockResolvedValue(unlinkedLoad), {
      discover: vi.fn().mockReturnValue(new Promise(resolve => {resolveDiscovery = resolve;}))
    });
    const onBack = vi.fn();
    const onNavigate = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={sharedIdentity} onReady={vi.fn()}
      settingsShell={{app: "venta", session: {username: "admin", displayName: "ADMIN", permissions: ["ADMIN"]},
        terminalContext: sharedIdentity, onNavigate, onBack, onLocaleChange: vi.fn()}} />);
    await screen.findByDisplayValue("store.example");
    fireEvent.click(screen.getByRole("button", {name: "Buscar servidores"}));
    expect(screen.getByRole("button", {name: "Volver"})).toBeDisabled();
    expect(screen.getByRole("button", {name: "Visualización"})).toBeDisabled();
    fireEvent.click(screen.getByRole("button", {name: "Volver"}));
    window.dispatchEvent(new Event("tpv-sale-settings-back", {cancelable: true}));
    expect(onBack).not.toHaveBeenCalled();
    expect(onNavigate).not.toHaveBeenCalled();
    await act(async () => {resolveDiscovery({ok: true, servers: []});});
    expect(screen.getByRole("button", {name: "Volver"})).toBeEnabled();
    fireEvent.click(screen.getByRole("button", {name: "Volver"}));
    expect(onBack).toHaveBeenCalledOnce();
  });

  it("keeps shared navigation blocked when a restart is required", async () => {
    setBridge(vi.fn().mockResolvedValue({...unlinkedLoad, restartRequired: true}));
    render(<TerminalConnectionScreen locale="es" identity={sharedIdentity} onReady={vi.fn()}
      settingsShell={{app: "venta", session: {username: "admin", displayName: "ADMIN", permissions: ["ADMIN"]},
        terminalContext: sharedIdentity, onNavigate: vi.fn(), onBack: vi.fn(), onLocaleChange: vi.fn()}} />);
    await screen.findByDisplayValue("store.example");
    expect(screen.getByRole("button", {name: "Volver"})).toBeDisabled();
    expect(screen.getByRole("button", {name: "Impresoras"})).toBeDisabled();
    expect(screen.getByRole("button", {name: "Reiniciar aplicación"})).toBeEnabled();
  });

  it("explains how to migrate a link created by the other desktop app", async () => {
    window.tpvDesktop = { closeApplication: vi.fn(), backendConnection: {
      load: vi.fn().mockResolvedValue({ ok: false, code: "LINKING_STORAGE_MIGRATION_REQUIRED", message: "internal" }),
      discover: vi.fn(), probe: vi.fn(), requestLink: vi.fn(), refreshLink: vi.fn(),
      cancelLink: vi.fn(), restart: vi.fn(), saveAddress: vi.fn(),
    } };
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={vi.fn()} />);
    expect(await screen.findByText(/Abre primero la aplicación actualizada/)).toBeTruthy();
    expect(screen.queryByText("internal")).toBeNull();
  });

  it("repairs a changed backend IP using non-secret metadata when the old address is offline", async () => {
    const saveAddress = vi.fn().mockResolvedValue({ ok: true, restartRequired: true });
    const onReady = vi.fn();
    window.tpvDesktop = { closeApplication: vi.fn(), backendConnection: {
      load: vi.fn().mockResolvedValue({ ok: true, deviceName: "PC 2", identity: null,
        linkedIdentity: { installationId: "installation", bindingId: "binding", terminalId: "terminal", terminalCode: "002", storeName: "Tienda" },
        link: { status: "ACTIVE", terminalCode: "002", terminalName: "CAJA 2" },
        configuration: { backendUrl: "https://old.example:8443" } }),
      discover: vi.fn(), requestLink: vi.fn(), refreshLink: vi.fn(), cancelLink: vi.fn(), restart: vi.fn(), saveAddress,
      probe: vi.fn().mockResolvedValue({ ok: true, sameInstallation: true, localServer: false,
        server: { installationId: "installation", storeId: "store", maxWindows: 3, slots: [] } }),
    } };
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    await waitFor(() => expect((screen.getByRole("textbox", { name: "Equipo o dominio" }) as HTMLInputElement).value).toBe("old.example"));
    fireEvent.change(screen.getByRole("textbox", { name: "Equipo o dominio" }), { target: { value: "new.example" } });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar conexión" }));
    fireEvent.click(await screen.findByRole("button", { name: "Guardar dirección" }));
    await waitFor(() => expect(saveAddress).toHaveBeenCalledWith({ backendUrl: "https://new.example:8443" }));
    expect(await screen.findByRole("button", { name: "Reiniciar aplicación" })).toBeTruthy();
    expect(onReady).not.toHaveBeenCalled();
  });
  it("keeps approved identity behind an explicit restart when the proxy still targets the old address", async () => {
    const onReady = vi.fn();
    const restart = vi.fn().mockResolvedValue({ ok: true });
    const active = { requestId: "request", bindingId: "binding", terminalId: "terminal",
      terminalCode: "002", terminalName: "CAJA 2", storeId: "store", storeName: "Tienda",
      installationId: "installation", status: "ACTIVE" as const };
    const pending = { ...active, status: "PENDING" as const };
    window.tpvDesktop = {
      closeApplication: vi.fn(),
      backendConnection: {
        load: vi.fn().mockResolvedValue({ ok: true, deviceName: "Equipo", link: pending,
          configuration: { backendUrl: "https://store.example:8443" } }),
        discover: vi.fn(), probe: vi.fn(), requestLink: vi.fn(),
        refreshLink: vi.fn().mockResolvedValue({ ok: true, link: active,
          identity: { terminalId: "terminal", terminalCode: "002", terminalCredential: "secret", storeName: "Tienda" },
          restartRequired: true }),
        cancelLink: vi.fn(), saveAddress: vi.fn(), restart,
      },
    };
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    fireEvent.click(await screen.findByRole("button", { name: "Consultar estado" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Reiniciar aplicación" })).toBeTruthy());
    expect(onReady).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Reiniciar aplicación" }));
    await waitFor(() => expect(restart).toHaveBeenCalledOnce());
  });

  it("adopts the assigned code of a legacy terminal without offering a fresh slot", async () => {
    const requestLink = vi.fn().mockResolvedValue({ ok: true, link: { status: "PENDING", terminalCode: "003",
      terminalName: "CAJA ANTIGUA", requestId: "request", bindingId: "binding", terminalId: "legacy",
      storeId: "store", storeName: "Tienda", installationId: "installation" } });
    window.tpvDesktop = { closeApplication: vi.fn(), backendConnection: {
      load: vi.fn().mockResolvedValue({ ok: true, deviceName: "PC antiguo",
        legacyIdentity: { terminalId: "legacy", terminalCode: "OLD", storeName: "Tienda" },
        configuration: { backendUrl: "https://store.example:8443" } }),
      discover: vi.fn(),
      probe: vi.fn().mockResolvedValue({ ok: true, sameInstallation: false, localServer: false,
        server: { protocolVersion: 1, installationId: "installation", installationReference: "INST",
          storeId: "store", storeName: "Tienda", publicKey: "key", challenge: "challenge", signature: "signature",
          maxWindows: 4, slots: [
            { code: "002", status: "FREE" },
            { code: "003", status: "ACTIVE", name: "CAJA ANTIGUA", terminalId: "legacy" },
          ] } }),
      requestLink, refreshLink: vi.fn(), cancelLink: vi.fn(), saveAddress: vi.fn(), restart: vi.fn(),
    } };
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Comprobar conexión" }));
    fireEvent.click(await screen.findByRole("button", { name: "Conservar terminal existente" }));
    await waitFor(() => expect(requestLink).toHaveBeenCalledWith({
      backendUrl: "https://store.example:8443", code: "003", name: "CAJA ANTIGUA",
    }));
    expect(screen.queryByText("002")).toBeNull();
  });
});

describe("shared terminal binding between desktop apps", () => {
  it.each([
    ["VENTA to GESTIÓN", "VENTA", "GESTIÓN"],
    ["GESTIÓN to VENTA", "GESTIÓN", "VENTA"],
  ])("recognizes an existing %s binding with the same code and name", async (_direction, first, second) => {
    const load = vi.fn().mockResolvedValue({ ...unlinkedLoad, link: activeLink, identity: verifiedIdentity,
      linkedIdentity: sharedIdentity, restartRequired: false });
    const bridge = setBridge(load);
    const firstReady = vi.fn();
    const firstScreen = render(<TerminalConnectionScreen locale="es" identity={null} onReady={firstReady} />);
    await waitFor(() => expect(firstReady).toHaveBeenCalledWith(verifiedIdentity));
    firstScreen.unmount();
    const secondReady = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={secondReady} />);
    await waitFor(() => expect(secondReady).toHaveBeenCalledWith(verifiedIdentity));
    expect(bridge.requestLink).not.toHaveBeenCalled();
    expect(first).not.toBe(second);
  });

  it("notices an external binding while the assistant remains open without a pending request", async () => {
    const load = vi.fn().mockResolvedValueOnce(unlinkedLoad)
      .mockResolvedValue({ ...unlinkedLoad, link: activeLink, identity: verifiedIdentity, linkedIdentity: sharedIdentity });
    const bridge = setBridge(load);
    const onReady = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    await waitFor(() => expect((screen.getByRole("textbox", { name: "Equipo o dominio" }) as HTMLInputElement).value).toBe("store.example"));
    await waitFor(() => expect(onReady).toHaveBeenCalledOnce(), { timeout: 6500 });
    expect(onReady).toHaveBeenCalledWith(verifiedIdentity);
    expect(bridge.refreshLink).not.toHaveBeenCalled();
    expect(bridge.requestLink).not.toHaveBeenCalled();
  }, 8000);

  it("shows the existing terminal and restart action without offering registration or ADMIN inputs", async () => {
    const load = vi.fn().mockResolvedValue({ ...unlinkedLoad, linkedIdentity: sharedIdentity,
      link: activeLink, restartRequired: true });
    const bridge = setBridge(load, { probe: vi.fn().mockResolvedValue({ ok: true, sameInstallation: true,
      localServer: false, server: { installationId: "installation", storeId: "store", maxWindows: 3,
        slots: [{ code: "003", status: "FREE" }] } }) });
    const onReady = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    expect(await screen.findByText("002 · CAJA 2")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Comprobar conexión" }));
    await waitFor(() => expect(bridge.probe).toHaveBeenCalledOnce());
    expect(screen.getByText(/configuración compartida/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Reiniciar aplicación" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Solicitar vinculación" })).toBeNull();
    expect(screen.queryByRole("textbox", { name: "Nombre de terminal" })).toBeNull();
    expect(screen.queryByRole("textbox", { name: "Usuario ADMIN" })).toBeNull();
    expect(onReady).not.toHaveBeenCalled();
  });

  it.each([false, true])("preserves address, name and terminal selection on focus (manual code: %s)", async manualCode => {
    const load = vi.fn().mockResolvedValueOnce(unlinkedLoad)
      .mockResolvedValue({ ...unlinkedLoad, deviceName: "OTHER PC",
        configuration: { backendUrl: "https://new.example:9443" } });
    const bridge = setBridge(load, { probe: vi.fn().mockResolvedValue({ ok: true, sameInstallation: false,
      localServer: false, server: { installationId: "installation", storeId: "store", maxWindows: 3,
        slots: [{ code: "002", status: "FREE" }, { code: "003", status: "FREE" }] } }) });
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={vi.fn()} />);
    await waitFor(() => expect((screen.getByRole("textbox", { name: "Equipo o dominio" }) as HTMLInputElement).value).toBe("store.example"));
    fireEvent.change(screen.getByRole("textbox", { name: "Equipo o dominio" }), { target: { value: "manual.example" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Puerto" }), { target: { value: "7443" } });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar conexión" }));
    const nameInput = await screen.findByRole("textbox", { name: "Nombre de terminal" });
    fireEvent.change(nameInput, { target: { value: "MI CAJA" } });
    if (manualCode) fireEvent.change(screen.getByRole("combobox", { name: "Código de terminal" }), { target: { value: "003" } });
    fireEvent(window, new Event("focus"));
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
    expect((screen.getByRole("textbox", { name: "Equipo o dominio" }) as HTMLInputElement).value).toBe("manual.example");
    expect((screen.getByRole("textbox", { name: "Puerto" }) as HTMLInputElement).value).toBe("7443");
    expect((screen.getByRole("textbox", { name: "Nombre de terminal" }) as HTMLInputElement).value).toBe("MI CAJA");
    expect((screen.getByRole("combobox", { name: "Código de terminal" }) as HTMLSelectElement).value).toBe(manualCode ? "003" : "002");
  });

  it("checks an immediately active request after the action finishes", async () => {
    const load = vi.fn().mockResolvedValueOnce(unlinkedLoad)
      .mockResolvedValue({ ...unlinkedLoad, link: activeLink, linkedIdentity: sharedIdentity, identity: verifiedIdentity });
    const bridge = setBridge(load, { probe: vi.fn().mockResolvedValue({ ok: true, sameInstallation: false,
      localServer: false, server: { installationId: "installation", storeId: "store", maxWindows: 3,
        slots: [{ code: "002", status: "FREE" }] } }),
      requestLink: vi.fn().mockResolvedValue({ ok: true, link: activeLink, restartRequired: false }) });
    const onReady = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    await waitFor(() => expect((screen.getByRole("textbox", { name: "Equipo o dominio" }) as HTMLInputElement).value).toBe("store.example"));
    fireEvent.click(screen.getByRole("button", { name: "Comprobar conexión" }));
    fireEvent.click(await screen.findByRole("button", { name: "Solicitar vinculación" }));
    await waitFor(() => expect(onReady).toHaveBeenCalledWith(verifiedIdentity));
    expect(load).toHaveBeenCalledTimes(2);
    expect(bridge.refreshLink).not.toHaveBeenCalled();
  });

  it("keeps connection configuration editable and never calls onReady from its load", async () => {
    const load = vi.fn().mockResolvedValue({ ...unlinkedLoad, link: activeLink,
      identity: verifiedIdentity, linkedIdentity: sharedIdentity });
    const bridge = setBridge(load);
    const onReady = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={verifiedIdentity} onReady={onReady} onBack={vi.fn()} />);
    await waitFor(() => expect(load).toHaveBeenCalledOnce());
    fireEvent.change(screen.getByRole("textbox", { name: "Equipo o dominio" }), { target: { value: "manual.example" } });
    expect((screen.getByRole("textbox", { name: "Equipo o dominio" }) as HTMLInputElement).value).toBe("manual.example");
    vi.useFakeTimers();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(load).toHaveBeenCalledOnce();
    expect(onReady).not.toHaveBeenCalled();
    expect(bridge.requestLink).not.toHaveBeenCalled();
  });

  it.each(["RELEASED", "DISABLED", "PENDING"])("does not admit %s metadata to login", async status => {
    const load = vi.fn().mockResolvedValue({ ...unlinkedLoad, link: { ...activeLink, status },
      identity: verifiedIdentity, linkedIdentity: sharedIdentity });
    setBridge(load);
    const onReady = vi.fn();
    render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    await waitFor(() => expect(load).toHaveBeenCalledOnce());
    expect(onReady).not.toHaveBeenCalled();
  });

  it("ignores an outstanding load after unmount", async () => {
    let resolveLoad!: (result: unknown) => void;
    const load = vi.fn().mockReturnValue(new Promise<unknown>(resolve => { resolveLoad = resolve; }));
    setBridge(load);
    const onReady = vi.fn();
    const view = render(<TerminalConnectionScreen locale="es" identity={null} onReady={onReady} />);
    view.unmount();
    await act(async () => { resolveLoad({ ...unlinkedLoad, link: activeLink, identity: verifiedIdentity }); });
    expect(onReady).not.toHaveBeenCalled();
  });
});

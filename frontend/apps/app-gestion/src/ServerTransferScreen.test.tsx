/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { ServerTransferScreen } from "./ServerTransferScreen";
import * as api from "./serverTransferApi";

vi.mock("./serverTransferApi", () => ({
  loadServerTransferStatus: vi.fn(), prepareServerTransfer: vi.fn(), sealServerTransfer: vi.fn(), cancelServerTransfer: vi.fn()
}));

const session: UserSession = { username: "ADMIN", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] };
const baseStatus = { transferId: "fd49837a-6941-43ad-a3f9-b10317645ab5", destinationKeyFingerprint: null, backupSha256: null };

afterEach(() => { cleanup(); vi.clearAllMocks(); Reflect.deleteProperty(window, "tpvDesktop"); });

describe("ServerTransferScreen", () => {
  it("allows a new preparation after the previous destination becomes active", async () => {
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: { requestOrigin: vi.fn() } } });
    vi.mocked(api.loadServerTransferStatus).mockResolvedValue({ ...baseStatus, state: "ACTIVE" });
    render(<ServerTransferScreen session={session} t={createTranslator("es")} onOpenMaintenance={() => {}} />);
    expect(await screen.findByText("Destino activo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Preparar traslado" })).toBeInTheDocument();
  });

  it("does not offer preparation from a retired origin", async () => {
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: { requestOrigin: vi.fn() } } });
    vi.mocked(api.loadServerTransferStatus).mockResolvedValue({ ...baseStatus, state: "ACTIVATED" });
    render(<ServerTransferScreen session={session} t={createTranslator("es")} onOpenMaintenance={() => {}} />);
    expect(await screen.findByText("Origen retirado")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Preparar traslado" })).not.toBeInTheDocument();
  });

  it("enables preparation after importing a destination request", async () => {
    const openRequest = vi.fn().mockResolvedValue({ ok: true, destinationPublicKey: "public-key", destinationProof: "proof" });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: { requestOrigin: vi.fn(), openRequest } } });
    vi.mocked(api.loadServerTransferStatus).mockResolvedValue({ ...baseStatus, state: "NONE" });
    render(<ServerTransferScreen session={session} t={createTranslator("es")} onOpenMaintenance={() => {}} />);
    const prepare = await screen.findByRole("button", { name: "Preparar traslado" });
    expect(prepare).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Importar solicitud" }));
    expect(await screen.findByText("Solicitud del destino cargada.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Contraseña ADMIN"), { target: { value: "admin-secret" } });
    expect(prepare).toBeEnabled();
  });

  it("allows cancelling a sealed transfer with ADMIN reauthentication without offering seal again", async () => {
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: { requestOrigin: vi.fn() } } });
    vi.mocked(api.loadServerTransferStatus).mockResolvedValue({ ...baseStatus, state: "SEALED" });
    vi.mocked(api.cancelServerTransfer).mockResolvedValue({ ...baseStatus, state: "CANCELLED" });
    render(<ServerTransferScreen session={session} t={createTranslator("es")} onOpenMaintenance={() => {}} />);
    const cancel = await screen.findByRole("button", { name: "Cancelar traslado sellado" });
    expect(screen.queryByRole("button", { name: "Sellar traslado" })).not.toBeInTheDocument();
    expect(cancel).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Contraseña ADMIN"), { target: { value: "admin-secret" } });
    fireEvent.click(cancel);
    await waitFor(() => expect(api.cancelServerTransfer).toHaveBeenCalledWith("token", baseStatus.transferId, "admin-secret"));
    expect(api.sealServerTransfer).not.toHaveBeenCalled();
  });
});

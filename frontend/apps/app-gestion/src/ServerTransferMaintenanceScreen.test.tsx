/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator } from "@tpverp/app-common";
import { ServerTransferMaintenanceScreen } from "./ServerTransferMaintenanceScreen";

afterEach(() => { cleanup(); Reflect.deleteProperty(window, "tpvDesktop"); });

describe("ServerTransferMaintenanceScreen", () => {
  it("offers reception after the database was restored but finalization was interrupted", async () => {
    const startMaintenance = vi.fn().mockResolvedValue({ ok: true, started: true });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: {
      readStatus: vi.fn().mockResolvedValue({ ok: true, state: "DATABASE_RESTORED" }), startMaintenance
    } } });
    render(<ServerTransferMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    const receive = await screen.findByRole("button", { name: "Recibir paquete" });
    expect(screen.getByText("Base de datos restaurada; falta completar la recepción")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activar destino" })).not.toBeInTheDocument();
    fireEvent.click(receive);
    expect(startMaintenance).toHaveBeenCalledWith({ action: "receive" });
  });

  it("hides maintenance actions when reading local status fails", async () => {
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: {
      readStatus: vi.fn().mockRejectedValue(new Error("read failed")), startMaintenance: vi.fn()
    } } });
    render(<ServerTransferMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo leer el estado local del traslado.");
    expect(screen.queryByRole("button", { name: "Generar solicitud" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activar destino" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Actualizar estado mostrado" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Consultar estado instalado" }));
    expect(window.tpvDesktop?.serverTransfer?.startMaintenance).toHaveBeenCalledWith({ action: "status" });
  });

  it("only offers activation after reception and lets it resume", async () => {
    const readStatus = vi.fn().mockResolvedValueOnce({ ok: true, state: "RECEIVED" })
      .mockResolvedValue({ ok: true, state: "ACTIVATING" });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { serverTransfer: {
      readStatus, startMaintenance: vi.fn().mockResolvedValue({ ok: true, started: true })
    } } });
    render(<ServerTransferMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    expect(await screen.findByRole("button", { name: "Activar destino" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Actualizar estado mostrado" }));
    await waitFor(() => expect(screen.getByText("Activando destino")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Activar destino" })).toBeInTheDocument();
  });
});

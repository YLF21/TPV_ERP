/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator } from "@tpverp/app-common";
import { DisasterRecoveryMaintenanceScreen } from "./DisasterRecoveryMaintenanceScreen";

afterEach(() => { cleanup(); Reflect.deleteProperty(window, "tpvDesktop"); });

describe("DisasterRecoveryMaintenanceScreen", () => {
  it("has translated quarantine and support gates in ES, EN and ZH", () => {
    for (const locale of ["es", "en", "zh"] as const) {
      const t = createTranslator(locale);
      for (const key of ["gestion.disaster.entry", "gestion.disaster.state.RECEIVED_QUARANTINED",
        "gestion.disaster.step.SUPPORT_REVIEW", "gestion.disaster.activate",
        "gestion.disaster.dataLossWarning", "gestion.disaster.centralEvidenceWarning",
        "gestion.disaster.resumeReceive", "gestion.disaster.revalidateReceive"])
        expect(t(key)).not.toBe(key);
    }
  });

  it("offers only the support check while recovery is quarantined", async () => {
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { disasterRecovery: {
      readStatus: vi.fn().mockResolvedValue({ ok: true, state: "SUPPORT_REVIEW", recoveryId: "recovery-1" }),
      startMaintenance: vi.fn()
    } } });
    render(<DisasterRecoveryMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    expect(await screen.findByText("En revisión por soporte")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Consultar revisión de soporte" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revalidar copia y certificados" })).toBeInTheDocument();
    expect(screen.getByText(/evidencia central de cambios está incompleta/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activar servidor recuperado" })).not.toBeInTheDocument();
  });

  it("starts activation only from an approved local state", async () => {
    const startMaintenance = vi.fn().mockResolvedValue({ ok: true, started: true });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { disasterRecovery: {
      readStatus: vi.fn().mockResolvedValue({ ok: true, state: "ACTIVATION_APPROVED" }), startMaintenance
    } } });
    render(<DisasterRecoveryMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Activar servidor recuperado" }));
    expect(startMaintenance).toHaveBeenCalledWith({ action: "activate" });
  });

  it("does not offer a new request when the local status cannot be read", async () => {
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { disasterRecovery: {
      readStatus: vi.fn().mockRejectedValue(new Error("read failed")), startMaintenance: vi.fn()
    } } });
    render(<DisasterRecoveryMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo leer el estado local de recuperación.");
    expect(screen.queryByRole("button", { name: "Generar solicitud del destino" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Consultar estado instalado" }));
    expect(window.tpvDesktop?.disasterRecovery?.startMaintenance).toHaveBeenCalledWith({ action: "status" });
  });

  it("resumes an interrupted receive and checks service status after activation", async () => {
    const startMaintenance = vi.fn().mockResolvedValue({ ok: true, started: true });
    const readStatus = vi.fn().mockResolvedValueOnce({ ok: true, state: "RECEIVING" })
      .mockResolvedValue({ ok: true, state: "ACTIVATED_SERVICE_UNREADY" });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { disasterRecovery: {
      readStatus, startMaintenance
    } } });
    const view = render(<DisasterRecoveryMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Reanudar recepción de la copia" }));
    expect(startMaintenance).toHaveBeenCalledWith({ action: "receive" });
    view.unmount();
    render(<DisasterRecoveryMaintenanceScreen locale="es" t={createTranslator("es")} onExit={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Consultar revisión de soporte" }));
    expect(startMaintenance).toHaveBeenCalledWith({ action: "status" });
  });
});

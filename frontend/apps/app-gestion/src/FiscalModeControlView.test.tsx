// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FiscalModeControlView } from "./FiscalModeControlView";
import * as api from "./verifactuManagementApi";

vi.mock("./verifactuManagementApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("./verifactuManagementApi")>(),
  transitionFiscalMode: vi.fn()
}));

const labels: Record<string, string> = {
  "verifactu.management.fiscalMode.PRE_SIF": "Pre-SIF",
  "verifactu.management.fiscalMode.NO_VERIFACTU": "NO VERI*FACTU",
  "verifactu.management.fiscalMode.VERIFACTU": "VERI*FACTU",
  "verifactu.mode.target": "Nueva modalidad",
  "verifactu.mode.reason": "Motivo auditado",
  "verifactu.mode.confirmation": "Confirmación reforzada",
  "verifactu.mode.confirmationPhrase": "CAMBIAR MODALIDAD FISCAL",
  "verifactu.mode.confirmChange": "Confirmar transición fiscal",
  "verifactu.mode.endDate": "FechaFinVeriFactu comunicada",
  "verifactu.mode.ack": "Referencia del acuse AEAT",
  "verifactu.mode.verifactuOnly": "Esta versión solo permite VERI*FACTU.",
  "verifactu.mode.capabilityUnavailable": "No se conoce la capacidad de esta versión.",
  "verifactu.mode.retryHint": "La incidencia se conserva",
  "verifactu.mode.retryChange": "Reprogramar / reintentar transición",
  "verifactu.management.fiscalTransitionFailed": "Transición fallida:"
};
const t = (key: string) => labels[key] ?? key;

const baseStatus: api.FiscalStatus = {
  companyId: "company-1",
  mode: "PRE_SIF",
  modeVersion: 4,
  runtimeClass: "SANDBOX",
  endpointEnvironment: "TEST",
  transportMode: "SIMULATED",
  productionEnabled: false,
  productCapability: "DUAL"
};

describe("FiscalModeControlView", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it.each(["PRE_SIF", "NO_VERIFACTU", "VERIFACTU"] as const)("respeta VERIFACTU_ONLY desde %s", (mode) => {
    render(<FiscalModeControlView locale="es" status={{ ...baseStatus, mode, productCapability: "VERIFACTU_ONLY" }} t={t} onChanged={vi.fn()} />);

    expect(screen.getByText("Esta versión solo permite VERI*FACTU.")).toBeTruthy();
    expect(screen.queryByRole("option", { name: "NO VERI*FACTU" })).toBeNull();
    if (mode === "VERIFACTU") {
      expect(screen.queryByLabelText("Nueva modalidad")).toBeNull();
      expect(screen.queryByLabelText("Motivo auditado")).toBeNull();
      expect(screen.queryByRole("button", { name: "Confirmar transición fiscal" })).toBeNull();
    } else {
      expect(screen.getByRole("option", { name: "VERI*FACTU" })).toBeTruthy();
    }
    expect(api.transitionFiscalMode).not.toHaveBeenCalled();
  });

  it("no deduce capacidad DUAL de una respuesta legacy", () => {
    render(<FiscalModeControlView locale="es" status={{ ...baseStatus, mode: "VERIFACTU", productCapability: undefined }} t={t} onChanged={vi.fn()} />);

    expect(screen.getByText("No se conoce la capacidad de esta versión.")).toBeTruthy();
    expect(screen.queryByLabelText("Nueva modalidad")).toBeNull();
    expect(api.transitionFiscalMode).not.toHaveBeenCalled();
  });

  it("descarta el destino NO al actualizar la capacidad sin cambiar la versión de modalidad", () => {
    const { rerender } = render(<FiscalModeControlView locale="es" status={baseStatus} t={t} onChanged={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Nueva modalidad"), { target: { value: "NO_VERIFACTU" } });

    rerender(<FiscalModeControlView locale="es" status={{ ...baseStatus, productCapability: "VERIFACTU_ONLY" }} t={t} onChanged={vi.fn()} />);

    expect((screen.getByLabelText("Nueva modalidad") as HTMLSelectElement).value).toBe("VERIFACTU");
    expect(screen.queryByRole("option", { name: "NO VERI*FACTU" })).toBeNull();
    expect(api.transitionFiscalMode).not.toHaveBeenCalled();
  });

  it("envía la versión esperada y la confirmación reforzada", async () => {
    vi.mocked(api.transitionFiscalMode).mockResolvedValue({ ...baseStatus, mode: "VERIFACTU", modeVersion: 5 });
    const onChanged = vi.fn();
    render(<FiscalModeControlView locale="es" token="token" status={baseStatus} t={t} onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText(/Motivo auditado/), { target: { value: "Decisión fiscal aprobada" } });
    fireEvent.change(screen.getByLabelText(/Confirmación reforzada/), { target: { value: "CAMBIAR MODALIDAD FISCAL" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar transición fiscal" }));

    await waitFor(() => expect(api.transitionFiscalMode).toHaveBeenCalledWith(expect.objectContaining({
      targetMode: "VERIFACTU",
      expectedVersion: 4,
      confirmation: true,
      reason: "Decisión fiscal aprobada"
    }), "token"));
    expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ modeVersion: 5 }));
  });

  it("exige fecha y ACK al programar una salida REAL de VERI*FACTU", () => {
    render(<FiscalModeControlView locale="es" status={{
      ...baseStatus,
      mode: "VERIFACTU",
      runtimeClass: "REAL",
      endpointEnvironment: "PRODUCTION",
      transportMode: "AEAT",
      productionEnabled: true
    }} t={t} onChanged={vi.fn()} />);

    expect(screen.getByLabelText("FechaFinVeriFactu comunicada")).toBeTruthy();
    expect(screen.getByLabelText("Referencia del acuse AEAT")).toBeTruthy();
  });

  it("acepta la frase de confirmación traducida", async () => {
    vi.mocked(api.transitionFiscalMode).mockResolvedValue({ ...baseStatus, mode: "VERIFACTU", modeVersion: 5 });
    const translated = (key: string) => key === "verifactu.mode.confirmationPhrase"
      ? "CHANGE FISCAL MODE"
      : t(key);
    render(<FiscalModeControlView locale="en" token="token" status={baseStatus} t={translated} onChanged={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/Motivo auditado/), { target: { value: "Decisión fiscal aprobada" } });
    fireEvent.change(screen.getByLabelText(/Confirmación reforzada/), { target: { value: "CHANGE FISCAL MODE" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar transición fiscal" }));

    await waitFor(() => expect(api.transitionFiscalMode).toHaveBeenCalledWith(expect.objectContaining({ confirmation: true }), "token"));
  });

  it("conserva la incidencia FALLIDA y permite reprogramar el mismo destino", async () => {
    const failedStatus: api.FiscalStatus = {
      ...baseStatus,
      mode: "VERIFACTU",
      modeVersion: 8,
      scheduledTransition: {
        previousMode: "VERIFACTU",
        newMode: "NO_VERIFACTU",
        status: "FALLIDA",
        requestedAt: "2026-08-26T10:00:00Z",
        effectiveAt: "2026-08-27T10:00:00Z",
        lastErrorCode: "NETWORK_ERROR"
      }
    };
    vi.mocked(api.transitionFiscalMode).mockResolvedValue({ ...failedStatus, scheduledTransition: null });
    render(<FiscalModeControlView locale="es" token="token" status={failedStatus} t={t} onChanged={vi.fn()} />);
    expect(screen.getByText("Transición fallida:")).toBeTruthy();
    expect(screen.getByText(/NETWORK_ERROR/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/Motivo auditado/), { target: { value: "Reintento tras recuperar conexión" } });
    fireEvent.change(screen.getByLabelText(/Confirmación reforzada/), { target: { value: "CAMBIAR MODALIDAD FISCAL" } });
    fireEvent.click(screen.getByRole("button", { name: "Reprogramar / reintentar transición" }));
    await waitFor(() => expect(api.transitionFiscalMode).toHaveBeenCalledWith(expect.objectContaining({ targetMode: "NO_VERIFACTU", expectedVersion: 8 }), "token"));
  });
});

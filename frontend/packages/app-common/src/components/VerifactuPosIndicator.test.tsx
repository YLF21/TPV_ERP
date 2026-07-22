// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { VerifactuPosQueueItem, VerifactuPosStatus } from "../api/verifactuPos";
import { VerifactuPosIndicator } from "./VerifactuPosIndicator";

const apiMocks = vi.hoisted(() => ({
  getStatus: vi.fn(),
  getQueue: vi.fn()
}));

vi.mock("../api/verifactuPos", async (importOriginal) => {
  const original = await importOriginal<typeof import("../api/verifactuPos")>();
  return {
    ...original,
    getVerifactuPosStatus: apiMocks.getStatus,
    getVerifactuPosQueue: apiMocks.getQueue
  };
});

const status: VerifactuPosStatus = {
  active: true,
  presentationStatus: "PENDIENTES",
  pendingCount: 2,
  sendingCount: 0,
  reviewRequiredCount: 0
};

const queue: VerifactuPosQueueItem[] = [{
  documentNumber: "T-2026-0042",
  documentType: "F2",
  submissionStatus: "PENDIENTE",
  updatedAt: "2026-07-21T12:00:00Z",
  operationalMessageCode: null
}];

const labels: Record<string, string> = {
  "verifactu.pos.presentation.pending": "Pendientes",
  "verifactu.pos.presentation.operational": "Operativo",
  "verifactu.pos.presentation.unknown": "Desconocido",
  "verifactu.pos.loadError": "No se pudo consultar",
  "verifactu.pos.attentionCount": "Elementos que requieren atención",
  "verifactu.pos.terminalQueue": "Cola de esta terminal",
  "verifactu.pos.readOnlyDescription": "Consulta de solo lectura",
  "verifactu.pos.close": "Cerrar",
  "verifactu.pos.currentStatus": "Estado actual",
  "verifactu.pos.refresh": "Actualizar",
  "verifactu.pos.queueLoadError": "No se pudo cargar la cola",
  "verifactu.pos.loadingQueue": "Cargando cola",
  "verifactu.pos.emptyQueue": "Sin envíos pendientes",
  "verifactu.pos.document": "Documento",
  "verifactu.pos.updatedAt": "Actualizado",
  "verifactu.pos.status": "Estado",
  "verifactu.pos.queueStatus.pending": "Pendiente",
  "verifactu.pos.reviewInManagement": "Revisar en APP GESTIÓN"
};

const t = (key: string) => labels[key] ?? key;

describe("VerifactuPosIndicator", () => {
  beforeEach(() => {
    apiMocks.getStatus.mockResolvedValue(status);
    apiMocks.getQueue.mockResolvedValue(queue);
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "visible"
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("loads status on mount and the terminal queue when opened", async () => {
    render(<VerifactuPosIndicator token="token" locale="es" t={t} />);

    await waitFor(() => expect(apiMocks.getStatus).toHaveBeenCalledWith("token"));
    const trigger = screen.getByRole("button", { name: /VERI\*FACTUPendientes/ });
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(trigger);

    expect(await screen.findByRole("dialog", { name: "VERI*FACTU" })).toBeInTheDocument();
    await waitFor(() => expect(apiMocks.getQueue).toHaveBeenCalledWith("token"));
    expect(await screen.findByText("T-2026-0042")).toBeInTheDocument();
    expect(screen.getByText("Consulta de solo lectura")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /reintentar/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /subsanar/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /configurar/i })).not.toBeInTheDocument();
  });

  it("updates manually and reacts to a completed-sale refresh signal", async () => {
    const view = render(
      <VerifactuPosIndicator token="token" locale="es" t={t} refreshSignal={0} />
    );
    await waitFor(() => expect(apiMocks.getStatus).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: /VERI\*FACTU/ }));
    await waitFor(() => expect(apiMocks.getQueue).toHaveBeenCalledTimes(1));

    const refresh = screen.getByRole("button", { name: "Actualizar" });
    await waitFor(() => expect(refresh).toBeEnabled());
    fireEvent.click(refresh);
    await waitFor(() => expect(apiMocks.getQueue).toHaveBeenCalledTimes(2));

    view.rerender(
      <VerifactuPosIndicator token="token" locale="es" t={t} refreshSignal={1} />
    );
    await waitFor(() => expect(apiMocks.getQueue).toHaveBeenCalledTimes(3));
  });

  it("closes with Escape or an outside pointer and restores trigger focus", async () => {
    render(<VerifactuPosIndicator token="token" locale="es" t={t} />);
    const trigger = await screen.findByRole("button", { name: /VERI\*FACTU/ });

    fireEvent.click(trigger);
    const close = screen.getByRole("button", { name: "Cerrar" });
    expect(close).toHaveFocus();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();

    fireEvent.click(trigger);
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("polls every minute only while the document is visible", async () => {
    vi.useFakeTimers();
    render(
      <VerifactuPosIndicator
        token="token"
        locale="es"
        t={t}
        pollIntervalMs={60_000}
      />
    );
    await act(async () => Promise.resolve());
    expect(apiMocks.getStatus).toHaveBeenCalledTimes(1);

    let resolvePoll!: (value: VerifactuPosStatus) => void;
    apiMocks.getStatus.mockReturnValueOnce(new Promise<VerifactuPosStatus>((resolve) => {
      resolvePoll = resolve;
    }));
    await act(async () => {
      vi.advanceTimersByTime(60_000);
      await Promise.resolve();
    });
    expect(apiMocks.getStatus).toHaveBeenCalledTimes(2);
    await act(async () => {
      vi.advanceTimersByTime(60_000);
      await Promise.resolve();
    });
    expect(apiMocks.getStatus).toHaveBeenCalledTimes(2);
    resolvePoll(status);
    await act(async () => Promise.resolve());

    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "hidden"
    });
    fireEvent(document, new Event("visibilitychange"));
    await act(async () => {
      vi.advanceTimersByTime(120_000);
      await Promise.resolve();
    });
    expect(apiMocks.getStatus).toHaveBeenCalledTimes(2);

    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "visible"
    });
    fireEvent(document, new Event("visibilitychange"));
    await act(async () => Promise.resolve());
    expect(apiMocks.getStatus).toHaveBeenCalledTimes(3);
  });

  it("shows sanitized loading errors without exposing backend details", async () => {
    apiMocks.getStatus.mockRejectedValueOnce(new Error("SOAP responsePayload XML certificate"));
    apiMocks.getQueue.mockRejectedValueOnce(new Error("private backend detail"));
    render(<VerifactuPosIndicator token="token" locale="es" t={t} />);

    expect(await screen.findByText("No se pudo consultar")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /VERI\*FACTU/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo cargar la cola");
    expect(document.body).not.toHaveTextContent("responsePayload");
    expect(document.body).not.toHaveTextContent("private backend detail");
  });

  it("clears session data on token change and ignores the obsolete response", async () => {
    let resolveOldStatus!: (value: VerifactuPosStatus) => void;
    const oldStatus = new Promise<VerifactuPosStatus>((resolve) => {
      resolveOldStatus = resolve;
    });
    let oldTokenCalls = 0;
    apiMocks.getStatus.mockImplementation((token: string) => token === "old-token"
      ? (++oldTokenCalls === 1 ? oldStatus : Promise.resolve(status))
      : Promise.resolve({
          ...status,
          presentationStatus: "REQUIERE_REVISION",
          pendingCount: 0,
          reviewRequiredCount: 1
        }));
    let resolveNewQueue!: (value: VerifactuPosQueueItem[]) => void;
    apiMocks.getQueue.mockImplementation((token: string) => token === "old-token"
      ? Promise.resolve(queue)
      : new Promise<VerifactuPosQueueItem[]>((resolve) => {
          resolveNewQueue = resolve;
        }));

    const view = render(
      <VerifactuPosIndicator token="old-token" locale="es" t={t} />
    );
    expect(apiMocks.getStatus).toHaveBeenCalledWith("old-token");
    fireEvent.click(screen.getByRole("button", { name: /VERI\*FACTU/ }));
    expect(await screen.findByText("T-2026-0042")).toBeInTheDocument();

    view.rerender(
      <VerifactuPosIndicator token="new-token" locale="es" t={t} />
    );
    await waitFor(() => expect(apiMocks.getStatus).toHaveBeenCalledWith("new-token"));
    expect(screen.queryByText("T-2026-0042")).not.toBeInTheDocument();
    resolveNewQueue([]);
    await act(async () => Promise.resolve());
    expect(screen.getByRole("button", { name: /VERI\*FACTUverifactu.pos.presentation.reviewRequired/ }))
      .toBeInTheDocument();

    resolveOldStatus({ ...status, presentationStatus: "OPERATIVO", pendingCount: 0 });
    await act(async () => Promise.resolve());
    expect(screen.getByRole("button", { name: /VERI\*FACTUverifactu.pos.presentation.reviewRequired/ }))
      .toBeInTheDocument();
    expect(screen.queryByText("Operativo")).not.toBeInTheDocument();
  });
});

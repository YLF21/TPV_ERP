// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { VerifactuResolutionPanel } from "./VerifactuResolutionPanel";
import * as api from "./verifactuManagementApi";
import { ApiError } from "@tpverp/app-common";
import { webcrypto } from "node:crypto";

vi.mock("./verifactuManagementApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./verifactuManagementApi")>();
  return {
    ...original,
    loadVerifactuResolution: vi.fn(),
    retryVerifactuSubmission: vi.fn(),
    createVerifactuCorrection: vi.fn()
  };
});

const target = { recordId: "record-1", documentNumber: "T-2026-0042" };
const t = (key: string) => key.startsWith("verifactu.resolution.action.")
  || key.startsWith("verifactu.resolution.explanation.")
  ? `translated:${key}`
  : key;

function resolution(
  action: api.VerifactuResolutionAction,
  permittedActions: api.VerifactuResolutionAction[] = []
): api.VerifactuResolution {
  return {
    recordId: "record-1",
    operation: "ALTA",
    status: action === "CREATE_RECTIFYING_INVOICE" ? "ACEPTADO" : "DEFECTUOSO",
    version: 4,
    errorCode: action === "RETRY" ? "INVALID_AEAT_RESPONSE" : "AEAT-1100",
    category: action === "RETRY" ? "LOCAL_TECHNICAL_ERROR" : "AEAT_REJECTED",
    recommendedAction: action,
    permittedActions
  };
}

beforeEach(() => {
  vi.stubGlobal("crypto", webcrypto);
  sessionStorage.clear();
  vi.mocked(api.retryVerifactuSubmission).mockResolvedValue({
    recordId: "record-1", status: "ACEPTADO", errorCode: null
  });
  vi.mocked(api.createVerifactuCorrection).mockResolvedValue({
    id: "correction-1",
    originalRecordId: "record-1",
    number: "T-2026-0042",
    generatedAt: "2026-07-21T12:00:00Z",
    status: "PENDIENTE"
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

function renderPanel(onCompleted = vi.fn()) {
  render(
    <VerifactuResolutionPanel
      target={target}
      token="fiscal-token"
      recoveryScope="user-1"
      locale="es"
      t={t}
      onClose={vi.fn()}
      onCompleted={onCompleted}
    />
  );
  return onCompleted;
}

describe("VerifactuResolutionPanel", () => {
  async function openCorrection() {
    vi.mocked(api.loadVerifactuResolution).mockResolvedValue(resolution("CREATE_CORRECTION", ["CREATE_CORRECTION"]));
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: "verifactu.resolution.prepareCorrection" }));
    fillCorrection();
    await waitFor(() => expect(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" })).toBeEnabled());
  }

  function fillCorrection() {
    fireEvent.change(screen.getByLabelText("verifactu.resolution.reason"), { target: { value: "Motivo reservado" } });
    fireEvent.change(screen.getByLabelText("verifactu.resolution.operationDescription"), { target: { value: "Descripción original" } });
  }

  it("libera una reserva nueva si el panel se cierra antes de enviar el POST", async () => {
    await openCorrection();
    const digest = crypto.subtle.digest.bind(crypto.subtle);
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const delayedDigest = vi.spyOn(crypto.subtle, "digest").mockImplementation(async (...args) => {
      const result = await digest(...args);
      await pending;
      return result;
    });
    const saveRecovery = vi.spyOn(Storage.prototype, "setItem");
    try {
      fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
      cleanup();
      release();
      // Observe the reservation before asserting its cleanup, rather than
      // accepting the initially empty storage while digests are still pending.
      await waitFor(() => expect(saveRecovery).toHaveBeenCalledTimes(1));
      expect(sessionStorage.length).toBe(0);
      expect(api.createVerifactuCorrection).not.toHaveBeenCalled();
    } finally {
      delayedDigest.mockRestore();
      saveRecovery.mockRestore();
    }
  });

  it("conserva la clave tras un fallo de transporte y rechaza cambiar el intento incierto", async () => {
    vi.mocked(api.createVerifactuCorrection).mockRejectedValueOnce(new Error("transport interrupted"));
    await openCorrection();
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.genericError");
    const first = vi.mocked(api.createVerifactuCorrection).mock.calls[0][1];
    expect(first.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    const saved = sessionStorage.getItem(sessionStorage.key(0)!);
    expect(saved).toContain(first.idempotencyKey);
    expect(saved).not.toContain("Motivo reservado");
    expect(saved).not.toContain("Descripción original");
    expect(saved).not.toContain("fiscal-token");

    fireEvent.change(screen.getByLabelText("verifactu.resolution.operationDescription"), { target: { value: "Otros datos" } });
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.recoveryError");
    expect(api.createVerifactuCorrection).toHaveBeenCalledTimes(1);
    fillCorrection();
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.correctionSuccess");
    expect(vi.mocked(api.createVerifactuCorrection).mock.calls[1][1].idempotencyKey).toBe(first.idempotencyKey);
    expect(sessionStorage.length).toBe(0);
  });

  it("recupera la clave tras reabrir el panel sin guardar datos personales", async () => {
    vi.mocked(api.createVerifactuCorrection).mockRejectedValueOnce(new Error("timeout"));
    await openCorrection();
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.genericError");
    const key = vi.mocked(api.createVerifactuCorrection).mock.calls[0][1].idempotencyKey;
    cleanup();
    renderPanel();
    await screen.findByText("verifactu.resolution.recoveryPending");
    fillCorrection();
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.correctionSuccess");
    expect(vi.mocked(api.createVerifactuCorrection).mock.calls[1][1].idempotencyKey).toBe(key);
  });

  it("genera otra clave al corregir un rechazo de validación definitivo", async () => {
    vi.mocked(api.createVerifactuCorrection).mockRejectedValueOnce(new ApiError("invalid", 400));
    await openCorrection();
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.genericError");
    const key = vi.mocked(api.createVerifactuCorrection).mock.calls[0][1].idempotencyKey;
    expect(sessionStorage.length).toBe(0);
    fireEvent.change(screen.getByLabelText("verifactu.resolution.operationDescription"), { target: { value: "Datos corregidos" } });
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText("verifactu.resolution.correctionSuccess");
    expect(vi.mocked(api.createVerifactuCorrection).mock.calls[1][1].idempotencyKey).not.toBe(key);
  });

  it.each([
    ["subsanacion_idempotency_conflict", "idempotency"],
    ["subsanacion_pending_conflict", "pendingCorrection"]
  ])("traduce %s y conserva la recuperación", async (code, message) => {
    vi.mocked(api.createVerifactuCorrection).mockRejectedValueOnce(new ApiError("protected detail", 409, { code }));
    await openCorrection();
    fireEvent.click(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }));
    await screen.findByText(`verifactu.resolution.${message}Error`);
    expect(sessionStorage.length).toBe(1);
    expect(screen.queryByText("protected detail")).not.toBeInTheDocument();
  });

  it("renders a backend decision as read only when the user lacks the permitted action", async () => {
    vi.mocked(api.loadVerifactuResolution).mockResolvedValue(
      resolution("CREATE_CORRECTION")
    );

    renderPanel();

    expect(await screen.findByText("translated:verifactu.resolution.action.CREATE_CORRECTION"))
      .toBeInTheDocument();
    expect(screen.getByText("verifactu.resolution.permissionRequired")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "verifactu.resolution.prepareCorrection" }))
      .not.toBeInTheDocument();
  });

  it("requires an audited reason and executes a versioned retry", async () => {
    vi.mocked(api.loadVerifactuResolution).mockResolvedValue(
      resolution("RETRY", ["RETRY"])
    );
    const completed = renderPanel();

    fireEvent.click(await screen.findByRole("button", {
      name: "verifactu.resolution.prepareRetry"
    }));
    const confirm = screen.getByRole("button", { name: "verifactu.resolution.confirmRetry" });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText("verifactu.resolution.reason"), {
      target: { value: " Respuesta AEAT no interpretable " }
    });
    fireEvent.click(confirm);

    await waitFor(() => expect(api.retryVerifactuSubmission).toHaveBeenCalledWith(
      "record-1", 4, " Respuesta AEAT no interpretable ", "fiscal-token"
    ));
    expect(await screen.findByText("verifactu.resolution.retrySuccess")).toBeInTheDocument();
    expect(completed).toHaveBeenCalledTimes(1);
  });

  it("validates paired recipient fields before creating an administrative correction", async () => {
    vi.mocked(api.loadVerifactuResolution).mockResolvedValue(
      resolution("CREATE_CORRECTION", ["CREATE_CORRECTION"])
    );
    renderPanel();

    fireEvent.click(await screen.findByRole("button", {
      name: "verifactu.resolution.prepareCorrection"
    }));
    fireEvent.change(screen.getByLabelText("verifactu.resolution.reason"), {
      target: { value: "Corregir destinatario" }
    });
    fireEvent.change(screen.getByLabelText("verifactu.resolution.recipientTaxId"), {
      target: { value: "B12345674" }
    });
    expect(screen.getByRole("button", { name: "verifactu.resolution.confirmCorrection" }))
      .toBeDisabled();
    fireEvent.change(screen.getByLabelText("verifactu.resolution.recipientName"), {
      target: { value: "Cliente SL" }
    });
    fireEvent.click(screen.getByRole("button", {
      name: "verifactu.resolution.confirmCorrection"
    }));

    await waitFor(() => expect(api.createVerifactuCorrection).toHaveBeenCalledWith(
      "record-1",
      expect.objectContaining({
        reason: "Corregir destinatario",
        recipientTaxId: "B12345674",
        recipientName: "Cliente SL"
      }),
      "fiscal-token"
    ));
    expect(await screen.findByText("verifactu.resolution.correctionSuccess"))
      .toBeInTheDocument();
  });

  it("routes accepted records to the commercial rectifying-invoice flow without a fake button", async () => {
    vi.mocked(api.loadVerifactuResolution).mockResolvedValue(
      resolution("CREATE_RECTIFYING_INVOICE", ["CREATE_RECTIFYING_INVOICE"])
    );

    renderPanel();

    expect(await screen.findByText("translated:verifactu.resolution.action.CREATE_RECTIFYING_INVOICE"))
      .toBeInTheDocument();
    expect(screen.getByText("translated:verifactu.resolution.explanation.CREATE_RECTIFYING_INVOICE"))
      .toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /rectif/i })).not.toBeInTheDocument();
    expect(api.retryVerifactuSubmission).not.toHaveBeenCalled();
    expect(api.createVerifactuCorrection).not.toHaveBeenCalled();
  });
});

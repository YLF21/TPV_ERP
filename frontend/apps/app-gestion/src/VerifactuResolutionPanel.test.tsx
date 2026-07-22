// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { VerifactuResolutionPanel } from "./VerifactuResolutionPanel";
import * as api from "./verifactuManagementApi";

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
});

function renderPanel(onCompleted = vi.fn()) {
  render(
    <VerifactuResolutionPanel
      target={target}
      token="fiscal-token"
      locale="es"
      t={t}
      onClose={vi.fn()}
      onCompleted={onCompleted}
    />
  );
  return onCompleted;
}

describe("VerifactuResolutionPanel", () => {
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

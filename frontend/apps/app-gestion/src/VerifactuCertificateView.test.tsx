// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { VerifactuCertificateView } from "./VerifactuCertificateView";
import * as api from "./verifactuManagementApi";

vi.mock("./verifactuManagementApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./verifactuManagementApi")>();
  return {
    ...original,
    loadVerifactuCertificates: vi.fn(),
    importVerifactuCertificate: vi.fn(),
    deleteVerifactuCertificate: vi.fn()
  };
});

const certificate: api.VerifactuManagedCertificate = {
  id: "certificate-1",
  status: "ACTIVO",
  subject: "CN=Empresa de prueba",
  issuer: "CN=Autoridad certificadora",
  serialNumber: "0123456789",
  taxId: "B12345674",
  fingerprint: "A".repeat(64),
  validFrom: "2026-01-01T00:00:00Z",
  validUntil: "2027-01-01T00:00:00Z",
  validityStatus: "VALIDO",
  daysRemaining: 120,
  canDelete: false,
  deleteBlockReason: "FIRST_SUBMISSION_RECORDED"
};

const t = (key: string) => key;

beforeEach(() => {
  vi.mocked(api.loadVerifactuCertificates).mockResolvedValue([certificate]);
  vi.mocked(api.importVerifactuCertificate).mockResolvedValue(certificate);
  vi.mocked(api.deleteVerifactuCertificate).mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("VerifactuCertificateView", () => {
  it("renders only public certificate metadata and obeys backend deletion policy", async () => {
    render(<VerifactuCertificateView locale="es" token="admin-token" revision={0} t={t} onChanged={vi.fn()} />);

    expect(await screen.findByText("CN=Empresa de prueba")).toBeInTheDocument();
    expect(screen.getByText("B12345674")).toBeInTheDocument();
    expect(screen.getByText("A".repeat(64))).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "verifactu.certificate.delete" })).toBeDisabled();
    expect(screen.getByText("verifactu.certificate.deleteBlocked")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("password");
    expect(document.body.textContent).not.toContain("privateKey");
    expect(document.body.textContent).not.toContain("secretPath");
  });

  it("clears the selected file and password after a failed import", async () => {
    vi.mocked(api.loadVerifactuCertificates).mockResolvedValue([]);
    vi.mocked(api.importVerifactuCertificate).mockRejectedValueOnce(new Error("secret backend detail"));
    render(<VerifactuCertificateView locale="es" token="admin-token" revision={0} t={t} onChanged={vi.fn()} />);
    await screen.findByText("verifactu.certificate.emptyTitle");

    fireEvent.click(screen.getByRole("button", { name: "verifactu.certificate.import" }));
    const fileInput = screen.getByLabelText("verifactu.certificate.file") as HTMLInputElement;
    const passwordInput = screen.getByLabelText("verifactu.certificate.password") as HTMLInputElement;
    const file = new File(["pkcs12"], "empresa.p12", { type: "application/x-pkcs12" });
    fireEvent.change(fileInput, { target: { files: [file] } });
    fireEvent.change(passwordInput, { target: { value: "top-secret" } });
    const submit = screen.getByRole("button", { name: "verifactu.certificate.confirmImport" });
    fireEvent.submit(submit.closest("form")!);

    expect(await screen.findByText("verifactu.certificate.importError")).toBeInTheDocument();
    expect(api.importVerifactuCertificate).toHaveBeenCalledWith(file, "top-secret", null, "admin-token");
    expect(fileInput.value).toBe("");
    expect(passwordInput.value).toBe("");
    expect(document.body.textContent).not.toContain("secret backend detail");
    expect(document.body.textContent).not.toContain("top-secret");
  });

  it("requires the exact replacement confirmation and refreshes after success", async () => {
    const onChanged = vi.fn();
    render(<VerifactuCertificateView locale="es" token="admin-token" revision={0} t={t} onChanged={onChanged} />);
    await screen.findByText("CN=Empresa de prueba");

    fireEvent.click(screen.getByRole("button", { name: "verifactu.certificate.replace" }));
    const file = new File(["replacement"], "renovado.pfx", { type: "application/x-pkcs12" });
    fireEvent.change(screen.getByLabelText("verifactu.certificate.file"), { target: { files: [file] } });
    fireEvent.change(screen.getByLabelText("verifactu.certificate.password"), { target: { value: "temporary" } });
    const confirmation = screen.getByLabelText("verifactu.certificate.replaceConfirmation");
    const submit = screen.getByRole("button", { name: "verifactu.certificate.confirmReplace" });
    expect(submit).toBeDisabled();
    fireEvent.change(confirmation, { target: { value: "SUSTITUIR CERTIFICADO" } });
    expect(submit).toBeEnabled();
    fireEvent.submit(submit.closest("form")!);

    await waitFor(() => expect(api.importVerifactuCertificate).toHaveBeenCalledWith(
      file,
      "temporary",
      { expectedActiveCertificateId: "certificate-1", confirmation: "SUSTITUIR CERTIFICADO" },
      "admin-token"
    ));
    expect(onChanged).toHaveBeenCalledOnce();
    expect(await screen.findByText("verifactu.certificate.replaced")).toBeInTheDocument();
  });

  it("sends the exact delete confirmation only when backend allows deletion", async () => {
    vi.mocked(api.loadVerifactuCertificates).mockResolvedValue([{ ...certificate, canDelete: true, deleteBlockReason: null }]);
    const onChanged = vi.fn();
    render(<VerifactuCertificateView locale="es" token="admin-token" revision={0} t={t} onChanged={onChanged} />);
    await screen.findByText("CN=Empresa de prueba");

    fireEvent.click(screen.getByRole("button", { name: "verifactu.certificate.delete" }));
    const confirmation = screen.getByLabelText("verifactu.certificate.deleteConfirmation");
    const submit = screen.getByRole("button", { name: "verifactu.certificate.confirmDelete" });
    expect(submit).toBeDisabled();
    fireEvent.change(confirmation, { target: { value: "ELIMINAR CERTIFICADO" } });
    fireEvent.click(submit);

    await waitFor(() => expect(api.deleteVerifactuCertificate).toHaveBeenCalledWith(
      "ELIMINAR CERTIFICADO", "admin-token"
    ));
    expect(onChanged).toHaveBeenCalledOnce();
  });
});

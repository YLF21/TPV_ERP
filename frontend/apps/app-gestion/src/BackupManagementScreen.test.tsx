/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { BackupManagementScreen } from "./BackupManagementScreen";
import * as api from "./backupApi";

vi.mock("./backupApi", () => ({
  loadBackupHistory: vi.fn(), loadBackupConfiguration: vi.fn(), loadBackupDetail: vi.fn(),
  runBackup: vi.fn(), saveBackupConfiguration: vi.fn(), retryBackupCloudUpload: vi.fn()
}));

const session: UserSession = { username: "ADMIN", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] };
const t = (key: string) => key;

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); this.dispatchEvent(new Event("close")); };
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });

describe("BackupManagementScreen", () => {
  it("labels manifest categories, document types and phases in all supported languages", () => {
    for (const locale of ["es", "en", "zh"] as const) {
      const translate = createTranslator(locale);
      for (const key of ["gestion.backups.category.products", "gestion.backups.documentType.TICKET",
        "gestion.backups.phase.QUEUED", "gestion.backups.cloud.PENDING", "gestion.backups.cloud.SENT",
        "gestion.backups.cloud.FAILED", "gestion.backups.portableSecretsIncomplete",
        "gestion.backups.legacyRecoveryLimit"]) expect(translate(key)).not.toBe(key);
    }
  });

  it("does not request backup data without permission", () => {
    render(<BackupManagementScreen session={{ ...session, permissions: [] }} locale="es" t={t} />);
    expect(screen.getByRole("alert")).toHaveTextContent("gestion.backups.noAccess");
    expect(api.loadBackupHistory).not.toHaveBeenCalled();
  });

  it("shows paged history and loads details by double click", async () => {
    vi.mocked(api.loadBackupHistory).mockResolvedValue({
      items: [{ id: "backup-1", sequenceNumber: 12, startedAt: "2026-10-22T08:00:00Z", result: "EXITO", available: true, origin: "MANUAL", phase: "COMPLETED" }],
      page: 0, size: 50, total: 1, overdue: false, configured: true
    });
    vi.mocked(api.loadBackupConfiguration).mockResolvedValue({ time: "03:00", directory: "C:\\backup", active: true, dailyRetention: 30, monthlyRetention: 72, configured: true });
    vi.mocked(api.loadBackupDetail).mockResolvedValue({
      id: "backup-1", sequenceNumber: 12, startedAt: "2026-10-22T08:00:00Z", result: "EXITO", available: true, origin: "MANUAL",
      phase: "COMPLETED", metadata: { manifest: { applicationVersion: "4.2.0", categories: { products: 34 } } }
    });
    render(<BackupManagementScreen session={session} locale="es" t={createTranslator("es")} />);
    expect(screen.getAllByRole("columnheader").map(header => header.textContent)).toEqual(["ID", "Fecha", "Hora", "Tamaño", "Estado", ""]);
    const row = await screen.findByRole("row", { name: /12/ });
    expect(row).not.toHaveTextContent("COMPLETED");
    fireEvent.doubleClick(row);
    await waitFor(() => expect(api.loadBackupDetail).toHaveBeenCalledWith("backup-1", "token"));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(await screen.findByText("4.2.0")).toBeInTheDocument();
    expect(screen.getByText("34")).toBeInTheDocument();
    expect(screen.getByText("Productos")).toBeInTheDocument();
    expect(screen.getByText("Finalizada")).toBeInTheDocument();
  });

  it("polls a running backup without replacing the history with a loading row", async () => {
    let tick: (() => void) | undefined;
    vi.spyOn(window, "setInterval").mockImplementation((callback) => {
      tick = callback as () => void;
      return 1 as unknown as ReturnType<typeof setInterval>;
    });
    const running = { id: "backup-1", sequenceNumber: 12, startedAt: "2026-10-22T08:00:00Z",
      result: "EN_CURSO" as const, available: false, origin: "MANUAL" as const };
    vi.mocked(api.loadBackupHistory).mockResolvedValueOnce({
      items: [running], page: 0, size: 50, total: 1, overdue: false, configured: true
    }).mockResolvedValue({
      items: [{ ...running, result: "EXITO", available: true }], page: 0, size: 50, total: 1, overdue: false, configured: true
    });
    vi.mocked(api.loadBackupConfiguration).mockResolvedValue({ time: "03:00", directory: "C:\\backup",
      active: true, dailyRetention: 30, monthlyRetention: 72, configured: true });
    render(<BackupManagementScreen session={session} locale="es" t={createTranslator("es")} />);
    const row = await screen.findByRole("row", { name: /12/ });
    expect(row).toHaveTextContent("En curso");
    expect(tick).toBeTypeOf("function");
    await act(async () => { tick?.(); });
    await waitFor(() => expect(row).toHaveTextContent("Correcta"));
    expect(screen.queryByText("Cargando")).not.toBeInTheDocument();
  });

  it("shows SaaS delivery and retries a failed upload from the detail", async () => {
    const failed = { id: "backup-1", sequenceNumber: 12, startedAt: "2026-10-22T08:00:00Z",
      result: "EXITO" as const, available: true, origin: "MANUAL" as const,
      cloud: { state: "FAILED" as const, attempts: 2, errorReason: "retryable" } };
    vi.mocked(api.loadBackupHistory).mockResolvedValue({
      items: [failed], page: 0, size: 50, total: 1, overdue: false, configured: true
    });
    vi.mocked(api.loadBackupConfiguration).mockResolvedValue({ time: "03:00", directory: "C:\\backup",
      active: true, dailyRetention: 30, monthlyRetention: 72, configured: true });
    vi.mocked(api.loadBackupDetail).mockResolvedValue(failed);
    vi.mocked(api.retryBackupCloudUpload).mockResolvedValue(undefined);
    render(<BackupManagementScreen session={session} locale="es" t={createTranslator("es")} />);
    const row = await screen.findByRole("row", { name: /12/ });
    expect(row).toHaveTextContent("Error de envío");
    fireEvent.doubleClick(row);
    fireEvent.click(await screen.findByRole("button", { name: "Reintentar envío al servidor central" }));
    await waitFor(() => expect(api.retryBackupCloudUpload).toHaveBeenCalledWith("backup-1", "token"));
  });

  it("distinguishes missing portable keys from older backups in detail only", async () => {
    const item = { id: "backup-1", sequenceNumber: 12, startedAt: "2026-10-22T08:00:00Z",
      result: "EXITO" as const, available: true, origin: "MANUAL" as const };
    vi.mocked(api.loadBackupHistory).mockResolvedValue({
      items: [item], page: 0, size: 50, total: 1, overdue: false, configured: true
    });
    vi.mocked(api.loadBackupConfiguration).mockResolvedValue({ time: "03:00", directory: "C:\\backup",
      active: true, dailyRetention: 30, monthlyRetention: 72, configured: true });
    const cases: Array<{ manifest: import("./backupApi").BackupManifest; expected: string }> = [
      { manifest: { portableSecretsVersion: 1, portableSecretsComplete: false,
        portableSecretsWarnings: ["MISSING_FISCAL_KEY"] }, expected: "La copia conserva los datos" },
      { manifest: { applicationVersion: "4.1.0" }, expected: "Esta copia antigua no incluye las claves portátiles" }
    ];
    for (const { manifest, expected } of cases) {
      vi.mocked(api.loadBackupDetail).mockResolvedValueOnce({ ...item, metadata: { manifest } });
      render(<BackupManagementScreen session={session} locale="es" t={createTranslator("es")} />);
      const row = await screen.findByRole("row", { name: /12/ });
      expect(row).not.toHaveTextContent(expected);
      fireEvent.doubleClick(row);
      const dialog = await screen.findByRole("dialog");
      expect(dialog).toHaveTextContent(expected);
      expect(dialog).not.toHaveTextContent("MISSING_FISCAL_KEY");
      cleanup();
    }
  });
});

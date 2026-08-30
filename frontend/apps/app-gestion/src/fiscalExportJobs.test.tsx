// @vitest-environment jsdom
import { act, render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "./verifactuManagementApi";
import { FiscalExportJobsList, useFiscalExportJobs } from "./fiscalExportJobs";

vi.mock("./verifactuManagementApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("./verifactuManagementApi")>(),
  createFiscalExportJob: vi.fn(),
  loadFiscalExportJobs: vi.fn(),
  retryFiscalExportJob: vi.fn(),
  downloadFiscalExportJob: vi.fn()
}));
vi.mock("./fiscalExportDownload", () => ({ downloadFiscalExportBlob: vi.fn(), submitFiscalExportDownload: vi.fn() }));

const messages: Record<string, string> = {
  "verifactu.exportJobs.recentTitle": "Exportaciones recientes", "verifactu.exportJobs.refresh": "Actualizar trabajos", "verifactu.exportJobs.loading": "Cargando", "verifactu.exportJobs.error": "Error", "verifactu.exportJobs.retry": "Reintentar carga", "verifactu.exportJobs.empty": "Sin trabajos", "verifactu.exportJobs.processed": "procesados", "verifactu.exportJobs.failedHint": "Trabajo fallido", "verifactu.exportJobs.expiredHint": "Trabajo caducado", "verifactu.exportJobs.downloading": "Descargando", "verifactu.exportJobs.download": "Descargar ZIP", "verifactu.exportJobs.downloadError": "Error descarga", "verifactu.exportJobs.retryRequest": "Solicitar de nuevo", "verifactu.exportJobs.created": "Creado", "verifactu.exportJobs.current": "Actual", "verifactu.exportJobs.kindBilling": "Facturación", "verifactu.exportJobs.kindEvents": "Eventos", "verifactu.exportJobs.kindUnknown": "Fiscal", "verifactu.exportJobs.statusQueued": "En cola", "verifactu.exportJobs.statusRunning": "En proceso", "verifactu.exportJobs.statusCompleted": "Completado", "verifactu.exportJobs.statusFailed": "Fallido", "verifactu.exportJobs.statusExpired": "Caducado", "verifactu.exportJobs.page": "Páginas de trabajos", "verifactu.exportJobs.previous": "Anterior", "verifactu.exportJobs.next": "Siguiente"
};
const t = (key: string) => messages[key] ?? key;
const queued = { id: "job-1", status: "QUEUED" as const, processed: 3, hasMore: true, error: null, fileSize: 0, downloadAvailable: false, createdAt: "2026-08-26T10:00:00Z" };

function Harness() {
  const state = useFiscalExportJobs("token", true);
  return <><span data-testid="status">{state.jobs[0]?.status ?? "none"}</span><span data-testid="creating">{String(state.creating)}</span><span data-testid="download-id">{state.downloadId ?? ""}</span><button data-testid="start-download" onClick={() => state.jobs[0] && void state.download(state.jobs[0])}>download</button><button data-testid="start-create" onClick={() => void state.create({ kind: "BILLING", scope: "CURRENT", periodStart: null, periodEnd: null, recordIds: [] })}>create</button><FiscalExportJobsList jobs={state.jobs} loading={state.loading} error={state.error} downloadId={state.downloadId} downloadError={state.downloadError} onRefresh={() => void state.refresh()} onRetry={(id) => void state.retry(id)} onDownload={(job) => void state.download(job)} pageIndex={state.pageIndex} totalPages={state.totalPages} totalElements={state.totalElements} onPageChange={(page) => void state.goToPage(page)} t={t} /></>;
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe("fiscal export jobs", () => {
  it("recupera un job activo y hace polling hasta completarlo", async () => {
    vi.mocked(api.loadFiscalExportJobs)
      .mockResolvedValueOnce({ content: [queued], totalElements: 1, totalPages: 1, number: 0, size: 20 })
      .mockResolvedValue({ content: [{ ...queued, status: "COMPLETED", hasMore: false, fileSize: 10, downloadAvailable: true }], totalElements: 1, totalPages: 1, number: 0, size: 20 });
    vi.useFakeTimers();
    render(<Harness />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId("status").textContent).toBe("QUEUED");
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId("status").textContent).toBe("COMPLETED");
    expect(api.loadFiscalExportJobs).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "Descargar ZIP" })).toBeTruthy();
  });

  it("aborta el polling al desmontar y no deja la promesa activa", async () => {
    let signal: AbortSignal | undefined;
    vi.mocked(api.loadFiscalExportJobs).mockImplementation((_token, _page, _size, requestSignal) => {
      signal = requestSignal;
      return Promise.resolve({ content: [queued], totalElements: 1, totalPages: 1, number: 0, size: 20 });
    });
    vi.useFakeTimers();
    const view = render(<Harness />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(screen.getByTestId("status").textContent).toBe("QUEUED");
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); await Promise.resolve(); await Promise.resolve(); });
    expect(signal).toBeTruthy();
    view.unmount();
    expect(signal?.aborted).toBe(true);
  });

  it("muestra estados traducidos y solo ofrece reintento explícito", async () => {
    const failed = { ...queued, status: "FAILED" as const, hasMore: false, error: "backend_code" };
    vi.mocked(api.loadFiscalExportJobs).mockResolvedValue({ content: [failed], totalElements: 1, totalPages: 1, number: 0, size: 20 });
    vi.mocked(api.createFiscalExportJob).mockResolvedValue({ ...queued, id: "job-2" });
    vi.mocked(api.retryFiscalExportJob).mockResolvedValue({ ...queued, id: "job-2" });
    render(<Harness />);
    expect(await screen.findByText("Fallido")).toBeTruthy();
    expect(screen.queryByText("backend_code")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Solicitar de nuevo" }));
    await waitFor(() => expect(api.retryFiscalExportJob).toHaveBeenCalledWith("job-1", "token", expect.anything()));
  });

  it("no ofrece descarga para un completado sin archivo disponible", async () => {
    vi.mocked(api.loadFiscalExportJobs).mockResolvedValue({ content: [{ ...queued, status: "COMPLETED", hasMore: false, fileSize: 0, downloadAvailable: false }], totalElements: 1, totalPages: 1, number: 0, size: 20 });
    render(<Harness />);
    expect(await screen.findByText("Completado")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Descargar ZIP" })).toBeNull();
  });

  it("mantiene independientes la creación y la descarga y limpia ambos estados", async () => {
    const createRequest = { kind: "BILLING" as const, scope: "CURRENT" as const, periodStart: null, periodEnd: null, recordIds: [] };
    let resolveCreate!: (value: typeof queued) => void;
    let resolveDownload!: (value: string) => void;
    let createSignal: AbortSignal | undefined;
    let downloadSignal: AbortSignal | undefined;
    vi.mocked(api.loadFiscalExportJobs).mockResolvedValue({ content: [{ ...queued, status: "COMPLETED", downloadAvailable: true }], totalElements: 1, totalPages: 1, number: 0, size: 20 });
    vi.mocked(api.createFiscalExportJob).mockImplementation((_request, _token, signal) => {
      createSignal = signal;
      return new Promise((resolve) => { resolveCreate = resolve; });
    });
    vi.mocked(api.downloadFiscalExportJob).mockImplementation((_id, _token, signal) => {
      downloadSignal = signal;
      return new Promise((resolve) => { resolveDownload = resolve; });
    });
    render(<Harness />);
    await screen.findByText("Completado");
    fireEvent.click(screen.getByTestId("start-download"));
    fireEvent.click(screen.getByTestId("start-create"));
    await waitFor(() => expect(screen.getByTestId("creating").textContent).toBe("true"));
    expect(createSignal).toBeTruthy();
    expect(downloadSignal).toBeTruthy();
    expect(createSignal).not.toBe(downloadSignal);
    resolveDownload("download-capability");
    resolveCreate({ ...queued, id: "job-2" });
    await waitFor(() => {
      expect(screen.getByTestId("creating").textContent).toBe("false");
      expect(screen.getByTestId("download-id").textContent).toBe("");
    });
  });

  it("navega a la página siguiente sin limitarse a los primeros 20 trabajos", async () => {
    vi.mocked(api.loadFiscalExportJobs)
      .mockResolvedValueOnce({ content: [{ ...queued, status: "COMPLETED", downloadAvailable: false }], totalElements: 101, totalPages: 2, number: 0, size: 100 })
      .mockResolvedValueOnce({ content: [{ ...queued, id: "job-101", status: "FAILED", downloadAvailable: false }], totalElements: 101, totalPages: 2, number: 1, size: 100 });
    render(<Harness />);
    await screen.findByText("Completado");
    fireEvent.click(screen.getByRole("button", { name: "Siguiente" }));
    await waitFor(() => expect(api.loadFiscalExportJobs).toHaveBeenLastCalledWith("token", 1, 100, expect.anything()));
    expect(await screen.findByText("Fallido")).toBeTruthy();
  });
});

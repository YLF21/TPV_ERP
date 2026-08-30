// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { addFiscalRecordIdsWithinLimit, FiscalRecordsView, trustedQrUrl } from "./FiscalRecordsView";
import * as api from "./fiscalRecordsApi";
import * as exportApi from "./verifactuManagementApi";

vi.mock("./fiscalRecordsApi", async (importOriginal) => ({ ...await importOriginal<typeof import("./fiscalRecordsApi")>(), loadFiscalRecordsCursor: vi.fn(), loadFiscalRecord: vi.fn() }));
vi.mock("./verifactuManagementApi", async (importOriginal) => ({ ...await importOriginal<typeof import("./verifactuManagementApi")>(), createFiscalExportJob: vi.fn(), loadFiscalExportJobs: vi.fn(), loadFiscalExportJobStatus: vi.fn(), downloadFiscalExportJob: vi.fn(), downloadFiscalExportZip: vi.fn() }));

const messages: Record<string, string> = {
  "verifactu.ui.filters": "Filtros", "verifactu.ui.export": "Exportar", "verifactu.ui.close": "Cerrar", "verifactu.ui.resizeColumn": "Redimensionar columna",
  "verifactu.records.title": "Registros fiscales", "verifactu.records.visibleCount": "visibles", "verifactu.records.number": "Número", "verifactu.records.numberMatch": "Coincidencia", "verifactu.records.numberExact": "Número exacto", "verifactu.records.numberPrefix": "Prefijo", "verifactu.records.selectionLimit": "Máximo 1.000 registros", "verifactu.records.operation": "Operación", "verifactu.records.documentType": "Tipo", "verifactu.records.fiscalMode": "Modalidad", "verifactu.records.viewDetail": "Ver detalle", "verifactu.records.closeDetail": "Cerrar detalle", "verifactu.records.detailTitle": "Detalle fiscal", "verifactu.records.detailEyebrow": "Registro congelado", "verifactu.records.hash": "Huella", "verifactu.records.previousHash": "Huella anterior", "verifactu.records.snapshotHash": "Huella del resumen", "verifactu.records.chainTitle": "Cadena", "verifactu.records.artifactTitle": "Artefacto fiscal", "verifactu.records.documentTitle": "Documento", "verifactu.records.submissionTitle": "Envío", "verifactu.records.none": "No disponible", "verifactu.records.qrUrl": "URL QR", "verifactu.records.openQr": "Abrir QR", "verifactu.records.sandbox": "Pruebas", "verifactu.records.notSubmitted": "No enviado", "verifactu.records.allOperations": "Todas", "verifactu.records.allTypes": "Todos", "verifactu.records.allModes": "Todas", "verifactu.records.applyFilters": "Aplicar", "verifactu.records.clearFilters": "Limpiar", "verifactu.records.previous": "Anterior", "verifactu.records.next": "Siguiente", "verifactu.records.previousRecord": "Registro anterior", "verifactu.records.nextRecord": "Siguiente registro", "verifactu.records.chainSequence": "Secuencia de cadena", "verifactu.records.detail": "Detalle", "verifactu.records.loading": "Cargando", "verifactu.records.refreshing": "Actualizando", "verifactu.records.empty": "Sin registros", "verifactu.records.emptyFiltered": "Sin resultados para estos filtros", "verifactu.records.loadError": "Error", "verifactu.records.retry": "Reintentar", "verifactu.records.detailLoading": "Cargando detalle", "verifactu.records.detailError": "Error detalle", "verifactu.records.selectRecord": "Seleccionar registro", "verifactu.records.selectAll": "Seleccionar visibles", "verifactu.records.copyHash": "Copiar huella", "verifactu.records.copiedHash": "Huella copiada", "verifactu.records.copyHashError": "Error copia", "verifactu.records.adjacentChain": "Encadenamiento adyacente", "verifactu.records.adjacentChain.valid": "Adyacencia correcta", "verifactu.records.adjacentChain.anomalous": "Adyacencia anómala", "verifactu.records.adjacentChain.unavailable": "Adyacencia no comprobable", "verifactu.records.exportTitle": "Exportación", "verifactu.records.exportScope": "Alcance", "verifactu.records.exportCurrent": "Actual", "verifactu.records.exportSelected": "Seleccionados", "verifactu.records.exportFiltered": "Filtrados", "verifactu.records.exportPeriod": "Periodo", "verifactu.records.exportStart": "Inicio", "verifactu.records.exportEnd": "Fin", "verifactu.records.export": "Exportar ZIP", "verifactu.records.exporting": "Exportando", "verifactu.records.exportSuccess": "Exportado", "verifactu.records.exportError": "Error exportación", "verifactu.records.exportInvalidPeriod": "Periodo inválido", "verifactu.records.exportCurrentRequired": "Abra un registro", "verifactu.records.exportSelectionRequired": "Seleccione un registro", "verifactu.records.exportPermission": "Sin permiso", "verifactu.management.fiscalMode.NO_VERIFACTU": "NO VERI*FACTU", "verifactu.operation.ALTA": "Alta", "verifactu.operation.ANULACION": "Anulación", "verifactu.status.ACEPTADO": "Aceptado", "verifactu.records.relation.RECTIFICA": "Rectifica a", "verifactu.records.documentStatus.CONFIRMADO": "Confirmado"
};
const t = (key: string) => messages[key] ?? key;
messages["verifactu.records.numberPrefixHint"] = "Busca por prefijo del número";
Object.assign(messages, {
  "verifactu.exportJobs.recentTitle": "Exportaciones recientes", "verifactu.exportJobs.refresh": "Actualizar trabajos", "verifactu.exportJobs.loading": "Cargando trabajos", "verifactu.exportJobs.error": "Error trabajos", "verifactu.exportJobs.retry": "Reintentar carga", "verifactu.exportJobs.empty": "Sin trabajos", "verifactu.exportJobs.processed": "procesados", "verifactu.exportJobs.failedHint": "Trabajo fallido", "verifactu.exportJobs.expiredHint": "Trabajo caducado", "verifactu.exportJobs.downloading": "Descargando", "verifactu.exportJobs.download": "Descargar ZIP", "verifactu.exportJobs.retryRequest": "Solicitar de nuevo", "verifactu.exportJobs.created": "Trabajo creado", "verifactu.exportJobs.kindBilling": "Registros de facturación", "verifactu.exportJobs.kindEvents": "Eventos", "verifactu.exportJobs.kindUnknown": "Exportación fiscal", "verifactu.exportJobs.statusQueued": "En cola", "verifactu.exportJobs.statusRunning": "En proceso", "verifactu.exportJobs.statusCompleted": "Completado", "verifactu.exportJobs.statusFailed": "Fallido", "verifactu.exportJobs.statusExpired": "Caducado", "verifactu.exportJobs.periodRegulatory": "Por periodo (reglamentaria)", "verifactu.exportJobs.partialHint": "Copia operativa/parcial"
});

describe("FiscalRecordsView", () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(exportApi.loadFiscalExportJobs).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    vi.mocked(exportApi.createFiscalExportJob).mockResolvedValue({ id: "job-1", status: "QUEUED", processed: 0, hasMore: true, error: null, fileSize: 0, downloadAvailable: false, createdAt: "2026-08-26T10:00:00Z" });
    vi.mocked(exportApi.loadFiscalExportJobStatus).mockResolvedValue({ id: "job-1", status: "QUEUED", processed: 0, hasMore: true, error: null, fileSize: 0, downloadAvailable: false, createdAt: "2026-08-26T10:00:00Z" });
    vi.mocked(exportApi.downloadFiscalExportJob).mockResolvedValue("download-capability");
    vi.mocked(exportApi.downloadFiscalExportZip).mockResolvedValue(new Response(new Blob(["zip"]), { status: 200 }));
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:test") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    vi.mocked(api.loadFiscalRecordsCursor).mockResolvedValue({ items: [{ recordId: "record-1", installationId: "i", storeId: "s", sequence: 1, operation: "ALTA", documentType: "F1", number: "T-1", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:00:00Z", fiscalMode: "NO_VERIFACTU", totalAmount: 12.5, hash: "1234567890ABCDEF1234567890ABCDEF", submissionStatus: "ACEPTADO" }], size: 25, nextCursor: "cursor-2", previousCursor: null, hasNext: true, hasPrevious: false, snapshotSequence: 1 });
    vi.mocked(api.loadFiscalRecord).mockResolvedValue({ recordId: "record-1", installationId: "i", storeId: "s", sequence: 1, operation: "ALTA", documentType: "F1", number: "T-1", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:00:00Z", fiscalMode: "NO_VERIFACTU", hash: "1234567890ABCDEF1234567890ABCDEF", snapshotHash: "snapshot-hash-1234567890", chainId: "chain", companyId: "company", timezone: "Europe/Madrid", issuerTaxId: "B12345678", formatVersion: "1", algorithmVersion: "1", applicationVersion: "dev", relations: [{ relatedRecordId: "record-2", type: "RECTIFICA" }], adjacentChainStatus: "ADJACENT_VALID", nextRecordId: "record-2", artifact: { fiscalMode: "NO_VERIFACTU", environment: "TEST", sandbox: true, xmlHash: "xml-hash", qrHash: "qr-hash", qrUrl: "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQRNoVerifactu?nif=B12345678&numserie=T-1&fecha=26-08-2026&importe=12.50" }, document: { id: "doc-1", storeId: "s", type: "F1", status: "CONFIRMADO", number: "T-1", issueDate: "2026-08-26" }, submission: { status: "ACEPTADO", updatedAt: "2026-08-26T10:00:00Z" } });
  });

  it("diferencia la carga inicial y no pinta una tabla vacía", () => {
    vi.mocked(api.loadFiscalRecordsCursor).mockImplementation(() => new Promise(() => {}));
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    expect(screen.getAllByRole("status").some((element) => element.textContent === "Cargando")).toBe(true);
    expect(screen.queryByRole("columnheader")).toBeNull();
  });

  it("muestra error y permite reintentar", async () => {
    vi.mocked(api.loadFiscalRecordsCursor)
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce({ items: [], size: 25, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false, snapshotSequence: 4 });
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    expect(await screen.findByRole("alert")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await waitFor(() => expect(api.loadFiscalRecordsCursor).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Sin registros")).toBeTruthy();
  });

  it("distingue una búsqueda filtrada sin resultados", async () => {
    vi.mocked(api.loadFiscalRecordsCursor).mockResolvedValue({ items: [], size: 25, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false, snapshotSequence: 5 });
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    await screen.findByText("Sin registros");
    fireEvent.click(screen.getByRole("button", { name: /^Filtros/ }));
    fireEvent.change(screen.getByRole("textbox", { name: /^Número/ }), { target: { value: "T-404" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    expect(await screen.findByText("Sin resultados para estos filtros")).toBeTruthy();
  });

  it("cancela la petición de lista al desmontar", () => {
    let signal: AbortSignal | undefined;
    vi.mocked(api.loadFiscalRecordsCursor).mockImplementation((_filters, _token, requestSignal) => {
      signal = requestSignal;
      return new Promise(() => {});
    });
    const view = render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    view.unmount();
    expect(signal?.aborted).toBe(true);
  });

  it("descarta una respuesta obsoleta cuando cambian los filtros", async () => {
    const resolves: Array<(value: Awaited<ReturnType<typeof api.loadFiscalRecordsCursor>>) => void> = [];
    vi.mocked(api.loadFiscalRecordsCursor).mockImplementation(() => new Promise((resolve) => resolves.push(resolve)));
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    fireEvent.click(screen.getByRole("button", { name: /^Filtros/ }));
    fireEvent.change(screen.getByRole("textbox", { name: /^Número/ }), { target: { value: "T-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(resolves).toHaveLength(2));
    resolves[0]({ items: [{ recordId: "old", installationId: "i", storeId: "s", sequence: 1, operation: "ALTA", documentType: "F1", number: "OLD", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:00:00Z", fiscalMode: "NO_VERIFACTU", hash: "old" }], size: 25, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false, snapshotSequence: 1 });
    resolves[1]({ items: [{ recordId: "new", installationId: "i", storeId: "s", sequence: 2, operation: "ALTA", documentType: "F1", number: "NEW", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:01:00Z", fiscalMode: "NO_VERIFACTU", hash: "new" }], size: 25, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false, snapshotSequence: 2 });
    expect(await screen.findByText("NEW")).toBeTruthy();
    expect(screen.queryByText("OLD")).toBeNull();
  });

  it("oculta secuencia en la tabla y la muestra en el detalle", async () => {
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    await screen.findByText("T-1");
    expect(screen.queryByRole("columnheader", { name: "Secuencia de cadena" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Ver detalle" }));
    expect(await screen.findByText("Secuencia de cadena")).toBeTruthy();
    expect(screen.getByText("1")).toBeTruthy();
  });

  it("muestra huellas completas, permite copiarlas y navega por enlace adyacente backend", async () => {
    vi.stubGlobal("navigator", { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    render(<FiscalRecordsView locale="es" revision={0} t={t} canManage />);
    fireEvent.click(await screen.findByRole("button", { name: "Ver detalle" }));
    expect(await screen.findByText("1234567890ABCDEF1234567890ABCDEF")).toBeTruthy();
    expect(screen.getByText("snapshot-hash-1234567890")).toBeTruthy();
    expect(screen.getByText("Adyacencia correcta")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: /Copiar huella/ })[0]);
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: "Siguiente registro" }));
    await waitFor(() => expect(api.loadFiscalRecord).toHaveBeenLastCalledWith("record-2", undefined, expect.anything()));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cerrar detalle" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("aplica filtros y abre un detalle sin exponer XML", async () => {
    render(<FiscalRecordsView locale="es" token="token" revision={0} t={t} />);
    expect(await screen.findByText("T-1")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /^Filtros/ }));
    const filterDialog = screen.getByRole("dialog");
    expect(within(filterDialog).getByText("Busca por prefijo del número")).toBeTruthy();
    expect(within(filterDialog).getByRole("button", { name: "Aplicar" })).toBeTruthy();
    expect(within(filterDialog).getByRole("button", { name: "Limpiar" })).toBeTruthy();
    expect(within(filterDialog).getAllByRole("button", { name: "Cerrar" })).toHaveLength(2);
    const number = screen.getByRole("textbox", { name: /^Número/ });
    fireEvent.change(number, { target: { value: "T-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(api.loadFiscalRecordsCursor).toHaveBeenLastCalledWith(expect.objectContaining({ number: "T-1", cursor: null }), "token", expect.anything()));
    fireEvent.click(screen.getByRole("button", { name: "Ver detalle" }));
    expect(await screen.findByRole("dialog")).toBeTruthy();
    expect(await screen.findByText("Artefacto fiscal")).toBeTruthy();
    expect(screen.getByText("Abrir QR")).toBeTruthy();
    expect(screen.getByText(/Rectifica a/)).toBeTruthy();
    expect(screen.getByText("Confirmado")).toBeTruthy();
    expect(screen.queryByText(/<.*XML|xml_firmado/i)).toBeNull();
  });

  it("permite número exacto y envía documentNumber sin convertirlo en prefijo", async () => {
    render(<FiscalRecordsView locale="es" token="token" timezone="Atlantic/Canary" revision={0} t={t} canManage />);
    await screen.findByText("T-1");
    fireEvent.click(screen.getByRole("button", { name: /^Filtros/ }));
    fireEvent.change(screen.getByRole("textbox", { name: /^Número/ }), { target: { value: "T-1" } });
    fireEvent.click(screen.getByRole("button", { name: /^Coincidencia/ }));
    fireEvent.click(screen.getByRole("option", { name: "Número exacto" }));
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(api.loadFiscalRecordsCursor).toHaveBeenLastCalledWith(expect.objectContaining({ number: "T-1", numberMatch: "EXACT" }), "token", expect.anything()));
    fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
    fireEvent.click(screen.getByRole("button", { name: /^Alcance:/ }));
    fireEvent.click(screen.getByRole("option", { name: "Filtrados" }));
    fireEvent.click(screen.getByRole("button", { name: "Exportar ZIP" }));
    await waitFor(() => expect(exportApi.createFiscalExportJob).toHaveBeenCalledWith(expect.objectContaining({ documentNumber: "T-1", documentNumberPrefix: null }), "token", expect.anything()));
  });

  it("limita la selección por lote y no materializa más de una página", async () => {
    const items = Array.from({ length: 1001 }, (_, index) => ({
      recordId: `record-${index}`, installationId: "i", storeId: "s", sequence: index + 1,
      operation: "ALTA" as const, documentType: "F1" as const, number: `T-${index}`,
      issueDate: "2026-08-26", generatedAt: "2026-08-26T10:00:00Z", fiscalMode: "NO_VERIFACTU" as const,
      hash: `hash-${index}`
    }));
    vi.mocked(api.loadFiscalRecordsCursor).mockResolvedValue({ items, size: 25, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false, snapshotSequence: 1 });
    render(<FiscalRecordsView locale="es" revision={0} t={t} canManage />);
    await screen.findByText("T-0");
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar visibles" }));
    expect(screen.queryByText("Máximo 1.000 registros")).toBeNull();
    expect(screen.getAllByRole("checkbox").filter((checkbox) => (checkbox as HTMLInputElement).checked)).toHaveLength(26);
    expect(screen.getAllByRole("row")).toHaveLength(26);
    expect(addFiscalRecordIdsWithinLimit(new Set<string>(), items.map((item) => item.recordId)).size).toBe(1000);
  });

  it("deshabilita la navegación del detalle mientras carga", async () => {
    vi.mocked(api.loadFiscalRecord).mockImplementation(() => new Promise(() => {}));
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    fireEvent.click(await screen.findByRole("button", { name: "Ver detalle" }));
    const dialog = await screen.findByRole("dialog");
    expect((within(dialog).getByRole("button", { name: "Registro anterior" }) as HTMLButtonElement).disabled).toBe(true);
    expect((within(dialog).getByRole("button", { name: "Siguiente registro" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("navega con el cursor siguiente y vuelve usando el cursor previo del backend", async () => {
    vi.mocked(api.loadFiscalRecordsCursor)
      .mockResolvedValueOnce({ items: [{ recordId: "record-1", installationId: "i", storeId: "s", sequence: 1, operation: "ALTA", documentType: "F1", number: "T-1", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:00:00Z", fiscalMode: "NO_VERIFACTU", hash: "hash-1" }], size: 25, nextCursor: "cursor-2", previousCursor: null, hasNext: true, hasPrevious: false, snapshotSequence: 42 })
      .mockResolvedValueOnce({ items: [{ recordId: "record-2", installationId: "i", storeId: "s", sequence: 2, operation: "ALTA", documentType: "F1", number: "T-2", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:01:00Z", fiscalMode: "NO_VERIFACTU", hash: "hash-2" }], size: 25, nextCursor: "cursor-3", previousCursor: "cursor-1", hasNext: true, hasPrevious: true, snapshotSequence: 42 })
      .mockResolvedValueOnce({ items: [{ recordId: "record-1", installationId: "i", storeId: "s", sequence: 1, operation: "ALTA", documentType: "F1", number: "T-1", issueDate: "2026-08-26", generatedAt: "2026-08-26T10:00:00Z", fiscalMode: "NO_VERIFACTU", hash: "hash-1" }], size: 25, nextCursor: "cursor-2", previousCursor: null, hasNext: true, hasPrevious: false, snapshotSequence: 42 });
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    await screen.findByText("T-1");
    fireEvent.click(screen.getByRole("button", { name: "Siguiente" }));
    await waitFor(() => expect(api.loadFiscalRecordsCursor).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: "cursor-2" }), undefined, expect.anything()));
    fireEvent.click(await screen.findByRole("button", { name: "Anterior" }));
    await waitFor(() => expect(api.loadFiscalRecordsCursor).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: "cursor-1" }), undefined, expect.anything()));
    expect(screen.queryByText(/Corte|snapshot/i)).toBeNull();
  });

  it("persiste el orden y el ancho de las columnas con el patrón compartido", async () => {
    localStorage.clear();
    render(<FiscalRecordsView locale="es" username="operador" revision={0} t={t} />);
    await screen.findByText("T-1");
    const numberHeader = document.querySelector('th[data-column-key="number"]') as HTMLElement;
    fireEvent.keyDown(numberHeader, { key: "ArrowLeft", ctrlKey: true });
    expect(document.querySelector("th[data-column-key]")?.getAttribute("data-column-key")).toBe("number");
    const numberResizer = numberHeader.querySelector(".table-layout-column-resizer") as HTMLButtonElement;
    fireEvent.keyDown(numberResizer, { key: "ArrowRight" });
    expect(document.querySelectorAll("col")[0]?.getAttribute("style")).toContain("width: 228px");
    cleanup();
    expect(localStorage.getItem("tpv-erp:gestion:user:operador:table:gestion.verifactu.records:layout")).toContain("number");
    localStorage.clear();
  });

  it("rechaza QR de un dominio externo aunque use HTTPS", () => {
    expect(trustedQrUrl("https://example.com/wlpl/TIKE-CONT/ValidarQR?nif=B12345678")).toBeNull();
    expect(trustedQrUrl("https://prewww2.aeat.es:443/wlpl/TIKE-CONT/ValidarQR?nif=B12345678")).toBeNull();
    expect(trustedQrUrl("https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQRNoVerifactu?nif=B12345678", {
      fiscalMode: "NO_VERIFACTU",
      environment: "TEST",
      sandbox: true
    })).not.toBeNull();
  });

  it("crea jobs para current, selected, filtered y period conservando su alcance", async () => {
    const choose = async (label: string, expectedCall?: number) => {
      fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
      fireEvent.click(screen.getByRole("button", { name: /^Alcance:/ }));
      fireEvent.click(screen.getByRole("option", { name: label }));
      await waitFor(() => expect(screen.getByRole("button", { name: new RegExp(`^Alcance: ${label}$`) })).toBeTruthy());
      fireEvent.click(screen.getByRole("button", { name: "Exportar ZIP" }));
      if (expectedCall !== undefined) await waitFor(() => expect(exportApi.createFiscalExportJob).toHaveBeenCalledTimes(expectedCall));
    };
    render(<FiscalRecordsView locale="es" timezone="Atlantic/Canary" revision={0} t={t} canManage />);
    fireEvent.click(await screen.findByRole("button", { name: "Ver detalle" }));
    await choose("Actual", 1);
    await waitFor(() => expect(exportApi.createFiscalExportJob).toHaveBeenCalledWith(expect.objectContaining({ kind: "BILLING", scope: "CURRENT", periodStart: null, periodEnd: null, recordIds: ["record-1"] }), undefined, expect.anything()));
    cleanup();

    render(<FiscalRecordsView locale="es" timezone="Atlantic/Canary" revision={0} t={t} canManage />);
    await screen.findByText("T-1");
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar registro T-1" }));
    await choose("Seleccionados", 2);
    await waitFor(() => expect(exportApi.createFiscalExportJob).toHaveBeenCalledWith(expect.objectContaining({ kind: "BILLING", scope: "SELECTED", periodStart: null, periodEnd: null, recordIds: ["record-1"] }), undefined, expect.anything()));
    cleanup();

    render(<FiscalRecordsView locale="es" timezone="Atlantic/Canary" revision={0} t={t} canManage />);
    await screen.findByText("T-1");
    fireEvent.click(screen.getByRole("button", { name: /^Filtros/ }));
    fireEvent.change(screen.getByRole("textbox", { name: /^Número/ }), { target: { value: "T-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(api.loadFiscalRecordsCursor).toHaveBeenLastCalledWith(expect.objectContaining({ number: "T-1", cursor: null }), undefined, expect.anything()));
    await choose("Filtrados", 3);
    await waitFor(() => expect(exportApi.createFiscalExportJob).toHaveBeenCalledWith(expect.objectContaining({ kind: "BILLING", scope: "FILTERED", documentNumberPrefix: "T-1" }), undefined, expect.anything()));
    cleanup();

    render(<FiscalRecordsView locale="es" timezone="Atlantic/Canary" revision={0} t={t} canManage />);
    await screen.findByText("T-1");
    fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
    fireEvent.change(screen.getByLabelText("Inicio"), { target: { value: "2026-08-26T10:00" } });
    fireEvent.change(screen.getByLabelText("Fin"), { target: { value: "2026-08-26T11:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Exportar ZIP" }));
    await waitFor(() => expect(exportApi.createFiscalExportJob).toHaveBeenLastCalledWith(expect.objectContaining({ kind: "BILLING", scope: "PERIOD", periodStart: "2026-08-26T09:00:00.000Z", periodEnd: "2026-08-26T10:00:00.000Z", recordIds: [] }), undefined, expect.anything()));
  });

  it("no renderiza casillas ni acciones de exportación sin gestión fiscal", async () => {
    render(<FiscalRecordsView locale="es" revision={0} t={t} />);
    await screen.findByText("T-1");
    expect(screen.queryByRole("checkbox")).toBeNull();
    expect(screen.queryByRole("button", { name: "Exportar" })).toBeNull();
  });
});

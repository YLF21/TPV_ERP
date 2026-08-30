// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FiscalComplianceView } from "./FiscalComplianceView";
import * as api from "./verifactuManagementApi";

vi.mock("./fiscalExportDownload", () => ({ downloadFiscalExport: vi.fn(), downloadFiscalExportBlob: vi.fn() }));
vi.mock("./verifactuManagementApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./verifactuManagementApi")>();
  return {
    ...original,
    loadFiscalEvents: vi.fn(),
    loadFiscalEventsCursor: vi.fn(),
    loadFiscalExportHistory: vi.fn(),
    loadFiscalExportHistoryCursor: vi.fn(),
    loadFiscalRequiredSubmissions: vi.fn(),
    loadFiscalRequiredSubmissionsCursor: vi.fn(),
    runFiscalIntegrityCheck: vi.fn(),
    createFiscalIntegrityJob: vi.fn(),
    loadFiscalIntegrityJobStatus: vi.fn(),
    retryFiscalIntegrityJob: vi.fn(),
    createFiscalExportJob: vi.fn(),
    loadFiscalExportJobs: vi.fn(),
    loadFiscalExportJobStatus: vi.fn(),
    downloadFiscalExportJob: vi.fn(),
    registerFiscalRequiredSubmission: vi.fn(),
    exportFiscalRequiredSubmission: vi.fn(),
    createFiscalRequiredSubmissionExportJob: vi.fn(),
    loadFiscalResponsibleDeclaration: vi.fn()
  };
});

const messages: Record<string, string> = {
  "verifactu.compliance.event.SUMMARY": "Resumen periódico (10)",
  "verifactu.compliance.signed": "Firmado",
  "verifactu.compliance.runIntegrity": "Comprobar ahora",
  "verifactu.compliance.registerRequirement": "Registrar requerimiento",
  "verifactu.compliance.integrityOk": "Integridad verificada sin anomalías",
  "verifactu.compliance.conservationEyebrow": "Conservación",
  "verifactu.compliance.exportTitle": "Exportación",
  "verifactu.compliance.exportHint": "Descarga fiscal",
  "verifactu.compliance.exportKind": "Tipo",
  "verifactu.compliance.exportBilling": "Registros",
  "verifactu.compliance.exportEvents": "Eventos",
  "verifactu.management.dateFrom": "Desde",
  "verifactu.management.dateTo": "Hasta",
  "verifactu.compliance.createExport": "Crear exportación",
  "verifactu.compliance.exportSuccess": "Exportación creada",
  "verifactu.compliance.exportError": "Error de exportación",
  "verifactu.compliance.managePermission": "Permiso requerido",
  "verifactu.ui.export": "Exportar",
  "verifactu.ui.exportDialogTitle": "Exportar cumplimiento",
  "verifactu.ui.close": "Cerrar",
  "verifactu.ui.resizeColumn": "Redimensionar columna",
  "verifactu.compliance.paginationLabel": "Paginación del historial fiscal",
  "verifactu.compliance.showFullHash": "Mostrar huella completa",
  "verifactu.compliance.visibleRecords": "{count} registros visibles",
  "verifactu.records.visibleCount": "visibles",
  "verifactu.records.copyHash": "Copiar huella",
  "verifactu.records.copiedHash": "Huella copiada",
  "verifactu.records.copyHashError": "Error al copiar la huella",
  "verifactu.compliance.requirementReference": "Referencia del requerimiento",
  "verifactu.compliance.attendRequirement": "Generar entrega firmada",
  "verifactu.compliance.declarationAction": "Consultar declaración",
  "verifactu.compliance.declarationTitle": "Declaración responsable",
  "verifactu.compliance.declarationEyebrow": "Documento fiscal",
  "verifactu.compliance.declarationLoading": "Consultando...",
  "verifactu.compliance.declarationError": "Error declaración",
  "verifactu.compliance.declarationStatus.AVAILABLE": "Disponible",
  "verifactu.compliance.declarationDownload": "Descargar declaración"
};
Object.assign(messages, {
  "verifactu.exportJobs.recentTitle": "Exportaciones recientes", "verifactu.exportJobs.refresh": "Actualizar trabajos", "verifactu.exportJobs.loading": "Cargando trabajos", "verifactu.exportJobs.error": "Error trabajos", "verifactu.exportJobs.retry": "Reintentar carga", "verifactu.exportJobs.empty": "Sin trabajos", "verifactu.exportJobs.processed": "procesados", "verifactu.exportJobs.failedHint": "Trabajo fallido", "verifactu.exportJobs.expiredHint": "Trabajo caducado", "verifactu.exportJobs.downloading": "Descargando", "verifactu.exportJobs.download": "Descargar ZIP", "verifactu.exportJobs.retryRequest": "Solicitar de nuevo", "verifactu.exportJobs.created": "Trabajo creado", "verifactu.exportJobs.kindBilling": "Registros de facturación", "verifactu.exportJobs.kindEvents": "Eventos", "verifactu.exportJobs.kindUnknown": "Exportación fiscal", "verifactu.exportJobs.statusQueued": "En cola", "verifactu.exportJobs.statusRunning": "En proceso", "verifactu.exportJobs.statusCompleted": "Completado", "verifactu.exportJobs.statusFailed": "Fallido", "verifactu.exportJobs.statusExpired": "Caducado", "verifactu.exportJobs.periodRegulatory": "Por periodo (reglamentaria)", "verifactu.exportJobs.partialHint": "Copia operativa/parcial", "verifactu.exportJobs.page": "Páginas de trabajos", "verifactu.exportJobs.previous": "Anterior", "verifactu.exportJobs.next": "Siguiente"
});
const t = (key: string) => messages[key] ?? key;

afterEach(() => cleanup());

describe("FiscalComplianceView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.loadFiscalEvents).mockResolvedValue([{
      id: "event-1",
      installationId: "installation-1",
      systemVersionId: "version-1",
      sequence: 10,
      type: "SUMMARY",
      fiscalMode: "NO_VERIFACTU",
      generatedAt: "2026-08-26T10:00:00Z",
      previousHash: "previous",
      hash: "1234567890ABCDEF1234567890ABCDEF",
      xmlHash: "xml-hash",
      signed: true
    }]);
    vi.mocked(api.loadFiscalEventsCursor).mockResolvedValue({ items: [{
      id: "event-1",
      installationId: "installation-1",
      systemVersionId: "version-1",
      sequence: 10,
      type: "SUMMARY",
      fiscalMode: "NO_VERIFACTU",
      generatedAt: "2026-08-26T10:00:00Z",
      previousHash: "previous",
      hash: "1234567890ABCDEF1234567890ABCDEF",
      xmlHash: "xml-hash",
      signed: true
    }], size: 50, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false, snapshotSequence: 10 });
    vi.mocked(api.loadFiscalExportHistory).mockResolvedValue([]);
    vi.mocked(api.loadFiscalExportHistoryCursor).mockResolvedValue({ items: [], size: 50, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false });
    vi.mocked(api.loadFiscalRequiredSubmissions).mockResolvedValue([]);
    vi.mocked(api.loadFiscalRequiredSubmissionsCursor).mockResolvedValue({ items: [], size: 50, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false });
    vi.mocked(api.loadFiscalExportJobs).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    vi.mocked(api.createFiscalExportJob).mockResolvedValue({ id: "job-1", status: "QUEUED", processed: 0, hasMore: true, error: null, fileSize: 0, downloadAvailable: false, createdAt: "2026-08-26T10:00:00Z" });
    vi.mocked(api.createFiscalRequiredSubmissionExportJob).mockResolvedValue({ id: "job-req", status: "QUEUED", processed: 0, hasMore: true, error: null, fileSize: 0, downloadAvailable: false, createdAt: "2026-08-26T10:00:00Z", requiredSubmissionId: "requirement-1" });
    vi.mocked(api.loadFiscalResponsibleDeclaration).mockResolvedValue({ status: "AVAILABLE", fileName: "declaracion.pdf", contentType: "application/pdf", size: 2048, sha256: "abc", issuedAt: "2026-08-26T10:00:00Z" });
  });

  it("ofrece trazabilidad al lector sin exponer acciones de mutación", async () => {
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      username="tester"
      canManage={false}
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    expect(await screen.findByText("Resumen periódico (10)")).toBeTruthy();
    expect(screen.getByText("Firmado")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Comprobar ahora" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Registrar requerimiento" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Exportar" })).toBeNull();
  });

  it("permite desplegar y copiar la huella completa del evento", async () => {
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      username="tester"
      canManage={false}
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    await screen.findByText("Resumen periódico (10)");
    fireEvent.click(screen.getByText("12345678…90ABCDEF"));
    expect(screen.getByText("1234567890ABCDEF1234567890ABCDEF")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Copiar huella 1234567890ABCDEF1234567890ABCDEF" })).toBeTruthy();
  });

  it("localiza la navegación y el contador de cada página fiscal", async () => {
    vi.mocked(api.loadFiscalEventsCursor).mockResolvedValue({ items: [{
      id: "event-1", installationId: "installation-1", systemVersionId: "version-1", sequence: 10,
      type: "SUMMARY", fiscalMode: "NO_VERIFACTU", generatedAt: "2026-08-26T10:00:00Z",
      previousHash: "previous", hash: "1234567890ABCDEF1234567890ABCDEF", xmlHash: "xml-hash", signed: true
    }], size: 50, nextCursor: "next", previousCursor: "previous", hasNext: true, hasPrevious: true, snapshotSequence: 10 });
    render(<FiscalComplianceView locale="es" token="token" mode="NO_VERIFACTU" username="tester" canManage={false} revision={0} t={t} onChanged={vi.fn()} />);
    expect(await screen.findByText("1 registros visibles")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Paginación del historial fiscal" })).toBeTruthy();
  });

  it("valida que la exportación fiscal siempre tenga el periodo completo", async () => {
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      timezone="Atlantic/Canary"
      username="tester"
      canManage
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Crear exportación" }));
    expect(api.createFiscalExportJob).not.toHaveBeenCalled();
    expect(await screen.findByText("Error de exportación")).toBeTruthy();
  });

  it("crea el control durable, muestra el progreso y presenta el resultado", async () => {
    vi.mocked(api.createFiscalIntegrityJob).mockResolvedValue({
      id: "integrity-1", mode: "NO_VERIFACTU", status: "QUEUED",
      billingSnapshotSequence: 24, eventSnapshotSequence: 10,
      billingChecked: 0, eventsChecked: 0, anomaliesTotal: 0,
      billingAnomalies: 0, eventAnomalies: 0, evidenceCodes: [],
      createdAt: "2026-08-26T10:00:00Z", updatedAt: "2026-08-26T10:00:00Z"
    });
    vi.mocked(api.loadFiscalIntegrityJobStatus).mockResolvedValue({
      id: "integrity-1", mode: "NO_VERIFACTU", status: "COMPLETED",
      billingSnapshotSequence: 24, eventSnapshotSequence: 10,
      billingChecked: 24, eventsChecked: 10, anomaliesTotal: 0,
      billingAnomalies: 0, eventAnomalies: 0, evidenceCodes: [],
      createdAt: "2026-08-26T10:00:00Z", updatedAt: "2026-08-26T10:00:01Z",
      completedAt: "2026-08-26T10:00:01Z"
    });
    const onChanged = vi.fn();
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      username="tester"
      canManage
      revision={0}
      t={t}
      onChanged={onChanged}
    />);

    fireEvent.click(screen.getByRole("button", { name: "Comprobar ahora" }));
    expect(await screen.findByText("Integridad verificada sin anomalías")).toBeTruthy();
    await waitFor(() => expect(api.createFiscalIntegrityJob).toHaveBeenCalledWith("token", expect.anything()));
    expect(onChanged).toHaveBeenCalled();
  });

  it("crea un job de cumplimiento conservando el periodo", async () => {
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      timezone="Atlantic/Canary"
      username="tester"
      canManage
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toBeTruthy();
    expect(dialog.classList.contains("fiscal-workspace-dialog-purpose-export")).toBe(true);
    fireEvent.change(screen.getByLabelText("Desde"), { target: { value: "2026-08-01T10:00" } });
    fireEvent.change(screen.getByLabelText("Hasta"), { target: { value: "2026-08-31T23:59" } });
    fireEvent.click(screen.getByRole("button", { name: "Crear exportación" }));

    await waitFor(() => expect(api.createFiscalExportJob).toHaveBeenCalledWith(
      { kind: "BILLING", scope: "PERIOD", periodStart: "2026-08-01T09:00:00.000Z", periodEnd: "2026-08-31T22:59:00.000Z", recordIds: [] }, "token", expect.anything()
    ));
    expect(await screen.findByText("Trabajo creado")).toBeTruthy();
  });

  it("abre y cierra el diálogo de exportación sin mutar el estado fiscal", async () => {
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      timezone="Atlantic/Canary"
      username="tester"
      canManage
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    fireEvent.click(await screen.findByRole("button", { name: "Exportar" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toBeTruthy();
    fireEvent.click(within(dialog).getAllByRole("button", { name: "Cerrar" })[0]);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(api.createFiscalExportJob).not.toHaveBeenCalled();
  });

  it("presenta las tres tablas semánticas con layout reordenable y redimensionable", async () => {
    vi.mocked(api.loadFiscalExportHistory).mockResolvedValue([{
      exportId: "export-1",
      companyId: "company-1",
      installationId: "installation-1",
      kind: "BILLING",
      exportedAt: "2026-08-26T10:00:00Z",
      recordCount: 10,
      contentHash: "export-hash"
    }]);
    vi.mocked(api.loadFiscalExportHistoryCursor).mockResolvedValue({
      items: [{
        exportId: "export-1",
        companyId: "company-1",
        installationId: "installation-1",
        kind: "BILLING",
        exportedAt: "2026-08-26T10:00:00Z",
        recordCount: 10,
        contentHash: "export-hash"
      }], size: 50, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false
    });
    vi.mocked(api.loadFiscalRequiredSubmissions).mockResolvedValue([{
      id: "requirement-1",
      companyId: "company-1",
      installationId: "installation-1",
      reference: "AEAT-1",
      status: "EXPORTADO",
      requestedAt: "2026-08-26T10:00:00Z"
    }]);
    vi.mocked(api.loadFiscalRequiredSubmissionsCursor).mockResolvedValue({
      items: [{
        id: "requirement-1",
        companyId: "company-1",
        installationId: "installation-1",
        reference: "AEAT-1",
        status: "EXPORTADO",
        requestedAt: "2026-08-26T10:00:00Z"
      }], size: 50, nextCursor: null, previousCursor: null, hasNext: false, hasPrevious: false
    });

    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      timezone="Atlantic/Canary"
      username="tester"
      canManage
      canAttendRequirements
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    const tables = await screen.findAllByRole("table");
    expect(tables).toHaveLength(3);
    expect(tables.every((table) => table.querySelector("th.table-layout-header-cell"))).toBe(true);
    expect(screen.getAllByRole("button", { name: /Redimensionar columna/ }).length).toBeGreaterThanOrEqual(3);

    const firstHeader = tables[0].querySelector("th[data-column-key='sequence']");
    expect(firstHeader).toBeTruthy();
    fireEvent.keyDown(firstHeader!, { key: "ArrowRight", ctrlKey: true });
    await waitFor(() => expect(tables[0].querySelector("thead tr")?.firstElementChild?.getAttribute("data-column-key")).toBe("eventType"));
  });

  it("atiende un requerimiento creando un job durable sin descarga síncrona", async () => {
    vi.mocked(api.registerFiscalRequiredSubmission).mockResolvedValue({
      id: "requirement-1", reference: "AEAT-REQ-1", status: "PENDIENTE", requestedAt: "2026-08-26T10:00:00Z"
    });
    render(<FiscalComplianceView
      locale="es"
      token="token"
      mode="NO_VERIFACTU"
      timezone="Atlantic/Canary"
      username="tester"
      canManage
      canAttendRequirements
      revision={0}
      t={t}
      onChanged={vi.fn()}
    />);

    fireEvent.change(screen.getByLabelText("Referencia del requerimiento"), { target: { value: " AEAT-REQ-1 " } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar requerimiento" }));
    const from = await screen.findByLabelText("Desde");
    const requirementFields = screen.getAllByLabelText("Desde");
    fireEvent.change(requirementFields[requirementFields.length - 1], { target: { value: "2026-08-01T10:00" } });
    const toFields = screen.getAllByLabelText("Hasta");
    fireEvent.change(toFields[toFields.length - 1], { target: { value: "2026-08-31T23:59" } });
    fireEvent.click(screen.getByRole("button", { name: "Generar entrega firmada" }));

    await waitFor(() => expect(api.createFiscalRequiredSubmissionExportJob).toHaveBeenCalledWith(
      "requirement-1", "2026-08-01T09:00:00.000Z", "2026-08-31T22:59:00.000Z", "token", expect.anything()
    ));
    expect(api.exportFiscalRequiredSubmission).not.toHaveBeenCalled();
    expect(await screen.findByRole("dialog")).toBeTruthy();
    void from;
  });

  it("permite consultar la declaración responsable también en modo solo lectura", async () => {
    render(<FiscalComplianceView locale="es" token="token" mode="NO_VERIFACTU" timezone="Atlantic/Canary" username="reader" canManage={false} revision={0} t={t} onChanged={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Consultar declaración" }));
    await waitFor(() => expect(api.loadFiscalResponsibleDeclaration).toHaveBeenCalledWith("token"));
    expect(await screen.findByText("Disponible")).toBeTruthy();
    expect(screen.getByText("declaracion.pdf")).toBeTruthy();
  });
});

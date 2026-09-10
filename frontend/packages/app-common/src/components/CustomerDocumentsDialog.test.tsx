// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { ApiError, apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { CustomerDocumentsDialog } from "./CustomerDocumentsDialog";
import { createTranslator } from "../i18n/LocalizedMessages";
import { loadTablePreference, saveTablePreference, tableLayoutStorageKey } from "./tableLayoutPreferences";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
vi.mock("./tableLayoutPreferences", async (original) => ({
  ...await original<typeof import("./tableLayoutPreferences")>(),
  loadTablePreference: vi.fn(), saveTablePreference: vi.fn(),
}));
const session: UserSession = { username: "test", displayName: "Test", accessToken: "test-token", permissions: ["VENTA"] };
const customer = { id: "customer-1", clientId: "C-001", fiscalName: "Cliente de prueba" };
const ticket = { id: "ticket-1", customerId: "customer-1", clienteId: "customer-1", numero: "T-001", fecha: "2026-09-09", tipo: "TICKET", estado: "PAGADO", base: "10.00", impuesto: "2.10", total: "12.10", terminalOrigenNombre: "Terminal 1", usuarioNombre: "Operador" };
const page = (items = [ticket], nextCursor: string | null = null) => ({ items, hasMore: Boolean(nextCursor), nextCursor });
function mount(props: Partial<Parameters<typeof CustomerDocumentsDialog>[0]> = {}) {
  return render(<CustomerDocumentsDialog customer={customer} session={session} locale="es" canEdit onEdit={vi.fn()} onClose={vi.fn()} {...props} />);
}
beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockResolvedValue(page());
  vi.mocked(loadTablePreference).mockReset().mockImplementation(async (app, tableKey) => ({ app, tableKey, columns: [] }));
  vi.mocked(saveTablePreference).mockReset().mockImplementation(async (app, tableKey, columns) => ({ app, tableKey, columns }));
});
afterEach(() => { cleanup(); localStorage.clear(); delete window.tpvDesktop; vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("CustomerDocumentsDialog", () => {
  it.each([
    ["es", "Resumen anual", "Modelo 347"],
    ["en", "Annual summary", "Form 347"],
    ["zh", "年度汇总", "347 表"],
  ] as const)("labels the annual summary button in %s without renaming the report", async (locale, label, title) => {
    mount({ locale, session: { ...session, permissions: ["INVOICES_READ"] } });
    await screen.findByText("T-001");
    expect(screen.queryByRole("button", { name: title })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: label }));
    expect(screen.getByRole("dialog", { name: title })).toBeInTheDocument();
  });

  it.each(["venta", "gestion"] as const)("opens Model 347 in %s from invoices even with empty or unapplied filters", async (app) => {
    vi.mocked(apiRequest).mockResolvedValue(page([]));
    mount({ app, session: { ...session, permissions: ["INVOICES_READ"] } });
    await screen.findByText("Este cliente no tiene documentos de este tipo en la tienda activa.");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "NO-MATCH" } });
    const modelButton = screen.getByRole("button", { name: "Resumen anual" }); modelButton.focus(); fireEvent.click(modelButton);
    expect(screen.getByRole("dialog", { name: "Modelo 347" })).toBeInTheDocument();
    expect(screen.getByRole("spinbutton", { name: "Año" })).toHaveFocus();
    expect(document.querySelector(".customer-documents-dialog")).toHaveAttribute("inert");
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    vi.mocked(apiRequest).mockResolvedValue({ arrayBuffer: async () => new ArrayBuffer(0) });
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "2025" } });
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect(apiRequest).toHaveBeenLastCalledWith("/customer-document-reports/customer-1/model-347.pdf?year=2025&locale=es", {
      token: "test-token", signal: expect.any(AbortSignal), responseType: "blob",
    });
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Modelo 347" })).not.toBeInTheDocument();
    expect(modelButton).toHaveFocus();
    expect(screen.getByRole("searchbox")).toHaveValue("NO-MATCH");
  });

  it("only offers Model 347 on the invoices tab with invoice access and shields parent shortcuts", async () => {
    const close = vi.fn(); const edit = vi.fn();
    mount({ onClose: close, onEdit: edit }); await screen.findByText("T-001");
    expect(screen.queryByRole("button", { name: "Resumen anual" })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "F2" }); await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Resumen anual" }));
    fireEvent.keyDown(window, { key: "F7" }); fireEvent.keyDown(window, { key: "F1" });
    expect(edit).not.toHaveBeenCalled(); expect(close).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "Modelo 347" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(close).not.toHaveBeenCalled();
    fireEvent.keyDown(window, { key: "F3" }); await screen.findByText("T-001");
    expect(screen.queryByRole("button", { name: "Resumen anual" })).not.toBeInTheDocument();
  });

  it.each(["customer", "inactive", "permission", "unmount", "tab"])("aborts Model 347 on %s changes without saving late responses", async (change) => {
    const saveFile = vi.fn(); let finish!: (blob: unknown) => void;
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    vi.mocked(apiRequest).mockImplementation(async (path) => path.includes("model-347.pdf")
      ? new Promise((resolve) => { finish = resolve; }) : page());
    const props = { customer, session: { ...session, permissions: ["INVOICES_READ", "TICKETS_READ"] as UserSession["permissions"] }, locale: "es" as const, canEdit: false, onEdit: vi.fn(), onClose: vi.fn() };
    const view = mount(props); await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("tab", { name: "Facturas F2" })); await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Resumen anual" }));
    fireEvent.click(screen.getByRole("button", { name: "Generar PDF" }));
    const request = vi.mocked(apiRequest).mock.calls.find(([path]) => path.includes("model-347.pdf"))!;
    if (change === "unmount") view.unmount();
    else if (change === "tab") fireEvent.click(document.getElementById("customer-documents-tab-tickets")!);
    else view.rerender(<CustomerDocumentsDialog {...props}
      customer={change === "customer" ? { ...customer, id: "another-customer" } : customer}
      active={change !== "inactive"}
      session={change === "permission" ? { ...session, permissions: ["CUSTOMERS_READ"] } : props.session} />);
    expect(request[1]!.signal?.aborted).toBe(true);
    expect(screen.queryByRole("dialog", { name: "Modelo 347" })).not.toBeInTheDocument();
    await act(async () => finish({ arrayBuffer: async () => new ArrayBuffer(0) }));
    expect(saveFile).not.toHaveBeenCalled();
  });

  it("reads paid tickets by customer, with a bounded page and no writes", async () => {
    mount();
    expect(await screen.findByText("T-001")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Pagado" })).toBeInTheDocument();
    expect(apiRequest).toHaveBeenCalledExactlyOnceWith("/document-reports/tickets?customerId=customer-1&limit=50&sortBy=date&sortDirection=desc", expect.objectContaining({ token: "test-token", signal: expect.any(AbortSignal) }));
    expect(screen.getByText("12,10 €")).toBeInTheDocument();
  });

  it("switches with F1/F2/F3 and mouse; F7 edits, Escape closes, modified shortcuts do not trigger", async () => {
    const edit = vi.fn(); const close = vi.fn();
    mount({ onEdit: edit, onClose: close });
    await screen.findByText("T-001");
    for (const [key, endpoint] of [["F2", "invoices"], ["F3", "delivery-notes"], ["F1", "tickets"]]) {
      fireEvent.keyDown(window, { key });
      await waitFor(() => expect(apiRequest).toHaveBeenLastCalledWith(`/document-reports/${endpoint}?customerId=customer-1&limit=50&sortBy=date&sortDirection=desc`, expect.anything()));
    }
    fireEvent.click(screen.getByRole("tab", { name: "Facturas F2" }));
    expect(screen.getByRole("tab", { name: "Facturas F2" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(window, { key: "F7", ctrlKey: true });
    fireEvent.keyDown(window, { key: "F7", repeat: true });
    expect(edit).not.toHaveBeenCalled();
    fireEvent.keyDown(window, { key: "F7" }); expect(edit).toHaveBeenCalledOnce();
    fireEvent.keyDown(window, { key: "Escape" }); expect(close).toHaveBeenCalledOnce();
  });

  it("respects document-specific permissions and read-only customer permissions", async () => {
    const edit = vi.fn();
    mount({ session: { ...session, permissions: ["INVOICES_READ", "CUSTOMERS_READ"] }, canEdit: false, onEdit: edit });
    await screen.findByText("T-001");
    expect(apiRequest).toHaveBeenCalledExactlyOnceWith("/document-reports/invoices?customerId=customer-1&limit=50&sortBy=date&sortDirection=desc", expect.anything());
    expect(screen.getByRole("tab", { name: "Tickets F1" })).toBeDisabled();
    fireEvent.keyDown(window, { key: "F1" });
    fireEvent.keyDown(window, { key: "F7" });
    expect(apiRequest).toHaveBeenCalledTimes(1); expect(edit).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Modificar cliente (F7)" })).toBeDisabled();
  });

  it("shows denied access without requesting reports when only customer permissions exist", () => {
    mount({ session: { ...session, permissions: ["CUSTOMERS_READ", "CUSTOMERS_WRITE"] } });
    expect(screen.getByText("No tienes permiso para consultar estos documentos.")).toBeInTheDocument();
    expect(apiRequest).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Modificar cliente (F7)" })).toBeEnabled();
  });

  it("appends on scroll without losing rows or selection, deduplicates and resets on tab change", async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(page([ticket], "date|time|id"))
      .mockResolvedValueOnce(page([ticket, { ...ticket, id: "t2", numero: "T-002" }])).mockResolvedValue(page());
    mount(); await screen.findByText("T-001");
    fireEvent.scroll(screen.getByRole("rowgroup"));
    await screen.findByText("T-002");
    expect(screen.getAllByText("T-001")).toHaveLength(1);
    expect(screen.getByText("T-001").closest("[role=row]")).toHaveClass("selected");
    expect(apiRequest).toHaveBeenLastCalledWith("/document-reports/tickets?customerId=customer-1&limit=50&sortBy=date&sortDirection=desc&cursor=date%7Ctime%7Cid", expect.anything());
    expect(screen.getByText("2 documentos cargados")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Siguiente" })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "F2" });
    await waitFor(() => expect(apiRequest).toHaveBeenLastCalledWith("/document-reports/invoices?customerId=customer-1&limit=50&sortBy=date&sortDirection=desc", expect.anything()));
  });

  it("ignores late responses after tab changes and aborts the old request", async () => {
    let finish!: (value: unknown) => void;
    vi.mocked(apiRequest).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }))
      .mockResolvedValueOnce(page([{ ...ticket, numero: "FV-001", tipo: "RECTIFICATIVA_VENTA" }]));
    mount();
    const signal = vi.mocked(apiRequest).mock.calls[0][1]?.signal;
    fireEvent.keyDown(window, { key: "F2" });
    await screen.findByText("FV-001");
    expect(signal?.aborted).toBe(true);
    await act(async () => finish(page()));
    expect(screen.queryByText("T-001")).not.toBeInTheDocument();
    expect(screen.getByText("Rectificativa")).toBeInTheDocument();
  });

  it("shows translated errors and retries the same read; empty tables keep their layout", async () => {
    vi.mocked(apiRequest).mockRejectedValueOnce(new Error("internal SQL" )).mockResolvedValueOnce(page([]));
    mount();
    expect(await screen.findByRole("alert")).not.toHaveTextContent("internal SQL");
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await screen.findByText("Este cliente no tiene documentos de este tipo en la tienda activa.");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(apiRequest).toHaveBeenCalledTimes(2);
  });

  it("rejects rows from another customer if an old backend ignores the filter", async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(page([{ ...ticket, customerId: "other-customer" }]));
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent("Actualiza o reinicia el backend");
    expect(screen.queryByText("T-001")).not.toBeInTheDocument();
  });

  it("supports selection by keyboard, tab navigation and focus restoration", async () => {
    const trigger = document.createElement("button"); document.body.append(trigger); trigger.focus();
    vi.mocked(apiRequest).mockResolvedValueOnce(page([ticket, { ...ticket, id: "t2", numero: "T-002" }]));
    const view = mount(); await screen.findByText("T-002");
    const body = screen.getByRole("rowgroup"); body.focus();
    fireEvent.keyDown(body, { key: "ArrowDown" });
    expect(screen.getByText("T-002").closest("[role=row]")).toHaveClass("selected");
    const tabs = screen.getByRole("tablist");
    fireEvent.keyDown(within(tabs).getByRole("tab", { name: "Tickets F1" }), { key: "ArrowRight" });
    expect(screen.getByRole("tab", { name: "Facturas F2" })).toHaveFocus();
    view.unmount(); expect(trigger).toHaveFocus(); trigger.remove();
  });

  it("uses a fixed header outside the growing scroll body, without white frame padding", () => {
    const css = readFileSync("packages/app-common/src/components/CustomerDocumentsDialog.css", "utf8");
    expect(css).toMatch(/padding: 0; gap: 0; display: flex; flex-direction: column/);
    expect(css).toMatch(/\.customer-documents-table-body\s*\{[^}]*flex: 1; min-height: 0; overflow-y: auto/);
    expect(css).not.toMatch(/position:\s*sticky/);
  });

  it("fills the trailing space with the last real column, including after reordering and with no rows", async () => {
    mount(); await screen.findByText("T-001");
    const headers = screen.getAllByRole("columnheader");
    const row = screen.getByText("T-001").closest<HTMLElement>("[role=row]")!;
    const headerRow = headers[0].parentElement!;
    expect(headers).toHaveLength(9);
    expect(within(row).getAllByRole("cell")).toHaveLength(9);
    expect(headerRow.style.gridTemplateColumns).toMatch(/minmax\(160px, 1fr\)$/);
    expect(row.style.gridTemplateColumns).toBe(headerRow.style.gridTemplateColumns);
    fireEvent.keyDown(headers[8], { key: "ArrowLeft", ctrlKey: true });
    expect(screen.getAllByRole("columnheader")[8]).toHaveAttribute("data-column-key", "terminal");
    expect(headerRow.style.gridTemplateColumns).toMatch(/minmax\(150px, 1fr\)$/);
    expect(row.style.gridTemplateColumns).toBe(headerRow.style.gridTemplateColumns);
    vi.mocked(apiRequest).mockResolvedValueOnce(page([]));
    fireEvent.keyDown(window, { key: "F3" });
    await screen.findByText("Este cliente no tiene documentos de este tipo en la tienda activa.");
    expect(screen.getAllByRole("columnheader")).toHaveLength(9);
    expect(screen.getAllByRole("columnheader")[8].parentElement!.style.gridTemplateColumns).toMatch(/minmax\(150px, 1fr\)$/);
  });

  it("applies number search, state and inclusive dates server-side; clears filters and restarts scrolling", async () => {
    mount(); await screen.findByText("T-001");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "  FV-10%_  " } });
    fireEvent.change(screen.getByLabelText("Fecha desde"), { target: { value: "2026-09-01" } });
    fireEvent.change(screen.getByLabelText("Fecha hasta"), { target: { value: "2026-09-09" } });
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "Pendiente" }));
    expect(apiRequest).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Exportar a Excel" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Aplicar filtro" }));
    await waitFor(() => expect(apiRequest).toHaveBeenLastCalledWith(expect.stringContaining("search=FV-10%25_&status=PENDIENTE&dateFrom=2026-09-01&dateTo=2026-09-09"), expect.anything()));
    fireEvent.click(screen.getByRole("button", { name: "Limpiar filtros" }));
    await waitFor(() => expect(apiRequest).toHaveBeenLastCalledWith("/document-reports/tickets?customerId=customer-1&limit=50&sortBy=date&sortDirection=desc", expect.anything()));
    expect(screen.getByRole("searchbox")).toHaveValue("");
  });

  it("rejects inverted dates locally and Escape closes the dropdown before the window", async () => {
    const close = vi.fn(); mount({ onClose: close }); await screen.findByText("T-001");
    fireEvent.change(screen.getByLabelText("Fecha desde"), { target: { value: "2026-09-09" } });
    fireEvent.change(screen.getByLabelText("Fecha hasta"), { target: { value: "2026-09-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar filtro" }));
    expect(screen.getByRole("alert")).toHaveTextContent("La fecha hasta no puede ser anterior");
    expect(apiRequest).toHaveBeenCalledTimes(1);
    const status = screen.getByRole("button", { name: "Estado" }); fireEvent.click(status);
    fireEvent.keyDown(status, { key: "Escape" });
    expect(status).toHaveAttribute("aria-expanded", "false"); expect(close).not.toHaveBeenCalled();
    fireEvent.keyDown(window, { key: "Escape" }); expect(close).toHaveBeenCalledOnce();
  });

  it("sorts all nine columns on the server using the existing header controls", async () => {
    mount(); await screen.findByText("T-001");
    const names = ["Documento", "Fecha", "Tipo", "Estado", "Base imponible", "Impuestos", "Total", "Terminal", "Usuario"];
    const keys = ["number", "date", "type", "status", "base", "tax", "total", "terminal", "user"];
    for (const [index, name] of names.entries()) {
      const button = screen.getByRole("button", { name: `Ordenar por ${name}` });
      fireEvent.click(button);
      await waitFor(() => expect(apiRequest).toHaveBeenLastCalledWith(expect.stringContaining(`sortBy=${keys[index]}&sortDirection=asc`), expect.anything()));
      expect(button.closest("[role=columnheader]")).toHaveAttribute("aria-sort", "ascending");
      fireEvent.click(button);
      await waitFor(() => expect(apiRequest).toHaveBeenLastCalledWith(expect.stringContaining(`sortBy=${keys[index]}&sortDirection=desc`), expect.anything()));
    }
  });

  it("keeps loaded rows after a scroll failure and retries that cursor without duplicates", async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(page([ticket], "next"))
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce(page([{ ...ticket, id: "t2", numero: "T-002" }]));
    mount(); await screen.findByText("T-001"); fireEvent.scroll(screen.getByRole("rowgroup"));
    await screen.findByRole("alert"); expect(screen.getByText("T-001")).toBeInTheDocument();
    fireEvent.scroll(screen.getByRole("rowgroup")); expect(apiRequest).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" })); await screen.findByText("T-002");
    expect(screen.getByText("2 documentos cargados")).toBeInTheDocument();
  });

  it("renders a bounded window of rows and reveals the keyboard-selected last row", async () => {
    vi.mocked(apiRequest).mockResolvedValue(page(Array.from({ length: 500 }, (_, index) => ({ ...ticket, id: String(index), numero: `DOC-${index}` }))));
    mount(); await screen.findByText("DOC-0");
    expect(screen.getAllByRole("row").length).toBeLessThan(35);
    const body = screen.getByRole("rowgroup"); Object.defineProperty(body, "clientHeight", { value: 440 });
    fireEvent.keyDown(body, { key: "End" });
    await screen.findByText("DOC-499");
    expect(screen.getByText("DOC-499").closest("[role=row]")).toHaveClass("selected");
    expect(screen.getAllByRole("row").length).toBeLessThan(35);
  });

  it("exports exactly loaded IDs without filters and all filtered results with translated visible columns", async () => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    const blob = { arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer };
    vi.mocked(apiRequest).mockImplementation(async (path) => path.endsWith("export.xlsx") ? blob : page([ticket]));
    mount(); await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Exportar a Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({ defaultFileName: "C-001-Cliente de prueba-Tickets.xlsx" }));
    let body = vi.mocked(apiRequest).mock.calls.find(([path]) => path.endsWith("export.xlsx"))![1]!.body;
    expect(body).toMatchObject({ reportKey: "tickets", customerId: customer.id, filters: {}, documentIds: [ticket.id], sortBy: "date", sortDirection: "desc" });
    expect(body).toMatchObject({ columns: [{ key: "number", label: "Documento" }, ...["date", "type", "status", "base", "tax", "total", "terminal", "user"].map((key) => ({ key, label: expect.any(String) }))] });
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "T-" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar filtro" })); await screen.findByText("T-001");
    await waitFor(() => expect(screen.getByRole("button", { name: "Exportar a Excel" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Exportar a Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(2));
    body = vi.mocked(apiRequest).mock.calls.filter(([path]) => path.endsWith("export.xlsx")).at(-1)![1]!.body;
    expect(body).toMatchObject({ filters: { search: "T-" }, labels: { statuses: { PENDIENTE: "Pendiente" } } });
    expect(body).not.toHaveProperty("documentIds");
    expect(vi.mocked(apiRequest).mock.calls.every(([path, options]) => !options?.body || path.endsWith("export.xlsx"))).toBe(true);
  });

  it("blocks double export and aborts it on close without saving a late response", async () => {
    const saveFile = vi.fn(); let finish!: (blob: unknown) => void;
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    vi.mocked(apiRequest).mockResolvedValueOnce(page()).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    const view = mount(); await screen.findByText("T-001");
    const button = screen.getByRole("button", { name: "Exportar a Excel" }); fireEvent.click(button); fireEvent.click(button);
    expect(apiRequest).toHaveBeenCalledTimes(2);
    const signal = vi.mocked(apiRequest).mock.calls[1][1]!.signal;
    view.unmount(); expect(signal?.aborted).toBe(true);
    await act(async () => finish({ arrayBuffer: async () => new ArrayBuffer(0) }));
    expect(saveFile).not.toHaveBeenCalled();
  });

  it.each([
    ["es", "Código del cliente", "NIF", "Nombre del cliente", "Total documentos", "Filtros", "Sin filtros", "Tickets"],
    ["en", "Customer code", "Tax ID", "Customer name", "Documents total", "Filters", "No filters", "Tickets"],
    ["zh", "客户编号", "税号", "客户名称", "单据合计", "筛选条件", "无筛选条件", "小票"],
  ] as const)("sends customer, filter and total labels in %s without supplying customer identity values", async (locale, customerCode, customerTaxId, customerName, grandTotal, title, none, documentType) => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    vi.mocked(apiRequest).mockImplementation(async (path) => path.endsWith("export.xlsx")
      ? { arrayBuffer: async () => new ArrayBuffer(0) } : page());
    mount({ locale }); await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: createTranslator(locale)("stock.history.exportExcel") }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({ defaultFileName: `C-001-Cliente de prueba-${documentType}.xlsx` }));
    const request = vi.mocked(apiRequest).mock.calls.find(([path]) => path.endsWith("export.xlsx"))![1]!.body;
    expect(request).toMatchObject({ customerId: customer.id, filters: {}, labels: {
      customerCode, customerTaxId, customerName, grandTotal, filters: { title, none },
    } });
    expect(request).not.toHaveProperty("fiscalName");
    expect(request).not.toHaveProperty("documentNumber");
    expect(request).not.toHaveProperty("clientId");
  });

  it.each([
    ["INVOICES_READ", "Facturas"], ["DELIVERY_NOTES_READ", "Albaranes"],
  ] as const)("names the desktop export with customer code, name and %s document type", async (permission, documentType) => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    vi.mocked(apiRequest).mockImplementation(async (path) => path.endsWith("export.xlsx")
      ? { arrayBuffer: async () => new ArrayBuffer(0) } : page());
    mount({ app: "gestion", session: { ...session, permissions: [permission] } });
    await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Exportar a Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({
      defaultFileName: `C-001-Cliente de prueba-${documentType}.xlsx`,
    })));
  });

  it("uses the same safe browser filename, preserving accents and Chinese and bounding long names", async () => {
    const createObjectURL = vi.fn().mockReturnValue("blob:customer-export");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", class extends URL {
      static createObjectURL = createObjectURL;
      static revokeObjectURL = revokeObjectURL;
    });
    let downloadedName = "";
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloadedName = this.download;
    });
    vi.mocked(apiRequest).mockImplementation(async (path) => path.endsWith("export.xlsx") ? new Blob() : page());
    mount({ customer: { ...customer, clientId: "  AC/001  ", fiscalName: 'Niño "海洋" ' + "X".repeat(150) } });
    await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Exportar a Excel" }));
    await waitFor(() => expect(downloadedName).toBe(`AC-001-Niño -海洋- ${"X".repeat(70)}-Tickets.xlsx`));
    expect(downloadedName.length).toBeLessThan(255);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:customer-export");
  });

  it("shows a translated export limit error without technical details", async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(page()).mockRejectedValueOnce(new ApiError("internal SQL", 400, { code: "customer_documents_export_limit_exceeded" }));
    mount(); await screen.findByText("T-001"); fireEvent.click(screen.getByRole("button", { name: "Exportar a Excel" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("50.000 documentos");
    expect(screen.getByRole("alert")).not.toHaveTextContent("internal SQL");
  });

  it("clears accumulated documents immediately if the read permission is revoked", async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce(page([ticket], "next"))
      .mockResolvedValueOnce(page([{ ...ticket, id: "t2", numero: "T-002" }]));
    const view = mount(); await screen.findByText("T-001"); fireEvent.scroll(screen.getByRole("rowgroup")); await screen.findByText("T-002");
    view.rerender(<CustomerDocumentsDialog customer={customer} session={{ ...session, permissions: ["CUSTOMERS_READ"] }} locale="es" canEdit={false} onEdit={vi.fn()} onClose={vi.fn()} />);
    expect(screen.queryByText("T-001")).not.toBeInTheDocument(); expect(screen.queryByText("T-002")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Exportar a Excel" })).toBeDisabled();
  });

  it("restores the virtual viewport offset after returning from F7", async () => {
    const documents = Array.from({ length: 60 }, (_, index) => ({ ...ticket, id: String(index), numero: `DOC-${index}` }));
    vi.mocked(apiRequest).mockResolvedValueOnce(page(documents.slice(0, 50), "next"))
      .mockResolvedValue(page(documents.slice(50)));
    const view = mount(); await screen.findByText("DOC-0");
    fireEvent.scroll(screen.getByRole("rowgroup"), { target: { scrollTop: 1700 } });
    await screen.findByText("60 documentos cargados");
    const props = { customer, session, locale: "es" as const, canEdit: true, onEdit: vi.fn(), onClose: vi.fn() };
    view.rerender(<CustomerDocumentsDialog {...props} active={false} />);
    expect(screen.queryByRole("rowgroup")).not.toBeInTheDocument();
    view.rerender(<CustomerDocumentsDialog {...props} />);
    expect(screen.getByRole("rowgroup").scrollTop).toBe(1700);
    await screen.findByText("60 documentos cargados");
    expect(screen.getByRole("rowgroup").scrollTop).toBe(1700);
  });

  it("moves columns from the Stock menu and closes that menu before the history on Escape", async () => {
    const close = vi.fn(); mount({ onClose: close }); await screen.findByText("T-001");
    const header = screen.getAllByRole("columnheader")[0];
    const menu = within(header).getByRole("button", { name: "Opciones de columna" });
    fireEvent.click(menu);
    fireEvent.click(screen.getByRole("menuitem", { name: "Mover a la derecha" }));
    expect(screen.getAllByRole("columnheader").slice(0, 2).map((cell) => cell.dataset.columnKey)).toEqual(["date", "number"]);
    expect(within(screen.getByText("T-001").closest("[role=row]")!).getAllByRole("cell")[1]).toHaveTextContent("T-001");
    expect(header).toHaveFocus();
    fireEvent.click(menu); fireEvent.keyDown(menu, { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument(); expect(close).not.toHaveBeenCalled();
    fireEvent.click(menu); fireEvent.keyDown(window, { key: "F2" });
    await screen.findByText("T-001"); expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" }); expect(close).toHaveBeenCalledOnce();
  });

  it("keeps keyboard column order and width by user and application, including reopening", async () => {
    const view = mount(); await screen.findByText("T-001");
    const header = screen.getAllByRole("columnheader")[0]; header.focus();
    fireEvent.keyDown(header, { key: "ArrowRight", ctrlKey: true });
    fireEvent.keyDown(header.querySelector(".table-layout-column-resizer")!, { key: "ArrowRight" });
    expect(header).toHaveFocus();
    const order = () => screen.getAllByRole("columnheader").map((cell) => cell.dataset.columnKey);
    expect(order().slice(0, 2)).toEqual(["date", "number"]);
    expect(header.parentElement!.style.gridTemplateColumns).toContain("218px");
    await waitFor(() => expect(saveTablePreference).toHaveBeenCalledWith("venta", "customers.documents",
      expect.arrayContaining([expect.objectContaining({ key: "number", width: 218 })]), "test-token"));
    view.unmount(); const reopened = mount(); await screen.findByText("T-001");
    expect(order().slice(0, 2)).toEqual(["date", "number"]);
    reopened.unmount(); const gestion = mount({ app: "gestion" }); await screen.findByText("T-001");
    expect(order().slice(0, 2)).toEqual(["number", "date"]);
    expect(loadTablePreference).toHaveBeenCalledWith("gestion", "customers.documents", "test-token");
    gestion.unmount(); mount({ session: { ...session, username: "another-user" } }); await screen.findByText("T-001");
    expect(order().slice(0, 2)).toEqual(["number", "date"]);
    expect(localStorage.getItem(tableLayoutStorageKey("venta", "test", "customers.documents"))).toContain("218");
    expect(vi.mocked(apiRequest).mock.calls.every(([, options]) => !options?.body)).toBe(true);
  });

  it("drags columns without reloading documents and exports the displayed column order", async () => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as typeof window.tpvDesktop;
    vi.mocked(apiRequest).mockImplementation(async (path) => path.endsWith("export.xlsx")
      ? { arrayBuffer: async () => new ArrayBuffer(0) } : page());
    mount(); await screen.findByText("T-001");
    const headers = screen.getAllByRole("columnheader");
    const data = new Map<string, string>();
    const dataTransfer = { setData: (type: string, value: string) => data.set(type, value), getData: (type: string) => data.get(type) ?? "" };
    fireEvent.dragStart(headers[0], { dataTransfer }); fireEvent.dragOver(headers[2], { dataTransfer });
    fireEvent.drop(headers[2], { dataTransfer }); fireEvent.dragEnd(headers[0], { dataTransfer });
    const order = screen.getAllByRole("columnheader").map((cell) => cell.dataset.columnKey);
    expect(order.slice(0, 3)).toEqual(["date", "type", "number"]);
    expect(apiRequest).toHaveBeenCalledTimes(1);
    expect(screen.getByText("T-001").closest("[role=row]")).toHaveClass("selected");
    fireEvent.click(screen.getByRole("button", { name: "Exportar a Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect(apiRequest).toHaveBeenLastCalledWith("/customer-document-reports/export.xlsx", expect.objectContaining({
      body: expect.objectContaining({ columns: order.map((key) => ({ key, label: expect.any(String) })) }),
    }));
  });
});

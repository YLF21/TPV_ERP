// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { WarehouseTransferScreen } from "./WarehouseTransferScreen";
import * as api from "./warehouseOperationsApi";
import type { UserSession } from "../../../packages/app-common/src/types";

vi.mock("./warehouseOperationsApi", async (original) => ({
  ...await original<typeof import("./warehouseOperationsApi")>(),
  loadWarehouseOptions: vi.fn(),
  loadWarehouseDocumentProducts: vi.fn(),
  loadTransferDocuments: vi.fn(),
  loadTransferDocument: vi.fn(),
  searchWarehouseProducts: vi.fn(),
  saveTransferDocument: vi.fn(),
  confirmTransferDocument: vi.fn(),
  exportTransferList: vi.fn()
}));

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  vi.mocked(api.loadWarehouseOptions).mockResolvedValue([
    { id: "source", name: "ORIGEN", active: true },
    { id: "target", name: "DESTINO", active: true }
  ]);
  vi.mocked(api.loadTransferDocuments).mockResolvedValue({ items: [], hasMore: false, nextCursor: null });
  vi.mocked(api.loadWarehouseDocumentProducts).mockResolvedValue([{ id: "product", code: "P-1",
    name: "Producto", barcode: "843000", productType: "UNIT", active: true, purchasePrice: 3, salePrice: 5 }]);
  vi.mocked(api.searchWarehouseProducts).mockResolvedValue({ items: [{ product: {
    id: "product", code: "P-1", name: "Producto", barcode: "843000", productType: "UNIT", active: true
  } }] });
  vi.mocked(api.saveTransferDocument).mockResolvedValue({ document: {
    id: "transfer", storeId: "store", sourceWarehouseId: "source", targetWarehouseId: "target",
    status: "DRAFT", createdAt: "2026-09-22T10:00:00Z", version: 0
  }, lines: [{ productId: "product", code: "P-1", name: "Producto", barcode: "843000", quantity: 2 }] });
  vi.mocked(api.confirmTransferDocument).mockResolvedValue({ document: {
    id: "transfer", storeId: "store", sourceWarehouseId: "source", targetWarehouseId: "target",
    number: "TRA-2026-000001", status: "CONFIRMED", createdAt: "2026-09-22T10:00:00Z", version: 1
  }, lines: [{ productId: "product", code: "P-1", name: "Producto", barcode: "843000", quantity: 2 }] });
  vi.mocked(api.loadTransferDocument).mockResolvedValue({ document: {
    id: "transfer", storeId: "store", sourceWarehouseId: "source", targetWarehouseId: "target",
    number: "TRA-2026-000001", notes: "Reposición", status: "CONFIRMED", createdAt: "2026-09-22T10:00:00Z", version: 1
  }, lines: [{ productId: "product", code: "P-1", name: "Producto", barcode: "843000", quantity: 2 }] });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("opens a new transfer document from the warehouse screen", async () => {
  render(<WarehouseTransferScreen session={{ username: "admin", displayName: "Admin",
    accessToken: "token", permissions: ["GESTION_ALMACEN"] }} t={(key) => key} createOnMount />);
  const dialog = await screen.findByRole("dialog", { name: "warehouse.transfer.create" });
  await waitFor(() => expect(api.loadWarehouseOptions).toHaveBeenCalled());
  expect(dialog).toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "Archivo" })).toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "Guardar (F9)" })).toBeDisabled();
  expect(within(dialog).getByRole("button", { name: "Almacén de origen" })).toHaveTextContent("ORIGEN");
  expect(within(dialog).getByRole("button", { name: "Almacén de destino" })).toHaveTextContent("DESTINO");
  expect(within(dialog).getByLabelText("Buscar producto")).toBeInTheDocument();
  expect(within(dialog).getByRole("table")).toBeInTheDocument();
});

it("lists transfer documents like warehouse entries and opens the selected one", async () => {
  vi.mocked(api.loadTransferDocuments).mockResolvedValue({ items: [{
    id: "transfer", storeId: "store", sourceWarehouseId: "source", targetWarehouseId: "target",
    number: "TRA-2026-000001", notes: "Reposición", status: "CONFIRMED", createdAt: "2026-09-22T10:00:00Z", version: 1,
    lineCount: 2, totalUnits: 7.5
  }], hasMore: false, nextCursor: null });
  render(<WarehouseTransferScreen session={{ username: "admin", displayName: "Admin",
    accessToken: "token", permissions: ["GESTION_ALMACEN"] }} t={(key) => key} />);
  const row = await screen.findByRole("row", { name: /TRA-2026-000001/ });
  expect(screen.getByRole("button", { name: "warehouseDocument.view" })).toBeDisabled();
  expect(within(row).getByText("Reposición")).toBeInTheDocument();
  expect(screen.getByRole("columnheader", { name: /warehouse.transfer.lines/ })).toBeInTheDocument();
  expect(screen.getByRole("columnheader", { name: /warehouse.transfer.units/ })).toBeInTheDocument();
  expect(within(row).getByText("2")).toBeInTheDocument();
  expect(within(row).getByText(/7[,.]5/)).toBeInTheDocument();
  fireEvent.click(row);
  expect(row).toHaveAttribute("aria-selected", "true");
  fireEvent.click(screen.getByRole("button", { name: "warehouseDocument.view" }));
  await waitFor(() => expect(api.loadTransferDocument).toHaveBeenCalledWith("transfer", "token"));
  expect(await screen.findByRole("dialog", { name: /TRA-2026-000001/ })).toBeInTheDocument();
});

it("saves a transfer draft and applies it only after confirmation", async () => {
  render(<WarehouseTransferScreen session={{ username: "admin", displayName: "Admin",
    accessToken: "token", permissions: ["GESTION_ALMACEN"] }} t={(key) => key} />);
  const create = await screen.findByRole("button", { name: "warehouseDocument.create" });
  await waitFor(() => expect(create).toBeEnabled());
  fireEvent.click(create);
  const dialog = screen.getByRole("dialog", { name: "warehouse.transfer.create" });
  const search = within(dialog).getByLabelText("Buscar producto");
  fireEvent.change(search, { target: { value: "P-1" } });
  fireEvent.keyDown(search, { key: "Enter" });
  fireEvent.change(search, { target: { value: "P-1" } });
  fireEvent.keyDown(search, { key: "Enter" });
  expect(screen.getByText("843000")).toBeInTheDocument();
  fireEvent.keyDown(search, { key: "F9" });
  await waitFor(() => expect(api.saveTransferDocument).toHaveBeenCalled());
  expect(api.saveTransferDocument).toHaveBeenLastCalledWith(expect.objectContaining({
    sourceWarehouseId: "source", targetWarehouseId: "target", priceSource: "PURCHASE",
    lines: [expect.objectContaining({ productId: "product", quantity: 2, unitPrice: 3, discount: 0 })]
  }), "token", undefined);
  expect(api.confirmTransferDocument).not.toHaveBeenCalled();
  const confirm = within(dialog).getByRole("button", { name: "Confirmar" });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  await waitFor(() => expect(api.confirmTransferDocument).toHaveBeenCalledWith("transfer", 0, "token"));
});

it("searches and filters the full paged list while removing one filter chip at a time", async () => {
  render(<WarehouseTransferScreen session={{ username: "admin", displayName: "Admin",
    accessToken: "token", permissions: ["GESTION_ALMACEN"] }} t={(key) => key} />);
  await waitFor(() => expect(api.loadTransferDocuments).toHaveBeenCalled());
  fireEvent.click(screen.getByRole("button", { name: "warehouse.count.status" }));
  fireEvent.click(screen.getByRole("option", { name: "warehouse.count.status.DRAFT" }));
  fireEvent.click(screen.getByRole("button", { name: "warehouse.transfer.source" }));
  fireEvent.click(await screen.findByRole("option", { name: "ORIGEN" }));
  fireEvent.change(screen.getByRole("searchbox", { name: "warehouse.transfer.search" }),
    { target: { value: "TRA-2026" } });
  await waitFor(() => expect(api.loadTransferDocuments).toHaveBeenLastCalledWith(0, expect.objectContaining({
    status: "DRAFT", sourceWarehouseId: "source", search: "TRA-2026"
  }), "token"));
  const applied = screen.getByRole("group", { name: "filters.applied" });
  expect(within(applied).getByText("TRA-2026")).toBeInTheDocument();
  fireEvent.click(within(applied).getByRole("button", { name: "filters.remove warehouse.transfer.search" }));
  await waitFor(() => expect(api.loadTransferDocuments).toHaveBeenLastCalledWith(0, expect.objectContaining({
    status: "DRAFT", sourceWarehouseId: "source", search: ""
  }), "token"));
  expect(within(applied).getByText("ORIGEN")).toBeInTheDocument();
});

function scrollTransferList() {
  const scroll = screen.getByRole("table").parentElement!;
  Object.defineProperties(scroll, {
    clientHeight: { configurable: true, value: 400 },
    scrollHeight: { configurable: true, value: 1200 },
    scrollTop: { configurable: true, writable: true, value: 750 }
  });
  fireEvent.scroll(scroll);
}

function transferRow(id: string): api.TransferListItem {
  return { id, number: id, storeId: "store", sourceWarehouseId: "source", targetWarehouseId: "target",
    status: "CONFIRMED", createdAt: "2026-09-22T10:00:00Z", version: 1, lineCount: 2, totalUnits: 7 };
}

const listSession: UserSession = { username: "admin", displayName: "Admin", accessToken: "token", permissions: ["GESTION_ALMACEN"] };
type TransferPage = Awaited<ReturnType<typeof api.loadTransferDocuments>>;

it("exports the complete filtered list as PDF and Excel independently of loaded rows", async () => {
  vi.stubGlobal("URL", class extends URL {
    static createObjectURL = vi.fn(() => "blob:report");
    static revokeObjectURL = vi.fn();
  });
  const download = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  vi.mocked(api.exportTransferList).mockResolvedValue(new Blob(["report"]));
  render(<WarehouseTransferScreen session={listSession} t={(key) => key} locale="es" />);
  await waitFor(() => expect(api.loadTransferDocuments).toHaveBeenCalled());
  fireEvent.change(screen.getByRole("searchbox", { name: "warehouse.transfer.search" }), { target: { value: "TRA" } });
  fireEvent.click(screen.getByRole("button", { name: "warehouse.transfer.source" }));
  fireEvent.click(await screen.findByRole("option", { name: "ORIGEN" }));
  fireEvent.change(screen.getByLabelText("warehouse.report.from"), { target: { value: "2026-09-01" } });
  for (const [label, format] of [["PDF", "pdf"], ["Excel", "xlsx"]]) {
    const button = screen.getByRole("button", { name: label });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    await waitFor(() => expect(api.exportTransferList).toHaveBeenLastCalledWith(format,
      expect.objectContaining({ search: "TRA", sourceWarehouseId: "source", dateFrom: "2026-09-01" }), "es", "token"));
  }
  await waitFor(() => expect(download).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2), { timeout: 2000 });
});

it("appends on scroll without duplicate requests or rows and preserves selection", async () => {
  let resolveNext!: (page: TransferPage) => void;
  vi.mocked(api.loadTransferDocuments)
    .mockResolvedValueOnce({ items: [transferRow("TRA-1")], hasMore: true, nextCursor: null })
    .mockImplementationOnce(() => new Promise((resolve) => { resolveNext = resolve; }));
  render(<WarehouseTransferScreen session={listSession} t={(key) => key} />);
  const first = await screen.findByRole("row", { name: /TRA-1/ });
  fireEvent.click(first);
  expect(screen.queryByRole("button", { name: "warehouse.adjustment.next" })).not.toBeInTheDocument();
  scrollTransferList();
  await waitFor(() => expect(api.loadTransferDocuments).toHaveBeenCalledTimes(2));
  scrollTransferList();
  expect(api.loadTransferDocuments).toHaveBeenCalledTimes(2);
  expect(first).toHaveAttribute("aria-selected", "true");
  await act(async () => resolveNext({ items: [transferRow("TRA-1"), transferRow("TRA-2")], hasMore: false, nextCursor: null }));
  expect(await screen.findByRole("row", { name: /TRA-2/ })).toBeInTheDocument();
  expect(screen.getAllByRole("row", { name: /TRA-1/ })).toHaveLength(1);
  expect(first).toHaveAttribute("aria-selected", "true");
  scrollTransferList();
  expect(api.loadTransferDocuments).toHaveBeenCalledTimes(2);
});

it("discards an old scroll response when filters change and restarts from the first batch", async () => {
  let resolveOld!: (page: TransferPage) => void;
  vi.mocked(api.loadTransferDocuments)
    .mockResolvedValueOnce({ items: [transferRow("TRA-OLD")], hasMore: true, nextCursor: null })
    .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
    .mockResolvedValueOnce({ items: [transferRow("TRA-NEW")], hasMore: false, nextCursor: null });
  render(<WarehouseTransferScreen session={listSession} t={(key) => key} />);
  await screen.findByRole("row", { name: /TRA-OLD/ });
  scrollTransferList();
  await waitFor(() => expect(api.loadTransferDocuments).toHaveBeenCalledTimes(2));
  fireEvent.click(screen.getByRole("button", { name: "warehouse.count.status" }));
  fireEvent.click(screen.getByRole("option", { name: "warehouse.count.status.DRAFT" }));
  await screen.findByRole("row", { name: /TRA-NEW/ });
  expect(api.loadTransferDocuments).toHaveBeenLastCalledWith(0, expect.objectContaining({ status: "DRAFT" }), "token");
  await act(async () => resolveOld({ items: [transferRow("TRA-STALE")], hasMore: true, nextCursor: null }));
  expect(screen.queryByRole("row", { name: /TRA-OLD|TRA-STALE/ })).not.toBeInTheDocument();
  expect(screen.getByRole("table").parentElement!.scrollTop).toBe(0);
});

it("keeps loaded rows on an incremental error and retries the same batch on scroll", async () => {
  vi.mocked(api.loadTransferDocuments)
    .mockResolvedValueOnce({ items: [transferRow("TRA-1")], hasMore: true, nextCursor: null })
    .mockRejectedValueOnce(new Error("Load failed"))
    .mockResolvedValueOnce({ items: [transferRow("TRA-2")], hasMore: false, nextCursor: null });
  render(<WarehouseTransferScreen session={listSession} t={(key) => key} />);
  await screen.findByRole("row", { name: /TRA-1/ });
  scrollTransferList();
  await screen.findByRole("alert");
  expect(screen.getByRole("row", { name: /TRA-1/ })).toBeInTheDocument();
  scrollTransferList();
  await screen.findByRole("row", { name: /TRA-2/ });
  expect(vi.mocked(api.loadTransferDocuments).mock.calls.map((call) => call[0])).toEqual([0, 1, 1]);
});

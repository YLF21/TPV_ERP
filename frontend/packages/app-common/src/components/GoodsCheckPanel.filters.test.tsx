// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind } from "../types";
import { GoodsCheckPanel, type GoodsCheckItem, type GoodsCheckView, type PurchaseDocument } from "./GoodsCheckPanel";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));

const t = createTranslator("es");
const documents: PurchaseDocument[] = [
  { id: "invoice-north", documentType: "FACTURA_ENTRADA", status: "CONFIRMADA", number: "FE-1", date: "2026-09-21", supplierId: "north" },
  { id: "invoice-south", documentType: "FACTURA_ENTRADA", status: "CONFIRMADA", number: "FE-2", date: "2026-09-20", supplierId: "south" },
  { id: "delivery-north", documentType: "ALBARAN_ENTRADA", status: "CONFIRMADA", number: "AE-1", date: "2026-09-22", supplierId: "north" },
  { id: "delivery-south", documentType: "ALBARAN_ENTRADA", status: "CONFIRMADA", number: "AE-2", date: "2026-09-19", supplierId: "south" },
];
const items: GoodsCheckItem[] = [
  { productId: "balanced", code: "A", name: "Equilibrado", expectedQuantity: 1, registeredQuantity: 1, missingQuantity: 0, extraQuantity: 0 },
  { productId: "missing", code: "B", name: "Pendiente", expectedQuantity: 2, registeredQuantity: 0, missingQuantity: 2, extraQuantity: 0 },
  { productId: "extra", code: "C", name: "Sobrante", expectedQuantity: 1, registeredQuantity: 2, missingQuantity: 0, extraQuantity: 1 },
];

function show(app: AppKind = "venta") {
  return render(<GoodsCheckPanel app={app} locale="es" token="test-token" t={t}
    suppliers={[{ id: "north", legalName: "Proveedor Norte" }, { id: "south", legalName: "Proveedor Sur" }]} />);
}

function documentRows(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLTableRowElement>(".goods-check-document-list tbody tr")];
}

beforeEach(() => {
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path.startsWith("/warehouse-inputs?")) {
      const type = new URL(path, "http://goods-check.test").searchParams.get("type");
      return { items: documents.filter(document => document.documentType === type), hasMore: false };
    }
    if (path.endsWith("/import")) {
      return { id: "check", documentId: path.split("/")[3], status: "ABIERTA", todos: items,
        faltantes: [items[1]], registrados: [items[0], items[2]] } satisfies GoodsCheckView;
    }
    throw new Error(`Unexpected request: ${path}`);
  });
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Goods check ERP table filters", () => {
  it.each(["venta", "gestion"] as const)("removes search separately and preserves sort/selection in %s", async app => {
    const { container } = show(app);
    await waitFor(() => expect(documentRows(container)).toHaveLength(4));
    expect(container.querySelector(".goods-check-document-list")).toHaveClass("erp-classic-tables", "warehouse-classic-table");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    const search = screen.getByRole("searchbox", { name: "Buscar" });
    fireEvent.change(search, { target: { value: "Norte" } });
    fireEvent.click(screen.getByRole("button", { name: "Facturas" }));
    expect(documentRows(container)).toHaveLength(1);
    const selectedDocument = documentRows(container)[0];
    expect(selectedDocument).toHaveTextContent("FE-1");
    expect(selectedDocument).toHaveClass("selected");
    const sort = screen.getByRole("button", { name: `${t("party.sortBy")} Número` });
    fireEvent.click(sort);
    expect(sort).toHaveAttribute("data-sort-direction", "asc");
    const chips = screen.getByRole("group", { name: "Filtros aplicados" });
    expect(chips).toHaveTextContent("Buscar: Norte");
    expect(chips).toHaveTextContent("Tipo: Facturas");
    expect(chips.closest(".goods-check-documents-header")).not.toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(search).toHaveValue("");
    expect(documentRows(container)).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Facturas" })).toHaveAttribute("aria-pressed", "true");
    expect(chips).toHaveTextContent("Tipo: Facturas");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    expect(documentRows(container)).toHaveLength(4);
    expect(screen.getByRole("button", { name: "Todos" })).toHaveAttribute("aria-pressed", "true");
    expect(sort).toHaveAttribute("data-sort-direction", "asc");
    expect(selectedDocument).toHaveClass("selected");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    await waitFor(() => expect(search).toHaveFocus());
    expect(apiRequest).toHaveBeenCalledTimes(2);
  });

  it("removes only document type while keeping the existing search", async () => {
    const { container } = show();
    await waitFor(() => expect(documentRows(container)).toHaveLength(4));
    const search = screen.getByRole("searchbox", { name: "Buscar" });
    fireEvent.change(search, { target: { value: "Norte" } });
    fireEvent.click(screen.getByRole("button", { name: "Albaranes" }));
    expect(documentRows(container)).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Tipo" }));
    expect(documentRows(container)).toHaveLength(2);
    expect(search).toHaveValue("Norte");
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Buscar: Norte");
  });

  it.each(["venta", "gestion"] as const)("keeps line filters independent and preserves operational row markers in %s", async app => {
    const { container } = show(app);
    await waitFor(() => expect(documentRows(container)).toHaveLength(4));
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "Norte" } });
    fireEvent.click(screen.getByRole("button", { name: "Iniciar comprobación" }));
    const workspace = container.querySelector<HTMLElement>(".goods-check-workspace")!;
    const lineRows = () => workspace.querySelectorAll(".goods-check-lines tbody tr");
    await waitFor(() => expect(lineRows()).toHaveLength(3));
    expect(workspace.querySelector(".goods-check-lines")).toHaveClass("erp-classic-tables", "warehouse-classic-table");
    expect(workspace.querySelectorAll(".goods-check-difference")).toHaveLength(2);
    fireEvent.click(within(workspace).getByRole("button", { name: /^Con diferencias/ }));
    expect(lineRows()).toHaveLength(2);
    expect(within(workspace).getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Líneas: Con diferencias");
    fireEvent.click(within(workspace).getByRole("button", { name: "Quitar filtro Líneas" }));
    expect(lineRows()).toHaveLength(3);
    fireEvent.click(within(workspace).getByRole("button", { name: /^Escaneadas/ }));
    expect(lineRows()).toHaveLength(2);
    expect(within(workspace).getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Líneas: Escaneadas");
    fireEvent.click(within(workspace).getByRole("button", { name: "Limpiar todos" }));
    expect(lineRows()).toHaveLength(3);
    expect(within(workspace).queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    expect(documentRows(container)).toHaveLength(2);
    expect(screen.getByRole("searchbox", { name: "Buscar" })).toHaveValue("Norte");
    await waitFor(() => expect(within(workspace).getByRole("button", { name: /^Todas las líneas/ })).toHaveFocus());
    expect(apiRequest).toHaveBeenCalledTimes(3);
  });

  it.each(["pda"] as const)("preserves the existing filters and appearance in %s", async (app) => {
    const { container } = show(app);
    await waitFor(() => expect(documentRows(container)).toHaveLength(4));
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar" }), { target: { value: "Norte" } });
    fireEvent.click(screen.getByRole("button", { name: "Facturas" }));
    expect(documentRows(container)).toHaveLength(1);
    expect(container.querySelector(".erp-classic-tables")).toBeNull();
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });
});

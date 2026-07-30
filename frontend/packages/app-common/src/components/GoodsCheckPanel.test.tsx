import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  GoodsCheckPanel,
  filterGoodsCheckDocuments,
  goodsCheckClosePath,
  goodsCheckDocumentIsAvailable,
  goodsCheckDocumentPath,
  goodsCheckScanPath,
  loadGoodsCheckDocuments
} from "./GoodsCheckPanel";

const apiRequestMock = vi.hoisted(() => vi.fn());

vi.mock("../api/client", () => ({ apiRequest: apiRequestMock }));

describe("GoodsCheckPanel", () => {
  beforeEach(() => {
    apiRequestMock.mockReset();
  });

  it("uses encoded import, scan and close backend paths", () => {
    expect(goodsCheckDocumentPath("doc/1")).toBe("/goods-checks/documents/doc%2F1/import");
    expect(goodsCheckScanPath("check/1")).toBe("/goods-checks/check%2F1/scan");
    expect(goodsCheckClosePath("check/1")).toBe("/goods-checks/check%2F1/close");
  });

  it("keeps search, document summary and import action in a separated header", () => {
    const html = renderToStaticMarkup(
      <GoodsCheckPanel locale="es" t={(key) => key} />
    );

    expect(html).toContain("goods-check-documents-header");
    expect(html).toContain("goods-check-search");
    expect(html).toContain("goods-check-documents-summary");
    expect(html).toContain("goods-check-document-count");
    expect(html).toContain("goodsCheck.availableDocuments");
    expect(html).toContain("goodsCheck.documents");
    expect(html).toContain("goods-check-type-filter");
    expect(html).toContain("goodsCheck.filter.deliveryNotes");
    expect(html).toContain("goodsCheck.filter.invoices");
    expect(html).not.toContain("sr-only");
  });

  it("filters purchase documents independently by delivery note and invoice", () => {
    const documents = [{
      id: "delivery-1",
      tipo: "ALBARAN_COMPRA" as const,
      estado: "CONFIRMADO",
      numero: "AC-1",
      fecha: "2026-07-30",
      proveedorNombre: "Proveedor Norte"
    }, {
      id: "invoice-1",
      tipo: "FACTURA_COMPRA" as const,
      estado: "CONFIRMADO",
      numero: "FC-1",
      fecha: "2026-07-30",
      proveedorNombre: "Proveedor Sur"
    }];

    expect(filterGoodsCheckDocuments(documents, "", "all")).toHaveLength(2);
    expect(filterGoodsCheckDocuments(documents, "", "deliveryNotes").map((item) => item.id))
      .toEqual(["delivery-1"]);
    expect(filterGoodsCheckDocuments(documents, "", "invoices").map((item) => item.id))
      .toEqual(["invoice-1"]);
    expect(filterGoodsCheckDocuments(documents, "sur", "all").map((item) => item.id))
      .toEqual(["invoice-1"]);
  });

  it("only accepts confirmed numbered purchase invoices and delivery notes", () => {
    const available = {
      id: "document-1",
      tipo: "FACTURA_COMPRA" as const,
      estado: "CONFIRMADO",
      numero: "FC-1",
      fecha: "2026-07-16"
    };

    expect(goodsCheckDocumentIsAvailable(available)).toBe(true);
    expect(goodsCheckDocumentIsAvailable({ ...available, tipo: "ALBARAN_COMPRA" })).toBe(true);
    expect(goodsCheckDocumentIsAvailable({ ...available, tipo: "RECTIFICATIVA_COMPRA" })).toBe(false);
    expect(goodsCheckDocumentIsAvailable({ ...available, estado: "BORRADOR" })).toBe(false);
    expect(goodsCheckDocumentIsAvailable({ ...available, numero: null })).toBe(false);
  });

  it("loads every report page and orders available documents newest first", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/document-reports/invoices?limit=500") {
        return Promise.resolve({
          items: [{
            id: "invoice-old",
            tipo: "FACTURA_COMPRA",
            estado: "CONFIRMADO",
            numero: "FC-1",
            fecha: "2026-07-10"
          }],
          hasMore: true,
          nextCursor: "page/2"
        });
      }
      if (path === "/document-reports/invoices?limit=500&cursor=page%2F2") {
        return Promise.resolve({
          items: [{
            id: "invoice-draft",
            tipo: "FACTURA_COMPRA",
            estado: "BORRADOR",
            numero: null,
            fecha: "2026-07-16"
          }],
          hasMore: false
        });
      }
      if (path === "/document-reports/delivery-notes?limit=500") {
        return Promise.resolve({
          items: [{
            id: "delivery-new",
            tipo: "ALBARAN_COMPRA",
            estado: "CONFIRMADO",
            numero: "AC-2",
            fecha: "2026-07-15"
          }],
          hasMore: false
        });
      }
      throw new Error(`Unexpected API path: ${path}`);
    });

    const documents = await loadGoodsCheckDocuments("warehouse-token");

    expect(documents.map((document) => document.id)).toEqual(["delivery-new", "invoice-old"]);
    expect(apiRequestMock).toHaveBeenCalledTimes(3);
    expect(apiRequestMock).toHaveBeenCalledWith(
      "/document-reports/invoices?limit=500&cursor=page%2F2",
      { token: "warehouse-token" }
    );
  });
});

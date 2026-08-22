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
      documentType: "ALBARAN_ENTRADA" as const,
      status: "CONFIRMADA",
      number: "AE-1",
      date: "2026-07-30",
      supplierId: "supplier-north"
    }, {
      id: "invoice-1",
      documentType: "FACTURA_ENTRADA" as const,
      status: "CONFIRMADA",
      number: "FE-1",
      date: "2026-07-30",
      supplierId: "supplier-south"
    }];

    expect(filterGoodsCheckDocuments(documents, "", "all")).toHaveLength(2);
    expect(filterGoodsCheckDocuments(documents, "", "deliveryNotes").map((item) => item.id))
      .toEqual(["delivery-1"]);
    expect(filterGoodsCheckDocuments(documents, "", "invoices").map((item) => item.id))
      .toEqual(["invoice-1"]);
    expect(filterGoodsCheckDocuments(documents, "south", "all").map((item) => item.id))
      .toEqual(["invoice-1"]);
  });

  it("only accepts confirmed numbered purchase invoices and delivery notes", () => {
    const available = {
      id: "document-1",
      documentType: "FACTURA_ENTRADA" as const,
      status: "CONFIRMADA",
      number: "FE-1",
      date: "2026-07-16"
    };

    expect(goodsCheckDocumentIsAvailable(available)).toBe(true);
    expect(goodsCheckDocumentIsAvailable({ ...available, documentType: "ALBARAN_ENTRADA" })).toBe(true);
    expect(goodsCheckDocumentIsAvailable({ ...available, documentType: undefined })).toBe(false);
    expect(goodsCheckDocumentIsAvailable({ ...available, status: "BORRADOR" })).toBe(false);
    expect(goodsCheckDocumentIsAvailable({ ...available, number: null })).toBe(false);
  });

  it("loads every report page and orders available documents newest first", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path === "/warehouse-inputs?type=FACTURA_ENTRADA&limit=500") {
        return Promise.resolve({
          items: [{
            id: "invoice-old",
            documentType: "FACTURA_ENTRADA",
            status: "CONFIRMADA",
            number: "FE-1",
            date: "2026-07-10"
          }],
          hasMore: true,
          nextCursor: "page/2"
        });
      }
      if (path === "/warehouse-inputs?type=FACTURA_ENTRADA&limit=500&cursor=page%2F2") {
        return Promise.resolve({
          items: [{
            id: "invoice-draft",
            documentType: "FACTURA_ENTRADA",
            status: "BORRADOR",
            number: null,
            date: "2026-07-16"
          }],
          hasMore: false
        });
      }
      if (path === "/warehouse-inputs?type=ALBARAN_ENTRADA&limit=500") {
        return Promise.resolve({
          items: [{
            id: "delivery-new",
            documentType: "ALBARAN_ENTRADA",
            status: "CONFIRMADA",
            number: "AE-2",
            date: "2026-07-15"
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
      "/warehouse-inputs?type=FACTURA_ENTRADA&limit=500&cursor=page%2F2",
      { token: "warehouse-token" }
    );
  });
});

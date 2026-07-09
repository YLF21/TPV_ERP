import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  buildStockInventoryRows,
  buildStockTopSalesFamilyTree,
  createDefaultStockColumnSettings,
  filterStockInventoryRows,
  filterStockTopSalesRows,
  moveStockColumn,
  resizeStockColumn,
  sanitizeStockColumnSettings,
  stockColumnStorageKey,
  stockViewAfterProductCreated,
  stockTopSalesPeriodLabel,
  stockTopSalesPeriodRange,
  stockInventoryStatus,
  stockLoadStatus,
  stockFilterButtonLabelKey,
  loadStockSubfamilies,
  loadStockInventoryRows,
  stockTopSalesPath,
  stockViews,
  stockDetailKeyAction,
  nextStockSelectedIndex,
  stockRowToProductEdit,
  StockScreen
} from "./StockScreen";
import type { TerminalContext, UserSession } from "../types";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

describe("StockScreen", () => {
  it("combines backend products, warehouses and stock into inventory rows", () => {
    const rows = buildStockInventoryRows(
      [
        {
          id: "product-1",
          code: "A001",
          barcode: "8430000000011",
          name: "Articulo completo",
          purchasePrice: "4.20",
          salePrice: "6.50",
          memberPrice: "6.00",
          wholesalePrice: "5.30",
          offerPrice: "5.95",
          productType: "UNIT",
          discountType: "NORMAL",
          familyId: "family-1",
          subfamilyId: "subfamily-1",
          taxId: "tax-1",
          taxesIncluded: true,
          offerActive: true,
          offerFrom: "2026-07-01",
          offerUntil: "2026-07-31"
        }
      ],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 12 }],
      {
        families: [{ id: "family-1", name: "Bebidas" }],
        subfamilies: [{ id: "subfamily-1", familyId: "family-1", name: "Cafe" }],
        taxes: [{ id: "tax-1", percentage: 7 }]
      }
    );

    expect(rows).toEqual([
      expect.objectContaining({
        productId: "product-1",
        code: "A001",
        barcode: "8430000000011",
        name: "Articulo completo",
        purchasePrice: "4.20",
        salePrice: "6.50",
        memberPrice: "6.00",
        wholesalePrice: "5.30",
        offerPrice: "5.95",
        productType: "UNIT",
        discountType: "NORMAL",
        familyId: "family-1",
        familyName: "Bebidas",
        subfamilyId: "subfamily-1",
        subfamilyName: "Cafe",
        taxId: "tax-1",
        taxName: "7%",
        taxesIncluded: "common.yes",
        offerActive: "common.yes",
        offerFrom: "2026-07-01",
        offerUntil: "2026-07-31",
        warehouseName: "GENERAL",
        quantity: 12,
        totalQuantity: 12
      })
    ]);
  });

  it("uses the new stock sections without the old low and empty views", () => {
    expect(stockViews).toEqual([
      "stock.current",
      "stock.topSales",
      "stock.offers",
      "stock.memberPrice",
      "stock.promotions",
      "stock.noDiscount",
      "stock.bulkEdit"
    ]);
    expect(stockViews).not.toContain("stock.low");
    expect(stockViews).not.toContain("stock.empty");
  });

  it("keeps backend products with no stock visible in inventory rows", () => {
    const rows = buildStockInventoryRows(
      [
        {
          id: "product-1",
          code: "A001",
          name: "Articulo nuevo",
          salePrice: "3.95",
          productType: "UNIT",
          discountType: "NORMAL",
          taxesIncluded: true
        }
      ],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      []
    );

    expect(rows).toEqual([
      expect.objectContaining({
        productId: "product-1",
        code: "A001",
        name: "Articulo nuevo",
        warehouseName: "GENERAL",
        quantity: 0
      })
    ]);
  });

  it("keeps products without stock visible when other products have stock", () => {
    const rows = buildStockInventoryRows(
      [
        {
          id: "product-1",
          code: "A001",
          name: "Articulo con stock",
          salePrice: "3.95",
          productType: "UNIT",
          discountType: "NORMAL",
          taxesIncluded: true
        },
        {
          id: "product-2",
          code: "B002",
          name: "Articulo sin stock",
          salePrice: "4.95",
          productType: "UNIT",
          discountType: "NORMAL",
          taxesIncluded: true
        }
      ],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 8 }]
    );

    expect(rows).toEqual([
      expect.objectContaining({ productId: "product-1", code: "A001", quantity: 8 }),
      expect.objectContaining({ productId: "product-2", code: "B002", quantity: 0, warehouseName: "GENERAL" })
    ]);
  });

  it("keeps inventory loading when one subfamily request fails", async () => {
    const subfamilies = await loadStockSubfamilies(
      [
        { id: "family-1", name: "Bebidas" },
        { id: "family-2", name: "Panaderia" }
      ],
      async (familyId) => {
        if (familyId === "family-2") {
          throw new TypeError("Failed to write request");
        }
        return [{ id: "subfamily-1", familyId, name: "Cafe" }];
      }
    );

    expect(subfamilies).toEqual([{ id: "subfamily-1", familyId: "family-1", name: "Cafe" }]);
  });

  it("keeps database products visible when stock snapshots fail to load", async () => {
    const rows = await loadStockInventoryRows({
      loadStock: async () => {
        throw new TypeError("Failed to write request");
      },
      loadProducts: async () => [
        {
          id: "product-1",
          code: "A001",
          name: "Articulo sin stock",
          salePrice: "3.95",
          productType: "UNIT",
          discountType: "NORMAL",
          taxesIncluded: true
        }
      ],
      loadWarehouses: async () => [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      loadFamilies: async () => [],
      loadTaxes: async () => [],
      loadSubfamilies: async () => []
    });

    expect(rows).toEqual([
      expect.objectContaining({ productId: "product-1", code: "A001", quantity: 0, warehouseName: "GENERAL" })
    ]);
  });

  it("shows the stock list after creating a product", () => {
    expect(stockViewAfterProductCreated("stock.topSales")).toBe("stock.current");
    expect(stockViewAfterProductCreated("stock.offers")).toBe("stock.current");
    expect(stockViewAfterProductCreated("stock.bulkEdit")).toBe("stock.current");
  });

  it("filters stock rows by selected stock section and search text", () => {
    const rows = [
      {
        productId: "product-1",
        warehouseId: "warehouse-1",
        code: "A001",
        barcode: "8430000000011",
        name: "Articulo completo",
        purchasePrice: "4.20",
        salePrice: "6.50",
        memberPrice: "6.00",
        wholesalePrice: "5.30",
        offerPrice: "5.95",
        productType: "UNIT",
        discountType: "NORMAL",
        familyId: "family-1",
        familyName: "Bebidas",
        subfamilyId: "subfamily-1",
        subfamilyName: "Cafe",
        taxId: "tax-1",
        taxName: "7%",
        taxesIncluded: "common.yes",
        offerActive: "common.no",
        offerFrom: "-",
        offerUntil: "-",
        warehouseName: "GENERAL",
        quantity: 12,
        totalQuantity: 12
      },
      {
        productId: "product-2",
        warehouseId: "warehouse-1",
        code: "B002",
        barcode: "8430000000028",
        name: "Articulo bajo",
        purchasePrice: "2.00",
        salePrice: "3.00",
        memberPrice: "-",
        wholesalePrice: "-",
        offerPrice: "-",
        productType: "UNIT",
        discountType: "NORMAL",
        familyId: "family-2",
        familyName: "Panaderia",
        subfamilyId: "-",
        subfamilyName: "-",
        taxId: "tax-1",
        taxName: "7%",
        taxesIncluded: "common.no",
        offerActive: "common.no",
        offerFrom: "-",
        offerUntil: "-",
        warehouseName: "RESERVA",
        quantity: 3,
        totalQuantity: 3
      },
      {
        productId: "product-3",
        warehouseId: "warehouse-1",
        code: "C003",
        barcode: "-",
        name: "Articulo agotado",
        purchasePrice: "1.00",
        salePrice: "2.00",
        memberPrice: "-",
        wholesalePrice: "-",
        offerPrice: "-",
        productType: "UNIT",
        discountType: "NORMAL",
        familyId: "family-3",
        familyName: "Congelados",
        subfamilyId: "-",
        subfamilyName: "-",
        taxId: "tax-1",
        taxName: "7%",
        taxesIncluded: "common.yes",
        offerActive: "common.no",
        offerFrom: "-",
        offerUntil: "-",
        warehouseName: "GENERAL",
        quantity: 0,
        totalQuantity: 0
      },
      {
        productId: "product-4",
        warehouseId: "warehouse-1",
        code: "D004",
        barcode: "-",
        name: "Articulo oferta",
        purchasePrice: "1.00",
        salePrice: "2.00",
        memberPrice: "-",
        wholesalePrice: "-",
        offerPrice: "1.50",
        productType: "UNIT",
        discountType: "OFFER_DISCOUNT",
        familyId: "family-4",
        familyName: "Ofertas",
        subfamilyId: "-",
        subfamilyName: "-",
        taxId: "tax-1",
        taxName: "7%",
        taxesIncluded: "common.yes",
        offerActive: "common.yes",
        offerFrom: "2026-07-01",
        offerUntil: "-",
        warehouseName: "GENERAL",
        quantity: 6,
        totalQuantity: 6
      }
    ];

    expect(filterStockInventoryRows(rows, "stock.current", "reserva")).toEqual([
      expect.objectContaining({ code: "B002" })
    ]);
    expect(filterStockInventoryRows(rows, "stock.offers", "")).toEqual([
      expect.objectContaining({ code: "D004" })
    ]);
    expect(filterStockInventoryRows(rows, "stock.memberPrice", "")).toEqual([]);
    expect(filterStockInventoryRows(rows, "stock.bulkEdit", "")).toEqual([]);
  });

  it("calculates product total stock across warehouses", () => {
    const rows = buildStockInventoryRows(
      [{ id: "product-1", code: "A001", name: "Cafe", salePrice: "3.95", productType: "UNIT", discountType: "NORMAL" }],
      [
        { id: "warehouse-1", name: "GENERAL", defaultWarehouse: true },
        { id: "warehouse-2", name: "RESERVA" }
      ],
      [
        { productId: "product-1", warehouseId: "warehouse-1", quantity: 8 },
        { productId: "product-1", warehouseId: "warehouse-2", quantity: 5 }
      ]
    );

    expect(rows).toEqual([
      expect.objectContaining({ warehouseName: "GENERAL", quantity: 8, totalQuantity: 13 }),
      expect.objectContaining({ warehouseName: "RESERVA", quantity: 5, totalQuantity: 13 })
    ]);
  });

  it("maps stock list keyboard shortcuts to detail tabs", () => {
    expect(stockDetailKeyAction("F5")).toBe("stock");
    expect(stockDetailKeyAction("F6")).toBe("sales");
    expect(stockDetailKeyAction("F7")).toBe("edit");
    expect(stockDetailKeyAction("Enter")).toBe("stock");
    expect(stockDetailKeyAction("Escape")).toBe("close");
    expect(stockDetailKeyAction("F8")).toBeNull();
  });

  it("builds an edit product form from the selected stock row", () => {
    expect(stockRowToProductEdit({
      productId: "product-1",
      warehouseId: "warehouse-1",
      code: "A001",
      barcode: "8430000000011",
      name: "Cafe molido",
      description: "Tueste natural",
      comments: "Preferente",
      purchasePrice: "4.20",
      salePrice: "6.50",
      memberPrice: "6.00",
      wholesalePrice: "5.30",
      offerPrice: "5.95",
      offerDiscountPercent: "10",
      productType: "UNIT",
      discountType: "OFFER_DISCOUNT",
      familyId: "family-1",
      familyName: "Bebidas",
      subfamilyId: "subfamily-1",
      subfamilyName: "Cafe",
      taxId: "tax-1",
      taxName: "7%",
      taxesIncluded: "common.yes",
      offerActive: "common.yes",
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31",
      warehouseName: "GENERAL",
      quantity: 12,
      totalQuantity: 18
    })).toEqual(expect.objectContaining({
      id: "product-1",
      form: expect.objectContaining({
        code: "A001",
        name: "Cafe molido",
        priceUseMode: "OFFER_DISCOUNT",
        discountType: "DISCOUNT_PRICE",
        offerDiscountPercent: "10"
      })
    }));
  });

  it("moves the selected stock row with arrow keys", () => {
    expect(nextStockSelectedIndex(0, 3, "ArrowDown")).toBe(1);
    expect(nextStockSelectedIndex(2, 3, "ArrowDown")).toBe(2);
    expect(nextStockSelectedIndex(2, 3, "ArrowUp")).toBe(1);
    expect(nextStockSelectedIndex(0, 3, "ArrowUp")).toBe(0);
    expect(nextStockSelectedIndex(0, 0, "ArrowDown")).toBe(-1);
  });

  it("filters inventory rows by product attributes selected in the inventory dialog", () => {
    const rows = [
      {
        productId: "product-1",
        warehouseId: "warehouse-1",
        code: "A001",
        barcode: "8430000000011",
        name: "Cafe molido",
        purchasePrice: "4.20",
        salePrice: "6.50",
        memberPrice: "6.00",
        wholesalePrice: "5.30",
        offerPrice: "5.95",
        productType: "UNIT",
        discountType: "NORMAL",
        familyId: "family-1",
        familyName: "Bebidas",
        subfamilyId: "subfamily-1",
        subfamilyName: "Cafe",
        taxId: "tax-1",
        taxName: "7%",
        taxesIncluded: "common.yes",
        offerActive: "common.yes",
        offerFrom: "2026-07-01",
        offerUntil: "2026-07-31",
        warehouseName: "GENERAL",
        quantity: 12,
        totalQuantity: 12
      },
      {
        productId: "product-2",
        warehouseId: "warehouse-2",
        code: "B002",
        barcode: "-",
        name: "Pan integral",
        purchasePrice: "2.00",
        salePrice: "3.00",
        memberPrice: "-",
        wholesalePrice: "-",
        offerPrice: "-",
        productType: "WEIGHT",
        discountType: "MEMBER_PRICE",
        familyId: "family-2",
        familyName: "Panaderia",
        subfamilyId: "-",
        subfamilyName: "-",
        taxId: "tax-2",
        taxName: "3%",
        taxesIncluded: "common.no",
        offerActive: "common.no",
        offerFrom: "-",
        offerUntil: "-",
        warehouseName: "RESERVA",
        quantity: 3,
        totalQuantity: 3
      }
    ];

    expect(filterStockInventoryRows(rows, "stock.current", "", {
      type: "UNIT",
      discount: "NORMAL",
      family: "family-1",
      tax: "tax-1",
      offerActive: "yes",
      warehouse: "warehouse-1"
    })).toEqual([expect.objectContaining({ code: "A001" })]);

    expect(filterStockInventoryRows(rows, "stock.current", "", {
      type: "UNIT",
      discount: "NORMAL",
      family: "family-2",
      tax: "",
      offerActive: "",
      warehouse: ""
    })).toEqual([]);
  });

  it("derives a row status from quantity", () => {
    expect(stockInventoryStatus(12)).toBe("stock.status.ok");
    expect(stockInventoryStatus(3)).toBe("stock.status.low");
    expect(stockInventoryStatus(0)).toBe("stock.status.empty");
    expect(stockInventoryStatus(-1)).toBe("stock.status.empty");
  });

  it("does not expose low-level network write errors in inventory status", () => {
    expect(stockLoadStatus(new TypeError("Failed to write request"), "stock.status.noData")).toBe("stock.status.noData");
    expect(stockLoadStatus(new Error("Failed to write request"), "stock.status.noData")).toBe("stock.status.noData");
    expect(stockLoadStatus(new Error("METHOD_NOT_ALLOWED"), "stock.status.noData")).toBe("METHOD_NOT_ALLOWED");
  });

  it("filters top sales rows locally by family, subfamily, supplier and search text", () => {
    const rows = [
      {
        productId: "product-1",
        code: "A001",
        barcode: "8430000000011",
        name: "Cafe molido",
        familyId: "family-1",
        familyName: "Bebidas",
        subfamilyId: "subfamily-1",
        subfamilyName: "Cafe",
        suppliers: [{ supplierId: "supplier-1", supplierCode: "PR0001", supplierName: "Proveedor General" }],
        soldQuantity: 82,
        netAmount: 246,
        currentStock: 14,
        warehouseId: "warehouse-1",
        warehouseName: "GENERAL"
      },
      {
        productId: "product-2",
        code: "B002",
        barcode: "8430000000028",
        name: "Pan integral",
        familyId: "family-2",
        familyName: "Panaderia",
        subfamilyId: "subfamily-2",
        subfamilyName: "Pan",
        suppliers: [{ supplierId: "supplier-2", supplierCode: "PR0002", supplierName: "Horno Norte" }],
        soldQuantity: 65,
        netAmount: 130,
        currentStock: 9,
        warehouseId: "warehouse-1",
        warehouseName: "GENERAL"
      }
    ];

    expect(filterStockTopSalesRows(rows, {
      family: "bebidas",
      subfamily: "cafe",
      supplier: "general",
      search: "8430000000011"
    })).toEqual([expect.objectContaining({ code: "A001" })]);
    expect(filterStockTopSalesRows(rows, {
      family: "panaderia",
      subfamily: "",
      supplier: "horno",
      search: ""
    })).toEqual([expect.objectContaining({ code: "B002" })]);
  });

  it("builds the top sales API path from period and date only", () => {
    expect(stockTopSalesPath("week", "2026-07-08")).toBe("/stock/top-sales?period=week&date=2026-07-08");
  });

  it("builds the top sales API path from custom date range", () => {
    expect(stockTopSalesPath("custom", "2026-02-01", "2026-02-28")).toBe("/stock/top-sales?dateFrom=2026-02-01&dateTo=2026-02-28");
  });

  it("builds top sales quick ranges backwards from the current date", () => {
    const currentDate = new Date(2026, 6, 8);

    expect(stockTopSalesPeriodRange("day", currentDate)).toEqual({ dateFrom: "2026-07-08", dateTo: "2026-07-08" });
    expect(stockTopSalesPeriodRange("week", currentDate)).toEqual({ dateFrom: "2026-07-02", dateTo: "2026-07-08" });
    expect(stockTopSalesPeriodRange("month", currentDate)).toEqual({ dateFrom: "2026-06-09", dateTo: "2026-07-08" });
    expect(stockTopSalesPeriodRange("year", currentDate)).toEqual({ dateFrom: "2025-07-09", dateTo: "2026-07-08" });
  });

  it("builds a per-user stock column storage key", () => {
    expect(stockColumnStorageKey("venta", "admin")).toBe("tpv.stock.columns.venta.admin");
  });

  it("sanitizes saved column order and widths per stock view", () => {
    const sanitized = sanitizeStockColumnSettings({
      "stock.topSales": [
        { key: "name", width: 480 },
        { key: "missing", width: 10 },
        { key: "code", width: 40 },
        { key: "name", width: 260 }
      ]
    });

    expect(sanitized["stock.topSales"].slice(0, 2)).toEqual([
      { key: "name", width: 420 },
      { key: "code", width: 72 }
    ]);
    expect(sanitized["stock.current"].map((column) => column.key)).toEqual(createDefaultStockColumnSettings()["stock.current"].map((column) => column.key));
  });

  it("moves and resizes stock columns without mutating other views", () => {
    const settings = createDefaultStockColumnSettings();
    const moved = moveStockColumn(settings, "stock.topSales", "name", -1);
    const resized = resizeStockColumn(settings, "stock.topSales", "name", 320);

    expect(moved["stock.topSales"].map((column) => column.key).indexOf("name")).toBe(
      settings["stock.topSales"].map((column) => column.key).indexOf("name") - 1
    );
    expect(moved["stock.current"]).toEqual(settings["stock.current"]);
    expect(resized["stock.topSales"].find((column) => column.key === "name")?.width).toBe(320);
  });

  it("uses Spanish labels for top sales periods", () => {
    expect(stockTopSalesPeriodLabel("week")).toBe("stock.period.week");
    expect(stockTopSalesPeriodLabel("year")).toBe("stock.period.year");
  });

  it("builds a family tree for the top sales picker", () => {
    const tree = buildStockTopSalesFamilyTree([
      {
        productId: "product-1",
        code: "A001",
        barcode: "8430000000011",
        name: "Cafe molido",
        familyId: "family-1",
        familyName: "Bebidas",
        subfamilyId: "subfamily-1",
        subfamilyName: "Cafe",
        suppliers: [],
        soldQuantity: 82,
        netAmount: 246,
        currentStock: 14,
        warehouseId: "warehouse-1",
        warehouseName: "GENERAL"
      },
      {
        productId: "product-2",
        code: "A002",
        barcode: "8430000000028",
        name: "Te verde",
        familyId: "family-1",
        familyName: "Bebidas",
        subfamilyId: "subfamily-2",
        subfamilyName: "Infusiones",
        suppliers: [],
        soldQuantity: 30,
        netAmount: 90,
        currentStock: 7,
        warehouseId: "warehouse-1",
        warehouseName: "GENERAL"
      }
    ]);

    expect(tree).toEqual([
      expect.objectContaining({
        name: "Bebidas",
        subfamilies: [
          expect.objectContaining({ name: "Cafe" }),
          expect.objectContaining({ name: "Infusiones" })
        ]
      })
    ]);
  });

  it("renders the stock workspace with shared frame controls", () => {
    const html = renderToStaticMarkup(
      <StockScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(html).toContain('class="stock-screen work-screen"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("STOCK");
    expect(html).toContain("Top ventas");
    expect(html).toContain("Añadir producto");
    expect(html).toContain("Filtrar");
    expect(html).toContain("Stock local");
    expect(html).toContain("Stock total");
    expect(html).toContain("Stock");
    expect(html).toContain("Productos con oferta");
    expect(html).toContain("Productos con precio socio");
    expect(html).toContain("Productos con promocion");
    expect(html).toContain("Productos prohibidos a descuento");
    expect(html).toContain("Edicion masiva de productos");
    expect(html).toContain("Configuracion stock");
    expect(html).toContain("Codigo");
    expect(html).toContain("Codigo barra");
    expect(html).toContain("Nombre");
    expect(html).toContain("Familia");
    expect(html).toContain("Subfamilia");
    expect(html).toContain("Almacen");
    expect(html).toContain("Sin datos de stock");
    expect(html).not.toContain("Movimientos");
    expect(html).not.toContain("Entrada stock");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Aceite oliva");
  });

  it("uses an inventory filter dialog label outside top sales", () => {
    expect(stockFilterButtonLabelKey("stock.topSales")).toBe("stock.filter.title");
    expect(stockFilterButtonLabelKey("stock.current")).toBe("stock.filter.inventoryTitle");
    expect(stockFilterButtonLabelKey("stock.offers")).toBe("stock.filter.inventoryTitle");
    expect(stockFilterButtonLabelKey("stock.bulkEdit")).toBe("stock.filter.inventoryTitle");
  });
});

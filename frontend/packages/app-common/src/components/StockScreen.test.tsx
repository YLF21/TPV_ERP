import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  buildStockInventoryRows,
  buildStockTopSalesFamilyTree,
  createDefaultStockColumnSettings,
  filterStockInventoryRows,
  filterStockTopSalesRows,
  moveStockColumn,
  reorderStockColumn,
  resizeStockColumn,
  sanitizeStockColumnSettings,
  stockColumnGridTemplate,
  stockColumnStorageKey,
  stockTableShouldAutoFocus,
  toggleStockColumnVisibility,
  visibleStockColumns,
  stockViewAfterProductCreated,
  stockTopSalesPeriodLabel,
  stockTopSalesPeriodRange,
  stockInventoryStatus,
  stockInventoryStatusClass,
  stockLoadStatus,
  stockFilterButtonLabelKey,
  loadStockSubfamilies,
  loadStockInventoryRows,
  stockTopSalesPath,
  stockViews,
  stockDetailKeyAction,
  nextStockSelectedIndex,
  normalizeStockBulkContent,
  hydrateStockBulkProductActivation,
  backendDiscountTypeForPriceUse,
  stockRowToProductEdit,
  stockBulkProductRowIds,
  selectedStockBulkProductIds,
  setAllStockBulkRowsSelected,
  stockBulkEditExactProduct,
  stockBulkShortcutAction,
  stockBulkFileMenuItems,
  stockBulkImportMenuItems,
  stockBulkSelectedActionsByTab,
  stockBulkQuickEditFieldsByTab,
  stockBenefitPercent,
  stockPriceFromBenefit,
  stockPriceBelowCost,
  selectStockInventoryRows,
  userCanManageStockProducts,
  userCanManageWarehouses,
  userCanReadStock,
  visibleStockViewsForSession,
  stockViewIsSelected,
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
          version: 4,
          imageId: "image-1",
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
        version: 4,
        imageId: "image-1",
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

  it("resolves a barcode in the code cell and keeps both real identifiers", () => {
    const rows = buildStockInventoryRows(
      [{
        id: "product-1",
        code: "A001",
        barcode: "8430000000011",
        name: "Agua",
        taxesIncluded: true
      }],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      []
    );

    expect(stockBulkEditExactProduct(rows, "8430000000011")).toEqual(expect.objectContaining({
      code: "A001",
      barcode: "8430000000011"
    }));
  });

  it("calculates benefit over sale price and calculates the inverse price", () => {
    expect(stockBenefitPercent(50, 100)).toBe(50);
    expect(stockBenefitPercent(75, 100)).toBe(25);
    expect(stockBenefitPercent(100, 0)).toBe(0);
    expect(stockPriceFromBenefit(50, 50)).toBe(100);
    expect(stockPriceFromBenefit(75, 25)).toBe(100);
    expect(stockPriceFromBenefit(50, 100)).toBeNull();
    expect(stockPriceBelowCost(0, 50)).toBe(true);
    expect(stockPriceBelowCost(50, 50)).toBe(false);
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

  it("does not keep stock selected while a party directory is open", () => {
    expect(stockViewIsSelected("stock.current", "stock.current", "customers")).toBe(false);
    expect(stockViewIsSelected("stock.current", "stock.current", null)).toBe(true);
  });

  it("groups every import action inside the Archivo import submenu", () => {
    expect(stockBulkFileMenuItems).toContain("stock.bulkEdit.import");
    expect(stockBulkFileMenuItems).not.toContain("stock.bulkEdit.importExcel");
    expect(stockBulkImportMenuItems).toEqual([
      "stock.bulkEdit.importExcel",
      "stock.bulkEdit.importSupplier",
      "stock.bulkEdit.importPurchaseInvoice",
      "stock.bulkEdit.importPurchaseDeliveryNote",
      "stock.bulkEdit.importFamilies"
    ]);
  });

  it("maps the bulk editor keyboard shortcuts without stealing text editing", () => {
    expect(stockBulkShortcutAction("s", { ctrlKey: true })).toBe("save");
    expect(stockBulkShortcutAction("z", { ctrlKey: true })).toBe("undo");
    expect(stockBulkShortcutAction("a", { ctrlKey: true })).toBe("open");
    expect(stockBulkShortcutAction("a", { ctrlKey: true, editingText: true })).toBe("open");
    expect(stockBulkShortcutAction("z", { ctrlKey: true, editingText: true })).toBe("undo");
    expect(stockBulkShortcutAction("s", { ctrlKey: true, altKey: true })).toBeNull();
  });

  it("shows only the relevant bulk actions for each edit tab", () => {
    expect(stockBulkSelectedActionsByTab.main).toEqual([
      "purchasePrice",
      "salePrice",
      "memberPrice",
      "wholesalePrice",
      "offerPrice",
      "offerDiscountPercent",
      "priceUse",
      "offerActive",
      "offerDates",
      "tax",
      "taxesIncluded",
      "productActive",
      "supplier",
      "principalSupplier",
      "family"
    ]);
    expect(stockBulkSelectedActionsByTab.info).toEqual([
      "tax",
      "taxesIncluded",
      "productActive",
      "supplier",
      "principalSupplier",
      "family"
    ]);
    expect(stockBulkSelectedActionsByTab.offer).toContain("offerActive");
    expect(stockBulkSelectedActionsByTab.salePrice).toContain("benefit");
    expect(stockBulkSelectedActionsByTab.memberPrice).toContain("benefit");
    expect(stockBulkSelectedActionsByTab.wholesalePrice).toContain("benefit");
    expect(stockBulkSelectedActionsByTab.offer).toContain("benefit");
    expect(stockBulkSelectedActionsByTab.salePrice).not.toContain("offerPrice");
    expect(stockBulkSelectedActionsByTab.image).toEqual(["productActive", "supplier", "principalSupplier"]);
  });

  it("offers quick editing only for editable fields visible in each tab", () => {
    expect(stockBulkQuickEditFieldsByTab.main).toEqual([
      "name",
      "description",
      "purchasePrice",
      "salePrice",
      "memberPrice",
      "wholesalePrice",
      "offerPrice",
      "offerDiscountPercent"
    ]);
    expect(stockBulkQuickEditFieldsByTab.salePrice).toEqual(["purchasePrice", "salePrice", "benefit"]);
    expect(stockBulkQuickEditFieldsByTab.memberPrice).toEqual(["purchasePrice", "memberPrice", "benefit"]);
    expect(stockBulkQuickEditFieldsByTab.wholesalePrice).toEqual(["purchasePrice", "wholesalePrice", "benefit"]);
    expect(stockBulkQuickEditFieldsByTab.offer).toEqual(["purchasePrice", "offerPrice", "benefit", "offerDiscountPercent"]);
    expect(stockBulkQuickEditFieldsByTab.info).toEqual(["name", "description"]);
    expect(stockBulkQuickEditFieldsByTab.image).toEqual([]);
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

  it("keeps optional product fields empty in inventory rows when the backend has blanks", () => {
    const rows = buildStockInventoryRows(
      [
        {
          id: "product-1",
          code: "A001",
          barcode: null,
          barcode2: "",
          name: "Articulo nuevo",
          description: null,
          comments: "",
          purchasePrice: "1.00",
          salePrice: "3.95",
          memberPrice: null,
          wholesalePrice: "",
          offerPrice: null,
          offerDiscountPercent: "",
          productType: "UNIT",
          discountType: "NORMAL",
          familyId: null,
          subfamilyId: "",
          taxId: null,
          taxesIncluded: true,
          offerActive: false,
          offerFrom: null,
          offerUntil: ""
        }
      ],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      []
    );

    expect(rows).toEqual([
      expect.objectContaining({
        barcode: "",
        barcode2: "",
        description: "",
        comments: "",
        memberPrice: "",
        wholesalePrice: "",
        offerPrice: "",
        offerDiscountPercent: "",
        familyId: "",
        subfamilyId: "",
        taxId: "",
        offerFrom: "",
        offerUntil: ""
      })
    ]);
  });

  it("uses default family and tax ids for edit rows when a product is missing them", () => {
    const [row] = buildStockInventoryRows(
      [
        {
          id: "product-1",
          code: "0",
          barcode: null,
          name: "ARTICULO",
          purchasePrice: "0",
          salePrice: "5",
          priceUseMode: "OFFER_PRICE",
          discountType: "DISCOUNT_PRICE",
          offerPrice: "3",
          offerFrom: "2026-07-01",
          offerUntil: "2026-07-31"
        }
      ],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      [],
      {
        families: [{ id: "family-general", name: "GENERAL" }],
        taxes: [{ id: "tax-21", percentage: 21 }]
      }
    );

    expect(row).toEqual(expect.objectContaining({
      familyId: "family-general",
      familyName: "GENERAL",
      taxId: "tax-21",
      taxName: "21%"
    }));
    expect(stockRowToProductEdit(row).form).toEqual(expect.objectContaining({
      familyId: "family-general",
      taxId: "tax-21",
      code: "0",
      barcode: ""
    }));
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

  it("shows real promotion targets and the backend NONE discount lock", () => {
    const rows = buildStockInventoryRows(
      [{
        id: "product-1",
        code: "A001",
        name: "Cafe",
        familyId: "family-1",
        priceUseMode: "NORMAL",
        discountType: "NONE"
      }],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      [],
      {
        promotions: [{
          id: "promotion-1",
          name: "Cafe 2x1",
          type: "BUY_X_PAY_Y",
          status: "ACTIVE",
          startDate: "2026-07-01",
          endDate: "2026-07-31",
          scope: "FAMILY",
          customerSegment: null,
          memberCategoryId: null,
          minimumAmount: null,
          minimumQuantity: null,
          buyQuantity: "2",
          payQuantity: "1",
          buyXPayYMode: "SAME_PRODUCT",
          discountAmount: null,
          discountPercent: null,
          maximumDiscount: null,
          packPrice: null,
          targets: [{ type: "FAMILY", targetId: "family-1" }]
        }]
      }
    );

    expect(filterStockInventoryRows(rows, "stock.promotions", "")).toEqual([
      expect.objectContaining({
        promotionNames: "Cafe 2x1",
        promotionTypes: "BUY_X_PAY_Y",
        promotionStatuses: "ACTIVE"
      })
    ]);
    expect(filterStockInventoryRows(rows, "stock.noDiscount", "")).toEqual([
      expect.objectContaining({ productId: "product-1", backendDiscountType: "NONE" })
    ]);
  });

  it("shows one product row for the local warehouse and supports TOTAL", () => {
    const products = [
      { id: "product-1", code: "A001", name: "Cafe", salePrice: "3.95", productType: "UNIT", discountType: "NORMAL" },
      { id: "product-2", code: "A002", name: "Te", salePrice: "2.95", productType: "UNIT", discountType: "NORMAL" }
    ];
    const warehouses = [
      { id: "warehouse-1", name: "GENERAL", defaultWarehouse: true },
      { id: "warehouse-2", name: "RESERVA" },
      { id: "warehouse-3", name: "INACTIVO", active: false }
    ];
    const stock = [
      { productId: "product-1", warehouseId: "warehouse-1", quantity: 8 },
      { productId: "product-1", warehouseId: "warehouse-2", quantity: 5 }
    ];
    const localRows = buildStockInventoryRows(products, warehouses, stock);
    const allRows = buildStockInventoryRows(
      products,
      warehouses,
      stock,
      {},
      { mode: "all" }
    );

    expect(localRows).toEqual([
      expect.objectContaining({ productId: "product-1", warehouseName: "GENERAL", quantity: 8, totalQuantity: 13 }),
      expect.objectContaining({ productId: "product-2", warehouseName: "GENERAL", quantity: 0, totalQuantity: 0 })
    ]);
    expect(allRows).toHaveLength(4);
    expect(allRows).toEqual(expect.arrayContaining([
      expect.objectContaining({ productId: "product-1", warehouseName: "RESERVA", quantity: 5 }),
      expect.objectContaining({ productId: "product-2", warehouseName: "RESERVA", quantity: 0 })
    ]));
    expect(allRows.some((row) => row.warehouseName === "INACTIVO")).toBe(false);

    expect(selectStockInventoryRows(allRows, "TOTAL")).toEqual([
      expect.objectContaining({ productId: "product-1", warehouseId: "TOTAL", quantity: 13, totalQuantity: 13 }),
      expect.objectContaining({ productId: "product-2", warehouseId: "TOTAL", quantity: 0, totalQuantity: 0 })
    ]);
  });

  it("selects a requested warehouse with one row per product including zero", () => {
    const rows = buildStockInventoryRows(
      [
        { id: "product-1", code: "A001", name: "Cafe" },
        { id: "product-2", code: "A002", name: "Te" }
      ],
      [
        { id: "warehouse-1", name: "GENERAL", defaultWarehouse: true },
        { id: "warehouse-2", name: "RESERVA" }
      ],
      [{ productId: "product-1", warehouseId: "warehouse-2", quantity: 5 }],
      {},
      { warehouseId: "warehouse-2" }
    );

    expect(rows).toEqual([
      expect.objectContaining({ productId: "product-1", warehouseName: "RESERVA", quantity: 5 }),
      expect.objectContaining({ productId: "product-2", warehouseName: "RESERVA", quantity: 0 })
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

  it("only enables product management actions such as F7 for product managers or admins", () => {
    expect(userCanManageStockProducts({ permissions: ["ADMIN"] })).toBe(true);
    expect(userCanManageStockProducts({ permissions: ["GESTION_PRODUCTO"] })).toBe(true);
    expect(userCanManageStockProducts({ permissions: ["VENTA"] })).toBe(false);
  });

  it("separates product management, sales stock reading and warehouse configuration", () => {
    expect(userCanReadStock({ permissions: ["STOCK_READ"] })).toBe(true);
    expect(userCanReadStock({ permissions: ["GESTION_VENTAS"] })).toBe(true);
    expect(userCanReadStock({ permissions: ["GESTION_PRODUCTO"] })).toBe(true);
    expect(userCanReadStock({ permissions: ["GESTION_ALMACEN"] })).toBe(true);
    expect(userCanReadStock({ permissions: ["VENTA"] })).toBe(false);
    expect(userCanManageWarehouses({ permissions: ["WAREHOUSES_MANAGE"] })).toBe(true);
    expect(userCanManageWarehouses({ permissions: ["GESTION_ALMACEN"] })).toBe(true);
    expect(userCanManageWarehouses({ permissions: ["GESTION_PRODUCTO"] })).toBe(false);
    expect(userCanReadStock({ permissions: ["ADMIN"] })).toBe(true);
  });

  it("builds an edit product form from the selected stock row", () => {
    expect(stockRowToProductEdit({
      productId: "product-1",
      imageId: "image-1",
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
      imageId: "image-1",
      form: expect.objectContaining({
        code: "A001",
        name: "Cafe molido",
        priceUseMode: "OFFER_DISCOUNT",
        discountType: "DISCOUNT_PRICE",
        offerDiscountPercent: "10"
      })
    }));
  });

  it("preserves the no-discount lock and synchronizes bulk price-use contracts", () => {
    const [row] = buildStockInventoryRows(
      [{
        id: "product-1",
        code: "A001",
        name: "Cafe",
        familyId: "family-1",
        taxId: "tax-1",
        purchasePrice: "1.00",
        salePrice: "2.00",
        priceUseMode: "NORMAL",
        discountType: "NONE"
      }],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      []
    );

    expect(stockRowToProductEdit({ ...row, purchaseDiscountPercent: "12.50" })).toEqual(expect.objectContaining({
      initialData: {
        discountType: "NONE",
        purchaseDiscountPercent: "12.50",
        packageQuantity: "1",
        stockMin: null,
        stockMax: null
      },
      form: expect.objectContaining({ discountType: "NONE" })
    }));
    expect(backendDiscountTypeForPriceUse("NORMAL", "NONE")).toBe("NONE");
    expect(backendDiscountTypeForPriceUse("NORMAL", "DISCOUNT_PRICE")).toBe("NORMAL");
    expect(backendDiscountTypeForPriceUse("MEMBER_PRICE", "NONE")).toBe("MEMBER_PRICE");
    expect(backendDiscountTypeForPriceUse("OFFER_PRICE", "NONE")).toBe("DISCOUNT_PRICE");

    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product: {
        ...row,
        packageQuantity: "6",
        supplierName: "Proveedor visual",
        promotionNames: "Promocion visual"
      },
      draft: { discountType: "OFFER_PRICE" }
    }, {
      id: "row-empty",
      selected: false,
      query: "",
      draft: {}
    }];
    const [normalized] = normalizeStockBulkContent(rows);
    expect(normalized.draft.backendDiscountType).toBe("DISCOUNT_PRICE");
    expect(normalized.product).toEqual(expect.objectContaining({ packageQuantity: "6" }));
    expect(normalized.product).toEqual(expect.objectContaining({ quantity: "0", totalQuantity: "0" }));
    expect(normalized.product).not.toHaveProperty("supplierName");
    expect(normalized.product).not.toHaveProperty("promotionNames");
    expect(stockBulkProductRowIds(rows)).toEqual(["row-1"]);
    expect(selectedStockBulkProductIds(rows)).toEqual([]);
    expect(selectedStockBulkProductIds(setAllStockBulkRowsSelected(rows, true))).toEqual([row.productId]);
    expect(setAllStockBulkRowsSelected(rows, true).map((value) => value.selected)).toEqual([true, false]);
  });

  it("hydrates activation in legacy bulk drafts from the current product", () => {
    const [liveProduct] = buildStockInventoryRows(
      [{ id: "product-legacy", active: false, name: "Producto desactivado" }],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true }],
      []
    );
    const legacyProduct = { ...liveProduct, active: undefined };
    const [hydrated] = hydrateStockBulkProductActivation([{
      id: "legacy-row",
      selected: false,
      query: "",
      product: legacyProduct,
      draft: { ...legacyProduct, active: undefined }
    }], [liveProduct]);

    expect(hydrated.product?.active).toBe("common.no");
    expect(hydrated.draft.active).toBe("common.no");
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
    expect(stockInventoryStatus(12, false)).toBe("stock.status.inactive");
  });

  it("maps inactive backend products to an inactive stock row", () => {
    const [row] = buildStockInventoryRows(
      [{ id: "product-inactive", active: false, name: "Producto desactivado" }],
      [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }],
      []
    );

    expect(row.active).toBe("common.no");
    expect(stockInventoryStatus(row.quantity, row.active !== "common.no")).toBe("stock.status.inactive");
  });

  it("derives the visual stock status class from quantity", () => {
    expect(stockInventoryStatusClass(12)).toBe("stock-status-correcto");
    expect(stockInventoryStatusClass(3)).toBe("stock-status-bajo");
    expect(stockInventoryStatusClass(0)).toBe("stock-status-critico");
    expect(stockInventoryStatusClass(-1)).toBe("stock-status-critico");
    expect(stockInventoryStatusClass(12, false)).toBe("stock-status-desactivado");
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
    expect(filterStockTopSalesRows(rows, {
      family: "",
      subfamily: "",
      supplier: "",
      search: "",
      warehouse: "warehouse-2"
    })).toEqual([]);
  });

  it("builds the top sales API path from period and date only", () => {
    expect(stockTopSalesPath("week", "2026-07-08")).toBe("/stock/top-sales?period=week&date=2026-07-08");
    expect(stockTopSalesPath("week", "2026-07-08", "2026-07-08", "warehouse-2"))
      .toBe("/stock/top-sales?period=week&date=2026-07-08&warehouseId=warehouse-2");
  });

  it("builds the top sales API path from custom date range", () => {
    expect(stockTopSalesPath("custom", "2026-02-01", "2026-02-28")).toBe("/stock/top-sales?dateFrom=2026-02-01&dateTo=2026-02-28");
    expect(stockTopSalesPath("custom", "2026-02-01", "2026-02-28", "warehouse-1"))
      .toBe("/stock/top-sales?dateFrom=2026-02-01&dateTo=2026-02-28&warehouseId=warehouse-1");
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
      { key: "name", width: 420, visible: true },
      { key: "code", width: 72, visible: true }
    ]);
    expect(sanitized["stock.current"].map((column) => column.key)).toEqual(createDefaultStockColumnSettings()["stock.current"].map((column) => column.key));
  });

  it("moves resizes reorders and toggles stock columns without mutating other views", () => {
    const settings = createDefaultStockColumnSettings();
    const moved = moveStockColumn(settings, "stock.topSales", "name", -1);
    const resized = resizeStockColumn(settings, "stock.topSales", "name", 320);
    const reordered = reorderStockColumn(settings, "stock.topSales", "amount", "code");
    const hidden = toggleStockColumnVisibility(settings, "stock.topSales", "name");

    expect(moved["stock.topSales"].map((column) => column.key).indexOf("name")).toBe(
      settings["stock.topSales"].map((column) => column.key).indexOf("name") - 1
    );
    expect(reordered["stock.topSales"].map((column) => column.key).slice(1, 3)).toEqual(["amount", "code"]);
    expect(moved["stock.current"]).toEqual(settings["stock.current"]);
    expect(resized["stock.topSales"].find((column) => column.key === "name")?.width).toBe(320);
    expect(hidden["stock.topSales"].find((column) => column.key === "name")?.visible).toBe(false);
  });

  it("builds one stock grid template from visible selected column settings", () => {
    let settings = resizeStockColumn(createDefaultStockColumnSettings(), "stock.topSales", "name", 320);
    settings = toggleStockColumnVisibility(settings, "stock.topSales", "code");
    const columns = settings["stock.topSales"];
    const template = stockColumnGridTemplate(columns);

    expect(visibleStockColumns(columns).map((column) => column.key)).not.toContain("code");
    expect(template.split(" ")).toHaveLength(visibleStockColumns(columns).length);
    expect(template).toContain("320px");
    expect(template).toBe(visibleStockColumns(columns).map((column) => `${column.width}px`).join(" "));
  });

  it("keeps at least one stock column visible", () => {
    let settings = createDefaultStockColumnSettings();
    for (const column of settings["stock.topSales"].slice(1)) {
      settings = toggleStockColumnVisibility(settings, "stock.topSales", column.key);
    }
    const before = settings["stock.topSales"].filter((column) => column.visible !== false).length;
    const after = toggleStockColumnVisibility(settings, "stock.topSales", "ranking");

    expect(before).toBe(1);
    expect(after["stock.topSales"].filter((column) => column.visible !== false).length).toBe(1);
  });

  it("auto-focuses the stock table when opening an inventory section without dialogs", () => {
    expect(stockTableShouldAutoFocus("stock.current", {
      inventoryFilterOpen: false,
      productCreateOpen: false,
      stockColumnsOpen: false,
      topSalesFilterOpen: false
    })).toBe(true);
    expect(stockTableShouldAutoFocus("stock.topSales", {
      inventoryFilterOpen: false,
      productCreateOpen: false,
      stockColumnsOpen: false,
      topSalesFilterOpen: false
    })).toBe(false);
    expect(stockTableShouldAutoFocus("stock.current", {
      inventoryFilterOpen: true,
      productCreateOpen: false,
      stockColumnsOpen: false,
      topSalesFilterOpen: false
    })).toBe(false);
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
    expect(html).toContain("PRODUCTO");
    expect(html).toContain("Top ventas");
    expect(html).toContain("Añadir producto");
    expect(html).toContain("Filtrar");
    expect(html).toContain("Stock local");
    expect(html).toContain("Stock total");
    expect(html).toContain("Stock");
    expect(html).toContain("Productos con oferta");
    expect(html).toContain("Productos con precio socio");
    expect(html).toContain("Productos con promoción");
    expect(html).toContain("Productos prohibidos a descuento");
    expect(html).toContain("Edición masiva de productos");
    expect(html).toContain("Configuración stock");
    expect(html).toContain("Código");
    expect(html).toContain("Código de barras");
    expect(html).toContain("Nombre");
    expect(html).toContain("Familia");
    expect(html).toContain("Subfamilia");
    expect(html).toContain("Almacén");
    expect(html).toContain("Sin datos de stock");
    expect(html).not.toContain("Movimientos");
    expect(html).not.toContain("Entrada stock");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Aceite oliva");
  });

  it("renders a selected stock view as embedded APP GESTION content without duplicate navigation", () => {
    const html = renderToStaticMarkup(
      <StockScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        embedded
        initialView="stock.topSales"
      />
    );

    expect(html).toContain('class="stock-screen work-screen gestion-embedded-module"');
    expect(html).toContain("Top ventas");
    expect(html).not.toContain('class="stock-nav"');
    expect(html).not.toContain('class="report-brand-back"');
    expect(html).not.toContain('class="report-user-button"');
  });

  it("builds permission-aware stock submenu options", () => {
    expect(visibleStockViewsForSession({ permissions: ["GESTION_ALMACEN"] }))
      .toEqual(["stock.current"]);
    expect(visibleStockViewsForSession({ permissions: ["STOCK_READ"] }))
      .not.toContain("stock.bulkEdit");
    expect(visibleStockViewsForSession({ permissions: ["GESTION_PRODUCTO"] }))
      .toContain("stock.bulkEdit");
  });

  it("uses an inventory filter dialog label outside top sales", () => {
    expect(stockFilterButtonLabelKey("stock.topSales")).toBe("stock.filter.title");
    expect(stockFilterButtonLabelKey("stock.current")).toBe("stock.filter.inventoryTitle");
    expect(stockFilterButtonLabelKey("stock.offers")).toBe("stock.filter.inventoryTitle");
    expect(stockFilterButtonLabelKey("stock.bulkEdit")).toBe("stock.filter.inventoryTitle");
  });
});

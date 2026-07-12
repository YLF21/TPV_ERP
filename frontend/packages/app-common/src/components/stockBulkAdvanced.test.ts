import { describe, expect, it } from "vitest";
import type { StockInventoryRow } from "./StockScreen";
import type { StockBulkEditRowData } from "./stockBulkEdit";
import {
  applyStockBulkDecimalEnding,
  countActiveStockBulkFilters,
  emptyStockBulkFilterCriteria,
  filterStockBulkRows,
  matchStockBulkImageFiles,
  mergeStockBulkFamilyProducts
} from "./stockBulkAdvanced";

const product = (overrides: Partial<StockInventoryRow> = {}): StockInventoryRow => ({
  productId: "product-1",
  code: "P-001",
  barcode: "840001",
  barcode2: "840002",
  name: "Agua mineral",
  description: "",
  productType: "UNIT",
  discountType: "NORMAL",
  backendDiscountType: "NORMAL",
  purchasePrice: "2.00",
  purchaseDiscountPercent: "0",
  salePrice: "12.78",
  memberPrice: "11.00",
  wholesalePrice: "10.00",
  offerPrice: "9.00",
  offerDiscountPercent: "0",
  offerFrom: "",
  offerUntil: "",
  offerActive: "common.no",
  familyId: "family-1",
  familyName: "Bebidas",
  subfamilyId: "subfamily-1",
  subfamilyName: "Agua",
  taxId: "tax-1",
  taxName: "IVA 21%",
  taxesIncluded: "common.yes",
  quantity: 0,
  totalQuantity: 0,
  warehouseId: "warehouse-1",
  warehouseName: "Principal",
  ...overrides
});

const row = (value = product()): StockBulkEditRowData => ({
  id: `row-${value.productId}`,
  selected: true,
  query: value.code,
  product: value,
  draft: { ...value }
});

describe("stock bulk advanced helpers", () => {
  it("counts only active filter values", () => {
    expect(countActiveStockBulkFilters(emptyStockBulkFilterCriteria)).toBe(0);
    expect(countActiveStockBulkFilters({
      ...emptyStockBulkFilterCriteria,
      productType: "UNIT",
      offerActive: false
    })).toBe(2);
  });

  it("filters by family, supplier, offer and price", () => {
    const candidate = { ...row(), suppliers: [{ id: "supplier-1" } as never] };
    expect(filterStockBulkRows([candidate], {
      familyId: "family-1",
      supplierId: "supplier-1",
      offerActive: false,
      minimumPrice: 10,
      maximumPrice: 20
    })).toHaveLength(1);
    expect(filterStockBulkRows([candidate], { offerActive: true })).toHaveLength(0);
  });

  it("keeps an open-ended offer when it overlaps the requested period", () => {
    const offered = row(product({
      discountType: "OFFER_PRICE",
      offerActive: "common.yes",
      offerFrom: "2026-07-01",
      offerUntil: ""
    }));

    expect(filterStockBulkRows([offered], {
      offerActive: true,
      offerFrom: "2026-07-10",
      offerUntil: "2026-07-31"
    })).toHaveLength(1);
  });

  it("imports every matching family product and skips duplicates", () => {
    const second = product({ productId: "product-2", code: "P-002" });
    const merged = mergeStockBulkFamilyProducts([row()], [product(), second], ["family-1"]);
    expect(merged.filter((item) => item.product)).toHaveLength(2);
    expect(merged.at(-1)?.product).toBeUndefined();
  });

  it("replaces the decimal ending on selected prices", () => {
    const adjusted = applyStockBulkDecimalEnding([row()], new Set(["row-product-1"]), "salePrice", 95);
    expect(adjusted[0].draft.salePrice).toBe("12.95");
  });

  it("matches image names by all product codes and leaves conflicts unresolved", () => {
    const file = { name: "840002.png" } as File;
    const result = matchStockBulkImageFiles([{ name: file.name, file }], [product()], "code");
    expect(result.matches).toMatchObject([{ productId: "product-1", fileName: "840002.png" }]);

    const duplicate = product({ productId: "product-2", code: "P-001", barcode: "", barcode2: null });
    const conflictFile = { name: "P-001.jpg" } as File;
    const conflict = matchStockBulkImageFiles(
      [{ name: conflictFile.name, file: conflictFile }],
      [product(), duplicate],
      "code"
    );
    expect(conflict.matches).toHaveLength(0);
    expect(conflict.unresolved[0].reason).toBe("multipleMatches");
  });
});

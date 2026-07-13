import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { StockBulkDecimalDialog, normalizeStockBulkDecimalInput } from "./StockBulkDecimalDialog";
import { StockBulkFamilyDialog, stockBulkFamilySelectionCount } from "./StockBulkFamilyDialog";
import { StockBulkFilterDialog, validateStockBulkFilter } from "./StockBulkFilterDialog";
import { priceRuleConflictFormIndexes, StockBulkPriceRulesDialog } from "./StockBulkPriceRulesDialog";
import { ApiError } from "../api/client";
import { emptyStockBulkFilterCriteria } from "./stockBulkAdvanced";

const families = [{ id: "family-1", name: "Bebidas", subfamilies: [{ id: "sub-1", name: "Agua" }] }];

describe("stock bulk dialogs", () => {
  it("validates filter ranges and renders every backend filter", () => {
    expect(validateStockBulkFilter({ minimumPrice: 20, maximumPrice: 10 })).toBe("stock.bulkEdit.filter.priceRangeError");
    expect(validateStockBulkFilter({ offerFrom: "2026-08-01", offerUntil: "2026-07-01" })).toBe("stock.bulkEdit.filter.offerRangeError");

    const html = renderToStaticMarkup(
      <StockBulkFilterDialog
        open
        locale="es"
        value={emptyStockBulkFilterCriteria}
        families={families}
        suppliers={[{ value: "supplier-1", label: "Proveedor" }]}
        taxes={[{ value: "tax-1", label: "IVA" }]}
        onApply={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(html).toContain("Filtrar edición masiva");
    expect(html).toContain("Tipo de producto");
    expect(html).toContain("Proveedor");
    expect(html).toContain("Precio mínimo");
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
  });

  it("renders the family explorer and counts distinct selections", () => {
    expect(stockBulkFamilySelectionCount(["family-1"], ["sub-1"])).toBe(2);
    const html = renderToStaticMarkup(
      <StockBulkFamilyDialog open locale="es" families={families} onApply={vi.fn()} onClose={vi.fn()} />
    );
    expect(html).toContain("Importar familias de productos");
    expect(html).toContain("Bebidas");

    const ruleHtml = renderToStaticMarkup(
      <StockBulkFamilyDialog
        open
        locale="es"
        families={families}
        titleKey="stock.bulkEdit.rules.chooseFamilies"
        applyLabelKey="stock.filter.apply"
        onApply={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(ruleHtml).toContain("Seleccionar familias y subfamilias");
  });

  it("accepts only two decimal ending digits", () => {
    expect(normalizeStockBulkDecimalInput("95")).toBe("95");
    expect(normalizeStockBulkDecimalInput("0,95")).toBe("95");
    expect(normalizeStockBulkDecimalInput("9")).toBeNull();
    const html = renderToStaticMarkup(
      <StockBulkDecimalDialog open locale="es" fieldLabel="Precio venta" selectedCount={2} onApply={vi.fn()} onClose={vi.fn()} />
    );
    expect(html).toContain("0,");
    expect(html).toContain('value=""');
    expect(html).toContain("00 y 99");
  });

  it("renders the formal two-panel price rule editor", () => {
    const html = renderToStaticMarkup(
      <StockBulkPriceRulesDialog
        open
        locale="es"
        token="token"
        currentUsername="admin"
        isAdmin
        products={[]}
        families={families}
        suppliers={[]}
        warehouses={[]}
        onApplied={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(html).toContain("stock-bulk-rules-list");
    expect(html).toContain("stock-bulk-rule-editor");
    expect(html).toContain("Reglas de precio");
    expect(html).toContain("Limpiar");
    expect(html).toContain("Precio fijo");
    expect(html).toContain('class="erp-select');
    expect(html).toContain('aria-haspopup="listbox"');
    expect(html).not.toContain("<select");
  });

  it("identifies the forms returned by a backend price conflict", () => {
    expect(priceRuleConflictFormIndexes(new ApiError("Conflicto", 409, {
      code: "PRODUCT_PRICE_RULE_CONFLICT",
      formIndexes: [0, 2]
    }))).toEqual([0, 2]);
    expect(priceRuleConflictFormIndexes(new Error("otro"))).toEqual([]);
  });
});

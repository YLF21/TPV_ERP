import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent, ReactNode } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ProductCreateDialog } from "./ProductCreateDialog";
import type { ProductCreateEditProduct, ProductCreateFormState } from "./ProductCreateDialog";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import stockFilterIcon from "../assets/stock/filter.png";
import stockSearchIcon from "../assets/stock/search.png";

export type StockViewKey =
  | "stock.current"
  | "stock.topSales"
  | "stock.offers"
  | "stock.memberPrice"
  | "stock.promotions"
  | "stock.noDiscount"
  | "stock.bulkEdit";
type StockDetailTab = "stock" | "sales" | "edit";
export type StockTopSalesPeriod = "day" | "week" | "month" | "year" | "custom";
type StockTopSalesQuickPeriod = Exclude<StockTopSalesPeriod, "custom">;

type StockScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};

type StockItemView = {
  productId: string;
  warehouseId: string;
  quantity: number;
};

type ProductView = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  description?: string | null;
  comments?: string | null;
  purchasePrice?: number | string | null;
  salePrice?: number | string | null;
  memberPrice?: number | string | null;
  wholesalePrice?: number | string | null;
  offerPrice?: number | string | null;
  productType?: string | null;
  priceUseMode?: string | null;
  discountType?: string | null;
  familyId?: string | null;
  subfamilyId?: string | null;
  taxId?: string | null;
  taxesIncluded?: boolean | null;
  offerDiscountPercent?: number | string | null;
  offerActive?: boolean | null;
  offerFrom?: string | null;
  offerUntil?: string | null;
};

type WarehouseView = {
  id: string;
  name?: string | null;
  defaultWarehouse?: boolean;
};

type FamilyView = {
  id: string;
  name?: string | null;
};

type SubfamilyView = {
  id: string;
  familyId?: string | null;
  name?: string | null;
};

type TaxView = {
  id: string;
  percentage?: number | string | null;
  name?: string | null;
};

export type StockInventoryRow = {
  productId: string;
  warehouseId: string;
  code: string;
  barcode: string;
  name: string;
  description?: string;
  comments?: string;
  purchasePrice: string;
  salePrice: string;
  memberPrice: string;
  wholesalePrice: string;
  offerPrice: string;
  offerDiscountPercent?: string;
  productType: string;
  discountType: string;
  familyId: string;
  familyName: string;
  subfamilyId: string;
  subfamilyName: string;
  taxId: string;
  taxName: string;
  taxesIncluded: string;
  offerActive: string;
  offerFrom: string;
  offerUntil: string;
  warehouseName: string;
  quantity: number;
  totalQuantity: number;
};

export type StockTopSalesSupplierView = {
  supplierId: string;
  supplierCode: string;
  supplierName: string;
};

export type StockTopSalesRow = {
  productId: string;
  code: string;
  barcode: string;
  name: string;
  familyId: string | null;
  familyName: string;
  subfamilyId: string | null;
  subfamilyName: string;
  suppliers: StockTopSalesSupplierView[];
  soldQuantity: number;
  netAmount: number;
  currentStock: number;
  warehouseId: string | null;
  warehouseName: string;
};

export type StockTopSalesFilters = {
  family: string;
  subfamily: string;
  supplier: string;
  search: string;
};

export type StockInventoryFilters = {
  type: string;
  discount: string;
  family: string;
  tax: string;
  offerActive: "" | "yes" | "no";
  warehouse: string;
};

export type StockColumnSetting = {
  key: string;
  width: number;
};

export type StockTopSalesFamilyNode = {
  id: string;
  name: string;
  subfamilies: Array<{
    id: string;
    name: string;
  }>;
};

export const stockViews: StockViewKey[] = [
  "stock.current",
  "stock.topSales",
  "stock.offers",
  "stock.memberPrice",
  "stock.promotions",
  "stock.noDiscount",
  "stock.bulkEdit"
];
const stockTopSalesPeriods: StockTopSalesQuickPeriod[] = ["day", "week", "month", "year"];
const stockColumnMinWidth = 72;
const stockColumnMaxWidth = 420;
const stockProductTypeOptions = ["UNIT", "WEIGHT", "SERVICE"];
const stockDiscountTypeOptions = ["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"];
const defaultStockInventoryFilters: StockInventoryFilters = {
  type: "",
  discount: "",
  family: "",
  tax: "",
  offerActive: "",
  warehouse: ""
};

type StockColumnDefinition = {
  key: string;
  labelKey: string;
  defaultWidth: number;
};

const stockTopSalesColumns: StockColumnDefinition[] = [
  { key: "ranking", labelKey: "stock.column.ranking", defaultWidth: 72 },
  { key: "code", labelKey: "stock.column.code", defaultWidth: 110 },
  { key: "barcode", labelKey: "stock.column.barcode", defaultWidth: 140 },
  { key: "name", labelKey: "stock.column.name", defaultWidth: 220 },
  { key: "family", labelKey: "stock.column.family", defaultWidth: 150 },
  { key: "subfamily", labelKey: "stock.column.subfamily", defaultWidth: 150 },
  { key: "supplier", labelKey: "stock.column.supplier", defaultWidth: 180 },
  { key: "soldUnits", labelKey: "stock.column.soldUnits", defaultWidth: 130 },
  { key: "amount", labelKey: "stock.column.amount", defaultWidth: 110 },
  { key: "currentStock", labelKey: "stock.column.currentStock", defaultWidth: 120 },
  { key: "warehouse", labelKey: "stock.column.warehouse", defaultWidth: 140 }
];

const stockInventoryColumns: StockColumnDefinition[] = [
  { key: "code", labelKey: "stock.column.code", defaultWidth: 110 },
  { key: "barcode", labelKey: "stock.column.barcode", defaultWidth: 140 },
  { key: "name", labelKey: "stock.column.name", defaultWidth: 220 },
  { key: "type", labelKey: "stock.column.type", defaultWidth: 88 },
  { key: "discount", labelKey: "product.field.usePrice", defaultWidth: 120 },
  { key: "family", labelKey: "stock.column.family", defaultWidth: 180 },
  { key: "subfamily", labelKey: "stock.column.subfamily", defaultWidth: 180 },
  { key: "tax", labelKey: "stock.column.tax", defaultWidth: 180 },
  { key: "taxIncluded", labelKey: "stock.column.taxIncluded", defaultWidth: 82 },
  { key: "purchasePrice", labelKey: "stock.column.purchasePrice", defaultWidth: 86 },
  { key: "salePrice", labelKey: "stock.column.salePrice", defaultWidth: 86 },
  { key: "memberPrice", labelKey: "stock.column.memberPrice", defaultWidth: 86 },
  { key: "wholesalePrice", labelKey: "stock.column.wholesalePrice", defaultWidth: 86 },
  { key: "offerPrice", labelKey: "stock.column.offerPrice", defaultWidth: 86 },
  { key: "offerActive", labelKey: "stock.column.offerActive", defaultWidth: 100 },
  { key: "offerFrom", labelKey: "stock.column.offerFrom", defaultWidth: 110 },
  { key: "offerUntil", labelKey: "stock.column.offerUntil", defaultWidth: 110 },
  { key: "warehouse", labelKey: "stock.column.warehouse", defaultWidth: 140 },
  { key: "localStock", labelKey: "stock.column.localStock", defaultWidth: 96 },
  { key: "totalStock", labelKey: "stock.column.totalStock", defaultWidth: 96 },
  { key: "status", labelKey: "stock.column.status", defaultWidth: 180 }
];

const stockColumnDefinitions: Record<StockViewKey, StockColumnDefinition[]> = {
  "stock.topSales": stockTopSalesColumns,
  "stock.current": stockInventoryColumns,
  "stock.offers": stockInventoryColumns,
  "stock.memberPrice": stockInventoryColumns,
  "stock.promotions": stockInventoryColumns,
  "stock.noDiscount": stockInventoryColumns,
  "stock.bulkEdit": stockInventoryColumns
};

export type StockColumnSettingsByView = Record<StockViewKey, StockColumnSetting[]>;

function valueText(value: unknown) {
  return value === null || value === undefined || value === "" ? "-" : String(value);
}

function valueNumber(value: number | string) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function taxDisplayName(tax?: TaxView) {
  if (!tax) {
    return "-";
  }
  if (tax.name) {
    return tax.name;
  }
  return tax.percentage === undefined || tax.percentage === null ? tax.id : `${tax.percentage}%`;
}

function clampStockColumnWidth(width: number) {
  if (!Number.isFinite(width)) {
    return stockColumnMinWidth;
  }
  return Math.min(stockColumnMaxWidth, Math.max(stockColumnMinWidth, Math.round(width)));
}

function stockColumnDefaultsForView(view: StockViewKey): StockColumnSetting[] {
  return stockColumnDefinitions[view].map((column) => ({
    key: column.key,
    width: column.defaultWidth
  }));
}

export function createDefaultStockColumnSettings(): StockColumnSettingsByView {
  return stockViews.reduce((settings, view) => ({
    ...settings,
    [view]: stockColumnDefaultsForView(view)
  }), {} as StockColumnSettingsByView);
}

export function stockColumnStorageKey(app: AppKind, username: string) {
  return `tpv.stock.columns.${app}.${username}`;
}

export function sanitizeStockColumnSettings(saved?: Partial<Record<StockViewKey, StockColumnSetting[]>> | null): StockColumnSettingsByView {
  const defaults = createDefaultStockColumnSettings();

  return stockViews.reduce((next, view) => {
    const definitions = stockColumnDefinitions[view];
    const availableKeys = new Set(definitions.map((column) => column.key));
    const seen = new Set<string>();
    const savedColumns = Array.isArray(saved?.[view]) ? saved[view] ?? [] : [];
    const sanitized = savedColumns.reduce<StockColumnSetting[]>((columns, column) => {
      if (!availableKeys.has(column.key) || seen.has(column.key)) {
        return columns;
      }
      seen.add(column.key);
      columns.push({ key: column.key, width: clampStockColumnWidth(column.width) });
      return columns;
    }, []);

    const missing = defaults[view].filter((column) => !seen.has(column.key));
    return {
      ...next,
      [view]: sanitized.length > 0 ? [...sanitized, ...missing] : defaults[view]
    };
  }, {} as StockColumnSettingsByView);
}

export function moveStockColumn(settings: StockColumnSettingsByView, view: StockViewKey, columnKey: string, direction: -1 | 1): StockColumnSettingsByView {
  const columns = [...settings[view]];
  const from = columns.findIndex((column) => column.key === columnKey);
  if (from < 0) {
    return settings;
  }
  const to = Math.min(columns.length - 1, Math.max(0, from + direction));
  if (from === to) {
    return settings;
  }
  const [column] = columns.splice(from, 1);
  columns.splice(to, 0, column);
  return { ...settings, [view]: columns };
}

export function resizeStockColumn(settings: StockColumnSettingsByView, view: StockViewKey, columnKey: string, width: number): StockColumnSettingsByView {
  return {
    ...settings,
    [view]: settings[view].map((column) => (
      column.key === columnKey ? { ...column, width: clampStockColumnWidth(width) } : column
    ))
  };
}

function stockColumnGridTemplate(columns: StockColumnSetting[]) {
  return columns.map((column) => `${column.width}px`).join(" ");
}

export function stockDetailKeyAction(key: string): StockDetailTab | "close" | null {
  if (key === "F5" || key === "Enter") {
    return "stock";
  }
  if (key === "F6") {
    return "sales";
  }
  if (key === "F7") {
    return "edit";
  }
  if (key === "Escape") {
    return "close";
  }
  return null;
}

function productTypeForForm(value: string): ProductCreateFormState["productType"] {
  return value === "SERVICE" || value === "WEIGHT" ? value : "UNIT";
}

function priceUseModeForForm(value: string): ProductCreateFormState["priceUseMode"] {
  if (value === "MEMBER_PRICE" || value === "OFFER_PRICE" || value === "OFFER_DISCOUNT") {
    return value;
  }
  return "NORMAL";
}

function discountTypeForForm(value: string): ProductCreateFormState["discountType"] {
  if (value === "NONE" || value === "MEMBER_PRICE" || value === "DISCOUNT_PRICE") {
    return value;
  }
  return "NORMAL";
}

export function stockRowToProductEdit(row: StockInventoryRow): ProductCreateEditProduct {
  const priceUseMode = priceUseModeForForm(row.discountType);
  return {
    id: row.productId,
    form: {
      familyId: row.familyId,
      subfamilyId: row.subfamilyId,
      taxId: row.taxId,
      productType: productTypeForForm(row.productType),
      priceUseMode,
      discountType: discountTypeForForm(priceUseMode === "MEMBER_PRICE" ? "MEMBER_PRICE" : priceUseMode === "OFFER_PRICE" || priceUseMode === "OFFER_DISCOUNT" ? "DISCOUNT_PRICE" : row.discountType),
      name: row.name,
      description: row.description ?? "",
      comments: row.comments ?? "",
      purchasePrice: row.purchasePrice || "0.00",
      taxesIncluded: row.taxesIncluded === "common.yes",
      code: row.code,
      barcode: row.barcode,
      salePrice: row.salePrice || "0.00",
      memberPrice: row.memberPrice,
      wholesalePrice: row.wholesalePrice,
      offerPrice: row.offerPrice,
      offerDiscountPercent: row.offerDiscountPercent ?? "",
      offerActive: priceUseMode === "OFFER_PRICE" || priceUseMode === "OFFER_DISCOUNT",
      offerFrom: row.offerFrom,
      offerUntil: row.offerUntil
    }
  };
}

export function nextStockSelectedIndex(currentIndex: number, rowCount: number, key: string) {
  if (rowCount <= 0) {
    return -1;
  }
  if (key === "ArrowDown") {
    return Math.min(rowCount - 1, currentIndex + 1);
  }
  if (key === "ArrowUp") {
    return Math.max(0, currentIndex - 1);
  }
  return currentIndex;
}

function uniqueStockOptions(rows: StockInventoryRow[], valueKey: keyof StockInventoryRow, labelKey: keyof StockInventoryRow) {
  const options = new Map<string, string>();
  rows.forEach((row) => {
    const value = String(row[valueKey] ?? "");
    const label = String(row[labelKey] ?? value);
    if (value && value !== "-" && !options.has(value)) {
      options.set(value, label && label !== "-" ? label : value);
    }
  });
  return Array.from(options, ([value, label]) => ({ value, label }))
    .sort((left, right) => left.label.localeCompare(right.label, "es"));
}

function loadStoredStockColumnSettings(app: AppKind, username: string) {
  if (typeof window === "undefined") {
    return createDefaultStockColumnSettings();
  }
  const raw = window.localStorage.getItem(stockColumnStorageKey(app, username));
  if (!raw) {
    return createDefaultStockColumnSettings();
  }
  try {
    return sanitizeStockColumnSettings(JSON.parse(raw));
  } catch {
    return createDefaultStockColumnSettings();
  }
}

function saveStoredStockColumnSettings(app: AppKind, username: string, settings: StockColumnSettingsByView) {
  if (typeof window === "undefined") {
    return;
  }
  window.localStorage.setItem(stockColumnStorageKey(app, username), JSON.stringify(settings));
}

export function buildStockInventoryRows(
  products: ProductView[],
  warehouses: WarehouseView[],
  stock: StockItemView[],
  catalog: {
    families?: FamilyView[];
    subfamilies?: SubfamilyView[];
    taxes?: TaxView[];
  } = {}
): StockInventoryRow[] {
  const productsById = new Map(products.map((product) => [product.id, product]));
  const warehousesById = new Map(warehouses.map((warehouse) => [warehouse.id, warehouse]));
  const familiesById = new Map((catalog.families ?? []).map((family) => [family.id, family]));
  const subfamiliesById = new Map((catalog.subfamilies ?? []).map((subfamily) => [subfamily.id, subfamily]));
  const taxesById = new Map((catalog.taxes ?? []).map((tax) => [tax.id, tax]));
  const defaultWarehouse = warehouses.find((warehouse) => warehouse.defaultWarehouse) ?? warehouses[0];
  const totalStockByProduct = stock.reduce<Map<string, number>>((totals, item) => {
    totals.set(item.productId, (totals.get(item.productId) ?? 0) + valueNumber(item.quantity));
    return totals;
  }, new Map());
  const seenProducts = new Set<string>();

  function productRow(product: ProductView | undefined, productId: string, warehouseId: string, warehouseName: string, quantity: number): StockInventoryRow {
    const family = product?.familyId ? familiesById.get(product.familyId) : undefined;
    const subfamily = product?.subfamilyId ? subfamiliesById.get(product.subfamilyId) : undefined;
    const tax = product?.taxId ? taxesById.get(product.taxId) : undefined;
    return {
      productId,
      warehouseId,
      code: valueText(product?.code),
      barcode: valueText(product?.barcode),
      name: valueText(product?.name ?? productId),
      description: valueText(product?.description),
      comments: valueText(product?.comments),
      purchasePrice: valueText(product?.purchasePrice),
      salePrice: valueText(product?.salePrice),
      memberPrice: valueText(product?.memberPrice),
      wholesalePrice: valueText(product?.wholesalePrice),
      offerPrice: valueText(product?.offerPrice),
      offerDiscountPercent: valueText(product?.offerDiscountPercent),
      productType: valueText(product?.productType),
      discountType: valueText(product?.priceUseMode ?? product?.discountType),
      familyId: valueText(product?.familyId),
      familyName: valueText(family?.name ?? product?.familyId),
      subfamilyId: valueText(product?.subfamilyId),
      subfamilyName: valueText(subfamily?.name ?? product?.subfamilyId),
      taxId: valueText(product?.taxId),
      taxName: taxDisplayName(tax) === "-" ? valueText(product?.taxId) : taxDisplayName(tax),
      taxesIncluded: product?.taxesIncluded === undefined || product.taxesIncluded === null ? "-" : product.taxesIncluded ? "common.yes" : "common.no",
      offerActive: product?.offerActive === undefined || product.offerActive === null ? "-" : product.offerActive ? "common.yes" : "common.no",
      offerFrom: valueText(product?.offerFrom),
      offerUntil: valueText(product?.offerUntil),
      warehouseName,
      quantity,
      totalQuantity: totalStockByProduct.get(productId) ?? quantity
    };
  }

  const stockRows = stock.map((item) => {
    const product = productsById.get(item.productId);
    const warehouse = warehousesById.get(item.warehouseId);
    seenProducts.add(item.productId);

    return productRow(product, item.productId, item.warehouseId, valueText(warehouse?.name ?? item.warehouseId), valueNumber(item.quantity));
  });

  const zeroStockProductRows = products
    .filter((product) => !seenProducts.has(product.id))
    .map((product) => productRow(product, product.id, valueText(defaultWarehouse?.id), valueText(defaultWarehouse?.name), 0));

  return [...stockRows, ...zeroStockProductRows];
}

export async function loadStockSubfamilies(
  families: FamilyView[],
  loadSubfamilies: (familyId: string) => Promise<SubfamilyView[]>
) {
  const results = await Promise.allSettled(families.map((family) => loadSubfamilies(family.id)));
  return results.flatMap((result) => result.status === "fulfilled" ? result.value : []);
}

export async function loadStockInventoryRows(loaders: {
  loadStock: () => Promise<StockItemView[]>;
  loadProducts: () => Promise<ProductView[]>;
  loadWarehouses: () => Promise<WarehouseView[]>;
  loadFamilies: () => Promise<FamilyView[]>;
  loadTaxes: () => Promise<TaxView[]>;
  loadSubfamilies: (familyId: string) => Promise<SubfamilyView[]>;
}) {
  const [stockResult, products, warehouses, families, taxes] = await Promise.all([
    loaders.loadStock().then(
      (stock) => ({ status: "fulfilled" as const, value: stock }),
      () => ({ status: "rejected" as const, value: [] as StockItemView[] })
    ),
    loaders.loadProducts(),
    loaders.loadWarehouses(),
    loaders.loadFamilies(),
    loaders.loadTaxes()
  ]);
  const subfamilies = await loadStockSubfamilies(families, loaders.loadSubfamilies);
  return buildStockInventoryRows(products, warehouses, stockResult.value, { families, subfamilies, taxes });
}

export function stockInventoryStatus(quantity: number) {
  if (quantity <= 0) {
    return "stock.status.empty";
  }
  if (quantity <= 5) {
    return "stock.status.low";
  }
  return "stock.status.ok";
}

export function stockLoadStatus(error: unknown, fallback: string) {
  if (error instanceof TypeError || (error instanceof Error && error.message === "Failed to write request")) {
    return fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

export function stockFilterButtonLabelKey(view: StockViewKey) {
  return view === "stock.topSales" ? "stock.filter.title" : "stock.filter.inventoryTitle";
}

export function stockViewAfterProductCreated(_currentView: StockViewKey): StockViewKey {
  return "stock.current";
}

function stockProductTypeLabel(type: string) {
  if (type === "WEIGHT") {
    return "product.type.weight";
  }
  if (type === "SERVICE") {
    return "product.type.service";
  }
  return "product.type.unit";
}

function stockDiscountTypeLabel(type: string) {
  if (type === "NORMAL" || type === "NONE") {
    return "product.discount.none";
  }
  if (type === "MEMBER_PRICE") {
    return "product.discount.memberPrice";
  }
  if (type === "DISCOUNT_PRICE" || type === "OFFER_PRICE") {
    return "product.discount.offerPrice";
  }
  return "product.discount.offerDiscount";
}

export function filterStockInventoryRows(
  rows: StockInventoryRow[],
  selectedView: StockViewKey,
  searchText: string,
  filters: StockInventoryFilters = defaultStockInventoryFilters
) {
  const normalizedSearch = searchText.trim().toLocaleLowerCase("es");

  return rows.filter((row) => {
    if (selectedView === "stock.offers" && !["OFFER_PRICE", "OFFER_DISCOUNT", "DISCOUNT_PRICE"].includes(row.discountType)) {
      return false;
    }
    if (selectedView === "stock.memberPrice" && row.discountType !== "MEMBER_PRICE") {
      return false;
    }
    if (selectedView === "stock.noDiscount" && row.discountType !== "NONE") {
      return false;
    }
    if (selectedView === "stock.promotions" || selectedView === "stock.bulkEdit") {
      return false;
    }
    if (filters.type && row.productType !== filters.type) {
      return false;
    }
    if (filters.discount && row.discountType !== filters.discount) {
      return false;
    }
    if (filters.family && row.familyId !== filters.family && row.familyName !== filters.family) {
      return false;
    }
    if (filters.tax && row.taxId !== filters.tax && row.taxName !== filters.tax) {
      return false;
    }
    if (filters.offerActive === "yes" && row.offerActive !== "common.yes") {
      return false;
    }
    if (filters.offerActive === "no" && row.offerActive !== "common.no") {
      return false;
    }
    if (filters.warehouse && row.warehouseId !== filters.warehouse && row.warehouseName !== filters.warehouse) {
      return false;
    }
    if (normalizedSearch.length === 0) {
      return true;
    }

    return [
      row.code,
      row.barcode,
      row.name,
      row.productType,
      row.discountType,
      row.familyId,
      row.familyName,
      row.subfamilyId,
      row.subfamilyName,
      row.taxId,
      row.taxName,
      row.offerActive,
      row.offerFrom,
      row.offerUntil,
      row.warehouseName
    ].some((value) => String(value ?? "").toLocaleLowerCase("es").includes(normalizedSearch));
  });
}

export function stockTopSalesPath(period: StockTopSalesPeriod, dateFrom: string, dateTo = dateFrom) {
  if (period === "custom") {
    return `/stock/top-sales?dateFrom=${encodeURIComponent(dateFrom)}&dateTo=${encodeURIComponent(dateTo)}`;
  }
  return `/stock/top-sales?period=${encodeURIComponent(period)}&date=${encodeURIComponent(dateTo)}`;
}

export function todayIsoDate(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function parseIsoDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) {
    return null;
  }
  return new Date(year, month - 1, day);
}

function startOfMonth(value: Date) {
  return new Date(value.getFullYear(), value.getMonth(), 1);
}

function addStockDays(value: Date, days: number) {
  return new Date(value.getFullYear(), value.getMonth(), value.getDate() + days);
}

export function stockTopSalesPeriodRange(period: StockTopSalesQuickPeriod, date = new Date()) {
  const dateTo = todayIsoDate(date);
  const offset = period === "day" ? 0 : period === "week" ? -6 : period === "month" ? -29 : -364;
  return {
    dateFrom: todayIsoDate(addStockDays(date, offset)),
    dateTo
  };
}

function buildStockCalendarDays(month: Date) {
  const firstDay = startOfMonth(month);
  const firstWeekday = (firstDay.getDay() + 6) % 7;
  const daysInMonth = new Date(firstDay.getFullYear(), firstDay.getMonth() + 1, 0).getDate();
  const blanks = Array.from({ length: firstWeekday }, () => null);
  const days = Array.from({ length: daysInMonth }, (_, index) => new Date(firstDay.getFullYear(), firstDay.getMonth(), index + 1));
  return [...blanks, ...days];
}

function stockWeekdayLabels(locale: LocaleCode) {
  if (locale === "zh") {
    return ["一", "二", "三", "四", "五", "六", "日"];
  }
  if (locale === "en") {
    return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  }
  return ["L", "M", "X", "J", "V", "S", "D"];
}

function formatStockFilterDate(value: string, locale: LocaleCode) {
  const date = parseIsoDate(value);
  if (!date) {
    return "";
  }
  const browserLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  return new Intl.DateTimeFormat(browserLocale).format(date);
}

function formatStockDateRange(dateFrom: string, dateTo: string, locale: LocaleCode) {
  const from = formatStockFilterDate(dateFrom, locale);
  const to = formatStockFilterDate(dateTo, locale);
  if (!from && !to) {
    return "";
  }
  if (!to || from === to) {
    return from;
  }
  return `${from}-${to}`;
}

function parseStockManualDate(value: string) {
  const trimmed = value.trim();
  const separated = trimmed.match(/^(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{2}|\d{4})$/);
  if (separated) {
    return buildStockIsoDate(separated[1], separated[2], separated[3]);
  }
  const digits = trimmed.replace(/\D/g, "");
  if (digits.length === 6) {
    return buildStockIsoDate(digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 6));
  }
  if (digits.length === 8) {
    return buildStockIsoDate(digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 8));
  }
  return "";
}

function buildStockIsoDate(dayValue: string, monthValue: string, yearValue: string) {
  const day = Number(dayValue);
  const month = Number(monthValue);
  const year = Number(yearValue.length === 2 ? `20${yearValue}` : yearValue);
  const date = new Date(year, month - 1, day);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    return "";
  }
  return todayIsoDate(date);
}

function normalizeStockDateRange(dateFrom: string, dateTo: string) {
  if (!dateFrom && !dateTo) {
    return null;
  }
  const start = dateFrom || dateTo;
  const end = dateTo || dateFrom;
  return start <= end ? { dateFrom: start, dateTo: end } : { dateFrom: end, dateTo: start };
}

function parseStockDateRangeInput(value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  const compact = trimmed.replace(/\D/g, "");
  const spacedDashRange = trimmed.match(/^(.+?)\s+-\s+(.+)$/);
  if (spacedDashRange) {
    return normalizeStockDateRange(parseStockManualDate(spacedDashRange[1]), parseStockManualDate(spacedDashRange[2]));
  }
  const slashOrDotRange = trimmed.match(/^(\d{1,2}[/.]\d{1,2}[/.](?:\d{2}|\d{4}))\s*-\s*(\d{1,2}[/.]\d{1,2}[/.](?:\d{2}|\d{4}))$/);
  if (slashOrDotRange) {
    return normalizeStockDateRange(parseStockManualDate(slashOrDotRange[1]), parseStockManualDate(slashOrDotRange[2]));
  }
  const compactDashRange = trimmed.match(/^(\d{6}|\d{8})\s*-\s*(\d{6}|\d{8})$/);
  if (compactDashRange) {
    return normalizeStockDateRange(parseStockManualDate(compactDashRange[1]), parseStockManualDate(compactDashRange[2]));
  }
  if (/^\d+$/.test(trimmed) && compact.length === 16) {
    return normalizeStockDateRange(parseStockManualDate(compact.slice(0, 8)), parseStockManualDate(compact.slice(8, 16)));
  }
  if (/^\d+$/.test(trimmed) && compact.length === 12) {
    return normalizeStockDateRange(parseStockManualDate(compact.slice(0, 6)), parseStockManualDate(compact.slice(6, 12)));
  }
  const singleDate = parseStockManualDate(trimmed);
  if (singleDate) {
    return normalizeStockDateRange(singleDate, singleDate);
  }
  return null;
}

export function stockTopSalesPeriodLabel(period: StockTopSalesPeriod) {
  if (period === "day") {
    return "stock.period.day";
  }
  if (period === "month") {
    return "stock.period.month";
  }
  if (period === "year") {
    return "stock.period.year";
  }
  if (period === "custom") {
    return "stock.period.custom";
  }
  return "stock.period.week";
}

export function formatStockTopSalesDate(date: string) {
  if (!date) {
    return "";
  }
  const [year, month, day] = date.split("-");
  return year && month && day ? `${day}/${month}/${year}` : date;
}

export function buildStockTopSalesFamilyTree(rows: StockTopSalesRow[], noFamilyLabel = "stock.filter.noFamily"): StockTopSalesFamilyNode[] {
  const families = new Map<string, StockTopSalesFamilyNode>();

  rows.forEach((row) => {
    const familyName = row.familyName || row.familyId || noFamilyLabel;
    const familyId = row.familyId || familyName;
    const family = families.get(familyId) ?? {
      id: familyId,
      name: familyName,
      subfamilies: []
    };

    if (!families.has(familyId)) {
      families.set(familyId, family);
    }

    const subfamilyName = row.subfamilyName || row.subfamilyId || "";
    const subfamilyId = row.subfamilyId || subfamilyName;
    if (subfamilyName && !family.subfamilies.some((subfamily) => subfamily.id === subfamilyId)) {
      family.subfamilies.push({ id: subfamilyId, name: subfamilyName });
    }
  });

  return Array.from(families.values())
    .map((family) => ({
      ...family,
      subfamilies: family.subfamilies.sort((left, right) => left.name.localeCompare(right.name, "es"))
    }))
    .sort((left, right) => left.name.localeCompare(right.name, "es"));
}

export function buildStockInventoryFamilyTree(rows: StockInventoryRow[], noFamilyLabel = "stock.filter.noFamily"): StockTopSalesFamilyNode[] {
  const families = new Map<string, StockTopSalesFamilyNode>();

  rows.forEach((row) => {
    const familyId = row.familyId && row.familyId !== "-" ? row.familyId : row.familyName || noFamilyLabel;
    const familyName = row.familyName && row.familyName !== "-" ? row.familyName : noFamilyLabel;
    const family = families.get(familyId) ?? {
      id: familyId,
      name: familyName,
      subfamilies: []
    };

    if (!families.has(familyId)) {
      families.set(familyId, family);
    }

    if (row.subfamilyId && row.subfamilyId !== "-" && row.subfamilyName && row.subfamilyName !== "-"
      && !family.subfamilies.some((subfamily) => subfamily.id === row.subfamilyId)) {
      family.subfamilies.push({ id: row.subfamilyId, name: row.subfamilyName });
    }
  });

  return Array.from(families.values())
    .map((family) => ({
      ...family,
      subfamilies: family.subfamilies.sort((left, right) => left.name.localeCompare(right.name, "es"))
    }))
    .sort((left, right) => left.name.localeCompare(right.name, "es"));
}

export function filterStockTopSalesRows(rows: StockTopSalesRow[], filters: StockTopSalesFilters) {
  const family = filters.family.trim().toLocaleLowerCase("es");
  const subfamily = filters.subfamily.trim().toLocaleLowerCase("es");
  const supplier = filters.supplier.trim().toLocaleLowerCase("es");
  const search = filters.search.trim().toLocaleLowerCase("es");

  return rows.filter((row) => {
    const supplierText = row.suppliers
      .map((value) => `${value.supplierCode} ${value.supplierName}`)
      .join(" ")
      .toLocaleLowerCase("es");
    const rowText = [
      row.code,
      row.barcode,
      row.name,
      row.familyName,
      row.subfamilyName,
      supplierText,
      row.warehouseName
    ].join(" ").toLocaleLowerCase("es");

    return (!family || row.familyName.toLocaleLowerCase("es").includes(family) || (row.familyId ?? "").toLocaleLowerCase("es").includes(family)) &&
      (!subfamily || row.subfamilyName.toLocaleLowerCase("es").includes(subfamily) || (row.subfamilyId ?? "").toLocaleLowerCase("es").includes(subfamily)) &&
      (!supplier || supplierText.includes(supplier)) &&
      (!search || rowText.includes(search));
  }).sort((left, right) => right.soldQuantity - left.soldQuantity || left.name.localeCompare(right.name, "es"));
}

export function StockScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout
}: StockScreenProps) {
  const t = createTranslator(locale);
  const stockTitle = t("home.stock").toLocaleUpperCase(locale === "zh" ? "zh-CN" : locale);
  const [selectedView, setSelectedView] = useState<StockViewKey>("stock.current");
  const [searchText, setSearchText] = useState("");
  const [stockRows, setStockRows] = useState<StockInventoryRow[]>([]);
  const [status, setStatus] = useState("stock.status.noData");
  const [stockRefreshCounter, setStockRefreshCounter] = useState(0);
  const [productCreateOpen, setProductCreateOpen] = useState(false);
  const [topSalesRows, setTopSalesRows] = useState<StockTopSalesRow[]>([]);
  const [topSalesStatus, setTopSalesStatus] = useState("stock.status.noTopSales");
  const [topSalesPeriod, setTopSalesPeriod] = useState<StockTopSalesPeriod>("week");
  const [topSalesDateFrom, setTopSalesDateFrom] = useState(() => stockTopSalesPeriodRange("week").dateFrom);
  const [topSalesDateTo, setTopSalesDateTo] = useState(() => stockTopSalesPeriodRange("week").dateTo);
  const [topSalesFilters, setTopSalesFilters] = useState<StockTopSalesFilters>({
    family: "",
    subfamily: "",
    supplier: "",
    search: ""
  });
  const [draftTopSalesPeriod, setDraftTopSalesPeriod] = useState<StockTopSalesPeriod>("week");
  const [draftTopSalesDateFrom, setDraftTopSalesDateFrom] = useState(() => stockTopSalesPeriodRange("week").dateFrom);
  const [draftTopSalesDateTo, setDraftTopSalesDateTo] = useState(() => stockTopSalesPeriodRange("week").dateTo);
  const [draftTopSalesFilters, setDraftTopSalesFilters] = useState<StockTopSalesFilters>({
    family: "",
    subfamily: "",
    supplier: "",
    search: ""
  });
  const [topSalesFilterOpen, setTopSalesFilterOpen] = useState(false);
  const [inventoryFilterOpen, setInventoryFilterOpen] = useState(false);
  const [draftInventoryView, setDraftInventoryView] = useState<StockViewKey>("stock.current");
  const [inventoryFilters, setInventoryFilters] = useState<StockInventoryFilters>(defaultStockInventoryFilters);
  const [draftInventoryFilters, setDraftInventoryFilters] = useState<StockInventoryFilters>(defaultStockInventoryFilters);
  const [inventoryDropdownOpen, setInventoryDropdownOpen] = useState("");
  const [inventoryFamilyPickerOpen, setInventoryFamilyPickerOpen] = useState(false);
  const [selectedInventoryFamily, setSelectedInventoryFamily] = useState("");
  const [topSalesDatePickerOpen, setTopSalesDatePickerOpen] = useState(false);
  const [topSalesDateText, setTopSalesDateText] = useState(() => {
    const range = stockTopSalesPeriodRange("week");
    return formatStockDateRange(range.dateFrom, range.dateTo, locale);
  });
  const [topSalesDateRangeStart, setTopSalesDateRangeStart] = useState<string | null>(null);
  const [stockColumnsOpen, setStockColumnsOpen] = useState(false);
  const [calendarMonth, setCalendarMonth] = useState(() => startOfMonth(new Date()));
  const [familyPickerOpen, setFamilyPickerOpen] = useState(false);
  const [expandedFamilies, setExpandedFamilies] = useState<Record<string, boolean>>({});
  const [selectedFamily, setSelectedFamily] = useState({ family: "", subfamily: "" });
  const [columnSettings, setColumnSettings] = useState<StockColumnSettingsByView>(() => loadStoredStockColumnSettings(app, session.username));
  const [selectedStockIndex, setSelectedStockIndex] = useState(0);
  const [detailRow, setDetailRow] = useState<StockInventoryRow | null>(null);
  const [detailTab, setDetailTab] = useState<StockDetailTab>("stock");
  const [editingProduct, setEditingProduct] = useState<ProductCreateEditProduct | null>(null);
  const stockTableRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken) {
      setStockRows([]);
      setStatus("stock.status.noData");
      return;
    }
    if (selectedView === "stock.topSales") {
      return;
    }

    async function loadStock() {
      try {
        const rows = await loadStockInventoryRows({
          loadStock: () => apiRequest<StockItemView[]>("/stock", { token: session.accessToken }),
          loadProducts: () => apiRequest<ProductView[]>("/products", { token: session.accessToken }),
          loadWarehouses: () => apiRequest<WarehouseView[]>("/warehouses", { token: session.accessToken }),
          loadFamilies: () => apiRequest<FamilyView[]>("/families", { token: session.accessToken }),
          loadTaxes: () => apiRequest<TaxView[]>("/taxes/selectable", { token: session.accessToken }),
          loadSubfamilies: (familyId) => apiRequest<SubfamilyView[]>(`/families/${encodeURIComponent(familyId)}/subfamilies`, { token: session.accessToken })
        });
        if (!cancelled) {
          setStockRows(rows);
          setStatus(rows.length === 0 ? "stock.status.noData" : "stock.status.inventoryLoaded");
        }
      } catch (error) {
        if (!cancelled) {
          setStockRows([]);
          setStatus(stockLoadStatus(error, "stock.status.noData"));
        }
      }
    }

    void loadStock();
    return () => {
      cancelled = true;
    };
  }, [selectedView, session.accessToken, stockRefreshCounter]);

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken || selectedView !== "stock.topSales") {
      return;
    }

    async function loadTopSales() {
      try {
        const rows = await apiRequest<StockTopSalesRow[]>(stockTopSalesPath(topSalesPeriod, topSalesDateFrom, topSalesDateTo), { token: session.accessToken });
        if (!cancelled) {
          setTopSalesRows(rows);
          setTopSalesStatus(rows.length === 0 ? "stock.status.noTopSales" : "stock.status.topSalesLoaded");
        }
      } catch (error) {
        if (!cancelled) {
          setTopSalesRows([]);
          setTopSalesStatus(stockLoadStatus(error, "stock.status.noTopSales"));
        }
      }
    }

    void loadTopSales();
    return () => {
      cancelled = true;
    };
  }, [selectedView, session.accessToken, topSalesDateFrom, topSalesDateTo, topSalesPeriod]);

  const visibleRows = filterStockInventoryRows(stockRows, selectedView, searchText, inventoryFilters);
  const visibleTopSalesRows = filterStockTopSalesRows(topSalesRows, topSalesFilters);
  const familyTree = useMemo(() => buildStockTopSalesFamilyTree(topSalesRows, t("stock.filter.noFamily")), [topSalesRows, t]);
  const inventoryFamilyTree = useMemo(() => buildStockInventoryFamilyTree(stockRows, t("stock.filter.noFamily")), [stockRows, t]);
  const inventoryTaxOptions = useMemo(() => uniqueStockOptions(stockRows, "taxId", "taxName"), [stockRows]);
  const inventoryWarehouseOptions = useMemo(() => uniqueStockOptions(stockRows, "warehouseId", "warehouseName"), [stockRows]);
  const calendarLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const calendarTitle = new Intl.DateTimeFormat(calendarLocale, { month: "long", year: "numeric" }).format(calendarMonth);
  const selectedViewLabel = t(selectedView);
  const selectedViewSubtitle = selectedView === "stock.topSales" ? t("stock.subtitle.topSales") : t("stock.subtitle.inventory");
  const selectedColumnSettings = columnSettings[selectedView];
  const selectedColumnDefinitions = stockColumnDefinitions[selectedView];
  const selectedColumnDefinitionByKey = new Map(selectedColumnDefinitions.map((column) => [column.key, column]));
  const selectedGridStyle: CSSProperties = {
    gridTemplateColumns: stockColumnGridTemplate(selectedColumnSettings)
  };
  const selectedStockRow = visibleRows[selectedStockIndex] ?? visibleRows[0] ?? null;
  const detailStockRows = detailRow ? stockRows.filter((row) => row.productId === detailRow.productId) : [];

  useEffect(() => {
    setColumnSettings(loadStoredStockColumnSettings(app, session.username));
  }, [app, session.username]);

  useEffect(() => {
    saveStoredStockColumnSettings(app, session.username, columnSettings);
  }, [app, session.username, columnSettings]);

  useEffect(() => {
    setSelectedStockIndex((current) => {
      if (visibleRows.length === 0) {
        return 0;
      }
      return Math.min(current, visibleRows.length - 1);
    });
  }, [visibleRows.length, selectedView, searchText]);

  useEffect(() => {
    if (!detailRow) {
      return;
    }
    const row = detailRow;
    function handleDetailKey(event: globalThis.KeyboardEvent) {
      const action = stockDetailKeyAction(event.key);
      if (!action) {
        return;
      }
      event.preventDefault();
      if (action === "close") {
        setDetailRow(null);
      } else {
        setDetailTab(action);
        if (action === "edit") {
          setEditingProduct(stockRowToProductEdit(row));
          setProductCreateOpen(true);
        }
      }
    }
    window.addEventListener("keydown", handleDetailKey);
    return () => window.removeEventListener("keydown", handleDetailKey);
  }, [detailRow]);

  function updateDraftTopSalesFilter(key: keyof StockTopSalesFilters, value: string) {
    setDraftTopSalesFilters((current) => ({ ...current, [key]: value }));
  }

  function updateTopSalesSearch(value: string) {
    setTopSalesFilters((current) => ({ ...current, search: value }));
    setDraftTopSalesFilters((current) => ({ ...current, search: value }));
  }

  function updateDraftInventoryFilter<K extends keyof StockInventoryFilters>(key: K, value: StockInventoryFilters[K]) {
    setDraftInventoryFilters((current) => ({ ...current, [key]: value }));
  }

  function openTopSalesFilters() {
    setDraftTopSalesPeriod(topSalesPeriod);
    setDraftTopSalesDateFrom(topSalesDateFrom);
    setDraftTopSalesDateTo(topSalesDateTo);
    setDraftTopSalesFilters({ ...topSalesFilters, search: "" });
    setTopSalesDateText(formatStockDateRange(topSalesDateFrom, topSalesDateTo, locale));
    setCalendarMonth(startOfMonth(parseIsoDate(topSalesDateFrom) ?? new Date()));
    setTopSalesDateRangeStart(null);
    setTopSalesDatePickerOpen(false);
    setTopSalesFilterOpen(true);
  }

  function openInventoryFilters() {
    setDraftInventoryView(selectedView === "stock.topSales" ? "stock.current" : selectedView);
    setDraftInventoryFilters(inventoryFilters);
    setSelectedInventoryFamily(inventoryFilters.family);
    setInventoryDropdownOpen("");
    setInventoryFamilyPickerOpen(false);
    setInventoryFilterOpen(true);
  }

  function applyInventoryFilters() {
    setSelectedView(draftInventoryView);
    setInventoryFilters(draftInventoryFilters);
    setInventoryDropdownOpen("");
    setInventoryFamilyPickerOpen(false);
    setInventoryFilterOpen(false);
  }

  function clearInventoryFilters() {
    setDraftInventoryView("stock.current");
    setDraftInventoryFilters(defaultStockInventoryFilters);
    setSelectedInventoryFamily("");
  }

  function openStockDetail(row: StockInventoryRow | null, tab: StockDetailTab = "stock") {
    if (!row) {
      return;
    }
    setDetailRow(row);
    setDetailTab(tab);
    if (tab === "edit") {
      setEditingProduct(stockRowToProductEdit(row));
      setProductCreateOpen(true);
    }
  }

  function handleStockTableKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (selectedView === "stock.topSales") {
      return;
    }
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      setSelectedStockIndex((current) => nextStockSelectedIndex(current, visibleRows.length, event.key));
      return;
    }
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      if (stockTableRef.current) {
        stockTableRef.current.scrollLeft += event.key === "ArrowRight" ? 96 : -96;
      }
      return;
    }
    const action = stockDetailKeyAction(event.key);
    if (!action || action === "close") {
      return;
    }
    event.preventDefault();
    openStockDetail(selectedStockRow, action);
  }

  function clearTopSalesFilters() {
    const range = stockTopSalesPeriodRange("week");
    setDraftTopSalesPeriod("week");
    setDraftTopSalesDateFrom(range.dateFrom);
    setDraftTopSalesDateTo(range.dateTo);
    setDraftTopSalesFilters((current) => ({ ...current, family: "", subfamily: "", supplier: "" }));
    setTopSalesDateText(formatStockDateRange(range.dateFrom, range.dateTo, locale));
    setCalendarMonth(startOfMonth(new Date()));
    setTopSalesDateRangeStart(null);
    setSelectedFamily({ family: "", subfamily: "" });
  }

  function applyTopSalesFilters(
    nextFilters = draftTopSalesFilters,
    nextPeriod = draftTopSalesPeriod,
    nextDateFrom = draftTopSalesDateFrom,
    nextDateTo = draftTopSalesDateTo
  ) {
    setTopSalesPeriod(nextPeriod);
    setTopSalesDateFrom(nextDateFrom);
    setTopSalesDateTo(nextDateTo);
    setTopSalesFilters({ ...nextFilters, search: topSalesFilters.search });
    setTopSalesFilterOpen(false);
    setFamilyPickerOpen(false);
    setTopSalesDatePickerOpen(false);
    setTopSalesDateRangeStart(null);
  }

  function toggleFamily(familyId: string) {
    setExpandedFamilies((current) => ({ ...current, [familyId]: !current[familyId] }));
  }

  function chooseFamily(family: string, subfamily = "") {
    setSelectedFamily({ family, subfamily });
  }

  function applyFamilySelection(immediate = false) {
    const nextFilters = {
      ...draftTopSalesFilters,
      family: selectedFamily.family,
      subfamily: selectedFamily.subfamily
    };
    setDraftTopSalesFilters(nextFilters);
    setFamilyPickerOpen(false);
    if (immediate) {
      applyTopSalesFilters(nextFilters);
    }
  }

  function applyInventoryFamilySelection() {
    setDraftInventoryFilters((current) => ({ ...current, family: selectedInventoryFamily }));
    setInventoryFamilyPickerOpen(false);
  }

  function updateTopSalesDateText(value: string) {
    setTopSalesDateText(value);
    const range = parseStockDateRangeInput(value);
    if (range) {
      setDraftTopSalesPeriod("custom");
      setDraftTopSalesDateFrom(range.dateFrom);
      setDraftTopSalesDateTo(range.dateTo);
      setTopSalesDateRangeStart(null);
      const date = parseIsoDate(range.dateFrom);
      if (date) {
        setCalendarMonth(startOfMonth(date));
      }
    }
  }

  function applyTopSalesDateText() {
    const range = parseStockDateRangeInput(topSalesDateText);
    if (range) {
      setDraftTopSalesPeriod("custom");
      setDraftTopSalesDateFrom(range.dateFrom);
      setDraftTopSalesDateTo(range.dateTo);
      setTopSalesDateText(formatStockDateRange(range.dateFrom, range.dateTo, locale));
      setTopSalesDateRangeStart(null);
      const date = parseIsoDate(range.dateFrom);
      if (date) {
        setCalendarMonth(startOfMonth(date));
      }
    } else {
      setTopSalesDateText(formatStockDateRange(draftTopSalesDateFrom, draftTopSalesDateTo, locale));
    }
  }

  function selectTopSalesDate(date: Date) {
    const selected = todayIsoDate(date);
    setDraftTopSalesPeriod("custom");
    setCalendarMonth(startOfMonth(date));
    if (!topSalesDateRangeStart) {
      setTopSalesDateRangeStart(selected);
      setDraftTopSalesDateFrom(selected);
      setDraftTopSalesDateTo(selected);
      setTopSalesDateText(formatStockDateRange(selected, selected, locale));
      return;
    }
    const range = normalizeStockDateRange(topSalesDateRangeStart, selected);
    if (range) {
      setDraftTopSalesDateFrom(range.dateFrom);
      setDraftTopSalesDateTo(range.dateTo);
      setTopSalesDateText(formatStockDateRange(range.dateFrom, range.dateTo, locale));
    }
    setTopSalesDateRangeStart(null);
    setTopSalesDatePickerOpen(false);
  }

  function selectTopSalesQuickPeriod(period: StockTopSalesQuickPeriod) {
    const range = stockTopSalesPeriodRange(period);
    setDraftTopSalesPeriod(period);
    setDraftTopSalesDateFrom(range.dateFrom);
    setDraftTopSalesDateTo(range.dateTo);
    setTopSalesDateText(formatStockDateRange(range.dateFrom, range.dateTo, locale));
    setCalendarMonth(startOfMonth(parseIsoDate(range.dateFrom) ?? new Date()));
    setTopSalesDateRangeStart(null);
  }

  function moveCalendarMonth(offset: number) {
    setCalendarMonth((current) => new Date(current.getFullYear(), current.getMonth() + offset, 1));
  }

  function moveSelectedColumn(columnKey: string, direction: -1 | 1) {
    setColumnSettings((current) => moveStockColumn(current, selectedView, columnKey, direction));
  }

  function resizeSelectedColumn(columnKey: string, width: number) {
    setColumnSettings((current) => resizeStockColumn(current, selectedView, columnKey, width));
  }

  function startColumnResize(columnKey: string, startWidth: number, startX: number) {
    function moveColumn(event: PointerEvent) {
      resizeSelectedColumn(columnKey, startWidth + event.clientX - startX);
    }

    function stopResize() {
      window.removeEventListener("pointermove", moveColumn);
      window.removeEventListener("pointerup", stopResize);
    }

    window.addEventListener("pointermove", moveColumn);
    window.addEventListener("pointerup", stopResize);
  }

  function renderStockHeader() {
    return (
      <div className="stock-row stock-row-head" style={selectedGridStyle}>
        {selectedColumnSettings.map((column) => (
          <span className="stock-header-cell" key={column.key}>
            {t(selectedColumnDefinitionByKey.get(column.key)?.labelKey ?? column.key)}
            <button
              type="button"
              className="stock-column-resizer"
              aria-label={`${t("stock.columns.resize")} ${t(selectedColumnDefinitionByKey.get(column.key)?.labelKey ?? column.key)}`}
              onPointerDown={(event) => {
                event.preventDefault();
                startColumnResize(column.key, column.width, event.clientX);
              }}
            />
          </span>
        ))}
      </div>
    );
  }

  function renderInventoryFilterDropdown(
    name: keyof StockInventoryFilters,
    label: string,
    value: string,
    options: Array<{ value: string; label: string }>
  ) {
    const isOpen = inventoryDropdownOpen === name;
    const selectedLabel = options.find((option) => option.value === value)?.label ?? t("stock.filter.all");
    return (
      <div className="filter-field">
        <span>{label}</span>
        <button
          type="button"
          className="filter-select-button"
          aria-expanded={isOpen}
          onClick={() => {
            setInventoryFamilyPickerOpen(false);
            setInventoryDropdownOpen((current) => current === name ? "" : name);
          }}
        >
          <span>{selectedLabel}</span>
          <span className="filter-control-arrow">v</span>
        </button>
        {isOpen && (
          <div className="filter-popover product-select-popover">
            <button
              type="button"
              className={!value ? "selected" : ""}
              onClick={() => {
                updateDraftInventoryFilter(name, "" as StockInventoryFilters[typeof name]);
                setInventoryDropdownOpen("");
              }}
            >
              {t("stock.filter.all")}
            </button>
            {options.map((option) => (
              <button
                type="button"
                className={option.value === value ? "selected" : ""}
                key={option.value}
                onClick={() => {
                  updateDraftInventoryFilter(name, option.value as StockInventoryFilters[typeof name]);
                  setInventoryDropdownOpen("");
                }}
              >
                {option.label}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  function renderTopSalesCell(row: StockTopSalesRow, index: number, columnKey: string): ReactNode {
    if (columnKey === "ranking") {
      return <strong>{index + 1}</strong>;
    }
    if (columnKey === "code") {
      return <span>{row.code}</span>;
    }
    if (columnKey === "barcode") {
      return <span>{row.barcode}</span>;
    }
    if (columnKey === "name") {
      return <span>{row.name}</span>;
    }
    if (columnKey === "family") {
      return <span>{row.familyName}</span>;
    }
    if (columnKey === "subfamily") {
      return <span>{row.subfamilyName}</span>;
    }
    if (columnKey === "supplier") {
      return <span>{row.suppliers.map((supplier) => supplier.supplierName).join(", ") || "-"}</span>;
    }
    if (columnKey === "soldUnits") {
      return <b>{row.soldQuantity}</b>;
    }
    if (columnKey === "amount") {
      return <span>{row.netAmount}</span>;
    }
    if (columnKey === "currentStock") {
      return <span>{row.currentStock}</span>;
    }
    if (columnKey === "warehouse") {
      return <span>{row.warehouseName}</span>;
    }
    return <span />;
  }

  function renderInventoryCell(row: StockInventoryRow, columnKey: string): ReactNode {
    if (columnKey === "code") {
      return <strong>{row.code}</strong>;
    }
    if (columnKey === "barcode") {
      return <span>{row.barcode}</span>;
    }
    if (columnKey === "name") {
      return <span>{row.name}</span>;
    }
    if (columnKey === "type") {
      return <span>{row.productType === "-" ? row.productType : t(stockProductTypeLabel(row.productType))}</span>;
    }
    if (columnKey === "discount") {
      return <span>{row.discountType === "-" ? row.discountType : t(stockDiscountTypeLabel(row.discountType))}</span>;
    }
    if (columnKey === "family") {
      return <span>{row.familyName}</span>;
    }
    if (columnKey === "subfamily") {
      return <span>{row.subfamilyName}</span>;
    }
    if (columnKey === "tax") {
      return <span>{row.taxName}</span>;
    }
    if (columnKey === "taxIncluded") {
      return <span>{t(row.taxesIncluded)}</span>;
    }
    if (columnKey === "purchasePrice") {
      return <span>{row.purchasePrice}</span>;
    }
    if (columnKey === "salePrice") {
      return <span>{row.salePrice}</span>;
    }
    if (columnKey === "memberPrice") {
      return <span>{row.memberPrice}</span>;
    }
    if (columnKey === "wholesalePrice") {
      return <span>{row.wholesalePrice}</span>;
    }
    if (columnKey === "offerPrice") {
      return <span>{row.offerPrice}</span>;
    }
    if (columnKey === "offerActive") {
      return <span>{t(row.offerActive)}</span>;
    }
    if (columnKey === "offerFrom") {
      return <span>{row.offerFrom}</span>;
    }
    if (columnKey === "offerUntil") {
      return <span>{row.offerUntil}</span>;
    }
    if (columnKey === "warehouse") {
      return <span>{row.warehouseName}</span>;
    }
    if (columnKey === "localStock") {
      return <b>{row.quantity}</b>;
    }
    if (columnKey === "totalStock") {
      return <b>{row.totalQuantity}</b>;
    }
    if (columnKey === "status") {
      return <em>{t(stockInventoryStatus(row.quantity))}</em>;
    }
    return <span />;
  }

  function activeTopSalesSummary() {
    const items = [
      topSalesPeriod === "custom"
        ? formatStockDateRange(topSalesDateFrom, topSalesDateTo, locale)
        : `${t(stockTopSalesPeriodLabel(topSalesPeriod))} ${t("stock.summary.until")} ${formatStockTopSalesDate(topSalesDateTo)}`
    ];
    if (topSalesFilters.family) {
      items.push(`${t("stock.column.family")}: ${topSalesFilters.family}`);
    }
    if (topSalesFilters.subfamily) {
      items.push(`${t("stock.column.subfamily")}: ${topSalesFilters.subfamily}`);
    }
    if (topSalesFilters.supplier) {
      items.push(`${t("stock.column.supplier")}: ${topSalesFilters.supplier}`);
    }
    return items;
  }

  return (
    <main className="stock-screen work-screen">
      <SessionTopControls
        locale={locale}
        session={session}
        languageLabel={t("login.language")}
        shutdownLabel={t("login.shutdown")}
        changePasswordLabel={t("common.changePassword")}
        logoutLabel={t("common.logout")}
        shutdownConfirmTitle={t("login.shutdownConfirmTitle")}
        shutdownConfirmText={t("login.shutdownConfirmText")}
        noLabel={t("common.no")}
        yesLabel={t("common.yes")}
        onLocaleChange={onLocaleChange}
        onLogout={onLogout}
      />

      <section className="work-shell" aria-label={stockTitle}>
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{stockTitle}</h1>
        </header>

        <aside className="stock-nav">
          <strong>{stockTitle}</strong>
          {stockViews.map((view) => (
            <button
              type="button"
              className={selectedView === view ? "selected" : ""}
              key={view}
              onClick={() => setSelectedView(view)}
            >
              {t(view)}
            </button>
          ))}

          <strong className="stock-nav-section">{t("stock.settings")}</strong>
          <button type="button">{t("stock.settings.configuration")}</button>
          <button type="button">{t("stock.settings.permissions")}</button>

          <button type="button" className="report-back" onClick={onBack}>
            {t("common.back")}
          </button>
        </aside>

        <section className="stock-list work-panel" aria-label={selectedViewLabel}>
          <header className="work-panel-heading stock-panel-heading">
            <div>
              <h2>{selectedViewLabel}</h2>
              <span>{selectedViewSubtitle}</span>
            </div>
            <button
              type="button"
              className="stock-add-product-button"
              onClick={() => {
                setEditingProduct(null);
                setProductCreateOpen(true);
              }}
            >
              {t("product.create.button")}
            </button>
          </header>
          {selectedView === "stock.topSales" ? (
            <>
              <div className="stock-top-sales-toolbar">
                <div className="stock-search-stack">
                  <div className="active-filter-summary" aria-label={t("stock.filters.summary")}>
                    {activeTopSalesSummary().map((item) => (
                      <span key={item}>{item}</span>
                    ))}
                  </div>
                  <label className="report-search stock-top-sales-search">
                    <img alt="" src={stockSearchIcon} />
                    <input
                      type="search"
                      value={topSalesFilters.search}
                      aria-label={t("stock.search.topSales")}
                      placeholder={t("salesReport.search")}
                      onChange={(event) => updateTopSalesSearch(event.target.value)}
                    />
                  </label>
                </div>
                <button
                  type="button"
                  className="stock-filter-button"
                  aria-haspopup="dialog"
                  aria-label={t(stockFilterButtonLabelKey(selectedView))}
                  onClick={openTopSalesFilters}
                >
                  <img alt="" className="report-action-icon" src={stockFilterIcon} />
                  {t("salesReport.filter")}
                </button>
                <button type="button" className="stock-columns-button" onClick={() => setStockColumnsOpen(true)}>
                  {t("stock.columns")}
                </button>
              </div>
              <div className="stock-table stock-top-sales-table">
                {renderStockHeader()}
                {visibleTopSalesRows.length === 0 && <div className="stock-empty-state">{topSalesRows.length === 0 ? t(topSalesStatus) : t("stock.status.noResults")}</div>}
                {visibleTopSalesRows.map((row, index) => (
                  <article className="stock-row" key={row.productId} style={selectedGridStyle}>
                    {selectedColumnSettings.map((column) => (
                      <span className="stock-cell" key={column.key}>
                        {renderTopSalesCell(row, index, column.key)}
                      </span>
                    ))}
                  </article>
                ))}
              </div>
            </>
          ) : (
            <>
              <div className="stock-toolbar">
                <div className="stock-search-stack">
                  <div className="active-filter-summary" aria-label={t("stock.filters.summary")}>
                    <span>{selectedViewLabel}</span>
                  </div>
                  <label className="report-search stock-top-sales-search">
                    <img alt="" src={stockSearchIcon} />
                    <input
                      type="search"
                      placeholder={t("stock.search.articlePlaceholder")}
                      value={searchText}
                      aria-label={t("stock.search.article")}
                      onChange={(event) => setSearchText(event.target.value)}
                    />
                  </label>
                </div>
                <button
                  type="button"
                  className="stock-filter-button"
                  aria-haspopup="dialog"
                  aria-label={t(stockFilterButtonLabelKey(selectedView))}
                  onClick={openInventoryFilters}
                >
                  <img alt="" className="report-action-icon" src={stockFilterIcon} />
                  {t("salesReport.filter")}
                </button>
                <button type="button" className="stock-columns-button" onClick={() => setStockColumnsOpen(true)}>{t("stock.columns")}</button>
              </div>
              <div
                className="stock-table"
                ref={stockTableRef}
                tabIndex={0}
                onKeyDown={handleStockTableKeyDown}
                aria-label={t("stock.table.aria")}
              >
                {renderStockHeader()}
                {visibleRows.length === 0 && <div className="stock-empty-state">{stockRows.length === 0 ? t(status) : t("stock.status.noResults")}</div>}
                {visibleRows.map((row, index) => (
                  <article
                    className={`stock-row ${index === selectedStockIndex ? "selected" : ""}`}
                    key={`${row.productId}-${row.warehouseId}`}
                    style={selectedGridStyle}
                    onClick={() => setSelectedStockIndex(index)}
                    onDoubleClick={() => openStockDetail(row, "stock")}
                  >
                    {selectedColumnSettings.map((column) => (
                      <span className="stock-cell" key={column.key}>
                        {renderInventoryCell(row, column.key)}
                      </span>
                    ))}
                  </article>
                ))}
              </div>
            </>
          )}
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>

      {topSalesFilterOpen && (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-filter-title">
          <section className="filter-dialog stock-filter-dialog">
            <header className="filter-header">
              <h2 id="stock-filter-title">{t("stock.filter.title")}</h2>
              <button type="button" onClick={() => setTopSalesFilterOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="filter-grid">
              <div className="filter-field filter-wide">
                <span>{t("stock.filter.period")}</span>
                <div className="stock-periods" aria-label={t("stock.filter.period")}>
                  {stockTopSalesPeriods.map((period) => (
                    <button
                      type="button"
                      className={draftTopSalesPeriod === period ? "selected" : ""}
                      key={period}
                      onClick={() => selectTopSalesQuickPeriod(period)}
                    >
                      {t(stockTopSalesPeriodLabel(period))}
                    </button>
                  ))}
                </div>
              </div>
              <div className="filter-field">
                <span>{t("stock.filter.date")}</span>
                <div className="date-range-control">
                  <input
                    type="text"
                    value={topSalesDateText}
                    aria-label={t("stock.filter.date")}
                    placeholder={t("salesReport.filter.dateRangePlaceholder")}
                    onChange={(event) => updateTopSalesDateText(event.target.value)}
                    onBlur={applyTopSalesDateText}
                    onFocus={(event) => event.currentTarget.select()}
                    onClick={(event) => event.currentTarget.select()}
                    onMouseUp={(event) => event.preventDefault()}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        applyTopSalesDateText();
                      }
                    }}
                  />
                  <button
                    type="button"
                    aria-expanded={topSalesDatePickerOpen}
                    aria-label={t("salesReport.filter.openCalendar")}
                    onClick={() => setTopSalesDatePickerOpen((current) => !current)}
                  >
                    <span className="filter-control-arrow">v</span>
                  </button>
                </div>
                {topSalesDatePickerOpen && (
                  <div className="date-popover date-range-popover">
                    <p>{topSalesDateRangeStart ? t("salesReport.filter.pickDateTo") : t("salesReport.filter.pickDateFrom")}</p>
                    <header className="date-calendar-header">
                      <button type="button" onClick={() => moveCalendarMonth(-1)}>
                        {"<"}
                      </button>
                      <strong>{calendarTitle}</strong>
                      <button type="button" onClick={() => moveCalendarMonth(1)}>
                        {">"}
                      </button>
                    </header>
                    <div className="date-calendar-grid">
                      {stockWeekdayLabels(locale).map((weekday) => (
                        <span className="date-weekday" key={weekday}>
                          {weekday}
                        </span>
                      ))}
                      {buildStockCalendarDays(calendarMonth).map((day, index) =>
                        day ? (
                          <button
                            type="button"
                            className={[
                              "date-day",
                              todayIsoDate(day) === draftTopSalesDateFrom || todayIsoDate(day) === draftTopSalesDateTo ? "selected" : "",
                              todayIsoDate(day) > draftTopSalesDateFrom && todayIsoDate(day) < draftTopSalesDateTo ? "in-range" : ""
                            ].filter(Boolean).join(" ")}
                            key={todayIsoDate(day)}
                            onClick={() => selectTopSalesDate(day)}
                          >
                            {day.getDate()}
                          </button>
                        ) : (
                          <span className="date-day empty" key={`empty-${index}`} />
                        )
                      )}
                    </div>
                  </div>
                )}
              </div>
              <div className="filter-field">
                <span>{t("stock.column.family")}</span>
                <button
                  type="button"
                  className="filter-select-button"
                  aria-expanded={familyPickerOpen}
                  onClick={() => {
                    setSelectedFamily({ family: draftTopSalesFilters.family, subfamily: draftTopSalesFilters.subfamily });
                    setFamilyPickerOpen(true);
                  }}
                >
                  <span>{draftTopSalesFilters.subfamily || draftTopSalesFilters.family || t("stock.filter.all")}</span>
                  <span className="filter-control-arrow">v</span>
                </button>
              </div>
              <label>
                <span>{t("stock.column.subfamily")}</span>
                <input value={draftTopSalesFilters.subfamily} onChange={(event) => updateDraftTopSalesFilter("subfamily", event.target.value)} />
              </label>
              <label>
                <span>{t("stock.column.supplier")}</span>
                <input value={draftTopSalesFilters.supplier} onChange={(event) => updateDraftTopSalesFilter("supplier", event.target.value)} />
              </label>
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={clearTopSalesFilters}>{t("salesReport.filter.clear")}</button>
              <button type="button" onClick={() => applyTopSalesFilters()}>{t("salesReport.filter.apply")}</button>
            </footer>
          </section>
        </div>
      )}

      {inventoryFilterOpen && (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-inventory-filter-title">
          <section className="filter-dialog stock-filter-dialog">
            <header className="filter-header">
              <h2 id="stock-inventory-filter-title">{t("stock.filter.inventoryTitle")}</h2>
              <button type="button" onClick={() => setInventoryFilterOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="filter-grid">
              <div className="filter-field filter-wide">
                <span>{t("stock.filter.inventoryView")}</span>
                <div className="stock-periods" aria-label={t("stock.filter.inventoryView")}>
                  {stockViews.filter((view) => view !== "stock.topSales").map((view) => (
                    <button
                      type="button"
                      className={draftInventoryView === view ? "selected" : ""}
                      key={view}
                      onClick={() => setDraftInventoryView(view)}
                    >
                      {t(view)}
                    </button>
                  ))}
                </div>
              </div>
              {renderInventoryFilterDropdown(
                "type",
                t("stock.column.type"),
                draftInventoryFilters.type,
                stockProductTypeOptions.map((type) => ({ value: type, label: t(stockProductTypeLabel(type)) }))
              )}
              {renderInventoryFilterDropdown(
                "discount",
                t("product.field.usePrice"),
                draftInventoryFilters.discount,
                stockDiscountTypeOptions.map((type) => ({ value: type, label: t(stockDiscountTypeLabel(type)) }))
              )}
              <div className="filter-field">
                <span>{t("stock.column.family")}</span>
                <button
                  type="button"
                  className="filter-select-button"
                  aria-expanded={inventoryFamilyPickerOpen}
                  onClick={() => {
                    setInventoryDropdownOpen("");
                    setSelectedInventoryFamily(draftInventoryFilters.family);
                    setInventoryFamilyPickerOpen(true);
                  }}
                >
                  <span>{inventoryFamilyTree.find((family) => family.id === draftInventoryFilters.family)?.name ?? t("stock.filter.all")}</span>
                  <span className="filter-control-arrow">v</span>
                </button>
              </div>
              {renderInventoryFilterDropdown(
                "tax",
                t("stock.column.tax"),
                draftInventoryFilters.tax,
                inventoryTaxOptions
              )}
              {renderInventoryFilterDropdown(
                "offerActive",
                t("stock.column.offerActive"),
                draftInventoryFilters.offerActive,
                [
                  { value: "yes", label: t("common.yes") },
                  { value: "no", label: t("common.no") }
                ]
              )}
              {renderInventoryFilterDropdown(
                "warehouse",
                t("stock.column.warehouse"),
                draftInventoryFilters.warehouse,
                inventoryWarehouseOptions
              )}
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={clearInventoryFilters}>{t("salesReport.filter.clear")}</button>
              <button type="button" onClick={applyInventoryFilters}>{t("salesReport.filter.apply")}</button>
            </footer>
          </section>
        </div>
      )}

      {inventoryFamilyPickerOpen && (
        <div className="filter-overlay stock-family-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-inventory-family-title">
          <section className="filter-dialog stock-family-dialog">
            <header className="filter-header">
              <h2 id="stock-inventory-family-title">{t("stock.column.family")}</h2>
              <button type="button" onClick={() => setInventoryFamilyPickerOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="stock-family-list">
              {inventoryFamilyTree.length === 0 && <p>{t("stock.filter.noFamilies")}</p>}
              {inventoryFamilyTree.map((family) => {
                const selected = selectedInventoryFamily === family.id;
                return (
                  <div className="stock-family-group" key={family.id}>
                    <div className={`stock-family-row ${selected ? "selected" : ""}`}>
                      <button type="button" className="stock-family-expand" disabled>
                        {""}
                      </button>
                      <button
                        type="button"
                        className="stock-family-choice"
                        onClick={() => setSelectedInventoryFamily(family.id)}
                        onDoubleClick={() => {
                          setDraftInventoryFilters((current) => ({ ...current, family: family.id }));
                          setInventoryFamilyPickerOpen(false);
                        }}
                      >
                        {family.name}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={() => setSelectedInventoryFamily("")}>{t("salesReport.filter.clear")}</button>
              <button type="button" onClick={applyInventoryFamilySelection}>{t("stock.filter.apply")}</button>
            </footer>
          </section>
        </div>
      )}

      {stockColumnsOpen && (
        <div className="visualization-overlay stock-columns-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-columns-title">
          <section className="visualization-dialog stock-columns-dialog">
            <header className="visualization-header">
              <h2 id="stock-columns-title">{t("stock.columns.title")}</h2>
              <button type="button" onClick={() => setStockColumnsOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="stock-column-editor">
              {selectedColumnSettings.map((column, index) => {
                const definition = selectedColumnDefinitionByKey.get(column.key);
                const label = t(definition?.labelKey ?? column.key);
                return (
                  <div className="stock-column-editor-row" key={column.key}>
                    <strong>{label}</strong>
                    <div className="attribute-actions">
                      <button
                        type="button"
                        aria-label={t("salesReport.moveUp")}
                        title={t("salesReport.moveUp")}
                        disabled={index === 0}
                        onClick={() => moveSelectedColumn(column.key, -1)}
                      >
                        {"^"}
                      </button>
                      <button
                        type="button"
                        aria-label={t("salesReport.moveDown")}
                        title={t("salesReport.moveDown")}
                        disabled={index === selectedColumnSettings.length - 1}
                        onClick={() => moveSelectedColumn(column.key, 1)}
                      >
                        {"v"}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        </div>
      )}

      {familyPickerOpen && (
        <div className="filter-overlay stock-family-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-family-title">
          <section className="filter-dialog stock-family-dialog">
            <header className="filter-header">
              <h2 id="stock-family-title">{t("stock.column.family")}</h2>
              <button type="button" onClick={() => setFamilyPickerOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="stock-family-list">
              {familyTree.length === 0 && <p>{t("stock.filter.noFamilies")}</p>}
              {familyTree.map((family) => {
                const selected = selectedFamily.family === family.name && selectedFamily.subfamily === "";
                const expanded = expandedFamilies[family.id] ?? family.subfamilies.length > 0;
                return (
                  <div className="stock-family-group" key={family.id}>
                    <div className={`stock-family-row ${selected ? "selected" : ""}`}>
                      <button type="button" className="stock-family-expand" disabled={family.subfamilies.length === 0} onClick={() => toggleFamily(family.id)}>
                        {family.subfamilies.length > 0 ? (expanded ? "v" : ">") : ""}
                      </button>
                      <button
                        type="button"
                        className="stock-family-choice"
                        onClick={() => chooseFamily(family.name)}
                        onDoubleClick={() => {
                          chooseFamily(family.name);
                          applyTopSalesFilters({ ...draftTopSalesFilters, family: family.name, subfamily: "" });
                        }}
                      >
                        {family.name}
                      </button>
                    </div>
                    {expanded && family.subfamilies.length > 0 && (
                      <div className="stock-subfamily-list">
                        {family.subfamilies.map((subfamily) => {
                          const subfamilySelected = selectedFamily.family === family.name && selectedFamily.subfamily === subfamily.name;
                          return (
                            <button
                              type="button"
                              className={subfamilySelected ? "selected" : ""}
                              key={subfamily.id}
                              onClick={() => chooseFamily(family.name, subfamily.name)}
                              onDoubleClick={() => {
                                chooseFamily(family.name, subfamily.name);
                                applyTopSalesFilters({ ...draftTopSalesFilters, family: family.name, subfamily: subfamily.name });
                              }}
                            >
                              {subfamily.name}
                            </button>
                          );
                        })}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={() => chooseFamily("", "")}>{t("salesReport.filter.clear")}</button>
              <button type="button" onClick={() => applyFamilySelection(true)}>{t("stock.filter.apply")}</button>
            </footer>
          </section>
        </div>
      )}

      {detailRow && (
        <div className="filter-overlay stock-detail-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-detail-title">
          <section className="filter-dialog stock-detail-dialog">
            <header className="filter-header">
              <div>
                <h2 id="stock-detail-title">{detailRow.name}</h2>
                <span>{detailRow.code}</span>
              </div>
              <button type="button" onClick={() => setDetailRow(null)}>{t("common.close")}</button>
            </header>
            <div className="stock-detail-tabs" role="tablist">
              <button type="button" className={detailTab === "stock" ? "selected" : ""} onClick={() => setDetailTab("stock")}>
                {t("stock.detail.stockTab")}
              </button>
              <button type="button" className={detailTab === "sales" ? "selected" : ""} onClick={() => setDetailTab("sales")}>
                {t("stock.detail.salesTab")}
              </button>
              <button
                type="button"
                className={detailTab === "edit" ? "selected" : ""}
                onClick={() => {
                  setDetailTab("edit");
                  setEditingProduct(stockRowToProductEdit(detailRow));
                  setProductCreateOpen(true);
                }}
              >
                {t("stock.detail.editTab")}
              </button>
            </div>
            {detailTab === "stock" && (
              <div className="stock-detail-table">
                <div className="stock-detail-row header">
                  <span>{t("stock.column.warehouse")}</span>
                  <span>{t("stock.column.localStock")}</span>
                </div>
                {detailStockRows.map((row) => (
                  <div className="stock-detail-row" key={`${row.productId}-${row.warehouseId}`}>
                    <span>{row.warehouseName}</span>
                    <strong>{row.quantity}</strong>
                  </div>
                ))}
                <div className="stock-detail-row total">
                  <span>{t("stock.column.totalStock")}</span>
                  <strong>{detailRow.totalQuantity}</strong>
                </div>
              </div>
            )}
            {detailTab === "sales" && (
              <div className="stock-empty-state">{t("stock.detail.salesEmpty")}</div>
            )}
            {detailTab === "edit" && (
              <div className="stock-empty-state">{t("stock.detail.editHint")}</div>
            )}
          </section>
        </div>
      )}

      <ProductCreateDialog
        open={productCreateOpen}
        locale={locale}
        token={session.accessToken}
        editProduct={editingProduct}
        onClose={() => {
          setProductCreateOpen(false);
          setEditingProduct(null);
        }}
        onCreated={() => {
          setSelectedView((current) => stockViewAfterProductCreated(current));
          setStockRefreshCounter((current) => current + 1);
        }}
      />
    </main>
  );
}

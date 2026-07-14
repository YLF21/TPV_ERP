import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, DragEvent, KeyboardEvent, ReactNode } from "react";
import { ApiError, apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ProductCreateDialog } from "./ProductCreateDialog";
import { PartyDirectoryPanel } from "./PartyDirectoryPanel";
import type { PartyDirectoryKind } from "./PartyDirectoryPanel";
import type { ProductCreateEditProduct, ProductCreateFormState } from "./ProductCreateDialog";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import type {
  WarehouseCustomerOption,
  WarehouseDocumentMode,
  WarehouseOption,
  WarehouseSupplierOption
} from "./WarehouseDocumentDialog";
import { WarehouseOperationsPanel } from "./WarehouseOperationsPanel";
import type { WarehouseImportProduct } from "./warehouseDocumentImport";
import { StockSalesHistoryPanel } from "./StockSalesHistoryPanel";
import { StockSettingsDialog } from "./StockSettingsDialog";
import type { StockSettingsMode, StockSettingsView } from "./StockSettingsDialog";
import { StockPermissionsDialog } from "./StockPermissionsDialog";
import {
  buildStockBulkSupplierAssignments,
  buildStockBulkUpdates,
  finalizeStockBulkSupplierAssignments,
  hydrateStockBulkSupplierData,
  importStockBulkFile,
  mergeStockBulkPurchaseDocumentProducts,
  mergeStockBulkSupplierProducts,
  requestStockBulkXlsx,
  stockBulkEffectiveProduct,
  stockOfferPriceFromDiscount,
  stockBulkVersionedDeletePath,
  validateStockBulkRows
} from "./stockBulkEdit";
import type {
  StockBulkDraftView,
  StockBulkEditRowData,
  StockBulkPurchaseDocumentLine,
  StockBulkSupplierInfo,
  StockBulkSupplierProductLink,
  StockBulkStoreSupplierLink,
  StockBulkValidationError,
  StockBulkValidationField
} from "./stockBulkEdit";
import {
  applyStockBulkDecimalEnding,
  countActiveStockBulkFilters,
  emptyStockBulkFilterCriteria,
  filterStockBulkRows,
  mergeStockBulkFamilyProducts
} from "./stockBulkAdvanced";
import type { StockBulkFilterCriteria } from "./stockBulkAdvanced";
import { StockBulkFilterDialog } from "./StockBulkFilterDialog";
import { StockBulkFamilyDialog } from "./StockBulkFamilyDialog";
import { StockBulkDecimalDialog } from "./StockBulkDecimalDialog";
import {
  cloneStockBulkImageSnapshot,
  loadStockBulkDraftImages,
  stockBulkImagePendingAssignments,
  StockBulkImagePanel,
  syncStockBulkDraftImages
} from "./StockBulkImagePanel";
import type { StockBulkImagePanelHandle, StockBulkImageSnapshot } from "./StockBulkImagePanel";
import { StockBulkPriceRulesDialog } from "./StockBulkPriceRulesDialog";
import { StockBulkWorkspaceList } from "./StockBulkWorkspaceList";
import { StockPromotionGroups } from "./StockPromotionGroups";
import type { PromotionView } from "./PromotionForm";
import { ErpSelect } from "./ErpSelect";
import { excelImportAccept } from "./excelImport";
import { enterNavigationIntent, nextEnterTargetIndex } from "./keyboardNavigation";
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
type StockBulkEditTab = "main" | "info" | "salePrice" | "memberPrice" | "wholesalePrice" | "offer" | "image";
export type BulkPriceUseMode = "NORMAL" | "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT";
type BulkWorkspaceDialog = "save" | "comments" | "rename" | "clear" | "close" | "apply" | "delete" | null;
type BulkWorkspaceView = "list" | "editor";
type BulkSupplierDialogMode = "import" | "assign" | null;
type BulkValueField =
  | "purchasePrice"
  | "salePrice"
  | "memberPrice"
  | "wholesalePrice"
  | "offerPrice"
  | "offerDiscountPercent";
type BulkSelectedAction =
  | "supplier"
  | "family"
  | BulkValueField
  | "benefit"
  | "priceUse"
  | "activateOffer"
  | "deactivateOffer"
  | "offerDates"
  | "tax"
  | "taxesIncludedYes"
  | "taxesIncludedNo";
type BulkEditorDialog =
  | { kind: "value"; rowIds: string[]; field: BulkValueField; labelKey: string; value: string }
  | { kind: "benefit"; rowIds: string[]; priceField: "salePrice" | "memberPrice" | "wholesalePrice" | "offerPrice"; value: string }
  | { kind: "family"; rowIds: string[]; familyId: string; subfamilyId: string }
  | { kind: "tax"; rowIds: string[]; taxId: string }
  | { kind: "priceUse"; rowIds: string[]; value: BulkPriceUseMode; options: BulkPriceUseMode[] }
  | {
      kind: "dates";
      rowIds: string[];
      offerFrom: string;
      offerUntil: string;
      rangeStart: string | null;
      calendarMonth: Date;
    }
  | null;
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
  onOpenDocument?: (documentId: string, documentType: string) => void | Promise<void>;
};

type StockItemView = {
  productId: string;
  warehouseId: string;
  quantity: number;
};

type ProductView = {
  id: string;
  version?: number | null;
  imageId?: string | null;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  description?: string | null;
  comments?: string | null;
  purchasePrice?: number | string | null;
  purchaseDiscountPercent?: number | string | null;
  packageQuantity?: number | string | null;
  stockMin?: number | string | null;
  stockMax?: number | string | null;
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
  active?: boolean;
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

type SupplierOptionView = StockBulkSupplierInfo & {
  documentType: string;
};

type PurchaseDocumentKind = "invoice" | "deliveryNote";

type PurchaseDocumentOptionView = {
  id: string;
  number?: string | null;
  date: string;
  status: string;
  supplierId?: string | null;
  supplierName?: string | null;
  total: number | string;
  productCount: number;
};

const bulkPurchaseDocumentConfig = {
  invoice: {
    path: "purchase-invoices",
    titleKey: "stock.bulkEdit.importPurchaseInvoice",
    searchKey: "stock.bulkEdit.searchPurchaseInvoice",
    emptyKey: "stock.bulkEdit.noPurchaseInvoices",
    loadErrorKey: "stock.bulkEdit.purchaseInvoiceLoadError",
    noProductsKey: "stock.bulkEdit.purchaseInvoiceNoProducts",
    importedKey: "stock.bulkEdit.purchaseInvoiceImported",
    importErrorKey: "stock.bulkEdit.purchaseInvoiceImportError"
  },
  deliveryNote: {
    path: "purchase-delivery-notes",
    titleKey: "stock.bulkEdit.importPurchaseDeliveryNote",
    searchKey: "stock.bulkEdit.searchPurchaseDeliveryNote",
    emptyKey: "stock.bulkEdit.noPurchaseDeliveryNotes",
    loadErrorKey: "stock.bulkEdit.purchaseDeliveryNoteLoadError",
    noProductsKey: "stock.bulkEdit.purchaseDeliveryNoteNoProducts",
    importedKey: "stock.bulkEdit.purchaseDeliveryNoteImported",
    importErrorKey: "stock.bulkEdit.purchaseDeliveryNoteImportError"
  }
} as const;

export const stockBulkFileMenuItems = [
  "stock.bulkEdit.openList",
  "stock.bulkEdit.clearList",
  "stock.bulkEdit.undo",
  "stock.bulkEdit.saveList",
  "stock.bulkEdit.applyChanges",
  "stock.bulkEdit.comments",
  "stock.bulkEdit.import",
  "stock.bulkEdit.exportExcel",
  "stock.bulkEdit.closeList"
] as const;

export const stockBulkImportMenuItems = [
  "stock.bulkEdit.importExcel",
  "stock.bulkEdit.importSupplier",
  "stock.bulkEdit.importPurchaseInvoice",
  "stock.bulkEdit.importPurchaseDeliveryNote",
  "stock.bulkEdit.importFamilies"
] as const;

export type StockInventoryRow = {
  productId: string;
  version?: number;
  imageId?: string | null;
  warehouseId: string;
  code: string;
  barcode: string;
  barcode2?: string | null;
  name: string;
  description?: string;
  comments?: string;
  purchasePrice: string;
  purchaseDiscountPercent?: string;
  packageQuantity?: string;
  stockMin?: string;
  stockMax?: string;
  supplierName?: string;
  salePrice: string;
  memberPrice: string;
  wholesalePrice: string;
  offerPrice: string;
  offerDiscountPercent?: string;
  productType: string;
  discountType: string;
  backendDiscountType?: string;
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
  promotionNames?: string;
  promotionTypes?: string;
  promotionStatuses?: string;
  promotionValidity?: string;
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
  warehouse?: string;
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
  visible?: boolean;
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
const bulkPriceUseModes: BulkPriceUseMode[] = ["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"];
export const stockBulkSelectedActionsByTab: Record<StockBulkEditTab, BulkSelectedAction[]> = {
  main: [
    "supplier", "family", "purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice",
    "offerDiscountPercent", "priceUse", "activateOffer", "deactivateOffer", "offerDates", "tax",
    "taxesIncludedYes", "taxesIncludedNo"
  ],
  info: ["family", "tax", "taxesIncludedYes", "taxesIncludedNo"],
  salePrice: ["family", "purchasePrice", "salePrice", "benefit", "priceUse"],
  memberPrice: ["family", "purchasePrice", "memberPrice", "benefit", "priceUse"],
  wholesalePrice: ["family", "purchasePrice", "wholesalePrice", "benefit", "priceUse"],
  offer: [
    "family", "purchasePrice", "offerPrice", "offerDiscountPercent", "benefit", "priceUse", "activateOffer",
    "deactivateOffer", "offerDates"
  ],
  image: ["supplier"]
};
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
  { key: "supplier", labelKey: "stock.column.supplier", defaultWidth: 180 },
  { key: "family", labelKey: "stock.column.family", defaultWidth: 180 },
  { key: "subfamily", labelKey: "stock.column.subfamily", defaultWidth: 180 },
  { key: "tax", labelKey: "stock.column.tax", defaultWidth: 180 },
  { key: "taxIncluded", labelKey: "stock.column.taxIncluded", defaultWidth: 82 },
  { key: "packageQuantity", labelKey: "stock.column.packageQuantity", defaultWidth: 112 },
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
  { key: "stockMin", labelKey: "stock.column.stockMin", defaultWidth: 92 },
  { key: "stockMax", labelKey: "stock.column.stockMax", defaultWidth: 92 },
  { key: "status", labelKey: "stock.column.status", defaultWidth: 180 }
];

const stockPromotionColumns: StockColumnDefinition[] = [
  { key: "code", labelKey: "stock.column.code", defaultWidth: 112 },
  { key: "barcode", labelKey: "stock.column.barcode", defaultWidth: 132 },
  { key: "name", labelKey: "stock.column.name", defaultWidth: 210 },
  { key: "family", labelKey: "stock.column.family", defaultWidth: 130 },
  { key: "subfamily", labelKey: "stock.column.subfamily", defaultWidth: 130 },
  { key: "promotion", labelKey: "stock.column.promotion", defaultWidth: 220 },
  { key: "promotionType", labelKey: "stock.column.promotionType", defaultWidth: 150 },
  { key: "promotionStatus", labelKey: "stock.column.promotionStatus", defaultWidth: 120 },
  { key: "promotionValidity", labelKey: "stock.column.promotionValidity", defaultWidth: 180 },
  { key: "warehouse", labelKey: "stock.column.warehouse", defaultWidth: 140 },
  { key: "stock", labelKey: "stock.column.stock", defaultWidth: 86 }
];

const stockColumnDefinitions: Record<StockViewKey, StockColumnDefinition[]> = {
  "stock.topSales": stockTopSalesColumns,
  "stock.current": stockInventoryColumns,
  "stock.offers": stockInventoryColumns,
  "stock.memberPrice": stockInventoryColumns,
  "stock.promotions": stockPromotionColumns,
  "stock.noDiscount": stockInventoryColumns,
  "stock.bulkEdit": stockInventoryColumns
};

export type StockColumnSettingsByView = Record<StockViewKey, StockColumnSetting[]>;

type StockColumnPreferenceView = {
  app: AppKind;
  settings: Partial<Record<StockViewKey, StockColumnSetting[]>>;
};

type StockTableFocusState = {
  inventoryFilterOpen: boolean;
  productCreateOpen: boolean;
  stockColumnsOpen: boolean;
  topSalesFilterOpen: boolean;
};

type StockBulkEditRow = StockBulkEditRowData;

type PendingBulkFocus = {
  rowId: string;
  scrollTop: number;
  scrollLeft: number;
};

function ProductThumbnail({ product, token, className = "" }: {
  product?: StockInventoryRow;
  token: string;
  className?: string;
}) {
  const [source, setSource] = useState("");

  useEffect(() => {
    if (!product?.imageId || !token) {
      setSource("");
      return;
    }
    let active = true;
    let objectUrl = "";
    void fetch(`${apiBaseUrl}/products/${encodeURIComponent(product.productId)}/image?thumbnail=true`, {
      headers: { Authorization: `Bearer ${token}` }
    }).then((response) => {
      if (!response.ok) {
        throw new Error("product_image_unavailable");
      }
      return response.blob();
    }).then((blob) => {
      if (!active) {
        return;
      }
      objectUrl = URL.createObjectURL(blob);
      setSource(objectUrl);
    }).catch(() => {
      if (active) {
        setSource("");
      }
    });
    return () => {
      active = false;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [product?.imageId, product?.productId, token]);

  const fallback = product?.name?.slice(0, 1).toLocaleUpperCase() || "-";
  return (
    <div className={`bulk-image ${className}`.trim()}>
      {source ? <img src={source} alt={product?.name ?? ""} /> : <span>{fallback}</span>}
    </div>
  );
}

function LocalImagePreview({ file }: { file: File }) {
  const [source, setSource] = useState("");

  useEffect(() => {
    const objectUrl = URL.createObjectURL(file);
    setSource(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file]);

  return (
    <div className="bulk-image new">
      {source ? <img src={source} alt={file.name} /> : <span>+</span>}
    </div>
  );
}

function valueText(value: unknown) {
  return value === null || value === undefined || value === "" ? "" : String(value);
}

function decimalNumber(value: unknown) {
  const parsed = Number(String(value ?? "").trim().replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

export function stockBenefitPercent(purchasePrice: number, salePrice: number) {
  if (!Number.isFinite(purchasePrice) || !Number.isFinite(salePrice) || salePrice <= 0) {
    return 0;
  }
  return ((salePrice - purchasePrice) / salePrice) * 100;
}

export function stockPriceFromBenefit(purchasePrice: number, benefitPercent: number) {
  if (!Number.isFinite(purchasePrice) || !Number.isFinite(benefitPercent) || benefitPercent >= 100) {
    return null;
  }
  return purchasePrice / (1 - benefitPercent / 100);
}

export function stockPriceBelowCost(price: unknown, purchasePrice: unknown) {
  const normalizedPrice = Number(String(price ?? "").replace(",", "."));
  const normalizedPurchasePrice = Number(String(purchasePrice ?? "").replace(",", "."));
  return Number.isFinite(normalizedPrice)
    && Number.isFinite(normalizedPurchasePrice)
    && normalizedPrice < normalizedPurchasePrice;
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
    width: column.defaultWidth,
    visible: true
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
      columns.push({ key: column.key, width: clampStockColumnWidth(column.width), visible: column.visible !== false });
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

export function reorderStockColumn(settings: StockColumnSettingsByView, view: StockViewKey, columnKey: string, targetKey: string): StockColumnSettingsByView {
  if (columnKey === targetKey) {
    return settings;
  }
  const columns = [...settings[view]];
  const from = columns.findIndex((column) => column.key === columnKey);
  const to = columns.findIndex((column) => column.key === targetKey);
  if (from < 0 || to < 0) {
    return settings;
  }
  const [column] = columns.splice(from, 1);
  columns.splice(to, 0, column);
  return { ...settings, [view]: columns };
}

export function toggleStockColumnVisibility(settings: StockColumnSettingsByView, view: StockViewKey, columnKey: string): StockColumnSettingsByView {
  const visibleCount = settings[view].filter((column) => column.visible !== false).length;
  return {
    ...settings,
    [view]: settings[view].map((column) => {
      if (column.key !== columnKey) {
        return column;
      }
      const nextVisible = column.visible === false;
      if (!nextVisible && visibleCount <= 1) {
        return column;
      }
      return { ...column, visible: nextVisible };
    })
  };
}

export function visibleStockColumns(columns: StockColumnSetting[]) {
  const visible = columns.filter((column) => column.visible !== false);
  return visible.length > 0 ? visible : columns.slice(0, 1);
}

export function stockColumnGridTemplate(columns: StockColumnSetting[]) {
  return visibleStockColumns(columns).map((column) => `${column.width}px`).join(" ");
}

export function stockTableShouldAutoFocus(selectedView: StockViewKey, state: StockTableFocusState) {
  return selectedView !== "stock.topSales"
    && !state.inventoryFilterOpen
    && !state.productCreateOpen
    && !state.stockColumnsOpen
    && !state.topSalesFilterOpen;
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

export function stockBulkShortcutAction(
  key: string,
  modifiers: { ctrlKey: boolean; altKey?: boolean; metaKey?: boolean; editingText?: boolean }
) {
  if (!modifiers.ctrlKey || modifiers.altKey || modifiers.metaKey) {
    return null;
  }
  const normalized = key.toLocaleLowerCase();
  if (normalized === "s") return "save" as const;
  if (normalized === "z") return "undo" as const;
  if (normalized === "a") return "open" as const;
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

export function backendDiscountTypeForPriceUse(mode: BulkPriceUseMode, originalDiscountType: string) {
  if (mode === "NORMAL") {
    return originalDiscountType === "NONE" ? "NONE" : "NORMAL";
  }
  return mode === "MEMBER_PRICE" ? "MEMBER_PRICE" : "DISCOUNT_PRICE";
}

export function stockRowToProductEdit(row: StockInventoryRow): ProductCreateEditProduct {
  const priceUseMode = priceUseModeForForm(row.discountType);
  const backendDiscountType = backendDiscountTypeForPriceUse(
    priceUseMode,
    row.backendDiscountType ?? row.discountType
  );
  return {
    id: row.productId,
    initialData: {
      discountType: discountTypeForForm(backendDiscountType),
      purchaseDiscountPercent: row.purchaseDiscountPercent || null,
      packageQuantity: row.packageQuantity || null,
      stockMin: row.stockMin || null,
      stockMax: row.stockMax || null
    },
    form: {
      familyId: row.familyId,
      subfamilyId: row.subfamilyId,
      taxId: row.taxId,
      productType: productTypeForForm(row.productType),
      priceUseMode,
      discountType: discountTypeForForm(backendDiscountType),
      name: row.name,
      description: row.description ?? "",
      comments: row.comments ?? "",
      purchasePrice: row.purchasePrice || "0.00",
      taxesIncluded: row.taxesIncluded === "common.yes",
      code: row.code,
      barcode: row.barcode,
      barcode2: row.barcode2 ?? "",
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

export function userCanManageStockProducts(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_PRODUCTO");
}

export function userHasStockPermission(
  session: Pick<UserSession, "permissions">,
  ...permissions: UserSession["permissions"]
) {
  return userCanManageStockProducts(session)
    || permissions.some((permission) => session.permissions.includes(permission));
}

export function userCanReadStock(session: Pick<UserSession, "permissions">) {
  return userHasStockPermission(session, "STOCK_READ");
}

export function userCanCreateWarehouseInput(session: Pick<UserSession, "permissions">) {
  return userHasStockPermission(session, "WAREHOUSE_INPUTS_WRITE");
}

export function userCanCreateWarehouseOutput(session: Pick<UserSession, "permissions">) {
  return userHasStockPermission(session, "WAREHOUSE_OUTPUTS_EDIT");
}

export function userCanManageWarehouses(session: Pick<UserSession, "permissions">) {
  return userHasStockPermission(session, "WAREHOUSES_MANAGE");
}

export function stockNavigationStateForWarehouseMode(mode: WarehouseDocumentMode) {
  return {
    partyDirectory: null as PartyDirectoryKind | null,
    warehouseDocumentMode: mode
  };
}

export function stockViewIsSelected(
  selectedView: StockViewKey,
  view: StockViewKey,
  warehouseDocumentMode: WarehouseDocumentMode | null,
  partyDirectory: PartyDirectoryKind | null
) {
  return !warehouseDocumentMode && !partyDirectory && selectedView === view;
}

function uniqueProductRows(rows: StockInventoryRow[]) {
  const byProduct = new Map<string, StockInventoryRow>();
  rows.forEach((row) => {
    if (!byProduct.has(row.productId)) {
      byProduct.set(row.productId, row);
    }
  });
  return Array.from(byProduct.values());
}

function createEmptyBulkEditRow(index: number): StockBulkEditRow {
  return {
    id: `bulk-${Date.now()}-${index}`,
    selected: false,
    query: "",
    draft: {}
  };
}

function cloneBulkRows(rows: StockBulkEditRow[]) {
  return rows.map((row) => ({
    ...row,
    product: row.product ? { ...row.product } : undefined,
    draft: { ...row.draft },
    suppliers: row.suppliers?.map((supplier) => ({ ...supplier })),
    pendingSupplier: row.pendingSupplier ? { ...row.pendingSupplier } : undefined
  }));
}

export function normalizeStockBulkContent(rows: StockBulkEditRowData[]) {
  return cloneBulkRows(rows).map((row) => {
    if (!row.product) {
      return row;
    }
    const priceUseMode = priceUseModeForForm(valueText(row.draft.discountType ?? row.product.discountType));
    const backendDiscountType = backendDiscountTypeForPriceUse(
      priceUseMode,
      valueText(row.product.backendDiscountType ?? row.product.discountType)
    );
    return {
      ...row,
      draft: {
        ...row.draft,
        backendDiscountType
      }
    };
  });
}

export function stockBulkProductRowIds(rows: StockBulkEditRowData[]) {
  return rows.flatMap((row) => row.product ? [row.id] : []);
}

export function setAllStockBulkRowsSelected(rows: StockBulkEditRowData[], selected: boolean) {
  return rows.map((row) => row.product ? { ...row, selected } : row);
}

function withEmptyBulkTail(rows: StockBulkEditRow[]) {
  const cloned = cloneBulkRows(rows);
  return cloned.some((row) => !row.product && !row.query.trim())
    ? cloned
    : [...cloned, createEmptyBulkEditRow(cloned.length)];
}

function normalizeBulkQuery(value: string) {
  return value.trim().toLocaleLowerCase("es");
}

export function stockBulkEditMatches(products: StockInventoryRow[], query: string) {
  const normalized = normalizeBulkQuery(query);
  if (!normalized) {
    return [];
  }
  return products.filter((product) => [
    product.code,
    product.barcode,
    product.barcode2 ?? "",
    product.name,
    product.familyName,
    product.subfamilyName
  ].some((value) => value.toLocaleLowerCase("es").includes(normalized)));
}

export function stockBulkEditExactProduct(products: StockInventoryRow[], query: string) {
  const normalized = normalizeBulkQuery(query);
  if (!normalized) {
    return undefined;
  }
  return products.find((product) => [
    product.code,
    product.barcode,
    product.barcode2 ?? ""
  ].some((value) => value.toLocaleLowerCase("es") === normalized));
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
    promotions?: PromotionView[];
  } = {},
  selection: {
    mode?: "warehouse" | "total" | "all";
    warehouseId?: string | null;
  } = {}
): StockInventoryRow[] {
  const familiesById = new Map((catalog.families ?? []).map((family) => [family.id, family]));
  const subfamiliesById = new Map((catalog.subfamilies ?? []).map((subfamily) => [subfamily.id, subfamily]));
  const taxesById = new Map((catalog.taxes ?? []).map((tax) => [tax.id, tax]));
  const defaultFamily = catalog.families?.[0];
  const defaultTax = catalog.taxes?.[0];
  const activeWarehouses = warehouses.filter((warehouse) => warehouse.active !== false);
  const availableWarehouses = activeWarehouses.length > 0
    ? activeWarehouses
    : warehouses.length > 0
      ? warehouses
      : [{ id: "", name: "-", defaultWarehouse: true, active: true }];
  const defaultWarehouse = availableWarehouses.find((warehouse) => warehouse.defaultWarehouse)
    ?? availableWarehouses[0];
  const selectedWarehouse = availableWarehouses.find((warehouse) => warehouse.id === selection.warehouseId)
    ?? defaultWarehouse;
  const stockByProductAndWarehouse = stock.reduce<Map<string, number>>((values, item) => {
    const key = `${item.productId}\u0000${item.warehouseId}`;
    values.set(key, (values.get(key) ?? 0) + valueNumber(item.quantity));
    return values;
  }, new Map());
  const totalStockByProduct = stock.reduce<Map<string, number>>((totals, item) => {
    totals.set(item.productId, (totals.get(item.productId) ?? 0) + valueNumber(item.quantity));
    return totals;
  }, new Map());

  function productRow(product: ProductView, warehouseId: string, warehouseName: string, quantity: number): StockInventoryRow {
    const family = product.familyId ? familiesById.get(product.familyId) : defaultFamily;
    const subfamily = product.subfamilyId ? subfamiliesById.get(product.subfamilyId) : undefined;
    const tax = product.taxId ? taxesById.get(product.taxId) : defaultTax;
    const promotions = (catalog.promotions ?? []).filter((promotion) => (
      promotion.status === "ACTIVE" && promotionAppliesToProduct(promotion, product)
    ));
    return {
      productId: product.id,
      version: Number(product.version ?? 0),
      imageId: product.imageId ?? null,
      warehouseId,
      code: valueText(product.code),
      barcode: valueText(product.barcode),
      barcode2: valueText(product.barcode2),
      name: valueText(product.name ?? product.id),
      description: valueText(product.description),
      comments: valueText(product.comments),
      purchasePrice: valueText(product.purchasePrice),
      purchaseDiscountPercent: valueText(product.purchaseDiscountPercent),
      packageQuantity: valueText(product.packageQuantity ?? 1),
      stockMin: valueText(product.stockMin),
      stockMax: valueText(product.stockMax),
      supplierName: "",
      salePrice: valueText(product.salePrice),
      memberPrice: valueText(product.memberPrice),
      wholesalePrice: valueText(product.wholesalePrice),
      offerPrice: valueText(product.offerPrice),
      offerDiscountPercent: valueText(product.offerDiscountPercent),
      productType: valueText(product.productType),
      discountType: valueText(product.priceUseMode ?? product.discountType),
      backendDiscountType: valueText(product.discountType),
      familyId: valueText(product.familyId ?? defaultFamily?.id),
      familyName: valueText(family?.name ?? product.familyId),
      subfamilyId: valueText(product.subfamilyId),
      subfamilyName: valueText(subfamily?.name ?? product.subfamilyId),
      taxId: valueText(product.taxId ?? defaultTax?.id),
      taxName: taxDisplayName(tax) === "-" ? valueText(product.taxId) : taxDisplayName(tax),
      taxesIncluded: product.taxesIncluded === undefined || product.taxesIncluded === null ? "-" : product.taxesIncluded ? "common.yes" : "common.no",
      offerActive: product.offerActive === undefined || product.offerActive === null ? "-" : product.offerActive ? "common.yes" : "common.no",
      offerFrom: valueText(product.offerFrom),
      offerUntil: valueText(product.offerUntil),
      promotionNames: promotions.map((promotion) => promotion.name).join("; ") || "-",
      promotionTypes: promotions.map((promotion) => promotion.type).join("; ") || "-",
      promotionStatuses: promotions.map((promotion) => promotion.status).join("; ") || "-",
      promotionValidity: promotions.map((promotion) => `${promotion.startDate} / ${promotion.endDate ?? "-"}`).join("; ") || "-",
      warehouseName,
      quantity,
      totalQuantity: totalStockByProduct.get(product.id) ?? quantity
    };
  }

  if (selection.mode === "all") {
    return products.flatMap((product) => availableWarehouses.map((warehouse) => productRow(
      product,
      warehouse.id,
      valueText(warehouse.name ?? warehouse.id),
      stockByProductAndWarehouse.get(`${product.id}\u0000${warehouse.id}`) ?? 0
    )));
  }
  if (selection.mode === "total") {
    return products.map((product) => productRow(
      product,
      "TOTAL",
      "TOTAL",
      totalStockByProduct.get(product.id) ?? 0
    ));
  }
  return products.map((product) => productRow(
    product,
    selectedWarehouse.id,
    valueText(selectedWarehouse.name ?? selectedWarehouse.id),
    stockByProductAndWarehouse.get(`${product.id}\u0000${selectedWarehouse.id}`) ?? 0
  ));
}

function promotionAppliesToProduct(promotion: PromotionView, product: ProductView) {
  if (promotion.scope === "SALE") {
    return true;
  }
  return promotion.targets.some((target) => (
    target.type === "PRODUCT" && target.targetId === product.id
  ) || (
    target.type === "FAMILY" && target.targetId === product.familyId
  ) || (
    target.type === "SUBFAMILY" && target.targetId === product.subfamilyId
  ));
}

export function selectStockInventoryRows(
  allWarehouseRows: StockInventoryRow[],
  warehouseId: string
) {
  if (warehouseId !== "TOTAL") {
    return allWarehouseRows.filter((row) => row.warehouseId === warehouseId);
  }
  const rowsByProduct = new Map<string, StockInventoryRow>();
  allWarehouseRows.forEach((row) => {
    const current = rowsByProduct.get(row.productId);
    if (!current) {
      rowsByProduct.set(row.productId, {
        ...row,
        warehouseId: "TOTAL",
        warehouseName: "TOTAL",
        quantity: row.quantity
      });
      return;
    }
    current.quantity += row.quantity;
    current.totalQuantity = current.quantity;
  });
  return Array.from(rowsByProduct.values());
}

function supplierNameForStockRow(productId: string, links: StockBulkStoreSupplierLink[]) {
  const suppliers = links
    .filter((link) => link.productId === productId)
    .sort((left, right) => {
      const lastDifference = Number(Boolean(right.lastSupplier)) - Number(Boolean(left.lastSupplier));
      if (lastDifference !== 0) return lastDifference;
      const principalDifference = Number(Boolean(right.principal)) - Number(Boolean(left.principal));
      if (principalDifference !== 0) return principalDifference;
      return left.legalName.localeCompare(right.legalName, undefined, { sensitivity: "base" });
    });
  return suppliers[0]?.legalName ?? "";
}

function attachSupplierNamesToStockRows(rows: StockInventoryRow[], links: StockBulkStoreSupplierLink[]) {
  if (links.length === 0) {
    return rows;
  }
  return rows.map((row) => ({
    ...row,
    supplierName: supplierNameForStockRow(row.productId, links)
  }));
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
  loadPromotions?: () => Promise<PromotionView[]>;
}, selection: { mode?: "warehouse" | "total" | "all"; warehouseId?: string | null } = {}) {
  const [stockResult, products, warehouses, families, taxes, promotionsResult] = await Promise.all([
    loaders.loadStock().then(
      (stock) => ({ status: "fulfilled" as const, value: stock }),
      () => ({ status: "rejected" as const, value: [] as StockItemView[] })
    ),
    loaders.loadProducts(),
    loaders.loadWarehouses(),
    loaders.loadFamilies(),
    loaders.loadTaxes(),
    loaders.loadPromotions
      ? loaders.loadPromotions().then(
        (promotions) => ({ status: "fulfilled" as const, value: promotions }),
        () => ({ status: "rejected" as const, value: [] as PromotionView[] })
      )
      : Promise.resolve({ status: "fulfilled" as const, value: [] as PromotionView[] })
  ]);
  const subfamilies = await loadStockSubfamilies(families, loaders.loadSubfamilies);
  return buildStockInventoryRows(
    products,
    warehouses,
    stockResult.value,
    { families, subfamilies, taxes, promotions: promotionsResult.value },
    selection
  );
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
    if (selectedView === "stock.noDiscount" && row.backendDiscountType !== "NONE") {
      return false;
    }
    if (selectedView === "stock.promotions" && (!row.promotionNames || row.promotionNames === "-")) {
      return false;
    }
    if (selectedView === "stock.bulkEdit") {
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
      row.barcode2,
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
      row.promotionNames,
      row.promotionTypes,
      row.promotionStatuses,
      row.promotionValidity,
      row.warehouseName
    ].some((value) => String(value ?? "").toLocaleLowerCase("es").includes(normalizedSearch));
  });
}

export function stockTopSalesPath(
  period: StockTopSalesPeriod,
  dateFrom: string,
  dateTo = dateFrom,
  warehouseId = ""
) {
  const parameters = new URLSearchParams(period === "custom"
    ? { dateFrom, dateTo }
    : { period, date: dateTo });
  if (warehouseId) {
    parameters.set("warehouseId", warehouseId);
  }
  return `/stock/top-sales?${parameters.toString()}`;
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

function stockDateRangeDayCount(from: string, to: string) {
  const start = parseIsoDate(from);
  const end = parseIsoDate(to || from);
  if (!start || !end) {
    return 0;
  }
  return Math.max(1, Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1);
}

function stockSelectedDaysText(count: number, locale: LocaleCode) {
  if (locale === "zh") {
    return `已选择 ${count} 天`;
  }
  if (locale === "en") {
    return `${count} days selected`;
  }
  return `${count} días seleccionados`;
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
  const warehouse = filters.warehouse?.trim() ?? "";

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
      (!warehouse || row.warehouseId === warehouse) &&
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
  onLogout,
  onOpenDocument
}: StockScreenProps) {
  const t = createTranslator(locale);
  const stockTitle = t("home.stock").toLocaleUpperCase(locale === "zh" ? "zh-CN" : locale);
  const [selectedView, setSelectedView] = useState<StockViewKey>("stock.current");
  const [partyDirectory, setPartyDirectory] = useState<PartyDirectoryKind | null>(null);
  const [searchText, setSearchText] = useState("");
  const [allStockRows, setAllStockRows] = useState<StockInventoryRow[]>([]);
  const [warehouseCatalog, setWarehouseCatalog] = useState<WarehouseView[]>([]);
  const [defaultWarehouseId, setDefaultWarehouseId] = useState("");
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
    search: "",
    warehouse: ""
  });
  const [draftTopSalesPeriod, setDraftTopSalesPeriod] = useState<StockTopSalesPeriod>("week");
  const [draftTopSalesDateFrom, setDraftTopSalesDateFrom] = useState(() => stockTopSalesPeriodRange("week").dateFrom);
  const [draftTopSalesDateTo, setDraftTopSalesDateTo] = useState(() => stockTopSalesPeriodRange("week").dateTo);
  const [draftTopSalesFilters, setDraftTopSalesFilters] = useState<StockTopSalesFilters>({
    family: "",
    subfamily: "",
    supplier: "",
    search: "",
    warehouse: ""
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
  const [columnPreferencesReady, setColumnPreferencesReady] = useState(false);
  const [selectedStockIndex, setSelectedStockIndex] = useState(0);
  const [detailRow, setDetailRow] = useState<StockInventoryRow | null>(null);
  const [detailTab, setDetailTab] = useState<StockDetailTab>("stock");
  const [editingProduct, setEditingProduct] = useState<ProductCreateEditProduct | null>(null);
  const [stockPromotions, setStockPromotions] = useState<PromotionView[]>([]);
  const [bulkEditTab, setBulkEditTab] = useState<StockBulkEditTab>("main");
  const [bulkFileOpen, setBulkFileOpen] = useState(false);
  const [bulkImportOpen, setBulkImportOpen] = useState(false);
  const [bulkSearchText, setBulkSearchText] = useState("");
  const [bulkStatus, setBulkStatus] = useState("");
  const [bulkPriceUseOpenRowId, setBulkPriceUseOpenRowId] = useState<string | null>(null);
  const [bulkEditSelectedOpen, setBulkEditSelectedOpen] = useState(false);
  const [bulkFilterOpen, setBulkFilterOpen] = useState(false);
  const [bulkFilters, setBulkFilters] = useState<StockBulkFilterCriteria>({ ...emptyStockBulkFilterCriteria });
  const [bulkFamilyDialogOpen, setBulkFamilyDialogOpen] = useState(false);
  const [bulkDecimalDialogOpen, setBulkDecimalDialogOpen] = useState(false);
  const [bulkPriceRulesOpen, setBulkPriceRulesOpen] = useState(false);
  const [bulkEditorDialog, setBulkEditorDialog] = useState<BulkEditorDialog>(null);
  const [bulkEditorSearch, setBulkEditorSearch] = useState("");
  const [bulkExpandedFamilies, setBulkExpandedFamilies] = useState<Record<string, boolean>>({});
  const [bulkValidationErrors, setBulkValidationErrors] = useState<StockBulkValidationError[]>([]);
  const [bulkSupplierDialogMode, setBulkSupplierDialogMode] = useState<BulkSupplierDialogMode>(null);
  const [bulkSupplierOptions, setBulkSupplierOptions] = useState<SupplierOptionView[]>([]);
  const [bulkProductSupplierLinks, setBulkProductSupplierLinks] = useState<StockBulkStoreSupplierLink[]>([]);
  const [bulkProductSupplierLinksReady, setBulkProductSupplierLinksReady] = useState(false);
  const [bulkSupplierSearch, setBulkSupplierSearch] = useState("");
  const [bulkSelectedSupplierId, setBulkSelectedSupplierId] = useState<string | null>(null);
  const [bulkPurchaseDocumentKind, setBulkPurchaseDocumentKind] = useState<PurchaseDocumentKind | null>(null);
  const [bulkPurchaseDocuments, setBulkPurchaseDocuments] = useState<PurchaseDocumentOptionView[]>([]);
  const [bulkPurchaseDocumentSearch, setBulkPurchaseDocumentSearch] = useState("");
  const [bulkSelectedPurchaseDocumentId, setBulkSelectedPurchaseDocumentId] = useState<string | null>(null);
  const [bulkBenefitInputs, setBulkBenefitInputs] = useState<Record<string, string>>({});
  const [bulkRows, setBulkRows] = useState<StockBulkEditRow[]>(() => [createEmptyBulkEditRow(0)]);
  const [bulkImageSnapshot, setBulkImageSnapshot] = useState<StockBulkImageSnapshot>({ rows: [] });
  const [bulkDrafts, setBulkDrafts] = useState<StockBulkDraftView[]>([]);
  const [bulkWorkspaceView, setBulkWorkspaceView] = useState<BulkWorkspaceView>("list");
  const [bulkSelectedDraftId, setBulkSelectedDraftId] = useState<string | null>(null);
  const [activeBulkDraft, setActiveBulkDraft] = useState<StockBulkDraftView | null>(null);
  const [bulkDialog, setBulkDialog] = useState<BulkWorkspaceDialog>(null);
  const [bulkAfterSave, setBulkAfterSave] = useState<"apply" | "close" | "comments" | null>(null);
  const [bulkDraftName, setBulkDraftName] = useState("");
  const [bulkCommentText, setBulkCommentText] = useState("");
  const [bulkDeleteDraft, setBulkDeleteDraft] = useState<StockBulkDraftView | null>(null);
  const [bulkRenameDraft, setBulkRenameDraft] = useState<StockBulkDraftView | null>(null);
  const [bulkRenameValue, setBulkRenameValue] = useState("");
  const [bulkDirty, setBulkDirty] = useState(false);
  const [bulkImagesDirty, setBulkImagesDirty] = useState(false);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkConflictDraftId, setBulkConflictDraftId] = useState<string | null>(null);
  const [bulkFinder, setBulkFinder] = useState<{ rowId: string; query: string } | null>(null);
  const [warehouseDocumentMode, setWarehouseDocumentMode] = useState<WarehouseDocumentMode | null>(null);
  const [warehouseCustomers, setWarehouseCustomers] = useState<WarehouseCustomerOption[]>([]);
  const [warehouseSuppliers, setWarehouseSuppliers] = useState<WarehouseSupplierOption[]>([]);
  const [stockSettingsMode, setStockSettingsMode] = useState<StockSettingsMode | null>(null);
  const [stockSettings, setStockSettings] = useState<StockSettingsView | null>(null);
  const stockTableRef = useRef<HTMLDivElement | null>(null);
  const bulkTableRef = useRef<HTMLDivElement | null>(null);
  const bulkCodeInputRefs = useRef(new Map<string, HTMLInputElement>());
  const pendingBulkFocusRef = useRef<PendingBulkFocus | null>(null);
  const bulkEditorReturnFocusRef = useRef<HTMLElement | null>(null);
  const bulkHistoryRef = useRef<Array<{
    rows: StockBulkEditRow[];
    images: StockBulkImageSnapshot;
  }>>([]);
  const bulkRowsRef = useRef(bulkRows);
  const bulkImageSnapshotRef = useRef(bulkImageSnapshot);
  const bulkImagePanelRef = useRef<StockBulkImagePanelHandle | null>(null);
  const bulkFileInputRef = useRef<HTMLInputElement | null>(null);
  const bulkFileMenuRef = useRef<HTMLDivElement | null>(null);
  const bulkEditSelectedRef = useRef<HTMLDivElement | null>(null);
  const canReadStock = userCanReadStock(session);
  const bulkPendingImages = useMemo(
    () => Object.fromEntries(stockBulkImagePendingAssignments(bulkImageSnapshot)
      .map((assignment) => [assignment.productId, assignment.file])),
    [bulkImageSnapshot]
  );

  useEffect(() => {
    bulkRowsRef.current = bulkRows;
  }, [bulkRows]);

  useEffect(() => {
    bulkImageSnapshotRef.current = bulkImageSnapshot;
  }, [bulkImageSnapshot]);

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken || !canReadStock) {
      setAllStockRows([]);
      setWarehouseCatalog([]);
      setStockPromotions([]);
      setDefaultWarehouseId("");
      setStatus(session.accessToken ? "stock.status.noAccess" : "stock.status.noData");
      return;
    }

    async function loadStock() {
      try {
        let loadedWarehouses: WarehouseView[] = [];
        let loadedPromotions: PromotionView[] = [];
        const rows = await loadStockInventoryRows({
          loadStock: () => apiRequest<StockItemView[]>("/stock", { token: session.accessToken }),
          loadProducts: () => apiRequest<ProductView[]>("/products", { token: session.accessToken }),
          loadWarehouses: async () => {
            loadedWarehouses = await apiRequest<WarehouseView[]>("/warehouses", { token: session.accessToken });
            return loadedWarehouses;
          },
          loadFamilies: () => apiRequest<FamilyView[]>("/families", { token: session.accessToken }),
          loadTaxes: () => apiRequest<TaxView[]>("/taxes/selectable", { token: session.accessToken }),
          loadSubfamilies: (familyId) => apiRequest<SubfamilyView[]>(`/families/${encodeURIComponent(familyId)}/subfamilies`, { token: session.accessToken }),
          loadPromotions: async () => {
            loadedPromotions = await apiRequest<PromotionView[]>("/promotions", { token: session.accessToken });
            return loadedPromotions;
          }
        }, { mode: "all" });
        if (!cancelled) {
          const activeWarehouses = loadedWarehouses.filter((warehouse) => warehouse.active !== false);
          const defaultWarehouse = activeWarehouses.find((warehouse) => warehouse.defaultWarehouse)
            ?? activeWarehouses[0]
            ?? loadedWarehouses[0];
          setAllStockRows(rows);
          setWarehouseCatalog(loadedWarehouses);
          setStockPromotions(loadedPromotions);
          setDefaultWarehouseId(defaultWarehouse?.id ?? "");
          setStatus(rows.length === 0 ? "stock.status.noData" : "stock.status.inventoryLoaded");
        }
      } catch (error) {
        if (!cancelled) {
          setAllStockRows([]);
          setWarehouseCatalog([]);
          setStockPromotions([]);
          setDefaultWarehouseId("");
          setStatus(stockLoadStatus(error, "stock.status.noData"));
        }
      }
    }

    void loadStock();
    return () => {
      cancelled = true;
    };
  }, [canReadStock, session.accessToken, stockRefreshCounter]);

  useEffect(() => {
    let cancelled = false;
    if (!warehouseDocumentMode || !session.accessToken) {
      return;
    }
    const token = session.accessToken;
    void Promise.allSettled([
      apiRequest<WarehouseCustomerOption[]>("/customers", { token }),
      apiRequest<WarehouseSupplierOption[]>("/suppliers", { token })
    ]).then(([customers, suppliers]) => {
      if (cancelled) {
        return;
      }
      setWarehouseCustomers(customers.status === "fulfilled" ? customers.value : []);
      setWarehouseSuppliers(suppliers.status === "fulfilled" ? suppliers.value : []);
    });
    return () => {
      cancelled = true;
    };
  }, [session.accessToken, warehouseDocumentMode]);

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken || !canReadStock || selectedView !== "stock.topSales") {
      return;
    }

    async function loadTopSales() {
      try {
        const rows = await apiRequest<StockTopSalesRow[]>(
          stockTopSalesPath(topSalesPeriod, topSalesDateFrom, topSalesDateTo, topSalesFilters.warehouse),
          { token: session.accessToken }
        );
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
  }, [canReadStock, selectedView, session.accessToken, topSalesDateFrom, topSalesDateTo, topSalesFilters.warehouse, topSalesPeriod]);

  const effectiveWarehouseId = inventoryFilters.warehouse || defaultWarehouseId || allStockRows[0]?.warehouseId || "";
  const stockRows = useMemo(
    () => attachSupplierNamesToStockRows(selectStockInventoryRows(allStockRows, effectiveWarehouseId), bulkProductSupplierLinks),
    [allStockRows, bulkProductSupplierLinks, effectiveWarehouseId]
  );
  const visibleRows = filterStockInventoryRows(stockRows, selectedView, searchText, inventoryFilters);
  const visibleTopSalesRows = filterStockTopSalesRows(topSalesRows, topSalesFilters);
  const familyTree = useMemo(() => buildStockTopSalesFamilyTree(topSalesRows, t("stock.filter.noFamily")), [topSalesRows, t]);
  const inventoryFamilyTree = useMemo(() => buildStockInventoryFamilyTree(stockRows, t("stock.filter.noFamily")), [stockRows, t]);
  const inventoryTaxOptions = useMemo(() => uniqueStockOptions(stockRows, "taxId", "taxName"), [stockRows]);
  const inventoryWarehouseOptions = useMemo(() => [
    ...warehouseCatalog
      .filter((warehouse) => warehouse.active !== false)
      .map((warehouse) => ({ value: warehouse.id, label: valueText(warehouse.name ?? warehouse.id) })),
    { value: "TOTAL", label: t("stock.warehouse.total") }
  ], [warehouseCatalog, t]);
  const calendarLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const calendarTitle = new Intl.DateTimeFormat(calendarLocale, { month: "long", year: "numeric" }).format(calendarMonth);
  const selectedViewLabel = t(selectedView);
  const selectedViewSubtitle = selectedView === "stock.topSales" ? t("stock.subtitle.topSales") : t("stock.subtitle.inventory");
  const canManageProducts = userCanManageStockProducts(session);
  const supplierEntryDateFormatter = useMemo(() => new Intl.DateTimeFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { dateStyle: "short" }
  ), [locale]);

  useEffect(() => {
    let cancelled = false;
    setBulkProductSupplierLinksReady(false);
    if (!session.accessToken || !canManageProducts) {
      setBulkProductSupplierLinks([]);
      return;
    }
    void apiRequest<StockBulkStoreSupplierLink[]>(
      "/product-bulk-edits/product-suppliers",
      { token: session.accessToken }
    ).then((links) => {
      if (!cancelled) {
        setBulkProductSupplierLinks(links);
        setBulkProductSupplierLinksReady(true);
      }
    }).catch((error) => {
      if (!cancelled) {
        setBulkProductSupplierLinks([]);
        setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.supplierRelationsLoadError"));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [canManageProducts, session.accessToken, stockRefreshCounter]);

  useEffect(() => {
    if (!bulkProductSupplierLinksReady) return;
    setBulkRows((current) => {
      const next = withEmptyBulkTail(hydrateStockBulkSupplierData(current, bulkProductSupplierLinks));
      bulkRowsRef.current = next;
      return next;
    });
  }, [bulkProductSupplierLinks, bulkProductSupplierLinksReady]);
  const canCreateWarehouseInput = userCanCreateWarehouseInput(session);
  const canCreateWarehouseOutput = userCanCreateWarehouseOutput(session);
  const canReadWarehouseInput = userHasStockPermission(
    session,
    "WAREHOUSE_INPUTS_READ",
    "WAREHOUSE_INPUTS_WRITE",
    "WAREHOUSE_INPUTS_DELETE",
    "WAREHOUSE_INPUTS_CONFIRM"
  );
  const canReadWarehouseOutput = userHasStockPermission(
    session,
    "WAREHOUSE_OUTPUTS_READ",
    "WAREHOUSE_OUTPUTS_EDIT",
    "WAREHOUSE_OUTPUTS_DELETE",
    "WAREHOUSE_OUTPUTS_CONFIRM"
  );
  const canManageWarehouseSettings = userCanManageWarehouses(session);
  const visibleStockViews = !canReadStock
    ? []
    : canManageProducts
      ? stockViews
      : stockViews.filter((view) => view !== "stock.bulkEdit");
  const canReadCustomers = session.permissions.includes("ADMIN") || session.permissions.includes("CUSTOMERS_READ");
  const canReadSuppliers = session.permissions.includes("ADMIN") || session.permissions.includes("SUPPLIERS_READ");
  const visiblePartyDirectories: PartyDirectoryKind[] = [
    ...(canReadCustomers ? ["customers", "members"] as PartyDirectoryKind[] : []),
    ...(canReadSuppliers ? ["suppliers"] as PartyDirectoryKind[] : [])
  ];
  const bulkProducts = useMemo(() => uniqueProductRows(stockRows), [stockRows]);
  const filteredBulkRows = filterStockBulkRows(bulkRows, bulkFilters).filter((row) => {
    const normalized = normalizeBulkQuery(bulkSearchText);
    if (!normalized) {
      return true;
    }
    const product = row.product;
    return [row.query, product?.code, product?.barcode, product?.barcode2, product?.name, product?.familyName, product?.subfamilyName]
      .some((value) => normalizeBulkQuery(value ?? "").includes(normalized));
  });
  const activeBulkFilterCount = countActiveStockBulkFilters(bulkFilters);
  const selectableBulkRows = bulkRows.filter((row) => row.product);
  const allVisibleBulkRowsSelected = selectableBulkRows.length > 0
    && selectableBulkRows.every((row) => row.selected);
  const bulkFinderMatches = bulkFinder ? stockBulkEditMatches(bulkProducts, bulkFinder.query) : [];
  const bulkValidationByRow = useMemo(() => {
    const result = new Map<string, Set<StockBulkValidationField>>();
    bulkValidationErrors.forEach((error) => {
      const fields = result.get(error.rowId) ?? new Set<StockBulkValidationField>();
      fields.add(error.field);
      result.set(error.rowId, fields);
    });
    return result;
  }, [bulkValidationErrors]);
  const filteredBulkSupplierOptions = bulkSupplierOptions.filter((supplier) => {
    const query = normalizeBulkQuery(bulkSupplierSearch);
    return !query || [
      supplier.supplierCode,
      supplier.legalName,
      supplier.tradeName ?? "",
      supplier.documentNumber
    ].some((value) => normalizeBulkQuery(value).includes(query));
  });
  const filteredBulkPurchaseDocuments = bulkPurchaseDocuments.filter((document) => {
    const query = normalizeBulkQuery(bulkPurchaseDocumentSearch);
    return !query || [
      document.number ?? "",
      document.date,
      document.status,
      document.supplierName ?? ""
    ].some((value) => normalizeBulkQuery(value).includes(query));
  });
  const warehouseProducts: WarehouseImportProduct[] = bulkProducts.map((product) => ({
    id: product.productId,
    code: product.code,
    barcode: product.barcode,
    reference: product.barcode2 ?? undefined,
    name: product.name,
    discountType: product.discountType,
    salePrice: product.salePrice,
    wholesalePrice: product.wholesalePrice,
    purchasePrice: product.purchasePrice
  }));
  const warehouseOptions: WarehouseOption[] = warehouseCatalog
    .filter((warehouse) => warehouse.active !== false)
    .map((warehouse) => ({ id: warehouse.id, name: valueText(warehouse.name ?? warehouse.id) }));
  const selectedColumnSettings = columnSettings[selectedView];
  const visibleSelectedColumnSettings = visibleStockColumns(selectedColumnSettings);
  const selectedColumnDefinitions = stockColumnDefinitions[selectedView];
  const selectedColumnDefinitionByKey = new Map(selectedColumnDefinitions.map((column) => [column.key, column]));
  const selectedGridStyle: CSSProperties = {
    gridTemplateColumns: stockColumnGridTemplate(selectedColumnSettings)
  };
  const selectedStockRow = visibleRows[selectedStockIndex] ?? visibleRows[0] ?? null;
  const detailStockRows = detailRow ? allStockRows.filter((row) => row.productId === detailRow.productId) : [];

  useEffect(() => {
    let cancelled = false;
    const fallback = loadStoredStockColumnSettings(app, session.username);
    setColumnPreferencesReady(false);
    setColumnSettings(fallback);
    if (!session.accessToken) {
      setColumnPreferencesReady(true);
      return;
    }
    void apiRequest<StockColumnPreferenceView>(
      `/stock/column-preferences/${encodeURIComponent(app)}`,
      { token: session.accessToken }
    ).then((preference) => {
      if (cancelled) return;
      const next = sanitizeStockColumnSettings(preference.settings);
      setColumnSettings(next);
      saveStoredStockColumnSettings(app, session.username, next);
    }).catch(() => {
      if (!cancelled) setColumnSettings(fallback);
    }).finally(() => {
      if (!cancelled) setColumnPreferencesReady(true);
    });
    return () => {
      cancelled = true;
    };
  }, [app, session.accessToken, session.username]);

  useEffect(() => {
    saveStoredStockColumnSettings(app, session.username, columnSettings);
    if (!columnPreferencesReady || !session.accessToken) return;
    const timeout = window.setTimeout(() => {
      void apiRequest<StockColumnPreferenceView>(
        `/stock/column-preferences/${encodeURIComponent(app)}`,
        {
          method: "PUT",
          token: session.accessToken,
          body: { app, settings: columnSettings }
        }
      ).catch((error) => {
        setStatus(error instanceof Error ? error.message : t("stock.columns.saveError"));
      });
    }, 300);
    return () => window.clearTimeout(timeout);
  }, [app, columnPreferencesReady, columnSettings, session.accessToken, session.username]);

  useEffect(() => {
    if (selectedView === "stock.bulkEdit" && !canManageProducts) {
      setSelectedView("stock.current");
    }
  }, [canManageProducts, selectedView]);

  useEffect(() => {
    if (selectedView !== "stock.bulkEdit") {
      setBulkWorkspaceView("list");
    }
  }, [selectedView]);

  useEffect(() => {
    if (selectedView !== "stock.bulkEdit" || !session.accessToken || !canManageProducts) {
      return;
    }
    let cancelled = false;
    void apiRequest<StockBulkDraftView[]>("/product-bulk-edits", { token: session.accessToken })
      .then((drafts) => {
        if (!cancelled) {
          setBulkDrafts(drafts);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.loadError"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [canManageProducts, selectedView, session.accessToken]);

  useEffect(() => {
    setBulkSelectedDraftId((current) => (
      current && bulkDrafts.some((draft) => draft.id === current)
        ? current
        : bulkDrafts[0]?.id ?? null
    ));
  }, [bulkDrafts]);

  useEffect(() => {
    const pending = pendingBulkFocusRef.current;
    if (!pending) {
      return;
    }
    const frame = window.requestAnimationFrame(() => {
      const table = bulkTableRef.current;
      const input = bulkCodeInputRefs.current.get(pending.rowId);
      if (table) {
        table.scrollTop = pending.scrollTop;
        table.scrollLeft = pending.scrollLeft;
      }
      input?.focus({ preventScroll: true });
      if (table) {
        table.scrollTop = pending.scrollTop;
        table.scrollLeft = pending.scrollLeft;
      }
      pendingBulkFocusRef.current = null;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [bulkRows]);

  useEffect(() => {
    if (selectedView !== "stock.bulkEdit") {
      return;
    }
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (bulkWorkspaceView === "list") {
        return;
      }
      const target = event.target as HTMLElement | null;
      const editingText = target?.matches("input, textarea, select, [contenteditable='true']") ?? false;
      if (event.key === "Escape") {
        event.preventDefault();
        if (bulkPriceRulesOpen) {
          setBulkPriceRulesOpen(false);
        } else if (bulkDecimalDialogOpen) {
          setBulkDecimalDialogOpen(false);
        } else if (bulkFamilyDialogOpen) {
          setBulkFamilyDialogOpen(false);
        } else if (bulkFilterOpen) {
          setBulkFilterOpen(false);
        } else if (bulkEditorDialog) {
          setBulkEditorDialog(null);
        } else if (bulkPurchaseDocumentKind) {
          setBulkPurchaseDocumentKind(null);
        } else if (bulkSupplierDialogMode) {
          setBulkSupplierDialogMode(null);
        } else if (bulkFinder) {
          setBulkFinder(null);
        } else if (bulkDialog) {
          setBulkDialog(null);
          setBulkAfterSave(null);
        } else if (bulkImportOpen) {
          setBulkImportOpen(false);
        } else {
          closeBulkWorkspace();
        }
        return;
      }
      const shortcut = stockBulkShortcutAction(event.key, {
        ctrlKey: event.ctrlKey,
        altKey: event.altKey,
        metaKey: event.metaKey,
        editingText
      });
      if (shortcut === "save") {
        event.preventDefault();
        if (activeBulkDraft || bulkDraftName.trim()) {
          void saveBulkDraft();
        } else {
          setBulkDialog("save");
        }
      } else if (shortcut === "undo") {
        event.preventDefault();
        undoBulkChange();
      } else if (shortcut === "open") {
        event.preventDefault();
        closeBulkWorkspace();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [activeBulkDraft, bulkAfterSave, bulkBusy, bulkDecimalDialogOpen, bulkDialog, bulkDirty, bulkDraftName, bulkEditorDialog, bulkFamilyDialogOpen, bulkFilterOpen, bulkFinder, bulkImagesDirty, bulkImportOpen, bulkPriceRulesOpen, bulkPurchaseDocumentKind, bulkRows, bulkSupplierDialogMode, bulkWorkspaceView, selectedView, session.accessToken]);

  useEffect(() => {
    if (!bulkFileOpen && !bulkEditSelectedOpen) {
      return;
    }
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (bulkFileMenuRef.current?.contains(target) || bulkEditSelectedRef.current?.contains(target)) {
        return;
      }
      setBulkFileOpen(false);
      setBulkImportOpen(false);
      setBulkEditSelectedOpen(false);
    };
    document.addEventListener("pointerdown", handlePointerDown, true);
    return () => document.removeEventListener("pointerdown", handlePointerDown, true);
  }, [bulkEditSelectedOpen, bulkFileOpen]);

  useEffect(() => {
    setSelectedStockIndex((current) => {
      if (visibleRows.length === 0) {
        return 0;
      }
      return Math.min(current, visibleRows.length - 1);
    });
  }, [visibleRows.length, selectedView, searchText]);

  useEffect(() => {
    if (!stockTableShouldAutoFocus(selectedView, {
      inventoryFilterOpen,
      productCreateOpen,
      stockColumnsOpen,
      topSalesFilterOpen
    })) {
      return;
    }
    stockTableRef.current?.focus({ preventScroll: true });
  }, [inventoryFilterOpen, productCreateOpen, selectedView, stockColumnsOpen, topSalesFilterOpen]);

  useEffect(() => {
    if (!detailRow) {
      return;
    }
    const row = detailRow;
    function handleDetailKey(event: globalThis.KeyboardEvent) {
      if (event.key === "Enter"
          && (event.target as HTMLElement | null)?.closest("input, textarea, select, button, [role='combobox']")) {
        return;
      }
      const action = stockDetailKeyAction(event.key);
      if (!action) {
        return;
      }
      event.preventDefault();
      if (action === "close") {
        setDetailRow(null);
      } else {
        if (action === "edit" && !canManageProducts) {
          return;
        }
        setDetailTab(action);
        if (action === "edit") {
          setEditingProduct(stockRowToProductEdit(row));
          setProductCreateOpen(true);
        }
      }
    }
    window.addEventListener("keydown", handleDetailKey);
    return () => window.removeEventListener("keydown", handleDetailKey);
  }, [canManageProducts, detailRow]);

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
    if (tab === "edit" && !canManageProducts) {
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

  function withLiveBulkSupplierData(rows: StockBulkEditRow[]) {
    return bulkProductSupplierLinksReady
      ? hydrateStockBulkSupplierData(rows, bulkProductSupplierLinks)
      : rows;
  }

  function assignBulkProduct(rowId: string, product: StockInventoryRow) {
    const table = bulkTableRef.current;
    const scrollTop = table?.scrollTop ?? 0;
    const scrollLeft = table?.scrollLeft ?? 0;
    const duplicateIndex = bulkRows.findIndex((row) => row.id !== rowId && row.product?.productId === product.productId);
    if (duplicateIndex >= 0) {
      updateBulkQuery(rowId, "");
      setBulkFinder(null);
      setBulkStatus(t("stock.bulkEdit.productDuplicate").replace("{product}", product.name));
      if (table) {
        table.scrollTop = Math.max(0, duplicateIndex * 72);
      }
      return;
    }
    commitBulkRows((current) => {
      let next = current.map((row) => row.id === rowId
        ? { ...row, query: product.code || product.barcode || product.name, product, draft: { ...product } }
        : row);
      const rowIndex = next.findIndex((row) => row.id === rowId);
      if (rowIndex === next.length - 1) {
        next = [...next, createEmptyBulkEditRow(next.length)];
      }
      const nextRow = next[rowIndex + 1];
      if (nextRow) {
        pendingBulkFocusRef.current = { rowId: nextRow.id, scrollTop, scrollLeft };
      }
      return withLiveBulkSupplierData(next);
    });
    setBulkValidationErrors((current) => current.filter((error) => error.rowId !== rowId));
    setBulkFinder(null);
    setBulkStatus(t("stock.bulkEdit.productAdded"));
  }

  function updateBulkQuery(rowId: string, query: string) {
    setBulkRows((current) => current.map((row) => row.id === rowId ? { ...row, query } : row));
  }

  function handleBulkQueryKeyDown(rowId: string, query: string, event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== "Enter") {
      return;
    }
    event.preventDefault();
    const exact = stockBulkEditExactProduct(bulkProducts, query);
    if (exact) {
      assignBulkProduct(rowId, exact);
      return;
    }
    setBulkFinder({ rowId, query });
  }

  function bulkEntryElements() {
    return Array.from(
      bulkTableRef.current?.querySelectorAll<HTMLElement>("[data-bulk-entry]:not(:disabled)") ?? []
    );
  }

  function focusAdjacentBulkEntry(source: HTMLElement, intent: "next" | "previous") {
    const entries = bulkEntryElements();
    const current = source.closest<HTMLElement>("[data-bulk-entry]") ?? source;
    const currentIndex = entries.findIndex((entry) => entry === current);
    const nextIndex = nextEnterTargetIndex(currentIndex, entries.length, intent);
    if (nextIndex >= 0 && nextIndex !== currentIndex) {
      entries[nextIndex]?.focus();
    }
  }

  function focusAdjacentBulkEntryAfterRender(source: HTMLElement, intent: "next" | "previous" = "next") {
    window.requestAnimationFrame(() => focusAdjacentBulkEntry(source, intent));
  }

  function bulkEnterIntent(event: KeyboardEvent<HTMLElement>) {
    return enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      altKey: event.altKey,
      ctrlKey: event.ctrlKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
  }

  function acceptStockDialogEnter(
    event: KeyboardEvent<HTMLElement>,
    action: () => void,
    disabled = false
  ) {
    if (event.defaultPrevented || disabled || bulkEnterIntent(event) !== "next") return;
    if ((event.target as HTMLElement).closest(
      "button, [role='option'], [role='radio'], [role='listbox'], .erp-select, .date-popover"
    )) return;
    event.preventDefault();
    action();
  }

  function handleBulkEntryNavigation(event: KeyboardEvent<HTMLElement>) {
    const intent = bulkEnterIntent(event);
    if (!intent) return;
    event.preventDefault();
    if (intent === "next" && event.currentTarget.matches("input[type='checkbox'], input[type='radio']")) {
      (event.currentTarget as HTMLInputElement).click();
    }
    focusAdjacentBulkEntry(event.currentTarget, intent);
  }

  function clearBulkValidationFields(rowIds: string[], fields: StockBulkValidationField[]) {
    const rowIdSet = new Set(rowIds);
    const fieldSet = new Set<StockBulkValidationField>(fields);
    setBulkValidationErrors((current) => current.filter((error) => !rowIdSet.has(error.rowId) || !fieldSet.has(error.field)));
  }

  function updateBulkDraft(rowId: string, key: keyof StockInventoryRow, value: string) {
    commitBulkRows((current) => current.map((row) => {
      if (row.id !== rowId) {
        return row;
      }
      const draft: Partial<StockInventoryRow> = { ...row.draft, [key]: value };
      const priceUseMode = valueText(draft.discountType ?? row.product?.discountType);
      const offerDiscount = draft.offerDiscountPercent ?? row.product?.offerDiscountPercent;
      const salePrice = draft.salePrice ?? row.product?.salePrice;
      if (key === "offerDiscountPercent" || (key === "salePrice" && priceUseMode === "OFFER_DISCOUNT")) {
        const offerPrice = stockOfferPriceFromDiscount(salePrice, offerDiscount);
        if (offerPrice !== null) {
          draft.offerPrice = offerPrice;
        }
      }
      return { ...row, draft };
    }));
    clearBulkValidationFields([rowId], [key, ...(key === "salePrice" || key === "offerDiscountPercent" ? ["offerPrice" as const] : [])]);
  }

  function applyBulkPriceUse(rowIds: string[], mode: BulkPriceUseMode) {
    const offerActive = mode === "OFFER_PRICE" || mode === "OFFER_DISCOUNT";
    const rowIdSet = new Set(rowIds);
    commitBulkRows((current) => current.map((row) => {
      if (!rowIdSet.has(row.id)) {
        return row;
      }
      const draft: Partial<StockInventoryRow> = {
        ...row.draft,
        discountType: mode,
        backendDiscountType: backendDiscountTypeForPriceUse(
          mode,
          valueText(row.product?.backendDiscountType ?? row.product?.discountType)
        ),
        offerActive: offerActive ? "common.yes" : "common.no"
      };
      if (mode === "OFFER_DISCOUNT") {
        const offerPrice = stockOfferPriceFromDiscount(
          draft.salePrice ?? row.product?.salePrice,
          draft.offerDiscountPercent ?? row.product?.offerDiscountPercent
        );
        if (offerPrice !== null) {
          draft.offerPrice = offerPrice;
        }
      }
      return {
        ...row,
        draft
      };
    }));
    clearBulkValidationFields(rowIds, ["discountType", "offerActive", "offerPrice", "offerDiscountPercent", "offerFrom"]);
  }

  function updateBulkPriceUse(rowId: string, mode: BulkPriceUseMode) {
    applyBulkPriceUse([rowId], mode);
    setBulkPriceUseOpenRowId(null);
  }

  function toggleBulkSelected(rowId: string) {
    setBulkRows((current) => current.map((row) => row.id === rowId ? { ...row, selected: !row.selected } : row));
  }

  function toggleAllBulkRows(selected: boolean) {
    setBulkRows((current) => setAllStockBulkRowsSelected(current, selected));
  }

  function bulkSelectedActionLabel(action: BulkSelectedAction) {
    const labels: Record<BulkSelectedAction, string> = {
      supplier: "stock.column.supplier",
      family: "stock.column.family",
      purchasePrice: "stock.column.purchasePrice",
      salePrice: "stock.column.salePrice",
      memberPrice: "stock.column.memberPrice",
      wholesalePrice: "stock.column.wholesalePrice",
      offerPrice: "stock.column.offerPrice",
      offerDiscountPercent: "product.field.offerDiscountPercent",
      benefit: "stock.column.benefit",
      priceUse: "product.field.usePrice",
      activateOffer: "stock.bulkEdit.activateOffer",
      deactivateOffer: "stock.bulkEdit.deactivateOffer",
      offerDates: "product.field.offerRange",
      tax: "stock.column.tax",
      taxesIncludedYes: "stock.bulkEdit.taxesIncludedYes",
      taxesIncludedNo: "stock.bulkEdit.taxesIncludedNo"
    };
    return labels[action];
  }

  function selectedBulkRowIds() {
    return bulkRows.filter((row) => row.product && row.selected).map((row) => row.id);
  }

  function commonBulkValue(rowIds: string[], field: keyof StockInventoryRow) {
    const values = rowIds.map((rowId) => {
      const row = bulkRows.find((candidate) => candidate.id === rowId);
      return row ? bulkValue(row, field) : "";
    });
    return values.length > 0 && values.every((value) => value === values[0]) && values[0] !== "-" ? values[0] : "";
  }

  function patchBulkRows(
    rowIds: string[],
    patch: (row: StockBulkEditRow) => Partial<StockInventoryRow>,
    validationFields: StockBulkValidationField[]
  ) {
    const rowIdSet = new Set(rowIds);
    commitBulkRows((current) => current.map((row) => rowIdSet.has(row.id)
      ? { ...row, draft: { ...row.draft, ...patch(row) } }
      : row));
    clearBulkValidationFields(rowIds, validationFields);
  }

  function openBulkEditor(action: Exclude<BulkSelectedAction, "supplier">, rowIds: string[]) {
    const firstRow = bulkRows.find((row) => rowIds.includes(row.id));
    if (!firstRow) {
      return;
    }
    setBulkEditSelectedOpen(false);
    if (action === "family") {
      const familyId = commonBulkValue(rowIds, "familyId");
      setBulkEditorSearch("");
      setBulkExpandedFamilies(familyId ? { [familyId]: true } : {});
      setBulkEditorDialog({
        kind: "family",
        rowIds,
        familyId,
        subfamilyId: commonBulkValue(rowIds, "subfamilyId")
      });
      return;
    }
    if (action === "tax") {
      setBulkEditorSearch("");
      setBulkEditorDialog({ kind: "tax", rowIds, taxId: commonBulkValue(rowIds, "taxId") });
      return;
    }
    if (action === "priceUse" || action === "activateOffer") {
      const currentMode = commonBulkValue(rowIds, "discountType") as BulkPriceUseMode;
      const options = action === "activateOffer"
        ? (["OFFER_PRICE", "OFFER_DISCOUNT"] as BulkPriceUseMode[])
        : bulkPriceUseModes;
      setBulkEditorDialog({
        kind: "priceUse",
        rowIds,
        value: options.includes(currentMode) ? currentMode : options[0],
        options
      });
      return;
    }
    if (action === "deactivateOffer") {
      applyBulkPriceUse(rowIds, "NORMAL");
      setBulkStatus(t("stock.bulkEdit.selectedUpdated").replace("{count}", String(rowIds.length)));
      return;
    }
    if (action === "offerDates") {
      const offerFrom = commonBulkValue(rowIds, "offerFrom");
      const offerUntil = commonBulkValue(rowIds, "offerUntil");
      setBulkEditorDialog({
        kind: "dates",
        rowIds,
        offerFrom,
        offerUntil,
        rangeStart: null,
        calendarMonth: startOfMonth(parseIsoDate(offerFrom) ?? new Date())
      });
      return;
    }
    if (action === "taxesIncludedYes" || action === "taxesIncludedNo") {
      patchBulkRows(
        rowIds,
        () => ({ taxesIncluded: action === "taxesIncludedYes" ? "common.yes" : "common.no" }),
        ["taxesIncluded"]
      );
      setBulkStatus(t("stock.bulkEdit.selectedUpdated").replace("{count}", String(rowIds.length)));
      return;
    }
    if (action === "benefit") {
      const priceField = bulkEditTab === "memberPrice"
        ? "memberPrice"
        : bulkEditTab === "wholesalePrice"
          ? "wholesalePrice"
          : bulkEditTab === "offer"
            ? "offerPrice"
            : "salePrice";
      const margins = rowIds.map((rowId) => {
        const row = bulkRows.find((candidate) => candidate.id === rowId);
        return row
          ? Number(stockBenefitPercent(
            decimalNumber(row.draft.purchasePrice ?? row.product?.purchasePrice),
            decimalNumber(row.draft[priceField] ?? row.product?.[priceField])
          ).toFixed(2))
          : 0;
      });
      const commonMargin = margins.length > 0 && margins.every((margin) => margin === margins[0])
        ? String(margins[0])
        : "";
      setBulkEditorDialog({ kind: "benefit", rowIds, priceField, value: commonMargin });
      return;
    }
    setBulkEditorDialog({
      kind: "value",
      rowIds,
      field: action,
      labelKey: bulkSelectedActionLabel(action),
      value: commonBulkValue(rowIds, action)
    });
  }

  function handleBulkSelectedAction(action: BulkSelectedAction) {
    const rowIds = selectedBulkRowIds();
    if (rowIds.length === 0) {
      setBulkStatus(t("stock.bulkEdit.selectProductsFirst"));
      setBulkEditSelectedOpen(false);
      return;
    }
    if (action === "supplier") {
      void openBulkSupplierDialog("assign");
      return;
    }
    bulkEditorReturnFocusRef.current = null;
    openBulkEditor(action, rowIds);
  }

  function openBulkRowEditor(
    row: StockBulkEditRow,
    action: "family" | "tax" | "offerDates",
    returnFocus?: HTMLElement
  ) {
    if (!row.product) {
      return;
    }
    bulkEditorReturnFocusRef.current = returnFocus ?? null;
    openBulkEditor(action, [row.id]);
  }

  function finishBulkEditor(rowIds: string[]) {
    const returnFocus = bulkEditorReturnFocusRef.current;
    bulkEditorReturnFocusRef.current = null;
    setBulkStatus(t("stock.bulkEdit.selectedUpdated").replace("{count}", String(rowIds.length)));
    setBulkEditorDialog(null);
    if (returnFocus) {
      focusAdjacentBulkEntryAfterRender(returnFocus);
    }
  }

  function applyBulkFamilyValues(rowIds: string[], familyId: string, subfamilyId: string) {
    const family = inventoryFamilyTree.find((candidate) => candidate.id === familyId);
    const subfamily = family?.subfamilies.find((candidate) => candidate.id === subfamilyId);
    if (!family) {
      return false;
    }
    patchBulkRows(rowIds, () => ({
      familyId: family.id,
      familyName: family.name,
      subfamilyId: subfamily?.id ?? "-",
      subfamilyName: subfamily?.name ?? "-"
    }), ["familyId", "subfamilyId"]);
    return true;
  }

  function applyBulkTaxValue(rowIds: string[], taxId: string) {
    const tax = inventoryTaxOptions.find((candidate) => candidate.value === taxId);
    if (!tax) {
      return false;
    }
    patchBulkRows(rowIds, () => ({ taxId: tax.value, taxName: tax.label }), ["taxId"]);
    return true;
  }

  function applyBulkEditorDialog() {
    const editor = bulkEditorDialog;
    if (!editor) {
      return;
    }
    if (editor.kind === "value") {
      patchBulkRows(editor.rowIds, (row) => {
        const patch: Partial<StockInventoryRow> = { [editor.field]: editor.value };
        const priceUseMode = valueText(row.draft.discountType ?? row.product?.discountType);
        if (editor.field === "offerDiscountPercent" || (editor.field === "salePrice" && priceUseMode === "OFFER_DISCOUNT")) {
          const offerPrice = stockOfferPriceFromDiscount(
            editor.field === "salePrice" ? editor.value : row.draft.salePrice ?? row.product?.salePrice,
            editor.field === "offerDiscountPercent" ? editor.value : row.draft.offerDiscountPercent ?? row.product?.offerDiscountPercent
          );
          if (offerPrice !== null) {
            patch.offerPrice = offerPrice;
          }
        }
        return patch;
      }, [editor.field, ...(["offerDiscountPercent", "salePrice"].includes(editor.field) ? ["offerPrice" as const] : [])]);
    } else if (editor.kind === "benefit") {
      const benefit = Number(editor.value.replace(",", "."));
      patchBulkRows(editor.rowIds, (row) => {
        const purchasePrice = decimalNumber(row.draft.purchasePrice ?? row.product?.purchasePrice);
        const price = stockPriceFromBenefit(purchasePrice, benefit);
        if (price === null) {
          return {};
        }
        return editor.priceField === "offerPrice"
          ? {
            offerPrice: price.toFixed(2),
            discountType: "OFFER_PRICE",
            backendDiscountType: "DISCOUNT_PRICE",
            offerActive: "common.yes"
          }
          : { [editor.priceField]: price.toFixed(2) };
      }, [editor.priceField, ...(editor.priceField === "offerPrice"
        ? ["discountType" as const, "offerActive" as const]
        : [])]);
    } else if (editor.kind === "family") {
      if (!applyBulkFamilyValues(editor.rowIds, editor.familyId, editor.subfamilyId)) {
        return;
      }
    } else if (editor.kind === "tax") {
      if (!applyBulkTaxValue(editor.rowIds, editor.taxId)) {
        return;
      }
    } else if (editor.kind === "priceUse") {
      applyBulkPriceUse(editor.rowIds, editor.value);
    } else {
      patchBulkRows(editor.rowIds, () => ({
        offerFrom: editor.offerFrom || "-",
        offerUntil: editor.offerUntil || "-"
      }), ["offerFrom", "offerUntil"]);
    }
    finishBulkEditor(editor.rowIds);
  }

  function commitBulkRows(update: (current: StockBulkEditRow[]) => StockBulkEditRow[]) {
    setBulkRows((current) => {
      bulkHistoryRef.current = [...bulkHistoryRef.current.slice(-99), {
        rows: cloneBulkRows(current),
        images: cloneStockBulkImageSnapshot(bulkImageSnapshotRef.current)
      }];
      const next = update(current);
      bulkRowsRef.current = next;
      return next;
    });
    setBulkDirty(true);
  }

  function commitBulkImageSnapshot(nextSnapshot: StockBulkImageSnapshot) {
    setBulkImageSnapshot((current) => {
      bulkHistoryRef.current = [...bulkHistoryRef.current.slice(-99), {
        rows: cloneBulkRows(bulkRowsRef.current),
        images: cloneStockBulkImageSnapshot(current)
      }];
      const next = cloneStockBulkImageSnapshot(nextSnapshot);
      bulkImageSnapshotRef.current = next;
      return next;
    });
    setBulkImagesDirty(true);
    setBulkDirty(true);
  }

  function undoBulkChange() {
    const previous = bulkHistoryRef.current.pop();
    if (!previous) {
      setBulkStatus(t("stock.bulkEdit.nothingToUndo"));
      return;
    }
    const previousRows = withEmptyBulkTail(previous.rows);
    const previousImages = cloneStockBulkImageSnapshot(previous.images);
    bulkRowsRef.current = previousRows;
    bulkImageSnapshotRef.current = previousImages;
    setBulkRows(previousRows);
    setBulkImageSnapshot(previousImages);
    setBulkValidationErrors([]);
    setBulkImagesDirty(true);
    setBulkDirty(true);
    setBulkStatus(t("stock.bulkEdit.undoDone"));
  }

  function currentBulkContent() {
    return normalizeStockBulkContent(bulkRows.filter((row) => row.product));
  }

  async function reloadBulkDrafts() {
    if (!session.accessToken) {
      return [];
    }
    const drafts = await apiRequest<StockBulkDraftView[]>("/product-bulk-edits", { token: session.accessToken });
    setBulkDrafts(drafts);
    return drafts;
  }

  function showBulkRequestError(error: unknown, fallbackKey: string, draftId?: string) {
    if (error instanceof ApiError && error.status === 409 && draftId) {
      setBulkConflictDraftId(draftId);
      setBulkStatus(t("stock.bulkEdit.conflict"));
      return;
    }
    setBulkConflictDraftId(null);
    setBulkStatus(error instanceof Error ? error.message : t(fallbackKey));
  }

  async function reloadConflictedBulkDraft() {
    if (!session.accessToken || !bulkConflictDraftId || bulkBusy) return;
    setBulkBusy(true);
    try {
      const latest = await apiRequest<StockBulkDraftView>(
        `/product-bulk-edits/${encodeURIComponent(bulkConflictDraftId)}`,
        { token: session.accessToken }
      );
      await openBulkDraft(latest);
      await reloadBulkDrafts();
      setBulkStatus(t("stock.bulkEdit.reloaded"));
    } catch (error) {
      showBulkRequestError(error, "stock.bulkEdit.loadError");
    } finally {
      setBulkBusy(false);
    }
  }

  async function saveBulkDraft(afterAction = bulkAfterSave) {
    if (!session.accessToken || bulkBusy) {
      return null;
    }
    const name = bulkDraftName.trim() || activeBulkDraft?.name || "";
    if (!name) {
      setBulkAfterSave(afterAction);
      setBulkDialog("save");
      return null;
    }
    if (activeBulkDraft && !bulkDirty && name === activeBulkDraft.name) {
      setBulkDialog(null);
      setBulkStatus(t("stock.bulkEdit.noChanges"));
      return activeBulkDraft;
    }
    let savedDraft: StockBulkDraftView | null = null;
    setBulkBusy(true);
    try {
      const saved = await apiRequest<StockBulkDraftView>(
        activeBulkDraft ? `/product-bulk-edits/${encodeURIComponent(activeBulkDraft.id)}` : "/product-bulk-edits",
        {
          method: activeBulkDraft ? "PUT" : "POST",
          token: session.accessToken,
          body: activeBulkDraft
            ? { version: activeBulkDraft.version, name, content: currentBulkContent() }
            : { name, content: currentBulkContent() }
        }
      );
      savedDraft = saved;
      let persisted = saved;
      setActiveBulkDraft(saved);
      if (bulkImagesDirty) {
        const synchronized = await syncStockBulkDraftImages(
          saved.id,
          saved.version,
          bulkImageSnapshotRef.current,
          session.accessToken
        );
        const nextImages = cloneStockBulkImageSnapshot(synchronized.snapshot);
        bulkImageSnapshotRef.current = nextImages;
        setBulkImageSnapshot(nextImages);
        persisted = { ...saved, version: synchronized.version };
        setActiveBulkDraft(persisted);
      }
      setBulkDraftName(persisted.name);
      setBulkDirty(false);
      setBulkImagesDirty(false);
      setBulkConflictDraftId(null);
      setBulkDialog(null);
      setBulkStatus(t(activeBulkDraft?.status === "APPLIED" ? "stock.bulkEdit.versionCreated" : "stock.bulkEdit.saved"));
      await reloadBulkDrafts();
      const nextAction = afterAction;
      setBulkAfterSave(null);
      if (nextAction === "apply") {
        queueMicrotask(() => void applyBulkChanges(persisted));
      } else if (nextAction === "close") {
        queueMicrotask(resetBulkWorkspace);
      } else if (nextAction === "comments") {
        queueMicrotask(() => setBulkDialog("comments"));
      }
      return persisted;
    } catch (error) {
      setBulkDirty(true);
      showBulkRequestError(error, "stock.bulkEdit.saveError", savedDraft?.id ?? activeBulkDraft?.id);
      return null;
    } finally {
      setBulkBusy(false);
    }
  }

  async function openBulkDraft(draft: StockBulkDraftView) {
    if (!session.accessToken) return;
    setBulkBusy(true);
    try {
      const editableDraft = draft.status === "APPLIED"
        ? await apiRequest<StockBulkDraftView>(
            `/product-bulk-edits/${encodeURIComponent(draft.id)}`,
            {
              method: "PUT",
              token: session.accessToken,
              body: { version: draft.version, name: draft.name, content: draft.content }
            }
          )
        : draft;
      const loadedImages = await loadStockBulkDraftImages(editableDraft.id, session.accessToken);
      const nextRows = withLiveBulkSupplierData(withEmptyBulkTail(editableDraft.content));
      const nextImages = cloneStockBulkImageSnapshot(loadedImages);
      setActiveBulkDraft(editableDraft);
      setBulkDraftName(editableDraft.name);
      bulkRowsRef.current = nextRows;
      bulkImageSnapshotRef.current = nextImages;
      setBulkRows(nextRows);
      setBulkImageSnapshot(nextImages);
      bulkHistoryRef.current = [];
      setBulkValidationErrors([]);
      setBulkEditorDialog(null);
      setBulkDirty(false);
      setBulkImagesDirty(false);
      setBulkConflictDraftId(null);
      setBulkDialog(null);
      setBulkSelectedDraftId(editableDraft.id);
      setBulkWorkspaceView("editor");
      setBulkStatus(draft.status === "APPLIED"
        ? t("stock.bulkEdit.versionCreated")
        : `${editableDraft.code} - V${editableDraft.versionNumber}`);
      if (draft.status === "APPLIED") {
        await reloadBulkDrafts();
      }
    } catch (error) {
      showBulkRequestError(error, "stock.bulkEdit.loadError", draft.id);
    } finally {
      setBulkBusy(false);
    }
  }

  function clearBulkList() {
    if (bulkImageSnapshotRef.current.rows.length > 0) {
      setBulkImagesDirty(true);
    }
    commitBulkRows(() => [createEmptyBulkEditRow(0)]);
    const emptyImages = { rows: [] } satisfies StockBulkImageSnapshot;
    bulkImageSnapshotRef.current = emptyImages;
    setBulkImageSnapshot(emptyImages);
    setBulkValidationErrors([]);
    setBulkEditorDialog(null);
    setBulkDialog(null);
    setBulkStatus(t("stock.bulkEdit.listCleared"));
  }

  function resetBulkWorkspace() {
    const nextRows = [createEmptyBulkEditRow(0)];
    const emptyImages = { rows: [] } satisfies StockBulkImageSnapshot;
    bulkHistoryRef.current = [];
    bulkRowsRef.current = nextRows;
    bulkImageSnapshotRef.current = emptyImages;
    setBulkRows(nextRows);
    setBulkImageSnapshot(emptyImages);
    setActiveBulkDraft(null);
    setBulkDraftName("");
    setBulkDirty(false);
    setBulkImagesDirty(false);
    setBulkConflictDraftId(null);
    setBulkValidationErrors([]);
    setBulkEditorDialog(null);
    setBulkFileOpen(false);
    setBulkImportOpen(false);
    setBulkEditSelectedOpen(false);
    setBulkPriceUseOpenRowId(null);
    setBulkSearchText("");
    setBulkDialog(null);
    setBulkWorkspaceView("list");
    setBulkStatus(t("stock.bulkEdit.listClosed"));
    void reloadBulkDrafts();
  }

  function newBulkWorkspace() {
    resetBulkWorkspace();
    setBulkWorkspaceView("editor");
    setBulkStatus(t("stock.bulkEdit.workspace.newReady"));
  }

  function closeBulkWorkspace() {
    if (bulkDirty) {
      setBulkDialog("close");
      return;
    }
    resetBulkWorkspace();
  }

  async function applyBulkChanges(persistedDraft?: StockBulkDraftView) {
    if (!session.accessToken || bulkBusy) {
      return;
    }
    const validationErrors = validateStockBulkRows(bulkRows);
    if (validationErrors.length > 0) {
      setBulkValidationErrors(validationErrors);
      setBulkDialog(null);
      setBulkStatus(t("stock.bulkEdit.validationErrors").replace("{count}", String(validationErrors.length)));
      const firstRowIndex = filteredBulkRows.findIndex((row) => row.id === validationErrors[0].rowId);
      if (firstRowIndex >= 0 && bulkTableRef.current) {
        bulkTableRef.current.scrollTop = Math.max(0, firstRowIndex * 72);
      }
      return;
    }
    setBulkValidationErrors([]);
    let draft = persistedDraft ?? activeBulkDraft;
    if (!persistedDraft && (!draft || bulkDirty)) {
      if (!draft && !bulkDraftName.trim()) {
        setBulkAfterSave("apply");
        setBulkDialog("save");
        return;
      }
      draft = await saveBulkDraft(null);
    }
    if (!draft) {
      return;
    }
    const updates = buildStockBulkUpdates(bulkRows);
    const supplierAssignments = buildStockBulkSupplierAssignments(bulkRows);
    const imageSnapshot = cloneStockBulkImageSnapshot(bulkImageSnapshotRef.current);
    const imageAssignments = stockBulkImagePendingAssignments(imageSnapshot);
    const unassignedImages = imageSnapshot.rows.filter((row) => row.status !== "uploaded" && !row.productId);
    if (unassignedImages.length > 0) {
      setBulkEditTab("image");
      setBulkStatus(t("stock.bulkEdit.images.assignFirst"));
      setBulkDialog(null);
      return;
    }
    const hasCatalogChanges = updates.length > 0 || supplierAssignments.length > 0;
    if (!hasCatalogChanges && imageAssignments.length === 0) {
      setBulkStatus(t("stock.bulkEdit.noChanges"));
      setBulkDialog(null);
      return;
    }
    const appliedContent = finalizeStockBulkSupplierAssignments(currentBulkContent())
      .map((row) => {
        const effectiveProduct = stockBulkEffectiveProduct(row);
        return effectiveProduct
          ? { ...row, product: effectiveProduct, draft: effectiveProduct }
          : row;
      });
    setBulkBusy(true);
    try {
      const applied = await apiRequest<StockBulkDraftView>(
        `/product-bulk-edits/${encodeURIComponent(draft.id)}/apply`,
        {
          method: "POST",
          token: session.accessToken,
          body: { version: draft.version, updates, supplierAssignments, content: appliedContent }
        }
      );
      const nextRows = withLiveBulkSupplierData(withEmptyBulkTail(applied.content));
      const emptyImages = { rows: [] } satisfies StockBulkImageSnapshot;
      bulkRowsRef.current = nextRows;
      bulkImageSnapshotRef.current = emptyImages;
      setActiveBulkDraft(applied);
      setBulkRows(nextRows);
      setBulkImageSnapshot(emptyImages);
      setBulkDirty(false);
      setBulkImagesDirty(false);
      setBulkConflictDraftId(null);
      setBulkValidationErrors([]);
      bulkHistoryRef.current = [];
      setBulkDialog(null);
      setBulkStatus(t("stock.bulkEdit.applied"));
      setStockRefreshCounter((current) => current + 1);
      await reloadBulkDrafts();
    } catch (error) {
      showBulkRequestError(error, "stock.bulkEdit.applyError", draft.id);
    } finally {
      setBulkBusy(false);
    }
  }

  async function addBulkComment() {
    if (!session.accessToken || !activeBulkDraft || !bulkCommentText.trim() || bulkBusy) {
      return;
    }
    setBulkBusy(true);
    try {
      const updated = await apiRequest<StockBulkDraftView>(
        `/product-bulk-edits/${encodeURIComponent(activeBulkDraft.id)}/comments`,
        { method: "POST", token: session.accessToken, body: { text: bulkCommentText.trim() } }
      );
      setActiveBulkDraft(updated);
      setBulkCommentText("");
      await reloadBulkDrafts();
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.commentError"));
    } finally {
      setBulkBusy(false);
    }
  }

  function openBulkComments(draft: StockBulkDraftView) {
    setActiveBulkDraft(draft);
    setBulkSelectedDraftId(draft.id);
    setBulkCommentText("");
    setBulkDialog("comments");
  }

  function openBulkRename(draft: StockBulkDraftView) {
    setBulkRenameDraft(draft);
    setBulkRenameValue(draft.name);
    setBulkSelectedDraftId(draft.id);
    setBulkDialog("rename");
  }

  async function renameBulkDraft() {
    const name = bulkRenameValue.trim();
    if (!session.accessToken || !bulkRenameDraft || !name || bulkBusy) return;
    setBulkBusy(true);
    try {
      const updated = await apiRequest<StockBulkDraftView>(
        `/product-bulk-edits/${encodeURIComponent(bulkRenameDraft.id)}/name`,
        {
          method: "PATCH",
          token: session.accessToken,
          body: { version: bulkRenameDraft.version, name }
        }
      );
      if (activeBulkDraft?.id === updated.id) {
        setActiveBulkDraft(updated);
        setBulkDraftName(updated.name);
      }
      setBulkRenameDraft(null);
      setBulkRenameValue("");
      setBulkDialog(null);
      setBulkSelectedDraftId(updated.id);
      await reloadBulkDrafts();
      setBulkStatus(t("stock.bulkEdit.workspace.renamed"));
    } catch (error) {
      showBulkRequestError(error, "stock.bulkEdit.workspace.renameError", bulkRenameDraft.id);
    } finally {
      setBulkBusy(false);
    }
  }

  async function deleteBulkDraft() {
    if (!session.accessToken || !bulkDeleteDraft || bulkBusy) {
      return;
    }
    setBulkBusy(true);
    try {
      await apiRequest<void>(stockBulkVersionedDeletePath(bulkDeleteDraft.id, bulkDeleteDraft.version), {
        method: "DELETE",
        token: session.accessToken
      });
      if (activeBulkDraft?.id === bulkDeleteDraft.id) {
        resetBulkWorkspace();
      }
      setBulkDeleteDraft(null);
      setBulkDialog(null);
      await reloadBulkDrafts();
      setBulkConflictDraftId(null);
      setBulkStatus(t("stock.bulkEdit.deleted"));
    } catch (error) {
      showBulkRequestError(error, "stock.bulkEdit.deleteError", bulkDeleteDraft.id);
    } finally {
      setBulkBusy(false);
    }
  }

  async function exportBulkExcel() {
    if (!session.accessToken || bulkBusy) return;
    setBulkBusy(true);
    try {
      const download = await requestStockBulkXlsx(
        apiBaseUrl,
        session.accessToken,
        currentBulkContent()
      );
      const url = URL.createObjectURL(download.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = download.fileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
      setBulkStatus(t("stock.bulkEdit.exported"));
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.exportError"));
    } finally {
      setBulkBusy(false);
    }
  }

  async function importBulkExcel(file: File) {
    if (!session.accessToken) return;
    try {
      const [families, taxes] = await Promise.all([
        apiRequest<FamilyView[]>("/families", { token: session.accessToken }),
        apiRequest<TaxView[]>("/taxes/selectable", { token: session.accessToken })
      ]);
      const subfamilies = await loadStockSubfamilies(
        families,
        (familyId) => apiRequest<SubfamilyView[]>(
          `/families/${encodeURIComponent(familyId)}/subfamilies`,
          { token: session.accessToken }
        )
      );
      const imported = await importStockBulkFile(file, bulkProducts, {
        locale,
        families: families.map((family) => ({ id: family.id, name: family.name || family.id })),
        subfamilies: subfamilies.map((subfamily) => ({
          id: subfamily.id,
          familyId: subfamily.familyId || "",
          name: subfamily.name || subfamily.id
        })),
        taxes: taxes.map((tax) => ({ id: tax.id, name: taxDisplayName(tax) }))
      });
      if (imported.length === 0) {
        setBulkStatus(t("stock.bulkEdit.importNoMatches"));
        return;
      }
      commitBulkRows(() => withLiveBulkSupplierData(withEmptyBulkTail(imported)));
      setBulkValidationErrors([]);
      setBulkStatus(t("stock.bulkEdit.imported").replace("{count}", String(imported.length)));
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.importError"));
    }
  }

  async function loadBulkSupplierCatalog() {
    if (!session.accessToken) return [];
    const suppliers = await apiRequest<SupplierOptionView[]>(
      "/product-bulk-edits/suppliers",
      { token: session.accessToken }
    );
    setBulkSupplierOptions(suppliers);
    return suppliers;
  }

  async function openBulkFilterDialog() {
    setBulkFilterOpen(true);
    setBulkFileOpen(false);
    setBulkImportOpen(false);
    if (bulkSupplierOptions.length > 0 || !session.accessToken || bulkBusy) return;
    setBulkBusy(true);
    try {
      await loadBulkSupplierCatalog();
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.supplierLoadError"));
    } finally {
      setBulkBusy(false);
    }
  }

  async function applyBulkFilterSelection(value: StockBulkFilterCriteria) {
    if (value.supplierId && session.accessToken) {
      setBulkBusy(true);
      try {
        const supplier = bulkSupplierOptions.find((option) => option.id === value.supplierId);
        const links = await apiRequest<StockBulkSupplierProductLink[]>(
          `/product-bulk-edits/suppliers/${encodeURIComponent(value.supplierId)}/products`,
          { token: session.accessToken }
        );
        const linksByProduct = new Map(links.map((link) => [link.productId, link]));
        setBulkRows((current) => current.map((row) => {
          if (!row.product || !supplier) return row;
          const link = linksByProduct.get(row.product.productId);
          if (!link) return row;
          return {
            ...row,
            suppliers: [
              ...(row.suppliers ?? []).filter((item) => item.id !== supplier.id),
              { ...supplier, ...link }
            ]
          };
        }));
      } catch (error) {
        setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.supplierLoadError"));
        setBulkBusy(false);
        return;
      }
      setBulkBusy(false);
    }
    setBulkFilters(value);
    setBulkFilterOpen(false);
  }

  async function openBulkPriceRulesDialog() {
    setBulkPriceRulesOpen(true);
    setBulkFileOpen(false);
    setBulkImportOpen(false);
    if (bulkSupplierOptions.length > 0 || !session.accessToken || bulkBusy) return;
    setBulkBusy(true);
    try {
      await loadBulkSupplierCatalog();
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.supplierLoadError"));
    } finally {
      setBulkBusy(false);
    }
  }

  function importBulkFamilies(familyIds: string[], subfamilyIds: string[]) {
    const next = withLiveBulkSupplierData(
      mergeStockBulkFamilyProducts(bulkRows, bulkProducts, familyIds, subfamilyIds)
    );
    const previousCount = bulkRows.filter((row) => row.product).length;
    const nextCount = next.filter((row) => row.product).length;
    commitBulkRows(() => next);
    setBulkFamilyDialogOpen(false);
    setBulkValidationErrors([]);
    setBulkStatus(t("stock.bulkEdit.families.imported")
      .replace("{count}", String(nextCount - previousCount)));
  }

  function currentBulkDecimalField() {
    if (bulkEditTab === "salePrice") return "salePrice" as const;
    if (bulkEditTab === "memberPrice") return "memberPrice" as const;
    if (bulkEditTab === "wholesalePrice") return "wholesalePrice" as const;
    if (bulkEditTab === "offer") return "offerPrice" as const;
    return null;
  }

  function openBulkDecimalDialog() {
    if (stockBulkProductRowIds(bulkRows).length === 0) {
      setBulkStatus(t("stock.bulkEdit.decimal.noProducts"));
      return;
    }
    setBulkDecimalDialogOpen(true);
  }

  function applyBulkDecimalEnding(ending: number) {
    const field = currentBulkDecimalField();
    const productIds = stockBulkProductRowIds(bulkRows);
    const targetIds = new Set(productIds);
    if (!field || targetIds.size === 0) return;
    commitBulkRows((current) => applyStockBulkDecimalEnding(current, targetIds, field, ending));
    clearBulkValidationFields(productIds, [field]);
    setBulkDecimalDialogOpen(false);
    setBulkStatus(t("stock.bulkEdit.decimal.applied").replace("{count}", String(targetIds.size)));
  }

  async function openBulkSupplierDialog(mode: Exclude<BulkSupplierDialogMode, null>) {
    if (!session.accessToken || bulkBusy) {
      return;
    }
    if (mode === "assign" && !bulkRows.some((row) => row.product && row.selected)) {
      setBulkStatus(t("stock.bulkEdit.selectProductsFirst"));
      setBulkEditSelectedOpen(false);
      return;
    }
    setBulkFileOpen(false);
    setBulkImportOpen(false);
    setBulkEditSelectedOpen(false);
    setBulkSupplierSearch("");
    setBulkSelectedSupplierId(null);
    setBulkSupplierDialogMode(mode);
    setBulkBusy(true);
    try {
      await loadBulkSupplierCatalog();
    } catch (error) {
      setBulkSupplierDialogMode(null);
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.supplierLoadError"));
    } finally {
      setBulkBusy(false);
    }
  }

  async function applyBulkSupplier(selectedSupplier?: SupplierOptionView) {
    if (!session.accessToken || !bulkSupplierDialogMode || bulkBusy) {
      return;
    }
    const supplier = selectedSupplier
      ?? bulkSupplierOptions.find((option) => option.id === bulkSelectedSupplierId);
    if (!supplier || (bulkSupplierDialogMode === "assign" && !supplier.active)) {
      return;
    }
    if (bulkSupplierDialogMode === "assign") {
      const selectedIds = new Set(
        bulkRows.filter((row) => row.product && row.selected).map((row) => row.id)
      );
      commitBulkRows((current) => current.map((row) => selectedIds.has(row.id)
        ? { ...row, pendingSupplier: { ...supplier } }
        : row));
      setBulkStatus(t("stock.bulkEdit.supplierPending")
        .replace("{count}", String(selectedIds.size))
        .replace("{supplier}", supplier.legalName));
      setBulkSupplierDialogMode(null);
      return;
    }
    setBulkBusy(true);
    try {
      const links = await apiRequest<StockBulkSupplierProductLink[]>(
        `/product-bulk-edits/suppliers/${encodeURIComponent(supplier.id)}/products`,
        { token: session.accessToken }
      );
      if (links.length === 0) {
        setBulkStatus(t("stock.bulkEdit.supplierNoProducts"));
        return;
      }
      commitBulkRows((current) => withLiveBulkSupplierData(mergeStockBulkSupplierProducts(
        current, bulkProducts, supplier, links
      )));
      setBulkValidationErrors([]);
      setBulkStatus(t("stock.bulkEdit.supplierImported")
        .replace("{count}", String(links.length))
        .replace("{supplier}", supplier.legalName));
      setBulkSupplierDialogMode(null);
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t("stock.bulkEdit.supplierImportError"));
    } finally {
      setBulkBusy(false);
    }
  }

  async function openBulkPurchaseDocumentDialog(kind: PurchaseDocumentKind) {
    if (!session.accessToken || bulkBusy) {
      return;
    }
    const config = bulkPurchaseDocumentConfig[kind];
    setBulkFileOpen(false);
    setBulkImportOpen(false);
    setBulkPurchaseDocumentSearch("");
    setBulkSelectedPurchaseDocumentId(null);
    setBulkPurchaseDocumentKind(kind);
    setBulkBusy(true);
    try {
      const documents = await apiRequest<PurchaseDocumentOptionView[]>(
        `/product-bulk-edits/${config.path}`,
        { token: session.accessToken }
      );
      setBulkPurchaseDocuments(documents);
    } catch (error) {
      setBulkPurchaseDocumentKind(null);
      setBulkStatus(error instanceof Error ? error.message : t(config.loadErrorKey));
    } finally {
      setBulkBusy(false);
    }
  }

  async function applyBulkPurchaseDocument(selectedDocument?: PurchaseDocumentOptionView) {
    if (!session.accessToken || !bulkPurchaseDocumentKind || bulkBusy) {
      return;
    }
    const kind = bulkPurchaseDocumentKind;
    const config = bulkPurchaseDocumentConfig[kind];
    const document = selectedDocument
      ?? bulkPurchaseDocuments.find((value) => value.id === bulkSelectedPurchaseDocumentId);
    if (!document) {
      return;
    }
    setBulkBusy(true);
    try {
      const lines = await apiRequest<StockBulkPurchaseDocumentLine[]>(
        `/product-bulk-edits/${config.path}/${encodeURIComponent(document.id)}/products`,
        { token: session.accessToken }
      );
      if (lines.length === 0) {
        setBulkStatus(t(config.noProductsKey));
        return;
      }
      commitBulkRows((current) => withLiveBulkSupplierData(mergeStockBulkPurchaseDocumentProducts(
        current, bulkProducts, lines
      )));
      setBulkValidationErrors([]);
      setBulkStatus(t(config.importedKey)
        .replace("{count}", String(lines.length))
        .replace("{document}", document.number || t("stock.bulkEdit.purchaseInvoiceWithoutNumber")));
      setBulkPurchaseDocumentKind(null);
    } catch (error) {
      setBulkStatus(error instanceof Error ? error.message : t(config.importErrorKey));
    } finally {
      setBulkBusy(false);
    }
  }

  function markBulkAction(labelKey: string) {
    setBulkStatus(t(labelKey));
    setBulkFileOpen(false);
    setBulkImportOpen(false);
  }

  function handleBulkFileAction(item: string) {
    setBulkFileOpen(false);
    setBulkImportOpen(false);
    if (item === "stock.bulkEdit.openList") {
      closeBulkWorkspace();
      return;
    }
    if (item === "stock.bulkEdit.clearList") {
      setBulkDialog("clear");
      return;
    }
    if (item === "stock.bulkEdit.undo") {
      undoBulkChange();
      return;
    }
    if (item === "stock.bulkEdit.saveList") {
      if (activeBulkDraft || bulkDraftName.trim()) {
        void saveBulkDraft();
      } else {
        setBulkDialog("save");
      }
      return;
    }
    if (item === "stock.bulkEdit.applyChanges") {
      setBulkDialog("apply");
      return;
    }
    if (item === "stock.bulkEdit.comments") {
      if (!activeBulkDraft) {
        setBulkStatus(t("stock.bulkEdit.saveBeforeComment"));
        setBulkAfterSave("comments");
        setBulkDialog("save");
      } else {
        setBulkDialog("comments");
      }
      return;
    }
    if (item === "stock.bulkEdit.importExcel") {
      bulkFileInputRef.current?.click();
      return;
    }
    if (item === "stock.bulkEdit.importSupplier") {
      void openBulkSupplierDialog("import");
      return;
    }
    if (item === "stock.bulkEdit.importPurchaseInvoice") {
      void openBulkPurchaseDocumentDialog("invoice");
      return;
    }
    if (item === "stock.bulkEdit.importPurchaseDeliveryNote") {
      void openBulkPurchaseDocumentDialog("deliveryNote");
      return;
    }
    if (item === "stock.bulkEdit.importFamilies") {
      setBulkFamilyDialogOpen(true);
      return;
    }
    if (item === "stock.bulkEdit.exportExcel") {
      void exportBulkExcel();
      return;
    }
    if (item === "stock.bulkEdit.closeList") {
      closeBulkWorkspace();
      return;
    }
    markBulkAction(item);
  }

  function toggleBulkFileMenu() {
    const nextOpen = !bulkFileOpen;
    setBulkFileOpen(nextOpen);
    setBulkImportOpen(false);
    if (nextOpen) {
      setBulkEditSelectedOpen(false);
    }
  }

  function toggleBulkEditSelectedMenu() {
    const nextOpen = !bulkEditSelectedOpen;
    setBulkEditSelectedOpen(nextOpen);
    if (nextOpen) {
      setBulkFileOpen(false);
      setBulkImportOpen(false);
    }
  }

  function clearTopSalesFilters() {
    const range = stockTopSalesPeriodRange("week");
    setDraftTopSalesPeriod("week");
    setDraftTopSalesDateFrom(range.dateFrom);
    setDraftTopSalesDateTo(range.dateTo);
    setDraftTopSalesFilters((current) => ({
      ...current,
      family: "",
      subfamily: "",
      supplier: "",
      warehouse: ""
    }));
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

  function reorderSelectedColumn(columnKey: string, targetKey: string) {
    setColumnSettings((current) => reorderStockColumn(current, selectedView, columnKey, targetKey));
  }

  function toggleSelectedColumnVisibility(columnKey: string) {
    setColumnSettings((current) => toggleStockColumnVisibility(current, selectedView, columnKey));
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
        {visibleSelectedColumnSettings.map((column) => (
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
    const selectedLabel = options.find((option) => option.value === value)?.label
      ?? t(name === "warehouse" ? "stock.warehouse.local" : "stock.filter.all");
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
              {t(name === "warehouse" ? "stock.warehouse.local" : "stock.filter.all")}
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
      return <span className="product-name-text">{row.name}</span>;
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
      return <span className="product-name-text">{row.name}</span>;
    }
    if (columnKey === "type") {
      return <span>{row.productType === "-" ? row.productType : t(stockProductTypeLabel(row.productType))}</span>;
    }
    if (columnKey === "discount") {
      const discountType = row.backendDiscountType === "NONE" ? "NONE" : row.discountType;
      return <span>{discountType === "-" ? discountType : t(stockDiscountTypeLabel(discountType))}</span>;
    }
    if (columnKey === "supplier") {
      return <span>{row.supplierName || "-"}</span>;
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
    if (columnKey === "packageQuantity") {
      return <span>{row.packageQuantity || "-"}</span>;
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
    if (columnKey === "promotion") {
      return <span>{row.promotionNames ?? "-"}</span>;
    }
    if (columnKey === "promotionType") {
      return <span>{translateStockList(row.promotionTypes, "stock.promotion.type.")}</span>;
    }
    if (columnKey === "promotionStatus") {
      return <span>{translateStockList(row.promotionStatuses, "stock.promotion.status.")}</span>;
    }
    if (columnKey === "promotionValidity") {
      return <span>{row.promotionValidity ?? "-"}</span>;
    }
    if (columnKey === "warehouse") {
      return <span>{row.warehouseName}</span>;
    }
    if (columnKey === "localStock") {
      return <b>{row.quantity}</b>;
    }
    if (columnKey === "stock") {
      return <b>{row.quantity}</b>;
    }
    if (columnKey === "totalStock") {
      return <b>{row.totalQuantity}</b>;
    }
    if (columnKey === "stockMin") {
      return <span>{row.stockMin || "-"}</span>;
    }
    if (columnKey === "stockMax") {
      return <span>{row.stockMax || "-"}</span>;
    }
    if (columnKey === "status") {
      return <em>{t(stockInventoryStatus(row.quantity))}</em>;
    }
    return <span />;
  }

  function translateStockList(value: string | undefined, prefix: string) {
    if (!value || value === "-") {
      return "-";
    }
    return value.split("; ").map((item) => t(`${prefix}${item}`)).join("; ");
  }

  function bulkValue(row: StockBulkEditRow, key: keyof StockInventoryRow) {
    return valueText(row.draft[key] ?? row.product?.[key]);
  }

  function bulkCellInvalid(row: StockBulkEditRow, field: StockBulkValidationField) {
    return bulkValidationByRow.get(row.id)?.has(field) ?? false;
  }

  function renderBulkEditableCell(row: StockBulkEditRow, key: keyof StockInventoryRow, labelKey: string, className = "") {
    const original = valueText(row.product?.[key]);
    const value = bulkValue(row, key);
    const changed = row.product && value !== original;
    const invalid = bulkCellInvalid(row, key);
    const numeric = [
      "purchasePrice", "purchaseDiscountPercent", "salePrice", "memberPrice", "wholesalePrice", "offerPrice",
      "offerDiscountPercent"
    ].includes(String(key));
    const belowCost = ["salePrice", "memberPrice", "wholesalePrice", "offerPrice"].includes(String(key))
      && stockPriceBelowCost(value, row.draft.purchasePrice ?? row.product?.purchasePrice);
    return (
      <div className={`bulk-edit-cell ${className} ${changed ? "changed" : ""} ${belowCost ? "danger" : ""} ${invalid ? "invalid" : ""}`}>
        <input
          data-bulk-entry
          aria-label={t(labelKey)}
          aria-invalid={invalid}
          value={value === "-" ? "" : value}
          disabled={!row.product}
          inputMode={numeric ? "decimal" : undefined}
          onChange={(event) => updateBulkDraft(row.id, key, event.target.value)}
          onKeyDown={handleBulkEntryNavigation}
        />
      </div>
    );
  }

  function renderBulkFamilyCell(row: StockBulkEditRow, subfamily = false, combined = false) {
    const field = subfamily ? "subfamilyId" : "familyId";
    const labelKey = subfamily ? "stock.column.subfamily" : "stock.column.family";
    const label = combined
      ? [bulkValue(row, "familyName"), bulkValue(row, "subfamilyName")].filter((value) => value !== "-").join(" / ") || "-"
      : bulkValue(row, subfamily ? "subfamilyName" : "familyName");
    const changed = Boolean(row.product) && (bulkValue(row, field) !== valueText(row.product?.[field])
      || (combined && bulkValue(row, "subfamilyId") !== valueText(row.product?.subfamilyId)));
    const invalid = bulkCellInvalid(row, field) || (combined && bulkCellInvalid(row, "subfamilyId"));
    return (
      <div className={`bulk-choice-cell ${changed ? "changed" : ""} ${invalid ? "invalid" : ""}`}>
        <button
          type="button"
          data-bulk-entry
          disabled={!row.product}
          aria-label={t(labelKey)}
          aria-invalid={invalid}
          onClick={(event) => openBulkRowEditor(row, "family", event.currentTarget)}
          onKeyDown={(event) => {
            const intent = bulkEnterIntent(event);
            if (!intent) return;
            event.preventDefault();
            if (intent === "next") openBulkRowEditor(row, "family", event.currentTarget);
            else focusAdjacentBulkEntry(event.currentTarget, "previous");
          }}
        >
          <span>{label}</span>
          <span className="filter-control-arrow" aria-hidden="true">v</span>
        </button>
      </div>
    );
  }

  function renderBulkTaxCell(row: StockBulkEditRow) {
    const changed = Boolean(row.product) && bulkValue(row, "taxId") !== valueText(row.product?.taxId);
    const invalid = bulkCellInvalid(row, "taxId");
    return (
      <div className={`bulk-choice-cell ${changed ? "changed" : ""} ${invalid ? "invalid" : ""}`}>
        <button
          type="button"
          data-bulk-entry
          disabled={!row.product}
          aria-label={t("stock.column.tax")}
          aria-invalid={invalid}
          onClick={(event) => openBulkRowEditor(row, "tax", event.currentTarget)}
          onKeyDown={(event) => {
            const intent = bulkEnterIntent(event);
            if (!intent) return;
            event.preventDefault();
            if (intent === "next") openBulkRowEditor(row, "tax", event.currentTarget);
            else focusAdjacentBulkEntry(event.currentTarget, "previous");
          }}
        >
          <span>{bulkValue(row, "taxName")}</span>
          <span className="filter-control-arrow" aria-hidden="true">v</span>
        </button>
      </div>
    );
  }

  function renderBulkTaxesIncludedCell(row: StockBulkEditRow) {
    const value = bulkValue(row, "taxesIncluded");
    const changed = Boolean(row.product) && value !== valueText(row.product?.taxesIncluded);
    const checked = value === "common.yes" || value === "true";
    return (
      <label className={`bulk-boolean-cell ${changed ? "changed" : ""}`}>
        <input
          type="checkbox"
          data-bulk-entry
          disabled={!row.product}
          checked={checked}
          aria-label={t("stock.column.taxIncluded")}
          onChange={(event) => updateBulkDraft(row.id, "taxesIncluded", event.target.checked ? "common.yes" : "common.no")}
          onKeyDown={handleBulkEntryNavigation}
        />
        <span>{t(checked ? "common.yes" : "common.no")}</span>
      </label>
    );
  }

  function renderBulkOfferActiveCell(row: StockBulkEditRow) {
    const mode = bulkValue(row, "discountType");
    const active = mode === "OFFER_PRICE" || mode === "OFFER_DISCOUNT";
    const changed = Boolean(row.product) && active !== ["OFFER_PRICE", "OFFER_DISCOUNT"].includes(valueText(row.product?.discountType));
    return (
      <div className={`bulk-offer-indicator ${active ? "active" : ""} ${changed ? "changed" : ""}`}>
        <span aria-hidden="true" />
        <strong>{t(active ? "common.yes" : "common.no")}</strong>
      </div>
    );
  }

  function renderBulkOfferDateCell(row: StockBulkEditRow, field: "offerFrom" | "offerUntil", labelKey: string) {
    const value = bulkValue(row, field);
    const changed = Boolean(row.product) && value !== valueText(row.product?.[field]);
    const invalid = bulkCellInvalid(row, field);
    return (
      <div className={`bulk-choice-cell bulk-date-cell ${changed ? "changed" : ""} ${invalid ? "invalid" : ""}`}>
        <button
          type="button"
          data-bulk-entry
          disabled={!row.product}
          aria-label={t(labelKey)}
          aria-invalid={invalid}
          onClick={(event) => openBulkRowEditor(row, "offerDates", event.currentTarget)}
          onKeyDown={(event) => {
            const intent = bulkEnterIntent(event);
            if (!intent) return;
            event.preventDefault();
            if (intent === "next") openBulkRowEditor(row, "offerDates", event.currentTarget);
            else focusAdjacentBulkEntry(event.currentTarget, "previous");
          }}
        >
          <span>{value === "-" ? "-" : formatStockFilterDate(value, locale)}</span>
          <span className="filter-control-arrow" aria-hidden="true">v</span>
        </button>
      </div>
    );
  }

  function renderBulkPricePair(row: StockBulkEditRow, key: keyof StockInventoryRow, labelKey: string) {
    return (
      <div className="bulk-edit-pair">
        <span className={`bulk-before ${key === "name" ? "product-name-text" : ""}`}>{valueText(row.product?.[key])}</span>
        {renderBulkEditableCell(row, key, labelKey)}
      </div>
    );
  }

  function renderBulkPriceUseCell(row: StockBulkEditRow) {
    const rawMode = bulkValue(row, "discountType");
    const mode: BulkPriceUseMode = bulkPriceUseModes.includes(rawMode as BulkPriceUseMode)
      ? rawMode as BulkPriceUseMode
      : "NORMAL";
    const open = bulkPriceUseOpenRowId === row.id;
    const changed = Boolean(row.product) && rawMode !== valueText(row.product?.discountType);
    return (
      <div className={`bulk-price-use-cell ${changed ? "changed" : ""}`}>
        <button
          type="button"
          data-bulk-entry
          data-bulk-price-use-row={row.id}
          disabled={!row.product}
          aria-haspopup="listbox"
          aria-expanded={open}
          title={t("stock.bulkEdit.priceUseHint")}
          onDoubleClick={() => setBulkPriceUseOpenRowId(open ? null : row.id)}
          onKeyDown={(event) => {
            const intent = bulkEnterIntent(event);
            if (intent) {
              event.preventDefault();
              if (intent === "next") setBulkPriceUseOpenRowId(row.id);
              else focusAdjacentBulkEntry(event.currentTarget, "previous");
              return;
            }
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setBulkPriceUseOpenRowId(row.id);
            }
            if (event.key === "Escape") {
              setBulkPriceUseOpenRowId(null);
            }
          }}
        >
          {t(stockDiscountTypeLabel(mode))}
        </button>
        {open && (
          <div className="bulk-price-use-menu" role="listbox" aria-label={t("product.field.usePrice")}>
            {bulkPriceUseModes.map((option) => (
              <button
                type="button"
                role="option"
                aria-selected={option === mode}
                className={option === mode ? "selected" : ""}
                key={option}
                onClick={() => updateBulkPriceUse(row.id, option)}
                onKeyDown={(event) => {
                  const intent = bulkEnterIntent(event);
                  if (!intent) return;
                  event.preventDefault();
                  const trigger = bulkEntryElements().find((entry) => entry.dataset.bulkPriceUseRow === row.id);
                  setBulkPriceUseOpenRowId(null);
                  if (intent === "next") updateBulkPriceUse(row.id, option);
                  if (trigger) focusAdjacentBulkEntryAfterRender(trigger, intent);
                }}
              >
                {t(stockDiscountTypeLabel(option))}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  function renderBulkBenefit(row: StockBulkEditRow, priceKey: keyof StockInventoryRow) {
    if (!row.product) {
      return <div className="bulk-benefit empty">-</div>;
    }
    const purchasePrice = decimalNumber(row.draft.purchasePrice ?? row.product.purchasePrice);
    const price = decimalNumber(row.draft[priceKey] ?? row.product[priceKey]);
    const benefit = stockBenefitPercent(purchasePrice, price);
    const displayedBenefit = Number(benefit.toFixed(2));
    const progress = Math.max(0, Math.min(100, benefit));
    const inputKey = `${row.id}:${String(priceKey)}`;
    const inputValue = bulkBenefitInputs[inputKey] ?? String(displayedBenefit);

    function updateBenefit(value: string) {
      setBulkBenefitInputs((current) => ({ ...current, [inputKey]: value }));
      if (!value.trim()) {
        return;
      }
      const nextBenefit = decimalNumber(value);
      const nextPrice = stockPriceFromBenefit(purchasePrice, nextBenefit);
      if (nextPrice === null || nextPrice < 0) {
        return;
      }
      updateBulkDraft(row.id, priceKey, nextPrice.toFixed(2));
    }

    return (
      <div className="bulk-benefit" style={{ "--benefit-progress": `${progress}%` } as CSSProperties}>
        <label>
          <input
            data-bulk-entry
            className="bulk-benefit-number"
            type="number"
            step="0.01"
            max="99.99"
            aria-label={t("stock.column.benefit")}
            value={inputValue}
            onFocus={(event) => event.currentTarget.select()}
            onChange={(event) => updateBenefit(event.target.value)}
            onKeyDown={handleBulkEntryNavigation}
            onBlur={() => setBulkBenefitInputs((current) => {
              const next = { ...current };
              delete next[inputKey];
              return next;
            })}
          />
          <span>%</span>
        </label>
        <input
          className="bulk-benefit-range"
          type="range"
          min="0"
          max="99"
          step="0.1"
          aria-label={t("stock.bulkEdit.benefitBar")}
          value={Math.max(0, Math.min(99, benefit))}
          onChange={(event) => updateBenefit(event.target.value)}
        />
      </div>
    );
  }

  function renderBulkRows(columns: ReactNode) {
    return (
      <div className={`bulk-edit-table bulk-edit-${bulkEditTab}`} ref={bulkTableRef}>
        <div className="bulk-edit-row bulk-edit-head">
          {columns}
        </div>
        {filteredBulkRows.map((row) => renderBulkRow(row))}
      </div>
    );
  }

  function renderBulkRow(row: StockBulkEditRow) {
    const product = row.product;
    const rowClassName = `bulk-edit-row ${bulkPriceUseOpenRowId === row.id ? "menu-open" : ""} ${bulkValidationByRow.has(row.id) ? "invalid-row" : ""}`;
    const renderCommonStart = (includeImage = true) => (
      <>
        <label className="bulk-check">
          <input
            type="checkbox"
            data-bulk-entry
            checked={row.selected}
            onChange={() => toggleBulkSelected(row.id)}
            onKeyDown={handleBulkEntryNavigation}
          />
        </label>
        {includeImage && <ProductThumbnail product={product} token={session.accessToken ?? ""} />}
        {product ? (
          <span className={`bulk-code-value ${bulkCellInvalid(row, "productId") ? "invalid" : ""}`}>{product.code || "-"}</span>
        ) : (
          <label className="bulk-search-cell">
            <input
              ref={(element) => {
                if (element) {
                  bulkCodeInputRefs.current.set(row.id, element);
                } else {
                  bulkCodeInputRefs.current.delete(row.id);
                }
              }}
              aria-label={t("stock.column.code")}
              value={row.query}
              placeholder={t("stock.bulkEdit.firstCell")}
              onChange={(event) => updateBulkQuery(row.id, event.target.value)}
              onKeyDown={(event) => handleBulkQueryKeyDown(row.id, row.query, event)}
            />
          </label>
        )}
        <span>{product?.barcode ?? "-"}</span>
      </>
    );
    const commonStart = renderCommonStart();

    if (bulkEditTab === "info") {
      return (
        <div className={rowClassName} data-bulk-row-id={row.id} key={row.id}>
          {commonStart}
          {renderBulkPricePair(row, "name", "stock.column.name")}
          {renderBulkEditableCell(row, "description", "product.field.description")}
          {renderBulkFamilyCell(row, false, true)}
          {renderBulkTaxCell(row)}
          {renderBulkTaxesIncludedCell(row)}
        </div>
      );
    }

    if (bulkEditTab === "salePrice" || bulkEditTab === "memberPrice" || bulkEditTab === "wholesalePrice") {
      const priceKey = bulkEditTab === "salePrice" ? "salePrice" : bulkEditTab === "memberPrice" ? "memberPrice" : "wholesalePrice";
      const priceLabel = bulkEditTab === "salePrice" ? "stock.column.salePrice" : bulkEditTab === "memberPrice" ? "stock.column.memberPrice" : "stock.column.wholesalePrice";
      return (
        <div className={rowClassName} data-bulk-row-id={row.id} key={row.id}>
          {commonStart}
          <span className="product-name-text">{bulkValue(row, "name")}</span>
          {renderBulkPriceUseCell(row)}
          {renderBulkEditableCell(row, "purchaseDiscountPercent" as keyof StockInventoryRow, "stock.column.purchaseDiscount")}
          {renderBulkPricePair(row, "purchasePrice", "stock.column.purchasePrice")}
          {renderBulkPricePair(row, priceKey, priceLabel)}
          {renderBulkBenefit(row, priceKey)}
          {renderBulkFamilyCell(row, false, true)}
          <span>{product?.totalQuantity ?? "-"}</span>
        </div>
      );
    }

    if (bulkEditTab === "offer") {
      return (
        <div className={rowClassName} data-bulk-row-id={row.id} key={row.id}>
          {commonStart}
          <span className="product-name-text">{bulkValue(row, "name")}</span>
          {renderBulkPriceUseCell(row)}
          {renderBulkOfferActiveCell(row)}
          {renderBulkEditableCell(row, "purchaseDiscountPercent" as keyof StockInventoryRow, "stock.column.purchaseDiscount")}
          {renderBulkPricePair(row, "purchasePrice", "stock.column.purchasePrice")}
          {renderBulkPricePair(row, "offerPrice", "stock.column.offerPrice")}
          {renderBulkEditableCell(row, "offerDiscountPercent", "product.field.offerDiscountPercent")}
          <span>{bulkValue(row, "salePrice")}</span>
          {renderBulkBenefit(row, "offerPrice")}
          {renderBulkOfferDateCell(row, "offerFrom", "stock.column.offerFrom")}
          {renderBulkOfferDateCell(row, "offerUntil", "stock.column.offerUntil")}
          {renderBulkFamilyCell(row, false, true)}
          <span>{product?.totalQuantity ?? "-"}</span>
        </div>
      );
    }

    if (bulkEditTab === "image") {
      const suppliers = row.suppliers ?? [];
      const supplier = row.pendingSupplier
        ?? suppliers.find((value) => value.lastSupplier)
        ?? suppliers.find((value) => value.principal)
        ?? suppliers.at(-1);
      const additionalSupplierCount = suppliers.filter((value) => value.id !== supplier?.id).length;
      const supplierNames = [row.pendingSupplier, ...suppliers]
        .filter((value, index, values) => value && values.findIndex((item) => item?.id === value.id) === index)
        .map((value) => value?.legalName)
        .filter(Boolean)
        .join(", ");
      const lastEntryDate = supplier?.lastEntryAt ? new Date(supplier.lastEntryAt) : null;
      const lastEntryText = lastEntryDate && !Number.isNaN(lastEntryDate.getTime())
        ? supplierEntryDateFormatter.format(lastEntryDate)
        : "";
      return (
        <div className={rowClassName} data-bulk-row-id={row.id} key={row.id}>
          {renderCommonStart(false)}
          <span className="product-name-text">{bulkValue(row, "name")}</span>
          <div
            className={`bulk-supplier-cell ${row.pendingSupplier ? "changed" : ""}`}
            title={supplierNames || undefined}
          >
            <strong>{supplier?.legalName ?? "-"}</strong>
            {supplier && (
              <>
                <div className="bulk-supplier-flags">
                  {supplier.principal && <span>{t("stock.bulkEdit.supplierPrincipal")}</span>}
                  {supplier.lastSupplier && <span className="last">{t("stock.bulkEdit.supplierLast")}</span>}
                  {additionalSupplierCount > 0 && (
                    <span>{t("stock.bulkEdit.supplierMore").replace("{count}", String(additionalSupplierCount))}</span>
                  )}
                </div>
                <small>
                  {t("stock.bulkEdit.supplierGross")}: {valueText(supplier.grossPurchasePrice)} · {t("stock.bulkEdit.supplierDiscount")}: {valueText(supplier.purchaseDiscount)}% · {t("stock.bulkEdit.supplierNet")}: {valueText(supplier.netPurchasePrice)}
                </small>
                {lastEntryText && <small>{t("stock.bulkEdit.supplierLastEntry")}: {lastEntryText}</small>}
              </>
            )}
          </div>
          <span>{bulkValue(row, "salePrice")}</span>
          <ProductThumbnail product={product} token={session.accessToken ?? ""} className="old" />
          {product && bulkPendingImages[product.productId]
            ? <LocalImagePreview file={bulkPendingImages[product.productId]} />
            : <div className="bulk-image new">+</div>}
        </div>
      );
    }

    return (
      <div className={rowClassName} data-bulk-row-id={row.id} key={row.id}>
        {commonStart}
        {renderBulkEditableCell(row, "name", "stock.column.name")}
        {renderBulkEditableCell(row, "description", "product.field.description")}
        {renderBulkEditableCell(row, "purchasePrice", "stock.column.purchasePrice")}
        {renderBulkEditableCell(row, "purchaseDiscountPercent" as keyof StockInventoryRow, "stock.column.purchaseDiscount")}
        {renderBulkEditableCell(row, "salePrice", "stock.column.salePrice")}
        {renderBulkEditableCell(row, "memberPrice", "stock.column.memberPrice")}
        {renderBulkEditableCell(row, "wholesalePrice", "stock.column.wholesalePrice")}
        {renderBulkEditableCell(row, "offerPrice", "stock.column.offerPrice")}
        {renderBulkEditableCell(row, "offerDiscountPercent", "product.field.offerDiscountPercent")}
        {renderBulkPriceUseCell(row)}
        {renderBulkOfferActiveCell(row)}
        {renderBulkOfferDateCell(row, "offerFrom", "stock.column.offerFrom")}
        {renderBulkOfferDateCell(row, "offerUntil", "stock.column.offerUntil")}
        {renderBulkFamilyCell(row)}
        {renderBulkFamilyCell(row, true)}
        {renderBulkTaxCell(row)}
        {renderBulkTaxesIncludedCell(row)}
      </div>
    );
  }

  function renderBulkEditHeader() {
    const label = (labelKey: string, key = labelKey) => <span key={key}>{t(labelKey)}</span>;
    const pair = (labelKey: string, key = labelKey) => (
      <div className="bulk-pair-header" key={key}>
        <strong>{t(labelKey)}</strong>
        <span>
          <small>{t("stock.bulkEdit.before")}</small>
          <small>{t("stock.bulkEdit.after")}</small>
        </span>
      </div>
    );
    const base = [
      <label className="bulk-check bulk-check-all" key="select">
        <input
          type="checkbox"
          aria-label={t("stock.bulkEdit.selectAll")}
          checked={allVisibleBulkRowsSelected}
          disabled={selectableBulkRows.length === 0}
          onChange={(event) => toggleAllBulkRows(event.target.checked)}
        />
      </label>,
      <span key="image">{t("stock.column.image")}</span>,
      <span key="code">{t("stock.column.code")}</span>,
      <span key="barcode">{t("stock.column.barcode")}</span>
    ];
    if (bulkEditTab === "info") {
      return [
        ...base,
        pair("stock.column.name"),
        label("product.field.description"),
        label("stock.column.family"),
        label("stock.column.tax"),
        label("stock.column.taxIncluded")
      ];
    }
    if (bulkEditTab === "salePrice" || bulkEditTab === "memberPrice" || bulkEditTab === "wholesalePrice") {
      const priceLabel = bulkEditTab === "salePrice" ? "stock.column.salePrice" : bulkEditTab === "memberPrice" ? "stock.column.memberPrice" : "stock.column.wholesalePrice";
      return [
        ...base,
        label("stock.column.name"),
        label("product.field.usePrice"),
        label("stock.column.purchaseDiscount"),
        pair("stock.column.purchasePrice"),
        pair(priceLabel),
        label("stock.column.benefit"),
        label("stock.column.family"),
        label("stock.column.totalStock")
      ];
    }
    if (bulkEditTab === "offer") {
      return [
        ...base,
        label("stock.column.name"),
        label("product.field.usePrice"),
        label("stock.column.offerActive"),
        label("stock.column.purchaseDiscount"),
        pair("stock.column.purchasePrice"),
        pair("stock.column.offerPrice"),
        label("product.field.offerDiscountPercent"),
        label("stock.column.salePrice"),
        label("stock.column.benefit"),
        label("stock.column.offerFrom"),
        label("stock.column.offerUntil"),
        label("stock.column.family"),
        label("stock.column.totalStock")
      ];
    }
    if (bulkEditTab === "image") {
      return [
        base[0],
        base[2],
        base[3],
        label("stock.column.name"),
        label("stock.column.supplier"),
        label("stock.column.salePrice"),
        label("stock.bulkEdit.oldImage"),
        label("stock.bulkEdit.newImage")
      ];
    }
    return [
      ...base,
      label("stock.column.name"),
      label("product.field.description"),
      label("stock.column.purchasePrice"),
      label("stock.column.purchaseDiscount"),
      label("stock.column.salePrice"),
      label("stock.column.memberPrice"),
      label("stock.column.wholesalePrice"),
      label("stock.column.offerPrice"),
      label("product.field.offerDiscountPercent"),
      label("product.field.usePrice"),
      label("stock.column.offerActive"),
      label("stock.column.offerFrom"),
      label("stock.column.offerUntil"),
      label("stock.column.family"),
      label("stock.column.subfamily"),
      label("stock.column.tax"),
      label("stock.column.taxIncluded")
    ];
  }

  function renderBulkFileMenu() {
    if (!bulkFileOpen) {
      return null;
    }
    return (
      <div className="bulk-edit-menu bulk-file-menu" role="menu">
        {stockBulkFileMenuItems.map((item) => item === "stock.bulkEdit.import" ? (
          <div className="bulk-edit-submenu-anchor" key={item}>
            <button
              type="button"
              className="bulk-edit-submenu-trigger"
              aria-haspopup="menu"
              aria-expanded={bulkImportOpen}
              onClick={() => setBulkImportOpen((current) => !current)}
              onKeyDown={(event) => {
                if (event.key === "ArrowRight") {
                  event.preventDefault();
                  setBulkImportOpen(true);
                } else if (event.key === "ArrowLeft") {
                  event.preventDefault();
                  setBulkImportOpen(false);
                }
              }}
            >
              <span>{t(item)}</span>
              <span className="bulk-edit-submenu-chevron" aria-hidden="true">›</span>
            </button>
            {bulkImportOpen && (
              <div className="bulk-edit-menu bulk-import-menu" role="menu" aria-label={t(item)}>
                {stockBulkImportMenuItems.map((importItem) => (
                  <button type="button" key={importItem} onClick={() => handleBulkFileAction(importItem)}>
                    {t(importItem)}
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <button type="button" key={item} onClick={() => handleBulkFileAction(item)}>
            {t(item)}
          </button>
        ))}
      </div>
    );
  }

  function renderBulkLeftActions() {
    if (bulkEditTab === "image") {
      return (
        <>
          <button type="button" onClick={() => bulkImagePanelRef.current?.openFolder()}>
            {t("stock.bulkEdit.openFolder")}
          </button>
          <button
            type="button"
            disabled={bulkImageSnapshot.rows.length === 0}
            onClick={() => bulkImagePanelRef.current?.compareByName()}
          >
            {t("stock.bulkEdit.compareName")}
          </button>
          <button
            type="button"
            disabled={bulkImageSnapshot.rows.length === 0}
            onClick={() => bulkImagePanelRef.current?.compareByCode()}
          >
            {t("stock.bulkEdit.compareCode")}
          </button>
        </>
      );
    }
    const priceTabs: StockBulkEditTab[] = ["salePrice", "memberPrice", "wholesalePrice", "offer"];
    return (
      <>
        <div className="bulk-edit-file" ref={bulkFileMenuRef}>
          <button type="button" aria-haspopup="menu" aria-expanded={bulkFileOpen} onClick={toggleBulkFileMenu}>{t("stock.bulkEdit.file")}</button>
          {renderBulkFileMenu()}
        </div>
        {priceTabs.includes(bulkEditTab) && (
          <>
            <button type="button" onClick={openBulkDecimalDialog}>{t("stock.bulkEdit.decimalAdjust")}</button>
            <button type="button" onClick={() => void openBulkPriceRulesDialog()}>{t("stock.bulkEdit.priceRule")}</button>
          </>
        )}
      </>
    );
  }

  function renderBulkEditSelectedControl() {
    const actions = stockBulkSelectedActionsByTab[bulkEditTab];
    const selectedCount = selectedBulkRowIds().length;
    return (
      <div className="bulk-edit-selected" ref={bulkEditSelectedRef}>
        <button type="button" aria-haspopup="menu" aria-expanded={bulkEditSelectedOpen} onClick={toggleBulkEditSelectedMenu}>
          <span>{t("stock.bulkEdit.editSelected")}</span>
          {selectedCount > 0 && <strong>{selectedCount}</strong>}
        </button>
        {bulkEditSelectedOpen && (
          <div className="bulk-edit-menu bulk-edit-selected-menu" role="menu">
            {actions.map((action) => (
              <button type="button" key={action} onClick={() => handleBulkSelectedAction(action)}>
                {t(bulkSelectedActionLabel(action))}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  function renderBulkEditorDialog() {
    const editor = bulkEditorDialog;
    if (!editor) {
      return null;
    }
    const titleKey = editor.kind === "value"
      ? editor.labelKey
      : editor.kind === "benefit"
        ? "stock.column.benefit"
      : editor.kind === "family"
        ? "stock.column.family"
        : editor.kind === "tax"
          ? "stock.column.tax"
          : editor.kind === "priceUse"
            ? "product.field.usePrice"
            : "product.field.offerRange";
    const numericValue = editor.kind === "value" && editor.value.trim()
      ? Number(editor.value.replace(",", "."))
      : null;
    const valueRequired = editor.kind === "value"
      && (editor.field === "purchasePrice" || editor.field === "salePrice");
    const valueInvalid = editor.kind === "value" && (
      (valueRequired && !editor.value.trim())
      || (editor.value.trim() !== "" && (!Number.isFinite(numericValue) || Number(numericValue) < 0))
      || (editor.field === "offerDiscountPercent" && numericValue !== null && numericValue > 100)
    );
    const benefitValue = editor.kind === "benefit" && editor.value.trim()
      ? Number(editor.value.replace(",", "."))
      : null;
    const benefitInvalid = editor.kind === "benefit"
      && (benefitValue === null || !Number.isFinite(benefitValue) || benefitValue < 0 || benefitValue >= 100);
    const applyDisabled = (editor.kind === "family" && !editor.familyId)
      || (editor.kind === "tax" && !editor.taxId)
      || valueInvalid
      || benefitInvalid;
    const search = normalizeBulkQuery(bulkEditorSearch);
    const visibleFamilies = editor.kind === "family"
      ? inventoryFamilyTree.filter((family) => !search
        || normalizeBulkQuery(family.name).includes(search)
        || family.subfamilies.some((subfamily) => normalizeBulkQuery(subfamily.name).includes(search)))
      : [];
    const visibleTaxes = editor.kind === "tax"
      ? inventoryTaxOptions.filter((tax) => !search || normalizeBulkQuery(tax.label).includes(search))
      : [];

    return (
      <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-editor-title">
        <section className={`filter-dialog bulk-workspace-dialog bulk-editor-dialog bulk-editor-${editor.kind}`}>
          <header className="filter-header">
            <h2 id="bulk-editor-title">{t(titleKey)}</h2>
            <button type="button" onClick={() => setBulkEditorDialog(null)}>{t("common.close")}</button>
          </header>

          {editor.kind === "value" && (
            <label className="bulk-dialog-field bulk-value-editor-field">
              <span>{t(editor.labelKey)}</span>
              <input
                autoFocus
                inputMode="decimal"
                aria-invalid={valueInvalid}
                value={editor.value}
                onChange={(event) => setBulkEditorDialog({ ...editor, value: event.target.value })}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !valueInvalid) {
                    event.preventDefault();
                    applyBulkEditorDialog();
                  }
                }}
              />
            </label>
          )}

          {editor.kind === "benefit" && (
            <label className="bulk-dialog-field bulk-value-editor-field">
              <span>{t("stock.column.benefit")} %</span>
              <input
                autoFocus
                type="number"
                min="0"
                max="99.99"
                step="0.01"
                aria-invalid={benefitInvalid}
                value={editor.value}
                onChange={(event) => setBulkEditorDialog({ ...editor, value: event.target.value })}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !benefitInvalid) {
                    event.preventDefault();
                    applyBulkEditorDialog();
                  }
                }}
              />
            </label>
          )}

          {(editor.kind === "family" || editor.kind === "tax") && (
            <label className="report-search bulk-finder-search bulk-editor-search">
              <img alt="" src={stockSearchIcon} />
              <input
                autoFocus
                type="search"
                value={bulkEditorSearch}
                aria-label={t("stock.bulkEdit.searchOptions")}
                 placeholder={t("stock.bulkEdit.searchOptions")}
                 onChange={(event) => setBulkEditorSearch(event.target.value)}
                 onKeyDown={(event) => {
                   const intent = bulkEnterIntent(event);
                   if (intent === "next" && !applyDisabled) {
                     event.preventDefault();
                     applyBulkEditorDialog();
                   }
                 }}
               />
            </label>
          )}

          {editor.kind === "family" && (
            <div className="stock-family-list bulk-editor-family-list">
              {visibleFamilies.length === 0 && <span className="stock-empty-state">{t("stock.filter.noFamilies")}</span>}
              {visibleFamilies.map((family) => {
                const expanded = Boolean(bulkExpandedFamilies[family.id]) || Boolean(search);
                const visibleSubfamilies = family.subfamilies.filter((subfamily) => !search
                  || normalizeBulkQuery(family.name).includes(search)
                  || normalizeBulkQuery(subfamily.name).includes(search));
                const selected = editor.familyId === family.id && !editor.subfamilyId;
                return (
                  <div className="stock-family-group" key={family.id}>
                    <div className={`stock-family-row ${selected ? "selected" : ""}`}>
                      <button
                        type="button"
                        className="stock-family-expand"
                        disabled={family.subfamilies.length === 0}
                        aria-expanded={expanded}
                        onClick={() => setBulkExpandedFamilies((current) => ({ ...current, [family.id]: !expanded }))}
                      >
                        {family.subfamilies.length > 0 ? (expanded ? "v" : ">") : ""}
                      </button>
                      <button
                        type="button"
                        className="stock-family-choice"
                        onClick={() => setBulkEditorDialog({ ...editor, familyId: family.id, subfamilyId: "" })}
                         onDoubleClick={() => {
                           if (applyBulkFamilyValues(editor.rowIds, family.id, "")) {
                             finishBulkEditor(editor.rowIds);
                           }
                         }}
                         onKeyDown={(event) => {
                           const intent = bulkEnterIntent(event);
                           if (intent !== "next") return;
                           event.preventDefault();
                           if (applyBulkFamilyValues(editor.rowIds, family.id, "")) {
                             finishBulkEditor(editor.rowIds);
                           }
                         }}
                       >
                        {family.name}
                      </button>
                    </div>
                    {expanded && visibleSubfamilies.length > 0 && (
                      <div className="stock-subfamily-list">
                        {visibleSubfamilies.map((subfamily) => (
                          <button
                            type="button"
                            className={editor.subfamilyId === subfamily.id ? "selected" : ""}
                            key={subfamily.id}
                            onClick={() => setBulkEditorDialog({ ...editor, familyId: family.id, subfamilyId: subfamily.id })}
                             onDoubleClick={() => {
                               if (applyBulkFamilyValues(editor.rowIds, family.id, subfamily.id)) {
                                 finishBulkEditor(editor.rowIds);
                               }
                             }}
                             onKeyDown={(event) => {
                               const intent = bulkEnterIntent(event);
                               if (intent !== "next") return;
                               event.preventDefault();
                               if (applyBulkFamilyValues(editor.rowIds, family.id, subfamily.id)) {
                                 finishBulkEditor(editor.rowIds);
                               }
                             }}
                           >
                            {subfamily.name}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {editor.kind === "tax" && (
            <div className="bulk-editor-option-list" role="listbox" aria-label={t("stock.column.tax")}>
              {visibleTaxes.length === 0 && <span className="stock-empty-state">{t("stock.bulkEdit.noOptions")}</span>}
              {visibleTaxes.map((tax) => (
                <button
                  type="button"
                  role="option"
                  aria-selected={editor.taxId === tax.value}
                  className={editor.taxId === tax.value ? "selected" : ""}
                  key={tax.value}
                  onClick={() => setBulkEditorDialog({ ...editor, taxId: tax.value })}
                   onDoubleClick={() => {
                     if (applyBulkTaxValue(editor.rowIds, tax.value)) {
                       finishBulkEditor(editor.rowIds);
                     }
                   }}
                   onKeyDown={(event) => {
                     const intent = bulkEnterIntent(event);
                     if (intent !== "next") return;
                     event.preventDefault();
                     if (applyBulkTaxValue(editor.rowIds, tax.value)) {
                       finishBulkEditor(editor.rowIds);
                     }
                   }}
                 >
                  {tax.label}
                </button>
              ))}
            </div>
          )}

          {editor.kind === "priceUse" && (
            <div className="bulk-editor-option-list bulk-editor-price-use" role="radiogroup" aria-label={t("product.field.usePrice")}>
              {editor.options.map((option) => (
                <button
                  type="button"
                  role="radio"
                  aria-checked={editor.value === option}
                  className={editor.value === option ? "selected" : ""}
                  key={option}
                  onClick={() => setBulkEditorDialog({ ...editor, value: option })}
                   onDoubleClick={() => {
                     applyBulkPriceUse(editor.rowIds, option);
                     finishBulkEditor(editor.rowIds);
                   }}
                   onKeyDown={(event) => {
                     const intent = bulkEnterIntent(event);
                     if (intent !== "next") return;
                     event.preventDefault();
                     applyBulkPriceUse(editor.rowIds, option);
                     finishBulkEditor(editor.rowIds);
                   }}
                 >
                  {t(stockDiscountTypeLabel(option))}
                </button>
              ))}
            </div>
          )}

          {editor.kind === "dates" && (
            <div className="bulk-editor-date-panel">
              <div className="date-range-strip">
                <div className={`date-range-strip-cell ${editor.rangeStart ? "" : "active"}`}>
                  <span>{t("salesReport.filter.dateFrom")}</span>
                  <strong>{editor.offerFrom ? formatStockFilterDate(editor.offerFrom, locale) : "-"}</strong>
                </div>
                <div className={`date-range-strip-cell ${editor.rangeStart ? "active" : ""}`}>
                  <span>{t("salesReport.filter.dateTo")}</span>
                  <strong>{editor.offerUntil ? formatStockFilterDate(editor.offerUntil, locale) : t("product.field.noEnd")}</strong>
                </div>
              </div>
              <header className="date-calendar-header">
                <button type="button" onClick={() => setBulkEditorDialog({ ...editor, calendarMonth: new Date(editor.calendarMonth.getFullYear(), editor.calendarMonth.getMonth() - 1, 1) })}>{"<"}</button>
                <strong>{new Intl.DateTimeFormat(calendarLocale, { month: "long", year: "numeric" }).format(editor.calendarMonth)}</strong>
                <button type="button" onClick={() => setBulkEditorDialog({ ...editor, calendarMonth: new Date(editor.calendarMonth.getFullYear(), editor.calendarMonth.getMonth() + 1, 1) })}>{">"}</button>
              </header>
              <div className="date-calendar-grid">
                {stockWeekdayLabels(locale).map((weekday) => <span className="date-weekday" key={weekday}>{weekday}</span>)}
                {buildStockCalendarDays(editor.calendarMonth).map((day, index) => day ? (() => {
                  const selected = todayIsoDate(day);
                  const selectedBoundary = selected === editor.offerFrom || selected === editor.offerUntil;
                  const inRange = Boolean(editor.offerFrom && editor.offerUntil && selected > editor.offerFrom && selected < editor.offerUntil);
                  return (
                    <button
                      type="button"
                      className={["date-day", selectedBoundary ? "selected" : "", inRange ? "in-range" : ""].filter(Boolean).join(" ")}
                      key={selected}
                       onClick={() => {
                        if (!editor.rangeStart) {
                          setBulkEditorDialog({ ...editor, offerFrom: selected, offerUntil: selected, rangeStart: selected });
                          return;
                        }
                        const offerFrom = editor.rangeStart <= selected ? editor.rangeStart : selected;
                        const offerUntil = editor.rangeStart <= selected ? selected : editor.rangeStart;
                         setBulkEditorDialog({ ...editor, offerFrom, offerUntil, rangeStart: null });
                       }}
                       onKeyDown={(event) => {
                         const intent = bulkEnterIntent(event);
                         if (intent !== "next") return;
                         event.preventDefault();
                         if (!editor.rangeStart) {
                           setBulkEditorDialog({ ...editor, offerFrom: selected, offerUntil: selected, rangeStart: selected });
                           return;
                         }
                         const offerFrom = editor.rangeStart <= selected ? editor.rangeStart : selected;
                         const offerUntil = editor.rangeStart <= selected ? selected : editor.rangeStart;
                         patchBulkRows(editor.rowIds, () => ({ offerFrom, offerUntil }), ["offerFrom", "offerUntil"]);
                         finishBulkEditor(editor.rowIds);
                       }}
                     >
                      {day.getDate()}
                    </button>
                  );
                })() : <span className="date-day empty" key={`bulk-empty-${index}`} />)}
              </div>
              <div className="bulk-editor-date-actions">
                <span>{editor.offerFrom ? stockSelectedDaysText(stockDateRangeDayCount(editor.offerFrom, editor.offerUntil), locale) : t("salesReport.filter.pickDateFrom")}</span>
                <button type="button" onClick={() => setBulkEditorDialog({ ...editor, offerFrom: "", offerUntil: "", rangeStart: null })}>
                  {t("salesReport.filter.clear")}
                </button>
                <button
                  type="button"
                  disabled={!editor.offerFrom}
                  onClick={() => setBulkEditorDialog({ ...editor, offerUntil: "", rangeStart: null })}
                  onKeyDown={(event) => {
                    const intent = bulkEnterIntent(event);
                    if (intent !== "next" || !editor.offerFrom) return;
                    event.preventDefault();
                    patchBulkRows(editor.rowIds, () => ({ offerFrom: editor.offerFrom, offerUntil: "-" }), ["offerFrom", "offerUntil"]);
                    finishBulkEditor(editor.rowIds);
                  }}
                >
                  {t("product.field.noEnd")}
                </button>
              </div>
            </div>
          )}

          <footer className="filter-actions">
            <button type="button" onClick={() => setBulkEditorDialog(null)}>{t("common.cancel")}</button>
            <button type="button" disabled={applyDisabled} onClick={applyBulkEditorDialog}>{t("stock.filter.apply")}</button>
          </footer>
        </section>
      </div>
    );
  }

  function renderBulkFinder() {
    if (!bulkFinder) {
      return null;
    }
    return (
      <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-finder-title">
        <section className="filter-dialog bulk-finder-dialog">
          <header className="filter-header">
            <h2 id="bulk-finder-title">{t("stock.bulkEdit.finderTitle")}</h2>
            <button type="button" onClick={() => setBulkFinder(null)}>{t("common.close")}</button>
          </header>
          <label className="report-search bulk-finder-search">
            <img alt="" src={stockSearchIcon} />
            <input
              autoFocus
              type="search"
              value={bulkFinder.query}
              onChange={(event) => setBulkFinder({ ...bulkFinder, query: event.target.value })}
              onKeyDown={(event) => {
                const intent = bulkEnterIntent(event);
                if (intent === "next" && bulkFinderMatches[0]) {
                  event.preventDefault();
                  assignBulkProduct(bulkFinder.rowId, bulkFinderMatches[0]);
                }
              }}
            />
          </label>
          <div className="bulk-finder-list">
            {bulkFinderMatches.length === 0 && <span className="stock-empty-state">{t("stock.bulkEdit.noMatches")}</span>}
            {bulkFinderMatches.map((product) => (
              <button type="button" className="bulk-finder-row" key={product.productId} onDoubleClick={() => assignBulkProduct(bulkFinder.rowId, product)} onClick={() => assignBulkProduct(bulkFinder.rowId, product)}>
                <strong className="product-name-text">{product.name}</strong>
                <span>{product.code} - {product.barcode}</span>
              </button>
            ))}
          </div>
        </section>
      </div>
    );
  }

  function renderBulkSupplierDialog() {
    if (!bulkSupplierDialogMode) {
      return null;
    }
    const selectedSupplier = bulkSupplierOptions.find(
      (supplier) => supplier.id === bulkSelectedSupplierId
    );
    const assignMode = bulkSupplierDialogMode === "assign";
    return (
      <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-supplier-title">
        <section className="filter-dialog bulk-workspace-dialog bulk-supplier-dialog">
          <header className="filter-header">
            <h2 id="bulk-supplier-title">
              {t(assignMode ? "stock.bulkEdit.assignSupplier" : "stock.bulkEdit.importSupplier")}
            </h2>
            <button type="button" onClick={() => setBulkSupplierDialogMode(null)}>{t("common.close")}</button>
          </header>
          <label className="report-search bulk-finder-search">
            <img alt="" src={stockSearchIcon} />
            <input
              autoFocus
              type="search"
              value={bulkSupplierSearch}
              aria-label={t("stock.bulkEdit.searchSupplier")}
               placeholder={t("stock.bulkEdit.searchSupplier")}
               onChange={(event) => setBulkSupplierSearch(event.target.value)}
               onKeyDown={(event) => {
                 const intent = bulkEnterIntent(event);
                 const candidate = selectedSupplier ?? filteredBulkSupplierOptions.find((supplier) => !assignMode || supplier.active);
                 if (intent === "next" && candidate) {
                   event.preventDefault();
                   void applyBulkSupplier(candidate);
                 }
               }}
            />
          </label>
          <div className="bulk-supplier-list" role="listbox">
            {!bulkBusy && filteredBulkSupplierOptions.length === 0 && (
              <span className="stock-empty-state">{t("stock.bulkEdit.noSuppliers")}</span>
            )}
            {filteredBulkSupplierOptions.map((supplier) => {
              const disabled = assignMode && !supplier.active;
              return (
                <button
                  type="button"
                  role="option"
                  aria-selected={supplier.id === bulkSelectedSupplierId}
                  className={supplier.id === bulkSelectedSupplierId ? "selected" : ""}
                  disabled={disabled}
                  key={supplier.id}
                   onClick={() => setBulkSelectedSupplierId(supplier.id)}
                   onDoubleClick={() => void applyBulkSupplier(supplier)}
                   onKeyDown={(event) => {
                     const intent = bulkEnterIntent(event);
                     if (intent === "next") {
                       event.preventDefault();
                       void applyBulkSupplier(supplier);
                     }
                   }}
                >
                  <strong>{supplier.legalName}</strong>
                  <span>{supplier.supplierCode || "-"} · {supplier.documentNumber}</span>
                  <em>{t(supplier.active ? "stock.bulkEdit.supplierActive" : "stock.bulkEdit.supplierInactive")}</em>
                </button>
              );
            })}
          </div>
          <footer className="filter-actions">
            <button type="button" className="secondary" onClick={() => setBulkSupplierDialogMode(null)}>{t("common.cancel")}</button>
            <button
              type="button"
              disabled={!selectedSupplier || (assignMode && !selectedSupplier.active) || bulkBusy}
              onClick={() => void applyBulkSupplier()}
            >
              {t("stock.bulkEdit.applySupplier")}
            </button>
          </footer>
        </section>
      </div>
    );
  }

  function renderBulkPurchaseDocumentDialog() {
    if (!bulkPurchaseDocumentKind) {
      return null;
    }
    const config = bulkPurchaseDocumentConfig[bulkPurchaseDocumentKind];
    const selectedDocument = bulkPurchaseDocuments.find(
      (document) => document.id === bulkSelectedPurchaseDocumentId
    );
    const dateFormatter = new Intl.DateTimeFormat(
      locale === "zh" ? "zh-CN" : locale,
      { dateStyle: "short" }
    );
    const moneyFormatter = new Intl.NumberFormat(
      locale === "zh" ? "zh-CN" : locale,
      { style: "currency", currency: "EUR" }
    );
    return (
      <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-purchase-document-title">
        <section className="filter-dialog bulk-workspace-dialog bulk-purchase-document-dialog">
          <header className="filter-header">
            <h2 id="bulk-purchase-document-title">{t(config.titleKey)}</h2>
            <button type="button" onClick={() => setBulkPurchaseDocumentKind(null)}>{t("common.close")}</button>
          </header>
          <label className="report-search bulk-finder-search">
            <img alt="" src={stockSearchIcon} />
            <input
              autoFocus
              type="search"
              value={bulkPurchaseDocumentSearch}
              aria-label={t(config.searchKey)}
               placeholder={t(config.searchKey)}
               onChange={(event) => setBulkPurchaseDocumentSearch(event.target.value)}
               onKeyDown={(event) => {
                 const intent = bulkEnterIntent(event);
                 const candidate = selectedDocument ?? filteredBulkPurchaseDocuments[0];
                 if (intent === "next" && candidate) {
                   event.preventDefault();
                   void applyBulkPurchaseDocument(candidate);
                 }
               }}
            />
          </label>
          <div className="bulk-purchase-document-list" role="listbox">
            {!bulkBusy && filteredBulkPurchaseDocuments.length === 0 && (
              <span className="stock-empty-state">{t(config.emptyKey)}</span>
            )}
            {filteredBulkPurchaseDocuments.map((document) => (
              <button
                type="button"
                role="option"
                aria-selected={document.id === bulkSelectedPurchaseDocumentId}
                className={document.id === bulkSelectedPurchaseDocumentId ? "selected" : ""}
                key={document.id}
                 onClick={() => setBulkSelectedPurchaseDocumentId(document.id)}
                 onDoubleClick={() => void applyBulkPurchaseDocument(document)}
                 onKeyDown={(event) => {
                   const intent = bulkEnterIntent(event);
                   if (intent === "next") {
                     event.preventDefault();
                     void applyBulkPurchaseDocument(document);
                   }
                 }}
              >
                <strong>{document.number || t("stock.bulkEdit.purchaseInvoiceWithoutNumber")}</strong>
                <span>{document.supplierName || "-"} · {dateFormatter.format(new Date(`${document.date}T00:00:00`))}</span>
                <span className="bulk-document-amount">{moneyFormatter.format(Number(document.total) || 0)} · {t("stock.bulkEdit.purchaseInvoiceProducts").replace("{count}", String(document.productCount))}</span>
                <em>{t(`stock.bulkEdit.purchaseInvoiceStatus.${document.status}`)}</em>
              </button>
            ))}
          </div>
          <footer className="filter-actions">
            <button type="button" className="secondary" onClick={() => setBulkPurchaseDocumentKind(null)}>{t("common.cancel")}</button>
            <button type="button" disabled={!selectedDocument || bulkBusy} onClick={() => void applyBulkPurchaseDocument()}>
              {t("stock.bulkEdit.applySupplier")}
            </button>
          </footer>
        </section>
      </div>
    );
  }

  function renderBulkWorkspaceDialog() {
    if (!bulkDialog) {
      return null;
    }
    const close = () => {
      setBulkDialog(null);
      setBulkAfterSave(null);
      setBulkDeleteDraft(null);
      setBulkRenameDraft(null);
      setBulkRenameValue("");
    };
    const acceptEnter = (
      event: KeyboardEvent<HTMLElement>,
      action: () => void,
      disabled = false
    ) => {
      if (event.defaultPrevented || disabled || bulkEnterIntent(event) !== "next") return;
      if ((event.target as HTMLElement).closest("button, [role='option'], [role='radio']")) return;
      event.preventDefault();
      action();
    };
    if (bulkDialog === "save") {
      return (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-save-title">
          <section
            className="filter-dialog bulk-workspace-dialog bulk-small-dialog"
            onKeyDown={(event) => acceptEnter(
              event,
              () => void saveBulkDraft(),
              !bulkDraftName.trim() || bulkBusy
            )}
          >
            <header className="filter-header">
              <h2 id="bulk-save-title">{t("stock.bulkEdit.saveList")}</h2>
              <button type="button" onClick={close}>{t("common.close")}</button>
            </header>
            <label className="bulk-dialog-field">
              <span>{t("stock.bulkEdit.listName")}</span>
              <input autoFocus maxLength={160} value={bulkDraftName} onChange={(event) => setBulkDraftName(event.target.value)} />
            </label>
            <footer className="filter-actions">
              <button type="button" className="secondary" onClick={close}>{t("common.cancel")}</button>
              <button type="button" disabled={!bulkDraftName.trim() || bulkBusy} onClick={() => void saveBulkDraft()}>{t("common.save")}</button>
            </footer>
          </section>
        </div>
      );
    }
    if (bulkDialog === "rename") {
      return (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-rename-title">
          <section
            className="filter-dialog bulk-workspace-dialog bulk-small-dialog"
            onKeyDown={(event) => acceptEnter(
              event,
              () => void renameBulkDraft(),
              !bulkRenameValue.trim() || bulkBusy || bulkRenameValue.trim() === bulkRenameDraft?.name
            )}
          >
            <header className="filter-header">
              <h2 id="bulk-rename-title">{t("stock.bulkEdit.workspace.rename")}</h2>
              <button type="button" onClick={close}>{t("common.close")}</button>
            </header>
            <label className="bulk-dialog-field">
              <span>{t("stock.bulkEdit.listName")}</span>
              <input
                autoFocus
                maxLength={160}
                value={bulkRenameValue}
                onChange={(event) => setBulkRenameValue(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    void renameBulkDraft();
                  }
                }}
              />
            </label>
            <footer className="filter-actions">
              <button type="button" className="secondary" onClick={close}>{t("common.cancel")}</button>
              <button
                type="button"
                disabled={!bulkRenameValue.trim() || bulkBusy || bulkRenameValue.trim() === bulkRenameDraft?.name}
                onClick={() => void renameBulkDraft()}
              >
                {t("common.save")}
              </button>
            </footer>
          </section>
        </div>
      );
    }
    if (bulkDialog === "comments") {
      return (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-comments-title">
          <section
            className="filter-dialog bulk-workspace-dialog bulk-comments-dialog"
            onKeyDown={(event) => acceptEnter(
              event,
              () => void addBulkComment(),
              !bulkCommentText.trim() || bulkBusy
            )}
          >
            <header className="filter-header">
              <h2 id="bulk-comments-title">{t("stock.bulkEdit.comments")}</h2>
              <button type="button" onClick={close}>{t("common.close")}</button>
            </header>
            <div className="bulk-comment-list">
              {activeBulkDraft?.comments.length === 0 && <span className="stock-empty-state">{t("stock.bulkEdit.noComments")}</span>}
              {activeBulkDraft?.comments.map((comment) => (
                <article key={comment.id}>
                  <strong>{comment.username}</strong>
                  <time>{new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale, { dateStyle: "short", timeStyle: "short" }).format(new Date(comment.createdAt))}</time>
                  <p>{comment.text}</p>
                </article>
              ))}
            </div>
            <label className="bulk-dialog-field">
              <span>{t("stock.bulkEdit.newComment")}</span>
              <textarea autoFocus maxLength={1000} value={bulkCommentText} onChange={(event) => setBulkCommentText(event.target.value)} />
            </label>
            <footer className="filter-actions">
              <button type="button" className="secondary" onClick={close}>{t("common.close")}</button>
              <button type="button" disabled={!bulkCommentText.trim() || bulkBusy} onClick={() => void addBulkComment()}>{t("stock.bulkEdit.addComment")}</button>
            </footer>
          </section>
        </div>
      );
    }
    const dialogConfig = bulkDialog === "clear"
      ? { title: "stock.bulkEdit.clearList", body: "stock.bulkEdit.clearConfirm", action: clearBulkList }
      : bulkDialog === "apply"
        ? { title: "stock.bulkEdit.applyChanges", body: "stock.bulkEdit.applyConfirm", action: () => void applyBulkChanges() }
        : bulkDialog === "delete"
          ? { title: "stock.bulkEdit.delete", body: "stock.bulkEdit.deleteConfirm", action: () => void deleteBulkDraft() }
          : null;
    if (dialogConfig) {
      return (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-confirm-title">
          <section
            className="filter-dialog bulk-workspace-dialog bulk-small-dialog"
            onKeyDown={(event) => acceptEnter(event, dialogConfig.action, bulkBusy)}
          >
            <header className="filter-header">
              <h2 id="bulk-confirm-title">{t(dialogConfig.title)}</h2>
              <button type="button" onClick={close}>{t("common.close")}</button>
            </header>
            <p className="bulk-confirm-copy">{t(dialogConfig.body)}</p>
            <footer className="filter-actions">
              <button type="button" className="secondary" onClick={close}>{t("common.cancel")}</button>
              <button autoFocus type="button" disabled={bulkBusy} onClick={dialogConfig.action}>{t("common.confirm")}</button>
            </footer>
          </section>
        </div>
      );
    }
    return (
      <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="bulk-close-title">
        <section
          className="filter-dialog bulk-workspace-dialog bulk-small-dialog"
          onKeyDown={(event) => acceptEnter(event, () => void saveBulkDraft("close"), bulkBusy)}
        >
          <header className="filter-header">
            <h2 id="bulk-close-title">{t("stock.bulkEdit.closeList")}</h2>
            <button type="button" onClick={close}>{t("common.close")}</button>
          </header>
          <p className="bulk-confirm-copy">{t("stock.bulkEdit.closeConfirm")}</p>
          <footer className="filter-actions bulk-close-actions">
            <button type="button" className="secondary" onClick={close}>{t("common.cancel")}</button>
            <button type="button" className="secondary" onClick={resetBulkWorkspace}>{t("stock.bulkEdit.discard")}</button>
            <button autoFocus type="button" disabled={bulkBusy} onClick={() => void saveBulkDraft("close")}>{t("stock.bulkEdit.saveAndClose")}</button>
          </footer>
        </section>
      </div>
    );
  }

  function activeBulkFilterSummary() {
    const items: string[] = [];
    if (bulkFilters.productType) items.push(t(`product.type.${bulkFilters.productType.toLowerCase()}`));
    if (bulkFilters.familyId) {
      const family = inventoryFamilyTree.find((value) => value.id === bulkFilters.familyId);
      items.push(`${t("stock.column.family")}: ${family?.name ?? bulkFilters.familyId}`);
      if (bulkFilters.subfamilyId) {
        const subfamily = family?.subfamilies.find((value) => value.id === bulkFilters.subfamilyId);
        items.push(`${t("stock.column.subfamily")}: ${subfamily?.name ?? bulkFilters.subfamilyId}`);
      }
    }
    if (bulkFilters.supplierId) {
      const supplier = bulkSupplierOptions.find((value) => value.id === bulkFilters.supplierId);
      items.push(`${t("stock.column.supplier")}: ${supplier?.tradeName || supplier?.legalName || bulkFilters.supplierId}`);
    }
    if (bulkFilters.taxId) {
      const tax = inventoryTaxOptions.find((value) => value.value === bulkFilters.taxId);
      items.push(`${t("stock.column.tax")}: ${tax?.label ?? bulkFilters.taxId}`);
    }
    if (bulkFilters.priceUseMode) items.push(t(`product.discount.${bulkFilters.priceUseMode === "NORMAL" ? "salePrice" : bulkFilters.priceUseMode === "MEMBER_PRICE" ? "memberPrice" : bulkFilters.priceUseMode === "OFFER_PRICE" ? "offerPrice" : "offerDiscount"}`));
    if (bulkFilters.offerActive !== null && bulkFilters.offerActive !== undefined) {
      items.push(`${t("stock.bulkEdit.filter.offerActive")}: ${t(bulkFilters.offerActive ? "common.yes" : "common.no")}`);
    }
    if (bulkFilters.offerFrom || bulkFilters.offerUntil) {
      items.push(`${t("stock.bulkEdit.filter.offerRange")}: ${bulkFilters.offerFrom ?? "-"} / ${bulkFilters.offerUntil ?? "-"}`);
    }
    if (bulkFilters.minimumPrice !== null && bulkFilters.minimumPrice !== undefined) {
      items.push(`${t("stock.bulkEdit.filter.minimumPrice")}: ${bulkFilters.minimumPrice}`);
    }
    if (bulkFilters.maximumPrice !== null && bulkFilters.maximumPrice !== undefined) {
      items.push(`${t("stock.bulkEdit.filter.maximumPrice")}: ${bulkFilters.maximumPrice}`);
    }
    return items.length > 0 ? items : [t("stock.bulkEdit.filter.none")];
  }

  function renderFilterSummaryItem(item: string) {
    const separatorIndex = item.indexOf(": ");
    if (separatorIndex === -1) {
      return <span key={item}>{item}</span>;
    }
    const label = item.slice(0, separatorIndex + 1);
    const value = item.slice(separatorIndex + 2);
    return (
      <span key={item}>
        <strong>{label}</strong> {value}
      </span>
    );
  }

  function renderBulkEditScreen() {
    const tabs: Array<{ key: StockBulkEditTab; label: string }> = [
      { key: "main", label: "stock.bulkEdit.main" },
      { key: "info", label: "stock.bulkEdit.info" },
      { key: "salePrice", label: "stock.bulkEdit.salePrice" },
      { key: "memberPrice", label: "stock.bulkEdit.memberPrice" },
      { key: "wholesalePrice", label: "stock.bulkEdit.wholesalePrice" },
      { key: "offer", label: "stock.bulkEdit.offer" },
      { key: "image", label: "stock.bulkEdit.image" }
    ];
    return (
      <div className="bulk-edit-screen">
        <input
          ref={bulkFileInputRef}
          className="bulk-file-input"
          type="file"
          accept={excelImportAccept}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              void importBulkExcel(file);
            }
            event.currentTarget.value = "";
          }}
        />
        <div className="bulk-edit-toolbar">
          <div className="bulk-edit-actions">{renderBulkLeftActions()}</div>
          <div className="bulk-edit-search-actions">
            <div className="active-filter-summary bulk-active-filter-summary" aria-label={t("stock.filters.summary")}>
              {activeBulkFilterSummary().map(renderFilterSummaryItem)}
            </div>
            <button type="button" className="stock-filter-button" aria-haspopup="dialog" onClick={() => void openBulkFilterDialog()}>
              <img alt="" className="report-action-icon" src={stockFilterIcon} />
              {t("salesReport.filter")}{activeBulkFilterCount > 0 ? ` (${activeBulkFilterCount})` : ""}
            </button>
            <label className="report-search stock-top-sales-search">
              <img alt="" src={stockSearchIcon} />
              <input
                type="search"
                value={bulkSearchText}
                aria-label={t("stock.bulkEdit.searchList")}
                placeholder={t("stock.bulkEdit.searchList")}
                onChange={(event) => setBulkSearchText(event.target.value)}
              />
            </label>
            {renderBulkEditSelectedControl()}
          </div>
        </div>
        {bulkStatus && (
          <div className={`bulk-edit-status ${bulkConflictDraftId ? "conflict" : ""}`} role="status">
            <span>{bulkStatus}</span>
            {bulkConflictDraftId && (
              <button type="button" disabled={bulkBusy} onClick={() => void reloadConflictedBulkDraft()}>
                {t("stock.bulkEdit.reload")}
              </button>
            )}
          </div>
        )}
        {bulkEditTab === "image" ? (
          <div className="stock-bulk-image-workspace">
            {renderBulkRows(renderBulkEditHeader())}
            <StockBulkImagePanel
              ref={bulkImagePanelRef}
              locale={locale}
              products={uniqueProductRows(bulkRows.flatMap((row) => row.product ? [row.product] : []))}
              snapshot={bulkImageSnapshot}
              onSnapshotChange={commitBulkImageSnapshot}
              onStatus={setBulkStatus}
              showToolbar={false}
            />
          </div>
        ) : renderBulkRows(renderBulkEditHeader())}
        {renderBulkFinder()}
        {renderBulkEditorDialog()}
        {renderBulkSupplierDialog()}
        {renderBulkPurchaseDocumentDialog()}
        {renderBulkWorkspaceDialog()}
        <StockBulkFilterDialog
          open={bulkFilterOpen}
          locale={locale}
          value={bulkFilters}
          families={inventoryFamilyTree}
          suppliers={bulkSupplierOptions.map((supplier) => ({
            value: supplier.id,
            label: supplier.tradeName || supplier.legalName
          }))}
          taxes={inventoryTaxOptions}
          onClose={() => setBulkFilterOpen(false)}
          onApply={(value) => void applyBulkFilterSelection(value)}
        />
        <StockBulkFamilyDialog
          open={bulkFamilyDialogOpen}
          locale={locale}
          families={inventoryFamilyTree}
          onClose={() => setBulkFamilyDialogOpen(false)}
          onApply={importBulkFamilies}
        />
        <StockBulkDecimalDialog
          open={bulkDecimalDialogOpen}
          locale={locale}
          fieldLabel={t(currentBulkDecimalField() === "memberPrice"
            ? "stock.column.memberPrice"
            : currentBulkDecimalField() === "wholesalePrice"
              ? "stock.column.wholesalePrice"
              : currentBulkDecimalField() === "offerPrice"
                ? "stock.column.offerPrice"
                : "stock.column.salePrice")}
          selectedCount={stockBulkProductRowIds(bulkRows).length}
          onClose={() => setBulkDecimalDialogOpen(false)}
          onApply={applyBulkDecimalEnding}
        />
        <StockBulkPriceRulesDialog
          open={bulkPriceRulesOpen}
          locale={locale}
          token={session.accessToken ?? ""}
          currentUsername={session.username}
          isAdmin={session.permissions.includes("ADMIN")}
          products={bulkProducts}
          families={inventoryFamilyTree}
          suppliers={bulkSupplierOptions.map((supplier) => ({ value: supplier.id, label: supplier.tradeName || supplier.legalName }))}
          warehouses={warehouseOptions.map((warehouse) => ({ value: warehouse.id, label: warehouse.name ?? warehouse.id }))}
          onClose={() => setBulkPriceRulesOpen(false)}
          onApplied={() => {
            setStockRefreshCounter((current) => current + 1);
            setBulkStatus(t("stock.bulkEdit.rules.applied"));
          }}
        />
      </div>
    );
  }

  function renderBulkEditHeading() {
    if (bulkWorkspaceView === "list") {
      return (
        <header className="work-panel-heading stock-panel-heading bulk-workspace-list-heading">
          <div>
            <h2>{selectedViewLabel}</h2>
            <span>{t("stock.bulkEdit.workspace.subtitle")}</span>
          </div>
        </header>
      );
    }
    const tabs: Array<{ key: StockBulkEditTab; label: string }> = [
      { key: "main", label: "stock.bulkEdit.main" },
      { key: "info", label: "stock.bulkEdit.info" },
      { key: "salePrice", label: "stock.bulkEdit.salePrice" },
      { key: "memberPrice", label: "stock.bulkEdit.memberPrice" },
      { key: "wholesalePrice", label: "stock.bulkEdit.wholesalePrice" },
      { key: "offer", label: "stock.bulkEdit.offer" },
      { key: "image", label: "stock.bulkEdit.image" }
    ];
    return (
      <header className="work-panel-heading stock-panel-heading bulk-edit-heading">
        <div className="bulk-edit-title">
          <button type="button" className="bulk-workspace-back" onClick={closeBulkWorkspace}>
            {t("stock.bulkEdit.workspace.back")}
          </button>
          <h2>{selectedViewLabel}</h2>
          <span>{activeBulkDraft ? `${activeBulkDraft.code} · V${activeBulkDraft.versionNumber} · ${activeBulkDraft.name}` : t("stock.bulkEdit.workspace.unsaved")}</span>
        </div>
        <nav className="bulk-edit-tabs" aria-label={selectedViewLabel}>
          {tabs.map((tab) => (
            <button type="button" className={bulkEditTab === tab.key ? "selected" : ""} key={tab.key} onClick={() => setBulkEditTab(tab.key)}>
              {t(tab.label)}
            </button>
          ))}
        </nav>
      </header>
    );
  }

  function renderBulkWorkspaceManager() {
    return (
      <div className="stock-bulk-workspace-manager">
        <StockBulkWorkspaceList
          locale={locale}
          session={session}
          drafts={bulkDrafts}
          selectedId={bulkSelectedDraftId}
          busy={bulkBusy}
          onSelect={setBulkSelectedDraftId}
          onNew={newBulkWorkspace}
          onOpen={(draft) => void openBulkDraft(draft)}
          onComments={openBulkComments}
          onRename={openBulkRename}
          onDelete={(draft) => {
            setBulkDeleteDraft(draft);
            setBulkDialog("delete");
          }}
        />
        {bulkStatus && <p className="bulk-edit-status" role="status">{bulkStatus}</p>}
        {renderBulkWorkspaceDialog()}
      </div>
    );
  }

  function activeInventorySummary() {
    const items: string[] = selectedView === "stock.current" ? [] : [selectedViewLabel];
    if (inventoryFilters.type) {
      items.push(`${t("stock.column.type")}: ${t(stockProductTypeLabel(inventoryFilters.type))}`);
    }
    if (inventoryFilters.discount) {
      items.push(`${t("product.field.usePrice")}: ${t(stockDiscountTypeLabel(inventoryFilters.discount))}`);
    }
    if (inventoryFilters.family) {
      const family = inventoryFamilyTree.find((candidate) => candidate.id === inventoryFilters.family);
      const subfamily = inventoryFamilyTree.flatMap((candidate) => candidate.subfamilies).find((candidate) => candidate.id === inventoryFilters.family);
      items.push(`${t("stock.column.family")}: ${family?.name ?? subfamily?.name ?? inventoryFilters.family}`);
    }
    if (inventoryFilters.tax) {
      const tax = inventoryTaxOptions.find((candidate) => candidate.value === inventoryFilters.tax);
      items.push(`${t("stock.column.tax")}: ${tax?.label ?? inventoryFilters.tax}`);
    }
    if (inventoryFilters.offerActive) {
      items.push(`${t("stock.column.offerActive")}: ${t(inventoryFilters.offerActive === "yes" ? "common.yes" : "common.no")}`);
    }
    const warehouseLabel = effectiveWarehouseId === "TOTAL"
      ? t("stock.warehouse.total")
      : inventoryWarehouseOptions.find((warehouse) => warehouse.value === effectiveWarehouseId)?.label
        ?? valueText(warehouseCatalog.find((warehouse) => warehouse.id === effectiveWarehouseId)?.name ?? effectiveWarehouseId);
    items.push(`${t("stock.column.warehouse")}: ${warehouseLabel}`);
    return items;
  }

  function renderInventoryToolbar() {
    return (
      <div className="stock-toolbar">
        <div className="stock-search-stack">
          <div className="active-filter-summary" aria-label={t("stock.filters.summary")}>
            {activeInventorySummary().map(renderFilterSummaryItem)}
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
        {selectedView !== "stock.promotions" && (
          <button type="button" className="stock-columns-button" onClick={() => setStockColumnsOpen(true)}>{t("stock.columns")}</button>
        )}
      </div>
    );
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
    if (topSalesFilters.warehouse) {
      const warehouse = warehouseCatalog.find((candidate) => candidate.id === topSalesFilters.warehouse);
      items.push(`${t("stock.column.warehouse")}: ${valueText(warehouse?.name ?? topSalesFilters.warehouse)}`);
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

      <section
        className={`work-shell ${selectedView === "stock.bulkEdit" && bulkWorkspaceView === "editor" ? "bulk-editor-work-shell" : ""}`}
        aria-label={stockTitle}
      >
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{stockTitle}</h1>
        </header>

        <aside className="stock-nav">
          {!(selectedView === "stock.bulkEdit" && bulkWorkspaceView === "editor") && <strong>{stockTitle}</strong>}
          {visibleStockViews.map((view) => (
            <button
              type="button"
              className={stockViewIsSelected(selectedView, view, warehouseDocumentMode, partyDirectory) ? "selected" : ""}
              key={view}
              onClick={() => {
                setWarehouseDocumentMode(null);
                setPartyDirectory(null);
                setSelectedView(view);
              }}
            >
              {t(view)}
            </button>
          ))}

          {visiblePartyDirectories.length > 0 && <strong className="stock-nav-section">{t("party.section")}</strong>}
          {visiblePartyDirectories.map((kind) => (
            <button type="button" className={partyDirectory === kind ? "selected" : ""} key={kind} onClick={() => {
              setWarehouseDocumentMode(null);
              setPartyDirectory(kind);
            }}>{t(`party.${kind}.title`)}</button>
          ))}

          {(canReadWarehouseInput || canReadWarehouseOutput) && (
            <>
              <strong className="stock-nav-section">{t("stock.warehouse")}</strong>
              {canReadWarehouseInput && (
                <button type="button" className={!partyDirectory && warehouseDocumentMode === "input" ? "selected" : ""} onClick={() => {
                  const nextState = stockNavigationStateForWarehouseMode("input");
                  setPartyDirectory(nextState.partyDirectory);
                  setWarehouseDocumentMode(nextState.warehouseDocumentMode);
                }}>
                  {t("stock.nav.inputWarehouse")}
                </button>
              )}
              {canReadWarehouseOutput && (
                <button type="button" className={!partyDirectory && warehouseDocumentMode === "output" ? "selected" : ""} onClick={() => {
                  const nextState = stockNavigationStateForWarehouseMode("output");
                  setPartyDirectory(nextState.partyDirectory);
                  setWarehouseDocumentMode(nextState.warehouseDocumentMode);
                }}>
                  {t("stock.nav.outputWarehouse")}
                </button>
              )}
            </>
          )}

          {canReadStock && <strong className="stock-nav-section">{t("stock.settings")}</strong>}
          {canReadStock && (
            <button type="button" onClick={() => setStockSettingsMode("configuration")}>{t("stock.settings.configuration")}</button>
          )}
          {session.permissions.includes("ADMIN") && (
            <button type="button" onClick={() => setStockSettingsMode("permissions")}>{t("stock.settings.permissions")}</button>
          )}

          <button type="button" className="report-back" onClick={onBack}>
            {t("common.back")}
          </button>
        </aside>

        <section className={`stock-list work-panel ${!warehouseDocumentMode && !partyDirectory && selectedView === "stock.bulkEdit" ? "bulk-edit-panel" : ""} ${!partyDirectory && selectedView === "stock.bulkEdit" && bulkWorkspaceView === "editor" ? "bulk-edit-workspace-panel" : ""}`} aria-label={partyDirectory ? t(`party.${partyDirectory}.title`) : warehouseDocumentMode ? t(warehouseDocumentMode === "input" ? "stock.nav.inputWarehouse" : "stock.nav.outputWarehouse") : selectedViewLabel}>
          {partyDirectory ? null : warehouseDocumentMode ? (
            <header className="work-panel-heading stock-panel-heading">
              <div>
                <h2>{t(warehouseDocumentMode === "input" ? "stock.nav.inputWarehouse" : "stock.nav.outputWarehouse")}</h2>
                <span>{t(warehouseDocumentMode === "input" ? "warehouseOperations.inputSubtitle" : "warehouseOperations.outputSubtitle")}</span>
              </div>
            </header>
          ) : selectedView === "stock.bulkEdit" ? (
            renderBulkEditHeading()
          ) : (
            <header className="work-panel-heading stock-panel-heading">
              <div>
                <h2>{selectedViewLabel}</h2>
                <span>{selectedViewSubtitle}</span>
              </div>
              {canManageProducts && (
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
              )}
            </header>
          )}
          {partyDirectory ? (
            <PartyDirectoryPanel kind={partyDirectory} locale={locale} session={session} />
          ) : warehouseDocumentMode ? (
            <WarehouseOperationsPanel
              mode={warehouseDocumentMode}
              token={session.accessToken}
              products={warehouseProducts}
              warehouses={warehouseOptions}
              customers={warehouseCustomers}
              suppliers={warehouseSuppliers}
              t={t}
              locale={locale}
              terminalContext={terminalContext}
              defaultWarehouseId={stockSettings?.defaultWarehouseId}
              permissions={warehouseDocumentMode === "input" ? {
                read: canReadWarehouseInput,
                create: canCreateWarehouseInput,
                edit: userHasStockPermission(session, "WAREHOUSE_INPUTS_WRITE"),
                delete: userHasStockPermission(session, "WAREHOUSE_INPUTS_DELETE"),
                canConfirm: userHasStockPermission(session, "WAREHOUSE_INPUTS_CONFIRM")
              } : {
                read: canReadWarehouseOutput,
                create: canCreateWarehouseOutput,
                edit: userHasStockPermission(session, "WAREHOUSE_OUTPUTS_EDIT"),
                delete: userHasStockPermission(session, "WAREHOUSE_OUTPUTS_DELETE"),
                canConfirm: userHasStockPermission(session, "WAREHOUSE_OUTPUTS_CONFIRM")
              }}
              onClose={() => {
                setWarehouseDocumentMode(null);
                setPartyDirectory(null);
              }}
              onConfirmed={() => setStockRefreshCounter((current) => current + 1)}
              onError={(error) => setStatus(error instanceof Error ? error.message : "stock.status.noData")}
            />
          ) : selectedView === "stock.topSales" ? (
            <>
              <div className="stock-top-sales-toolbar">
                <div className="stock-search-stack">
                  <div className="active-filter-summary" aria-label={t("stock.filters.summary")}>
                    {activeTopSalesSummary().map(renderFilterSummaryItem)}
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
                  <article className="stock-row" key={`${row.productId}-${row.warehouseId ?? "all"}`} style={selectedGridStyle}>
                    {visibleSelectedColumnSettings.map((column) => (
                      <span className="stock-cell" key={column.key}>
                        {renderTopSalesCell(row, index, column.key)}
                      </span>
                    ))}
                  </article>
                ))}
              </div>
            </>
          ) : selectedView === "stock.bulkEdit" ? (
            bulkWorkspaceView === "editor" ? renderBulkEditScreen() : renderBulkWorkspaceManager()
          ) : selectedView === "stock.promotions" ? (
            <>
              {renderInventoryToolbar()}
              <StockPromotionGroups
                locale={locale}
                promotions={stockPromotions}
                productRows={visibleRows}
                t={t}
                hideEmptyGroups={Boolean(searchText.trim()) || Object.values(inventoryFilters).some(Boolean)}
              />
            </>
          ) : (
            <>
              {renderInventoryToolbar()}
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
                    {visibleSelectedColumnSettings.map((column) => (
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
          <section
            className="filter-dialog stock-filter-dialog"
            onKeyDown={(event) => acceptStockDialogEnter(event, () => applyTopSalesFilters())}
          >
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
                    <div className="date-range-strip">
                      <div className={`date-range-strip-cell ${topSalesDateRangeStart ? "" : "active"}`}>
                        <span>{t("salesReport.filter.dateFrom")}</span>
                        <strong>{draftTopSalesDateFrom ? formatStockFilterDate(draftTopSalesDateFrom, locale) : "-"}</strong>
                      </div>
                      <div className={`date-range-strip-cell ${topSalesDateRangeStart ? "active" : ""}`}>
                        <span>{t("salesReport.filter.dateTo")}</span>
                        <strong>{draftTopSalesDateTo ? formatStockFilterDate(draftTopSalesDateTo, locale) : "-"}</strong>
                      </div>
                    </div>
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
                    <footer className="date-range-footer">
                      <span>{draftTopSalesDateFrom ? stockSelectedDaysText(stockDateRangeDayCount(draftTopSalesDateFrom, draftTopSalesDateTo), locale) : t("salesReport.filter.pickDateFrom")}</span>
                      <div className="date-range-actions">
                        <button type="button" onClick={() => {
                          setTopSalesDateRangeStart(null);
                          setTopSalesDatePickerOpen(false);
                        }}>
                          {t("common.cancel")}
                        </button>
                        <button type="button" className="primary" onClick={() => {
                          setTopSalesDateRangeStart(null);
                          setTopSalesDatePickerOpen(false);
                        }}>
                          {t("common.apply")}
                        </button>
                      </div>
                    </footer>
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
              <label>
                <span>{t("stock.column.warehouse")}</span>
                <ErpSelect
                  value={draftTopSalesFilters.warehouse ?? ""}
                  onChange={(value) => updateDraftTopSalesFilter("warehouse", value)}
                  options={[
                    { value: "", label: t("stock.filter.allWarehouses") },
                    ...warehouseCatalog
                      .filter((warehouse) => warehouse.active !== false)
                      .map((warehouse) => ({
                        value: warehouse.id,
                        label: valueText(warehouse.name ?? warehouse.id)
                      }))
                  ]}
                />
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
          <section
            className="filter-dialog stock-filter-dialog"
            onKeyDown={(event) => acceptStockDialogEnter(event, applyInventoryFilters)}
          >
            <header className="filter-header">
              <h2 id="stock-inventory-filter-title">{t("stock.filter.inventoryTitle")}</h2>
              <button type="button" onClick={() => setInventoryFilterOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="filter-grid">
              <div className="filter-field filter-wide">
                <span>{t("stock.filter.inventoryView")}</span>
                <div className="stock-periods" aria-label={t("stock.filter.inventoryView")}>
                  {visibleStockViews.filter((view) => view !== "stock.topSales").map((view) => (
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
                        onKeyDown={(event) => {
                          if (bulkEnterIntent(event) !== "next") return;
                          event.preventDefault();
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
                const visibleColumns = selectedColumnSettings.filter((setting) => setting.visible !== false).length;
                const canHide = column.visible === false || visibleColumns > 1;
                return (
                  <div
                    className={`stock-column-editor-row ${column.visible === false ? "hidden" : ""}`}
                    draggable
                    key={column.key}
                    onDragStart={(event: DragEvent<HTMLDivElement>) => {
                      event.dataTransfer.effectAllowed = "move";
                      event.dataTransfer.setData("text/plain", column.key);
                    }}
                    onDragOver={(event: DragEvent<HTMLDivElement>) => {
                      event.preventDefault();
                      event.dataTransfer.dropEffect = "move";
                    }}
                    onDrop={(event: DragEvent<HTMLDivElement>) => {
                      event.preventDefault();
                      const draggedKey = event.dataTransfer.getData("text/plain");
                      if (draggedKey) {
                        reorderSelectedColumn(draggedKey, column.key);
                      }
                    }}
                  >
                    <span className="stock-column-drag-handle" aria-hidden="true">::</span>
                    <label className="stock-column-visible">
                      <input
                        type="checkbox"
                        checked={column.visible !== false}
                        disabled={!canHide}
                        onChange={() => toggleSelectedColumnVisibility(column.key)}
                      />
                    </label>
                    <strong>{label}</strong>
                    <div className="attribute-actions stock-column-order-actions">
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
                        onKeyDown={(event) => {
                          if (bulkEnterIntent(event) !== "next") return;
                          event.preventDefault();
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
                              onKeyDown={(event) => {
                                if (bulkEnterIntent(event) !== "next") return;
                                event.preventDefault();
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
              {canManageProducts && (
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
              )}
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
              <StockSalesHistoryPanel
                productId={detailRow.productId}
                productName={detailRow.name}
                locale={locale}
                token={session.accessToken}
                onClose={() => setDetailRow(null)}
                onOpenDocument={onOpenDocument}
              />
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
      <StockSettingsDialog
        open={stockSettingsMode === "configuration"}
        mode="configuration"
        locale={locale}
        token={session.accessToken}
        warehouses={warehouseCatalog}
        selectedProduct={selectedStockRow ? { id: selectedStockRow.productId, name: selectedStockRow.name } : null}
        selectedWarehouseId={selectedStockRow?.warehouseId === "TOTAL" ? defaultWarehouseId : selectedStockRow?.warehouseId}
        isAdmin={session.permissions.includes("ADMIN")}
        canEdit={canManageWarehouseSettings}
        onClose={() => setStockSettingsMode(null)}
        onSaved={setStockSettings}
      />
      <StockPermissionsDialog
        open={stockSettingsMode === "permissions"}
        locale={locale}
        token={session.accessToken}
        onClose={() => setStockSettingsMode(null)}
      />
    </main>
  );
}

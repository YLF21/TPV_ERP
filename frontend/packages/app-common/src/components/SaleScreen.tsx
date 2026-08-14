/// <reference types="vite/client" />

import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type RefObject } from "react";
import { ApiError, apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import { hasPermission } from "../auth/auth";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { CashPaymentResultDialog } from "./CashPaymentResultDialog";
import { CardPaymentDialog } from "./CardPaymentDialog";
import { readCashInputMode, type CashInputMode } from "../sale/cashInputMode";
import { PromotionPreviewPanel, type PromotionPreview } from "./PromotionPreviewPanel";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { queryPaymentOperation } from "../sale/paymentOperations";
import { SalePaymentCheckout, type PaymentFinalizationSummary, type SalePaymentCheckoutHandle } from "./SalePaymentCheckout";
import {
  TouchSaleActionPanel,
  type SaleCommandLabels
} from "./SaleCommandPresentation";
import {
  SaleCommandMenuBar,
  type SaleCommandMenu,
} from "./SaleCommandMenuBar";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import {
  outputConfirmedTicket,
  retryConfirmedTicketPrint,
  type ConfirmedTicketPrintSnapshot,
  type SalePrintMode,
  type TicketPrintOutcome,
} from "../sale/ticketPrinting";
import {
  outputIssuedVoucher,
  type IssuedVoucherPrintSnapshot,
} from "../sale/voucherPrinting";
import {
  saleCommandFromKeyboard,
  type SaleCommandId,
} from "../sale/saleCommands";
import type { TicketPrintUiStatus } from "./CashPaymentResultDialog";
import { CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";
import { GiftReceiptDialog } from "./GiftReceiptDialog";
import {
  addLocalDays,
  resolvePendingCardPaymentMode,
  type PendingCardPaymentMode,
  type PendingSaleDraft,
  type PendingTerminalPaymentConfiguration,
} from "../sale/customerReceivables";
import {
  clearPendingSaleRecovery,
  loadPendingSaleRecovery,
  pendingSaleRecoveryRequiresAttention,
  savePendingSaleRecovery,
  type PendingSaleRecoveryEnvelope,
  type PendingSaleRecoveryLoadResult,
} from "../sale/pendingSaleRecovery";
import {
  clearCashCloseRecovery,
  loadCashCloseRecovery,
  saveCashCloseRecovery,
  type CashCloseRecoveryLoadResult,
} from "../sale/cashCloseRecovery";
import { retryPrintSucceeded } from "../sale/printRetry";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { ParkedSalesDialog, type OpenedParkedSale } from "./ParkedSalesDialog";
import { SaleTicketCancellationDialog } from "./SaleTicketCancellationDialog";
import { SaleTicketInvoiceDialog } from "./SaleTicketInvoiceDialog";
import { VerifactuPosIndicator } from "./VerifactuPosIndicator";
import {
  SaleProductSearchDialog,
  filterSaleProductSearch,
} from "./SaleProductSearchDialog";
import { SaleProductInformationDialog } from "./SaleProductInformationDialog";
import { SaleOpenPriceDialog } from "./SaleOpenPriceDialog";
import { SaleCalculatorDialog } from "./SaleCalculatorDialog";
import { SaleProductConsultationDialog } from "./SaleProductConsultationDialog";
import { SalePriceConsultationDialog } from "./SalePriceConsultationDialog";
import { SaleProductSalesHistoryDialog } from "./SaleProductSalesHistoryDialog";
import { SaleCustomerCreateDialog, canCreateSaleCustomer } from "./SaleCustomerCreateDialog";
import { SaleCustomerReceivablesDialog } from "./SaleCustomerReceivablesDialog";
import { SaleCashDrawerAuthorizationDialog } from "./SaleCashDrawerAuthorizationDialog";
import { ProductCreateDialog, type ProductCreateEditProduct } from "./ProductCreateDialog";
import {
  createCashCloseUiFlow,
  SaleCashSessionDialog,
  type CashCloseUiFlow,
} from "./SaleCashSessionDialog";
import { SaleCashWithdrawalDialog } from "./SaleCashWithdrawalDialog";
import { SaleSerialNumberDialog } from "./SaleSerialNumberDialog";
import { TouchNumericKeypad } from "./TouchNumericKeypad";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { TableSortButton } from "./TableSortButton";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition, TableLayout } from "./tableLayoutPreferences";
import {
  TicketReturnDialog,
  type ReturnCartLine,
  type ReturnCartReservation,
} from "./TicketReturnDialog";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import {
  CashDrawerResultReportingError,
  executeAuthorizedCashDrawerOpen,
} from "../sale/cashDrawer";
import {
  authorizeProductEdit,
  productEditDialogValue,
  revokeProductEditAuthorization,
} from "../sale/productEdit";
import {
  createCashCloseWithdrawalIdempotencyKey,
  prepareCashSessionForSales,
  recoverCashCloseOperation,
} from "../sale/cashSessions";
import {
  findSaleOperationAuthorization,
  loadSalesOperationSecurity,
  type SaleOperationCredentials,
  type SalesOperationSecurityConfiguration,
} from "../sale/operationSecurity";
import {
  detectSaleMutationOperations,
  saleMutationCredentialsRequired,
  saleMutationAuthorizationRequirements,
  saleWithOperationAuthorizations,
  type DetectedSaleMutationOperation,
  type SaleMutationAuthorizationRequirement,
  type SaleMutationOperationAuthorizations,
} from "../sale/saleMutationAuthorizations";
import { SaleMutationAuthorizationDialog } from "./SaleMutationAuthorizationDialog";
import { userCanManageStockProducts } from "./stockAccess";
import {
  formatProductQuantity,
  isProductQuantityPrecisionValid,
  normalizeProductQuantity,
  parseProductQuantityInput,
  productQuantityStep,
} from "../sale/productQuantity";

export type SaleProduct = {
  id: string;
  imageId?: string | null;
  active?: boolean | null;
  productType?: "UNIT" | "WEIGHT" | "SERVICE" | string | null;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  salePrice?: number | string | null;
  memberPrice?: number | string | null;
  offerPrice?: number | string | null;
  offerDiscountPercent?: number | string | null;
  priceUseMode?: "NORMAL" | "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT" | string | null;
  discountType?: "NONE" | "NORMAL" | "MEMBER_PRICE" | "MEMBER_DISCOUNT" | "DISCOUNT_PRICE" | string | null;
  offerActive?: boolean | null;
  offerFrom?: string | null;
  offerUntil?: string | null;
  totalStock?: number | string | null;
  taxId: string;
  taxesIncluded: boolean;
  taxRegime: "IVA" | "IGIC";
  taxPercentage: number | string;
  rate?: string | null;
  packageQuantity?: number | string | null;
};

export type SaleLine = {
  /**
   * Client-side identity of the cart row. It must not be confused with the
   * product id: an open-price product may occur more than once in one sale.
   */
  cartLineId?: string;
  product: SaleProduct;
  quantity: number;
  openUnitPrice?: number;
  temporaryPriceAuthorization?: {
    token: string;
    expiresAt: string;
    unitPrice: number;
    productId: string;
    cartLineId: string;
    policyVersion: number;
  };
  returnUnitPrice?: number;
  temporaryName?: string;
  serialNumbers?: string[];
  // Operator-entered discount. Member benefit is kept separately.
  discountPercent: number;
  memberDiscountPercent?: number;
  returnOrigin?: {
    sourceType: "TICKET" | "GIFT_RECEIPT" | "SALES_INVOICE";
    sourceCode: string;
    sourceTicketId: string;
    sourceTicketNumber: string;
    sourceLineId: string;
    giftReceiptLineId?: string | null;
  };
  previousTicketImportOrigin?: {
    sourceTicketId: string;
    sourceTicketNumber: string;
    sourceLineId: string;
    sourceStatus: "CONFIRMADO" | "ANULADO";
    pricingMode: PreviousTicketImportPricingMode;
    lineType: string;
    historicalUnitPrice: number;
    historicalTotal: number;
    historicalDiscountPercent: number;
    requiresNewSerialNumbers: boolean;
    manualPricePreserved: boolean;
    temporaryPriceAuthorizationRequired: boolean;
  };
};

export type SaleCustomer = {
  id: string;
  clientId?: string | null;
  fiscalName?: string | null;
  documentNumber?: string | null;
  active?: boolean;
  activeMember?: boolean;
  memberCategoryName?: string | null;
  memberDiscountPercent?: number | string | null;
  memberBalance?: number | string | null;
  creditEnabled?: boolean;
  creditLimit?: number | string | null;
  paymentTermDays?: number | null;
  creditBlocked?: boolean;
  blockOnOverdue?: boolean;
  outstandingDebt?: number | string | null;
  overdueDebt?: number | string | null;
  availableCredit?: number | string | null;
};

export type PreviousTicketImportPreview = {
  ticketId: string;
  ticketNumber: string;
  ticketDate: string;
  status: "CONFIRMADO" | "ANULADO";
  pricingMode: PreviousTicketImportPricingMode;
  preservedManualDiscountAmount: number | string;
  manualDiscountAuthorizationRequired: boolean;
  fingerprint: string;
  customerId?: string | null;
  globalDiscount: number | string;
  baseTotal: number | string;
  taxTotal: number | string;
  total: number | string;
  currency: string;
  lines: Array<{
    sourceLineId: string;
    productId: string;
    code: string;
    name: string;
    quantity: number | string;
    productType?: "UNIT" | "WEIGHT" | "SERVICE" | null;
    unitPrice: number | string;
    discount: number | string;
    rate?: string | null;
    taxesIncluded: boolean;
    taxRegime: "IVA" | "IGIC";
    taxPercent: number | string;
    base: number | string;
    tax: number | string;
    total: number | string;
    serialNumbers: string[];
    requiresNewSerialNumbers?: boolean;
    manualPricePreserved?: boolean;
    temporaryPriceAuthorizationRequired: boolean;
  }>;
  adjustments: Array<{
    lineType: string;
    name: string;
    base: number | string;
    tax: number | string;
    total: number | string;
  }>;
};

export type PreviousTicketImportPricingMode = "FROZEN_EXACT" | "CURRENT_REPRICING";

export type PreviousTicketImportIssue = {
  type: "EMPTY" | "INVALID_RESPONSE" | "INVALID_QUANTITY";
  productLabel?: string;
};

export type PreparedPreviousTicketImportLine = {
  line: SaleLine;
};

type PendingPreviousTicketImport = {
  preview: PreviousTicketImportPreview;
  lines: SaleLine[];
  customer: SaleCustomer | null;
  baseLines: SaleLine[];
};

type PreviousTicketImportBatch = {
  ticketId: string;
  ticketNumber: string;
  fingerprint: string;
  sourceStatus: "CONFIRMADO" | "ANULADO";
  pricingMode: PreviousTicketImportPricingMode;
  preservedManualDiscountAmount: number;
  manualDiscountAuthorizationRequired: boolean;
  total: number;
  adjustments: PreviousTicketImportPreview["adjustments"];
  reconciliationAdjustment: number;
};

type SaleCustomerSortColumn = "code" | "name" | "document" | "member" | "discount" | "debt" | "overdue";

const noCustomerSelectionId = "__NO_CUSTOMER__";
const previousTicketImportTimeoutMs = 12_000;

type PosAuthoritativeQuote = {
  total: number | string;
  productTotal: number | string;
  promotionPreview: PromotionPreview;
  pricingVersion?: number;
  quoteFingerprint?: string;
  lineBreakdown?: AuthoritativeSaleLine[];
};

type AuthoritativeSaleLine = {
  lineId: string;
  position: number;
  lineType?: string;
  productId?: string | null;
  code: string;
  name: string;
  quantity: number | string;
  normalUnitPrice: number | string;
  memberUnitPrice?: number | string | null;
  baseUnitPrice: number | string;
  priceSource?: string | null;
  memberPriceSaving: number | string;
  memberDiscountPercent: number | string;
  memberDiscount: number | string;
  manualDiscountPercent: number | string;
  manualDiscount: number | string;
  promotionDiscount: number | string;
  couponDiscount: number | string;
  taxIncluded: boolean;
  taxRegime: string;
  taxPercent: number | string;
  taxBase: number | string;
  tax: number | string;
  baseSubtotal: number | string;
  roundingAdjustment: number | string;
  finalSubtotal: number | string;
};

export type SaleCartColumnKey =
  | "image"
  | "code"
  | "barcode"
  | "name"
  | "quantity"
  | "package"
  | "salePrice"
  | "discount"
  | "specialPrice"
  | "total";

export const saleCartTableKey = "sale.cart.v2";
export const saleCartImageColumnWidth = 58;
export const saleCartColumnDefinitions = [
  { key: "image", defaultWidth: saleCartImageColumnWidth },
  { key: "code", defaultWidth: 104, minWidth: 56 },
  { key: "barcode", defaultWidth: 132, minWidth: 72 },
  { key: "name", defaultWidth: 240, minWidth: 96 },
  { key: "quantity", defaultWidth: 72, minWidth: 40 },
  { key: "package", defaultWidth: 74, minWidth: 44 },
  { key: "salePrice", defaultWidth: 96, minWidth: 52 },
  { key: "discount", defaultWidth: 82, minWidth: 40 },
  { key: "specialPrice", defaultWidth: 120, minWidth: 64 },
  { key: "total", defaultWidth: 100, minWidth: 52 },
] as const satisfies readonly TableColumnDefinition<SaleCartColumnKey>[];

export function visibleSaleCartColumns(
  layout: TableLayout<SaleCartColumnKey>,
): TableLayout<SaleCartColumnKey> {
  return visibleTableColumns(layout).map((column) => (
    column.key === "image"
      ? { ...column, width: saleCartImageColumnWidth }
      : column
  ));
}

type SaleCartSpecialPrice = {
  type: "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT";
  unitPrice: number;
  discountPercent?: number;
};

function finiteAmount(value: number | string | null | undefined, fallback = 0) {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount : fallback;
}

export function saleCartSpecialPrice(
  product: SaleProduct,
  activeMember: boolean,
  authoritativeLine?: AuthoritativeSaleLine,
): SaleCartSpecialPrice | null {
  const mode = String(product.priceUseMode ?? "NORMAL").toUpperCase();
  if (authoritativeLine) {
    const source = String(authoritativeLine.priceSource ?? "").toUpperCase();
    if (source === "MEMBER" || source === "MEMBER_PRICE") {
      return {
        type: "MEMBER_PRICE",
        unitPrice: finiteAmount(authoritativeLine.baseUnitPrice),
      };
    }
    if (
      source === "OFERTA"
      || source === "OFFER"
      || source === "OFFER_PRICE"
      || source === "OFFER_DISCOUNT"
    ) {
      const offerType = source === "OFFER_DISCOUNT" || mode === "OFFER_DISCOUNT"
        ? "OFFER_DISCOUNT"
        : "OFFER_PRICE";
      return {
        type: offerType,
        unitPrice: finiteAmount(authoritativeLine.baseUnitPrice),
        ...(offerType === "OFFER_DISCOUNT"
          ? { discountPercent: finiteAmount(product.offerDiscountPercent) }
          : {}),
      };
    }
    return null;
  }

  if (
    activeMember
    && String(product.discountType ?? "").toUpperCase() === "MEMBER_PRICE"
    && finiteAmount(product.memberPrice) > 0
  ) {
    return {
      type: "MEMBER_PRICE",
      unitPrice: finiteAmount(product.memberPrice),
    };
  }
  if ((mode === "OFFER_PRICE" || mode === "OFFER_DISCOUNT") && saleOfferIsCurrent(product)) {
    return {
      type: mode,
      unitPrice: effectiveSaleProductPrice(product, activeMember),
      ...(mode === "OFFER_DISCOUNT"
        ? { discountPercent: finiteAmount(product.offerDiscountPercent) }
        : {}),
    };
  }
  return null;
}

type SaleCartProductThumbnailProps = {
  product: SaleProduct;
  token?: string;
};

function SaleCartProductThumbnail({
  product,
  token,
}: SaleCartProductThumbnailProps) {
  const [source, setSource] = useState("");

  useEffect(() => {
    if (!product.imageId || !token) {
      setSource("");
      return;
    }
    let active = true;
    let objectUrl = "";
    setSource("");
    void fetch(`${apiBaseUrl}/products/${encodeURIComponent(product.id)}/image?thumbnail=true`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((response) => {
      if (!response.ok) throw new Error("product_image_unavailable");
      return response.blob();
    }).then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setSource(objectUrl);
    }).catch(() => {
      if (active) setSource("");
    });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [product.id, product.imageId, token]);

  return source ? <img className="sale-cart-thumbnail" src={source} alt="" /> : null;
}

export function isCompleteAuthoritativeQuote(
  quote: PosAuthoritativeQuote | null | undefined,
): quote is PosAuthoritativeQuote & { pricingVersion: 1; lineBreakdown: AuthoritativeSaleLine[] } {
  if (quote?.pricingVersion !== 1 || !Array.isArray(quote.lineBreakdown)) return false;
  const total = Number(quote.total);
  if (!Number.isFinite(total) || quote.lineBreakdown.length === 0) return false;
  const lineTotal = quote.lineBreakdown.reduce((sum, line) => {
    const subtotal = Number(line.finalSubtotal);
    return Number.isFinite(subtotal) ? sum + subtotal : Number.NaN;
  }, 0);
  return Number.isFinite(lineTotal) && Math.abs(lineTotal - total) < 0.005;
}

type SaleTranslator = (key: string) => string;

export function saleMainMessage(
  t: SaleTranslator,
  key: string,
  values: Record<string, string | number> = {},
) {
  return Object.entries(values).reduce(
    (message, [name, value]) => message.replaceAll(`{${name}}`, String(value)),
    t(key),
  );
}

export function saleMainProductCount(t: SaleTranslator, count: number) {
  return saleMainMessage(t, count === 1 ? "sale.main.productCount.one" : "sale.main.productCount.many", { count });
}

function normalizedSearchValue(value: string | null | undefined) {
  return value?.trim().toLocaleLowerCase() ?? "";
}

export function filterSaleProducts(products: SaleProduct[], query: string, limit = 10) {
  return filterSaleProductSearch(products, query, limit);
}

export function saleSelectableProducts(products: SaleProduct[], allowInactiveProductSales: boolean) {
  return allowInactiveProductSales ? products : products.filter((product) => product.active !== false);
}

export function selectSaleProduct(products: SaleProduct[], query: string) {
  const normalizedQuery = normalizedSearchValue(query);
  if (!normalizedQuery) {
    return undefined;
  }
  return products.find((product) => [product.code, product.barcode, product.barcode2]
    .some((value) => normalizedSearchValue(value) === normalizedQuery));
}

export function isSalesDocumentShortcut(event: Pick<KeyboardEvent, "key" | "ctrlKey" | "altKey" | "metaKey">) {
  return event.ctrlKey
    && !event.altKey
    && !event.metaKey
    && event.key.toLocaleLowerCase() === "f";
}

export function saleQuickOperand(value: string) {
  if (!/^\d+$/.test(value)) return null;
  const operand = Number(value);
  return Number.isSafeInteger(operand) ? operand : null;
}

export function salePauseQuantity(value: string) {
  if (value === "-1") return -1;
  const quantity = parseProductQuantityInput(value);
  return Number.isFinite(quantity) ? quantity : null;
}

export function salePauseQuantityAllowed(line: SaleLine, quantity: number) {
  return !line.returnOrigin || quantity === 0;
}

export function saleKeyboardReturnRemovalAllowed(
  line: SaleLine | undefined,
  quickValue: string,
  paymentLocked: boolean,
) {
  return !paymentLocked
    && Boolean(line?.returnOrigin)
    && salePauseQuantity(quickValue) === 0;
}

export function saleReturnCartReservations(lines: readonly SaleLine[]): ReturnCartReservation[] {
  return lines.flatMap((line) => line.returnOrigin ? [{
    sourceTicketId: line.returnOrigin.sourceTicketId,
    sourceCode: line.returnOrigin.sourceCode,
    lineId: line.returnOrigin.sourceLineId,
    returnQuantity: Math.abs(line.quantity),
    selectedSerialNumbers: line.serialNumbers ?? [],
  }] : []);
}

export function saleReturnSourceConflict(
  lines: readonly SaleLine[],
  additions: readonly ReturnCartLine[],
) {
  const currentSources = new Set(saleReturnCartReservations(lines).map((line) => line.sourceTicketId));
  const addedSources = new Set(additions.map((line) => line.sourceTicketId));
  return currentSources.size > 1 || addedSources.size > 1
    || (currentSources.size === 1 && addedSources.size === 1
      && currentSources.values().next().value !== addedSources.values().next().value);
}

export function mergeSaleReturnLines(lines: readonly SaleLine[], additions: readonly SaleLine[]) {
  const merged = [...lines];
  for (const addition of additions) {
    const origin = addition.returnOrigin;
    const index = origin ? merged.findIndex((line) => line.returnOrigin
      && line.returnOrigin.sourceTicketId === origin.sourceTicketId
      && line.returnOrigin.sourceLineId === origin.sourceLineId
      && (line.returnOrigin.giftReceiptLineId ?? null) === (origin.giftReceiptLineId ?? null)) : -1;
    if (index < 0) {
      merged.push(addition);
      continue;
    }
    const current = merged[index];
    merged[index] = {
      ...current,
      quantity: current.quantity + addition.quantity,
      serialNumbers: [...new Set([...(current.serialNumbers ?? []), ...(addition.serialNumbers ?? [])])],
    };
  }
  return merged;
}

export function addSaleLine(
  lines: SaleLine[],
  product: SaleProduct,
  openUnitPrice?: number,
  quantity = 1,
  cartLineId: string = createSaleCartLineId(),
) {
  if (!isProductQuantityPrecisionValid(quantity, product.productType)
      || quantity < productQuantityStep(product.productType) || quantity > 9999) {
    throw new Error("invalid_quantity");
  }
  // An explicitly entered price defines a new economic line. Never merge it
  // with an earlier occurrence of the same catalog product.
  const existing = openUnitPrice == null
    ? lines.find((line) => !line.returnOrigin
      && !line.previousTicketImportOrigin
      && line.product.id === product.id
      && line.openUnitPrice == null)
    : undefined;
  if (!existing) {
    return [...lines, {
      cartLineId,
      product,
      quantity,
      discountPercent: 0,
      ...(openUnitPrice != null ? { openUnitPrice } : {}),
    }];
  }
  return lines.map((line) => saleCartLineIdentity(line) === saleCartLineIdentity(existing)
    ? { ...line, quantity: Math.min(9999, normalizeProductQuantity(line.quantity + quantity)) }
    : line);
}

export function preparePreviousTicketImport(
  preview: PreviousTicketImportPreview,
): { lines: PreparedPreviousTicketImportLine[]; issues: PreviousTicketImportIssue[] } {
  if (!Array.isArray(preview.lines) || preview.lines.length === 0) {
    return { lines: [], issues: [{ type: "EMPTY" }] };
  }
  if (preview.pricingMode !== "FROZEN_EXACT"
      && preview.pricingMode !== "CURRENT_REPRICING") {
    return { lines: [], issues: [{ type: "INVALID_RESPONSE" }] };
  }
  if (typeof preview.manualDiscountAuthorizationRequired !== "boolean"
      || !Number.isFinite(Number(preview.preservedManualDiscountAmount))) {
    return { lines: [], issues: [{ type: "INVALID_RESPONSE" }] };
  }
  const issues: PreviousTicketImportIssue[] = [];
  const prepared: PreparedPreviousTicketImportLine[] = [];

  for (const sourceLine of preview.lines) {
    const label = sourceLine.code?.trim() || sourceLine.name?.trim() || sourceLine.sourceLineId;
    const quantity = Number(sourceLine.quantity);
    const unitPrice = Number(sourceLine.unitPrice);
    const discountPercent = Number(sourceLine.discount);
    const historicalTotal = Number(sourceLine.total);
    const taxPercent = Number(sourceLine.taxPercent);
    if (!sourceLine.sourceLineId
        || !Number.isFinite(quantity)
        || quantity <= 0
        || Math.abs(quantity) > 9999
        || !Number.isFinite(unitPrice)
        || !Number.isFinite(discountPercent)
        || !Number.isFinite(historicalTotal)
        || !Number.isFinite(taxPercent)
        || typeof sourceLine.temporaryPriceAuthorizationRequired !== "boolean") {
      issues.push({ type: "INVALID_QUANTITY", productLabel: label });
      continue;
    }
    const product: SaleProduct = {
      id: sourceLine.productId,
      active: true,
      productType: sourceLine.productType
        ?? (Number.isInteger(quantity) ? "UNIT" : "SERVICE"),
      code: sourceLine.code,
      name: sourceLine.name,
      salePrice: unitPrice,
      taxId: `previous-ticket:${sourceLine.sourceLineId}`,
      taxesIncluded: sourceLine.taxesIncluded,
      taxRegime: sourceLine.taxRegime,
      taxPercentage: taxPercent,
      rate: sourceLine.rate ?? null,
    };
    prepared.push({
      line: {
        cartLineId: `previous-ticket:${sourceLine.sourceLineId}`,
        product,
        quantity,
        discountPercent,
        serialNumbers: preview.pricingMode === "FROZEN_EXACT"
          ? [...(sourceLine.serialNumbers ?? [])]
          : [],
        previousTicketImportOrigin: {
          sourceTicketId: preview.ticketId,
          sourceTicketNumber: preview.ticketNumber,
          sourceLineId: sourceLine.sourceLineId,
          sourceStatus: preview.status,
          pricingMode: preview.pricingMode,
          lineType: "PRODUCT",
          historicalUnitPrice: unitPrice,
          historicalTotal,
          historicalDiscountPercent: discountPercent,
          manualPricePreserved: Boolean(sourceLine.manualPricePreserved),
          temporaryPriceAuthorizationRequired:
            sourceLine.temporaryPriceAuthorizationRequired,
          requiresNewSerialNumbers: preview.pricingMode === "CURRENT_REPRICING"
            && (Boolean(sourceLine.requiresNewSerialNumbers)
              || (sourceLine.serialNumbers?.length ?? 0) > 0),
        },
      },
    });
  }

  return { lines: issues.length === 0 ? prepared : [], issues };
}

export function previousTicketImportReconciliationAdjustment(
  preview: PreviousTicketImportPreview,
) {
  if (preview.pricingMode !== "FROZEN_EXACT") return 0;
  const visibleTotal = preview.lines.reduce(
    (sum, line) => sum + finiteAmount(line.total),
    0,
  ) + (preview.adjustments ?? []).reduce(
    (sum, adjustment) => sum + finiteAmount(adjustment.total),
    0,
  );
  return Math.round((finiteAmount(preview.total) - visibleTotal) * 100) / 100;
}

export function appendPreviousTicketImport(
  currentLines: SaleLine[],
  importedLines: PreparedPreviousTicketImportLine[],
) {
  if (currentLines.some((line) => line.returnOrigin || line.previousTicketImportOrigin)) {
    throw new Error("previous_ticket_import_conflict");
  }
  const imported = importedLines.map(({ line }) => line);
  const nextLines = [...imported, ...currentLines];
  return {
    lines: nextLines,
    selectedLineId: imported[0] ? saleCartLineIdentity(imported[0]) : null,
  };
}

export function createSaleCartLineId(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `sale-line-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function saleCartLineIdentity(line: SaleLine) {
  // Product id keeps compatibility with old in-memory/test lines. Production
  // additions and recovered sales always receive cartLineId.
  return line.cartLineId ?? line.product.id;
}

export function updateSaleLineQuantity(lines: SaleLine[], lineId: string, quantity: number) {
  const selectedLine = lines.find((line) => saleCartLineIdentity(line) === lineId);
  if (!selectedLine
      || selectedLine.previousTicketImportOrigin
      || !isProductQuantityPrecisionValid(quantity, selectedLine.product.productType)
      || quantity === 0 || quantity < -1 || quantity > 9999) {
    throw new Error("invalid_quantity");
  }
  return lines.map((line) => saleCartLineIdentity(line) === lineId ? { ...line, quantity } : line);
}

export function updateSaleLineDiscount(lines: SaleLine[], lineId: string, discountPercent: number) {
  const hasMoreThanTwoDecimals = Math.abs(discountPercent * 100 - Math.round(discountPercent * 100)) > 1e-9;
  if (!Number.isFinite(discountPercent) || discountPercent < 0 || discountPercent > 100 || hasMoreThanTwoDecimals) {
    throw new Error("invalid_discount");
  }
  const line = lines.find((candidate) => saleCartLineIdentity(candidate) === lineId);
  if (line?.previousTicketImportOrigin) throw new Error("historical_import_locked");
  if (discountPercent > 0 && line && saleProductBlocksManualDiscount(line.product)) {
    throw new Error("discount_blocked");
  }
  return lines.map((line) => saleCartLineIdentity(line) === lineId ? { ...line, discountPercent } : line);
}

export function removeSaleLine(lines: SaleLine[], lineId: string) {
  const selectedLine = lines.find((line) => saleCartLineIdentity(line) === lineId);
  if (selectedLine?.previousTicketImportOrigin) {
    throw new Error("historical_import_locked");
  }
  return lines.filter((line) => saleCartLineIdentity(line) !== lineId);
}

export function selectedProductAfterRemoval(lines: SaleLine[], lineId: string) {
  const removedIndex = lines.findIndex((line) => saleCartLineIdentity(line) === lineId);
  const remaining = removeSaleLine(lines, lineId);
  if (remaining.length === 0) return null;
  const nextIndex = Math.min(Math.max(removedIndex, 0), remaining.length - 1);
  return saleCartLineIdentity(remaining[nextIndex]);
}

export function saleLineSelectionAfterArrow(
  lines: SaleLine[],
  selectedId: string | null,
  key: "ArrowUp" | "ArrowDown",
) {
  if (lines.length === 0) return null;
  const selectedIndex = lines.findIndex((line) => saleCartLineIdentity(line) === selectedId);
  if (selectedIndex < 0) {
    return key === "ArrowDown"
      ? saleCartLineIdentity(lines[0])
      : saleCartLineIdentity(lines[lines.length - 1]);
  }
  const offset = key === "ArrowDown" ? 1 : -1;
  const nextIndex = Math.min(Math.max(selectedIndex + offset, 0), lines.length - 1);
  return saleCartLineIdentity(lines[nextIndex]);
}

export function saleSearchSelectionAfterArrow(
  products: SaleProduct[],
  selectedId: string,
  key: "ArrowUp" | "ArrowDown",
) {
  if (products.length === 0) return "";
  const selectedIndex = products.findIndex((product) => product.id === selectedId);
  if (selectedIndex < 0) return key === "ArrowDown" ? products[0].id : products[products.length - 1].id;
  const offset = key === "ArrowDown" ? 1 : -1;
  return products[Math.min(Math.max(selectedIndex + offset, 0), products.length - 1)].id;
}

function saleShortcutTargetIsEditable(target: EventTarget | null) {
  return target instanceof HTMLElement && (
    target.matches("input, textarea, select")
    || target.isContentEditable
    || target.contentEditable === "true"
    || target.closest('[contenteditable]:not([contenteditable="false"])') !== null
  );
}

export function saleLineSubtotal(line: SaleLine, activeMember = false) {
  if (line.previousTicketImportOrigin) {
    return line.previousTicketImportOrigin.historicalTotal;
  }
  return saleLineUnitPrice(line, activeMember) * line.quantity * (1 - effectiveSaleLineDiscount(line) / 100);
}

export function updateSaleLineSerialNumbers(
  lines: SaleLine[],
  lineId: string,
  serialNumbers: string[],
) {
  return lines.map((line) => {
    if (saleCartLineIdentity(line) !== lineId) return line;
    if (line.previousTicketImportOrigin
        && !line.previousTicketImportOrigin.requiresNewSerialNumbers) {
      throw new Error("historical_import_locked");
    }
    return { ...line, serialNumbers: [...serialNumbers] };
  });
}

export function updateSaleLineTemporaryName(
  lines: SaleLine[],
  lineId: string,
  value: string,
) {
  const normalized = value.trim();
  if (normalized.length > 255) throw new Error("invalid_temporary_name");
  return lines.map((line) => {
    if (saleCartLineIdentity(line) !== lineId) return line;
    if (line.previousTicketImportOrigin) throw new Error("historical_import_locked");
    const catalogName = line.product.name?.trim() ?? "";
    const { temporaryName: _previous, ...withoutTemporaryName } = line;
    return normalized && normalized !== catalogName
      ? { ...withoutTemporaryName, temporaryName: normalized }
      : withoutTemporaryName;
  });
}

export function updateSaleLineTemporaryPrice(
  lines: SaleLine[],
  lineId: string,
  value?: number,
  authorization?: SaleLine["temporaryPriceAuthorization"],
) {
  if (value != null) {
    const hasMoreThanTwoDecimals = Math.abs(value * 100 - Math.round(value * 100)) > 1e-9;
    if (!Number.isFinite(value) || value <= 0 || hasMoreThanTwoDecimals) {
      throw new Error("invalid_temporary_price");
    }
  }
  return lines.map((line) => {
    if (saleCartLineIdentity(line) !== lineId) return line;
    if (line.previousTicketImportOrigin) throw new Error("historical_import_locked");
    const {
      openUnitPrice: _previous,
      temporaryPriceAuthorization: _previousAuthorization,
      ...withoutTemporaryPrice
    } = line;
    return value == null
      ? withoutTemporaryPrice
      : {
          ...withoutTemporaryPrice,
          openUnitPrice: value,
          ...(authorization ? { temporaryPriceAuthorization: authorization } : {}),
        };
  });
}

export function saleLineHasValidTemporaryPriceAuthorization(
  line: SaleLine,
  now = Date.now(),
  policyVersion?: number,
) {
  const authorization = line.temporaryPriceAuthorization;
  return Boolean(
    authorization
    && line.openUnitPrice != null
    && authorization.productId === line.product.id
    && authorization.cartLineId === saleCartLineIdentity(line)
    && authorization.unitPrice === line.openUnitPrice
    && (policyVersion == null || authorization.policyVersion === policyVersion)
    && Number.isFinite(Date.parse(authorization.expiresAt))
    && Date.parse(authorization.expiresAt) > now,
  );
}

export function saleLineUnitPrice(line: SaleLine, activeMember = false) {
  return line.previousTicketImportOrigin?.historicalUnitPrice
    ?? line.returnUnitPrice
    ?? line.openUnitPrice
    ?? effectiveSaleProductPrice(line.product, activeMember);
}

export function saleCartDisplayedUnitPrice(
  line: SaleLine,
  activeMember = false,
  authoritativeLine?: AuthoritativeSaleLine,
) {
  const appliedUnitPrice = finiteAmount(
    authoritativeLine?.baseUnitPrice ?? saleLineUnitPrice(line, activeMember),
  );
  if (line.returnOrigin || line.openUnitPrice != null) return appliedUnitPrice;
  const catalogSalePrice = authoritativeLine
    ? finiteAmount(authoritativeLine.normalUnitPrice)
    : finiteAmount(line.product.salePrice);
  return catalogSalePrice;
}

export function saleProductRequiresOpenPrice(product: SaleProduct) {
  if (product.salePrice == null || (typeof product.salePrice === "string" && !product.salePrice.trim())) {
    return false;
  }
  const price = Number(product.salePrice);
  return Number.isFinite(price) && price === 0;
}

export function saleDisplayedTotal(localTotal:number, paymentLocked:boolean, lineCount:number, reservedTotalCents:number|null){
  return paymentLocked && lineCount===0 && reservedTotalCents!=null ? reservedTotalCents/100 : localTotal;
}

export function effectiveSaleLineDiscount(line: SaleLine) {
  return line.previousTicketImportOrigin
    ? line.previousTicketImportOrigin.historicalDiscountPercent
    : line.returnOrigin
    ? line.discountPercent
    : Math.max(line.discountPercent, line.memberDiscountPercent ?? 0);
}

export function previousTicketImportSerialNumbersReady(lines: SaleLine[]) {
  const normalizedSerialNumbers: string[] = [];
  for (const line of lines) {
    if (!line.previousTicketImportOrigin?.requiresNewSerialNumbers) continue;
    const quantity = Number(line.quantity);
    const serialNumbers = (line.serialNumbers ?? []).map((value) => value.trim().toUpperCase());
    if (!Number.isInteger(quantity)
        || quantity <= 0
        || serialNumbers.length !== quantity
        || serialNumbers.some((value) => !value)) return false;
    normalizedSerialNumbers.push(...serialNumbers);
  }
  return new Set(normalizedSerialNumbers).size === normalizedSerialNumbers.length;
}

export function applyMemberDiscounts(lines: SaleLine[], customer: SaleCustomer | null) {
  const customerDiscount = customer?.activeMember ? Number(customer.memberDiscountPercent ?? 0) : 0;
  return lines.map((line) => line.previousTicketImportOrigin ? line : ({
    ...line,
    memberDiscountPercent: customerDiscount,
  }));
}

export function saleTotal(lines: SaleLine[], activeMember = false) {
  return lines.reduce((total, line) => total + saleLineSubtotal(line, activeMember), 0);
}

type CashPaymentResponse = {
  number: string;
  change: number | string;
  total?: number | string;
  received?: number | string;
  printTicket: ConfirmedTicketPrintSnapshot;
};

export type CashPaymentResult = {
  ticketNumber: string;
  totalCents: number;
  receivedCents?: number;
  changeCents?: number;
  method?: string;
  authorization?: string;
  reference?: string;
  printTicket?: ConfirmedTicketPrintSnapshot;
  printStatus?: TicketPrintUiStatus;
  printTechnicalMessage?: string;
  issuedVoucher?: IssuedVoucherPrintSnapshot;
  voucherPrintStatus?: TicketPrintUiStatus;
  voucherPrintTechnicalMessage?: string;
};

type CardPaymentResponse = { status: string; ticketId?: string | null; ticketNumber?: string | null; total?: number | string; reference?: string | null; authorization?: string | null; message?: string | null };
type CardPaymentOutcome = { clearSale: boolean; retryable: boolean; uncertain: boolean; status: string; message: string; result?: { ticketNumber: string; totalCents: number; method: string; authorization?: string; reference?: string } };

export function resolveCardPaymentOutcome(response: CardPaymentResponse, quotedTotalCents: number): CardPaymentOutcome {
  const status = response.status;
  const approved = status === "APPROVED";
  const finalFailure = status === "DECLINED" || status === "ERROR" || status === "CANCELLED";
  return {
    clearSale: approved,
    retryable: finalFailure,
    uncertain: !approved && !finalFailure,
    status,
    message: response.message ?? (approved ? "Pago aprobado" : "El pago no se ha completado"),
    result: approved ? { ticketNumber: response.ticketNumber ?? "-", totalCents: serverAmountCents(response.total, quotedTotalCents), method: "Tarjeta", authorization: response.authorization ?? undefined, reference: response.reference ?? undefined } : undefined
  };
}

export function cardRetryCheckoutId(status: string, generate: () => string) {
  return status === "DECLINED" || status === "ERROR" || status === "CANCELLED" ? generate() : null;
}

export function cardTransportFailureOutcome(checkoutId: string, message: string) {
  return { status: "UNCERTAIN", checkoutId, message, uncertain: true, clearSale: false, retryable: false };
}

export function buildCardChargeBody(checkoutId: string, sale: object, quotedCents: number) {
  return { checkoutId, sale, quotedTotal: (quotedCents / 100).toFixed(2) };
}

export async function runGuardedCardOpening(
  guard: { current: boolean; generation: number },
  opening: (context: { token: number; isCurrent: () => boolean }) => Promise<unknown>
) {
  if (guard.current) return false;
  guard.current = true;
  const token = ++guard.generation;
  try {
    await opening({ token, isCurrent: () => guard.generation === token });
    return true;
  } finally {
    guard.current = false;
  }
}

function serverAmountCents(value: number | string | undefined, fallback: number) {
  const amount = Number(value);
  return value == null || !Number.isFinite(amount) ? fallback : Math.round(amount * 100);
}

export function resolveCashPaymentResult(
  response: Pick<CashPaymentResponse, "number" | "change" | "total" | "received">,
  quotedTotalCents: number,
  sentReceivedCents: number
): CashPaymentResult {
  return {
    ticketNumber: response.number,
    totalCents: serverAmountCents(response.total, quotedTotalCents),
    receivedCents: sentReceivedCents,
    changeCents: serverAmountCents(response.change, sentReceivedCents - quotedTotalCents)
  };
}

export function readCashModeForOpening(storage?: Storage) {
  return readCashInputMode(storage);
}

export function cashPaymentSuccessTransition(result: CashPaymentResult) {
  return {
    cashDialogOpen: false,
    cashResult: result,
    lines: [] as SaleLine[],
    selectedLineId: null,
    selectedCustomer: null,
    query: ""
  };
}

export function cashPaymentErrorTransition<T extends object>(snapshot: T, cashError: string) {
  return { ...snapshot, cashError };
}

export function finishCashPaymentResult(
  clearResult: (result: null) => void,
  focusSearch: () => void
) {
  clearResult(null);
  focusSearch();
}

export function cashResultFromFinalization(
  ticketNumber: string,
  totalCents: number,
  receivedCents: number,
): CashPaymentResult {
  return {
    ticketNumber,
    totalCents,
    receivedCents,
    changeCents: Math.max(0, receivedCents - totalCents),
  };
}

export function paymentResultFromFinalization(
  printTicket: ConfirmedTicketPrintSnapshot,
  summary: PaymentFinalizationSummary,
): CashPaymentResult {
  if (summary.kind === "CASH") {
    return {
      ...cashResultFromFinalization(printTicket.documentNumber, summary.totalCents, summary.receivedCents),
      printTicket,
      ...(summary.issuedVoucher ? {
        issuedVoucher: summary.issuedVoucher,
        voucherPrintStatus: "PRINTING" as const,
      } : {}),
    };
  }
  return {
    ticketNumber: printTicket.documentNumber,
    totalCents: summary.totalCents,
    method: summary.kind === "CARD" ? "Tarjeta"
      : summary.kind === "VOUCHER" ? "Vale"
        : summary.kind === "REFUND" ? "Devolución"
          : summary.kind === "ZERO" ? "Sin liquidación"
            : "Mixto",
    printTicket,
    ...(summary.issuedVoucher ? {
      issuedVoucher: summary.issuedVoucher,
      voucherPrintStatus: "PRINTING" as const,
    } : {}),
  };
}

export function cashPaymentResultForAutomaticPrinting(
  response: CashPaymentResponse,
  quotedTotalCents: number,
  receivedCents: number,
): CashPaymentResult {
  return {
    ...resolveCashPaymentResult(response, quotedTotalCents, receivedCents),
    printTicket: response.printTicket,
    printStatus: "PRINTING",
  };
}

export function updateCashResultPrintOutcome(
  current: CashPaymentResult | null,
  documentId: string,
  outcome: TicketPrintOutcome,
) {
  return current?.printTicket?.documentId === documentId
    ? {
        ...current,
        printStatus: outcome.status,
        printTechnicalMessage: outcome.technicalMessage,
      }
    : current;
}

export function updateCashResultVoucherPrintOutcome(
  current: CashPaymentResult | null,
  voucherCode: string,
  outcome: TicketPrintOutcome,
) {
  return current?.issuedVoucher?.code === voucherCode
    ? {
        ...current,
        voucherPrintStatus: outcome.status,
        voucherPrintTechnicalMessage: outcome.technicalMessage,
      }
    : current;
}

export async function runGuardedCashSubmission(
  guard: { current: boolean },
  submission: () => Promise<unknown>
) {
  if (guard.current) return false;
  guard.current = true;
  try {
    await submission();
    return true;
  } finally {
    guard.current = false;
  }
}

export async function runGuardedCashOpening(
  guard: { current: boolean; generation: number },
  opening: (context: { isCurrent: () => boolean }) => Promise<unknown>
) {
  if (guard.current) return false;
  guard.current = true;
  const generation = ++guard.generation;
  try {
    await opening({ isCurrent: () => guard.generation === generation });
    return true;
  } finally {
    if (guard.generation === generation) guard.current = false;
  }
}

export function saleProductBlocksManualDiscount(product: SaleProduct) {
  return String(product.discountType ?? "NORMAL").toUpperCase() === "NONE";
}

export function effectiveSaleProductPrice(product: SaleProduct, activeMember = false, currentDate = currentSaleDate()) {
  const salePrice = salePriceNumber(product.salePrice);
  if (
    activeMember
    && String(product.discountType ?? "").toUpperCase() === "MEMBER_PRICE"
    && salePriceNumber(product.memberPrice) > 0
  ) {
    return salePriceNumber(product.memberPrice);
  }
  const mode = String(product.priceUseMode ?? "NORMAL").toUpperCase();
  if ((mode === "OFFER_PRICE" || mode === "OFFER_DISCOUNT") && saleOfferIsCurrent(product, currentDate)) {
    const explicitOfferPrice = salePriceNumber(product.offerPrice, Number.NaN);
    if (Number.isFinite(explicitOfferPrice)) return explicitOfferPrice;
    if (mode === "OFFER_DISCOUNT") {
      const discount = salePriceNumber(product.offerDiscountPercent);
      if (discount >= 0 && discount <= 100) return salePrice * (1 - discount / 100);
    }
  }
  return salePrice;
}

export function saleOfferIsCurrent(product: SaleProduct, currentDate = currentSaleDate()) {
  if (product.offerActive === false) return false;
  const from = product.offerFrom?.trim();
  const until = product.offerUntil?.trim();
  return (!from || from <= currentDate) && (!until || until >= currentDate);
}

function currentSaleDate(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function newSaleControlOperationId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.map((value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export type SaleControlResetReason = "PRODUCT_ADDED" | "SALE_FINALIZED" | "CART_EMPTIED" | "SALE_PARKED";

export class SaleDeletionControlSequence {
  private saleOperationId: string;
  private recordingQueue: Promise<unknown> = Promise.resolve();

  constructor(private readonly generateId: () => string = newSaleControlOperationId) {
    this.saleOperationId = generateId();
  }

  currentSaleOperationId() {
    return this.saleOperationId;
  }

  newDeletionOperationId() {
    return this.generateId();
  }

  reset(_reason: SaleControlResetReason) {
    this.saleOperationId = this.generateId();
  }

  enqueue(record: () => Promise<unknown>, onError: (error: unknown) => void) {
    this.recordingQueue = this.recordingQueue.then(record).catch(onError);
    return this.recordingQueue;
  }
}

function salePriceNumber(value: unknown, fallback = 0) {
  if (value === null || value === undefined || String(value).trim() === "") return fallback;
  const parsed = Number(String(value).replace(",", "."));
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function filterSaleCustomers(customers: SaleCustomer[], query: string, limit = 20) {
  const normalizedQuery = normalizedSearchValue(query);
  if (!normalizedQuery) {
    return customers.slice(0, limit);
  }
  return customers
    .filter((customer) => [customer.clientId, customer.fiscalName, customer.documentNumber]
      .some((value) => normalizedSearchValue(value).includes(normalizedQuery)))
    .slice(0, limit);
}

export function saleProductFiscalSnapshot(product: SaleProduct) {
  if (typeof product.taxesIncluded !== "boolean") {
    throw new Error("Producto sin configuración de impuestos válida");
  }
  const rawPercentage: unknown = product.taxPercentage;
  if (
    rawPercentage === null
    || rawPercentage === undefined
    || (typeof rawPercentage !== "number" && typeof rawPercentage !== "string")
    || (typeof rawPercentage === "string" && rawPercentage.trim() === "")
  ) {
    throw new Error("Producto sin porcentaje fiscal válido");
  }
  const percentage = Number(rawPercentage);
  if (!Number.isFinite(percentage) || percentage < 0 || percentage > 100) {
    throw new Error("Producto sin porcentaje fiscal válido");
  }
  if (product.taxRegime !== "IVA" && product.taxRegime !== "IGIC") {
    throw new Error("Producto sin régimen fiscal válido");
  }
  return {
    taxesIncluded: product.taxesIncluded,
    taxPercentage: percentage.toFixed(2),
    taxRegime: product.taxRegime,
  };
}

export function pendingSaleDraftForCustomer(
  lines: SaleLine[],
  customer: SaleCustomer,
  warehouseId: string,
  now: Date,
  checkoutId: string,
  internalComment = "",
  printMode: SalePrintMode = "DEFAULT",
): PendingSaleDraft {
  return {
    checkoutId, warehouseId, type: "ALBARAN_VENTA", date: addLocalDays(now, 0),
    customerId: customer.id, dueDate: addLocalDays(now, Math.max(0, customer.paymentTermDays ?? 30)), globalDiscount: "0.00",
    ...(internalComment.trim() ? { internalComment: internalComment.trim() } : {}),
    printMode,
    lines: lines.map((line) => ({
      productId: line.product.id, quantity: line.quantity,
      code: line.product.code ?? line.product.barcode ?? line.product.id,
      name: line.temporaryName ?? line.product.name ?? line.product.code ?? "Producto",
      rate: line.product.rate ?? null,
      price: saleLineUnitPrice(line, customer.activeMember === true).toFixed(2),
      // Membership is backend-authoritative from customerId. Only the operator's manual discount crosses the boundary.
      discount: line.discountPercent.toFixed(2), ...saleProductFiscalSnapshot(line.product),
      serialNumbers: line.serialNumbers ?? [],
      temporaryNameOverride: Boolean(line.temporaryName),
      temporaryPriceOverride: line.openUnitPrice !== undefined
        && salePriceNumber(line.product.salePrice) !== 0,
      ...(line.temporaryPriceAuthorization ? {
        cartLineId: saleCartLineIdentity(line),
        temporaryPriceAuthorizationToken: line.temporaryPriceAuthorization.token,
      } : {}),
    })),
  };
}

type SaleScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  interfaceMode?: SaleInterfaceMode;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  onOpenCustomerReceivables?: (customerId?: string) => void;
  onOpenSalesDocumentWindow?: () => void;
};

export function SaleScreen({
  app,
  locale,
  session,
  terminalContext,
  interfaceMode = "KEYBOARD",
  onBack,
  onLocaleChange,
  onLogout,
  onOpenCustomerReceivables,
  onOpenSalesDocumentWindow,
}: SaleScreenProps) {
  const t = createTranslator(locale);
  const commandLabels: SaleCommandLabels = {
    shortcuts: t("sale.main.shortcuts"),
    priceLookup: t("sale.shortcut.priceLookup"),
    calculator: t("sale.shortcut.calculator"),
    eanGenerator: t("sale.shortcut.eanGenerator"),
    printProductLabel: t("sale.shortcut.printProductLabel"),
    cashDrawer: t("sale.shortcut.cashDrawer"),
    cashWithdrawal: t("sale.shortcut.cashWithdrawal"),
    logout: t("sale.shortcut.logout"),
    selectedStock: t("sale.shortcut.selectedStock"),
    productSales: t("sale.shortcut.productSales"),
    editProduct: t("sale.shortcut.editProduct"),
    ticketReturn: t("sale.shortcut.ticketReturn"),
    cancelTicket: t("sale.shortcut.cancelTicket"),
    cancelOtherTicket: t("sale.shortcut.cancelOtherTicket"),
    convertInvoice: t("sale.shortcut.convertInvoice"),
    checkout: t("sale.shortcut.checkout"),
    setQuantity: t("sale.shortcut.setQuantity"),
    selectItem: t("sale.shortcut.selectItem"),
    temporaryName: t("sale.shortcut.temporaryName"),
    desiredPrice: t("sale.shortcut.desiredPrice"),
    temporaryPrice: t("sale.shortcut.temporaryPrice"),
    printMethod: t("sale.shortcut.printMethod"),
    saleComment: t("sale.shortcut.saleComment"),
    serialNumber: t("sale.shortcut.serialNumber"),
    parkSale: t("sale.shortcut.parkSale"),
    lineDiscount: t("sale.shortcut.lineDiscount"),
    saleDiscount: t("sale.shortcut.saleDiscount"),
    nextPackage: t("sale.shortcut.nextPackage"),
    nextUnits: t("sale.shortcut.nextUnits"),
    addQuantity: t("sale.shortcut.addQuantity"),
    subtractQuantity: t("sale.shortcut.subtractQuantity"),
    document: t("salesDocument.shortcut"),
    search: t("sale.main.search"),
    quantity: t("sale.main.quantity"),
    discount: t("sale.main.discount"),
    customer: t("sale.main.customer"),
    removeLine: t("sale.main.removeLine"),
    deleteKey: t("sale.main.deleteKey"),
    parkedSales: t("sale.main.parkedSales"),
    parkedSalesHint: t("sale.main.parkedSalesHint"),
    manageTickets: t("sale.main.manageTickets"),
    manageTicketsHint: t("sale.main.manageTicketsHint"),
    receivables: t("receivables.title"),
    cash: t("sale.main.cash"),
    card: t("sale.main.card"),
    pending: t("sale.main.pending"),
    pageDownKey: t("sale.main.pageDownKey"),
    operations: t("sale.main.management"),
    productActions: t("sale.touch.productActions"),
    saleActions: t("sale.touch.saleActions"),
    cashActions: t("sale.touch.cashActions"),
    ticketActions: t("sale.touch.ticketActions")
  };
  const [products, setProducts] = useState<SaleProduct[]>([]);
  const [allowInactiveProductSales, setAllowInactiveProductSales] = useState(false);
  const [pendingInactiveProduct, setPendingInactiveProduct] = useState<SaleProduct | null>(null);
  const [pendingOpenPriceProduct, setPendingOpenPriceProduct] = useState<SaleProduct | null>(null);
  const [pendingOpenPriceQuantity, setPendingOpenPriceQuantity] = useState(1);
  const [previousTicketImportBatch, setPreviousTicketImportBatch] =
    useState<PreviousTicketImportBatch | null>(null);
  const [previousTicketImportBusy, setPreviousTicketImportBusy] = useState(false);
  const [previousTicketImportFocusRequest, setPreviousTicketImportFocusRequest] = useState(0);
  const [nextScanQuantity, setNextScanQuantity] = useState(1);
  const [nextScanMode, setNextScanMode] = useState<"UNIT" | "PACKAGE">("UNIT");
  const [shortcutStatus, setShortcutStatus] = useState("");
  const [cashDrawerAuthorizationOpen, setCashDrawerAuthorizationOpen] = useState(false);
  const [cashDrawerBusy, setCashDrawerBusy] = useState(false);
  const [cashDrawerError, setCashDrawerError] = useState("");
  const [productEditAuthorizationOpen, setProductEditAuthorizationOpen] = useState(false);
  const [productEditBusy, setProductEditBusy] = useState(false);
  const [productEditError, setProductEditError] = useState("");
  const [productEditAuthorizationId, setProductEditAuthorizationId] = useState("");
  const [editingProduct, setEditingProduct] = useState<ProductCreateEditProduct | null>(null);
  const [query, setQuery] = useState("");
  const [lines, setLines] = useState<SaleLine[]>([]);
  const [selectedLineId, setSelectedLineId] = useState<string | null>(null);
  const [actionDialog, setActionDialog] = useState<
    "quantity"
    | "discount"
    | "temporaryName"
    | "temporaryPrice"
    | "customer"
    | "remove"
    | "comment"
    | "clearSale"
    | "clearLines"
    | "printMethod"
    | null
  >(null);
  const [quantityInput, setQuantityInput] = useState("1");
  const [discountInput, setDiscountInput] = useState("0");
  const [temporaryNameInput, setTemporaryNameInput] = useState("");
  const [temporaryPriceInput, setTemporaryPriceInput] = useState("");
  const [pendingTemporaryPriceChange, setPendingTemporaryPriceChange] = useState<{
    lineId: string;
    productId: string;
    unitPrice: number;
  } | null>(null);
  const [temporaryPriceAuthorizationBusy, setTemporaryPriceAuthorizationBusy] = useState(false);
  const [temporaryPriceAuthorizationError, setTemporaryPriceAuthorizationError] = useState("");
  const [actionError, setActionError] = useState("");
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [customerQuery, setCustomerQuery] = useState("");
  const [customerLoading, setCustomerLoading] = useState(false);
  const [customerError, setCustomerError] = useState(false);
  const [customerCreateOpen, setCustomerCreateOpen] = useState(false);
  const [customerEditId, setCustomerEditId] = useState<string | null>(null);
  const [customerReceivablesOpen, setCustomerReceivablesOpen] = useState<SaleCustomer | null>(null);
  const [selectedCustomer, setSelectedCustomer] = useState<SaleCustomer | null>(null);
  const [selectedCustomerResultId, setSelectedCustomerResultId] = useState("");
  const [customerSort, setCustomerSort] = useState<TableSort<SaleCustomerSortColumn> | null>(null);
  const [saleComment, setSaleComment] = useState("");
  const [commentInput, setCommentInput] = useState("");
  const [salePrintMode, setSalePrintMode] = useState<SalePrintMode>("DEFAULT");
  const [printModeInput, setPrintModeInput] = useState<SalePrintMode>("DEFAULT");
  const [lastPrintMode, setLastPrintMode] = useState<SalePrintMode>("DEFAULT");
  const [pendingCustomerContinuation, setPendingCustomerContinuation] = useState(false);
  const [pendingRecovery, setPendingRecovery] = useState<PendingSaleRecoveryLoadResult>(() => {
    try {
      const loaded = loadPendingSaleRecovery(localStorage, terminalContext.terminalCode);
      if (loaded.status === "valid" && !pendingSaleRecoveryRequiresAttention(loaded.envelope)) {
        clearPendingSaleRecovery(localStorage, terminalContext.terminalCode);
        return { status: "empty" };
      }
      return loaded;
    }
    catch { return { status: "empty" }; }
  });
  const recoveredPendingSale = pendingRecovery.status === "valid" ? pendingRecovery.envelope : undefined;
  const pendingRecoveryBlocked = pendingRecovery.status === "blocked";
  const [pendingDraft, setPendingDraft] = useState<PendingSaleDraft | null>(recoveredPendingSale?.draft ?? null);
  const [pendingOpening, setPendingOpening] = useState(false);
  const [pendingError, setPendingError] = useState("");
  const [pendingPrintRetry, setPendingPrintRetry] = useState<(() => Promise<unknown>) | null>(null);
  const retryPendingPrint = async () => {
    if (!pendingPrintRetry) return;
    if (await retryPrintSucceeded(pendingPrintRetry)) setPendingPrintRetry(null);
  };
  const [cashCloseRecovery, setCashCloseRecovery] =
    useState<CashCloseRecoveryLoadResult>(() => {
      try { return loadCashCloseRecovery(localStorage, terminalContext.terminalCode); }
      catch { return { status: "empty" }; }
    });
  const recoveredCashCloseFlow = cashCloseRecovery.status === "valid"
    ? cashCloseRecovery.envelope.flow
    : null;
  const [cashDialogOpen, setCashDialogOpen] = useState(false);
  const [cashOpening, setCashOpening] = useState(false);
  const [cashQuoteCents, setCashQuoteCents] = useState(0);
  const [cashCheckoutId, setCashCheckoutId] = useState("");
  const [cashSubmitting, setCashSubmitting] = useState(false);
  const [cashError, setCashError] = useState("");
  const [cashStatus, setCashStatus] = useState("");
  const [cashInputMode, setCashInputMode] = useState<CashInputMode>("touch");
  const [cashResult, setCashResult] = useState<CashPaymentResult | null>(null);
  const [cardDialogOpen, setCardDialogOpen] = useState(false);
  const [cardQuoteCents, setCardQuoteCents] = useState(0);
  const [cardCheckoutId, setCardCheckoutId] = useState("");
  const [cardStatus, setCardStatus] = useState("PENDING");
  const [cardMessage, setCardMessage] = useState("");
  const [cardSubmitting, setCardSubmitting] = useState(false);
  const [cardOpening, setCardOpening] = useState(false);
  const [paymentLocked, setPaymentLocked] = useState(false);
  const [paymentHydrated, setPaymentHydrated] = useState(false);
  const [cashSessionState, setCashSessionState] =
    useState<"LOADING" | "OPEN" | "REQUIRED" | "ERROR">("LOADING");
  const [cashSessionError, setCashSessionError] = useState("");
  const [cashSessionCloseOpen, setCashSessionCloseOpen] =
    useState(Boolean(recoveredCashCloseFlow));
  const [cashSessionCloseFlow, setCashSessionCloseFlow] =
    useState<CashCloseUiFlow | null>(recoveredCashCloseFlow);
  const [cashWithdrawalOpen, setCashWithdrawalOpen] = useState(false);
  const [cashWithdrawalPolicy, setCashWithdrawalPolicy] = useState<{
    requireEntryBreakdown: boolean;
    entryDenominations: number[];
    requireBreakdown: boolean;
    denominations: number[];
  }>({
    requireEntryBreakdown: false,
    entryDenominations: [],
    requireBreakdown: false,
    denominations: [],
  });
  const [operationSecurity, setOperationSecurity] =
    useState<SalesOperationSecurityConfiguration | null>(null);
  const [operationSecurityReload, setOperationSecurityReload] = useState(0);
  const [pendingCardPaymentMode, setPendingCardPaymentMode] =
    useState<PendingCardPaymentMode | null>(null);
  const [reservedPaymentTotalCents, setReservedPaymentTotalCents] = useState<number | null>(null);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogReload, setCatalogReload] = useState(0);
  const cartTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: catalogLoading ? undefined : session.accessToken,
    tableKey: saleCartTableKey,
    definitions: saleCartColumnDefinitions,
  });
  const [authoritativeQuote, setAuthoritativeQuote] = useState<PosAuthoritativeQuote | null>(null);
  const [authoritativeQuoteRequestKey, setAuthoritativeQuoteRequestKey] = useState("");
  const [authoritativeQuoteLoading, setAuthoritativeQuoteLoading] = useState(false);
  const [authoritativeQuoteError, setAuthoritativeQuoteError] = useState("");
  const [checkoutDiscountCents, setCheckoutDiscountCents] = useState(0);
  const [parkedSalesOpen, setParkedSalesOpen] = useState(false);
  const [parkedSaleSaving, setParkedSaleSaving] = useState(false);
  const [parkedSaleAuthorizationOpen, setParkedSaleAuthorizationOpen] = useState(false);
  const [parkedSaleError, setParkedSaleError] = useState("");
  const [ticketCancellationMode, setTicketCancellationMode] =
    useState<"LAST" | "BY_NUMBER" | null>(null);
  const [ticketInvoiceOpen, setTicketInvoiceOpen] = useState(false);
  const [ticketReturnOpen, setTicketReturnOpen] = useState(false);
  const [giftReceiptOpen, setGiftReceiptOpen] = useState(false);
  const [serialNumberOpen, setSerialNumberOpen] = useState(false);
  const [productSearchOpen, setProductSearchOpen] = useState(false);
  const [productSearchQuery, setProductSearchQuery] = useState("");
  const [productSearchSelectedId, setProductSearchSelectedId] = useState("");
  const [productInformationProduct, setProductInformationProduct] = useState<SaleProduct | null>(null);
  const [consultationMode, setConsultationMode] = useState<"PRICE" | "STOCK" | null>(null);
  const [calculatorOpen, setCalculatorOpen] = useState(false);
  const [salesUtilityOpening, setSalesUtilityOpening] = useState(false);
  const [salesHistoryProduct, setSalesHistoryProduct] = useState<SaleProduct | null>(null);
  const [salesHistoryOpen, setSalesHistoryOpen] = useState(false);
  const [verifactuRefreshSignal, setVerifactuRefreshSignal] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const customerSearchInputRef = useRef<HTMLInputElement>(null);
  const quantityInputRef = useRef<HTMLInputElement>(null);
  const discountInputRef = useRef<HTMLInputElement>(null);
  const temporaryNameInputRef = useRef<HTMLInputElement>(null);
  const temporaryPriceInputRef = useRef<HTMLInputElement>(null);
  const clearSaleCancelButtonRef = useRef<HTMLButtonElement>(null);
  const clearSaleConfirmButtonRef = useRef<HTMLButtonElement>(null);
  const clearLinesCancelButtonRef = useRef<HTMLButtonElement>(null);
  const clearLinesConfirmButtonRef = useRef<HTMLButtonElement>(null);
  const removeConfirmButtonRef = useRef<HTMLButtonElement>(null);
  const cashSubmissionRef = useRef(false);
  const cashOpeningRef = useRef({ current: false, generation: 0 });
  const cardSubmissionRef = useRef(false);
  const cardOpeningRef = useRef({ current: false, generation: 0 });
  const paymentCheckoutRef = useRef<SalePaymentCheckoutHandle>(null);
  const saleShortcutHandlerRef = useRef<(event: KeyboardEvent) => void>(() => undefined);
  const blockedRecoveryDialogRef = useRef<HTMLElement>(null);
  const deletionControlRef = useRef<SaleDeletionControlSequence | null>(null);
  if (!deletionControlRef.current) deletionControlRef.current = new SaleDeletionControlSequence();
  const deletionControl = deletionControlRef.current;
  const logoutInProgressRef = useRef(false);
  const shutdownInProgressRef = useRef(false);
  const quoteGenerationRef = useRef(0);
  const previousTicketImportBusyRef = useRef(false);
  const customerSearchGenerationRef = useRef(0);
  const linesRef = useRef(lines);
  const selectedCustomerRef = useRef(selectedCustomer);
  linesRef.current = lines;
  selectedCustomerRef.current = selectedCustomer;
  const selectableProducts = useMemo(
    () => saleSelectableProducts(products, allowInactiveProductSales),
    [allowInactiveProductSales, products]
  );
  const customerResults = useMemo(() => sortTableRows(
    customers,
    customerSort,
    (customer, column) => {
      if (column === "code") return customer.clientId;
      if (column === "name") return customer.fiscalName;
      if (column === "document") return customer.documentNumber;
      if (column === "member") return customer.memberCategoryName ?? customer.activeMember ?? false;
      if (column === "discount") return customer.memberDiscountPercent == null ? null : Number(customer.memberDiscountPercent);
      if (column === "debt") return Number(customer.outstandingDebt ?? 0);
      return Number(customer.overdueDebt ?? 0);
    },
    locale
  ), [customerQuery, customerSort, customers, locale]);
  const customerSelectionIds = useMemo(
    () => [
      ...(!pendingCustomerContinuation ? [noCustomerSelectionId] : []),
      ...customerResults.map((customer) => customer.id),
    ],
    [customerResults, pendingCustomerContinuation],
  );
  const saleCustomerCreationAllowed = canCreateSaleCustomer(session.permissions);
  const saleCustomerEditAllowed = hasPermission(session, "CUSTOMERS_WRITE")
    || hasPermission(session, "GESTION_CLIENTE_PROVEEDOR");
  const saleCustomerReceivablesAllowed = hasPermission(session, "CUSTOMER_RECEIVABLES_READ")
    && hasPermission(session, "CUSTOMER_RECEIVABLES_PAY");
  const selectedLine = lines.find((line) => saleCartLineIdentity(line) === selectedLineId);
  useEffect(() => {
    if (previousTicketImportFocusRequest === 0) return;
    searchInputRef.current?.focus();
  }, [previousTicketImportFocusRequest]);
  useEffect(() => {
    if (previousTicketImportBatch
        && !lines.some((line) => line.previousTicketImportOrigin)) {
      setPreviousTicketImportBatch(null);
    }
  }, [lines, previousTicketImportBatch]);
  useEffect(() => {
    if (previousTicketImportBatch
        && checkoutDiscountCents > 0
        && !lines.some((line) => !line.previousTicketImportOrigin)) {
      setCheckoutDiscountCents(0);
    }
  }, [checkoutDiscountCents, lines, previousTicketImportBatch]);
  useEffect(() => {
    if (!selectedLineId) return;
    const escaped = globalThis.CSS?.escape
      ? globalThis.CSS.escape(selectedLineId)
      : selectedLineId.replace(/["\\]/g, "\\$&");
    document.querySelector<HTMLElement>(`[data-cart-line-id="${escaped}"]`)
      ?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [selectedLineId]);
  const activeMember = selectedCustomer?.activeMember === true;
  const visibleCartColumns = visibleSaleCartColumns(cartTableLayout.layout);
  const cartTableWidth = visibleCartColumns.reduce((totalWidth, column) => totalWidth + column.width, 0);
  const currentSaleRequest = cashSaleRequest();
  const currentSaleRequestKey = JSON.stringify(currentSaleRequest);
  const currentEconomicLines = lines.filter((line) => !line.previousTicketImportOrigin);
  const previousTicketImportSerialsReady = previousTicketImportSerialNumbersReady(lines);
  // A CURRENT_REPRICING preview is not a price commitment. Its historical
  // total must never be presented as the current sale while the authoritative
  // quote is pending or unavailable.
  const total = previousTicketImportBatch?.pricingMode === "CURRENT_REPRICING"
    ? 0
    : (previousTicketImportBatch?.total ?? 0)
      + saleTotal(currentEconomicLines, activeMember);
  const authoritativeQuoteReady = authoritativeQuoteRequestKey === currentSaleRequestKey
    && isCompleteAuthoritativeQuote(authoritativeQuote);
  const authoritativeTotal = authoritativeQuoteReady ? Number(authoritativeQuote.total) : total;
  const authoritativeLineBreakdown = authoritativeQuoteReady ? authoritativeQuote.lineBreakdown : null;
  const currentPromotionPreview = authoritativeQuoteReady
    ? authoritativeQuote?.promotionPreview ?? null
    : null;
  const visiblePromotionPreview: PromotionPreview | null = previousTicketImportBatch?.pricingMode === "FROZEN_EXACT"
    ? {
        appliedPromotions: [
          ...previousTicketImportBatch.adjustments.map((adjustment, index) => ({
            id: `previous-ticket-adjustment-${index}`,
            name: saleMainMessage(t, "sale.importPreviousTicket.adjustment", {
              name: adjustment.name,
            }),
            discountAmount: Number(adjustment.total),
          })),
          ...(previousTicketImportBatch.reconciliationAdjustment !== 0 ? [{
            id: "previous-ticket-global-adjustment",
            name: t("sale.importPreviousTicket.globalDiscount"),
            discountAmount: previousTicketImportBatch.reconciliationAdjustment,
          }] : []),
          ...(currentPromotionPreview?.appliedPromotions ?? []),
        ],
        usedCoupon: currentPromotionPreview?.usedCoupon ?? null,
        generatedCoupon: currentPromotionPreview?.generatedCoupon ?? null,
      }
    : currentPromotionPreview;
  const currentRepricingQuotePending = previousTicketImportBatch?.pricingMode === "CURRENT_REPRICING"
    && !authoritativeQuoteReady;
  const currentRepricingQuoteStatus = currentRepricingQuotePending
    ? authoritativeQuoteError
      ? {
          label: t("sale.quote.repricing.unavailableLabel"),
          detail: t("sale.quote.repricing.unavailable"),
          kind: "ERROR" as const,
        }
      : {
          label: t("sale.quote.repricing.loadingLabel"),
          detail: t("sale.quote.repricing.loading"),
          kind: "LOADING" as const,
        }
    : null;
  const displayedTotal = saleDisplayedTotal(authoritativeTotal,paymentLocked,lines.length,reservedPaymentTotalCents);
  const previousTicketImportQuoteFingerprintReady = !previousTicketImportBatch
    || Boolean(authoritativeQuoteReady && authoritativeQuote?.quoteFingerprint?.trim());
  const basePaymentActionsDisabled = lines.length === 0 || cashOpening
    || !previousTicketImportSerialsReady
    || !previousTicketImportQuoteFingerprintReady
    || authoritativeQuoteLoading || !authoritativeQuoteReady || Boolean(authoritativeQuoteError);
  const canApplyManualDiscount = Boolean(operationSecurity?.operations.some(
    (operation) => operation.code === "APPLY_SALE_DISCOUNT",
  ));
  const canOpenCustomerReceivables = Boolean(onOpenCustomerReceivables)
    && hasPermission(session, "CUSTOMER_RECEIVABLES_READ");
  const userDiscountLimit = session.permissions.includes("ADMIN") ? 100 : Number(session.maxDiscountPercent ?? 0);
  const cashSessionReady = cashSessionState === "OPEN";
  const cashDrawerAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "OPEN_CASH_DRAWER",
    session.permissions,
  );
  const productEditAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "EDIT_CATALOG_PRODUCT",
    session.permissions,
  );
  const internalEanAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "GENERATE_PRODUCT_EAN",
    session.permissions,
  );
  const cashSessionCloseAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CLOSE_CASH_SESSION",
    session.permissions,
  );
  const cashMovementAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CASH_MOVEMENT",
    session.permissions,
  );
  const ticketCancellationAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CANCEL_TICKET",
    session.permissions,
  );
  const ticketInvoiceAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CONVERT_TICKET_TO_INVOICE",
    session.permissions,
  );
  const paymentTerminalVoidAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "PAYMENT_TERMINAL_VOID",
    session.permissions,
  );
  const paymentTerminalRefundAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "PAYMENT_TERMINAL_REFUND",
    session.permissions,
  );
  const refundPolicyOverrideAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "REFUND_POLICY_OVERRIDE",
    session.permissions,
  );
  const refundTenderOverrideAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "REFUND_TENDER_OVERRIDE",
    session.permissions,
  );
  const paymentCompensationAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "PAYMENT_COMPENSATION_ACK",
    session.permissions,
  );
  const createPendingAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CREATE_PENDING_RECEIVABLE",
    session.permissions,
  );
  const creditOverrideAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CREDIT_OVERRIDE",
    session.permissions,
  );
  const manualCardPaymentAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CONFIRM_MANUAL_CARD_PAYMENT",
    session.permissions,
  );
  const transferPaymentAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CONFIRM_TRANSFER_PAYMENT",
    session.permissions,
  );
  const temporaryPriceChangeAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "TEMPORARY_PRICE_CHANGE",
    session.permissions,
  );
  const checkoutDiscountPercent = checkoutDiscountCents > 0
    ? Math.round((
        checkoutDiscountCents
        / Math.max(1, Math.round(authoritativeTotal * 100) + checkoutDiscountCents)
      ) * 10_000) / 100
    : 0;
  const detectedSaleMutationOperations = detectSaleMutationOperations(
    currentEconomicLines.map((line) => ({
      quantity: line.quantity,
      discountPercent: line.discountPercent,
      catalogName: line.product.name,
      temporaryName: line.temporaryName,
      catalogUnitPrice: line.product.salePrice,
      openUnitPrice: line.openUnitPrice,
    })),
    checkoutDiscountPercent,
  );
  if (currentEconomicLines.some((line) => line.returnOrigin?.sourceType === "SALES_INVOICE")) {
    detectedSaleMutationOperations.push({ code: "RETURN_SALES_INVOICE" });
  } else if (currentEconomicLines.some((line) => Boolean(line.returnOrigin))) {
    detectedSaleMutationOperations.push({ code: "RETURN_TICKET" });
  }
  const temporaryPriceLines = currentEconomicLines.filter(
    (line) => line.openUnitPrice != null && Number(line.product.salePrice ?? 0) !== 0,
  );
  const temporaryPriceAuthorizationsReady = temporaryPriceLines.every(
    (line) => saleLineHasValidTemporaryPriceAuthorization(
      line,
      Date.now(),
      operationSecurity?.version,
    ),
  );
  // Temporary price changes are authorized when the price is accepted, never
  // by the checkout dialog. Missing or expired grants fail closed here. The
  // exception is a TEMPORAL price preserved by a CURRENT_REPRICING import:
  // imported lines have no local cart-line grant, so the backend explicitly
  // marks the generic operation that checkout must authorize. Open-price
  // products never set that marker.
  const importedSaleMutationOperations: DetectedSaleMutationOperation[] = [];
  if (lines.some((line) => (
    line.previousTicketImportOrigin?.pricingMode === "CURRENT_REPRICING"
      && line.previousTicketImportOrigin.temporaryPriceAuthorizationRequired
  ))) {
    importedSaleMutationOperations.push({ code: "TEMPORARY_PRICE_CHANGE" });
  }
  if (previousTicketImportBatch?.pricingMode === "CURRENT_REPRICING"
      && previousTicketImportBatch.manualDiscountAuthorizationRequired) {
    importedSaleMutationOperations.push({ code: "APPLY_CHECKOUT_DISCOUNT" });
  }
  const unresolvedSaleMutationOperations: DetectedSaleMutationOperation[] = [
    ...detectedSaleMutationOperations.filter(
    (operation) => operation.code !== "TEMPORARY_PRICE_CHANGE",
    ),
    ...importedSaleMutationOperations,
  ];
  const saleMutationAuthorizations: SaleMutationAuthorizationRequirement[] | null =
    saleMutationAuthorizationRequirements(
      operationSecurity,
      unresolvedSaleMutationOperations.map((operation) => ({
        ...operation,
        label: t(`gestion.salesOperationSecurity.operation.${operation.code}`),
      })),
      session.permissions,
      userDiscountLimit,
    );
  const saleMutationSecurityUnavailable = saleMutationAuthorizations === null;
  const paymentActionsDisabled = basePaymentActionsDisabled
    || saleMutationSecurityUnavailable
    || !temporaryPriceAuthorizationsReady;
  const cashSessionCopy = locale === "en"
    ? {
        close: "Close register",
        unavailable: "Cash register unavailable",
        retry: "Retry",
        exit: "Exit Sales",
        loading: "Preparing cash register…",
        error: "The cash session could not be prepared.",
        recoveryError: "The interrupted cash close could not be recovered safely.",
        recovered: "The previous cash close was recovered successfully.",
      }
    : locale === "zh"
      ? {
          close: "关闭收银会话",
          unavailable: "收银会话不可用",
          retry: "重试",
          exit: "退出销售",
          loading: "正在准备收银会话…",
          error: "无法准备收银会话。",
          recoveryError: "无法安全恢复中断的收银结账。",
          recovered: "已成功恢复上一次收银结账。",
        }
      : {
          close: "Cerrar caja",
          unavailable: "Caja no disponible",
          retry: "Reintentar",
          exit: "Salir de Ventas",
          loading: "Preparando caja…",
          error: "No se pudo preparar la sesión de caja.",
          recoveryError: "No se pudo recuperar con seguridad el cierre de caja interrumpido.",
          recovered: "El cierre de caja anterior se recuperó correctamente.",
        };

  function cartColumnLabel(column: SaleCartColumnKey) {
    return t(`sale.cart.column.${column}`);
  }

  function renderCartRow(localLine: SaleLine, authoritativeLine?: AuthoritativeSaleLine) {
    const product = localLine.product;
    const name = authoritativeLine?.name
      ?? localLine.temporaryName
      ?? product.name
      ?? t("sale.main.unnamedProduct");
    const code = authoritativeLine?.code
      ?? product.code
      ?? product.barcode
      ?? t("sale.main.missingCode");
    const barcode = product.barcode?.trim() ?? "";
    const quantity = finiteAmount(authoritativeLine?.quantity ?? localLine.quantity);
    const quantityText = formatProductQuantity(quantity, product.productType, locale);
    const packageQuantity = finiteAmount(product.packageQuantity, Number.NaN);
    const packageText = Number.isFinite(packageQuantity) && packageQuantity > 0
      ? packageQuantity.toLocaleString(locale, { maximumFractionDigits: 3 })
      : "";
    const appliedUnitPrice = finiteAmount(
      authoritativeLine?.baseUnitPrice ?? saleLineUnitPrice(localLine, activeMember),
    );
    const displayedSalePrice = saleCartDisplayedUnitPrice(
      localLine,
      activeMember,
      authoritativeLine,
    );
    const manualDiscount = authoritativeLine
      ? finiteAmount(authoritativeLine.manualDiscountPercent)
      : finiteAmount(localLine.discountPercent);
    const memberDiscount = authoritativeLine
      ? finiteAmount(authoritativeLine.memberDiscountPercent)
      : finiteAmount(localLine.memberDiscountPercent);
    const specialPrice = saleCartSpecialPrice(product, activeMember, authoritativeLine);
    const offerDiscount = specialPrice?.type === "OFFER_DISCOUNT"
      ? finiteAmount(specialPrice.discountPercent)
      : 0;
    const discountText = offerDiscount > 0
      ? `${formatSaleAmount(offerDiscount)}%`
      : memberDiscount > 0 && memberDiscount >= manualDiscount
        ? `${t("sale.main.member")} ${formatSaleAmount(memberDiscount)}%`
        : manualDiscount > 0
          ? `${formatSaleAmount(manualDiscount)}%`
          : "";
    const totalAmount = authoritativeLine
      ? finiteAmount(authoritativeLine.finalSubtotal)
      : saleLineSubtotal(localLine, activeMember);
    const selectionLabel = `${name} ${quantityText} x ${formatSaleAmount(appliedUnitPrice)} ${discountText} ${formatSaleAmount(totalAmount)}`;
    const cartLineId = saleCartLineIdentity(localLine);
    const selected = selectedLineId === cartLineId;
    const touchQuantityLocked = paymentLocked
      || Boolean(localLine.returnOrigin)
      || Boolean(localLine.previousTicketImportOrigin);

    function adjustTouchQuantity(delta: number) {
      if (touchQuantityLocked) return;
      const nextQuantity = Math.min(9999, Math.max(1, localLine.quantity + delta));
      setSelectedLineId(cartLineId);
      setLines((current) => updateSaleLineQuantity(current, cartLineId, nextQuantity));
    }

    function renderCell(column: SaleCartColumnKey) {
      if (column === "image") {
        return (
          <td className="sale-cart-image-cell" data-column-key={column} key={column}>
            <SaleCartProductThumbnail
              product={product}
              token={session.accessToken}
            />
          </td>
        );
      }
      if (column === "code") {
        return <td className="sale-cart-code" data-column-key={column} key={column}>{code}</td>;
      }
      if (column === "barcode") {
        return (
          <td className="sale-cart-barcode" data-column-key={column} key={column}>
            {barcode}
          </td>
        );
      }
      if (column === "name") {
        return (
          <td className="sale-cart-name" data-column-key={column} key={column}>
            <button
              type="button"
              className="sale-cart-select"
              aria-label={selectionLabel}
              aria-pressed={selected}
              onClick={(event) => {
                event.stopPropagation();
                setSelectedLineId(cartLineId);
              }}
            >
              <strong className="product-name-text">{name}</strong>
              {localLine.previousTicketImportOrigin && (
                <small className="sale-line-historical-origin">
                  {saleMainMessage(t, localLine.previousTicketImportOrigin.pricingMode === "FROZEN_EXACT"
                    ? "sale.importPreviousTicket.lineOrigin.exact"
                    : "sale.importPreviousTicket.lineOrigin.repriced", {
                    ticketNumber: localLine.previousTicketImportOrigin.sourceTicketNumber,
                  })}
                </small>
              )}
              {localLine.previousTicketImportOrigin?.manualPricePreserved && (
                <small className="sale-line-historical-origin">
                  {t("sale.importPreviousTicket.manualPricePreserved")}
                </small>
              )}
              {(localLine.serialNumbers ?? []).map((serial) => (
                <small className="sale-line-serial" key={serial}>S/N: {serial}</small>
              ))}
            </button>
          </td>
        );
      }
      if (column === "quantity") {
        return (
          <td className="sale-cart-number sale-cart-quantity" data-column-key={column} key={column}>
            {interfaceMode === "TOUCH" ? (
              <div className="sale-cart-touch-quantity">
                <button
                  type="button"
                  aria-label={`${t("sale.touch.decreaseQuantity")}: ${name}`}
                  disabled={touchQuantityLocked || quantity <= 1}
                  onClick={(event) => { event.stopPropagation(); adjustTouchQuantity(-1); }}
                >−</button>
                <output aria-label={`${t("sale.quantity.label")}: ${name}`}>{quantityText}</output>
                <button
                  type="button"
                  aria-label={`${t("sale.touch.increaseQuantity")}: ${name}`}
                  disabled={touchQuantityLocked || quantity >= 9999}
                  onClick={(event) => { event.stopPropagation(); adjustTouchQuantity(1); }}
                >+</button>
              </div>
            ) : quantityText}
          </td>
        );
      }
      if (column === "package") {
        return (
          <td className="sale-cart-number sale-cart-package" data-column-key={column} key={column}>
            {packageText}
          </td>
        );
      }
      if (column === "salePrice") {
        return (
          <td className="sale-cart-number sale-cart-sale-price" data-column-key={column} key={column}>
            {formatSaleAmount(displayedSalePrice)} €
          </td>
        );
      }
      if (column === "discount") {
        return <td className="sale-cart-discount" data-column-key={column} key={column}>{discountText}</td>;
      }
      if (column === "specialPrice") {
        const specialLabel = specialPrice?.type === "MEMBER_PRICE"
          ? t("sale.cart.special.member")
          : specialPrice?.type === "OFFER_DISCOUNT"
            ? t("sale.cart.special.offerDiscount")
            : t("sale.cart.special.offerPrice");
        return (
          <td className="sale-cart-special" data-column-key={column} key={column}>
            {specialPrice ? (
              <>
                <small>{specialLabel}</small>
                <strong>
                  {formatSaleAmount(specialPrice.unitPrice)} €
                  <span>{t("sale.cart.perUnit")}</span>
                </strong>
              </>
            ) : null}
          </td>
        );
      }
      return (
        <td className="sale-cart-number sale-cart-total" data-column-key={column} key={column}>
          {formatSaleAmount(totalAmount)} €
        </td>
      );
    }

    return (
      <tr
        className={`sale-cart-row${selected ? " selected" : ""}${localLine.previousTicketImportOrigin ? ` historical-import ${localLine.previousTicketImportOrigin.pricingMode === "CURRENT_REPRICING" ? "current-repricing-import" : "frozen-exact-import"}` : ""}`}
        key={cartLineId}
        data-cart-line-id={cartLineId}
        onClick={() => setSelectedLineId(cartLineId)}
      >
        {visibleCartColumns.map((column) => renderCell(column.key))}
      </tr>
    );
  }

  async function prepareSalesCashSession() {
    if (!terminalContext.terminalId || !session.accessToken) {
      setCashSessionError(cashSessionCopy.error);
      setCashSessionState("ERROR");
      return;
    }
    setCashSessionState("LOADING");
    setCashSessionError("");
    let closeFlowAfterReadiness = cashCloseRecovery.status === "valid"
      ? cashCloseRecovery.envelope.flow
      : null;
    try {
      if (cashCloseRecovery.status === "blocked") {
        setCashSessionError(cashSessionCopy.recoveryError);
        setCashSessionState("ERROR");
        return;
      }
      if (cashCloseRecovery.status === "valid") {
        const persistedFlow = cashCloseRecovery.envelope.flow;
        try {
          const recovery = await recoverCashCloseOperation(
            terminalContext.terminalId,
            persistedFlow.closeOperationId,
            session.accessToken,
          );
          if (recovery.status === "CERRADA" || recovery.result?.status === "CERRADA") {
            clearCashCloseRecovery(localStorage, terminalContext.terminalCode);
            setCashCloseRecovery({ status: "empty" });
            setCashSessionCloseFlow(null);
            setCashSessionCloseOpen(false);
            setShortcutStatus(cashSessionCopy.recovered);
            onBack?.();
            return;
          }
          const nextAttemptId = recovery.latestReconciliationAttemptId
            === persistedFlow.reconciliationAttemptId
            ? createCashCloseWithdrawalIdempotencyKey()
            : persistedFlow.reconciliationAttemptId;
          const recoveredFlow: CashCloseUiFlow = {
            ...persistedFlow,
            reconciliationAttemptId: nextAttemptId,
            phase: recovery.status === "REQUIERE_ARQUEO"
              ? "RECONCILIATION_REQUIRED"
              : persistedFlow.phase,
            finalWithdrawal: String(recovery.finalWithdrawalAmount),
            comment: recovery.finalWithdrawalComment ?? "",
          };
          saveCashCloseRecovery(
            localStorage,
            terminalContext.terminalCode,
            recoveredFlow,
          );
          setCashCloseRecovery(loadCashCloseRecovery(
            localStorage,
            terminalContext.terminalCode,
          ));
          setCashSessionCloseFlow(recoveredFlow);
          setCashSessionCloseOpen(true);
          setCashSessionState("OPEN");
          return;
        } catch (failure) {
          if (!(failure instanceof ApiError
            && failure.status === 404
            && failure.problem?.code === "NOT_FOUND")) {
            throw failure;
          }
          closeFlowAfterReadiness = {
            ...persistedFlow,
            phase: "READY",
          };
          saveCashCloseRecovery(
            localStorage,
            terminalContext.terminalCode,
            closeFlowAfterReadiness,
          );
          setCashCloseRecovery(loadCashCloseRecovery(
            localStorage,
            terminalContext.terminalCode,
          ));
        }
      }
      const readiness = await prepareCashSessionForSales(
        terminalContext.terminalId,
        session.accessToken,
      );
      setCashWithdrawalPolicy({
        requireEntryBreakdown: Boolean(readiness.requireEntryBreakdown),
        entryDenominations: Array.isArray(readiness.entryDenominations)
          ? readiness.entryDenominations
          : [],
        requireBreakdown: Boolean(readiness.requireWithdrawalBreakdown),
        denominations: Array.isArray(readiness.withdrawalDenominations)
          ? readiness.withdrawalDenominations
          : [],
      });
      setCashSessionState(readiness.open ? "OPEN" : "REQUIRED");
      if (readiness.open && closeFlowAfterReadiness) {
        setCashSessionCloseFlow(closeFlowAfterReadiness);
        setCashSessionCloseOpen(true);
      }
    } catch (failure) {
      setCashSessionError(cashCloseRecovery.status === "valid"
        ? cashSessionCopy.recoveryError
        : failure instanceof Error ? failure.message : cashSessionCopy.error);
      setCashSessionState("ERROR");
    }
  }

  useEffect(() => {
    void prepareSalesCashSession();
  }, [session.accessToken, terminalContext.terminalId]);

  useEffect(() => {
    let cancelled = false;
    loadSalesOperationSecurity(session.accessToken)
      .then((configuration) => {
        if (!cancelled) setOperationSecurity(configuration);
      })
      .catch(() => {
        if (!cancelled) setOperationSecurity(null);
      });
    return () => {
      cancelled = true;
    };
  }, [operationSecurityReload, session.accessToken]);

  useEffect(() => {
    if (!pendingDraft) {
      setPendingCardPaymentMode(null);
      return;
    }
    let cancelled = false;
    void apiRequest<PendingTerminalPaymentConfiguration>(
      "/terminal-configuration/payment",
      { token: session.accessToken },
    ).then((configuration) => {
      if (!cancelled) {
        setPendingCardPaymentMode(resolvePendingCardPaymentMode(configuration));
      }
    }).catch(() => {
      if (!cancelled) setPendingCardPaymentMode(null);
    });
    return () => {
      cancelled = true;
    };
  }, [pendingDraft?.checkoutId, session.accessToken]);

  function reportOperationSecurityUnavailable() {
    setShortcutStatus(t("sale.operationSecurity.unavailable"));
    setOperationSecurityReload((current) => current + 1);
  }

  async function recoverOperationSecurityAndOpen(
    operationCode: string,
    open: () => void,
  ) {
    setShortcutStatus(t("sale.operationSecurity.unavailable"));
    try {
      const configuration = await loadSalesOperationSecurity(session.accessToken);
      setOperationSecurity(configuration);
      const authorization = findSaleOperationAuthorization(
        configuration,
        operationCode,
        session.permissions,
      );
      if (!authorization) return;
      setShortcutStatus("");
      open();
    } catch {
      setShortcutStatus(t("sale.operationSecurity.unavailable"));
    }
  }

  async function openSalesUtilityWindow(
    kind: "INTERNAL_EAN" | "PRODUCT_LABEL",
  ) {
    const desktop = window.tpvDesktop?.salesUtilities;
    if (!desktop) {
      setShortcutStatus(t("sale.utilityWindow.restartRequired"));
      return;
    }
    setSalesUtilityOpening(true);
    setShortcutStatus("");
    try {
      const result = await desktop.open({
        kind,
        locale,
        session,
        terminalContext,
        initialProductId: selectedLine?.product.id,
        ...(kind === "INTERNAL_EAN" && internalEanAuthorization
          ? { authorization: internalEanAuthorization }
          : {}),
      });
      if (!result.ok) {
        setShortcutStatus(result.message);
        return;
      }
      if (result.catalogChanged) {
        setCatalogReload((current) => current + 1);
        setShortcutStatus(t("sale.internalEan.assigned"));
      } else if (result.printed) {
        setShortcutStatus(t(result.pdf
          ? "sale.productLabel.pdfSaved"
          : "sale.productLabel.printed"));
      }
    } catch (failure) {
      setShortcutStatus(failure instanceof Error
        ? failure.message
        : t("sale.operationSecurity.unavailable"));
    } finally {
      setSalesUtilityOpening(false);
      queueMicrotask(() => searchInputRef.current?.focus());
    }
  }

  function invalidateCashOpening() {
    cashOpeningRef.current.generation += 1;
    cashOpeningRef.current.current = false;
    setCashOpening(false);
  }

  function updateMatchingPrintOutcome(documentId: string, outcome: TicketPrintOutcome) {
    setCashResult((current) => updateCashResultPrintOutcome(current, documentId, outcome));
  }

  function startAutomaticTicketPrint(
    snapshot: ConfirmedTicketPrintSnapshot,
    printMode: SalePrintMode = salePrintMode,
  ) {
    void outputConfirmedTicket(snapshot, terminalContext, printMode, locale)
      .then((outcome) => updateMatchingPrintOutcome(snapshot.documentId, outcome));
  }

  function renderReturnPromotionAdjustmentRow(line: AuthoritativeSaleLine) {
    function renderCell(column: SaleCartColumnKey) {
      if (column === "code") {
        return <td className="sale-cart-code" data-column-key={column} key={column}>{t("sale.cart.returnAdjustment.code")}</td>;
      }
      if (column === "name") {
        return (
          <td className="sale-cart-name" data-column-key={column} key={column}>
            <strong className="sale-return-adjustment-name">{t("sale.cart.returnAdjustment.name")}</strong>
          </td>
        );
      }
      if (column === "total") {
        return (
          <td className="sale-cart-number sale-cart-total" data-column-key={column} key={column}>
            +{formatSaleAmount(finiteAmount(line.finalSubtotal))} €
          </td>
        );
      }
      return <td data-column-key={column} key={column} />;
    }

    return (
      <tr className="sale-cart-return-adjustment" key={line.lineId}>
        {visibleCartColumns.map((column) => renderCell(column.key))}
      </tr>
    );
  }

  function updateMatchingVoucherPrintOutcome(
    voucherCode: string,
    outcome: TicketPrintOutcome,
  ) {
    setCashResult((current) => updateCashResultVoucherPrintOutcome(
      current,
      voucherCode,
      outcome,
    ));
  }

  function startAutomaticVoucherPrint(snapshot: IssuedVoucherPrintSnapshot) {
    void outputIssuedVoucher(snapshot, terminalContext, locale)
      .then((outcome) => updateMatchingVoucherPrintOutcome(snapshot.code, outcome));
  }

  function retryTicketPrint() {
    const snapshot = cashResult?.printTicket;
    if (!snapshot) return;
    setCashResult((current) => current?.printTicket?.documentId === snapshot.documentId
      ? { ...current, printStatus: "PRINTING", printTechnicalMessage: undefined }
      : current);
    const retry = lastPrintMode === "DEFAULT"
      ? retryConfirmedTicketPrint(snapshot, terminalContext)
      : outputConfirmedTicket(snapshot, terminalContext, lastPrintMode, locale);
    void retry
      .then((outcome) => updateMatchingPrintOutcome(snapshot.documentId, outcome));
  }

  function retryVoucherPrint() {
    const snapshot = cashResult?.issuedVoucher;
    if (!snapshot) return;
    setCashResult((current) => current?.issuedVoucher?.code === snapshot.code
      ? {
          ...current,
          voucherPrintStatus: "PRINTING",
          voucherPrintTechnicalMessage: undefined,
        }
      : current);
    void outputIssuedVoucher(snapshot, terminalContext, locale)
      .then((outcome) => updateMatchingVoucherPrintOutcome(snapshot.code, outcome));
  }

  async function handleSaleLogout() {
    if (logoutInProgressRef.current) return;
    if (lines.length > 0) {
      setShortcutStatus("No se puede cerrar sesión mientras el carrito tenga productos");
      return;
    }
    logoutInProgressRef.current = true;
    try {
      const result = await paymentCheckoutRef.current?.prepareLogout();
      if (result === "READY") onLogout?.();
    } catch {
      // Fail closed: checkout keeps the recoverable payment state visible.
    } finally {
      logoutInProgressRef.current = false;
    }
  }

  async function handleApplicationClose() {
    if (shutdownInProgressRef.current || !paymentCheckoutRef.current) return false;
    shutdownInProgressRef.current = true;
    try {
      return await paymentCheckoutRef.current.prepareApplicationClose() === "READY";
    } catch {
      return false;
    } finally {
      shutdownInProgressRef.current = false;
    }
  }

  useEffect(() => {
    let cancelled = false;
    setCatalogLoading(true);
    setCatalogError(false);
    Promise.all([
      apiRequest<SaleProduct[]>("/products/sale", { token: session.accessToken }),
      apiRequest<{ allowInactiveProductSales?: boolean }>("/stock/settings", { token: session.accessToken })
        .catch(() => ({ allowInactiveProductSales: false }))
    ])
      .then(([loadedProducts, stockSettings]) => {
        if (!cancelled) {
          setProducts(loadedProducts);
          setAllowInactiveProductSales(Boolean(stockSettings.allowInactiveProductSales));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setCatalogError(true);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setCatalogLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [catalogReload, session.accessToken]);

  useEffect(() => {
    if (!pendingRecoveryBlocked || !blockedRecoveryDialogRef.current) return;
    const root = blockedRecoveryDialogRef.current;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    const blockEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
    };
    root.addEventListener("keydown", blockEscape);
    return () => { root.removeEventListener("keydown", blockEscape); deactivate(); };
  }, [pendingRecoveryBlocked]);

  function addProduct(product: SaleProduct, openUnitPrice?: number, quantity = 1) {
    deletionControl.reset("PRODUCT_ADDED");
    const existingLine = openUnitPrice == null
      ? lines.find((line) => !line.returnOrigin
        && !line.previousTicketImportOrigin
        && line.product.id === product.id
        && line.openUnitPrice == null)
      : undefined;
    const cartLineId = existingLine ? saleCartLineIdentity(existingLine) : createSaleCartLineId();
    setLines((current) => applyMemberDiscounts(
      addSaleLine(current, product, openUnitPrice, quantity, cartLineId),
      selectedCustomer,
    ));
    setSelectedLineId(cartLineId);
    setQuery("");
    setShortcutStatus(interfaceMode === "TOUCH"
      ? `${t("sale.touch.productAdded")}: ${product.name || product.code || t("sale.main.unnamedProduct")}`
      : "");
    searchInputRef.current?.focus();
  }

  function previousTicketImportIssueText(issue: PreviousTicketImportIssue) {
    const product = issue.productLabel ?? t("sale.main.unnamedProduct");
    switch (issue.type) {
      case "EMPTY":
        return t("sale.importPreviousTicket.error.empty");
      case "INVALID_RESPONSE":
        return t("sale.importPreviousTicket.error.invalid");
      case "INVALID_QUANTITY":
        return saleMainMessage(t, "sale.importPreviousTicket.error.invalidQuantity", { product });
    }
  }

  function previousTicketImportLineLockedText(line: SaleLine) {
    return t(line.previousTicketImportOrigin?.pricingMode === "FROZEN_EXACT"
      ? "sale.importPreviousTicket.lineLocked.exact"
      : "sale.importPreviousTicket.lineLocked.repriced");
  }

  function endPreviousTicketImport(status: string) {
    previousTicketImportBusyRef.current = false;
    setPreviousTicketImportBusy(false);
    setShortcutStatus(status);
    setPreviousTicketImportFocusRequest((value) => value + 1);
  }

  function commitPreviousTicketImport(draft: PendingPreviousTicketImport) {
    if (linesRef.current !== draft.baseLines) {
      endPreviousTicketImport(t("sale.importPreviousTicket.error.cartChanged"));
      return;
    }
    try {
      const appended = appendPreviousTicketImport(
        draft.baseLines,
        draft.lines.map((line) => ({ line })),
      );
      deletionControl.reset("PRODUCT_ADDED");
      const nextLines = applyMemberDiscounts(appended.lines, draft.customer);
      linesRef.current = nextLines;
      setLines(nextLines);
      setSelectedLineId(appended.selectedLineId);
      setSelectedCustomer(draft.customer);
      setPreviousTicketImportBatch({
        ticketId: draft.preview.ticketId,
        ticketNumber: draft.preview.ticketNumber,
        fingerprint: draft.preview.fingerprint,
        sourceStatus: draft.preview.status,
        pricingMode: draft.preview.pricingMode,
        preservedManualDiscountAmount: Number(draft.preview.preservedManualDiscountAmount),
        manualDiscountAuthorizationRequired:
          draft.preview.manualDiscountAuthorizationRequired,
        total: Number(draft.preview.total),
        adjustments: draft.preview.adjustments ?? [],
        reconciliationAdjustment: previousTicketImportReconciliationAdjustment(draft.preview),
      });
      setQuery("");
      endPreviousTicketImport(saleMainMessage(
        t,
        draft.preview.pricingMode === "FROZEN_EXACT"
          ? "sale.importPreviousTicket.success.exact"
          : "sale.importPreviousTicket.success.repriced",
        { ticketNumber: draft.preview.ticketNumber, count: draft.lines.length },
      ));
    } catch {
      endPreviousTicketImport(t("sale.importPreviousTicket.error.invalid"));
    }
  }

  async function importPreviousTicket() {
    if (previousTicketImportBusyRef.current) return;
    if (previousTicketImportBatch
        || lines.some((line) => line.returnOrigin || line.quantity <= 0)) {
      setShortcutStatus(t("sale.importPreviousTicket.error.conflict"));
      return;
    }
    previousTicketImportBusyRef.current = true;
    setPreviousTicketImportBusy(true);
    setShortcutStatus(t("sale.importPreviousTicket.loading"));
    const controller = new AbortController();
    let timedOut = false;
    const timeoutId = globalThis.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, previousTicketImportTimeoutMs);
    try {
      const preview = await apiRequest<PreviousTicketImportPreview>(
        "/tickets/previous-current-terminal/import-preview",
        { token: session.accessToken, signal: controller.signal },
      );
      if (!preview
          || !Array.isArray(preview.lines)
          || typeof preview.ticketNumber !== "string"
          || typeof preview.ticketId !== "string"
          || typeof preview.fingerprint !== "string"
          || !["CONFIRMADO", "ANULADO"].includes(preview.status)
          || !["FROZEN_EXACT", "CURRENT_REPRICING"].includes(preview.pricingMode)
          || typeof preview.manualDiscountAuthorizationRequired !== "boolean"
          || !Number.isFinite(Number(preview.preservedManualDiscountAmount))
          || !Number.isFinite(Number(preview.total))
          || !Array.isArray(preview.adjustments)) {
        endPreviousTicketImport(t("sale.importPreviousTicket.error.invalid"));
        return;
      }
      const baseLines = linesRef.current;
      if (baseLines.some((line) => line.returnOrigin
          || line.previousTicketImportOrigin
          || line.quantity <= 0)) {
        endPreviousTicketImport(t("sale.importPreviousTicket.error.conflict"));
        return;
      }
      const prepared = preparePreviousTicketImport(preview);
      if (prepared.issues.length > 0) {
        endPreviousTicketImport(prepared.issues.map(previousTicketImportIssueText).join(" · "));
        return;
      }
      let customer: SaleCustomer | null = null;
      if (preview.customerId) {
        customer = await apiRequest<SaleCustomer>(
          `/customers/sale-options/${encodeURIComponent(preview.customerId)}`,
          { token: session.accessToken, signal: controller.signal },
        );
        if (!customer || customer.id !== preview.customerId) {
          endPreviousTicketImport(t("sale.importPreviousTicket.error.customer"));
          return;
        }
      }
      const draft: PendingPreviousTicketImport = {
        preview,
        lines: prepared.lines.map(({ line }) => line),
        customer,
        baseLines,
      };
      commitPreviousTicketImport(draft);
    } catch (error) {
      if (timedOut) {
        endPreviousTicketImport(t("sale.importPreviousTicket.error.timeout"));
        return;
      }
      const detail = error instanceof ApiError && typeof error.problem?.detail === "string"
        ? ` ${error.problem.detail}`
        : "";
      endPreviousTicketImport(`${t("sale.importPreviousTicket.error.request")}${detail}`);
    } finally {
      globalThis.clearTimeout(timeoutId);
    }
  }

  function addReturnLinesToCart(returnLines: ReturnCartLine[]) {
    if (paymentLocked || returnLines.length === 0) return;
    if (saleReturnSourceConflict(lines, returnLines)) {
      setShortcutStatus(t("ticketReturn.singleSourceOnly"));
      return;
    }
    const added = returnLines.map((returnLine): SaleLine => {
      const catalogProduct = products.find((product) => product.id === returnLine.productId);
      const product: SaleProduct = catalogProduct ?? {
        id: returnLine.productId,
        active: true,
        productType: returnLine.productType,
        code: returnLine.code,
        barcode: returnLine.barcode,
        barcode2: returnLine.barcode2,
        name: returnLine.name,
        salePrice: returnLine.unitPrice,
        taxId: "return-origin",
        taxesIncluded: returnLine.taxesIncluded,
        taxRegime: returnLine.taxRegime === "IGIC" ? "IGIC" : "IVA",
        taxPercentage: returnLine.taxPercentage,
      };
      return {
        cartLineId: createSaleCartLineId(),
        product,
        quantity: -returnLine.returnQuantity,
        returnUnitPrice: Number(returnLine.unitPrice),
        discountPercent: Number(returnLine.discount),
        serialNumbers: returnLine.selectedSerialNumbers,
        returnOrigin: {
          sourceType: returnLine.sourceType,
          sourceCode: returnLine.sourceCode,
          sourceTicketId: returnLine.sourceTicketId,
          sourceTicketNumber: returnLine.sourceTicketNumber,
          sourceLineId: returnLine.lineId,
          giftReceiptLineId: returnLine.giftReceiptLineId,
        },
      };
    });
    const lastAddition = added[added.length - 1];
    const existingTarget = lines.find((line) => line.returnOrigin
      && lastAddition.returnOrigin
      && line.returnOrigin.sourceTicketId === lastAddition.returnOrigin.sourceTicketId
      && line.returnOrigin.sourceLineId === lastAddition.returnOrigin.sourceLineId
      && (line.returnOrigin.giftReceiptLineId ?? null)
        === (lastAddition.returnOrigin.giftReceiptLineId ?? null));
    setLines((current) => mergeSaleReturnLines(current, added));
    setSelectedLineId(saleCartLineIdentity(existingTarget ?? lastAddition));
    setTicketReturnOpen(false);
    setShortcutStatus(t("ticketReturn.addedToCart"));
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function requestPriceOrAddProduct(product: SaleProduct) {
    const packageQuantity = Number(product.packageQuantity ?? 1);
    const quantity = nextScanMode === "PACKAGE"
      ? nextScanQuantity * (Number.isFinite(packageQuantity) && packageQuantity > 0 ? packageQuantity : 1)
      : nextScanQuantity;
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    if (!isProductQuantityPrecisionValid(quantity, product.productType)
        || quantity < productQuantityStep(product.productType) || quantity > 9999) {
      setShortcutStatus("La cantidad resultante del paquete no es válida");
      return;
    }
    if (saleProductRequiresOpenPrice(product)) {
      setPendingOpenPriceQuantity(quantity);
      setPendingOpenPriceProduct(product);
      return;
    }
    addProduct(product, undefined, quantity);
  }

  useEffect(() => {
    if (actionDialog === "quantity") {
      quantityInputRef.current?.focus();
      quantityInputRef.current?.select();
    }
    if (actionDialog === "discount") {
      discountInputRef.current?.focus();
      discountInputRef.current?.select();
    }
    if (actionDialog === "temporaryName") {
      temporaryNameInputRef.current?.focus();
      temporaryNameInputRef.current?.select();
    }
    if (actionDialog === "temporaryPrice") {
      temporaryPriceInputRef.current?.focus();
      temporaryPriceInputRef.current?.select();
    }
    if (actionDialog === "remove") removeConfirmButtonRef.current?.focus();
  }, [actionDialog]);

  function requestAddProduct(product: SaleProduct) {
    if (previousTicketImportBusyRef.current) return;
    if (product.active === false) {
      if (!allowInactiveProductSales) {
        return;
      }
      setPendingInactiveProduct(product);
      return;
    }
    requestPriceOrAddProduct(product);
  }

  function confirmInactiveProduct() {
    if (!pendingInactiveProduct) {
      return;
    }
    const product = pendingInactiveProduct;
    setPendingInactiveProduct(null);
    requestPriceOrAddProduct(product);
  }

  function cancelInactiveProduct() {
    setPendingInactiveProduct(null);
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function cancelOpenPrice() {
    setPendingOpenPriceProduct(null);
    setPendingOpenPriceQuantity(1);
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function confirmOpenPrice(price: number) {
    if (!pendingOpenPriceProduct) return;
    const product = pendingOpenPriceProduct;
    const quantity = pendingOpenPriceQuantity;
    setPendingOpenPriceProduct(null);
    setPendingOpenPriceQuantity(1);
    addProduct(product, price, quantity);
  }

  function openQuantityDialog() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin) return;
    setQuantityInput(String(selectedLine.quantity));
    setActionError("");
    setActionDialog("quantity");
  }

  function openDiscountDialog() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin
        || !canApplyManualDiscount || saleProductBlocksManualDiscount(selectedLine.product)) return;
    setDiscountInput(String(selectedLine.discountPercent));
    setActionError("");
    setActionDialog("discount");
  }

  function openTemporaryNameDialog() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin || paymentLocked) return;
    setTemporaryNameInput(selectedLine.temporaryName ?? selectedLine.product.name ?? "");
    setActionError("");
    setActionDialog("temporaryName");
  }

  function openTemporaryPriceDialog() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin
        || paymentLocked || saleProductRequiresOpenPrice(selectedLine.product)) return;
    setTemporaryPriceInput(
      selectedLine.openUnitPrice == null ? "" : String(selectedLine.openUnitPrice),
    );
    setActionError("");
    setActionDialog("temporaryPrice");
  }

  function saveQuantity() {
    if (!selectedLineId) return;
    try {
      const nextLines = updateSaleLineQuantity(
        lines,
        selectedLineId,
        parseProductQuantityInput(quantityInput),
      );
      setLines(nextLines);
      setActionDialog(null);
    } catch {
      setActionError(t("sale.quantity.invalid"));
    }
  }

  function saveDiscount() {
    if (!selectedLineId) return;
    try {
      const discount = Number(discountInput);
      const nextLines = updateSaleLineDiscount(lines, selectedLineId, discount);
      setLines(nextLines);
      setActionDialog(null);
    } catch (error) {
      setActionError(error instanceof Error && error.message === "discount_blocked"
        ? t("sale.discountBlocked")
        : t("sale.discount.invalid"));
    }
  }

  function saveTemporaryName() {
    if (!selectedLineId) return;
    try {
      const nextLines = updateSaleLineTemporaryName(
        lines,
        selectedLineId,
        temporaryNameInput,
      );
      setLines(nextLines);
      setActionDialog(null);
      queueMicrotask(() => searchInputRef.current?.focus());
    } catch {
      setActionError(t("sale.temporaryName.invalid"));
    }
  }

  async function authorizeTemporaryPriceChange(
    change: { lineId: string; productId: string; unitPrice: number },
    credentials: SaleOperationCredentials,
  ) {
    setTemporaryPriceAuthorizationBusy(true);
    setTemporaryPriceAuthorizationError("");
    setActionError("");
    try {
      const authorization = await apiRequest<{
        token: string;
        expiresAt: string;
          policyVersion: number;
      }>("/pos/sale-operation-authorizations/temporary-price", {
        token: session.accessToken,
        body: {
          productId: change.productId,
          cartLineId: change.lineId,
          unitPrice: change.unitPrice,
          authorization: credentials,
        },
      });
      setLines((current) => updateSaleLineTemporaryPrice(
        current,
        change.lineId,
        change.unitPrice,
        {
          token: authorization.token,
          expiresAt: authorization.expiresAt,
          unitPrice: change.unitPrice,
          productId: change.productId,
          cartLineId: change.lineId,
          policyVersion: authorization.policyVersion,
        },
      ));
      setPendingTemporaryPriceChange(null);
      setActionDialog(null);
      queueMicrotask(() => searchInputRef.current?.focus());
    } catch (error) {
      const message = error instanceof ApiError
        ? error.message
        : t("sale.temporaryPrice.invalid");
      if (pendingTemporaryPriceChange) setTemporaryPriceAuthorizationError(message);
      else setActionError(message);
    } finally {
      setTemporaryPriceAuthorizationBusy(false);
    }
  }

  async function saveTemporaryPrice() {
    if (!selectedLineId) return;
    const normalized = temporaryPriceInput.trim().replace(",", ".");
    if (normalized && !/^\d+(?:\.\d{1,2})?$/.test(normalized)) {
      setActionError(t("sale.temporaryPrice.invalid"));
      return;
    }
    try {
      const unitPrice = normalized ? Number(normalized) : undefined;
      if (unitPrice == null || salePriceNumber(selectedLine?.product.salePrice) === 0) {
        const nextLines = updateSaleLineTemporaryPrice(
          lines,
          selectedLineId,
          unitPrice,
        );
        setLines(nextLines);
        setActionDialog(null);
        queueMicrotask(() => searchInputRef.current?.focus());
        return;
      }
      if (!selectedLine || !temporaryPriceChangeAuthorization) {
        setActionError(t("sale.temporaryPrice.authorizationUnavailable"));
        return;
      }
      const change = {
        lineId: selectedLineId,
        productId: selectedLine.product.id,
        unitPrice,
      };
      if (temporaryPriceChangeAuthorization.mode === "DIRECT") {
        await authorizeTemporaryPriceChange(change, {});
        return;
      }
      setPendingTemporaryPriceChange(change);
      setTemporaryPriceAuthorizationError("");
      setActionDialog(null);
    } catch {
      setActionError(t("sale.temporaryPrice.invalid"));
    }
  }

  function openCustomerDialog(continuePending = false) {
    if (previousTicketImportBatch) {
      setShortcutStatus(t("sale.importPreviousTicket.customerLocked"));
      return;
    }
    setPendingCustomerContinuation(continuePending);
    setCustomerCreateOpen(false);
    setCustomerEditId(null);
    setCustomerReceivablesOpen(null);
    setCustomerLoading(true);
    setCustomerError(false);
    setActionDialog("customer");
    setCustomerQuery("");
  }

  function chooseSaleCustomer(customer: SaleCustomer | null) {
    setSelectedCustomer(customer);
    setLines((current) => applyMemberDiscounts(current, customer));
    setActionDialog(null);
    if (pendingCustomerContinuation && customer) {
      setPendingCustomerContinuation(false);
      void beginPendingSale(customer);
    }
  }

  function closeCustomerDialog() {
    setPendingCustomerContinuation(false);
    setCustomerCreateOpen(false);
    setCustomerEditId(null);
    setCustomerReceivablesOpen(null);
    setActionDialog(null);
  }

  function selectedCustomerResult(): SaleCustomer | null | undefined {
    if (selectedCustomerResultId === noCustomerSelectionId) return null;
    return customerResults.find((candidate) => candidate.id === selectedCustomerResultId);
  }

  async function finishSavedSaleCustomer(customerId: string, selectForSale: boolean) {
    try {
      const saved = await apiRequest<SaleCustomer>(`/customers/sale-options/${customerId}`, {
        token: session.accessToken,
      });
      setCustomers((current) => [saved, ...current.filter((customer) => customer.id !== saved.id)]);
      setCustomerCreateOpen(false);
      setCustomerEditId(null);
      setSelectedCustomerResultId(saved.id);
      if (selectForSale) {
        chooseSaleCustomer(saved);
        return;
      }
      queueMicrotask(() => customerSearchInputRef.current?.focus());
    } catch {
      setCustomerCreateOpen(false);
      setCustomerEditId(null);
      setCustomerError(true);
    }
  }

  function openSelectedCustomerEditor() {
    const customer = selectedCustomerResult();
    if (!customer || !saleCustomerEditAllowed) return;
    setCustomerEditId(customer.id);
  }

  function openSelectedCustomerReceivables() {
    const customer = selectedCustomerResult();
    if (!customer || !saleCustomerReceivablesAllowed || Number(customer.outstandingDebt ?? 0) <= 0) return;
    setCustomerReceivablesOpen(customer);
  }

  function handleCustomerDialogKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      event.stopPropagation();
      setSelectedCustomerResultId((current) => {
        if (customerSelectionIds.length === 0) return "";
        const currentIndex = customerSelectionIds.indexOf(current);
        const nextIndex = currentIndex < 0
          ? event.key === "ArrowDown" ? 0 : customerSelectionIds.length - 1
          : (
            currentIndex
            + (event.key === "ArrowDown" ? 1 : -1)
            + customerSelectionIds.length
          ) % customerSelectionIds.length;
        return customerSelectionIds[nextIndex];
      });
      return;
    }
    if (event.key === "F5" && saleCustomerCreationAllowed) {
      event.preventDefault();
      event.stopPropagation();
      setCustomerCreateOpen(true);
      setCustomerEditId(null);
      return;
    }
    if (event.ctrlKey && event.key === "F7" && saleCustomerEditAllowed) {
      event.preventDefault();
      event.stopPropagation();
      openSelectedCustomerEditor();
      return;
    }
    if (event.key === "Enter" && saleCustomerReceivablesAllowed) {
      event.preventDefault();
      event.stopPropagation();
      openSelectedCustomerReceivables();
      return;
    }
    if (event.key !== "Insert") return;
    event.preventDefault();
    event.stopPropagation();
    const customer = selectedCustomerResult();
    if (customer !== undefined) chooseSaleCustomer(customer);
  }

  useEffect(() => {
    if (actionDialog !== "customer") return;
    if (customerSelectionIds.includes(selectedCustomerResultId)) return;
    setSelectedCustomerResultId(customerSelectionIds[0] ?? "");
  }, [actionDialog, customerSelectionIds, selectedCustomerResultId]);

  useEffect(() => {
    if (actionDialog !== "customer" || customerCreateOpen || customerEditId || customerReceivablesOpen) return;
    const generation = ++customerSearchGenerationRef.current;
    const timer = globalThis.setTimeout(() => {
      setCustomerLoading(true);
      setCustomerError(false);
      apiRequest<SaleCustomer[]>(`/customers/sale-options/search?q=${encodeURIComponent(customerQuery.trim())}&limit=50`, {
        token: session.accessToken,
      })
        .then((options) => {
          if (generation !== customerSearchGenerationRef.current) return;
          setCustomers(options);
          setSelectedCustomerResultId((current) => options.some((customer) => customer.id === current)
            ? current : options[0]?.id ?? (pendingCustomerContinuation ? "" : noCustomerSelectionId));
        })
        .catch(() => { if (generation === customerSearchGenerationRef.current) setCustomerError(true); })
        .finally(() => { if (generation === customerSearchGenerationRef.current) setCustomerLoading(false); });
    }, customerQuery ? 180 : 0);
    return () => globalThis.clearTimeout(timer);
  }, [actionDialog, customerCreateOpen, customerEditId, customerQuery, customerReceivablesOpen,
    pendingCustomerContinuation, session.accessToken]);

  async function beginPendingSale(customer: SaleCustomer) {
    if (pendingOpening || paymentActionsDisabled || paymentLocked
      || !authoritativeQuoteReady || authoritativeTotal <= 0) return;
    setPendingOpening(true);
    setPendingError("");
    try {
      const warehouses = await apiRequest<Array<{ id: string; active?: boolean; defaultWarehouse?: boolean; isDefaultWarehouse?: boolean }>>("/warehouses", { token: session.accessToken });
      const warehouse = warehouses.find((candidate) => candidate.active !== false && (candidate.defaultWarehouse || candidate.isDefaultWarehouse))
        ?? warehouses.find((candidate) => candidate.active !== false);
      if (!warehouse) throw new Error("No hay un almacen activo para registrar la venta");
      const now = new Date();
      setPendingDraft(pendingSaleDraftForCustomer(
        lines,
        customer,
        warehouse.id,
        now,
        newCheckoutId(),
        saleComment,
        salePrintMode,
      ));
    } catch (failure) {
      setPendingError(failure instanceof Error ? failure.message : "No se pudo preparar la venta pendiente");
    } finally { setPendingOpening(false); }
  }

  function openPendingSale() {
    if (pendingRecoveryBlocked || recoveredPendingSale || paymentActionsDisabled
      || paymentLocked || !paymentHydrated || !authoritativeQuoteReady || authoritativeTotal <= 0) return;
    if (!selectedCustomer) { openCustomerDialog(true); return; }
    void beginPendingSale(selectedCustomer);
  }

  function confirmRemoveLine() {
    if (!selectedLineId || !selectedLine) return;
    if (selectedLine.previousTicketImportOrigin) {
      setActionDialog(null);
      setShortcutStatus(previousTicketImportLineLockedText(selectedLine));
      return;
    }
    const removedLine = selectedLine;
    const fullTicketClear = lines.length === 1;
    setLines((current) => {
      const nextSelectedLineId = selectedProductAfterRemoval(current, selectedLineId);
      const remaining = removeSaleLine(current, selectedLineId);
      setSelectedLineId(nextSelectedLineId);
      return remaining;
    });
    setActionDialog(null);
    recordSaleLinesDeletion([removedLine], fullTicketClear);
  }

  function handleRemoveLineKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat || (event.key !== "Enter" && event.key !== "Escape")) return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key === "Enter") confirmRemoveLine();
    else setActionDialog(null);
  }

  function handleClearSaleKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat || (event.key !== "ArrowRight" && event.key !== "ArrowLeft")) return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key === "ArrowRight") clearSaleConfirmButtonRef.current?.focus();
    else clearSaleCancelButtonRef.current?.focus();
  }

  function handleClearLinesKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat || (event.key !== "ArrowRight" && event.key !== "ArrowLeft")) return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key === "ArrowRight") clearLinesConfirmButtonRef.current?.focus();
    else clearLinesCancelButtonRef.current?.focus();
  }

  function submitSearch() {
    const selected = selectSaleProduct(selectableProducts, query);
    if (selected) {
      requestAddProduct(selected);
      return;
    }
    if (!query.trim()) return;
    setProductSearchQuery(query);
    setProductSearchSelectedId("");
    setProductSearchOpen(true);
  }

  function openProductSearch() {
    setProductSearchQuery(query);
    setProductSearchSelectedId("");
    setProductSearchOpen(true);
  }

  function clearProductSearch() {
    setProductSearchOpen(false);
    setProductSearchQuery("");
    setProductSearchSelectedId("");
    setQuery("");
  }

  function closeProductSearch() {
    clearProductSearch();
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function selectProductFromSearch(product: SaleProduct) {
    clearProductSearch();
    requestAddProduct(product);
  }

  function openProductSalesHistory() {
    if (paymentLocked) return;
    setSalesHistoryProduct(selectedLine?.product ?? lines.at(-1)?.product ?? null);
    setSalesHistoryOpen(true);
  }

  function openProductInformation(product: SaleProduct) {
    setProductSearchSelectedId(product.id);
    setProductSearchOpen(false);
    setProductInformationProduct(product);
  }

  function closeProductInformation() {
    setProductInformationProduct(null);
    setProductSearchOpen(true);
  }

  function addProductFromInformation(product: SaleProduct) {
    setProductInformationProduct(null);
    selectProductFromSearch(product);
  }

  function clearQuickEntry(message = "") {
    setQuery("");
    setShortcutStatus(message);
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function quickOperand() {
    return saleQuickOperand(query);
  }

  function quantityOperand() {
    const quantity = parseProductQuantityInput(query);
    return Number.isFinite(quantity) ? quantity : null;
  }

  function applyPauseQuantity() {
    if (!selectedLine || paymentLocked) return;
    const quantity = salePauseQuantity(query);
    if (quantity == null) {
      setShortcutStatus("Introduce una cantidad y pulsa Pausa");
      return;
    }
    if (previousTicketImportBatch && quantity < 0) {
      setShortcutStatus(t("sale.importPreviousTicket.positiveLinesOnly"));
      clearQuickEntry(t("sale.importPreviousTicket.positiveLinesOnly"));
      return;
    }
    if (quantity !== -1
        && !isProductQuantityPrecisionValid(quantity, selectedLine.product.productType)) {
      setShortcutStatus(t("sale.quantity.invalid"));
      return;
    }
    if (quantity === 0) {
      if (selectedLine.previousTicketImportOrigin) {
        const lockedText = previousTicketImportLineLockedText(selectedLine);
        setShortcutStatus(lockedText);
        clearQuickEntry(lockedText);
        return;
      }
      confirmRemoveLine();
      clearQuickEntry();
      return;
    }
    if (!salePauseQuantityAllowed(selectedLine, quantity)) {
      setShortcutStatus("Las devoluciones de F10 solo pueden eliminarse con 0 + Pausa");
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, saleCartLineIdentity(selectedLine), quantity));
    clearQuickEntry(quantity === -1
      ? "Devolución manual -1 aplicada; se registrará como alerta de control"
      : "");
  }

  function addToSelectedQuantity() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin || paymentLocked) return;
    const operand = quantityOperand();
    if (operand == null
        || operand < productQuantityStep(selectedLine.product.productType)
        || !isProductQuantityPrecisionValid(operand, selectedLine.product.productType)) {
      setShortcutStatus("Introduce la cantidad que quieres sumar");
      return;
    }
    const result = normalizeProductQuantity(selectedLine.quantity + operand);
    if (result > 9999) {
      setShortcutStatus("La cantidad no puede superar 9999");
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, saleCartLineIdentity(selectedLine), result));
    clearQuickEntry();
  }

  function subtractFromSelectedQuantity() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin || paymentLocked) return;
    const operand = quantityOperand();
    if (operand == null
        || operand < productQuantityStep(selectedLine.product.productType)
        || !isProductQuantityPrecisionValid(operand, selectedLine.product.productType)) {
      setShortcutStatus("Introduce la cantidad que quieres restar");
      return;
    }
    const result = normalizeProductQuantity(selectedLine.quantity - operand);
    if (result < 0) {
      setShortcutStatus("Ctrl+- no permite dejar una cantidad negativa");
      return;
    }
    if (result === 0) {
      confirmRemoveLine();
      clearQuickEntry();
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, saleCartLineIdentity(selectedLine), result));
    clearQuickEntry();
  }

  function prepareNextProductQuantity(asPackage: boolean) {
    if (paymentLocked) return;
    const operand = asPackage ? quickOperand() : quantityOperand();
    if (operand == null || operand <= 0) {
      setShortcutStatus(asPackage
        ? "Introduce el número de paquetes antes de *"
        : "Introduce la cantidad antes de +");
      return;
    }
    if (asPackage) {
      setNextScanQuantity(operand);
      setNextScanMode("PACKAGE");
      clearQuickEntry(`${operand} paquete(s) para el próximo producto`);
      return;
    }
    setNextScanQuantity(operand);
    setNextScanMode("UNIT");
    clearQuickEntry(`${operand} unidad(es) para el próximo producto`);
  }

  function applyQuickLineDiscount() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin || paymentLocked || !canApplyManualDiscount
      || saleProductBlocksManualDiscount(selectedLine.product)) return;
    const discount = quickOperand();
    if (discount == null || discount < 0 || discount > 100) {
      setShortcutStatus("Introduce un descuento entre 0 y 100");
      return;
    }
    setDiscountInput(String(discount));
    clearQuickEntry();
    setLines((current) => updateSaleLineDiscount(current, saleCartLineIdentity(selectedLine), discount));
  }

  function applyQuickGlobalDiscount() {
    if (lines.length === 0 || paymentLocked || !canApplyManualDiscount) return;
    const discount = quickOperand();
    if (discount == null || discount < 0 || discount > 100) {
      setShortcutStatus("Introduce un descuento entre 0 y 100");
      return;
    }
    try {
      setLines((current) => current.reduce(
        (updated, line) => line.returnOrigin || line.previousTicketImportOrigin
          ? updated
          : updateSaleLineDiscount(updated, saleCartLineIdentity(line), discount),
        current,
      ));
      clearQuickEntry("Descuento aplicado a toda la compra");
    } catch (error) {
      setShortcutStatus(error instanceof Error && error.message === "discount_blocked"
        ? t("sale.discountBlocked")
        : "No se pudo aplicar el descuento global");
    }
  }

  function applyDesiredLinePrice() {
    if (!selectedLine || selectedLine.returnOrigin || selectedLine.previousTicketImportOrigin || paymentLocked || !canApplyManualDiscount
      || saleProductBlocksManualDiscount(selectedLine.product)) return;
    const desiredPrice = quickOperand();
    const currentPrice = saleLineUnitPrice(selectedLine, activeMember);
    if (desiredPrice == null || desiredPrice < 0 || desiredPrice > currentPrice || currentPrice <= 0) {
      setShortcutStatus("Introduce un precio final válido para la línea");
      return;
    }
    const discount = Math.round((1 - desiredPrice / currentPrice) * 10_000) / 100;
    setDiscountInput(String(discount));
    clearQuickEntry();
    setLines((current) => updateSaleLineDiscount(current, saleCartLineIdentity(selectedLine), discount));
  }

  function cashSaleRequest(includeQuoteFingerprint = false) {
    const serialNumbersBySourceLineId = Object.fromEntries(
      lines.flatMap((line) => {
        const origin = line.previousTicketImportOrigin;
        if (!origin
            || origin.sourceStatus !== "CONFIRMADO"
            || !origin.requiresNewSerialNumbers) return [];
        return [[origin.sourceLineId, [...(line.serialNumbers ?? [])]]];
      }),
    );
    return {
      customerId: selectedCustomer?.id ?? null,
      lines: lines.filter((line) => !line.previousTicketImportOrigin).map((line) => ({
        productId: line.product.id,
        cartLineId: saleCartLineIdentity(line),
        quantity: line.quantity,
        discount: line.discountPercent,
        ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {}),
        ...(line.openUnitPrice != null ? { openUnitPrice: line.openUnitPrice } : {}),
        ...(line.temporaryPriceAuthorization
          ? { temporaryPriceAuthorizationToken: line.temporaryPriceAuthorization.token }
          : {}),
        ...(line.temporaryName ? { temporaryName: line.temporaryName } : {}),
        ...(line.returnOrigin ? { returnOrigin: {
          sourceType: line.returnOrigin.sourceType,
          sourceCode: line.returnOrigin.sourceCode,
          sourceTicketId: line.returnOrigin.sourceTicketId,
          sourceLineId: line.returnOrigin.sourceLineId,
          giftReceiptLineId: line.returnOrigin.giftReceiptLineId ?? null,
        } } : {}),
      })),
      ...(previousTicketImportBatch ? { previousTicketImport: {
        ticketId: previousTicketImportBatch.ticketId,
        fingerprint: previousTicketImportBatch.fingerprint,
        serialNumbersBySourceLineId,
      } } : {}),
      ...(includeQuoteFingerprint
          && previousTicketImportBatch
          && authoritativeQuote?.quoteFingerprint?.trim()
        ? { quoteFingerprint: authoritativeQuote.quoteFingerprint }
        : {}),
      ...(checkoutDiscountCents > 0 && lines.some((line) => !line.previousTicketImportOrigin)
        ? { checkoutDiscountAmount: checkoutDiscountCents / 100 }
        : {}),
      ...(saleComment ? { internalComment: saleComment } : {}),
    };
  }

  function clearCurrentSale() {
    setLines([]);
    setPreviousTicketImportBatch(null);
    setSelectedLineId(null);
    setSelectedCustomer(null);
    setQuery("");
    setCheckoutDiscountCents(0);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setSaleComment("");
    setCommentInput("");
    setSalePrintMode("DEFAULT");
    setPrintModeInput("DEFAULT");
    setShortcutStatus("");
    deletionControl.reset("CART_EMPTIED");
  }

  function recordSaleLinesDeletion(
    removedLines: SaleLine[],
    fullTicketClear: boolean,
  ) {
    if (removedLines.length === 0) return;
    const saleOperationId = deletionControl.currentSaleOperationId();
    const deletionOperationId = deletionControl.newDeletionOperationId();
    void deletionControl.enqueue(
      () => apiRequest("/sale-line-deletions", {
        token: session.accessToken,
        body: {
          saleOperationId,
          deletionOperationId,
          fullTicketClear,
          lines: removedLines.map((line) => ({
            productId: line.product.id,
            code: line.product.code ?? "",
            name: line.product.name ?? "",
            quantity: line.quantity,
            unitPrice: saleLineUnitPrice(line, activeMember),
          })),
        },
      }),
      (error: unknown) => {
        // Best effort: a control-event outage must never block the active sale.
        console.warn("sale_line_deletion_not_recorded", error);
      },
    );
    if (fullTicketClear) deletionControl.reset("CART_EMPTIED");
  }

  function clearSaleLines() {
    const removedLines = lines;
    setLines([]);
    setPreviousTicketImportBatch(null);
    setSelectedLineId(null);
    setQuery("");
    setCheckoutDiscountCents(0);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setActionDialog(null);
    setShortcutStatus("");
    recordSaleLinesDeletion(removedLines, true);
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function clearSaleFromCommand() {
    const removedLines = lines;
    recordSaleLinesDeletion(removedLines, true);
    clearCurrentSale();
    setActionDialog(null);
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function clearManualDiscounts() {
    setLines((current) => current.map((line) => (
      line.returnOrigin || line.previousTicketImportOrigin || line.discountPercent === 0
        ? line
        : { ...line, discountPercent: 0 }
    )));
    setCheckoutDiscountCents(0);
    setShortcutStatus(t("sale.clearDiscounts.done"));
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function openSaleComment() {
    if (paymentLocked) return;
    setCommentInput(saleComment);
    setActionError("");
    setActionDialog("comment");
  }

  function saveSaleComment() {
    const normalized = commentInput.trim();
    if (normalized.length > 500) {
      setActionError(t("sale.comment.tooLong"));
      return;
    }
    setSaleComment(normalized);
    setActionDialog(null);
    setShortcutStatus(t("sale.comment.saved"));
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  async function parkCurrentSale(
    operationAuthorizations: SaleMutationOperationAuthorizations = {},
  ) {
    if (lines.length === 0 || paymentLocked || parkedSaleSaving
        || saleMutationSecurityUnavailable || previousTicketImportBatch) return;
    setParkedSaleSaving(true);
    setParkedSaleError("");
    try {
      await apiRequest("/parked-sales/from-pos", {
        token: session.accessToken,
        method: "POST",
        body: {
          sale: saleWithOperationAuthorizations(
            cashSaleRequest(),
            operationAuthorizations,
          ),
          comment: saleComment.trim() || null,
          printMode: salePrintMode,
        },
      });
      setParkedSaleAuthorizationOpen(false);
      clearCurrentSale();
      setShortcutStatus(t("parkedSales.saved"));
      queueMicrotask(() => searchInputRef.current?.focus());
    } catch (reason) {
      const message = reason instanceof Error
        ? reason.message : t("parkedSales.error.park");
      setParkedSaleError(message);
      if (!parkedSaleAuthorizationOpen) setShortcutStatus(message);
    } finally {
      setParkedSaleSaving(false);
    }
  }

  function runParkSaleCommand() {
    if (lines.length === 0) {
      setParkedSalesOpen(true);
      return;
    }
    if (!saleMutationAuthorizations) {
      reportOperationSecurityUnavailable();
      return;
    }
    const requiredCredentials = saleMutationCredentialsRequired(saleMutationAuthorizations);
    if (requiredCredentials.length > 0) {
      setParkedSaleError("");
      setParkedSaleAuthorizationOpen(true);
      return;
    }
    void parkCurrentSale();
  }

  function savePrintMethod(mode: SalePrintMode) {
    setSalePrintMode(mode);
    setActionDialog(null);
    setShortcutStatus(t("sale.printMethod.saved"));
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  async function openCashDrawer(authorizerUsername?: string, authorizerPassword?: string) {
    if (!terminalContext.terminalId) {
      const message = t("sale.cashDrawer.terminalRequired");
      if (cashDrawerAuthorizationOpen) setCashDrawerError(message);
      else setShortcutStatus(message);
      return;
    }
    setCashDrawerBusy(true);
    setCashDrawerError("");
    setShortcutStatus("");
    try {
      await executeAuthorizedCashDrawerOpen({
        terminalId: terminalContext.terminalId,
        token: session.accessToken,
        authorizerUsername,
        authorizerPassword,
      });
      setCashDrawerAuthorizationOpen(false);
      setShortcutStatus(t("sale.cashDrawer.opened"));
    } catch (error) {
      const message = error instanceof CashDrawerResultReportingError
        ? t("sale.cashDrawer.openedUnreported")
        : error instanceof Error ? error.message : t("sale.cashDrawer.error");
      if (cashDrawerAuthorizationOpen) setCashDrawerError(message);
      else setShortcutStatus(message);
    } finally {
      setCashDrawerBusy(false);
    }
  }

  function startCashDrawerOpening() {
    if (cashDrawerBusy) return;
    if (!cashDrawerAuthorization) {
      reportOperationSecurityUnavailable();
      return;
    }
    if (cashDrawerAuthorization.mode === "DIRECT") {
      void openCashDrawer();
      return;
    }
    setCashDrawerError("");
    setCashDrawerAuthorizationOpen(true);
  }

  async function openProductEditor(authorizerUsername?: string, authorizerPassword?: string) {
    if (!selectedLine || paymentLocked) {
      setShortcutStatus(t("sale.productEdit.selectLine"));
      return;
    }
    setProductEditBusy(true);
    setProductEditError("");
    setShortcutStatus("");
    try {
      const authorization = await authorizeProductEdit(
        selectedLine.product.id,
        session.accessToken,
        authorizerUsername,
        authorizerPassword,
      );
      setProductEditAuthorizationOpen(false);
      setProductEditAuthorizationId(authorization.operationId);
      setEditingProduct(productEditDialogValue(authorization.product));
    } catch (error) {
      const message = error instanceof Error ? error.message : t("sale.productEdit.error");
      if (productEditAuthorizationOpen) setProductEditError(message);
      else setShortcutStatus(message);
    } finally {
      setProductEditBusy(false);
    }
  }

  function startProductEditing() {
    if (productEditBusy) return;
    if (!selectedLine || paymentLocked) {
      setShortcutStatus(t("sale.productEdit.selectLine"));
      return;
    }
    if (!productEditAuthorization) {
      reportOperationSecurityUnavailable();
      return;
    }
    if (productEditAuthorization.mode === "DIRECT") {
      void openProductEditor();
      return;
    }
    setProductEditError("");
    setProductEditAuthorizationOpen(true);
  }

  function closeProductEditor() {
    const operationId = productEditAuthorizationId;
    setEditingProduct(null);
    setProductEditAuthorizationId("");
    if (operationId) {
      void revokeProductEditAuthorization(operationId, session.accessToken);
    }
    searchInputRef.current?.focus();
  }

  async function recoverParkedSale(opened: OpenedParkedSale) {
    const recoveredLines = opened.document.lineas.flatMap((line) => {
      const product = products.find((candidate) => candidate.id === line.productoId);
      if (!product) return [];
      return [{
        cartLineId: createSaleCartLineId(),
        product,
        quantity: Number(line.cantidad),
        discountPercent: Number(line.descuento),
        serialNumbers: line.serialNumbers ?? [],
        ...(line.originalDocumentLineId && line.returnSourceType
          && line.returnSourceCode && line.returnSourceTicketId
          ? {
              returnUnitPrice: Number(line.precioUnitario),
              returnOrigin: {
                sourceType: line.returnSourceType,
                sourceCode: line.returnSourceCode,
                sourceTicketId: line.returnSourceTicketId,
                sourceTicketNumber: line.returnSourceCode,
                sourceLineId: line.originalDocumentLineId,
                giftReceiptLineId: line.giftReceiptLineId,
              },
            }
          : {}),
        ...(line.temporaryNameOverride === true && line.nombre?.trim()
          ? { temporaryName: line.nombre.trim() }
          : {}),
        ...(!line.originalDocumentLineId
          && (line.temporaryPriceOverride === true || Number(product.salePrice) === 0)
          && Number(line.precioUnitario) > 0
          ? { openUnitPrice: Number(line.precioUnitario) }
          : {})
      } satisfies SaleLine];
    });
    setLines(recoveredLines);
    setPreviousTicketImportBatch(null);
    setSelectedLineId(recoveredLines[0] ? saleCartLineIdentity(recoveredLines[0]) : null);
    setCheckoutDiscountCents(0);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setSaleComment(opened.document.comentarioInterno?.trim() ?? "");
    setCommentInput(opened.document.comentarioInterno?.trim() ?? "");
    setSalePrintMode(opened.printMode ?? "DEFAULT");
    setPrintModeInput(opened.printMode ?? "DEFAULT");
    setShortcutStatus("");
    setParkedSalesOpen(false);
    const customerId = opened.document.clienteId;
    if (customerId) {
      try {
        const options = await apiRequest<SaleCustomer[]>("/customers/sale-options", { token: session.accessToken });
        const customer = options.find((candidate) => candidate.id === customerId) ?? null;
        setSelectedCustomer(customer);
        setLines((current) => applyMemberDiscounts(current, customer));
      } catch {
        setSelectedCustomer(null);
      }
    } else {
      setSelectedCustomer(null);
    }
  }

  useEffect(() => {
    const generation = ++quoteGenerationRef.current;
    if (lines.length === 0) {
      setAuthoritativeQuote(null);
      setAuthoritativeQuoteRequestKey("");
      setAuthoritativeQuoteLoading(false);
      setAuthoritativeQuoteError("");
      setCheckoutDiscountCents(0);
      return;
    }
    if (!previousTicketImportSerialsReady) {
      setAuthoritativeQuote(null);
      setAuthoritativeQuoteRequestKey("");
      setAuthoritativeQuoteLoading(false);
      setAuthoritativeQuoteError(t("sale.serialNumber.complete"));
      return;
    }
    setAuthoritativeQuoteLoading(true);
    setAuthoritativeQuoteError("");
    const timer = window.setTimeout(() => {
      apiRequest<PosAuthoritativeQuote>("/pos/sales/quote", {
        token: session.accessToken,
        body: currentSaleRequest
      }).then((quote) => {
        if (generation !== quoteGenerationRef.current) return;
        if (!isCompleteAuthoritativeQuote(quote)) {
          throw new Error(t("sale.quote.invalidResponse"));
        }
        setAuthoritativeQuote(quote);
        setAuthoritativeQuoteRequestKey(currentSaleRequestKey);
      }).catch((error) => {
        if (generation === quoteGenerationRef.current) {
          setAuthoritativeQuote(null);
          setAuthoritativeQuoteRequestKey("");
          setAuthoritativeQuoteError(error instanceof Error ? error.message : t("sale.quote.error"));
        }
      }).finally(() => {
        if (generation === quoteGenerationRef.current) setAuthoritativeQuoteLoading(false);
      });
    }, 180);
    return () => window.clearTimeout(timer);
  }, [currentSaleRequestKey, previousTicketImportSerialsReady, session.accessToken]);

  async function openCashDialog() {
    await runGuardedCashOpening(cashOpeningRef.current, async (opening) => {
      if (paymentActionsDisabled || paymentLocked || !authoritativeQuoteReady || authoritativeTotal <= 0) return;
      setCashOpening(true);
      setCashError("");
      setCashStatus("");
      setCashInputMode(readCashModeForOpening());
      try {
        const quote = await apiRequest<{ total: number | string }>("/pos/cash/quote", {
          token: session.accessToken,
          body: cashSaleRequest()
        });
        if (!opening.isCurrent()) return;
        setCashQuoteCents(Math.round(Number(quote.total) * 100));
        setCashCheckoutId(globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`);
        setCashDialogOpen(true);
      } catch (error) {
        if (opening.isCurrent()) setCashStatus(error instanceof Error ? error.message : t("sale.quote.error"));
      } finally {
        if (opening.isCurrent()) setCashOpening(false);
      }
    });
  }

  async function confirmCashPayment(receivedCents: number) {
    await runGuardedCashSubmission(cashSubmissionRef, async () => {
      setCashSubmitting(true);
      setCashError("");
      try {
      const result = await apiRequest<CashPaymentResponse>("/pos/cash", {
        token: session.accessToken,
        body: {
          checkoutId: cashCheckoutId,
          sale: cashSaleRequest(true),
          received: (receivedCents / 100).toFixed(2),
          quotedTotal: (cashQuoteCents / 100).toFixed(2)
        }
      });
      const confirmedResult = cashPaymentResultForAutomaticPrinting(result, cashQuoteCents, receivedCents);
      const transition = cashPaymentSuccessTransition(confirmedResult);
      const completedPrintMode = salePrintMode;
      setLastPrintMode(completedPrintMode);
      setCashDialogOpen(transition.cashDialogOpen);
      setLines(transition.lines);
      setSelectedLineId(transition.selectedLineId);
      setSelectedCustomer(transition.selectedCustomer);
      setCashResult(transition.cashResult);
      setQuery(transition.query);
      setNextScanQuantity(1);
      setNextScanMode("UNIT");
      setSaleComment("");
      setCommentInput("");
      setSalePrintMode("DEFAULT");
      setPrintModeInput("DEFAULT");
      deletionControl.reset("SALE_FINALIZED");
      setVerifactuRefreshSignal((current) => current + 1);
      startAutomaticTicketPrint(result.printTicket, completedPrintMode);
      } catch (error) {
      const transition = cashPaymentErrorTransition(
        { cashDialogOpen, lines, selectedLineId, selectedCustomer, query },
        error instanceof Error ? error.message : "No se pudo registrar el cobro"
      );
      setCashError(transition.cashError);
      } finally {
        setCashSubmitting(false);
      }
    });
  }

  const newCheckoutId = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;

  async function openCardDialog() {
    if (paymentActionsDisabled || paymentLocked || !authoritativeQuoteReady || authoritativeTotal <= 0) return;
    await runGuardedCardOpening(cardOpeningRef.current, async (opening) => {
      setCardOpening(true); setCashStatus("");
      try {
        const quote = await apiRequest<{ total: number | string }>("/pos/card/quote", { token: session.accessToken, body: cashSaleRequest() });
        if (!opening.isCurrent()) return;
        const cents = Math.round(Number(quote.total) * 100);
        const checkoutId = newCheckoutId();
        setCardQuoteCents(cents); setCardCheckoutId(checkoutId); setCardStatus("PENDING"); setCardMessage("Esperando respuesta del datafono..."); setCardDialogOpen(true);
        await submitCardPayment(checkoutId, cents);
      } catch (error) {
        if (opening.isCurrent()) setCashStatus(error instanceof Error ? error.message : t("sale.quote.error"));
      } finally { setCardOpening(false); }
    });
  }

  async function submitCardPayment(checkoutId: string, quotedCents: number) {
    await runGuardedCashSubmission(cardSubmissionRef, async () => {
      setCardSubmitting(true); setCardStatus("PENDING"); setCardMessage("Esperando respuesta del datafono...");
      try {
        const response = await apiRequest<CardPaymentResponse>("/pos/card/charge", { token: session.accessToken, body: buildCardChargeBody(checkoutId, cashSaleRequest(true), quotedCents) });
        const outcome = resolveCardPaymentOutcome(response, quotedCents);
        setCardStatus(outcome.status); setCardMessage(outcome.message);
        if (outcome.clearSale && outcome.result) {
          setCardDialogOpen(false); setLines([]); setPreviousTicketImportBatch(null); setSelectedLineId(null); setSelectedCustomer(null); setQuery(""); setCashResult(outcome.result);
          setNextScanQuantity(1);
          setNextScanMode("UNIT");
          setSaleComment("");
          setCommentInput("");
          setSalePrintMode("DEFAULT");
          setPrintModeInput("DEFAULT");
          deletionControl.reset("SALE_FINALIZED");
          setVerifactuRefreshSignal((current) => current + 1);
        }
      } catch (error) {
        if (error instanceof ApiError) {
          setCardStatus("ERROR");
          setCardMessage(error.message);
        } else {
          const outcome = cardTransportFailureOutcome(checkoutId, error instanceof Error ? error.message : "No se pudo comunicar con el datafono");
          setCardStatus(outcome.status); setCardMessage(outcome.message);
        }
      }
      finally { setCardSubmitting(false); }
    });
  }

  function retryCardPayment() {
    const next = cardRetryCheckoutId(cardStatus, newCheckoutId);
    if (!next) return;
    setCardCheckoutId(next);
    void submitCardPayment(next, cardQuoteCents);
  }

  function consultCardPayment() {
    setCardSubmitting(true);
    void queryPaymentOperation(cardCheckoutId, session.accessToken)
      .then((operation) => {
        const outcome = resolveCardPaymentOutcome({
          status: operation.status,
          total: operation.amount,
          reference: operation.reference,
          authorization: operation.authorization
        }, cardQuoteCents);
        setCardStatus(outcome.status);
        setCardMessage(outcome.message);
        if (outcome.clearSale) setVerifactuRefreshSignal((current) => current + 1);
      })
      .catch((error) => {
        setCardMessage(error instanceof Error ? error.message : "No se pudo consultar la operacion");
      })
      .finally(() => setCardSubmitting(false));
  }

  const cashSessionCloseDisabled = !cashSessionReady
    || lines.length > 0
    || paymentLocked
    || !paymentHydrated;

  function openCashSessionClose() {
    if (cashSessionCloseDisabled) {
      setShortcutStatus(lines.length > 0
        ? "El carrito debe estar vacío para cerrar la caja"
        : "Finaliza el cobro activo antes de cerrar la caja");
      return;
    }
    setCashSessionCloseFlow((current) => current ?? createCashCloseUiFlow());
    setCashSessionCloseOpen(true);
  }

  function updateCashSessionCloseFlow(flow: CashCloseUiFlow) {
    setCashSessionCloseFlow(flow);
    if (flow.phase === "READY") return;
    saveCashCloseRecovery(localStorage, terminalContext.terminalCode, flow);
    setCashCloseRecovery(loadCashCloseRecovery(
      localStorage,
      terminalContext.terminalCode,
    ));
  }

  function clearPersistedCashSessionClose() {
    clearCashCloseRecovery(localStorage, terminalContext.terminalCode);
    setCashCloseRecovery({ status: "empty" });
    setCashSessionCloseFlow(null);
  }

  function saleCommandDisabled(command: SaleCommandId) {
    if (previousTicketImportBusy && command !== "import-previous-ticket") return true;
    switch (command) {
      case "product-search":
        return catalogLoading || catalogError || paymentLocked;
      case "sales-document":
        return paymentLocked || !onOpenSalesDocumentWindow;
      case "quantity":
      case "add-quantity":
      case "subtract-quantity":
      case "desired-price":
      case "line-discount":
        return !selectedLine
          || Boolean(selectedLine.returnOrigin)
          || Boolean(selectedLine.previousTicketImportOrigin)
          || paymentLocked;
      case "stock":
        return !selectedLine || paymentLocked;
      case "serial-number":
        return !selectedLine || Boolean(selectedLine.returnOrigin) || paymentLocked
          || Boolean(selectedLine.previousTicketImportOrigin
            && !selectedLine.previousTicketImportOrigin.requiresNewSerialNumbers);
      case "edit-product":
        return !selectedLine || paymentLocked || productEditBusy;
      case "ean-generator":
        return paymentLocked || salesUtilityOpening || !internalEanAuthorization;
      case "print-product-label":
        return paymentLocked || salesUtilityOpening;
      case "ticket-return":
        return !paymentHydrated || paymentLocked || Boolean(previousTicketImportBatch);
      case "gift-receipt":
      case "cancel-last-ticket":
      case "cancel-ticket":
      case "convert-ticket":
        return !paymentHydrated || paymentLocked;
      case "import-previous-ticket":
        return catalogLoading || catalogError || paymentLocked || previousTicketImportBusy
          || Boolean(previousTicketImportBatch)
          || lines.some((line) => Boolean(line.returnOrigin) || line.quantity <= 0);
      case "checkout":
        return paymentActionsDisabled || paymentLocked;
      case "customer":
        return paymentLocked || Boolean(previousTicketImportBatch);
      case "customer-receivables":
        return paymentLocked || !canOpenCustomerReceivables;
      case "park-sale":
        return paymentLocked || parkedSaleSaving || Boolean(previousTicketImportBatch)
          || (lines.length > 0 && saleMutationSecurityUnavailable);
      case "sale-comment":
      case "print-method":
      case "next-units":
      case "next-package":
        return paymentLocked;
      case "clear-sale":
        return paymentLocked || (
          lines.length === 0
          && !selectedCustomer
          && !saleComment
          && checkoutDiscountCents === 0
          && salePrintMode === "DEFAULT"
        );
      case "clear-lines":
        return paymentLocked || lines.length === 0;
      case "clear-discounts":
        return paymentLocked || (
          checkoutDiscountCents === 0
          && lines.every((line) => line.discountPercent === 0)
        );
      case "temporary-name":
        return paymentLocked || !selectedLine || Boolean(selectedLine.returnOrigin)
          || Boolean(selectedLine.previousTicketImportOrigin);
      case "temporary-price":
        return paymentLocked || !selectedLine || Boolean(selectedLine.returnOrigin)
          || Boolean(selectedLine.previousTicketImportOrigin)
          || saleProductRequiresOpenPrice(selectedLine.product);
      case "sale-discount":
        return currentEconomicLines.length === 0 || paymentLocked || !canApplyManualDiscount;
      case "close-cash":
        return cashSessionCloseDisabled;
      case "cash-withdrawal":
        return !cashSessionReady || paymentLocked;
      case "cash-drawer":
        return cashDrawerBusy;
      default:
        return false;
    }
  }

  function executeSaleCommand(
    command: SaleCommandId,
    source: "KEYBOARD" | "UI" = "UI",
  ) {
    if (previousTicketImportBusyRef.current && command !== "import-previous-ticket") return false;
    const keyboardReturnRemoval = command === "quantity"
      && source === "KEYBOARD"
      && saleKeyboardReturnRemovalAllowed(selectedLine, query, paymentLocked);
    if (saleCommandDisabled(command) && !keyboardReturnRemoval) return false;
    switch (command) {
      case "sales-document":
        onOpenSalesDocumentWindow?.();
        break;
      case "price-lookup":
        setConsultationMode("PRICE");
        break;
      case "product-search":
        openProductSearch();
        break;
      case "calculator":
        setCalculatorOpen(true);
        break;
      case "ean-generator":
        if (internalEanAuthorization) void openSalesUtilityWindow("INTERNAL_EAN");
        else reportOperationSecurityUnavailable();
        break;
      case "print-product-label":
        void openSalesUtilityWindow("PRODUCT_LABEL");
        break;
      case "cash-drawer":
        startCashDrawerOpening();
        break;
      case "logout":
        void handleSaleLogout();
        break;
      case "stock":
        setConsultationMode("STOCK");
        break;
      case "sales-history":
        openProductSalesHistory();
        break;
      case "edit-product":
        startProductEditing();
        break;
      case "close-cash":
        if (cashSessionCloseAuthorization) openCashSessionClose();
        else reportOperationSecurityUnavailable();
        break;
      case "cash-withdrawal":
        if (cashMovementAuthorization) setCashWithdrawalOpen(true);
        else reportOperationSecurityUnavailable();
        break;
      case "ticket-return":
        setTicketReturnOpen(true);
        break;
      case "gift-receipt":
        setGiftReceiptOpen(true);
        break;
      case "cancel-last-ticket":
        if (ticketCancellationAuthorization) setTicketCancellationMode("LAST");
        else void recoverOperationSecurityAndOpen(
          "CANCEL_TICKET",
          () => setTicketCancellationMode("LAST"),
        );
        break;
      case "cancel-ticket":
        // Ctrl+F11 resolves the document type after the number is entered.
        // Ticket authorization remains enforced by its backend operation,
        // while invoice returns use their own RETURN_SALES_INVOICE policy.
        setTicketCancellationMode("BY_NUMBER");
        break;
      case "convert-ticket":
        if (ticketInvoiceAuthorization) setTicketInvoiceOpen(true);
        else void recoverOperationSecurityAndOpen(
          "CONVERT_TICKET_TO_INVOICE",
          () => setTicketInvoiceOpen(true),
        );
        break;
      case "import-previous-ticket":
        void importPreviousTicket();
        break;
      case "checkout":
        if (paymentCheckoutRef.current?.openCheckout) {
          paymentCheckoutRef.current.openCheckout("CASH");
        } else {
          paymentCheckoutRef.current?.triggerCash();
        }
        break;
      case "customer":
        openCustomerDialog();
        break;
      case "customer-receivables":
        onOpenCustomerReceivables?.(selectedCustomer?.id);
        break;
      case "park-sale":
        runParkSaleCommand();
        break;
      case "sale-comment":
        openSaleComment();
        break;
      case "clear-sale":
        setActionDialog("clearSale");
        break;
      case "clear-lines":
        setActionDialog("clearLines");
        break;
      case "clear-discounts":
        clearManualDiscounts();
        break;
      case "print-method":
        setPrintModeInput(salePrintMode);
        setActionDialog("printMethod");
        break;
      case "sale-discount":
        applyQuickGlobalDiscount();
        break;
      case "quantity":
        if (source === "KEYBOARD") applyPauseQuantity();
        else openQuantityDialog();
        break;
      case "add-quantity":
        addToSelectedQuantity();
        break;
      case "subtract-quantity":
        subtractFromSelectedQuantity();
        break;
      case "next-units":
        prepareNextProductQuantity(false);
        break;
      case "next-package":
        prepareNextProductQuantity(true);
        break;
      case "desired-price":
        applyDesiredLinePrice();
        break;
      case "temporary-name":
        openTemporaryNameDialog();
        break;
      case "temporary-price":
        openTemporaryPriceDialog();
        break;
      case "serial-number":
        if (selectedLine
            && !selectedLine.returnOrigin
            && (!selectedLine.previousTicketImportOrigin
              || selectedLine.previousTicketImportOrigin.requiresNewSerialNumbers)) {
          setSerialNumberOpen(true);
        }
        break;
      case "line-discount":
        if (source === "KEYBOARD") applyQuickLineDiscount();
        else openDiscountDialog();
        break;
    }
    return true;
  }

  saleShortcutHandlerRef.current = (event: KeyboardEvent) => {
      if (!cashSessionReady) return;
      if (event.repeat || document.querySelector('[role="dialog"][aria-modal="true"]')) return;
      if (pendingRecoveryBlocked) return;
      if (previousTicketImportBusyRef.current) return;
      const command = saleCommandFromKeyboard(event);
      if (command) {
        if (!event.ctrlKey
          && saleShortcutTargetIsEditable(event.target)
          && event.target !== searchInputRef.current) {
          return;
        }
        event.preventDefault();
        executeSaleCommand(command, "KEYBOARD");
        return;
      }

      if (event.ctrlKey && !event.altKey && !event.metaKey) {
        // Ctrl+C/V/X/Z/Y and every other standard editing shortcut remain native.
        return;
      }

      if (saleShortcutTargetIsEditable(event.target) && event.target !== searchInputRef.current) {
        return;
      }
      if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        if (paymentLocked || lines.length === 0) return;
        setSelectedLineId(saleLineSelectionAfterArrow(lines, selectedLineId, event.key));
        event.preventDefault();
        return;
      }

      if (!event.altKey && !event.metaKey && !event.ctrlKey && event.key.length === 1
        && event.target !== searchInputRef.current && !saleShortcutTargetIsEditable(event.target)) {
        event.preventDefault();
        setQuery((current) => current + event.key);
        setShortcutStatus("");
        searchInputRef.current?.focus();
      }
  };

  useEffect(() => {
    function handleSaleShortcut(event: KeyboardEvent) {
      saleShortcutHandlerRef.current(event);
    }
    window.addEventListener("keydown", handleSaleShortcut);
    return () => window.removeEventListener("keydown", handleSaleShortcut);
  }, []);

  const cartColumnVisible = (columnKey: SaleCartColumnKey) => (
    cartTableLayout.layout.find((column) => column.key === columnKey)?.visible !== false
  );
  const saleCommandMenus: readonly SaleCommandMenu[] = [
    {
      id: "system",
      label: t("sale.menu.system"),
      entries: [
        {
          type: "action", id: "calculator", label: commandLabels.calculator, shortcut: "F2",
          onSelect: () => executeSaleCommand("calculator"),
        },
        {
          type: "action", id: "ean-generator", label: commandLabels.eanGenerator, shortcut: "Ctrl+F2",
          disabled: saleCommandDisabled("ean-generator"),
          onSelect: () => executeSaleCommand("ean-generator"),
        },
        {
          type: "action", id: "print-product-label", label: commandLabels.printProductLabel, shortcut: "Ctrl+I",
          disabled: saleCommandDisabled("print-product-label"),
          onSelect: () => executeSaleCommand("print-product-label"),
        },
        ...(canOpenCustomerReceivables ? [{
          type: "action" as const,
          id: "customer-receivables",
          label: commandLabels.receivables,
          shortcut: "Ctrl+D",
          disabled: saleCommandDisabled("customer-receivables"),
          onSelect: () => executeSaleCommand("customer-receivables"),
        }] : []),
        { type: "separator", id: "system-separator-1" },
        {
          type: "action", id: "cash-drawer", label: commandLabels.cashDrawer, shortcut: "F3",
          disabled: cashDrawerBusy,
          disabledReason: t("sale.cashDrawer.error"),
          onSelect: () => executeSaleCommand("cash-drawer"),
        },
        {
          type: "action", id: "logout", label: commandLabels.logout, shortcut: "F4",
          onSelect: () => executeSaleCommand("logout"),
        },
        { type: "separator", id: "system-separator-2" },
        {
          type: "action", id: "close-cash", label: cashSessionCopy.close, shortcut: "F8",
          disabled: cashSessionCloseDisabled,
          disabledReason: lines.length > 0
            ? "El carrito debe estar vacío para cerrar la caja"
            : "Finaliza el cobro activo antes de cerrar la caja",
          onSelect: () => executeSaleCommand("close-cash"),
        },
        {
          type: "action", id: "cash-withdrawal", label: commandLabels.cashWithdrawal, shortcut: "F9",
          disabled: saleCommandDisabled("cash-withdrawal"),
          disabledReason: "Finaliza el cobro activo antes de registrar una retirada",
          onSelect: () => executeSaleCommand("cash-withdrawal"),
        },
      ],
    },
    {
      id: "invoice-ticket",
      label: t("sale.menu.invoiceTicket"),
      entries: [
        ...(onOpenSalesDocumentWindow ? [{
          type: "action" as const,
          id: "sales-document",
          label: commandLabels.document,
          shortcut: "Ctrl+F",
          disabled: paymentLocked,
          onSelect: () => executeSaleCommand("sales-document"),
        }] : []),
        {
          type: "action", id: "convert-ticket", label: commandLabels.convertInvoice, shortcut: "F12",
          disabled: !paymentHydrated || paymentLocked,
          onSelect: () => executeSaleCommand("convert-ticket"),
        },
        {
          type: "action", id: "import-previous-ticket", label: t("sale.shortcut.importPreviousTicket"),
          disabled: saleCommandDisabled("import-previous-ticket"),
          disabledReason: catalogLoading || catalogError
            ? t("sale.importPreviousTicket.error.catalogUnavailable")
            : paymentLocked
              ? t("sale.importPreviousTicket.error.paymentLocked")
              : previousTicketImportBusy
                ? t("sale.importPreviousTicket.loading")
                : undefined,
          onSelect: () => executeSaleCommand("import-previous-ticket"),
        },
        { type: "separator", id: "invoice-ticket-separator" },
        {
          type: "action", id: "ticket-return", label: commandLabels.ticketReturn, shortcut: "F10",
          disabled: saleCommandDisabled("ticket-return"),
          onSelect: () => executeSaleCommand("ticket-return"),
        },
        {
          type: "action", id: "gift-receipt", label: t("sale.shortcut.giftReceipt"), shortcut: "Ctrl+R",
          disabled: !paymentHydrated || paymentLocked,
          onSelect: () => executeSaleCommand("gift-receipt"),
        },
        {
          type: "action", id: "cancel-last-ticket", label: commandLabels.cancelTicket, shortcut: "F11",
          disabled: !paymentHydrated || paymentLocked,
          onSelect: () => executeSaleCommand("cancel-last-ticket"),
        },
        {
          type: "action", id: "cancel-ticket", label: commandLabels.cancelOtherTicket, shortcut: "Ctrl+F11",
          disabled: !paymentHydrated || paymentLocked,
          onSelect: () => executeSaleCommand("cancel-ticket"),
        },
      ],
    },
    {
      id: "document",
      label: t("sale.menu.document"),
      entries: [
        {
          type: "action", id: "checkout", label: commandLabels.checkout, shortcut: commandLabels.pageDownKey,
          disabled: paymentActionsDisabled || paymentLocked,
          onSelect: () => executeSaleCommand("checkout"),
        },
        {
          type: "action", id: "customer", label: commandLabels.customer, shortcut: "Fin",
          disabled: paymentLocked,
          onSelect: () => executeSaleCommand("customer"),
        },
        { type: "separator", id: "document-separator-1" },
        {
          type: "action", id: "park-sale", label: commandLabels.parkSale, shortcut: "Ctrl+G",
          disabled: saleCommandDisabled("park-sale"),
          onSelect: () => executeSaleCommand("park-sale"),
        },
        { type: "separator", id: "document-separator-2" },
        {
          type: "action", id: "sale-comment", label: commandLabels.saleComment, shortcut: "Ctrl+O",
          disabled: saleCommandDisabled("sale-comment"),
          onSelect: () => executeSaleCommand("sale-comment"),
        },
        {
          type: "action", id: "clear-sale", label: t("sale.clearSale.action"), shortcut: "Ctrl+F4",
          disabled: saleCommandDisabled("clear-sale"),
          onSelect: () => executeSaleCommand("clear-sale"),
        },
        {
          type: "action", id: "clear-lines", label: t("sale.clearLines.action"), shortcut: "Ctrl+Shift+A",
          disabled: saleCommandDisabled("clear-lines"),
          onSelect: () => executeSaleCommand("clear-lines"),
        },
        {
          type: "action", id: "clear-discounts", label: t("sale.clearDiscounts"), shortcut: "Ctrl+Shift+D",
          disabled: saleCommandDisabled("clear-discounts"),
          onSelect: () => executeSaleCommand("clear-discounts"),
        },
        {
          type: "action", id: "print-method", label: commandLabels.printMethod, shortcut: "Ctrl+P",
          disabled: saleCommandDisabled("print-method"),
          onSelect: () => executeSaleCommand("print-method"),
        },
        { type: "separator", id: "document-separator-3" },
        {
          type: "action", id: "sale-discount", label: commandLabels.saleDiscount, shortcut: "Ctrl+/",
          disabled: lines.length === 0 || paymentLocked || !canApplyManualDiscount,
          disabledReason: !canApplyManualDiscount ? "No tienes el permiso APLICAR_DESCUENTO" : undefined,
          onSelect: () => executeSaleCommand("sale-discount"),
        },
      ],
    },
    {
      id: "product",
      label: t("sale.menu.product"),
      entries: [
        {
          type: "action", id: "price-lookup", label: commandLabels.priceLookup, shortcut: "F1",
          onSelect: () => executeSaleCommand("price-lookup"),
        },
        {
          type: "action", id: "stock", label: commandLabels.selectedStock, shortcut: "F5",
          disabled: !selectedLine || paymentLocked,
          onSelect: () => executeSaleCommand("stock"),
        },
        {
          type: "action", id: "sales-history", label: commandLabels.productSales, shortcut: "F6",
          disabled: paymentLocked,
          onSelect: () => executeSaleCommand("sales-history"),
        },
        {
          type: "action", id: "edit-product", label: commandLabels.editProduct, shortcut: "F7",
          disabled: !selectedLine || paymentLocked || productEditBusy,
          onSelect: () => executeSaleCommand("edit-product"),
        },
        { type: "separator", id: "product-separator-1" },
        {
          type: "action", id: "quantity", label: commandLabels.setQuantity, shortcut: "Pausa",
          disabled: !selectedLine || paymentLocked,
          onSelect: () => executeSaleCommand("quantity"),
        },
        {
          type: "action", id: "add-quantity", label: commandLabels.addQuantity, shortcut: "Ctrl++",
          disabled: !selectedLine || paymentLocked,
          onSelect: () => executeSaleCommand("add-quantity"),
        },
        {
          type: "action", id: "subtract-quantity", label: commandLabels.subtractQuantity, shortcut: "Ctrl+-",
          disabled: !selectedLine || paymentLocked,
          onSelect: () => executeSaleCommand("subtract-quantity"),
        },
        {
          type: "action", id: "next-units", label: commandLabels.nextUnits, shortcut: "+",
          disabled: paymentLocked,
          onSelect: () => executeSaleCommand("next-units"),
        },
        {
          type: "action", id: "next-package", label: commandLabels.nextPackage, shortcut: "*",
          disabled: paymentLocked,
          onSelect: () => executeSaleCommand("next-package"),
        },
        { type: "separator", id: "product-separator-2" },
        {
          type: "action", id: "temporary-name", label: commandLabels.temporaryName, shortcut: "Inicio",
          disabled: saleCommandDisabled("temporary-name"),
          onSelect: () => executeSaleCommand("temporary-name"),
        },
        {
          type: "action", id: "desired-price", label: commandLabels.desiredPrice, shortcut: "RePág",
          disabled: !selectedLine || paymentLocked || !canApplyManualDiscount
            || saleProductBlocksManualDiscount(selectedLine.product),
          onSelect: () => executeSaleCommand("desired-price"),
        },
        {
          type: "action", id: "temporary-price", label: commandLabels.temporaryPrice, shortcut: "Ctrl+RePág",
          disabled: saleCommandDisabled("temporary-price"),
          onSelect: () => executeSaleCommand("temporary-price"),
        },
        {
          type: "action", id: "serial-number", label: commandLabels.serialNumber, shortcut: "Ctrl+N",
          disabled: !selectedLine || paymentLocked,
          onSelect: () => executeSaleCommand("serial-number"),
        },
        {
          type: "action", id: "line-discount", label: commandLabels.lineDiscount, shortcut: "/",
          disabled: !selectedLine || paymentLocked || !canApplyManualDiscount
            || saleProductBlocksManualDiscount(selectedLine.product),
          onSelect: () => executeSaleCommand("line-discount"),
        },
      ],
    },
    {
      id: "visualization",
      label: t("sale.menu.visualization"),
      entries: [
        {
          type: "toggle", id: "show-image", label: t("sale.menu.showImage"),
          checked: cartColumnVisible("image"),
          onToggle: () => cartTableLayout.toggleColumnVisibility("image"),
        },
        {
          type: "toggle", id: "show-barcode", label: t("sale.menu.showBarcode"),
          checked: cartColumnVisible("barcode"),
          onToggle: () => cartTableLayout.toggleColumnVisibility("barcode"),
        },
        {
          type: "toggle", id: "show-package", label: t("sale.menu.showPackage"),
          checked: cartColumnVisible("package"),
          onToggle: () => cartTableLayout.toggleColumnVisibility("package"),
        },
      ],
    },
  ];

  return (
    <main className={`sale-screen work-screen ${interfaceMode === "TOUCH" ? "touch-mode" : "keyboard-mode"}`}>
      <div aria-hidden={pendingRecoveryBlocked || !cashSessionReady || undefined} style={{ display: "contents" }}><SessionTopControls
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
        onLogout={() => void handleSaleLogout()}
        onPrepareShutdown={handleApplicationClose}
        onBrowserClose={onLogout}
      /></div>
      <section className="work-shell" aria-label={t("sale.main.screen")} aria-hidden={pendingRecoveryBlocked || !cashSessionReady || undefined}>
        <header className="work-topbar sale-command-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="sale-command-screen-title">{t("sale.main.screen")}</h1>
          <SaleCommandMenuBar
            ariaLabel={t("sale.menu.aria")}
            menus={saleCommandMenus}
          />
          {app === "venta" && hasPermission(session, "VENTA") && (
            <VerifactuPosIndicator
              token={session.accessToken ?? ""}
              locale={locale}
              t={t}
              refreshSignal={verifactuRefreshSignal}
            />
          )}
        </header>

        <section className="sale-ticket work-panel" aria-label={t("sale.main.ticket")}>
          <div className="sale-ticket-lines sale-cart-table-scroll">
            <table
              className="sale-cart-table"
              aria-label={t("sale.main.ticketLines")}
              style={{ width: Math.max(cartTableWidth, 720) }}
            >
              <colgroup>
                {visibleCartColumns.map((column) => (
                  <col key={column.key} style={{ width: column.width }} />
                ))}
              </colgroup>
              <thead>
                <tr>
                  {visibleCartColumns.map((column) => (
                    <TableLayoutHeaderCell
                      column={column}
                      key={column.key}
                      resizable={column.key !== "image"}
                      resizeLabel={`${t("stock.columns.resize")} ${cartColumnLabel(column.key)}`}
                      onReorder={cartTableLayout.reorderColumns}
                      onMove={cartTableLayout.moveColumn}
                      onResize={cartTableLayout.resizeColumn}
                    >
                      {cartColumnLabel(column.key)}
                    </TableLayoutHeaderCell>
                  ))}
                </tr>
              </thead>
              <tbody>
                {authoritativeLineBreakdown && !previousTicketImportBatch
                  ? (() => {
                      let productIndex = 0;
                      return authoritativeLineBreakdown.map((line) => {
                        if (line.lineType === "RETURN_ADJUSTMENT") {
                          return renderReturnPromotionAdjustmentRow(line);
                        }
                        if (line.lineType === "MANUAL_DISCOUNT") {
                          return null;
                        }
                        const localLine = lines[productIndex++];
                        return localLine ? renderCartRow(localLine, line) : null;
                      });
                    })()
                  : previousTicketImportBatch && authoritativeLineBreakdown
                    ? (() => {
                        const authoritativeProducts = authoritativeLineBreakdown.filter(
                          (line) => !line.lineType || line.lineType === "PRODUCT",
                        );
                        if (previousTicketImportBatch.pricingMode === "CURRENT_REPRICING") {
                          return lines.map((line, index) => renderCartRow(
                            line,
                            authoritativeProducts[index],
                          ));
                        }
                        const imported = lines.filter((line) => line.previousTicketImportOrigin);
                        const current = lines.filter((line) => !line.previousTicketImportOrigin);
                        const currentAuthoritative = authoritativeProducts.slice(imported.length);
                        return [
                          ...imported.map((line) => renderCartRow(line)),
                          ...current.map((line, index) => renderCartRow(line, currentAuthoritative[index])),
                        ];
                      })()
                    : lines.map((line) => renderCartRow(line))}
                <tr className="sale-cart-grid-filler" aria-hidden="true">
                  {visibleCartColumns.map((column) => (
                    <td data-column-key={column.key} key={column.key} />
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
          {paymentLocked && lines.length === 0 && (
            <p className="sale-ticket-recovery-guidance">{t("payment.split.reservedTicketGuidance")}</p>
          )}
          <PromotionPreviewPanel
            locale={locale}
            preview={visiblePromotionPreview}
            status={currentRepricingQuoteStatus}
          />
        </section>

        <section className="sale-tools work-panel" aria-label={t("sale.main.searchAndPayment")}>
          <footer className="sale-total">
            <span>{t("sale.main.total")}</span>
            <strong aria-busy={currentRepricingQuoteStatus?.kind === "LOADING" || undefined}>
              {currentRepricingQuotePending ? "—" : formatSaleAmount(displayedTotal)}
            </strong>
            {currentRepricingQuoteStatus ? (
              <small
                className={currentRepricingQuoteStatus.kind === "ERROR" ? "sale-action-error" : undefined}
                role={currentRepricingQuoteStatus.kind === "ERROR" ? "alert" : "status"}
              >
                {currentRepricingQuoteStatus.detail}
              </small>
            ) : authoritativeQuoteError ? (
              <small className="sale-action-error" role="alert">{authoritativeQuoteError}</small>
            ) : null}
          </footer>
          <label className="work-search">
            <span>{t("sale.main.searchProduct")}</span>
            <input
              ref={searchInputRef}
              aria-label={t("sale.main.searchProduct")}
              aria-expanded={productSearchOpen}
              aria-haspopup="dialog"
              autoComplete="off"
              disabled={catalogLoading || catalogError || paymentLocked || previousTicketImportBusy}
              placeholder={t("sale.main.searchPlaceholder")}
              role="combobox"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  submitSearch();
                }
              }}
            />
          </label>
          <p className="sale-next-quantity">
            {t("sale.main.quantity")}: {nextScanQuantity}
            {nextScanMode === "PACKAGE"
              ? ` ${t(nextScanQuantity === 1 ? "sale.quantity.package" : "sale.quantity.packages")}`
              : ""}
          </p>
          {(catalogLoading || catalogError || shortcutStatus
            || (temporaryPriceLines.length > 0 && !temporaryPriceAuthorizationsReady)) && (
            <div aria-live="polite" className="sale-search-results">
              {catalogLoading && <p className="sale-search-status">{t("sale.main.loadingProducts")}</p>}
              {catalogError && (
                <div className="sale-search-status sale-search-error">
                  <span>{t("sale.main.catalogError")}</span>
                  <button type="button" onClick={() => setCatalogReload((value) => value + 1)}>{t("sale.main.retry")}</button>
                </div>
              )}
              {shortcutStatus && <p className="sale-search-status" role="status">{shortcutStatus}</p>}
              {temporaryPriceLines.length > 0 && !temporaryPriceAuthorizationsReady && (
                <p className="sale-search-status sale-search-error" role="alert">
                  {t("sale.temporaryPrice.authorizationRequired")}
                </p>
              )}
            </div>
          )}
          <button
            type="button"
            className={`sale-customer-summary${selectedCustomer ? " has-customer" : " no-customer"}`}
            disabled={saleCommandDisabled("customer")}
            onClick={() => executeSaleCommand("customer")}
            aria-label={`${t("sale.customer.card.title")}: ${selectedCustomer?.fiscalName ?? t("sale.customer.none")}. ${t("sale.customer.card.open")}`}
          >
            <span className="sale-customer-summary-heading">
              <strong>{t("sale.customer.card.title")}</strong>
              <kbd>Fin</kbd>
            </span>
            {!selectedCustomer ? (
              <strong className="sale-customer-summary-empty">{t("sale.customer.none")}</strong>
            ) : (
              <>
                <span className="sale-customer-summary-identity">
                  <strong>{selectedCustomer.clientId ?? t("sale.customer.noCode")}</strong>
                  <b title={selectedCustomer.fiscalName ?? undefined}>{selectedCustomer.fiscalName ?? t("sale.customer.unnamed")}</b>
                  {selectedCustomer.documentNumber && <small>{selectedCustomer.documentNumber}</small>}
                </span>
                <span className="sale-customer-summary-values">
                  {selectedCustomer.activeMember && (
                    <span>
                      <small>{t("sale.customer.card.balance")}</small>
                      <strong>{formatSaleAmount(selectedCustomer.memberBalance)} €</strong>
                    </span>
                  )}
                  {Number(selectedCustomer.outstandingDebt ?? 0) > 0 && (
                    <span className="debt">
                      <small>{t("sale.customer.card.debt")}</small>
                      <strong>{formatSaleAmount(selectedCustomer.outstandingDebt)} €</strong>
                    </span>
                  )}
                  {Number(selectedCustomer.overdueDebt ?? 0) > 0 && (
                    <span className="overdue">
                      <small>{t("sale.customer.card.overdue")}</small>
                      <strong>{formatSaleAmount(selectedCustomer.overdueDebt)} €</strong>
                    </span>
                  )}
                </span>
              </>
            )}
          </button>
          {interfaceMode === "TOUCH" && (
            <TouchSaleActionPanel
              labels={commandLabels}
              paymentLocked={paymentLocked}
              searchDisabled={catalogLoading || Boolean(catalogError) || paymentLocked}
              quantityDisabled={saleCommandDisabled("quantity")}
              temporaryNameDisabled={saleCommandDisabled("temporary-name")}
              temporaryPriceDisabled={saleCommandDisabled("temporary-price")}
              editProductDisabled={!selectedLine || paymentLocked || productEditBusy}
              serialNumberDisabled={saleCommandDisabled("serial-number")}
              ticketReturnDisabled={saleCommandDisabled("ticket-return")}
              discountDisabled={
                !selectedLine
                || paymentLocked
                || Boolean(selectedLine.previousTicketImportOrigin)
                || !canApplyManualDiscount
                || saleProductBlocksManualDiscount(selectedLine.product)
              }
              discountTitle={!canApplyManualDiscount
                ? "No tienes el permiso APLICAR_DESCUENTO"
                : selectedLine && saleProductBlocksManualDiscount(selectedLine.product)
                  ? t("sale.discountBlocked")
                  : undefined}
              documentAvailable={Boolean(onOpenSalesDocumentWindow)}
              receivablesAvailable={canOpenCustomerReceivables}
              receivablesCustomer={selectedCustomer?.fiscalName ?? undefined}
              onSearch={() => searchInputRef.current?.focus()}
              onEanGenerator={() => executeSaleCommand("ean-generator")}
              onPrintProductLabel={() => executeSaleCommand("print-product-label")}
              onCashDrawer={() => executeSaleCommand("cash-drawer")}
              onCashWithdrawal={() => executeSaleCommand("cash-withdrawal")}
              onEditProduct={() => executeSaleCommand("edit-product")}
              onSerialNumber={() => executeSaleCommand("serial-number")}
              onTicketReturn={() => executeSaleCommand("ticket-return")}
              onDocument={() => executeSaleCommand("sales-document")}
              onQuantity={() => executeSaleCommand("quantity")}
              onTemporaryName={() => executeSaleCommand("temporary-name")}
              onTemporaryPrice={() => executeSaleCommand("temporary-price")}
              onDiscount={() => executeSaleCommand("line-discount")}
              onCustomer={() => executeSaleCommand("customer")}
              onRemoveLine={() => setActionDialog("remove")}
              onParkedSales={() => executeSaleCommand("park-sale")}
              onCancelLastTicket={() => executeSaleCommand("cancel-last-ticket")}
              onCancelTicket={() => executeSaleCommand("cancel-ticket")}
              onConvertTicket={() => executeSaleCommand("convert-ticket")}
              onReceivables={() => onOpenCustomerReceivables?.(selectedCustomer?.id)}
            />
          )}
          <section className="sale-payment" aria-label={t("sale.main.payment")}>
            {interfaceMode === "TOUCH" && <h2>{t("sale.main.payment")}</h2>}
            <SalePaymentCheckout
              ref={paymentCheckoutRef}
              locale={locale}
              currentUsername={session.username}
              totalCents={Math.round(authoritativeTotal * 100)}
              sale={cashSaleRequest(true)}
              token={session.accessToken}
              permissions={session.permissions}
              terminal={terminalContext}
              disabled={paymentActionsDisabled || !paymentHydrated}
              showIndividualActions={interfaceMode === "TOUCH"}
              unifiedCheckout
              interfaceMode={interfaceMode}
              customerSelected={Boolean(selectedCustomer)}
              voucherOnlyRefund={authoritativeTotal < 0 && lines.some((line) =>
                line.returnOrigin?.sourceType === "GIFT_RECEIPT")}
              checkoutDiscountCents={checkoutDiscountCents}
              testCashEnabled={import.meta.env.DEV && app === "venta"}
              manualCardPaymentAuthorization={manualCardPaymentAuthorization}
              transferPaymentAuthorization={transferPaymentAuthorization}
              saleMutationAuthorizations={saleMutationAuthorizations}
              paymentTerminalVoidAuthorization={paymentTerminalVoidAuthorization}
              paymentTerminalRefundAuthorization={paymentTerminalRefundAuthorization}
              refundPolicyOverrideAuthorization={refundPolicyOverrideAuthorization}
              refundTenderOverrideAuthorization={refundTenderOverrideAuthorization}
              paymentCompensationAuthorization={paymentCompensationAuthorization}
              createPendingAuthorization={createPendingAuthorization}
              creditOverrideAuthorization={creditOverrideAuthorization}
              onCash={() => void openCashDialog()}
              onPending={openPendingSale}
              onDiscount={currentEconomicLines.length > 0
                ? setCheckoutDiscountCents
                : undefined}
              onHydrationChange={setPaymentHydrated}
              onLockedChange={(locked, reservedTotalCents) => {
                setPaymentLocked(locked);
                setReservedPaymentTotalCents(
                  locked && reservedTotalCents != null ? reservedTotalCents : null,
                );
              }}
              onFinalized={(printTicket, summary) => {
                const completedPrintMode = salePrintMode;
                invalidateCashOpening();
                deletionControl.reset("SALE_FINALIZED");
                setVerifactuRefreshSignal((current) => current + 1);
                setLines([]);
                setPreviousTicketImportBatch(null);
                setSelectedLineId(null);
                setSelectedCustomer(null);
                setQuery("");
                setNextScanQuantity(1);
                setNextScanMode("UNIT");
                setSaleComment("");
                setCommentInput("");
                setSalePrintMode("DEFAULT");
                setPrintModeInput("DEFAULT");
                setCheckoutDiscountCents(0);
                setReservedPaymentTotalCents(null);
                setLastPrintMode(completedPrintMode);
                const result = paymentResultFromFinalization(printTicket, summary);
                setCashResult({ ...result, printStatus: "PRINTING" });
                startAutomaticTicketPrint(printTicket, completedPrintMode);
                if (summary.issuedVoucher) {
                  startAutomaticVoucherPrint(summary.issuedVoucher);
                }
              }}
            />
            {cashStatus && <p className="sale-payment-status" role="status">{cashStatus}</p>}
            {pendingError && <p className="sale-payment-status" role="alert">{pendingError}</p>}
          </section>
          {pendingPrintRetry && (
            <aside className="sale-sidebar-print-retry" aria-hidden={pendingRecoveryBlocked || undefined}>
              <p role="alert">{t("payment.result.printFailed")}</p>
              <button type="button" onClick={() => void retryPendingPrint()}>{t("payment.result.retryPrint")}</button>
            </aside>
          )}
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>

      <SaleCashDrawerAuthorizationDialog
        open={cashDrawerAuthorizationOpen}
        busy={cashDrawerBusy}
        error={cashDrawerError}
        t={t}
        locale={locale}
        currentUsername={session.username}
        authorization={cashDrawerAuthorization ?? {
          mode: "DELEGATED",
          requireUsername: true,
          requirePassword: true,
        }}
        onCancel={() => {
          if (cashDrawerBusy) return;
          setCashDrawerAuthorizationOpen(false);
          setCashDrawerError("");
        }}
        onAuthorize={(username, password) => void openCashDrawer(username, password)}
      />

      {cashWithdrawalOpen && cashSessionReady && terminalContext.terminalId && session.accessToken && (
        <SaleCashWithdrawalDialog
          locale={locale}
          currentUsername={session.username}
          terminalId={terminalContext.terminalId}
          terminalContext={terminalContext}
          token={session.accessToken}
          authorization={cashMovementAuthorization ?? {
            mode: "DELEGATED",
            requireUsername: true,
            requirePassword: true,
          }}
          requireEntryDenominationBreakdown={cashWithdrawalPolicy.requireEntryBreakdown}
          entryDenominations={cashWithdrawalPolicy.entryDenominations}
          requireDenominationBreakdown={cashWithdrawalPolicy.requireBreakdown}
          denominations={cashWithdrawalPolicy.denominations}
          onCancel={() => setCashWithdrawalOpen(false)}
          onCompleted={(movement) => {
            setCashWithdrawalOpen(false);
            setShortcutStatus(t(movement.type === "ENTRADA"
              ? "sale.cashMovement.entryCompleted"
              : "sale.cashMovement.withdrawalCompleted"));
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
        />
      )}

      <SaleCashDrawerAuthorizationDialog
        open={productEditAuthorizationOpen}
        busy={productEditBusy}
        error={productEditError}
        t={t}
        locale={locale}
        currentUsername={session.username}
        authorization={productEditAuthorization ?? {
          mode: "DELEGATED",
          requireUsername: true,
          requirePassword: true,
        }}
        translationPrefix="sale.productEdit"
        onCancel={() => {
          if (productEditBusy) return;
          setProductEditAuthorizationOpen(false);
          setProductEditError("");
        }}
        onAuthorize={(username, password) => void openProductEditor(username, password)}
      />

      <ProductCreateDialog
        open={Boolean(editingProduct && productEditAuthorizationId)}
        locale={locale}
        token={session.accessToken}
        operationalAuthorizationId={productEditAuthorizationId}
        editProduct={editingProduct}
        onClose={closeProductEditor}
        onCreated={() => {
          setCatalogReload((current) => current + 1);
          setShortcutStatus(t("sale.productEdit.saved"));
        }}
      />

      {cashDialogOpen && (
        <CashPaymentDialog
          locale={locale}
          totalCents={cashQuoteCents}
          initialMode={cashInputMode}
          submitting={cashSubmitting}
          error={cashError}
          onCancel={() => {
            invalidateCashOpening();
            setCashDialogOpen(false);
          }}
          onConfirm={(receivedCents) => void confirmCashPayment(receivedCents)}
        />
      )}

      {cashResult && (
        <CashPaymentResultDialog
          {...cashResult}
          locale={locale}
          onRetryPrint={retryTicketPrint}
          onRetryVoucherPrint={retryVoucherPrint}
          onFinish={() => setCashResult(null)}
        />
      )}

      {cardDialogOpen && <CardPaymentDialog totalCents={cardQuoteCents} status={cardStatus} submitting={cardSubmitting} message={cardMessage} onCancel={() => setCardDialogOpen(false)} onConsult={consultCardPayment} onNewOperation={retryCardPayment} />}

      {pendingDraft && (selectedCustomer || recoveredPendingSale) && <CustomerPendingSaleDialog
        customerName={selectedCustomer?.fiscalName ?? selectedCustomer?.clientId ?? recoveredPendingSale?.customer.name ?? "Cliente"}
        locale={locale}
        currentUsername={session.username}
        terminalContext={terminalContext}
        printMode={salePrintMode}
        draft={pendingDraft}
        recovery={recoveredPendingSale}
        token={session.accessToken}
        permissions={session.permissions}
        createPendingAuthorization={createPendingAuthorization}
        creditOverrideAuthorization={creditOverrideAuthorization}
        manualCardPaymentAuthorization={manualCardPaymentAuthorization}
        transferPaymentAuthorization={transferPaymentAuthorization}
        cardPaymentMode={pendingCardPaymentMode}
        saleMutationAuthorizations={saleMutationAuthorizations}
        disabled={paymentLocked}
        onPersistRecovery={(envelope: PendingSaleRecoveryEnvelope) => {
          savePendingSaleRecovery(localStorage, envelope);
          setPendingRecovery({ status: "valid", envelope });
        }}
        onClearRecovery={() => {
          clearPendingSaleRecovery(localStorage, terminalContext.terminalCode);
          setPendingRecovery({ status: "empty" });
        }}
        onCancel={() => {
          if (recoveredPendingSale) return;
          setPendingDraft(null);
          searchInputRef.current?.focus();
        }}
        onSuccess={(_result, retry) => {
          setPendingDraft(null); setLines([]); setPreviousTicketImportBatch(null); setSelectedLineId(null);
          setPendingPrintRetry(() => retry ?? null);
          setSelectedCustomer(null); setQuery(""); setNextScanQuantity(1); setNextScanMode("UNIT");
          setSaleComment(""); setCommentInput(""); setSalePrintMode("DEFAULT");
          setPrintModeInput("DEFAULT");
          searchInputRef.current?.focus();
          setVerifactuRefreshSignal((current) => current + 1);
        }}
      />}

      {pendingRecovery.status === "blocked" && <div className="sale-action-overlay pending-sale-overlay" role="presentation">
        <section ref={blockedRecoveryDialogRef} className="customer-pending-sale-dialog" role="dialog" aria-modal="true" aria-labelledby="pending-recovery-blocked-title">
          <header><h2 id="pending-recovery-blocked-title">{t("pendingSale.recoveryBlockedTitle")}</h2></header>
          <p className="sale-action-error" role="alert">{t("pendingSale.recoveryBlockedMessage")}</p>
          {pendingRecovery.identifiers.length > 0 && <div><strong>{t("pendingSale.recoveryIdentifiers")}</strong><ul>{pendingRecovery.identifiers.map((identifier) => <li key={identifier}><code>{identifier}</code></li>)}</ul></div>}
          <label>{t("pendingSale.recoveryRaw")}<textarea readOnly value={pendingRecovery.raw} rows={8} /></label>
          <footer><button type="button" onClick={() => void navigator.clipboard?.writeText(pendingRecovery.raw)}>{t("pendingSale.recoveryCopy")}</button></footer>
        </section>
      </div>}
      {productSearchOpen && !productInformationProduct && (
        <SaleProductSearchDialog
          initialQuery={productSearchQuery}
          initialSelectedId={productSearchSelectedId}
          interfaceMode={interfaceMode}
          locale={locale}
          labels={{
            title: t("sale.searchDialog.title"),
            query: t("sale.searchDialog.query"),
            image: t("sale.searchDialog.image"),
            code: t("sale.searchDialog.code"),
            barcode: t("sale.searchDialog.barcode"),
            name: t("sale.searchDialog.name"),
            stock: t("sale.searchDialog.stock"),
            price: t("sale.searchDialog.price"),
            result: t("sale.searchDialog.result"),
            results: t("sale.searchDialog.results"),
            empty: t("sale.searchDialog.empty"),
            close: t("common.close"),
            add: t("sale.searchDialog.add"),
            details: t("sale.searchDialog.details"),
            navigate: t("sale.searchDialog.navigate"),
            selected: t("sale.searchDialog.selected"),
            unnamedProduct: t("sale.main.unnamedProduct"),
            missingCode: t("sale.main.missingCode"),
          }}
          products={selectableProducts}
          token={session.accessToken}
          onInspect={openProductInformation}
          onQueryChange={setProductSearchQuery}
          onSelectionChange={setProductSearchSelectedId}
          onClose={closeProductSearch}
          onSelect={selectProductFromSearch}
        />
      )}

      {productInformationProduct && (
        <SaleProductInformationDialog
          product={productInformationProduct}
          locale={locale}
          token={session.accessToken}
          interfaceMode={interfaceMode}
          canManageProducts={userCanManageStockProducts(session)}
          onAdd={addProductFromInformation}
          onClose={closeProductInformation}
        />
      )}

      {consultationMode === "PRICE" && (
        <SalePriceConsultationDialog
          locale={locale}
          token={session.accessToken}
          onClose={() => {
            setConsultationMode(null);
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
        />
      )}

      {consultationMode === "STOCK" && (
        <SaleProductConsultationDialog
          products={selectableProducts}
          initialProduct={selectedLine?.product}
          token={session.accessToken}
          onClose={() => {
            setConsultationMode(null);
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
        />
      )}

      {calculatorOpen && (
        <SaleCalculatorDialog
          locale={locale}
          defaultTaxPercent={selectedLine?.product.taxPercentage ?? products[0]?.taxPercentage}
          terminalKey={terminalContext.terminalId ?? terminalContext.terminalCode}
          onClose={() => {
            setCalculatorOpen(false);
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
        />
      )}

      {salesHistoryOpen && (
        <SaleProductSalesHistoryDialog
          products={selectableProducts}
          initialProduct={salesHistoryProduct}
          locale={locale}
          app={app}
          username={session.username}
          accessToken={session.accessToken}
          onClose={() => {
            setSalesHistoryOpen(false);
            setSalesHistoryProduct(null);
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
        />
      )}

      {pendingOpenPriceProduct && (
        <SaleOpenPriceDialog
          key={`product-${pendingOpenPriceProduct.id}`}
          productName={pendingOpenPriceProduct.name ?? t("sale.main.unnamedProduct")}
          labels={{
            title: t("sale.openPrice.title"),
            product: t("sale.openPrice.product"),
            price: t("sale.openPrice.price"),
            placeholder: t("sale.openPrice.placeholder"),
            invalid: t("sale.openPrice.invalid"),
            cancel: t("common.cancel"),
            accept: t("sale.openPrice.accept"),
          }}
          onCancel={cancelOpenPrice}
          onAccept={confirmOpenPrice}
        />
      )}

      {actionDialog === "comment" && (
        <SaleActionDialog
          title={t("sale.comment.title")}
          closeLabel={t("sale.dialog.close")}
          onClose={() => setActionDialog(null)}
          className="sale-business-dialog sale-comment-dialog"
        >
          <form
            className="sale-action-form"
            onSubmit={(event) => {
              event.preventDefault();
              saveSaleComment();
            }}
          >
            <label>
              <span>{t("sale.comment.label")}</span>
              <textarea
                autoFocus
                aria-label={t("sale.comment.label")}
                maxLength={500}
                rows={5}
                value={commentInput}
                onChange={(event) => {
                  setCommentInput(event.target.value);
                  setActionError("");
                }}
              />
            </label>
            <small className="sale-dialog-hint">
              {t("sale.comment.hint")} {commentInput.length}/500
            </small>
            {actionError && <strong className="sale-action-error" role="alert">{actionError}</strong>}
            <div className="sale-action-buttons sale-business-dialog-actions">
              <button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button>
              <button type="submit" className="primary">{t("sale.dialog.save")}</button>
            </div>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "clearSale" && (
        <SaleActionDialog
          title={t("sale.clearSale.title")}
          closeLabel={t("sale.dialog.close")}
          onClose={() => setActionDialog(null)}
          onKeyDown={handleClearSaleKeyDown}
          initialFocusRef={clearSaleCancelButtonRef}
          className="sale-business-dialog sale-clear-sale-dialog"
        >
          <div className="sale-clear-sale-warning" role="note">
            <span className="sale-clear-sale-warning-icon" aria-hidden="true">!</span>
            <div>
              <strong>{t("sale.clearSale.warning")}</strong>
              <p>{t("sale.clearSale.confirm")}</p>
            </div>
          </div>
          <div className="sale-action-buttons sale-clear-sale-actions">
            <button ref={clearSaleCancelButtonRef} type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button>
            <button ref={clearSaleConfirmButtonRef} type="button" className="danger" onClick={clearSaleFromCommand}>{t("sale.clearSale.action")}</button>
          </div>
        </SaleActionDialog>
      )}

      {actionDialog === "clearLines" && (
        <SaleActionDialog
          title={t("sale.clearLines.title")}
          closeLabel={t("sale.dialog.close")}
          onClose={() => setActionDialog(null)}
          onKeyDown={handleClearLinesKeyDown}
          initialFocusRef={clearLinesCancelButtonRef}
          className="sale-business-dialog sale-clear-sale-dialog sale-clear-lines-dialog"
        >
          <div className="sale-clear-sale-warning" role="note">
            <span className="sale-clear-sale-warning-icon" aria-hidden="true">!</span>
            <div>
              <strong>{t("sale.clearSale.warning")}</strong>
              <p>{t("sale.clearLines.confirm")}</p>
            </div>
          </div>
          <div className="sale-action-buttons sale-clear-sale-actions">
            <button ref={clearLinesCancelButtonRef} type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button>
            <button ref={clearLinesConfirmButtonRef} type="button" className="danger" onClick={clearSaleLines}>{t("sale.clearLines.action")}</button>
          </div>
        </SaleActionDialog>
      )}

      {actionDialog === "printMethod" && (
        <SaleActionDialog
          title={t("sale.printMethod.title")}
          closeLabel={t("sale.dialog.close")}
          onClose={() => setActionDialog(null)}
          className="sale-print-method-dialog"
        >
          <form
            className="sale-action-form sale-print-method-form"
            onSubmit={(event) => {
              event.preventDefault();
              savePrintMethod(printModeInput);
            }}
          >
            <fieldset className="sale-print-method-options" aria-label={t("sale.printMethod.title")}>
              {([
                ["DEFAULT", "sale.printMethod.default", "sale.printMethod.defaultDescription"],
                ["TICKET_PRINTER", "sale.printMethod.ticket", "sale.printMethod.ticketDescription"],
                ["A4_PRINTER", "sale.printMethod.a4", "sale.printMethod.a4Description"],
                ["PDF", "sale.printMethod.pdf", "sale.printMethod.pdfDescription"],
                ["NONE", "sale.printMethod.none", "sale.printMethod.noneDescription"],
              ] as const).map(([mode, label, description]) => (
                <label
                  key={mode}
                  className={`sale-print-method-option${printModeInput === mode ? " selected" : ""}${mode === "DEFAULT" ? " default" : ""}${mode === "NONE" ? " no-output" : ""}`}
                >
                  <input
                    type="radio"
                    name="sale-print-method"
                    value={mode}
                    aria-label={t(label)}
                    checked={printModeInput === mode}
                    onChange={() => setPrintModeInput(mode)}
                  />
                  <span className="sale-print-method-option-copy">
                    <strong>{t(label)}</strong>
                    <small>{t(description)}</small>
                  </span>
                  <span className="sale-print-method-option-check" aria-hidden="true">
                    {printModeInput === mode ? "✓" : ""}
                  </span>
                </label>
              ))}
            </fieldset>
            <footer className="sale-print-method-footer">
              <p className="sale-dialog-hint">{t("sale.printMethod.hint")}</p>
              <div className="sale-action-buttons">
                <button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button>
                <button type="submit">{t("sale.dialog.apply")}</button>
              </div>
            </footer>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "quantity" && selectedLine && (
        <SaleActionDialog title={t("sale.quantity.title")} closeLabel={t("sale.dialog.close")} onClose={() => setActionDialog(null)}>
          <form className="sale-action-form" onSubmit={(event) => { event.preventDefault(); saveQuantity(); }}>
            <label>
              <span>{t("sale.quantity.label")}</span>
              <input
                ref={quantityInputRef}
                aria-label={t("sale.quantity.inputAria")}
                type="number"
                min={productQuantityStep(selectedLine.product.productType)}
                max="9999"
                step={productQuantityStep(selectedLine.product.productType)}
                value={quantityInput}
                onChange={(event) => setQuantityInput(event.target.value)}
              />
            </label>
            {interfaceMode === "TOUCH" && (
              <TouchNumericKeypad
                value={quantityInput}
                ariaLabel={t("sale.touch.numericKeypad")}
                clearLabel={t("sale.touch.clearNumber")}
                backspaceLabel={t("sale.touch.backspace")}
                onChange={setQuantityInput}
              />
            )}
            {actionError && <strong className="sale-action-error">{actionError}</strong>}
            <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button><button type="submit">{t("sale.dialog.save")}</button></div>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "discount" && selectedLine && (
        <SaleActionDialog title={t("sale.discount.title")} closeLabel={t("sale.dialog.close")} onClose={() => setActionDialog(null)}>
          <form className="sale-action-form" onSubmit={(event) => { event.preventDefault(); saveDiscount(); }}>
            <label>
              <span>{t("sale.discount.label")}</span>
              <input ref={discountInputRef} aria-label={t("sale.discount.inputAria")} type="number" min="0" max="100" step="0.01" value={discountInput} onChange={(event) => setDiscountInput(event.target.value)} />
            </label>
            {interfaceMode === "TOUCH" && (
              <TouchNumericKeypad
                value={discountInput}
                allowDecimal
                decimalLabel={locale === "es" ? "," : "."}
                ariaLabel={t("sale.touch.numericKeypad")}
                clearLabel={t("sale.touch.clearNumber")}
                backspaceLabel={t("sale.touch.backspace")}
                onChange={setDiscountInput}
              />
            )}
            {actionError && <strong className="sale-action-error">{actionError}</strong>}
            <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button><button type="submit">{t("sale.dialog.save")}</button></div>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "temporaryName" && selectedLine && (
        <SaleActionDialog
          title={t("sale.temporaryName.title")}
          closeLabel={t("sale.dialog.close")}
          className="sale-inline-edit-dialog"
          initialFocusRef={temporaryNameInputRef}
          onClose={() => setActionDialog(null)}
        >
          <form className="sale-action-form" onSubmit={(event) => {
            event.preventDefault();
            saveTemporaryName();
          }}>
            <div className="sale-inline-edit-product" aria-label={t("sale.main.product")}>
              <span>{selectedLine.product.code ?? selectedLine.product.barcode ?? ""}</span>
              <strong>{selectedLine.temporaryName ?? selectedLine.product.name ?? ""}</strong>
            </div>
            <label>
              <span>{t("sale.temporaryName.label")}</span>
              <input
                ref={temporaryNameInputRef}
                maxLength={255}
                value={temporaryNameInput}
                onChange={(event) => setTemporaryNameInput(event.target.value)}
              />
            </label>
            <small className="sale-dialog-hint">{t("sale.temporaryName.hint")}</small>
            {actionError && <strong className="sale-action-error">{actionError}</strong>}
            <div className="sale-action-buttons">
              <button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button>
              <button type="submit">{t("sale.dialog.save")}</button>
            </div>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "temporaryPrice" && selectedLine && (
        <SaleActionDialog
          title={t("sale.temporaryPrice.title")}
          closeLabel={t("sale.dialog.close")}
          className="sale-inline-edit-dialog"
          initialFocusRef={temporaryPriceInputRef}
          onClose={() => setActionDialog(null)}
        >
          <form className="sale-action-form" onSubmit={(event) => {
            event.preventDefault();
            saveTemporaryPrice();
          }}>
            <div className="sale-inline-edit-product" aria-label={t("sale.main.product")}>
              <span>{selectedLine.product.code ?? selectedLine.product.barcode ?? ""}</span>
              <strong>{selectedLine.temporaryName ?? selectedLine.product.name ?? ""}</strong>
            </div>
            <label>
              <span>{t("sale.temporaryPrice.label")}</span>
              <input
                ref={temporaryPriceInputRef}
                inputMode="decimal"
                disabled={temporaryPriceAuthorizationBusy}
                value={temporaryPriceInput}
                onChange={(event) => setTemporaryPriceInput(event.target.value)}
              />
            </label>
            {interfaceMode === "TOUCH" && (
              <TouchNumericKeypad
                value={temporaryPriceInput}
                allowDecimal
                decimalLabel={locale === "es" ? "," : "."}
                ariaLabel={t("sale.touch.numericKeypad")}
                clearLabel={t("sale.touch.clearNumber")}
                backspaceLabel={t("sale.touch.backspace")}
                onChange={setTemporaryPriceInput}
              />
            )}
            <small className="sale-dialog-hint">{t("sale.temporaryPrice.hint")}</small>
            {actionError && <strong className="sale-action-error">{actionError}</strong>}
            <div className="sale-action-buttons">
              <button type="button" disabled={temporaryPriceAuthorizationBusy} onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button>
              <button type="submit" disabled={temporaryPriceAuthorizationBusy}>{t("sale.dialog.save")}</button>
            </div>
          </form>
        </SaleActionDialog>
      )}

      {pendingTemporaryPriceChange && temporaryPriceChangeAuthorization && (
        <SaleMutationAuthorizationDialog
          open
          locale={locale}
          currentUsername={session.username}
          requirements={[{
            code: "TEMPORARY_PRICE_CHANGE",
            label: t("gestion.salesOperationSecurity.operation.TEMPORARY_PRICE_CHANGE"),
            authorization: temporaryPriceChangeAuthorization,
          }]}
          busy={temporaryPriceAuthorizationBusy}
          error={temporaryPriceAuthorizationError}
          onCancel={() => {
            setPendingTemporaryPriceChange(null);
            setTemporaryPriceAuthorizationError("");
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
          onConfirm={(authorizations: SaleMutationOperationAuthorizations) => {
            const credentials = authorizations.TEMPORARY_PRICE_CHANGE ?? {};
            void authorizeTemporaryPriceChange(pendingTemporaryPriceChange, credentials);
          }}
        />
      )}

      {actionDialog === "customer" && !customerCreateOpen && !customerEditId && (
        <SaleActionDialog
          title={t("sale.customer.title")}
          closeLabel={t("sale.dialog.close")}
          initialFocusRef={customerSearchInputRef}
          onClose={closeCustomerDialog}
          onKeyDown={handleCustomerDialogKeyDown}
          className="sale-customer-selection-dialog"
          wide
        >
          <div className="sale-customer-action-bar" aria-label={t("sale.customer.actions")}>
            {saleCustomerCreationAllowed && <button type="button" onClick={() => {
              setCustomerCreateOpen(true);
              setCustomerEditId(null);
            }}><span>{t("sale.customer.create")}</span><kbd>F5</kbd></button>}
            {saleCustomerEditAllowed && <button type="button" disabled={!selectedCustomerResult()} onClick={openSelectedCustomerEditor}>
              <span>{t("sale.customer.edit")}</span><kbd>Ctrl+F7</kbd>
            </button>}
            {saleCustomerReceivablesAllowed && <button
              type="button"
              className="debt-action"
              disabled={!selectedCustomerResult() || Number(selectedCustomerResult()?.outstandingDebt ?? 0) <= 0}
              onClick={openSelectedCustomerReceivables}
            ><span>{t("sale.customer.collectDebt")}</span><kbd>Enter</kbd></button>}
          </div>
          <div className="sale-customer-toolbar">
            <label>
              <span>{t("sale.customer.search")}</span>
              <input ref={customerSearchInputRef} aria-label={t("sale.customer.search")} value={customerQuery} onChange={(event) => setCustomerQuery(event.target.value)} placeholder={t("sale.customer.placeholder")} />
            </label>
          </div>
          <div className="sale-customer-table" role="table" aria-label={t("sale.customer.title")}>
            <div className="sale-customer-table-header" role="row">
              {(["code", "name", "document", "member", "discount", "debt", "overdue"] as const).map((column) => (
                <span
                  role="columnheader"
                  aria-sort={customerSort?.column === column
                    ? customerSort.direction === "asc" ? "ascending" : "descending"
                    : "none"}
                  key={column}
                >
                  <TableSortButton
                    direction={customerSort?.column === column ? customerSort.direction : null}
                    label={`${t("party.sortBy")} ${t(`sale.customer.column.${column}`)}`}
                    onSort={() => setCustomerSort((current) => nextTableSort(current, column))}
                  >
                    {t(`sale.customer.column.${column}`)}
                  </TableSortButton>
                </span>
              ))}
            </div>
            <div className="sale-customer-table-body" role="rowgroup">
              {customerLoading && <p className="sale-search-status">{t("sale.customer.loading")}</p>}
              {customerError && <p className="sale-action-error">{t("sale.customer.loadError")}</p>}
              {!customerLoading && !customerError && !pendingCustomerContinuation && <button
                type="button"
                aria-label={t("sale.customer.none")}
                className={`sale-customer-table-row sale-customer-none-row${selectedCustomerResultId === noCustomerSelectionId ? " selected" : ""}`}
                aria-current={selectedCustomerResultId === noCustomerSelectionId}
                onClick={() => setSelectedCustomerResultId(noCustomerSelectionId)}
                onDoubleClick={() => chooseSaleCustomer(null)}
              >
                <span role="cell">—</span>
                <strong role="cell">{t("sale.customer.none")}</strong>
                <span role="cell"></span><span role="cell"></span><span role="cell"></span>
                <span role="cell"></span><span role="cell"></span>
              </button>}
              {!customerLoading && !customerError && customerResults.map((customer) => (
                <button
                  type="button"
                  aria-label={[customer.clientId, customer.fiscalName, customer.documentNumber].filter(Boolean).join(" · ") || t("sale.customer.unnamed")}
                  className={`sale-customer-table-row${customer.id === selectedCustomerResultId ? " selected" : ""}`}
                  aria-current={customer.id === selectedCustomerResultId}
                  key={customer.id}
                  onFocus={() => setSelectedCustomerResultId(customer.id)}
                  onClick={() => setSelectedCustomerResultId(customer.id)}
                  onDoubleClick={() => chooseSaleCustomer(customer)}
                >
                  <strong role="cell">{customer.clientId ?? t("sale.customer.noCode")}</strong>
                  <span role="cell" title={customer.fiscalName ?? undefined}>{customer.fiscalName ?? t("sale.customer.unnamed")}</span>
                  <span role="cell">{customer.documentNumber ?? ""}</span>
                  <span role="cell">{customer.activeMember ? customer.memberCategoryName || t("common.yes") : ""}</span>
                  <span role="cell">{customer.memberDiscountPercent == null ? "" : `${Number(customer.memberDiscountPercent).toLocaleString(locale, { maximumFractionDigits: 2 })} %`}</span>
                  <strong role="cell" className={Number(customer.outstandingDebt ?? 0) > 0 ? "debt" : ""}>
                    {Number(customer.outstandingDebt ?? 0) > 0 ? `${Number(customer.outstandingDebt).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €` : ""}
                  </strong>
                  <strong role="cell" className={Number(customer.overdueDebt ?? 0) > 0 ? "overdue-debt" : ""}>
                    {Number(customer.overdueDebt ?? 0) > 0 ? `${Number(customer.overdueDebt).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €` : ""}
                  </strong>
                </button>
              ))}
              {!customerLoading && !customerError && customerResults.length === 0 && pendingCustomerContinuation && <p className="sale-customer-empty">{t("sale.customer.empty")}</p>}
            </div>
          </div>
          <footer className="sale-customer-selection-footer">
            <p className="sale-dialog-hint"><kbd>Insert</kbd> {t("sale.customer.insertHint")}</p>
            <div className="sale-action-buttons">
              <button type="button" onClick={closeCustomerDialog}>{t("sale.dialog.close")}</button>
              <button type="button" disabled={selectedCustomerResult() === undefined} onClick={() => {
                const customer = selectedCustomerResult();
                if (customer !== undefined) chooseSaleCustomer(customer);
              }}>{t("sale.customer.select")}</button>
            </div>
          </footer>
        </SaleActionDialog>
      )}

      {actionDialog === "customer" && customerCreateOpen && (
        <SaleCustomerCreateDialog
          locale={locale}
          session={session}
          onCancel={() => {
            setCustomerCreateOpen(false);
            queueMicrotask(() => customerSearchInputRef.current?.focus());
          }}
          onCreated={(customer) => void finishSavedSaleCustomer(customer.id, true)}
        />
      )}

      {actionDialog === "customer" && customerEditId && (
        <SaleCustomerCreateDialog
          locale={locale}
          session={session}
          customerId={customerEditId}
          onCancel={() => {
            setCustomerEditId(null);
            queueMicrotask(() => customerSearchInputRef.current?.focus());
          }}
          onCreated={(customer) => void finishSavedSaleCustomer(customer.id, false)}
        />
      )}

      {actionDialog === "customer" && customerReceivablesOpen && (
        <SaleCustomerReceivablesDialog
          locale={locale}
          interfaceMode={interfaceMode}
          session={session}
          terminalContext={terminalContext}
          customer={customerReceivablesOpen}
          onClose={() => {
            setCustomerReceivablesOpen(null);
            queueMicrotask(() => customerSearchInputRef.current?.focus());
          }}
        />
      )}

      {actionDialog === "remove" && selectedLine && (
        <SaleActionDialog title={t("sale.removeLine.title")} closeLabel={t("sale.dialog.close")} onClose={() => setActionDialog(null)} onKeyDown={handleRemoveLineKeyDown}>
          <p>{saleMainMessage(t, "sale.removeLine.confirm", { product: selectedLine.product.name ?? t("sale.removeLine.productFallback") })}</p>
          <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button><button ref={removeConfirmButtonRef} type="button" className="danger" onClick={confirmRemoveLine}>{t("sale.removeLine.action")}</button></div>
        </SaleActionDialog>
      )}

      {parkedSalesOpen && (
        <ParkedSalesDialog
          token={session.accessToken}
          locale={locale}
          currentUsername={session.username}
          canManageSales={hasPermission(session, "ADMIN")
            || hasPermission(session, "GESTION_VENTAS")}
          onClose={() => setParkedSalesOpen(false)}
          onRecovered={recoverParkedSale}
        />
      )}

      <SaleMutationAuthorizationDialog
        open={parkedSaleAuthorizationOpen}
        locale={locale}
        currentUsername={session.username}
        requirements={saleMutationCredentialsRequired(saleMutationAuthorizations ?? [])}
        busy={parkedSaleSaving}
        error={parkedSaleError}
        onCancel={() => {
          if (parkedSaleSaving) return;
          setParkedSaleAuthorizationOpen(false);
          setParkedSaleError("");
          queueMicrotask(() => searchInputRef.current?.focus());
        }}
        onConfirm={(authorizations) => void parkCurrentSale(authorizations)}
      />

      {ticketCancellationMode && (
        <SaleTicketCancellationDialog
          token={session.accessToken}
          locale={locale}
          currentUsername={session.username}
          authorization={ticketCancellationAuthorization ?? {
            mode: "DELEGATED",
            requireUsername: true,
            requirePassword: true,
          }}
          terminalContext={terminalContext}
          mode={ticketCancellationMode}
          canStartInvoiceCancellation={lines.length === 0 && !previousTicketImportBatch}
          onClose={() => setTicketCancellationMode(null)}
          onFiscalMutation={() => setVerifactuRefreshSignal((current) => current + 1)}
          onInvoiceAddToCart={addReturnLinesToCart}
        />
      )}

      {ticketInvoiceOpen && (
        <SaleTicketInvoiceDialog
          token={session.accessToken}
          locale={locale}
          terminalContext={terminalContext}
          currentUsername={session.username}
          authorization={ticketInvoiceAuthorization ?? {
            mode: "DELEGATED",
            requireUsername: true,
            requirePassword: true,
          }}
          onClose={() => setTicketInvoiceOpen(false)}
          onFiscalMutation={() => setVerifactuRefreshSignal((current) => current + 1)}
        />
      )}

      {ticketReturnOpen && (
        <TicketReturnDialog
          token={session.accessToken}
          locale={locale}
          existingCartLines={saleReturnCartReservations(lines)}
          onClose={() => setTicketReturnOpen(false)}
          onAddToCart={addReturnLinesToCart}
        />
      )}

      {giftReceiptOpen && (
        <GiftReceiptDialog
          token={session.accessToken}
          locale={locale}
          terminalContext={terminalContext}
          onClose={() => setGiftReceiptOpen(false)}
        />
      )}

      {serialNumberOpen && selectedLine && (
        <SaleSerialNumberDialog
          locale={locale}
          productName={selectedLine.product.name ?? selectedLine.product.code ?? ""}
          quantity={selectedLine.quantity}
          initialSerialNumbers={selectedLine.serialNumbers ?? []}
          onCancel={() => setSerialNumberOpen(false)}
          onConfirm={(serialNumbers) => {
            setLines((current) => updateSaleLineSerialNumbers(
              current,
              saleCartLineIdentity(selectedLine),
              serialNumbers,
            ));
            setSerialNumberOpen(false);
            setShortcutStatus(t("sale.serialNumber.saved"));
          }}
        />
      )}

      {pendingInactiveProduct && (
        <SaleActionDialog
          title={t("sale.inactiveProduct.title")}
          closeLabel={t("sale.dialog.close")}
          onClose={cancelInactiveProduct}
          onConfirm={confirmInactiveProduct}
        >
          <p>{saleMainMessage(t, "sale.inactiveProduct.warning", {
            name: pendingInactiveProduct.name ?? t("sale.main.unnamedProduct")
          })}</p>
          <div className="sale-action-buttons">
            <button type="button" onClick={cancelInactiveProduct}>{t("common.cancel")}</button>
            <button type="button" autoFocus onClick={confirmInactiveProduct}>{t("sale.inactiveProduct.continue")}</button>
          </div>
        </SaleActionDialog>
      )}

      {cashSessionState === "REQUIRED" && terminalContext.terminalId && session.accessToken && (
        <SaleCashSessionDialog
          locale={locale}
          currentUsername={session.username}
          mode="OPEN"
          terminalId={terminalContext.terminalId}
          token={session.accessToken}
          onExitSales={onBack}
          onOpened={() => setCashSessionState("OPEN")}
        />
      )}

      {(cashSessionState === "LOADING" || cashSessionState === "ERROR") && (
        <div className="sale-cash-session-overlay" role="presentation">
          <section
            className="sale-cash-session-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={cashSessionState === "LOADING" ? cashSessionCopy.loading : cashSessionCopy.unavailable}
          >
            <header>
              <h2>{cashSessionState === "LOADING" ? cashSessionCopy.loading : cashSessionCopy.unavailable}</h2>
            </header>
            {cashSessionState === "ERROR" && <p className="sale-cash-session-error" role="alert">{cashSessionError}</p>}
            <footer>
              <button type="button" className="secondary" onClick={onBack}>{cashSessionCopy.exit}</button>
              {cashSessionState === "ERROR" && (
                <button type="button" onClick={() => void prepareSalesCashSession()}>{cashSessionCopy.retry}</button>
              )}
            </footer>
          </section>
        </div>
      )}

      {cashSessionCloseOpen && cashSessionCloseFlow && cashSessionReady
        && terminalContext.terminalId && session.accessToken && (
        <SaleCashSessionDialog
          locale={locale}
          currentUsername={session.username}
          mode="CLOSE"
          terminalId={terminalContext.terminalId}
          token={session.accessToken}
          authorization={cashSessionCloseAuthorization ?? {
            mode: "CURRENT_PASSWORD",
            requireUsername: false,
            requirePassword: true,
          }}
          closeFlow={cashSessionCloseFlow}
          onCloseFlowChange={updateCashSessionCloseFlow}
          onCancel={() => {
            if (cashSessionCloseFlow?.phase !== "READY") return;
            setCashSessionCloseOpen(false);
            clearPersistedCashSessionClose();
          }}
          onClosed={() => {
            clearPersistedCashSessionClose();
            onBack?.();
          }}
        />
      )}
    </main>
  );
}

function SaleActionDialog({
  title,
  closeLabel,
  children,
  onClose,
  onKeyDown,
  onConfirm,
  initialFocusRef,
  className = "",
  wide = false
}: {
  title: string;
  closeLabel: string;
  children: React.ReactNode;
  onClose: () => void;
  onKeyDown?: (event: ReactKeyboardEvent<HTMLElement>) => void;
  onConfirm?: () => void;
  initialFocusRef?: RefObject<HTMLElement | null>;
  className?: string;
  wide?: boolean;
}) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    let active = true;
    queueMicrotask(() => {
      if (active) initialFocusRef?.current?.focus();
    });
    return () => {
      active = false;
      deactivate();
    };
  }, [initialFocusRef]);

  function handleKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    onKeyDown?.(event);
    if (event.defaultPrevented || event.repeat) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
    } else if (event.key === "Enter" && onConfirm) {
      event.preventDefault();
      event.stopPropagation();
      onConfirm();
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section ref={dialogRef} className={`sale-action-dialog${wide ? " wide" : ""}${className ? ` ${className}` : ""}`} role="dialog" aria-modal="true" aria-label={title} onKeyDown={handleKeyDown}>
        <header><h2>{title}</h2><button type="button" aria-label={closeLabel} onClick={onClose}>x</button></header>
        {children}
      </section>
    </div>
  );
}

function formatSaleAmount(value: number | string | null | undefined) {
  return Number(value ?? 0).toLocaleString("es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

function formatSalePercentage(
  value: number | string | null | undefined,
  locale: LocaleCode,
) {
  const numberLocale = locale === "es" ? "es-ES" : locale === "zh" ? "zh-CN" : "en-US";
  return Number(value ?? 0).toLocaleString(numberLocale, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

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
  KeyboardSaleCommandBar,
  TouchSaleActionPanel,
  type SaleCommandLabels
} from "./SaleCommandPresentation";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import {
  printConfirmedTicketAutomatically,
  retryConfirmedTicketPrint,
  type ConfirmedTicketPrintSnapshot,
  type TicketPrintOutcome,
} from "../sale/ticketPrinting";
import type { TicketPrintUiStatus } from "./CashPaymentResultDialog";
import { CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";
import { addLocalDays, type PendingSaleDraft } from "../sale/customerReceivables";
import {
  clearPendingSaleRecovery,
  loadPendingSaleRecovery,
  pendingSaleRecoveryRequiresAttention,
  savePendingSaleRecovery,
  type PendingSaleRecoveryEnvelope,
  type PendingSaleRecoveryLoadResult,
} from "../sale/pendingSaleRecovery";
import { retryPrintSucceeded } from "../sale/printRetry";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { ParkedSalesDialog, type OpenedParkedSale } from "./ParkedSalesDialog";
import { TicketManagementDialog } from "./TicketManagementDialog";
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
import { StockSalesHistoryPanel } from "./StockSalesHistoryPanel";
import { SaleCashDrawerAuthorizationDialog } from "./SaleCashDrawerAuthorizationDialog";
import { ProductCreateDialog, type ProductCreateEditProduct } from "./ProductCreateDialog";
import { SaleCashSessionDialog } from "./SaleCashSessionDialog";
import { SaleSerialNumberDialog } from "./SaleSerialNumberDialog";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition, TableLayout } from "./tableLayoutPreferences";
import { TicketReturnDialog } from "./TicketReturnDialog";
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
import { prepareCashSessionForSales } from "../sale/cashSessions";
import { userCanManageStockProducts } from "./stockAccess";

export type SaleProduct = {
  id: string;
  imageId?: string | null;
  active?: boolean | null;
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
  taxId: string;
  taxesIncluded: boolean;
  taxRegime: "IVA" | "IGIC";
  taxPercentage: number | string;
  rate?: string | null;
  packageQuantity?: number | string | null;
};

export type SaleLine = {
  product: SaleProduct;
  quantity: number;
  openUnitPrice?: number;
  serialNumbers?: string[];
  // Operator-entered discount. Member benefit is kept separately.
  discountPercent: number;
  memberDiscountPercent?: number;
};

export type SaleCustomer = {
  id: string;
  clientId?: string | null;
  fiscalName?: string | null;
  documentNumber?: string | null;
  activeMember?: boolean;
  memberCategoryName?: string | null;
  memberDiscountPercent?: number | string | null;
  creditEnabled?: boolean;
  creditLimit?: number | string | null;
  paymentTermDays?: number | null;
  creditBlocked?: boolean;
  blockOnOverdue?: boolean;
  outstandingDebt?: number | string | null;
  overdueDebt?: number | string | null;
  availableCredit?: number | string | null;
};

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
  productId: string;
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
  | "name"
  | "quantity"
  | "salePrice"
  | "discount"
  | "specialPrice"
  | "total";

export const saleCartTableKey = "sale.cart";
export const saleCartImageColumnWidth = 58;
export const saleCartColumnDefinitions = [
  { key: "image", defaultWidth: saleCartImageColumnWidth },
  { key: "code", defaultWidth: 112 },
  { key: "name", defaultWidth: 260 },
  { key: "quantity", defaultWidth: 86 },
  { key: "salePrice", defaultWidth: 112 },
  { key: "discount", defaultWidth: 116 },
  { key: "specialPrice", defaultWidth: 154 },
  { key: "total", defaultWidth: 116 },
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
  if (!Number.isFinite(total) || total < 0 || quote.lineBreakdown.length === 0) return false;
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
  return saleQuickOperand(value);
}

export function addSaleLine(
  lines: SaleLine[],
  product: SaleProduct,
  openUnitPrice?: number,
  quantity = 1,
) {
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 9999) {
    throw new Error("invalid_quantity");
  }
  const existing = lines.find((line) => line.product.id === product.id);
  if (!existing) {
    return [...lines, {
      product,
      quantity,
      discountPercent: 0,
      ...(openUnitPrice != null ? { openUnitPrice } : {}),
    }];
  }
  return lines.map((line) => line.product.id === product.id
    ? { ...line, quantity: Math.min(9999, line.quantity + quantity) }
    : line);
}

export function updateSaleLineQuantity(lines: SaleLine[], productId: string, quantity: number) {
  if (!Number.isInteger(quantity) || quantity === 0 || quantity < -1 || quantity > 9999) {
    throw new Error("invalid_quantity");
  }
  return lines.map((line) => line.product.id === productId ? { ...line, quantity } : line);
}

export function updateSaleLineDiscount(lines: SaleLine[], productId: string, discountPercent: number) {
  const hasMoreThanTwoDecimals = Math.abs(discountPercent * 100 - Math.round(discountPercent * 100)) > 1e-9;
  if (!Number.isFinite(discountPercent) || discountPercent < 0 || discountPercent > 100 || hasMoreThanTwoDecimals) {
    throw new Error("invalid_discount");
  }
  const line = lines.find((candidate) => candidate.product.id === productId);
  if (discountPercent > 0 && line && saleProductBlocksManualDiscount(line.product)) {
    throw new Error("discount_blocked");
  }
  return lines.map((line) => line.product.id === productId ? { ...line, discountPercent } : line);
}

export function removeSaleLine(lines: SaleLine[], productId: string) {
  return lines.filter((line) => line.product.id !== productId);
}

export function selectedProductAfterRemoval(lines: SaleLine[], productId: string) {
  const removedIndex = lines.findIndex((line) => line.product.id === productId);
  const remaining = removeSaleLine(lines, productId);
  if (remaining.length === 0) return null;
  const nextIndex = Math.min(Math.max(removedIndex, 0), remaining.length - 1);
  return remaining[nextIndex].product.id;
}

export function saleLineSelectionAfterArrow(
  lines: SaleLine[],
  selectedId: string | null,
  key: "ArrowUp" | "ArrowDown",
) {
  if (lines.length === 0) return null;
  const selectedIndex = lines.findIndex((line) => line.product.id === selectedId);
  if (selectedIndex < 0) {
    return key === "ArrowDown" ? lines[0].product.id : lines[lines.length - 1].product.id;
  }
  const offset = key === "ArrowDown" ? 1 : -1;
  const nextIndex = Math.min(Math.max(selectedIndex + offset, 0), lines.length - 1);
  return lines[nextIndex].product.id;
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
  return saleLineUnitPrice(line, activeMember) * line.quantity * (1 - effectiveSaleLineDiscount(line) / 100);
}

export function updateSaleLineSerialNumbers(
  lines: SaleLine[],
  productId: string,
  serialNumbers: string[],
) {
  return lines.map((line) => line.product.id === productId
    ? { ...line, serialNumbers: [...serialNumbers] }
    : line);
}

export function saleLineUnitPrice(line: SaleLine, activeMember = false) {
  return line.openUnitPrice ?? effectiveSaleProductPrice(line.product, activeMember);
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
  return Math.max(line.discountPercent, line.memberDiscountPercent ?? 0);
}

export function applyMemberDiscounts(lines: SaleLine[], customer: SaleCustomer | null) {
  const customerDiscount = customer?.activeMember ? Number(customer.memberDiscountPercent ?? 0) : 0;
  return lines.map((line) => ({
    ...line,
    memberDiscountPercent: customerDiscount
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
    selectedProductId: null,
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
    };
  }
  return {
    ticketNumber: printTicket.documentNumber,
    totalCents: summary.totalCents,
    method: summary.kind === "CARD" ? "Tarjeta" : summary.kind === "VOUCHER" ? "Vale" : "Mixto",
    printTicket,
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
): PendingSaleDraft {
  return {
    checkoutId, warehouseId, type: "ALBARAN_VENTA", date: addLocalDays(now, 0),
    customerId: customer.id, dueDate: addLocalDays(now, Math.max(0, customer.paymentTermDays ?? 30)), globalDiscount: "0.00",
    lines: lines.map((line) => ({
      productId: line.product.id, quantity: line.quantity,
      code: line.product.code ?? line.product.barcode ?? line.product.id,
      name: line.product.name ?? line.product.code ?? "Producto", rate: line.product.rate ?? null,
      price: saleLineUnitPrice(line, customer.activeMember === true).toFixed(2),
      // Membership is backend-authoritative from customerId. Only the operator's manual discount crosses the boundary.
      discount: line.discountPercent.toFixed(2), ...saleProductFiscalSnapshot(line.product),
      serialNumbers: line.serialNumbers ?? [],
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
    cashDrawer: t("sale.shortcut.cashDrawer"),
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
    operations: t("sale.main.management")
  };
  const [products, setProducts] = useState<SaleProduct[]>([]);
  const [allowInactiveProductSales, setAllowInactiveProductSales] = useState(false);
  const [pendingInactiveProduct, setPendingInactiveProduct] = useState<SaleProduct | null>(null);
  const [pendingOpenPriceProduct, setPendingOpenPriceProduct] = useState<SaleProduct | null>(null);
  const [pendingOpenPriceQuantity, setPendingOpenPriceQuantity] = useState(1);
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
  const [selectedProductId, setSelectedProductId] = useState<string | null>(null);
  const [actionDialog, setActionDialog] = useState<"quantity" | "discount" | "discountAuthorization" | "customer" | "remove" | null>(null);
  const [quantityInput, setQuantityInput] = useState("1");
  const [discountInput, setDiscountInput] = useState("0");
  const [discountAuthorizationToken, setDiscountAuthorizationToken] = useState("");
  const [discountAuthorizationPercent, setDiscountAuthorizationPercent] = useState(0);
  const [managerName, setManagerName] = useState("");
  const [managerPassword, setManagerPassword] = useState("");
  const [managerAuthorizationBusy, setManagerAuthorizationBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [customerQuery, setCustomerQuery] = useState("");
  const [customerLoading, setCustomerLoading] = useState(false);
  const [customerError, setCustomerError] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState<SaleCustomer | null>(null);
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
  const [cashSessionCloseOpen, setCashSessionCloseOpen] = useState(false);
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
  const [ticketManagementOpen, setTicketManagementOpen] = useState(false);
  const [ticketReturnOpen, setTicketReturnOpen] = useState(false);
  const [serialNumberOpen, setSerialNumberOpen] = useState(false);
  const [productSearchOpen, setProductSearchOpen] = useState(false);
  const [productSearchQuery, setProductSearchQuery] = useState("");
  const [productSearchSelectedId, setProductSearchSelectedId] = useState("");
  const [productSearchPurpose, setProductSearchPurpose] = useState<"ADD" | "HISTORY">("ADD");
  const [productInformationProduct, setProductInformationProduct] = useState<SaleProduct | null>(null);
  const [consultationMode, setConsultationMode] = useState<"PRICE" | "STOCK" | null>(null);
  const [calculatorOpen, setCalculatorOpen] = useState(false);
  const [salesHistoryProduct, setSalesHistoryProduct] = useState<SaleProduct | null>(null);
  const [verifactuRefreshSignal, setVerifactuRefreshSignal] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const customerSearchInputRef = useRef<HTMLInputElement>(null);
  const quantityInputRef = useRef<HTMLInputElement>(null);
  const discountInputRef = useRef<HTMLInputElement>(null);
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
  const selectableProducts = useMemo(
    () => saleSelectableProducts(products, allowInactiveProductSales),
    [allowInactiveProductSales, products]
  );
  const customerResults = useMemo(() => filterSaleCustomers(customers, customerQuery), [customers, customerQuery]);
  const selectedLine = lines.find((line) => line.product.id === selectedProductId);
  const activeMember = selectedCustomer?.activeMember === true;
  const visibleCartColumns = visibleSaleCartColumns(cartTableLayout.layout);
  const cartTableWidth = visibleCartColumns.reduce((totalWidth, column) => totalWidth + column.width, 0);
  const currentSaleRequest = cashSaleRequest();
  const currentSaleRequestKey = JSON.stringify(currentSaleRequest);
  const total = saleTotal(lines, activeMember);
  const authoritativeQuoteReady = authoritativeQuoteRequestKey === currentSaleRequestKey
    && isCompleteAuthoritativeQuote(authoritativeQuote);
  const authoritativeTotal = authoritativeQuoteReady ? Number(authoritativeQuote.total) : total;
  const authoritativeLineBreakdown = authoritativeQuoteReady ? authoritativeQuote.lineBreakdown : null;
  const displayedTotal = saleDisplayedTotal(authoritativeTotal,paymentLocked,lines.length,reservedPaymentTotalCents);
  const paymentActionsDisabled = lines.length === 0 || authoritativeTotal <= 0 || cashOpening
    || authoritativeQuoteLoading || !authoritativeQuoteReady || Boolean(authoritativeQuoteError);
  const canApplyManualDiscount = hasPermission(session, "APLICAR_DESCUENTO");
  const canOpenCustomerReceivables = Boolean(onOpenCustomerReceivables)
    && hasPermission(session, "CUSTOMER_RECEIVABLES_READ");
  const userDiscountLimit = session.permissions.includes("ADMIN") ? 100 : Number(session.maxDiscountPercent ?? 0);
  const cashSessionReady = cashSessionState === "OPEN";
  const cashSessionCopy = locale === "en"
    ? {
        close: "Close register",
        unavailable: "Cash register unavailable",
        retry: "Retry",
        exit: "Exit Sales",
        loading: "Preparing cash register…",
        error: "The cash session could not be prepared.",
      }
    : locale === "zh"
      ? {
          close: "关闭收银会话",
          unavailable: "收银会话不可用",
          retry: "重试",
          exit: "退出销售",
          loading: "正在准备收银会话…",
          error: "无法准备收银会话。",
        }
      : {
          close: "Cerrar caja",
          unavailable: "Caja no disponible",
          retry: "Reintentar",
          exit: "Salir de Ventas",
          loading: "Preparando caja…",
          error: "No se pudo preparar la sesión de caja.",
        };

  function cartColumnLabel(column: SaleCartColumnKey) {
    return t(`sale.cart.column.${column}`);
  }

  function renderCartRow(localLine: SaleLine, authoritativeLine?: AuthoritativeSaleLine) {
    const product = localLine.product;
    const name = authoritativeLine?.name ?? product.name ?? t("sale.main.unnamedProduct");
    const code = authoritativeLine?.code
      ?? product.code
      ?? product.barcode
      ?? t("sale.main.missingCode");
    const quantity = finiteAmount(authoritativeLine?.quantity ?? localLine.quantity);
    const appliedUnitPrice = finiteAmount(
      authoritativeLine?.baseUnitPrice ?? saleLineUnitPrice(localLine, activeMember),
    );
    const catalogSalePrice = authoritativeLine
      ? finiteAmount(authoritativeLine.normalUnitPrice)
      : finiteAmount(product.salePrice);
    const displayedSalePrice = catalogSalePrice === 0 && localLine.openUnitPrice != null
      ? appliedUnitPrice
      : catalogSalePrice;
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
    const selectionLabel = `${name} ${quantity} x ${formatSaleAmount(appliedUnitPrice)} ${discountText} ${formatSaleAmount(totalAmount)}`;
    const selected = selectedProductId === product.id;

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
                setSelectedProductId(product.id);
              }}
            >
              <strong className="product-name-text">{name}</strong>
              {(localLine.serialNumbers ?? []).map((serial) => (
                <small className="sale-line-serial" key={serial}>S/N: {serial}</small>
              ))}
            </button>
          </td>
        );
      }
      if (column === "quantity") {
        return <td className="sale-cart-number sale-cart-quantity" data-column-key={column} key={column}>{quantity}</td>;
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
        className={`sale-cart-row${selected ? " selected" : ""}`}
        key={product.id}
        onClick={() => setSelectedProductId(product.id)}
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
    try {
      const readiness = await prepareCashSessionForSales(
        terminalContext.terminalId,
        session.accessToken,
      );
      setCashSessionState(readiness.open ? "OPEN" : "REQUIRED");
    } catch (failure) {
      setCashSessionError(failure instanceof Error ? failure.message : cashSessionCopy.error);
      setCashSessionState("ERROR");
    }
  }

  useEffect(() => {
    void prepareSalesCashSession();
  }, [session.accessToken, terminalContext.terminalId]);

  function invalidateCashOpening() {
    cashOpeningRef.current.generation += 1;
    cashOpeningRef.current.current = false;
    setCashOpening(false);
  }

  function updateMatchingPrintOutcome(documentId: string, outcome: TicketPrintOutcome) {
    setCashResult((current) => updateCashResultPrintOutcome(current, documentId, outcome));
  }

  function startAutomaticTicketPrint(snapshot: ConfirmedTicketPrintSnapshot) {
    void printConfirmedTicketAutomatically(snapshot, terminalContext)
      .then((outcome) => updateMatchingPrintOutcome(snapshot.documentId, outcome));
  }

  function retryTicketPrint() {
    const snapshot = cashResult?.printTicket;
    if (!snapshot) return;
    setCashResult((current) => current?.printTicket?.documentId === snapshot.documentId
      ? { ...current, printStatus: "PRINTING", printTechnicalMessage: undefined }
      : current);
    void retryConfirmedTicketPrint(snapshot, terminalContext)
      .then((outcome) => updateMatchingPrintOutcome(snapshot.documentId, outcome));
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
    setLines((current) => applyMemberDiscounts(
      addSaleLine(current, product, openUnitPrice, quantity),
      selectedCustomer,
    ));
    setSelectedProductId(product.id);
    setQuery("");
    setShortcutStatus("");
    searchInputRef.current?.focus();
  }

  function requestPriceOrAddProduct(product: SaleProduct) {
    const packageQuantity = Number(product.packageQuantity ?? 1);
    const quantity = nextScanMode === "PACKAGE"
      ? nextScanQuantity * (Number.isFinite(packageQuantity) && packageQuantity > 0 ? packageQuantity : 1)
      : nextScanQuantity;
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 9999) {
      setShortcutStatus("La cantidad resultante del paquete no es válida");
      return;
    }
    const existingLine = lines.find((line) => line.product.id === product.id);
    if (saleProductRequiresOpenPrice(product) && !existingLine) {
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
    if (actionDialog === "remove") removeConfirmButtonRef.current?.focus();
  }, [actionDialog]);

  function requestAddProduct(product: SaleProduct) {
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
    if (!selectedLine) return;
    setQuantityInput(String(selectedLine.quantity));
    setActionError("");
    setActionDialog("quantity");
  }

  function openDiscountDialog() {
    if (!selectedLine || !canApplyManualDiscount || saleProductBlocksManualDiscount(selectedLine.product)) return;
    setDiscountInput(String(selectedLine.discountPercent));
    setActionError("");
    setActionDialog("discount");
  }

  function saveQuantity() {
    if (!selectedProductId) return;
    try {
      const nextLines = updateSaleLineQuantity(lines, selectedProductId, Number(quantityInput));
      setLines(nextLines);
      setActionDialog(null);
    } catch {
      setActionError(t("sale.quantity.invalid"));
    }
  }

  function saveDiscount() {
    if (!selectedProductId) return;
    try {
      const discount = Number(discountInput);
      updateSaleLineDiscount(lines, selectedProductId, discount);
      if (discount > userDiscountLimit) {
        setDiscountAuthorizationPercent(discount);
        setManagerName("");
        setManagerPassword("");
        setActionError("");
        setActionDialog("discountAuthorization");
        return;
      }
      setLines((current) => updateSaleLineDiscount(current, selectedProductId, discount));
      setDiscountAuthorizationToken("");
      setActionDialog(null);
    } catch (error) {
      setActionError(error instanceof Error && error.message === "discount_blocked"
        ? t("sale.discountBlocked")
        : t("sale.discount.invalid"));
    }
  }

  async function authorizeDiscount() {
    if (!selectedProductId || managerAuthorizationBusy) return;
    setManagerAuthorizationBusy(true);
    setActionError("");
    try {
      const authorization = await apiRequest<{ token: string }>("/pos/discount-authorizations", {
        token: session.accessToken,
        body: {
          managerName,
          password: managerPassword,
          requestedPercent: discountAuthorizationPercent
        }
      });
      setDiscountAuthorizationToken(authorization.token);
      setLines((current) => updateSaleLineDiscount(current, selectedProductId, discountAuthorizationPercent));
      setManagerPassword("");
      setActionDialog(null);
    } catch (error) {
      setManagerPassword("");
      setActionError(t("sale.discountAuthorization.error"));
    } finally {
      setManagerAuthorizationBusy(false);
    }
  }

  function openCustomerDialog(continuePending = false) {
    setPendingCustomerContinuation(continuePending);
    setActionDialog("customer");
    setCustomerQuery("");
    setCustomerLoading(true);
    setCustomerError(false);
    apiRequest<SaleCustomer[]>("/customers/sale-options", { token: session.accessToken })
      .then(setCustomers)
      .catch(() => setCustomerError(true))
      .finally(() => setCustomerLoading(false));
  }

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
      setPendingDraft(pendingSaleDraftForCustomer(lines, customer, warehouse.id, now, newCheckoutId()));
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
    if (!selectedProductId || !selectedLine) return;
    const removedLine = selectedLine;
    const fullTicketClear = lines.length === 1;
    const saleOperationId = deletionControl.currentSaleOperationId();
    const deletionOperationId = deletionControl.newDeletionOperationId();
    setLines((current) => {
      const nextSelectedProductId = selectedProductAfterRemoval(current, selectedProductId);
      const remaining = removeSaleLine(current, selectedProductId);
      setSelectedProductId(nextSelectedProductId);
      return remaining;
    });
    setActionDialog(null);
    void deletionControl.enqueue(
      () => apiRequest("/sale-line-deletions", {
        token: session.accessToken,
        body: {
          saleOperationId,
          deletionOperationId,
          fullTicketClear,
          lines: [{
            productId: removedLine.product.id,
            code: removedLine.product.code ?? "",
            name: removedLine.product.name ?? "",
            quantity: removedLine.quantity,
            unitPrice: saleLineUnitPrice(removedLine, activeMember),
          }],
        },
      }),
      (error: unknown) => {
        // Best effort: a control-event outage must never block the active sale.
        console.warn("sale_line_deletion_not_recorded", error);
      },
    );
    if (fullTicketClear) deletionControl.reset("CART_EMPTIED");
  }

  function handleRemoveLineKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat || (event.key !== "Enter" && event.key !== "Escape")) return;
    event.preventDefault();
    event.stopPropagation();
    if (event.key === "Enter") confirmRemoveLine();
    else setActionDialog(null);
  }

  function submitSearch() {
    const selected = selectSaleProduct(selectableProducts, query);
    if (selected) {
      requestAddProduct(selected);
      return;
    }
    if (!query.trim()) return;
    setProductSearchPurpose("ADD");
    setProductSearchQuery(query);
    setProductSearchSelectedId("");
    setProductSearchOpen(true);
  }

  function openProductSalesHistory() {
    if (paymentLocked) return;
    const product = selectSaleProduct(selectableProducts, query) ?? selectedLine?.product;
    if (product) {
      setSalesHistoryProduct(product);
      return;
    }
    setProductSearchPurpose("HISTORY");
    setProductSearchQuery(query);
    setProductSearchSelectedId("");
    setProductSearchOpen(true);
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
    setProductSearchOpen(false);
    requestAddProduct(product);
  }

  function clearQuickEntry(message = "") {
    setQuery("");
    setShortcutStatus(message);
    queueMicrotask(() => searchInputRef.current?.focus());
  }

  function quickOperand() {
    return saleQuickOperand(query);
  }

  function applyPauseQuantity() {
    if (!selectedLine || paymentLocked) return;
    const quantity = salePauseQuantity(query);
    if (quantity == null) {
      setShortcutStatus("Introduce una cantidad y pulsa Pausa");
      return;
    }
    if (quantity === 0) {
      confirmRemoveLine();
      clearQuickEntry();
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, selectedLine.product.id, quantity));
    clearQuickEntry(quantity === -1
      ? "Devolución manual -1 aplicada; se registrará como alerta de control"
      : "");
  }

  function addToSelectedQuantity() {
    if (!selectedLine || paymentLocked) return;
    const operand = quickOperand();
    if (operand == null || operand < 1) {
      setShortcutStatus("Introduce la cantidad que quieres sumar");
      return;
    }
    const result = selectedLine.quantity + operand;
    if (result > 9999) {
      setShortcutStatus("La cantidad no puede superar 9999");
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, selectedLine.product.id, result));
    clearQuickEntry();
  }

  function subtractFromSelectedQuantity() {
    if (!selectedLine || paymentLocked) return;
    const operand = quickOperand();
    if (operand == null || operand < 1) {
      setShortcutStatus("Introduce la cantidad que quieres restar");
      return;
    }
    const result = selectedLine.quantity - operand;
    if (result < 0) {
      setShortcutStatus("Ctrl+- no permite dejar una cantidad negativa");
      return;
    }
    if (result === 0) {
      confirmRemoveLine();
      clearQuickEntry();
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, selectedLine.product.id, result));
    clearQuickEntry();
  }

  function prepareNextProductQuantity(asPackage: boolean) {
    if (paymentLocked) return;
    const operand = quickOperand();
    if (operand == null || operand < 1) {
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
    if (!selectedLine || paymentLocked || !canApplyManualDiscount
      || saleProductBlocksManualDiscount(selectedLine.product)) return;
    const discount = quickOperand();
    if (discount == null || discount < 0 || discount > 100) {
      setShortcutStatus("Introduce un descuento entre 0 y 100");
      return;
    }
    setDiscountInput(String(discount));
    clearQuickEntry();
    if (discount > userDiscountLimit) {
      setDiscountAuthorizationPercent(discount);
      setManagerName("");
      setManagerPassword("");
      setActionDialog("discountAuthorization");
      return;
    }
    setLines((current) => updateSaleLineDiscount(current, selectedLine.product.id, discount));
    setDiscountAuthorizationToken("");
  }

  function applyQuickGlobalDiscount() {
    if (lines.length === 0 || paymentLocked || !canApplyManualDiscount) return;
    const discount = quickOperand();
    if (discount == null || discount < 0 || discount > 100) {
      setShortcutStatus("Introduce un descuento entre 0 y 100");
      return;
    }
    if (discount > userDiscountLimit) {
      setShortcutStatus("El descuento global necesita autorización");
      return;
    }
    try {
      setLines((current) => current.reduce(
        (updated, line) => updateSaleLineDiscount(updated, line.product.id, discount),
        current,
      ));
      setDiscountAuthorizationToken("");
      clearQuickEntry("Descuento aplicado a toda la compra");
    } catch (error) {
      setShortcutStatus(error instanceof Error && error.message === "discount_blocked"
        ? t("sale.discountBlocked")
        : "No se pudo aplicar el descuento global");
    }
  }

  function applyDesiredLinePrice() {
    if (!selectedLine || paymentLocked || !canApplyManualDiscount
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
    if (discount > userDiscountLimit) {
      setDiscountAuthorizationPercent(discount);
      setManagerName("");
      setManagerPassword("");
      setActionDialog("discountAuthorization");
      return;
    }
    setLines((current) => updateSaleLineDiscount(current, selectedLine.product.id, discount));
    setDiscountAuthorizationToken("");
  }

  function cashSaleRequest() {
    return {
      customerId: selectedCustomer?.id ?? null,
      lines: lines.map((line) => ({
        productId: line.product.id,
        quantity: line.quantity,
        discount: line.discountPercent,
        ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {}),
        ...(line.openUnitPrice != null ? { openUnitPrice: line.openUnitPrice } : {})
      })),
      ...(discountAuthorizationToken ? { discountAuthorizationToken } : {}),
      ...(checkoutDiscountCents > 0 ? { checkoutDiscountAmount: checkoutDiscountCents / 100 } : {})
    };
  }

  function clearCurrentSale() {
    setLines([]);
    setSelectedProductId(null);
    setSelectedCustomer(null);
    setQuery("");
    setDiscountAuthorizationToken("");
    setCheckoutDiscountCents(0);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setShortcutStatus("");
    deletionControl.reset("CART_EMPTIED");
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
    if (hasPermission(session, "ABRIR_CAJON")) {
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
    if (hasPermission(session, "GESTION_PRODUCTO")) {
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
        product,
        quantity: Number(line.cantidad),
        discountPercent: Number(line.descuento),
        serialNumbers: line.serialNumbers ?? [],
        ...(Number(product.salePrice) === 0 && Number(line.precioUnitario) > 0
          ? { openUnitPrice: Number(line.precioUnitario) }
          : {})
      } satisfies SaleLine];
    });
    setLines(recoveredLines);
    setSelectedProductId(recoveredLines[0]?.product.id ?? null);
    setDiscountAuthorizationToken("");
    setCheckoutDiscountCents(0);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
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
    const maximumDiscount = recoveredLines.reduce((maximum, line) => Math.max(maximum, line.discountPercent), 0);
    if (maximumDiscount > userDiscountLimit && recoveredLines[0]) {
      setDiscountAuthorizationPercent(maximumDiscount);
      setActionDialog("discountAuthorization");
    }
  }

  useEffect(() => {
    const generation = ++quoteGenerationRef.current;
    if (lines.length === 0) {
      setAuthoritativeQuote(null);
      setAuthoritativeQuoteRequestKey("");
      setAuthoritativeQuoteLoading(false);
      setAuthoritativeQuoteError("");
      setDiscountAuthorizationToken("");
      setCheckoutDiscountCents(0);
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
  }, [currentSaleRequestKey, session.accessToken]);

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
          sale: cashSaleRequest(),
          received: (receivedCents / 100).toFixed(2),
          quotedTotal: (cashQuoteCents / 100).toFixed(2)
        }
      });
      const confirmedResult = cashPaymentResultForAutomaticPrinting(result, cashQuoteCents, receivedCents);
      const transition = cashPaymentSuccessTransition(confirmedResult);
      setCashDialogOpen(transition.cashDialogOpen);
      setLines(transition.lines);
      setSelectedProductId(transition.selectedProductId);
      setSelectedCustomer(transition.selectedCustomer);
      setCashResult(transition.cashResult);
      setQuery(transition.query);
      setNextScanQuantity(1);
      setNextScanMode("UNIT");
      deletionControl.reset("SALE_FINALIZED");
      setVerifactuRefreshSignal((current) => current + 1);
      startAutomaticTicketPrint(result.printTicket);
      } catch (error) {
      const transition = cashPaymentErrorTransition(
        { cashDialogOpen, lines, selectedProductId, selectedCustomer, query },
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
        const response = await apiRequest<CardPaymentResponse>("/pos/card/charge", { token: session.accessToken, body: buildCardChargeBody(checkoutId, cashSaleRequest(), quotedCents) });
        const outcome = resolveCardPaymentOutcome(response, quotedCents);
        setCardStatus(outcome.status); setCardMessage(outcome.message);
        if (outcome.clearSale && outcome.result) {
          setCardDialogOpen(false); setLines([]); setSelectedProductId(null); setSelectedCustomer(null); setQuery(""); setCashResult(outcome.result);
          setNextScanQuantity(1);
          setNextScanMode("UNIT");
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

  saleShortcutHandlerRef.current = (event: KeyboardEvent) => {
      if (!cashSessionReady) return;
      if (event.repeat || document.querySelector('[role="dialog"][aria-modal="true"]')) return;
      if (pendingRecoveryBlocked) return;
      if (isSalesDocumentShortcut(event)) {
        if (!paymentLocked && onOpenSalesDocumentWindow) {
          event.preventDefault();
          onOpenSalesDocumentWindow();
        }
        return;
      }

      if (event.ctrlKey && !event.altKey && !event.metaKey) {
        const lowerKey = event.key.toLocaleLowerCase();
        if (lowerKey === "g") {
          if (!paymentLocked) {
            event.preventDefault();
            setParkedSalesOpen(true);
          }
          return;
        }
        if (lowerKey === "n") {
          if (selectedLine && !paymentLocked) {
            event.preventDefault();
            setSerialNumberOpen(true);
          }
          return;
        }
        if (event.key === "+") {
          event.preventDefault();
          addToSelectedQuantity();
          return;
        }
        if (event.key === "-") {
          event.preventDefault();
          subtractFromSelectedQuantity();
          return;
        }
        if (event.key === "/") {
          event.preventDefault();
          applyQuickGlobalDiscount();
          return;
        }
        if (event.key === "F11") {
          if (paymentHydrated && !paymentLocked) {
            event.preventDefault();
            setTicketManagementOpen(true);
          }
          return;
        }
        // Ctrl+C/V/X/Z/Y and every other standard editing shortcut remain native.
        return;
      }

      if (saleShortcutTargetIsEditable(event.target) && event.target !== searchInputRef.current) {
        return;
      }
      if (event.target === searchInputRef.current
        && (event.key === "ArrowUp" || event.key === "ArrowDown")) {
        return;
      }

      if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        if (paymentLocked || lines.length === 0) return;
        setSelectedProductId(saleLineSelectionAfterArrow(lines, selectedProductId, event.key));
        event.preventDefault();
        return;
      }

      let handled = true;
      switch (event.key) {
        case "F1":
          setConsultationMode("PRICE");
          break;
        case "F2":
          setCalculatorOpen(true);
          break;
        case "F3":
          startCashDrawerOpening();
          break;
        case "F4":
          void handleSaleLogout();
          break;
        case "F5":
          if (!selectedLine || paymentLocked) return;
          setConsultationMode("STOCK");
          break;
        case "F6":
          openProductSalesHistory();
          break;
        case "F7":
          startProductEditing();
          break;
        case "F10":
          if (!paymentHydrated || paymentLocked) return;
          setTicketReturnOpen(true);
          break;
        case "F11":
        case "F12":
          if (!paymentHydrated || paymentLocked) return;
          setTicketManagementOpen(true);
          break;
        case "End":
          if (paymentLocked) return;
          openCustomerDialog();
          break;
        case "Pause":
          applyPauseQuantity();
          break;
        case "PageUp":
          applyDesiredLinePrice();
          break;
        case "PageDown":
          if (paymentActionsDisabled || paymentLocked) return;
          if (paymentCheckoutRef.current?.openCheckout) {
            paymentCheckoutRef.current.openCheckout("CASH");
          } else {
            paymentCheckoutRef.current?.triggerCash();
          }
          break;
        case "+":
          prepareNextProductQuantity(false);
          break;
        case "*":
          prepareNextProductQuantity(true);
          break;
        case "/":
          applyQuickLineDiscount();
          break;
        default:
          handled = false;
      }
      if (handled) {
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
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{t("sale.main.screen")}</h1>
          <button
            type="button"
            className="sale-cash-session-action"
            disabled={!cashSessionReady || lines.length > 0 || paymentLocked || !paymentHydrated}
            title={lines.length > 0
              ? "El carrito debe estar vacío para cerrar la caja"
              : paymentLocked || !paymentHydrated
                ? "Finaliza el cobro activo antes de cerrar la caja"
                : undefined}
            onClick={() => setCashSessionCloseOpen(true)}
          >
            {cashSessionCopy.close}
          </button>
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
                {authoritativeLineBreakdown
                  ? authoritativeLineBreakdown.map((line) => {
                      const localLine = lines.find((candidate) => candidate.product.id === line.productId);
                      return localLine ? renderCartRow(localLine, line) : null;
                    })
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
          <PromotionPreviewPanel locale={locale} preview={authoritativeQuote?.promotionPreview ?? null} />
        </section>

        <section className="sale-tools work-panel" aria-label={t("sale.main.searchAndPayment")}>
          <footer className="sale-total">
            <span>{t("sale.main.total")}</span>
            <strong>{formatSaleAmount(displayedTotal)}</strong>
            {authoritativeQuoteError && <small className="sale-action-error" role="alert">{authoritativeQuoteError}</small>}
          </footer>
          <label className="work-search">
            <span>{t("sale.main.searchProduct")}</span>
            <input
              ref={searchInputRef}
              aria-label={t("sale.main.searchProduct")}
              aria-expanded={productSearchOpen}
              aria-haspopup="dialog"
              autoComplete="off"
              disabled={catalogLoading || catalogError || paymentLocked}
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
          <div aria-live="polite" className="sale-search-results">
            {catalogLoading && <p className="sale-search-status">{t("sale.main.loadingProducts")}</p>}
            {catalogError && (
              <div className="sale-search-status sale-search-error">
                <span>{t("sale.main.catalogError")}</span>
                <button type="button" onClick={() => setCatalogReload((value) => value + 1)}>{t("sale.main.retry")}</button>
              </div>
            )}
            <p className="sale-next-quantity">
              {t("sale.main.quantity")}: {nextScanQuantity}
              {nextScanMode === "PACKAGE"
                ? ` ${t(nextScanQuantity === 1 ? "sale.quantity.package" : "sale.quantity.packages")}`
                : ""}
            </p>
            {shortcutStatus && <p className="sale-search-status" role="status">{shortcutStatus}</p>}
          </div>
          {interfaceMode === "TOUCH" && (
            <TouchSaleActionPanel
              labels={commandLabels}
              paymentLocked={paymentLocked}
              searchDisabled={catalogLoading || Boolean(catalogError) || paymentLocked}
              quantityDisabled={!selectedLine || paymentLocked}
              editProductDisabled={!selectedLine || paymentLocked || productEditBusy}
              serialNumberDisabled={!selectedLine || paymentLocked}
              ticketReturnDisabled={paymentLocked || !paymentHydrated}
              discountDisabled={
                !selectedLine
                || paymentLocked
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
              onCashDrawer={startCashDrawerOpening}
              onEditProduct={startProductEditing}
              onSerialNumber={() => setSerialNumberOpen(true)}
              onTicketReturn={() => setTicketReturnOpen(true)}
              onDocument={() => onOpenSalesDocumentWindow?.()}
              onQuantity={openQuantityDialog}
              onDiscount={openDiscountDialog}
              onCustomer={() => openCustomerDialog()}
              onRemoveLine={() => setActionDialog("remove")}
              onParkedSales={() => setParkedSalesOpen(true)}
              onManageTickets={() => setTicketManagementOpen(true)}
              onReceivables={() => onOpenCustomerReceivables?.(selectedCustomer?.id)}
            />
          )}
          <section className="sale-payment" aria-label={t("sale.main.payment")}>
            <h2>{t("sale.main.payment")}</h2>
            <SalePaymentCheckout
              ref={paymentCheckoutRef}
              locale={locale}
              totalCents={Math.round(authoritativeTotal * 100)}
              sale={cashSaleRequest()}
              token={session.accessToken}
              permissions={session.permissions}
              terminal={terminalContext}
              disabled={paymentActionsDisabled || !paymentHydrated}
              showIndividualActions={interfaceMode === "TOUCH"}
              unifiedCheckout
              interfaceMode={interfaceMode}
              customerSelected={Boolean(selectedCustomer)}
              checkoutDiscountCents={checkoutDiscountCents}
              testCashEnabled={import.meta.env.DEV && app === "venta"}
              onCash={() => void openCashDialog()}
              onPending={openPendingSale}
              onDiscount={setCheckoutDiscountCents}
              onHydrationChange={setPaymentHydrated}
              onLockedChange={(locked, reservedTotalCents) => {
                setPaymentLocked(locked);
                setReservedPaymentTotalCents(
                  locked && reservedTotalCents != null ? reservedTotalCents : null,
                );
              }}
              onFinalized={(printTicket, summary) => {
                invalidateCashOpening();
                deletionControl.reset("SALE_FINALIZED");
                setVerifactuRefreshSignal((current) => current + 1);
                setLines([]);
                setSelectedProductId(null);
                setSelectedCustomer(null);
                setQuery("");
                setNextScanQuantity(1);
                setNextScanMode("UNIT");
                setCheckoutDiscountCents(0);
                setReservedPaymentTotalCents(null);
                const result = paymentResultFromFinalization(printTicket, summary);
                setCashResult({ ...result, printStatus: "PRINTING" });
                startAutomaticTicketPrint(printTicket);
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

        {interfaceMode === "KEYBOARD" && (
          <KeyboardSaleCommandBar
            labels={commandLabels}
            documentAvailable={Boolean(onOpenSalesDocumentWindow)}
          />
        )}

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>

      <SaleCashDrawerAuthorizationDialog
        open={cashDrawerAuthorizationOpen}
        busy={cashDrawerBusy}
        error={cashDrawerError}
        t={t}
        onCancel={() => {
          if (cashDrawerBusy) return;
          setCashDrawerAuthorizationOpen(false);
          setCashDrawerError("");
        }}
        onAuthorize={(username, password) => void openCashDrawer(username, password)}
      />

      <SaleCashDrawerAuthorizationDialog
        open={productEditAuthorizationOpen}
        busy={productEditBusy}
        error={productEditError}
        t={t}
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
          onFinish={() => {
            finishCashPaymentResult(setCashResult, () => searchInputRef.current?.focus());
          }}
        />
      )}

      {cardDialogOpen && <CardPaymentDialog totalCents={cardQuoteCents} status={cardStatus} submitting={cardSubmitting} message={cardMessage} onCancel={() => setCardDialogOpen(false)} onConsult={consultCardPayment} onNewOperation={retryCardPayment} />}

      {pendingDraft && (selectedCustomer || recoveredPendingSale) && <CustomerPendingSaleDialog
        customerName={selectedCustomer?.fiscalName ?? selectedCustomer?.clientId ?? recoveredPendingSale?.customer.name ?? "Cliente"}
        locale={locale}
        terminalContext={terminalContext}
        draft={pendingDraft}
        recovery={recoveredPendingSale}
        token={session.accessToken}
        permissions={session.permissions}
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
          setPendingDraft(null); setLines([]); setSelectedProductId(null);
          setPendingPrintRetry(() => retry ?? null);
          setSelectedCustomer(null); setQuery(""); setNextScanQuantity(1); setNextScanMode("UNIT"); searchInputRef.current?.focus();
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
          labels={{
            title: t("sale.searchDialog.title"),
            query: t("sale.searchDialog.query"),
            image: t("sale.searchDialog.image"),
            code: t("sale.searchDialog.code"),
            barcode: t("sale.searchDialog.barcode"),
            barcode2: t("sale.searchDialog.barcode2"),
            name: t("sale.searchDialog.name"),
            price: t("sale.searchDialog.price"),
            empty: t("sale.searchDialog.empty"),
            close: t("common.close"),
            unnamedProduct: t("sale.main.unnamedProduct"),
            missingCode: t("sale.main.missingCode"),
          }}
          products={selectableProducts}
          token={session.accessToken}
          onInspect={openProductInformation}
          onQueryChange={setProductSearchQuery}
          onSelectionChange={setProductSearchSelectedId}
          onClose={() => {
            setProductSearchOpen(false);
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
          onSelect={(product) => {
            setProductSearchOpen(false);
            if (productSearchPurpose === "HISTORY") {
              setSalesHistoryProduct(product);
              return;
            }
            requestAddProduct(product);
          }}
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
          taxRegime={(selectedLine?.product.taxRegime ?? products[0]?.taxRegime ?? "IVA") as "IVA" | "IGIC"}
          onClose={() => {
            setCalculatorOpen(false);
            queueMicrotask(() => searchInputRef.current?.focus());
          }}
        />
      )}

      {salesHistoryProduct && (
        <div className="sale-action-overlay" role="presentation">
          <section className="sale-action-dialog wide sale-sales-history-dialog" role="dialog" aria-modal="true"
            aria-label={`Ventas de ${salesHistoryProduct.name ?? "producto"}`}>
            <header>
              <h2>Histórico de venta · {salesHistoryProduct.name ?? salesHistoryProduct.code}</h2>
              <button type="button" aria-label="Cerrar" onClick={() => setSalesHistoryProduct(null)}>×</button>
            </header>
            <StockSalesHistoryPanel
              productId={salesHistoryProduct.id}
              productName={salesHistoryProduct.name ?? salesHistoryProduct.code ?? ""}
              locale={locale}
              app={app}
              username={session.username}
              accessToken={session.accessToken}
              onClose={() => setSalesHistoryProduct(null)}
            />
          </section>
        </div>
      )}

      {pendingOpenPriceProduct && (
        <SaleOpenPriceDialog
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

      {actionDialog === "quantity" && selectedLine && (
        <SaleActionDialog title={t("sale.quantity.title")} closeLabel={t("sale.dialog.close")} onClose={() => setActionDialog(null)}>
          <form className="sale-action-form" onSubmit={(event) => { event.preventDefault(); saveQuantity(); }}>
            <label>
              <span>{t("sale.quantity.label")}</span>
              <input ref={quantityInputRef} aria-label={t("sale.quantity.inputAria")} type="number" min="1" max="9999" step="1" value={quantityInput} onChange={(event) => setQuantityInput(event.target.value)} />
            </label>
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
            {actionError && <strong className="sale-action-error">{actionError}</strong>}
            <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>{t("sale.dialog.cancel")}</button><button type="submit">{t("sale.dialog.save")}</button></div>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "discountAuthorization" && selectedLine && (
        <SaleActionDialog title={t("sale.discountAuthorization.title")} closeLabel={t("sale.dialog.close")} onClose={() => { setManagerPassword(""); setActionDialog(null); }}>
          <form className="sale-action-form" onSubmit={(event) => { event.preventDefault(); void authorizeDiscount(); }}>
            <p>
              {saleMainMessage(t, "sale.discountAuthorization.exceedsLimit", {
                discount: formatSalePercentage(discountAuthorizationPercent, locale),
                limit: formatSalePercentage(userDiscountLimit, locale),
              })}
            </p>
            <label>
              <span>{t("sale.discountAuthorization.managerUser")}</span>
              <input autoFocus autoComplete="username" value={managerName} onChange={(event) => setManagerName(event.target.value)} />
            </label>
            <label>
              <span>{t("sale.discountAuthorization.managerPassword")}</span>
              <input type="password" inputMode="numeric" autoComplete="current-password" value={managerPassword} onChange={(event) => setManagerPassword(event.target.value)} />
            </label>
            {actionError && <strong className="sale-action-error" role="alert">{actionError}</strong>}
            <div className="sale-action-buttons">
              <button type="button" onClick={() => { setManagerPassword(""); setActionDialog(null); }}>{t("sale.dialog.cancel")}</button>
              <button type="submit" disabled={managerAuthorizationBusy || !managerName.trim() || !managerPassword}>{t("sale.discountAuthorization.authorize")}</button>
            </div>
          </form>
        </SaleActionDialog>
      )}

      {actionDialog === "customer" && (
        <SaleActionDialog
          title={t("sale.customer.title")}
          closeLabel={t("sale.dialog.close")}
          initialFocusRef={customerSearchInputRef}
          onClose={() => { setPendingCustomerContinuation(false); setActionDialog(null); }}
          wide
        >
          <label>
            <span>{t("sale.customer.search")}</span>
            <input ref={customerSearchInputRef} aria-label={t("sale.customer.search")} value={customerQuery} onChange={(event) => setCustomerQuery(event.target.value)} placeholder={t("sale.customer.placeholder")} />
          </label>
          {customerLoading && <p className="sale-search-status">{t("sale.customer.loading")}</p>}
          {customerError && <p className="sale-action-error">{t("sale.customer.loadError")}</p>}
          {!customerLoading && !customerError && (
            <div className="sale-customer-results">
              {!pendingCustomerContinuation && <button type="button" onClick={() => { setSelectedCustomer(null); setLines((current) => applyMemberDiscounts(current, null)); setActionDialog(null); }}>{t("sale.customer.none")}</button>}
              {customerResults.map((customer) => (
                <button type="button" key={customer.id} onClick={() => { setSelectedCustomer(customer); setLines((current) => applyMemberDiscounts(current, customer)); setActionDialog(null); if (pendingCustomerContinuation) { setPendingCustomerContinuation(false); void beginPendingSale(customer); } }}>
                  <strong>{customer.fiscalName ?? t("sale.customer.unnamed")}</strong>
                  <span>{customer.clientId ?? customer.documentNumber ?? t("sale.customer.noCode")}</span>
                </button>
              ))}
            </div>
          )}
          <div className="sale-action-buttons"><button type="button" onClick={() => { setPendingCustomerContinuation(false); setActionDialog(null); }}>{t("sale.dialog.close")}</button></div>
        </SaleActionDialog>
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
          currentSale={cashSaleRequest()}
          canPark={lines.length > 0 && !paymentLocked && !authoritativeQuoteLoading && !authoritativeQuoteError}
          onClose={() => setParkedSalesOpen(false)}
          onParked={clearCurrentSale}
          onRecovered={recoverParkedSale}
        />
      )}

      {ticketManagementOpen && (
        <TicketManagementDialog
          token={session.accessToken}
          locale={locale}
          permissions={session.permissions}
          terminalContext={terminalContext}
          onClose={() => setTicketManagementOpen(false)}
          onFiscalMutation={() => setVerifactuRefreshSignal((current) => current + 1)}
        />
      )}

      {ticketReturnOpen && (
        <TicketReturnDialog
          token={session.accessToken}
          locale={locale}
          terminalContext={terminalContext}
          onClose={() => setTicketReturnOpen(false)}
          onFiscalMutation={() => setVerifactuRefreshSignal((current) => current + 1)}
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
              selectedLine.product.id,
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

      {cashSessionCloseOpen && cashSessionReady && terminalContext.terminalId && session.accessToken && (
        <SaleCashSessionDialog
          locale={locale}
          mode="CLOSE"
          terminalId={terminalContext.terminalId}
          token={session.accessToken}
          onCancel={() => setCashSessionCloseOpen(false)}
          onClosed={onBack}
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
  wide = false
}: {
  title: string;
  closeLabel: string;
  children: React.ReactNode;
  onClose: () => void;
  onKeyDown?: (event: ReactKeyboardEvent<HTMLElement>) => void;
  onConfirm?: () => void;
  initialFocusRef?: RefObject<HTMLElement | null>;
  wide?: boolean;
}) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    initialFocusRef?.current?.focus();
    return deactivate;
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
      <section ref={dialogRef} className={`sale-action-dialog${wide ? " wide" : ""}`} role="dialog" aria-modal="true" aria-label={title} onKeyDown={handleKeyDown}>
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

// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { retryPrintSucceeded } from "../sale/printRetry";
import {
  SaleScreen,
  SaleDeletionControlSequence,
  addSaleLine,
  appendPreviousTicketImport,
  applyMemberDiscounts,
  cashPaymentErrorTransition,
  cashPaymentSuccessTransition,
  cashPaymentResultForAutomaticPrinting,
  updateCashResultPrintOutcome,
  cashResultFromFinalization,
  finishCashPaymentResult,
  isCompleteAuthoritativeQuote,
  saleCartDisplayedUnitPrice,
  isSalesDocumentShortcut,
  readCashModeForOpening,
  runGuardedCashSubmission,
  resolveCardPaymentOutcome,
  cardRetryCheckoutId,
  cardTransportFailureOutcome,
  buildCardChargeBody,
  runGuardedCardOpening,
  saleCartImageColumnWidth,
  saleCartColumnDefinitions,
  saleCartLineIdentity,
  saleCartSpecialPrice,
  saleMainMessage,
  saleMainProductCount,
  visibleSaleCartColumns,
  pendingSaleDraftForCustomer,
  preparePreviousTicketImport,
  previousTicketImportReconciliationAdjustment,
  previousTicketImportSerialNumbersReady,
  saleSelectableProducts,
  effectiveSaleLineDiscount,
  effectiveSaleProductPrice,
  filterSaleCustomers,
  filterSaleProducts,
  removeSaleLine,
  resolveCashPaymentResult,
  saleLineSelectionAfterArrow,
  saleSearchSelectionAfterArrow,
  selectedProductAfterRemoval,
  saleLineSubtotal,
  saleLineUnitPrice,
  saleDisplayedTotal,
  saleOfferIsCurrent,
  saleProductBlocksManualDiscount,
  saleProductRequiresOpenPrice,
  salePauseQuantity,
  salePauseQuantityAllowed,
  saleKeyboardReturnRemovalAllowed,
  saleReturnCartReservations,
  saleReturnSourceConflict,
  mergeSaleReturnLines,
  saleQuickOperand,
  saleTotal,
  selectSaleProduct,
  updateSaleLineDiscount,
  updateSaleLineQuantity,
  updateSaleLineSerialNumbers,
  type SaleCustomer,
  type SaleLine,
  type SaleProduct,
  type PreviousTicketImportPreview,
} from "./SaleScreen";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { TerminalContext, UserSession } from "../types";
import type { PaymentFinalizationSummary, SalePaymentCheckoutHandle } from "./SalePaymentCheckout";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { ConfirmedTicketPrintSnapshot } from "../sale/ticketPrinting";
import { pendingSaleRecoveryKey, savePendingSaleRecovery } from "../sale/pendingSaleRecovery";
import { ApiError } from "../api/client";
import {
  cashCloseRecoveryKey,
  saveCashCloseRecovery,
} from "../sale/cashCloseRecovery";

type CheckoutMockProps = {
  testCashEnabled?: boolean;
  disabled?: boolean;
  showIndividualActions?: boolean;
  sale?: {
    customerId: string | null;
    internalComment?: string;
    lines: Array<{
      productId: string;
      quantity: number;
      discount: number;
      openUnitPrice?: number;
      cartLineId?: string;
      temporaryPriceAuthorizationToken?: string;
    }>;
    previousTicketImport?: {
      ticketId: string;
      fingerprint: string;
      serialNumbersBySourceLineId: Record<string, string[]>;
    };
    quoteFingerprint?: string;
  };
  onCash?: () => void;
  onPending?: () => void;
  onDiscount?: (amountCents: number) => void;
  onHydrationChange?: (hydrated: boolean) => void;
  onLockedChange?: (locked: boolean, reservedTotalCents?: number) => void;
  saleMutationAuthorizations?: Array<{
    code: string;
    label: string;
    authorization: {
      mode: "DIRECT" | "CURRENT_PASSWORD" | "DELEGATED";
      requireUsername: boolean;
      requirePassword: boolean;
    };
  }> | null;
  onFinalized: (printTicket: ConfirmedTicketPrintSnapshot, summary: PaymentFinalizationSummary) => void;
};

const {
  prepareApplicationClose,
  prepareLogout,
  triggerCash,
  triggerCard,
  triggerPending,
  checkoutHandle,
  checkoutProps,
  verifactuIndicatorProps,
  prepareCashSessionForSales,
  recoverCashCloseOperation,
  loadSalesOperationSecurity,
} = vi.hoisted(() => ({
  prepareApplicationClose: vi.fn(),
  prepareLogout: vi.fn(),
  triggerCash: vi.fn(),
  triggerCard: vi.fn(),
  triggerPending: vi.fn(),
  checkoutHandle: { attached: true, dispatchCashShortcutOnEnable: false },
  checkoutProps: {
    current: null as CheckoutMockProps | null,
  },
  verifactuIndicatorProps: {
    current: null as { refreshSignal?: unknown } | null,
  },
  prepareCashSessionForSales: vi.fn(),
  recoverCashCloseOperation: vi.fn(),
  loadSalesOperationSecurity: vi.fn(),
}));

vi.mock("../sale/cashSessions", async (importOriginal) => {
  const original = await importOriginal<typeof import("../sale/cashSessions")>();
  return { ...original, prepareCashSessionForSales, recoverCashCloseOperation };
});

vi.mock("../sale/operationSecurity", async (importOriginal) => {
  const original = await importOriginal<typeof import("../sale/operationSecurity")>();
  return { ...original, loadSalesOperationSecurity };
});

vi.mock("./SalePaymentCheckout", async () => {
  const { forwardRef, useEffect, useImperativeHandle, useLayoutEffect, useRef } = await import("react");
  return {
    SalePaymentCheckout: forwardRef<SalePaymentCheckoutHandle, CheckoutMockProps>(function MockSalePaymentCheckout(props, ref) {
      checkoutProps.current = props;
      const wasDisabled = useRef(true);
      useEffect(() => { props.onHydrationChange?.(true); props.onLockedChange?.(false); }, []);
      useImperativeHandle(checkoutHandle.attached ? ref : null, () => ({
        prepareApplicationClose,
        prepareLogout,
        triggerCash,
        triggerCard,
        triggerPending,
      }) as unknown as SalePaymentCheckoutHandle);
      useLayoutEffect(() => {
        if (checkoutHandle.dispatchCashShortcutOnEnable && wasDisabled.current && !props.disabled) {
          window.dispatchEvent(new KeyboardEvent("keydown", { key: "PageDown" }));
        }
        wasDisabled.current = Boolean(props.disabled);
      }, [props.disabled]);
      return <button type="button" disabled={props.disabled} onClick={props.onCash}>Efectivo <kbd>AvPág</kbd></button>;
    })
  };
});

vi.mock("./VerifactuPosIndicator", () => ({
  VerifactuPosIndicator: (props: { refreshSignal?: unknown }) => {
    verifactuIndicatorProps.current = props;
    return <button type="button">VERI*FACTU</button>;
  }
}));

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  prepareApplicationClose.mockReset();
  prepareLogout.mockReset();
  triggerCash.mockReset();
  triggerCard.mockReset();
  triggerPending.mockReset();
  checkoutHandle.attached = true;
  checkoutHandle.dispatchCashShortcutOnEnable = false;
  checkoutProps.current = null;
  verifactuIndicatorProps.current = null;
  localStorage.clear();
  delete window.tpvDesktop;
});

beforeEach(() => {
  prepareCashSessionForSales.mockReset().mockResolvedValue({
    cashSessionRequired: false,
    open: true,
    session: {
      id: "cash-session-1",
      terminalId: "terminal-1",
      status: "ABIERTA",
      openedAt: "2026-07-25T08:00:00Z",
      openingFund: 0,
      closedByAttempt: false,
    },
    requireWithdrawalBreakdown: false,
    withdrawalDenominations: [100, 50, 20, 10, 5, 2, 1],
  });
  recoverCashCloseOperation.mockReset();
  loadSalesOperationSecurity.mockReset().mockResolvedValue({
    storeId: "store-1",
    version: 1,
    operations: [
      {
        code: "OPEN_CASH_DRAWER",
        category: "CASH",
        shortcuts: ["F3"],
        permissions: ["ABRIR_CAJON"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "EDIT_CATALOG_PRODUCT",
        category: "PRODUCT",
        shortcuts: ["F7"],
        permissions: ["GESTION_PRODUCTO"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "CLOSE_CASH_SESSION",
        category: "CASH",
        shortcuts: ["F8"],
        permissions: ["GESTION_VENTAS", "GESTION_CUENTAS"],
        defaultRequirePermission: false,
        defaultRequirePassword: true,
        requirePermission: false,
        requirePassword: true,
        customized: false,
      },
      {
        code: "CASH_MOVEMENT",
        category: "CASH",
        shortcuts: ["F9"],
        permissions: ["GESTION_VENTAS", "GESTION_CUENTAS"],
        defaultRequirePermission: true,
        defaultRequirePassword: true,
        requirePermission: true,
        requirePassword: true,
        customized: false,
      },
      {
        code: "RETURN_TICKET",
        category: "TICKET",
        shortcuts: ["F10"],
        permissions: ["GESTION_VENTAS"],
        defaultRequirePermission: false,
        defaultRequirePassword: false,
        requirePermission: false,
        requirePassword: false,
        customized: false,
      },
      {
        code: "CANCEL_TICKET",
        category: "TICKET",
        shortcuts: ["F11", "Ctrl+F11"],
        permissions: ["GESTION_VENTAS", "GESTION_CUENTAS"],
        defaultRequirePermission: true,
        defaultRequirePassword: true,
        requirePermission: true,
        requirePassword: true,
        customized: false,
      },
      {
        code: "CONVERT_TICKET_TO_INVOICE",
        category: "TICKET",
        shortcuts: ["F12"],
        permissions: ["GESTION_VENTAS"],
        defaultRequirePermission: false,
        defaultRequirePassword: false,
        requirePermission: false,
        requirePassword: false,
        customized: false,
      },
      {
        code: "DELETE_PARKED_SALE",
        category: "TICKET",
        shortcuts: [],
        permissions: ["GESTION_VENTAS"],
        defaultRequirePermission: false,
        defaultRequirePassword: false,
        requirePermission: false,
        requirePassword: false,
        customized: false,
      },
      {
        code: "MANUAL_RETURN_WITHOUT_TICKET",
        category: "TICKET",
        shortcuts: ["-1", "Pausa"],
        permissions: ["GESTION_VENTAS"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "TEMPORARY_NAME",
        category: "PRODUCT",
        shortcuts: ["Inicio"],
        permissions: ["GESTION_VENTAS"],
        defaultRequirePermission: false,
        defaultRequirePassword: false,
        requirePermission: false,
        requirePassword: false,
        customized: false,
      },
      {
        code: "TEMPORARY_PRICE_CHANGE",
        category: "PRODUCT",
        shortcuts: ["Ctrl+RePag"],
        permissions: ["CAMBIAR_PRECIO", "GESTION_VENTAS"],
        defaultRequirePermission: true,
        defaultRequirePassword: true,
        requirePermission: true,
        requirePassword: true,
        customized: false,
      },
      {
        code: "OPEN_PRICE_PRODUCT",
        category: "PRODUCT",
        shortcuts: [],
        permissions: ["CAMBIAR_PRECIO", "GESTION_VENTAS"],
        defaultRequirePermission: false,
        defaultRequirePassword: false,
        requirePermission: false,
        requirePassword: false,
        customized: false,
      },
      {
        code: "APPLY_SALE_DISCOUNT",
        category: "DISCOUNT",
        shortcuts: ["/", "RePag"],
        permissions: ["APLICAR_DESCUENTO"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "APPLY_CHECKOUT_DISCOUNT",
        category: "DISCOUNT",
        shortcuts: ["Ctrl+/"],
        permissions: ["APLICAR_DESCUENTO"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "CREATE_PENDING_RECEIVABLE",
        category: "CREDIT",
        shortcuts: ["F8"],
        permissions: ["CUSTOMER_RECEIVABLES_CREATE"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "CREDIT_OVERRIDE",
        category: "CREDIT",
        shortcuts: [],
        permissions: ["CUSTOMER_CREDIT_OVERRIDE"],
        defaultRequirePermission: true,
        defaultRequirePassword: false,
        requirePermission: true,
        requirePassword: false,
        customized: false,
      },
      {
        code: "PAYMENT_TERMINAL_VOID",
        category: "PAYMENT_TERMINAL",
        shortcuts: [],
        permissions: ["PAYMENT_TERMINAL_VOID"],
        defaultRequirePermission: true,
        defaultRequirePassword: true,
        requirePermission: true,
        requirePassword: true,
        customized: false,
      },
      {
        code: "PAYMENT_TERMINAL_REFUND",
        category: "PAYMENT_TERMINAL",
        shortcuts: [],
        permissions: ["PAYMENT_TERMINAL_REFUND"],
        defaultRequirePermission: true,
        defaultRequirePassword: true,
        requirePermission: true,
        requirePassword: true,
        customized: false,
      },
      {
        code: "PAYMENT_COMPENSATION_ACK",
        category: "PAYMENT_TERMINAL",
        shortcuts: [],
        permissions: ["PAYMENT_TERMINAL_REFUND"],
        defaultRequirePermission: true,
        defaultRequirePassword: true,
        requirePermission: true,
        requirePassword: true,
        customized: false,
      },
    ],
  });
});

it("keeps sale print retry after two failures and clears only after success", async () => {
  const retry = vi.fn().mockResolvedValueOnce({ status: "FAILED" })
    .mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ status: "PRINTED" });
  expect(await retryPrintSucceeded(retry)).toBe(false);
  expect(await retryPrintSucceeded(retry)).toBe(false);
  expect(await retryPrintSucceeded(retry)).toBe(true);
});

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "access-token"
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
  terminalId: "terminal-1",
};

const printSnapshot = (documentNumber: string): ConfirmedTicketPrintSnapshot => ({
  documentId: `document-${documentNumber}`,
  documentNumber,
  issuedAt: "2026-07-15T12:00:00.000Z",
  lines: [],
  payments: [],
  total: "12.10",
});

function installTicketHardware(printTicket: ReturnType<typeof vi.fn>) {
  window.tpvDesktop = {
    closeApplication: vi.fn().mockResolvedValue(undefined),
    hardware: {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket,
    } as never,
  };
}

const products: SaleProduct[] = [
  { id: "coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Cafe molido", salePrice: 10, taxId: "tax-iva-21", taxesIncluded: true, taxPercentage: 21, taxRegime: "IVA" },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", salePrice: "2.50", taxId: "tax-iva-21", taxesIncluded: true, taxPercentage: 21, taxRegime: "IVA" },
  { id: "milk", code: "LEC-001", barcode: "8410000000035", name: "Leche fresca", salePrice: 1.75, taxId: "tax-iva-21", taxesIncluded: true, taxPercentage: 21, taxRegime: "IVA" }
];

function authoritativeQuote(product: SaleProduct, total = "10.00", couponDiscount = "0.00") {
  return {
    total,
    productTotal: "10.00",
    promotionPreview: { appliedPromotions: [] },
    pricingVersion: 1,
    quoteFingerprint: `quote-${total}-${couponDiscount}`,
    lineBreakdown: [{
      lineId: `product:${product.id}:1`,
      position: 1,
      productId: product.id,
      code: product.code ?? "SKU",
      name: "Nombre autoritativo backend",
      quantity: "1.000",
      normalUnitPrice: "10.00",
      memberUnitPrice: null,
      baseUnitPrice: "10.00",
      priceSource: "SALE",
      memberPriceSaving: "0.00",
      memberDiscountPercent: "0.00",
      memberDiscount: "0.00",
      manualDiscountPercent: "0.00",
      manualDiscount: "0.00",
      promotionDiscount: "0.00",
      couponDiscount,
      taxIncluded: true,
      taxRegime: "IVA",
      taxPercent: "21.00",
      taxBase: total === "8.00" ? "6.61" : "8.26",
      tax: total === "8.00" ? "1.39" : "1.74",
      baseSubtotal: "10.00",
      roundingAdjustment: "0.00",
      finalSubtotal: total,
    }],
  };
}

function previousTicketPreview(
  overrides: Partial<PreviousTicketImportPreview> = {},
): PreviousTicketImportPreview {
  return {
    ticketId: "previous-ticket",
    ticketNumber: "001-260807-00010",
    ticketDate: "2026-08-07T10:00:00Z",
    status: "CONFIRMADO",
    pricingMode: "CURRENT_REPRICING",
    preservedManualDiscountAmount: "0.00",
    manualDiscountAuthorizationRequired: false,
    fingerprint: "previous-ticket-fingerprint",
    customerId: null,
    globalDiscount: "0.00",
    baseTotal: "16.53",
    taxTotal: "3.47",
    total: "20.00",
    currency: "EUR",
    lines: [{
      sourceLineId: "previous-line-coffee",
      productId: "coffee",
      code: "CAF-001",
      name: "Cafe histórico",
      quantity: "2",
      productType: "UNIT",
      unitPrice: "12.50",
      discount: "20.00",
      rate: null,
      taxesIncluded: true,
      taxRegime: "IVA",
      taxPercent: "21.00",
      base: "16.53",
      tax: "3.47",
      total: "20.00",
      serialNumbers: [],
      temporaryPriceAuthorizationRequired: false,
    }],
    adjustments: [],
    ...overrides,
  };
}

const memberDiscountProduct: SaleProduct = {
  id: "member-coffee",
  code: "MEM-CAFE",
  name: "Cafe socio",
  salePrice: 10,
  discountType: "MEMBER_DISCOUNT",
  taxId: "tax-iva-21",
  taxesIncluded: true,
  taxPercentage: 21,
  taxRegime: "IVA",
};

const customers: SaleCustomer[] = [
  { id: "customer-1", clientId: "C-001", fiscalName: "Cliente Pruebas SL", documentNumber: "B11111111" },
  { id: "customer-2", clientId: "C-002", fiscalName: "Maria Lopez", documentNumber: "12345678Z" }
];

describe("SaleScreen", () => {
  it("blocks Sales when manual cash opening is required and still allows exiting", async () => {
    prepareCashSessionForSales.mockResolvedValueOnce({
      cashSessionRequired: true,
      open: false,
      session: null,
    });
    const onBack = vi.fn();

    render(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={onBack}
        onLocaleChange={vi.fn()}
      />,
    );

    expect(await screen.findByRole("dialog", { name: "Abrir caja" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Buscar producto" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Salir de Ventas" }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it("recognizes only Ctrl+F as the document-window shortcut", () => {
    expect(isSalesDocumentShortcut({
      key: "f", ctrlKey: true, altKey: false, metaKey: false,
    })).toBe(true);
    expect(isSalesDocumentShortcut({
      key: "F", ctrlKey: true, altKey: false, metaKey: false,
    })).toBe(true);
    expect(isSalesDocumentShortcut({
      key: "f", ctrlKey: false, altKey: false, metaKey: false,
    })).toBe(false);
    expect(isSalesDocumentShortcut({
      key: "f", ctrlKey: true, altKey: true, metaKey: false,
    })).toBe(false);
  });

  it("accepts only reconciled version-one authoritative quotes", () => {
    const valid = authoritativeQuote(products[0]);

    expect(isCompleteAuthoritativeQuote(valid)).toBe(true);
    expect(isCompleteAuthoritativeQuote({ ...valid, pricingVersion: 0 })).toBe(false);
    expect(isCompleteAuthoritativeQuote({ ...valid, lineBreakdown: [] })).toBe(false);
    expect(isCompleteAuthoritativeQuote({
      ...valid,
      lineBreakdown: [{ ...valid.lineBreakdown[0], finalSubtotal: "9.99" }],
    })).toBe(false);
    expect(isCompleteAuthoritativeQuote({
      ...valid,
      total: "-10.00",
      productTotal: "-10.00",
      lineBreakdown: [{
        ...valid.lineBreakdown[0],
        quantity: "-1.000",
        baseSubtotal: "-10.00",
        finalSubtotal: "-10.00",
      }],
    })).toBe(true);
    expect(isCompleteAuthoritativeQuote({
      ...valid,
      total: "0.00",
      productTotal: "-10.00",
      lineBreakdown: [
        {
          ...valid.lineBreakdown[0],
          lineType: "PRODUCT",
          quantity: "-1.000",
          baseSubtotal: "-10.00",
          finalSubtotal: "-10.00",
        },
        {
          ...valid.lineBreakdown[0],
          lineId: "return-adjustment:2",
          position: 2,
          lineType: "RETURN_ADJUSTMENT",
          productId: null,
          code: "AJUSTE POR PERDIDA DE PROMOCION",
          name: "AJUSTE POR PERDIDA DE PROMOCION",
          quantity: "1.000",
          normalUnitPrice: "0.00",
          baseUnitPrice: "10.00",
          baseSubtotal: "10.00",
          finalSubtotal: "10.00",
        },
      ],
    })).toBe(true);
  });

  it("shows the historical unit price for an F10 return instead of the current catalog price", () => {
    const line = {
      product: { ...products[0], salePrice: "8.20" },
      quantity: -1,
      discountPercent: 0,
      returnUnitPrice: 100,
      returnOrigin: {
        sourceType: "TICKET" as const,
        sourceCode: "001-260803-00001",
        sourceTicketId: "ticket-1",
        sourceTicketNumber: "001-260803-00001",
        sourceLineId: "line-1",
      },
    };

    expect(saleCartDisplayedUnitPrice(line)).toBe(100);
  });

  it("shows the Ctrl+PageUp unit price and multiplies it by the line quantity", () => {
    const line: SaleLine = {
      product: { ...products[0], salePrice: "15.18" },
      quantity: 3,
      discountPercent: 0,
      openUnitPrice: 10,
    };
    const authoritativeLine = {
      ...authoritativeQuote(line.product, "30.00").lineBreakdown[0],
      quantity: "3.000",
      normalUnitPrice: "15.18",
      baseUnitPrice: "10.00",
      baseSubtotal: "30.00",
      finalSubtotal: "30.00",
    };

    expect(saleCartDisplayedUnitPrice(line, false, authoritativeLine)).toBe(10);
    expect(saleLineSubtotal(line)).toBe(30);
  });

  it("keeps every requested cart column visible by default", () => {
    expect(saleCartColumnDefinitions.map((column) => column.key)).toEqual([
      "image",
      "code",
      "barcode",
      "name",
      "quantity",
      "package",
      "salePrice",
      "discount",
      "specialPrice",
      "total",
    ]);
  });

  it("keeps the image column fixed even when a saved user preference contains another width", () => {
    expect(saleCartImageColumnWidth).toBe(58);

    const columns = visibleSaleCartColumns([
      { key: "image", width: 180, visible: true },
      { key: "name", width: 320, visible: true },
    ]);

    expect(columns).toEqual([
      { key: "image", width: saleCartImageColumnWidth, visible: true },
      { key: "name", width: 320, visible: true },
    ]);
  });

  it("shows the authoritative offer price below its compact offer label", () => {
    const offerProduct: SaleProduct = {
      ...products[0],
      priceUseMode: "OFFER_DISCOUNT",
      offerActive: true,
      offerDiscountPercent: 20,
    };
    const quoteLine = {
      ...authoritativeQuote(offerProduct, "8.00").lineBreakdown[0],
      baseUnitPrice: "8.00",
      priceSource: "OFERTA",
    };

    expect(saleCartSpecialPrice(offerProduct, false, quoteLine)).toEqual({
      type: "OFFER_DISCOUNT",
      unitPrice: 8,
      discountPercent: 20,
    });
  });

  it("renders an offer discount in its column and keeps only the offer label above the special price", async () => {
    const offerProduct: SaleProduct = {
      ...products[0],
      imageId: "image-1",
      priceUseMode: "OFFER_DISCOUNT",
      offerActive: true,
      offerDiscountPercent: 20,
    };
    const offerQuote = authoritativeQuote(offerProduct, "8.00");
    offerQuote.lineBreakdown[0] = {
      ...offerQuote.lineBreakdown[0],
      baseUnitPrice: "8.00",
      priceSource: "OFERTA",
      finalSubtotal: "8.00",
    };
    const createObjectUrl = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:cart-product");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([offerProduct]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(offerQuote), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/products/coffee/image")) {
        return new Response("image", {
          status: 200,
          headers: { "Content-Type": "image/webp" },
        });
      }
      return new Response("", { status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    const table = await screen.findByRole("table", { name: "Líneas del ticket" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Imagen",
      "Código",
      "Código de barras",
      "Nombre",
      "Cant.",
      "Paquete",
      "Precio",
      "Dto.",
      "P.Especial",
      "Total",
    ]);
    expect(await within(table).findByText("%Oferta")).toBeInTheDocument();
    expect(table.querySelector(".sale-cart-special strong")?.textContent).toBe("8,00 €/ud");
    expect(table.querySelector(".sale-cart-discount")?.textContent).toBe("20,00%");
    expect(table.querySelector(".sale-cart-special small")?.textContent).toBe("%Oferta");
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/products/coffee/image?thumbnail=true"))).toBe(true));
    await waitFor(() => expect(createObjectUrl).toHaveBeenCalled());
    await waitFor(() => expect(table.querySelector("img.sale-cart-thumbnail")).not.toBeNull());
    expect(within(table).queryByRole("button", { name: "Redimensionar Imagen" })).not.toBeInTheDocument();

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("blocks every payment while the authoritative quote is unresolved", async () => {
    let resolveQuote!: (value: Response) => void;
    const pendingQuote = new Promise<Response>((resolve) => { resolveQuote = resolve; });
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) return pendingQuote;
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    const cashAction = screen.getByRole("button", { name: /Efectivo/ });
    await waitFor(() => expect(cashAction).toBeDisabled());
    fireEvent.click(cashAction);
    fireEvent.keyDown(window, { key: "PageDown" });
    fireEvent.keyDown(window, { key: "F11" });
    fireEvent.keyDown(window, { key: "F12" });
    expect(fetchMock.mock.calls.some(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/pos/cash/quote"))).toBe(false);
    expect(triggerCard).not.toHaveBeenCalled();
    expect(triggerPending).not.toHaveBeenCalled();

    resolveQuote(new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } }));
    await waitFor(() => expect(cashAction).toBeEnabled());
    expect(screen.getByText("Nombre autoritativo backend")).toBeInTheDocument();
  });

  it("updates the visible total immediately without showing the previous quote or a calculation state", async () => {
    let quoteCalls = 0;
    const pendingUpdatedQuote = new Promise<Response>(() => undefined);
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        quoteCalls += 1;
        return quoteCalls === 1
          ? new Response(JSON.stringify(authoritativeQuote(products[0], "8.00")), {
              status: 200,
              headers: { "Content-Type": "application/json" },
            })
          : pendingUpdatedQuote;
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    const total = document.querySelector(".sale-total strong");
    await waitFor(() => expect(total?.textContent).toBe("8,00"));

    fireEvent.change(search, { target: { value: "2" } });
    fireEvent.keyDown(search, { key: "Pause" });

    expect(total?.textContent).toBe("20,00");
    expect(screen.queryByText("Calculando…")).not.toBeInTheDocument();
  });

  it("does not expose or send promotional coupons from the sales screen", async () => {
    const quoteBodies: Array<Record<string, unknown>> = [];
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) {
        const body = JSON.parse(String(options?.body)) as Record<string, unknown>;
        quoteBodies.push(body);
        return new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());

    expect(screen.queryByLabelText("Código del cupón")).not.toBeInTheDocument();
    expect(quoteBodies.every((body) => !Object.hasOwn(body, "promotionalCouponCode"))).toBe(true);
  });

  it("resets the deletion sequence for every real cart boundary", () => {
    const ids = ["sale-1", "after-add", "after-finalize", "after-empty", "after-park"];
    const sequence = new SaleDeletionControlSequence(() => ids.shift()!);

    expect(sequence.currentSaleOperationId()).toBe("sale-1");
    sequence.reset("PRODUCT_ADDED");
    expect(sequence.currentSaleOperationId()).toBe("after-add");
    sequence.reset("SALE_FINALIZED");
    expect(sequence.currentSaleOperationId()).toBe("after-finalize");
    sequence.reset("CART_EMPTIED");
    expect(sequence.currentSaleOperationId()).toBe("after-empty");
    sequence.reset("SALE_PARKED");
    expect(sequence.currentSaleOperationId()).toBe("after-park");
  });

  it("serializes deletion records so rapid removals preserve their database order", async () => {
    const sequence = new SaleDeletionControlSequence(() => "sale-1");
    const events: string[] = [];
    let releaseFirst!: () => void;
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const onError = vi.fn();

    const first = sequence.enqueue(async () => {
      events.push("first:start");
      await firstGate;
      events.push("first:end");
    }, onError);
    const second = sequence.enqueue(async () => {
      events.push("second:start");
    }, onError);

    await Promise.resolve();
    expect(events).toEqual(["first:start"]);
    releaseFirst();
    await Promise.all([first, second]);
    expect(events).toEqual(["first:start", "first:end", "second:start"]);
    expect(onError).not.toHaveBeenCalled();
  });
  function renderSaleScreen(
    onLogout = vi.fn(),
    locale: "es" | "en" | "zh" = "es",
    options: {
      session?: UserSession;
      terminalContext?: TerminalContext;
      interfaceMode?: "KEYBOARD" | "TOUCH";
    } = {},
  ) {
    render(
      <SaleScreen
        app="venta"
        locale={locale}
        session={options.session ?? session}
        terminalContext={options.terminalContext ?? terminalContext}
        interfaceMode={options.interfaceMode}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={onLogout}
      />
    );
    return onLogout;
  }

  function submitQuickEntry(input: HTMLElement, value: string) {
    fireEvent.change(input, { target: { value } });
    fireEvent.keyDown(input, { key: "Enter" });
  }

  async function importPreviousTicketFromMenu() {
    fireEvent.click(screen.getByRole("button", { name: /FACTURA\/TICKET/ }));
    const action = await screen.findByRole("menuitem", { name: "Importar ticket anterior" });
    await waitFor(() => expect(action).toBeEnabled());
    fireEvent.click(action);
  }

  async function confirmProductSearchWithInsert(name: RegExp) {
    await screen.findByRole("option", { name });
    fireEvent.keyDown(
      screen.getByRole("combobox", { name: "Código, código de barras o nombre" }),
      { key: "Insert" },
    );
  }

  async function logoutButton() {
    fireEvent.click(await screen.findByRole("button", { name: "ADMIN" }));
    return screen.getByRole("menuitem", { name: "Cerrar usuario" });
  }

  async function confirmShutdown() {
    fireEvent.click(await screen.findByRole("button", { name: "Apagar" }));
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
  }

  it("enables test cash for APP VENTA only in Vite development", async () => {
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current).not.toBeNull());
    expect(checkoutProps.current?.testCashEnabled).toBe(import.meta.env.DEV);
  });

  it("never enables test cash when SaleScreen is wired for APP GESTION", async () => {
    render(
      <SaleScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    await waitFor(() => expect(checkoutProps.current).not.toBeNull());
    expect(checkoutProps.current?.testCashEnabled).toBe(false);
    expect(verifactuIndicatorProps.current).toBeNull();
  });

  it("shows VeriFactu only to sales operators and refreshes it after checkout finalization", async () => {
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));
    expect(verifactuIndicatorProps.current?.refreshSignal).toBe(0);

    act(() => checkoutProps.current?.onFinalized(
      printSnapshot("VF-CARD"),
      { kind: "CARD", totalCents: 1210 },
    ));

    expect(verifactuIndicatorProps.current?.refreshSignal).toBe(1);
    cleanup();
    verifactuIndicatorProps.current = null;
    render(
      <SaleScreen
        app="venta"
        locale="es"
        session={{ ...session, permissions: ["GESTION_PRODUCTO"] }}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />,
    );
    expect(verifactuIndicatorProps.current).toBeNull();
  });

  it("closes the application only after payment checkout is ready", async () => {
    let resolvePreparation!: (result: "READY") => void;
    prepareApplicationClose.mockImplementation(() => new Promise((resolve) => {
      resolvePreparation = resolve;
    }));
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    renderSaleScreen();

    await confirmShutdown();

    expect(prepareApplicationClose).toHaveBeenCalledTimes(1);
    expect(closeApplication).not.toHaveBeenCalled();
    resolvePreparation("READY");
    await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(1));
  });

  it.each([
    ["BLOCKED", vi.fn().mockResolvedValue("BLOCKED")],
    ["rejection", vi.fn().mockRejectedValue(new Error("cleanup failed"))]
  ])("keeps the application open after shutdown preparation %s", async (_label, implementation) => {
    prepareApplicationClose.mockImplementation(implementation);
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    renderSaleScreen();

    await confirmShutdown();

    await waitFor(() => expect(prepareApplicationClose).toHaveBeenCalledTimes(1));
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it("fails closed when payment checkout has not attached its handle", async () => {
    checkoutHandle.attached = false;
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    renderSaleScreen();

    await confirmShutdown();

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(prepareApplicationClose).not.toHaveBeenCalled();
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it("logs out only after payment checkout is ready", async () => {
    prepareLogout.mockResolvedValue("READY");
    const onLogout = renderSaleScreen();

    fireEvent.click(await logoutButton());

    await waitFor(() => expect(prepareLogout).toHaveBeenCalledTimes(1));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it("does not log out when payment checkout blocks it", async () => {
    prepareLogout.mockResolvedValue("BLOCKED");
    const onLogout = renderSaleScreen();

    fireEvent.click(await logoutButton());

    await waitFor(() => expect(prepareLogout).toHaveBeenCalledTimes(1));
    expect(onLogout).not.toHaveBeenCalled();
  });

  it("does not log out when payment checkout preparation rejects", async () => {
    prepareLogout.mockRejectedValue(new Error("cleanup failed"));
    const onLogout = renderSaleScreen();

    fireEvent.click(await logoutButton());

    await waitFor(() => expect(prepareLogout).toHaveBeenCalledTimes(1));
    expect(onLogout).not.toHaveBeenCalled();
  });

  it("routes global typing to the product field and traps focus in customers opened with End", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(window, { key: "A" });
    expect(search).toHaveFocus();
    expect(search).toHaveValue("A");

    fireEvent.keyDown(window, { key: "End" });
    const dialog = screen.getByRole("dialog", { name: "Seleccionar cliente" });
    const customerSearch = await within(dialog).findByRole("textbox", { name: "Buscar cliente" });
    expect(customerSearch).toHaveFocus();

    const closeButtons = within(dialog).getAllByRole("button", { name: "Cerrar" });
    closeButtons[0].focus();
    await user.tab({ shift: true });
    expect(within(dialog).getByRole("button", { name: "Seleccionar cliente" })).toHaveFocus();

    customerSearch.focus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    expect(search).toHaveFocus();
  });

  it("connects F1, F2, F5 and F6 to their consultation windows", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      if (path.endsWith("/stock/page")) {
        return new Response(JSON.stringify({
          items: [{ product: { id: products[0].id }, stock: [{ quantity: 7 }, { quantity: 3 }] }]
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.includes("/stock/products/coffee/sales-history")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(search, { key: "F6" });
    const emptyHistory = screen.getByRole("dialog", { name: "Historial de ventas" });
    expect(within(emptyHistory).getByText("Busca un producto para consultar sus ventas")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Buscador de productos" })).not.toBeInTheDocument();
    fireEvent.click(within(emptyHistory).getAllByRole("button", { name: "Cerrar" })[0]);

    fireEvent.keyDown(search, { key: "F1" });
    expect(screen.getByRole("dialog", { name: "Consulta de precio" })).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Cerrar" })[0]);

    fireEvent.keyDown(search, { key: "F2" });
    expect(screen.getByRole("dialog", { name: "Calculadora" })).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Cerrar" })[0]);

    submitQuickEntry(search, "CAF-001");
    fireEvent.keyDown(search, { key: "F5" });
    expect(screen.getByRole("dialog", { name: "Consulta de stock" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText(/10,00/).length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByRole("button", { name: "Cerrar" })[0]);

    fireEvent.keyDown(search, { key: "F6" });
    expect(screen.getByRole("dialog", { name: "Historial de ventas" })).toBeInTheDocument();
    expect(screen.queryByText("No hay una sesión válida para consultar el historial")).not.toBeInTheDocument();
  });

  it.each([
    ["es", {
      productSearch: "Buscar producto",
      close: "Cerrar",
      cancel: "Cancelar",
      save: "Guardar",
      quantityTitle: "Cambiar cantidad",
      quantityLabel: "Cantidad",
      quantityInput: "Nueva cantidad",
      quantityInvalid: "La cantidad debe ser un número entero entre 1 y 9999",
      discountTitle: "Aplicar descuento",
      discountLabel: "Descuento (%)",
      discountInput: "Nuevo descuento",
      discountInvalid: "El descuento debe estar entre 0 y 100",
      customerTitle: "Seleccionar cliente",
      customerSearch: "Buscar cliente",
      customerPlaceholder: "Nombre, documento o código",
      customerLoading: "Cargando clientes...",
      customerNone: "Sin cliente",
      customerUnnamed: "Cliente sin nombre",
      customerNoCode: "Sin código",
      removeTitle: "Anular línea",
      removeConfirm: "Se eliminará Cafe molido del ticket.",
      removeAction: "Anular línea",
    }],
    ["en", {
      productSearch: "Search product",
      close: "Close",
      cancel: "Cancel",
      save: "Save",
      quantityTitle: "Change quantity",
      quantityLabel: "Quantity",
      quantityInput: "New quantity",
      quantityInvalid: "Quantity must be a whole number between 1 and 9999",
      discountTitle: "Apply discount",
      discountLabel: "Discount (%)",
      discountInput: "New discount",
      discountInvalid: "Discount must be between 0 and 100",
      customerTitle: "Select customer",
      customerSearch: "Search customer",
      customerPlaceholder: "Name, document or code",
      customerLoading: "Loading customers...",
      customerNone: "No customer",
      customerUnnamed: "Unnamed customer",
      customerNoCode: "No code",
      removeTitle: "Remove line",
      removeConfirm: "Cafe molido will be removed from the ticket.",
      removeAction: "Remove line",
    }],
    ["zh", {
      productSearch: "\u641c\u7d22\u5546\u54c1",
      close: "\u5173\u95ed",
      cancel: "\u53d6\u6d88",
      save: "\u4fdd\u5b58",
      quantityTitle: "\u66f4\u6539\u6570\u91cf",
      quantityLabel: "\u6570\u91cf",
      quantityInput: "\u65b0\u6570\u91cf",
      quantityInvalid: "\u6570\u91cf\u5fc5\u987b\u662f 1 \u5230 9999 \u4e4b\u95f4\u7684\u6574\u6570",
      discountTitle: "\u5e94\u7528\u6298\u6263",
      discountLabel: "\u6298\u6263 (%)",
      discountInput: "\u65b0\u6298\u6263",
      discountInvalid: "\u6298\u6263\u5fc5\u987b\u5728 0 \u5230 100 \u4e4b\u95f4",
      customerTitle: "\u9009\u62e9\u5ba2\u6237",
      customerSearch: "\u641c\u7d22\u5ba2\u6237",
      customerPlaceholder: "\u540d\u79f0\u3001\u8bc1\u4ef6\u6216\u4ee3\u7801",
      customerLoading: "\u6b63\u5728\u52a0\u8f7d\u5ba2\u6237...",
      customerNone: "\u65e0\u5ba2\u6237",
      customerUnnamed: "\u672a\u547d\u540d\u5ba2\u6237",
      customerNoCode: "\u65e0\u4ee3\u7801",
      removeTitle: "\u5220\u9664\u884c",
      removeConfirm: "\u5c06\u4ece\u5c0f\u7968\u4e2d\u79fb\u9664 Cafe molido\u3002",
      removeAction: "\u5220\u9664\u884c",
    }],
  ] as const)("localizes sale action dialogs in %s", async (locale, expected) => {
    const user = userEvent.setup();
    const t = createTranslator(locale);
    let resolveCustomers!: (response: Response) => void;
    const customersResponse = new Promise<Response>((resolve) => { resolveCustomers = resolve; });
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/customers/sale-options")) return customersResponse;
      return Promise.resolve(new Response(JSON.stringify([products[0]]), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      }));
    }));
    renderSaleScreen(vi.fn(), locale, { interfaceMode: "TOUCH" });
    const search = await screen.findByRole("combobox", { name: expected.productSearch });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.click(screen.getByRole("button", { name: t("sale.main.quantity") }));
    let dialog = screen.getByRole("dialog", { name: expected.quantityTitle });
    expect(within(dialog).getByText(expected.quantityLabel)).toBeInTheDocument();
    expect(within(dialog).getByRole("spinbutton", { name: expected.quantityInput })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.close })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.cancel })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.save })).toBeInTheDocument();
    const quantityInput = within(dialog).getByRole("spinbutton", { name: expected.quantityInput });
    await user.clear(quantityInput);
    await user.type(quantityInput, "0");
    await waitFor(() => expect(quantityInput).toHaveValue(0));
    fireEvent.submit(dialog.querySelector("form")!);
    expect(await within(dialog).findByText(expected.quantityInvalid)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: expected.cancel }));

    fireEvent.click(screen.getByRole("button", { name: t("sale.main.discount") }));
    dialog = screen.getByRole("dialog", { name: expected.discountTitle });
    expect(within(dialog).getByText(expected.discountLabel)).toBeInTheDocument();
    expect(within(dialog).getByRole("spinbutton", { name: expected.discountInput })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.cancel })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.save })).toBeInTheDocument();
    const discountInput = within(dialog).getByRole("spinbutton", { name: expected.discountInput });
    await user.clear(discountInput);
    await user.type(discountInput, "101");
    await waitFor(() => expect(discountInput).toHaveValue(101));
    fireEvent.submit(dialog.querySelector("form")!);
    expect(await within(dialog).findByText(expected.discountInvalid)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: expected.cancel }));

    fireEvent.click(screen.getByRole("button", { name: t("sale.main.customer") }));
    dialog = screen.getByRole("dialog", { name: expected.customerTitle });
    expect(within(dialog).getByText(expected.customerSearch)).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: expected.customerSearch })).toHaveAttribute("placeholder", expected.customerPlaceholder);
    expect(within(dialog).getByText(expected.customerLoading)).toBeInTheDocument();
    resolveCustomers(new Response(JSON.stringify([{ id: "anonymous" }]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: expected.customerNone })).toBeInTheDocument());
    expect(within(dialog).getByText(expected.customerUnnamed)).toBeInTheDocument();
    expect(within(dialog).getByText(expected.customerNoCode)).toBeInTheDocument();
    const [closeIcon] = within(dialog).getAllByRole("button", { name: expected.close });
    expect(closeIcon).toBeInTheDocument();
    fireEvent.click(closeIcon);

    fireEvent.click(screen.getByRole("button", { name: t("sale.main.removeLine") }));
    dialog = screen.getByRole("dialog", { name: expected.removeTitle });
    expect(within(dialog).getByText(expected.removeConfirm)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.cancel })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: expected.removeAction })).toBeInTheDocument();
  });

  it.each([
    ["es", { productSearch: "Buscar producto", discountInput: "Nuevo descuento", save: "Guardar", title: "Autorización de descuento", explanation: "El descuento del 10,00% supera tu límite del 5,00%.", managerUser: "Usuario responsable", managerPassword: "Contraseña del responsable", cancel: "Cancelar", authorize: "Autorizar" }],
    ["en", { productSearch: "Search product", discountInput: "New discount", save: "Save", title: "Discount authorization", explanation: "The 10.00% discount exceeds your 5.00% limit.", managerUser: "Manager username", managerPassword: "Manager password", cancel: "Cancel", authorize: "Authorize" }],
    ["zh", { productSearch: "\u641c\u7d22\u5546\u54c1", discountInput: "\u65b0\u6298\u6263", save: "\u4fdd\u5b58", title: "\u6298\u6263\u6388\u6743", explanation: "10.00% \u7684\u6298\u6263\u8d85\u8fc7\u4e86\u60a8\u7684 5.00% \u9650\u5236\u3002", managerUser: "\u8d1f\u8d23\u4eba\u7528\u6237\u540d", managerPassword: "\u8d1f\u8d23\u4eba\u5bc6\u7801", cancel: "\u53d6\u6d88", authorize: "\u6388\u6743" }],
  ] as const)("routes localized discount authorization to checkout in %s", async (locale, expected) => {
    const t = createTranslator(locale);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen(vi.fn(), locale, {
      session: { ...session, permissions: ["APLICAR_DESCUENTO"], maxDiscountPercent: 5 },
      interfaceMode: "TOUCH",
    });
    const search = await screen.findByRole("combobox", { name: expected.productSearch });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.click(screen.getByRole("button", { name: t("sale.main.discount") }));
    fireEvent.change(screen.getByRole("spinbutton", { name: expected.discountInput }), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: expected.save }));

    await waitFor(() => expect(
      checkoutProps.current?.saleMutationAuthorizations,
    ).toEqual([{
      code: "APPLY_SALE_DISCOUNT",
      label: t("gestion.salesOperationSecurity.operation.APPLY_SALE_DISCOUNT"),
      authorization: {
        mode: "DELEGATED",
        requireUsername: true,
        requirePassword: true,
      },
    }]));
    expect(screen.queryByRole("dialog", { name: expected.title }))
      .not.toBeInTheDocument();
  });

  it("recovers a completed durable cash close after reload without opening another session", async () => {
    saveCashCloseRecovery(localStorage, terminalContext.terminalCode, {
      closeOperationId: "11111111-1111-4111-8111-111111111111",
      reconciliationAttemptId: "22222222-2222-4222-8222-222222222222",
      phase: "ATTEMPTED",
      retainedFund: "40",
      finalWithdrawal: "10",
      comment: "Cierre",
    });
    recoverCashCloseOperation.mockResolvedValueOnce({
      operationId: "11111111-1111-4111-8111-111111111111",
      sessionId: "cash-session-1",
      terminalId: "terminal-1",
      status: "CERRADA",
      finalWithdrawalAmount: 10,
      finalWithdrawalComment: "Cierre",
      latestReconciliationAttemptId: "22222222-2222-4222-8222-222222222222",
      result: {
        id: "cash-session-1",
        terminalId: "terminal-1",
        status: "CERRADA",
        openedAt: "2026-07-25T08:00:00Z",
        openingFund: 0,
        retainedFund: 40,
        closedAt: "2026-07-25T18:00:00Z",
        reconciliationAttempt: 1,
        closedByAttempt: true,
      },
    });
    const onBack = vi.fn();

    render(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={onBack}
        onLocaleChange={vi.fn()}
      />,
    );

    await waitFor(() => expect(onBack).toHaveBeenCalledTimes(1));
    expect(recoverCashCloseOperation).toHaveBeenCalledWith(
      "terminal-1",
      "11111111-1111-4111-8111-111111111111",
      session.accessToken,
    );
    expect(prepareCashSessionForSales).not.toHaveBeenCalled();
    expect(localStorage.getItem(cashCloseRecoveryKey(terminalContext.terminalCode)))
      .toBeNull();
  });

  it("resumes an interrupted close when the durable operation was never created", async () => {
    saveCashCloseRecovery(localStorage, terminalContext.terminalCode, {
      closeOperationId: "11111111-1111-4111-8111-111111111111",
      reconciliationAttemptId: "22222222-2222-4222-8222-222222222222",
      phase: "ATTEMPTED",
      retainedFund: "10",
      finalWithdrawal: "100",
      comment: "",
    });
    recoverCashCloseOperation.mockRejectedValueOnce(new ApiError(
      "Recurso no encontrado",
      404,
      { code: "NOT_FOUND" },
    ));

    renderSaleScreen();

    expect(await screen.findByRole("dialog", { name: "Arqueo y cierre de caja" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Caja no disponible" }))
      .not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cerrar caja" }))
      .toBeEnabled();
    expect(screen.getByRole("button", { name: "Cancelar" }))
      .toBeEnabled();
    expect(screen.getByLabelText("Fondo que queda en caja"))
      .toBeEnabled();
    expect(screen.getByLabelText("Retirada final"))
      .toBeEnabled();
    expect(prepareCashSessionForSales).toHaveBeenCalledWith(
      terminalContext.terminalId,
      session.accessToken,
    );
  });

  it.each([
    ["es", { productSearch: "Buscar producto", discountInput: "Nuevo descuento", save: "Guardar", managerUser: "Usuario responsable", managerPassword: "Contraseña del responsable", authorize: "Autorizar", error: "No se pudo autorizar el descuento" }],
    ["en", { productSearch: "Search product", discountInput: "New discount", save: "Save", managerUser: "Manager username", managerPassword: "Manager password", authorize: "Authorize", error: "The discount could not be authorized" }],
    ["zh", { productSearch: "\u641c\u7d22\u5546\u54c1", discountInput: "\u65b0\u6298\u6263", save: "\u4fdd\u5b58", managerUser: "\u8d1f\u8d23\u4eba\u7528\u6237\u540d", managerPassword: "\u8d1f\u8d23\u4eba\u5bc6\u7801", authorize: "\u6388\u6743", error: "\u65e0\u6cd5\u6388\u6743\u6298\u6263" }],
  ] as const)("does not call the retired discount-token endpoint in %s", async (locale, expected) => {
    const t = createTranslator(locale);
    const fetchMock = vi.fn((url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/pos/discount-authorizations")) return Promise.resolve(new Response("", { status: 500 }));
      return Promise.resolve(new Response(JSON.stringify([products[0]]), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      }));
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen(vi.fn(), locale, {
      session: { ...session, permissions: ["APLICAR_DESCUENTO"], maxDiscountPercent: 5 },
      interfaceMode: "TOUCH",
    });
    const search = await screen.findByRole("combobox", { name: expected.productSearch });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    fireEvent.click(screen.getByRole("button", { name: t("sale.main.discount") }));
    fireEvent.change(screen.getByRole("spinbutton", { name: expected.discountInput }), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: expected.save }));

    await waitFor(() => expect(
      checkoutProps.current?.saleMutationAuthorizations?.[0],
    ).toMatchObject({
      code: "APPLY_SALE_DISCOUNT",
      authorization: { mode: "DELEGATED" },
    }));
    expect(fetchMock.mock.calls.some(([url]) =>
      new URL(String(url), "http://localhost").pathname
        .endsWith("/pos/discount-authorizations"))).toBe(false);
  });

  it.each([
    ["es", { customerTitle: "Seleccionar cliente", customerLoadError: "No se pudieron cargar los clientes" }],
    ["en", { customerTitle: "Select customer", customerLoadError: "Customers could not be loaded" }],
    ["zh", { customerTitle: "\u9009\u62e9\u5ba2\u6237", customerLoadError: "\u65e0\u6cd5\u52a0\u8f7d\u5ba2\u6237" }],
  ] as const)("localizes the customer loading error in %s", async (locale, expected) => {
    const t = createTranslator(locale);
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/customers/sale-options")) return Promise.reject(new Error("offline"));
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200, headers: { "Content-Type": "application/json" } }));
    }));
    renderSaleScreen(vi.fn(), locale, { interfaceMode: "TOUCH" });
    fireEvent.click(await screen.findByRole("button", { name: t("sale.main.customer") }));
    const dialog = await screen.findByRole("dialog", { name: expected.customerTitle });
    expect(await within(dialog).findByText(expected.customerLoadError)).toBeInTheDocument();
  });

  it("opens the cash drawer directly with F3 when the current user has permission", async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/cash-drawer/open-authorizations")) {
        expect(JSON.parse(String(init?.body))).toEqual({ terminalId: "terminal-1" });
        return new Response(JSON.stringify({
          operationId: "drawer-operation-1",
          authorizedBy: "ADMIN",
          delegated: false,
          expiresAt: "2026-07-24T12:02:00Z"
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/cash-drawer/open-authorizations/drawer-operation-1/result")) {
        expect(JSON.parse(String(init?.body))).toEqual({ opened: true });
        return new Response(JSON.stringify({ operationId: "drawer-operation-1", opened: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    const openCashDrawer = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = {
      closeApplication: vi.fn(),
      hardware: { openCashDrawer } as never
    };
    renderSaleScreen(vi.fn(), "es", {
      terminalContext: { ...terminalContext, terminalId: "terminal-1" }
    });
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(search, { key: "F3" });

    await waitFor(() => expect(openCashDrawer).toHaveBeenCalledOnce());
    expect(await screen.findByText("Cajón abierto. La operación ha quedado registrada.")).toBeInTheDocument();
  });

  it("opens the configured cash withdrawal flow with F9", async () => {
    prepareCashSessionForSales.mockResolvedValueOnce({
      cashSessionRequired: false,
      open: true,
      session: {
        id: "cash-session-1",
        terminalId: "terminal-1",
        status: "ABIERTA",
        openedAt: "2026-07-25T08:00:00Z",
        openingFund: 0,
        closedByAttempt: false,
      },
      requireWithdrawalBreakdown: true,
      withdrawalDenominations: [20, 10, 1],
    });
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response("[]", {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(search, { key: "F9" });

    const dialog = await screen.findByRole("dialog", { name: "Movimiento de efectivo" });
    expect(within(dialog).getByText("Desglose de efectivo")).toBeInTheDocument();
    expect(within(dialog).getByText("Usuario que confirma")).toBeInTheDocument();
    expect(within(dialog).getByText("admin")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Tu contraseña")).toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Usuario autorizador")).not.toBeInTheDocument();
  });

  it("requests delegated credentials for F3 when the operator lacks the permission", async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/cash-drawer/open-authorizations")) {
        expect(JSON.parse(String(init?.body))).toEqual({
          terminalId: "terminal-1",
          authorizerUsername: "encargado",
          authorizerPassword: "1234"
        });
        return new Response(JSON.stringify({
          operationId: "drawer-operation-2",
          authorizedBy: "ENCARGADO",
          delegated: true,
          expiresAt: "2026-07-24T12:02:00Z"
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/cash-drawer/open-authorizations/drawer-operation-2/result")) {
        return new Response(JSON.stringify({ operationId: "drawer-operation-2", opened: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    const openCashDrawer = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = {
      closeApplication: vi.fn(),
      hardware: { openCashDrawer } as never
    };
    renderSaleScreen(vi.fn(), "es", {
      session: { ...session, permissions: ["VENTA"] },
      terminalContext: { ...terminalContext, terminalId: "terminal-1" }
    });
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(search, { key: "F3" });
    const dialog = await screen.findByRole("dialog", { name: "Autorizar apertura de cajón" });
    expect(within(dialog).getByText("Operador actual")).toBeInTheDocument();
    expect(within(dialog).getByText("admin")).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText("Usuario autorizador"), {
      target: { value: "encargado" }
    });
    fireEvent.change(within(dialog).getByLabelText("Contraseña del autorizador"), {
      target: { value: "1234" }
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Autorizar y abrir" }));

    await waitFor(() => expect(openCashDrawer).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.queryByRole(
      "dialog",
      { name: "Autorizar apertura de cajón" },
    )).not.toBeInTheDocument());
  });

  it("opens the complete product editor with F7 for the selected ticket line", async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      if (path.endsWith("/pos/product-edit-authorizations")) {
        expect(JSON.parse(String(init?.body))).toEqual({ productId: "coffee" });
        return new Response(JSON.stringify({
          operationId: "product-edit-1",
          authorizedBy: "ADMIN",
          delegated: false,
          expiresAt: "2026-07-24T12:15:00Z",
          product: {
            id: "coffee",
            familyId: "family-1",
            taxId: "tax-iva-21",
            productType: "UNIT",
            discountType: "NORMAL",
            priceUseMode: "NORMAL",
            name: "Cafe molido",
            purchasePrice: 5,
            active: true,
            taxesIncluded: true,
            offerActive: false,
            salePrice: 10
          }
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.includes("/products/coffee/suppliers")
          || path.endsWith("/families")
          || path.endsWith("/taxes/selectable")
          || path.endsWith("/products")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.keyDown(search, { key: "F7" });

    expect(await screen.findByRole("dialog", { name: "Modificar producto" })).toBeInTheDocument();
  });

  it("uses the quick field for Pause, slash and zero plus Pause", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response("[]", {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.change(search, { target: { value: "2" } });
    fireEvent.keyDown(search, { key: "Pause" });
    expect(screen.getByRole("button", { name: /Cafe molido.*2 x 10,00/s })).toBeInTheDocument();

    fireEvent.change(search, { target: { value: "10" } });
    fireEvent.keyDown(search, { key: "/" });
    expect(screen.getByRole("button", { name: /Cafe molido.*10,00%/s })).toBeInTheDocument();

    fireEvent.change(search, { target: { value: "0" } });
    fireEvent.keyDown(search, { key: "Pause" });
    expect(screen.queryByRole("button", { name: /Cafe molido/ })).not.toBeInTheDocument();
  });

  it("shows and consumes package quantity for the next scanned product", async () => {
    const packagedProduct = { ...products[0], packageQuantity: 6 };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([packagedProduct]), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        const quote = authoritativeQuote(packagedProduct, "120.00");
        quote.lineBreakdown[0].quantity = "12";
        quote.lineBreakdown[0].baseSubtotal = "120.00";
        quote.lineBreakdown[0].finalSubtotal = "120.00";
        return new Response(JSON.stringify(quote), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response("{}", {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.change(search, { target: { value: "2" } });
    fireEvent.keyDown(search, { key: "*" });
    expect(screen.getByText("Cantidad: 2 paquetes")).toBeInTheDocument();
    submitQuickEntry(search, "CAF-001");

    await waitFor(() => expect(checkoutProps.current?.sale?.lines[0]?.quantity).toBe(12));
    expect(screen.getByText("Cantidad: 1")).toBeInTheDocument();
  });

  it("does not let Ctrl+- make the selected quantity negative", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.change(search, { target: { value: "2" } });
    fireEvent.keyDown(search, { key: "-", ctrlKey: true });
    expect(screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
    expect(screen.getByText("Ctrl+- no permite dejar una cantidad negativa")).toBeInTheDocument();
  });

  it("allows exactly -1 only through the quick field plus Pause", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.change(search, { target: { value: "-1" } });
    fireEvent.keyDown(search, { key: "Pause" });

    expect(screen.getByRole("button", { name: /Cafe molido.*-1 x 10,00/s })).toBeInTheDocument();
    expect(screen.getByText(/Devolución manual -1 aplicada/)).toBeInTheDocument();
  });

  it("records the removed line and identifies a complete cart clear", async () => {
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(String(url), "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/sale-line-deletions")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    for (let index = 0; index < 2; index += 1) {
      submitQuickEntry(search, "CAF-001");
    }

    fireEvent.change(search, { target: { value: "0" } });
    fireEvent.keyDown(search, { key: "Pause" });

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/sale-line-deletions"))).toBe(true));
    const [, request] = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/sale-line-deletions"))!;
    expect(request).toMatchObject({
      method: "POST",
      headers: expect.objectContaining({ Authorization: "Bearer access-token" }),
    });
    expect(JSON.parse(String(request?.body))).toEqual({
      saleOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      deletionOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      fullTicketClear: true,
      lines: [{
        productId: "coffee",
        code: "CAF-001",
        name: "Cafe molido",
        quantity: 2,
        unitPrice: 10,
      }],
    });
  });

  it("removes one line even when recording the best-effort event fails", async () => {
    const warning = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const fetchMock = vi.fn(async (url: string, _options?: RequestInit) => {
      const path = new URL(String(url), "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products.slice(0, 2)), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/sale-line-deletions")) {
        throw new Error("control endpoint unavailable");
      }
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    submitQuickEntry(search, "PAN-001");

    fireEvent.change(search, { target: { value: "0" } });
    fireEvent.keyDown(search, { key: "Pause" });

    expect(screen.queryByRole("button", { name: /Pan integral.*1 x 2,50/s })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
    await waitFor(() => expect(warning).toHaveBeenCalledWith("sale_line_deletion_not_recorded", expect.any(Error)));
    const [, request] = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/sale-line-deletions"))!;
    expect(JSON.parse(String(request?.body))).toMatchObject({
      saleOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      deletionOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      fullTicketClear: false,
      lines: [{ productId: "bread", code: "PAN-001", name: "Pan integral", quantity: 1, unitPrice: 2.5 }],
    });
    warning.mockRestore();
  });

  it("adds and subtracts the written operand with Ctrl++ and Ctrl+-", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.change(search, { target: { value: "9" } });
    fireEvent.keyDown(search, { key: "+", ctrlKey: true });
    expect(screen.getByRole("button", { name: /Cafe molido.*10 x 10,00/s })).toBeInTheDocument();

    fireEvent.change(search, { target: { value: "5" } });
    fireEvent.keyDown(search, { key: "-", ctrlKey: true });
    expect(screen.getByRole("button", { name: /Cafe molido.*5 x 10,00/s })).toBeInTheDocument();
  });

  it("converts the desired unit price entered before PageUp into a discount", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.change(search, { target: { value: "8" } });
    fireEvent.keyDown(search, { key: "PageUp" });

    expect(screen.getByRole("button", { name: /Cafe molido.*20,00%/s })).toBeInTheDocument();
  });

  it("focuses and selects the complete current name when the temporary name dialog opens", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.keyDown(window, { key: "Home" });

    const nameInput = await screen.findByLabelText("Nombre para esta compra") as HTMLInputElement;
    await waitFor(() => expect(nameInput).toHaveFocus());
    expect(nameInput.value).toBe("Cafe molido");
    expect(nameInput.selectionStart).toBe(0);
    expect(nameInput.selectionEnd).toBe(nameInput.value.length);
  });

  it("authorizes a temporary price when it is saved and does not defer it to checkout", async () => {
    const authorizationRequests: Array<Record<string, unknown>> = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sale-operation-authorizations/temporary-price")) {
        authorizationRequests.push(JSON.parse(String(options?.body)));
        return new Response(JSON.stringify({
          token: "temporary-price-proof",
          expiresAt: new Date(Date.now() + 20 * 60_000).toISOString(),
          authorizedBy: "ADMIN",
          delegated: false,
          policyVersion: 1,
        }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.keyDown(window, { key: "PageUp", ctrlKey: true });
    const priceInput = await screen.findByLabelText("Precio para esta compra");
    fireEvent.change(priceInput, { target: { value: "8" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));

    const authorizationDialog = await screen.findByRole("dialog", {
      name: /Autorizaci.n de la venta/i,
    });
    expect(authorizationRequests).toHaveLength(0);
    fireEvent.change(within(authorizationDialog).getByLabelText("Tu contraseña"), {
      target: { value: "secret" },
    });
    fireEvent.click(within(authorizationDialog).getByRole("button", {
      name: "Confirmar y continuar",
    }));

    await waitFor(() => expect(authorizationRequests).toHaveLength(1));
    expect(authorizationRequests[0]).toMatchObject({
      productId: "coffee",
      unitPrice: 8,
      cartLineId: expect.any(String),
      authorization: { authorizerPassword: "secret" },
    });
    await waitFor(() => expect(checkoutProps.current?.sale?.lines[0]).toMatchObject({
      productId: "coffee",
      openUnitPrice: 8,
      temporaryPriceAuthorizationToken: "temporary-price-proof",
    }));
    expect(checkoutProps.current?.saleMutationAuthorizations ?? [])
      .not.toEqual(expect.arrayContaining([
        expect.objectContaining({ code: "TEMPORARY_PRICE_CHANGE" }),
      ]));
  });

  it("cancels customer selection with Escape without changing the customer", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    expect(screen.getByRole("button", { name: /Cliente: Sin cliente/ })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Cobro" })).not.toBeInTheDocument();

    fireEvent.keyDown(window, { key: "End" });
    const customerSearch = await screen.findByRole("textbox", { name: "Buscar cliente" });
    await user.type(customerSearch, "Maria");
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Cliente: /)).not.toBeInTheDocument();
  });

  it("delegates only PageDown to checkout and ignores repeats or open modals", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) return new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/customers/sale-options")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "Cafe");
    await confirmProductSearchWithInsert(/Cafe molido/);
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());

    fireEvent.keyDown(window, { key: "PageDown" });
    expect(triggerCash).toHaveBeenCalledTimes(1);
    expect(triggerCard).not.toHaveBeenCalled();

    fireEvent.keyDown(window, { key: "PageDown", repeat: true });
    expect(triggerCash).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: "End" });
    expect(await screen.findByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "PageDown" });
    expect(triggerCash).toHaveBeenCalledTimes(1);
  });

  it("uses the latest payment state when a shortcut arrives during the enabled commit", async () => {
    checkoutHandle.dispatchCashShortcutOnEnable = true;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));

    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());
    expect(triggerCash).toHaveBeenCalledTimes(1);
  });

  it("opens the ticket commands and recovers their security configuration on demand", async () => {
    const securityConfiguration = await loadSalesOperationSecurity();
    loadSalesOperationSecurity.mockReset()
      .mockRejectedValueOnce(new Error("backend restarting"))
      .mockResolvedValue(securityConfiguration);
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/parked-sales")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/tickets")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/tickets/cancellation-preview/last")) {
        return new Response(JSON.stringify({
          ticket: { id: "ticket-1", numero: "T-001", fecha: "2026-08-01", total: "10.00" },
          manualReferences: [],
          integratedCardPayments: [],
          cashAmount: "10.00",
          openCashDrawer: true,
          consumedVoucherCodes: [],
          generatedVoucherCodes: [],
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/tickets/last-current-terminal")) {
        return new Response(JSON.stringify({
          id: "ticket-1", numero: "T-001", fecha: "2026-08-01", total: "10.00",
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/customers/sale-options")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/vouchers")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    const onOpenCustomerReceivables = vi.fn();
    render(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenCustomerReceivables={onOpenCustomerReceivables}
      />
    );
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    await waitFor(() => expect(loadSalesOperationSecurity).toHaveBeenCalledTimes(1));

    fireEvent.keyDown(window, { key: "g", ctrlKey: true });
    expect(await screen.findByRole("dialog", { name: "Ventas aparcadas" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "F10" });
    expect(onOpenCustomerReceivables).not.toHaveBeenCalled();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    fireEvent.keyDown(window, { key: "F10" });
    expect(await screen.findByRole("dialog", { name: "Devolución por ticket" })).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    fireEvent.keyDown(window, { key: "F11" });
    const cancellationDialog = await screen.findByRole("dialog", { name: "Anular ticket" });
    expect(loadSalesOperationSecurity).toHaveBeenCalledTimes(2);
    expect(cancellationDialog.parentElement).toHaveClass("sale-action-overlay");
    const cancelCancellation = within(cancellationDialog).getByRole("button", { name: "Cancelar" });
    await waitFor(() => expect(cancelCancellation).toBeEnabled());
    fireEvent.click(cancelCancellation);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    fireEvent.keyDown(window, { key: "F12" });
    const invoiceDialog = await screen.findByRole("dialog", { name: "Convertir ticket a factura" });
    expect(invoiceDialog.parentElement).toHaveClass("sale-action-overlay");
    const cancelInvoice = within(invoiceDialog).getByRole("button", { name: "Cancelar" });
    await waitFor(() => expect(cancelInvoice).toBeEnabled());
    fireEvent.click(cancelInvoice);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(onOpenCustomerReceivables).not.toHaveBeenCalled();
  });

  it("keeps the approved clear scopes and per-sale comment, discounts and print method", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/sale-line-deletions")) {
        return new Response(null, { status: 204 });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    await waitFor(() => expect(checkoutProps.current?.sale?.lines).toHaveLength(1));

    fireEvent.keyDown(window, { key: "o", ctrlKey: true });
    const commentDialog = await screen.findByRole("dialog", {
      name: "Comentario interno de la venta",
    });
    fireEvent.change(within(commentDialog).getByRole("textbox", { name: "Comentario" }), {
      target: { value: "Entregar en almacén interior" },
    });
    fireEvent.click(within(commentDialog).getByRole("button", { name: "Guardar" }));
    expect(checkoutProps.current?.sale?.internalComment).toBe(
      "Entregar en almacén interior",
    );

    fireEvent.change(search, { target: { value: "20" } });
    fireEvent.keyDown(search, { key: "/" });
    expect(checkoutProps.current?.sale?.lines[0].discount).toBe(20);
    fireEvent.keyDown(window, { key: "D", ctrlKey: true, shiftKey: true });
    expect(checkoutProps.current?.sale?.lines[0].discount).toBe(0);

    fireEvent.keyDown(window, { key: "p", ctrlKey: true });
    const printDialog = await screen.findByRole("dialog", {
      name: "Salida de impresión",
    });
    expect(within(printDialog).getByText("Usa la salida configurada para este terminal.")).toBeVisible();
    fireEvent.click(within(printDialog).getByRole("radio", { name: "Guardar como PDF" }));
    fireEvent.click(within(printDialog).getByRole("button", { name: "Aplicar" }));

    fireEvent.keyDown(window, { key: "A", ctrlKey: true, shiftKey: true });
    const clearLines = await screen.findByRole("dialog", {
      name: "Eliminar todos los artículos",
    });
    fireEvent.click(within(clearLines).getByRole("button", { name: "Eliminar artículos" }));
    expect(checkoutProps.current?.sale?.lines).toHaveLength(0);
    expect(checkoutProps.current?.sale?.internalComment).toBe(
      "Entregar en almacén interior",
    );

    fireEvent.keyDown(window, { key: "p", ctrlKey: true });
    expect(await screen.findByRole("radio", { name: "Guardar como PDF" })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));

    fireEvent.keyDown(window, { key: "F4", ctrlKey: true });
    const clearSale = await screen.findByRole("dialog", {
      name: "Eliminar venta actual",
    });
    fireEvent.click(within(clearSale).getByRole("button", { name: "Eliminar venta" }));
    expect(checkoutProps.current?.sale?.internalComment).toBeUndefined();

    fireEvent.keyDown(window, { key: "p", ctrlKey: true });
    expect(await screen.findByRole("radio", { name: "Configuración predeterminada" }))
      .toBeChecked();
  });

  it("selects the highlighted customer with Insert", async () => {
    const customerWithFinancials: SaleCustomer = {
      ...customers[1],
      activeMember: true,
      memberBalance: "12.50",
      outstandingDebt: "34.25",
      overdueDebt: "5.00",
    };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response("[]", {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/customers/sale-options")) {
        return new Response(JSON.stringify([customers[0], customerWithFinancials]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(window, { key: "End" });
    const dialog = await screen.findByRole("dialog", { name: "Seleccionar cliente" });
    await waitFor(() => expect(
      within(dialog).getByRole("button", { name: /Cliente Pruebas/ }),
    ).toHaveAttribute("aria-current", "true"));
    fireEvent.keyDown(dialog, { key: "ArrowDown" });
    fireEvent.keyDown(dialog, { key: "Insert" });

    expect(checkoutProps.current?.sale?.customerId).toBe(customers[1].id);
    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" }))
      .not.toBeInTheDocument();
    const customerSummary = screen.getByRole("button", { name: /Cliente: Maria Lopez/ });
    expect(within(customerSummary).getByText("C-002")).toBeInTheDocument();
    expect(within(customerSummary).getByText("12345678Z")).toBeInTheDocument();
    expect(within(customerSummary).getByText("12,50 €")).toBeInTheDocument();
    expect(within(customerSummary).getByText("34,25 €")).toBeInTheDocument();
    expect(within(customerSummary).getByText("5,00 €")).toBeInTheDocument();

    fireEvent.click(customerSummary);
    expect(await screen.findByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
  });

  it("loads the sale catalog from the fiscal sale endpoint", async () => {
    const apiPaths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      apiPaths.push(path.replace("/api/v1", ""));
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([{
          ...products[0],
          taxId: "tax-iva-21",
          taxesIncluded: true,
          taxPercentage: 21,
          taxRegime: "IVA",
        }]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));

    renderSaleScreen();

    await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(apiPaths).toContain("/products/sale"));
    expect(apiPaths).not.toContain("/products");
  });

  it("opens pending checkout through customer selection, uses local plus 30 days and clears only after create succeeds", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(2026, 6, 16, 12));
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify(products), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) {
        const body = JSON.parse(String(options?.body));
        const total = body.customerId === "customer-1" ? "9.50" : "10.00";
        return new Response(JSON.stringify(authoritativeQuote(products[0], total)), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/customers/sale-options")) return new Response(JSON.stringify([{ ...customers[0], activeMember: true, memberDiscountPercent: 5 }, customers[1]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/warehouses")) return new Response(JSON.stringify([{ id: "warehouse-1", defaultWarehouse: true, active: true }]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/customer-pending-sales/quote")) {
        const body = JSON.parse(String(options?.body));
        expect(body.customerId).toBe("customer-1");
        expect(body.lines[0].descuento).toBe("0.00");
        expect(body.lines[0]).not.toHaveProperty("memberDiscountPercent");
        return new Response(JSON.stringify({ total: "9.50" }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/payment-methods")) return new Response(JSON.stringify([
        { id: "cash-method", name: "EFECTIVO", active: true },
        { id: "card-method", name: "TARJETA", active: true },
        { id: "transfer-method", name: "TRANSFERENCIA", active: true },
      ]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/customer-pending-sales")) {
        const body = JSON.parse(String(options?.body));
        expect(body).toMatchObject({ customerId: "customer-1", dueDate: "2026-08-15", payments: [], quotedTotal: "9.50" });
        expect(body.lines[0].descuento).toBe("0.00");
        expect(body).not.toHaveProperty("paymentMethod");
        return new Response(JSON.stringify({ receivable: { documentId: "doc-1", documentNumber: "AV-1" }, printDocument: {
          documentId: "doc-1", documentType: "ALBARAN_VENTA", documentNumber: "AV-1",
          issueDate: "2026-07-16", lines: [], baseTotal: "9.50", taxTotal: "0.00", total: "9.50"
        } }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/terminal-configuration/payment")) return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/payment-sessions/active")) return new Response("null", { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());

    act(() => checkoutProps.current?.onLockedChange?.(true, 1000));
    act(() => checkoutProps.current?.onPending?.());
    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    act(() => checkoutProps.current?.onLockedChange?.(false));
    act(() => checkoutProps.current?.onPending?.());
    fireEvent.doubleClick(await screen.findByRole("button", { name: /Cliente Pruebas/ }));
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(screen.getByLabelText(/vencimiento/i)).toHaveValue("2026-08-15");
    await waitFor(() => expect(screen.getAllByText("9,50")).not.toHaveLength(0));
    act(() => checkoutProps.current?.onLockedChange?.(true, 1000));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    act(() => checkoutProps.current?.onLockedChange?.(false));
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());
    await act(async () => { await Promise.resolve(); });
    act(() => checkoutProps.current?.onPending?.());
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    const confirm = await screen.findByRole("button", { name: /confirmar venta pendiente/i });
    await waitFor(() => expect(confirm).toBeEnabled());
    fireEvent.click(confirm);

    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /Cafe molido.*1 x/s })).not.toBeInTheDocument();
    vi.useRealTimers();
  });

  it("shows the fiscal catalog error instead of opening a pending-sale dialog", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([{ ...products[0], taxesIncluded: null as never }]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/customers/sale-options")) {
        return new Response(JSON.stringify([customers[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/warehouses")) {
        return new Response(JSON.stringify([{ id: "warehouse-1", defaultWarehouse: true, active: true }]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));

    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());
    act(() => checkoutProps.current?.onPending?.());
    fireEvent.doubleClick(await screen.findByRole("button", { name: /Cliente Pruebas/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Producto sin configuración de impuestos válida");
    expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument();
  });

  it("auto-opens the same uncertain pending checkout after unmount and reload without requoting", async () => {
    const recoveredDraft = pendingSaleDraftForCustomer([
      { product: products[0], quantity: 1, discountPercent: 0 },
    ], { ...customers[0], activeMember: false }, "warehouse-1", new Date(2026, 6, 16, 12), "checkout-reload");
    savePendingSaleRecovery(localStorage, {
      version: 2,
      phase: "CARD_IN_FLIGHT",
      terminalCode: terminalContext.terminalCode,
      customer: { id: "customer-1", name: "Cliente Pruebas SL" },
      draft: recoveredDraft,
      quoteCents: 1_000,
      quoteReady: true,
      payments: [{ id: "checkout-reload", operationId: "checkout-reload", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 300, status: "TIMEOUT" }],
      savedAt: "2026-07-18T08:00:00.000Z",
    });
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/payment-methods")) return new Response(JSON.stringify([{ id: "card-method", name: "TARJETA", active: true }]), { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderSaleScreen();
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(screen.getByRole("button", { name: /consultar tarjeta/i })).toBeEnabled();
    cleanup();
    renderSaleScreen();
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes("/quote"))).toBe(true);
  });

  it("auto-reopens and byte-replays a READY_TO_CREATE sale without card after a lost response", async () => {
    const recoveredDraft = pendingSaleDraftForCustomer([
      { product: products[0], quantity: 1, discountPercent: 0 },
    ], { ...customers[0], activeMember: false }, "warehouse-1", new Date(2026, 6, 16, 12), "checkout-ready");
    savePendingSaleRecovery(localStorage, {
      version: 2, phase: "READY_TO_CREATE", terminalCode: terminalContext.terminalCode,
      customer: { id: "customer-1", name: "Cliente Pruebas SL" }, draft: recoveredDraft,
      quoteCents: 1_000, quoteReady: true, payments: [], savedAt: "2026-07-18T08:00:00.000Z",
      createAttempted: true,
    });
    const bodies: string[] = [];
    let creates = 0;
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale") || path.endsWith("/payment-methods")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/customer-pending-sales")) {
        bodies.push(String(options?.body)); creates += 1;
        if (creates === 1) throw new Error("response lost");
        return new Response(JSON.stringify({ receivable: { documentId: "doc-ready" }, printDocument: {} }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderSaleScreen();
    const confirmPendingSale = await screen.findByRole("button", {
      name: /confirmar venta pendiente/i,
    });
    await waitFor(() => expect(confirmPendingSale).toBeEnabled());
    fireEvent.click(confirmPendingSale);
    expect(await screen.findByRole("alert")).toHaveTextContent("response lost");
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).not.toBeNull();
    const durableDialog = screen.getByRole("dialog", { name: /venta pendiente/i });
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    fireEvent.keyDown(window, { key: "F12" });
    expect(durableDialog).toBeVisible();
    expect(durableDialog).not.toHaveAttribute("aria-hidden", "true");
    cleanup();
    renderSaleScreen();
    fireEvent.click(await screen.findByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toBe(bodies[0]);
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).toBeNull();
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes("/quote"))).toBe(true);
  });

  it("discards a legacy local-only draft instead of reopening it on a new sale entry", async () => {
    const recoveredDraft = pendingSaleDraftForCustomer([
      { product: products[0], quantity: 1, discountPercent: 0 },
    ], { ...customers[0], activeMember: false }, "warehouse-1", new Date(2026, 6, 16, 12), "checkout-stale");
    savePendingSaleRecovery(localStorage, {
      version: 2, phase: "READY_TO_CREATE", terminalCode: terminalContext.terminalCode,
      customer: { id: "customer-1", name: "Cliente Pruebas SL" }, draft: recoveredDraft,
      quoteCents: 1_000, quoteReady: true,
      payments: [{ id: "cash-stale", kind: "CASH", methodId: "cash-method", amountCents: 200,
        deliveredCents: 200, changeCents: 0, status: "APPROVED" }],
      savedAt: "2026-07-18T08:00:00.000Z",
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200, headers: { "Content-Type": "application/json" },
    })));

    renderSaleScreen();

    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).toBeNull();
  });

  it("fails closed on corrupt recovery data and exposes its recoverable identifier without deleting it", async () => {
    const raw = '{"checkoutId":"checkout-corrupt","broken":';
    localStorage.setItem(pendingSaleRecoveryKey(terminalContext.terminalCode), raw);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } })));
    const previous = document.createElement("button");
    document.body.appendChild(previous);
    previous.focus();

    renderSaleScreen();
    const dialog = await screen.findByRole("dialog", { name: /recuperaci[oó]n de cobro bloqueada/i });
    expect(dialog).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent(/no se han eliminado/i);
    expect(screen.getByText("checkout-corrupt")).toBeInTheDocument();
    const rawField = screen.getByLabelText(/datos t[eé]cnicos guardados/i);
    const copy = screen.getByRole("button", { name: /copiar datos/i });
    expect(rawField).toHaveValue(raw);
    expect(rawField).toHaveFocus();
    copy.focus(); fireEvent.keyDown(copy, { key: "Tab" }); expect(rawField).toHaveFocus();
    fireEvent.keyDown(rawField, { key: "Tab", shiftKey: true }); expect(copy).toHaveFocus();
    fireEvent.keyDown(copy, { key: "Escape" });
    expect(dialog).toBeVisible();
    expect(document.querySelector(".work-shell")).toHaveAttribute("aria-hidden", "true");
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).toBe(raw);
    fireEvent.keyDown(window, { key: "F12" });
    expect(screen.queryByRole("dialog", { name: /seleccionar cliente/i })).not.toBeInTheDocument();
    cleanup();
    expect(previous).toHaveFocus();
    previous.remove();
  });

  it("keeps editing keys local while product search accepts the sale function keys", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) return new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/customers/sale-options")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "Cafe");
    await confirmProductSearchWithInsert(/Cafe molido/);
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());

    const contentEditable = document.createElement("div");
    contentEditable.contentEditable = "true";
    contentEditable.tabIndex = 0;
    document.body.appendChild(contentEditable);
    contentEditable.focus();
    fireEvent.keyDown(contentEditable, { key: "PageDown" });
    fireEvent.keyDown(contentEditable, { key: "F11" });
    fireEvent.keyDown(contentEditable, { key: "F6" });
    expect(triggerCash).not.toHaveBeenCalled();
    expect(triggerCard).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    search.focus();
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(screen.getByRole("button", { name: /Nombre autoritativo backend.*1 x 10,00/s })).toHaveAttribute("aria-pressed", "true");

    fireEvent.change(search, { target: { value: "2" } });
    fireEvent.keyDown(search, { key: "+" });
    expect(screen.getByText("Cantidad: 2")).toBeInTheDocument();
    fireEvent.keyDown(search, { key: "End" });
    const customerDialog = await screen.findByRole("dialog", { name: /seleccionar cliente/i });
    fireEvent.click(within(customerDialog).getAllByRole("button", { name: "Cerrar" })[0]);

    fireEvent.keyDown(search, { key: "PageDown" });
    expect(triggerCash).toHaveBeenCalledOnce();
    expect(triggerCard).not.toHaveBeenCalled();
  });

  it("opens product search with Delete, transfers the quick query and clears it on close", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify(products.slice(0, 2)), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/customers/sale-options")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.change(search, { target: { value: "Cafe" } });
    fireEvent.keyDown(search, { key: "Delete" });

    const dialog = await screen.findByRole("dialog", { name: "Buscador de productos" });
    expect(within(dialog).getByRole("combobox")).toHaveValue("Cafe");
    fireEvent.keyDown(within(dialog).getByRole("combobox"), { key: "Escape" });

    expect(screen.queryByRole("dialog", { name: "Buscador de productos" })).not.toBeInTheDocument();
    expect(search).toHaveValue("");
    await waitFor(() => expect(search).toHaveFocus());
  });

  it("does not start cash payment from PageDown when checkout is disabled for an empty sale", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const cashButton = await screen.findByRole("button", { name: /Efectivo/ });
    expect(cashButton).toBeDisabled();

    fireEvent.keyDown(window, { key: "PageDown" });

    expect(triggerCash).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("moves ticket-line selection with vertical arrows without wrapping", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "Cafe");
    await confirmProductSearchWithInsert(/Cafe molido/);
    submitQuickEntry(search, "Pan");
    await confirmProductSearchWithInsert(/Pan integral/);
    const coffee = screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s });
    const bread = screen.getByRole("button", { name: /Pan integral.*1 x 2,50/s });

    expect(bread).toHaveAttribute("aria-pressed", "true");
    const handledArrow = new KeyboardEvent("keydown", { key: "ArrowUp", cancelable: true });
    act(() => window.dispatchEvent(handledArrow));
    expect(handledArrow.defaultPrevented).toBe(true);
    expect(coffee).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(coffee).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(window, { key: "ArrowDown" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(window, { key: "ArrowDown" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
  });

  it("uses vertical arrows from the product entry for the cart, but leaves other editable targets untouched", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "Cafe");
    await confirmProductSearchWithInsert(/Cafe molido/);
    submitQuickEntry(search, "Pan");
    await confirmProductSearchWithInsert(/Pan integral/);
    const coffee = screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s });
    const bread = screen.getByRole("button", { name: /Pan integral.*1 x 2,50/s });

    const editable = document.createElement("div");
    editable.contentEditable = "true";
    document.body.appendChild(editable);
    const searchArrow = new KeyboardEvent("keydown", { key: "ArrowUp", bubbles: true, cancelable: true });
    act(() => search.dispatchEvent(searchArrow));
    expect(searchArrow.defaultPrevented).toBe(true);
    expect(coffee).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(bread).toHaveAttribute("aria-pressed", "true");

    for (const target of [document.createElement("textarea"), document.createElement("select"), editable]) {
      if (!target.isConnected) document.body.appendChild(target);
      const event = new KeyboardEvent("keydown", { key: "ArrowUp", bubbles: true, cancelable: true });
      target.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
      expect(bread).toHaveAttribute("aria-pressed", "true");
    }

    fireEvent.keyDown(window, { key: "ArrowUp", repeat: true });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    act(() => checkoutProps.current?.onLockedChange?.(true, 1250));
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    act(() => checkoutProps.current?.onLockedChange?.(false));
    fireEvent.keyDown(window, { key: "End" });
    expect(await screen.findByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    expect(coffee).toHaveAttribute("aria-pressed", "false");
  });

  it("ignores a second logout click while payment preparation is pending", async () => {
    let resolvePreparation!: (result: "READY") => void;
    prepareLogout.mockImplementation(() => new Promise((resolve) => {
      resolvePreparation = resolve;
    }));
    const onLogout = renderSaleScreen();
    fireEvent.click(await logoutButton());
    fireEvent.click(await logoutButton());

    expect(onLogout).not.toHaveBeenCalled();
    resolvePreparation("READY");

    await waitFor(() => expect(onLogout).toHaveBeenCalledTimes(1));
    expect(prepareLogout).toHaveBeenCalledTimes(1);
  });

  it("preserves cash received and calculates non-negative change for individual checkout", () => {
    expect(cashResultFromFinalization("T-1", 1210, 2000)).toEqual({
      ticketNumber: "T-1",
      totalCents: 1210,
      receivedCents: 2000,
      changeCents: 790,
    });
    expect(cashResultFromFinalization("T-2", 1210, 1210)).toEqual({
      ticketNumber: "T-2",
      totalCents: 1210,
      receivedCents: 1210,
      changeCents: 0,
    });
    expect(cashResultFromFinalization("T-3", 1210, 1000).changeCents).toBe(0);
  });

  it("maps explicit checkout finalization summaries to card, cash, or mixed result details", async () => {
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized?.(printSnapshot("CARD-1"), { kind: "CARD", totalCents: 1210 }));

    const cardResult = within(screen.getByRole("region", { name: "Pago completado" }));
    expect(cardResult.getByText("Tarjeta")).toBeInTheDocument();
    expect(cardResult.queryByText("Dinero recibido")).not.toBeInTheDocument();
    expect(cardResult.queryByText("Cambio")).not.toBeInTheDocument();

    act(() => checkoutProps.current?.onFinalized?.(printSnapshot("CASH-1"), { kind: "CASH", totalCents: 1210, receivedCents: 2000 }));

    const cashResult = within(screen.getByRole("region", { name: "Pago completado" }));
    expect(cashResult.getByText("Dinero recibido")).toBeInTheDocument();
    expect(cashResult.getByText("Cambio")).toBeInTheDocument();
    expect(cashResult.getByText("7,90")).toBeInTheDocument();

    act(() => checkoutProps.current?.onFinalized?.(printSnapshot("MIXED-1"), { kind: "MIXED", totalCents: 1210 }));

    const mixedResult = within(screen.getByRole("region", { name: "Pago completado" }));
    expect(mixedResult.getByText("Mixto")).toBeInTheDocument();
    expect(mixedResult.queryByText("Dinero recibido")).not.toBeInTheDocument();
    expect(mixedResult.queryByText("Cambio")).not.toBeInTheDocument();
  });

  it("shows completed CASH checkout as PRINTING before ticket hardware settles", async () => {
    let resolvePrint!: (result: { ok: true }) => void;
    const printTicket = vi.fn(() => new Promise<{ ok: true }>((resolve) => { resolvePrint = resolve; }));
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-PRINT"), { kind: "CASH", totalCents: 1210, receivedCents: 2000 }));

    expect(screen.getByText("Pago completado")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    resolvePrint({ ok: true });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });

  it("automatically prints a pure CARD checkout ticket", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("CARD-PRINT"), { kind: "CARD", totalCents: 1210 }));

    expect(screen.getByText("Pago completado")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
    const search = screen.getByRole("combobox", { name: "Buscar producto" });
    fireEvent.keyDown(document.body, { key: "8" });
    expect(screen.queryByText("Pago completado")).not.toBeInTheDocument();
    expect(search).toHaveValue("8");
  });

  it("automatically prints a MIXED checkout ticket", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("MIXED-PRINT"), { kind: "MIXED", totalCents: 1210 }));

    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });

  it("prints the exact issued refund voucher and retries only its note", async () => {
    const voucherAttempts = new Map<string, number>();
    const printTicket = vi.fn(async (request: { documentNumber: string }) => {
      if (request.documentNumber !== "VREFUND1") return { ok: true };
      const attempt = (voucherAttempts.get(request.documentNumber) ?? 0) + 1;
      voucherAttempts.set(request.documentNumber, attempt);
      return attempt === 1
        ? { ok: false, code: "PRINT_FAILED", message: "paper jam" }
        : { ok: true };
    });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));
    const issuedVoucher = {
      code: "VREFUND1",
      amount: "10.00",
      issuedAt: "2026-08-04T12:00:00Z",
      originTicketNumber: "R-1",
    };

    act(() => checkoutProps.current?.onFinalized(
      printSnapshot("R-1"),
      { kind: "REFUND", totalCents: -1000, issuedVoucher },
    ));

    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));
    expect(printTicket.mock.calls.slice(0, 2).map(([request]) => request.documentNumber).sort())
      .toEqual(["R-1", "VREFUND1"]);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "El vale se ha generado, pero no ha sido posible imprimir su nota.",
    );

    fireEvent.click(screen.getByRole("button", {
      name: "Reintentar impresión del vale",
    }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(3));
    expect(printTicket.mock.calls[2][0].documentNumber).toBe("VREFUND1");
    expect(screen.queryByRole("button", {
      name: "Reintentar impresión del vale",
    })).not.toBeInTheDocument();
  });

  it("keeps completion after print failure and retries hardware without finalizing payment again", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const printTicket = vi.fn()
      .mockResolvedValueOnce({ ok: false, code: "PRINT_FAILED", message: "paper jam" })
      .mockResolvedValueOnce({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-RETRY"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));

    expect(await screen.findByRole("alert")).toHaveTextContent("El cobro se ha completado");
    expect(screen.queryByRole("button", { name: "Finalizar" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar impresión" }));
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/pos/cash"))).toHaveLength(0);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });

  it("keeps a late print failure recoverable after the operator has already continued", async () => {
    let resolvePrint!: (result: { ok: false; code: "PRINT_FAILED"; message: string }) => void;
    const printTicket = vi.fn(() => new Promise((resolve) => { resolvePrint = resolve; }));
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-CLOSED"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    fireEvent.pointerDown(document.body);

    resolvePrint({ ok: false, code: "PRINT_FAILED", message: "late failure" });
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText("Pago completado")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Reintentar impresi/ })).toBeEnabled();
  });

  it("does not apply an old print result to a newer completed ticket", async () => {
    const resolvers: Array<(result: { ok: boolean; code?: "PRINT_FAILED"; message?: string }) => void> = [];
    const printTicket = vi.fn(() => new Promise((resolve) => { resolvers.push(resolve); }));
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-OLD"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-NEW"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));

    resolvers[0]({ ok: false, code: "PRINT_FAILED", message: "old failure" });
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText("CASH-NEW")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    resolvers[1]({ ok: true });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });
  it("shows the authoritative reserved total when a recovered payment locks an empty local cart", () => {
    expect(saleDisplayedTotal(0, true, 0, 1210)).toBe(12.1);
    expect(saleDisplayedTotal(5, false, 0, 1210)).toBe(5);
  });
  it("renders the sales workspace with shared frame controls", () => {
    const openCustomerReceivables = vi.fn();
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenCustomerReceivables={openCustomerReceivables}
      />
    );

    expect(html).toContain('class="sale-screen work-screen keyboard-mode"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("Venta");
    expect(html).not.toContain("Añadir producto");
    expect(html).not.toContain('class="work-panel-heading sale-product-heading"');
    expect(html).not.toContain("Líneas de venta");
    expect(html).toContain("Cobro");
    expect(html).toContain('class="sale-command-menus"');
    expect(html).not.toContain('class="sale-shortcut-bar keyboard-sale-command-bar"');
    expect(html).not.toContain('class="touch-sale-actions"');
    expect(html).toContain("SISTEMA");
    expect(html).toContain("FACTURA/TICKET");
    expect(html).toContain("DOCUMENTO");
    expect(html).toContain("PRODUCTO");
    expect(html).toContain("VISUALIZACIÓN");
    expect(html).toContain("Cantidad: 1");
    expect(html.indexOf('class="sale-next-quantity"')).toBeLessThan(
      html.indexOf('class="sale-search-results"'),
    );
    expect(html).not.toContain("Sin venta iniciada");
    expect(html).toContain('aria-label="Líneas del ticket"');
    expect(html).toContain('class="sale-cart-grid-filler"');
    expect(html).toContain('aria-label="Buscar producto"');
    expect(html).toContain('aria-label="Búsqueda y cobro"');
    expect(html).not.toContain("Entrada rápida por código o código de barras");
    expect(html).toContain('placeholder="Código, código de barras o código de barras 2"');
    expect(html).toContain('aria-haspopup="dialog"');
    expect(html).toContain("Cargando productos");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Leche fresca");
    expect(html).not.toContain("15,15");
    expect(html).not.toContain("Cupón promocional");
  });

  it("renders visible function buttons in touch mode over the shared checkout", () => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        interfaceMode="TOUCH"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onOpenCustomerReceivables={vi.fn()}
        onOpenSalesDocumentWindow={vi.fn()}
      />
    );

    expect(html).toContain('class="sale-screen work-screen touch-mode"');
    expect(html).toContain('class="touch-sale-actions"');
    expect(html).not.toContain('class="sale-shortcut-bar keyboard-sale-command-bar"');
    expect(html).toContain("Buscar");
    expect(html).toContain("Factura / albarán");
    expect(html).toContain("Ventas aparcadas");
    expect(html).toContain("Anular último ticket");
    expect(html).toContain(createTranslator("es")("sale.shortcut.cancelOtherTicket"));
    expect(html).toContain("Convertir ticket a factura");
    expect(html).toContain("Efectivo");
    expect(checkoutProps.current?.showIndividualActions).toBe(true);
  });

  it.each([
    ["es", ["Venta", "Buscar producto", "Cobro"], null],
    ["en", ["Sale", "Search product", "Payment"], ["Sale", "Current ticket", "Search and payment", "Search product", "Payment", "Sale commands"]],
    ["zh", ["销售", "搜索商品", "收款"], ["销售", "当前小票", "商品搜索与收款", "搜索商品", "收款", "销售命令"]],
  ] as const)("localizes the main sale view in %s", (locale, labels, ariaLabels) => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />,
    );

    labels.forEach((label) => expect(html).toContain(label));
    ariaLabels?.forEach((label) => expect(html).toContain(`aria-label="${label}"`));
    expect(html).toContain("0,00");
  });

  it.each([
    ["es", ["Gesti\u00f3n", "Ventas aparcadas", "Guardar o recuperar", "Anular último ticket", createTranslator("es")("sale.shortcut.cancelOtherTicket"), "Convertir ticket a factura", "Importar ticket anterior"]],
    ["en", ["Management", "Parked sales", "Save or recover", "Cancel last ticket", createTranslator("en")("sale.shortcut.cancelOtherTicket"), "Convert ticket to invoice", "Import previous ticket"]],
    ["zh", ["\u7ba1\u7406", "\u6682\u5b58\u9500\u552e", "\u4fdd\u5b58\u6216\u6062\u590d", "取消上一张小票", createTranslator("zh")("sale.shortcut.cancelOtherTicket"), "小票转发票", "\u5bfc\u5165\u4e0a\u4e00\u5f20\u5c0f\u7968"]],
  ] as const)("localizes sale management actions in %s", (locale, labels) => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        interfaceMode="TOUCH"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />,
    );

    labels.slice(0, -1).forEach((label) => expect(html).toContain(label));
    expect(createTranslator(locale)("sale.shortcut.importPreviousTicket")).toBe(labels.at(-1));
  });

  it("imports a confirmed previous ticket as an immutable current-repricing block", async () => {
    const quoteBodies: Array<Record<string, unknown>> = [];
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products.slice(0, 2)), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        return new Response(JSON.stringify(previousTicketPreview({
          preservedManualDiscountAmount: "2.00",
          manualDiscountAuthorizationRequired: true,
          lines: [{
            ...previousTicketPreview().lines[0],
            manualPricePreserved: true,
            temporaryPriceAuthorizationRequired: true,
          }],
        })), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        quoteBodies.push(JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>);
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen(vi.fn(), "es", {
      session: { ...session, permissions: [] },
    });
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    await waitFor(() => expect(checkoutProps.current?.sale?.lines).toHaveLength(1));

    await importPreviousTicketFromMenu();

    await waitFor(() => expect(checkoutProps.current?.sale).toMatchObject({
      customerId: null,
      lines: [{ productId: "coffee", quantity: 1, discount: 0 }],
      previousTicketImport: {
        ticketId: "previous-ticket",
        fingerprint: "previous-ticket-fingerprint",
        serialNumbersBySourceLineId: {},
      },
      quoteFingerprint: "quote-10.00-0.00",
    }));
    expect(quoteBodies.length).toBeGreaterThan(0);
    expect(quoteBodies.every((body) => body.quoteFingerprint == null)).toBe(true);
    expect(checkoutProps.current?.onDiscount).toBeTypeOf("function");
    expect((await screen.findAllByText("Nombre autoritativo backend")).length).toBeGreaterThan(0);
    expect(screen.getByText("Precio manual del ticket conservado")).toBeInTheDocument();
    expect(checkoutProps.current?.saleMutationAuthorizations).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: "TEMPORARY_PRICE_CHANGE",
          authorization: expect.objectContaining({ mode: "DELEGATED" }),
        }),
        expect.objectContaining({
          code: "APPLY_CHECKOUT_DISCOUNT",
          authorization: expect.objectContaining({ mode: "DELEGATED" }),
        }),
      ]),
    );
    expect(await screen.findByText(/Los precios y promociones se han recalculado con las condiciones actuales/)).toBeInTheDocument();
    expect(screen.getAllByText(/condiciones actuales/)).toHaveLength(2);
    await waitFor(() => expect(search).toHaveFocus());
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/tickets/previous-current-terminal/import-preview"))).toHaveLength(1);
  });

  it("hides the historical total and stale promotions until CURRENT_REPRICING has a matching quote", async () => {
    let quoteCalls = 0;
    let resolveRepricingQuote!: (response: Response) => void;
    const pendingRepricingQuote = new Promise<Response>((resolve) => {
      resolveRepricingQuote = resolve;
    });
    const staleQuote = {
      ...authoritativeQuote(products[0]),
      promotionPreview: {
        appliedPromotions: [{ id: "stale-promotion", name: "Promoción anterior", discountAmount: "2.00" }],
        usedCoupon: { code: "CUPON-ANTERIOR", amount: "1.00" },
      },
    };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        return new Response(JSON.stringify(previousTicketPreview({ total: "99.00" })), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        quoteCalls += 1;
        return quoteCalls === 1
          ? new Response(JSON.stringify(staleQuote), {
              status: 200, headers: { "Content-Type": "application/json" },
            })
          : pendingRepricingQuote;
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    expect(await screen.findByText("Promoción anterior")).toBeInTheDocument();

    await importPreviousTicketFromMenu();

    await waitFor(() => expect(document.querySelector(".sale-total strong")?.textContent).toBe("—"));
    expect(screen.getAllByText("Calculando el total y las promociones con las condiciones actuales…").length)
      .toBeGreaterThan(0);
    expect(screen.queryByText("Promoción anterior")).not.toBeInTheDocument();
    expect(screen.queryByText("CUPON-ANTERIOR")).not.toBeInTheDocument();
    expect(document.querySelector(".sale-total strong")?.textContent).not.toBe("99,00");

    await act(async () => {
      resolveRepricingQuote(new Response("offline", { status: 503 }));
      await Promise.resolve();
    });
    expect((await screen.findAllByText(
      "No se pudo completar el recálculo; el total y las promociones no están disponibles.",
    )).length).toBeGreaterThan(0);
    expect(document.querySelector(".sale-total strong")?.textContent).toBe("—");
    expect(screen.queryByText("Promoción anterior")).not.toBeInTheDocument();
  });

  it("keeps the cart unchanged when the historical snapshot is invalid", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        const invalid = previousTicketPreview({
          ticketNumber: "001-260807-00011",
          lines: [{
            ...previousTicketPreview().lines[0],
            code: "BAD-QTY",
            quantity: 0,
          }],
        });
        return new Response(JSON.stringify(invalid), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    await waitFor(() => expect(checkoutProps.current?.sale?.lines).toHaveLength(1));

    await importPreviousTicketFromMenu();

    expect(await screen.findByText(/BAD-QTY: la cantidad del ticket no es válida/)).toBeInTheDocument();
    expect(checkoutProps.current?.sale?.lines).toMatchObject([
      { productId: "coffee", quantity: 1 },
    ]);
  });

  it("uses the historical price for a zero-price product without opening the price dialog", async () => {
    const openProduct: SaleProduct = {
      ...products[0], id: "open-1", code: "OPEN-1", name: "Abierto 1", salePrice: 0,
    };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([openProduct]), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        const preview = previousTicketPreview({
          ticketId: "previous-ticket-open",
          ticketNumber: "001-260807-00012",
          fingerprint: "open-price-fingerprint",
          total: "7.25",
          lines: [{
            ...previousTicketPreview().lines[0],
            sourceLineId: "open-source-line",
            productId: "open-1",
            code: "OPEN-1",
            name: "Abierto histórico",
            quantity: 1,
            unitPrice: "7.25",
            discount: 0,
            base: "5.99",
            tax: "1.26",
            total: "7.25",
          }],
        });
        return new Response(JSON.stringify(preview), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(openProduct, "7.25")), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    await importPreviousTicketFromMenu();
    expect(screen.queryByRole("dialog", { name: "Introducir precio" })).not.toBeInTheDocument();
    expect(await screen.findByText("Abierto histórico")).toBeInTheDocument();
    expect(checkoutProps.current?.sale?.lines).toEqual([]);
    expect(checkoutProps.current?.sale?.previousTicketImport).toMatchObject({
      ticketId: "previous-ticket-open",
      fingerprint: "open-price-fingerprint",
    });
    expect(checkoutProps.current?.saleMutationAuthorizations ?? []).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ code: "TEMPORARY_PRICE_CHANGE" }),
      ]),
    );
    expect(checkoutProps.current?.onDiscount).toBeUndefined();
  });

  it("copies and locks the historical customer, allows new positive lines, and clears replay after finalization", async () => {
    const importedCustomer: SaleCustomer = {
      ...customers[1],
      activeMember: true,
      memberCategoryName: "Socio Oro",
      memberDiscountPercent: "7.50",
      memberBalance: "12.50",
      outstandingDebt: "34.25",
      overdueDebt: "5.00",
    };
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products.slice(0, 2)), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        return new Response(JSON.stringify(previousTicketPreview({ customerId: importedCustomer.id })), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith(`/customers/sale-options/${importedCustomer.id}`)) {
        return new Response(JSON.stringify(importedCustomer), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        const request = JSON.parse(String(init?.body ?? "{}")) as CheckoutMockProps["sale"];
        const hasCurrentLine = Boolean(request?.lines.length);
        const historicalLine = authoritativeQuote(products[0], "20.00").lineBreakdown[0];
        const currentLine = authoritativeQuote(products[1], "2.50").lineBreakdown[0];
        return new Response(JSON.stringify({
          ...authoritativeQuote(products[0], hasCurrentLine ? "22.50" : "20.00"),
          productTotal: hasCurrentLine ? "22.50" : "20.00",
          lineBreakdown: hasCurrentLine ? [historicalLine, currentLine] : [historicalLine],
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    await importPreviousTicketFromMenu();

    await waitFor(() => expect(checkoutProps.current?.sale).toMatchObject({
      customerId: importedCustomer.id,
      lines: [],
      previousTicketImport: { ticketId: "previous-ticket" },
    }));
    const customerSummary = screen.getByRole("button", { name: /Cliente: Maria Lopez/ });
    expect(customerSummary).toBeDisabled();
    expect(within(customerSummary).getByText(/12,50/)).toBeInTheDocument();
    expect(within(customerSummary).getByText(/34,25/)).toBeInTheDocument();
    expect(within(customerSummary).getByText(/5,00/)).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "End" });
    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /FACTURA\/TICKET/ }));
    expect(screen.getByRole("menuitem", { name: /Devoluci.*por ticket/ })).toBeDisabled();

    submitQuickEntry(search, "PAN-001");
    await waitFor(() => expect(checkoutProps.current?.sale?.lines).toMatchObject([
      { productId: "bread", quantity: 1 },
    ]));
    expect(checkoutProps.current?.sale?.previousTicketImport).toBeDefined();

    act(() => checkoutProps.current?.onFinalized(
      printSnapshot("REPLAY-COMPLETE"),
      { kind: "CARD", totalCents: 2250 },
    ));
    expect(checkoutProps.current?.sale?.lines).toEqual([]);
    expect(checkoutProps.current?.sale?.previousTicketImport).toBeUndefined();
    expect(checkoutProps.current?.sale?.customerId).toBeNull();
  });

  it("shows historical adjustments and the exact global discount once", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        return new Response(JSON.stringify(previousTicketPreview({
          status: "ANULADO",
          pricingMode: "FROZEN_EXACT",
          globalDiscount: "10.00",
          total: "85.00",
          lines: [{ ...previousTicketPreview().lines[0], total: "100.00" }],
          adjustments: [{
            lineType: "PROMOTION_BENEFIT",
            name: "3x2 histÃ³rico",
            base: "-4.13",
            tax: "-0.87",
            total: "-5.00",
          }],
        })), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0], "85.00")), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    await importPreviousTicketFromMenu();

    expect(await screen.findByText(/3x2 hist/)).toBeInTheDocument();
    expect(screen.getByText(/Descuento global/)).toBeInTheDocument();
    expect(screen.getAllByText("-5,00")).toHaveLength(1);
    expect(screen.getAllByText("-10,00")).toHaveLength(1);
  });

  it("shows only the authoritative current promotions for a confirmed imported ticket", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        return new Response(JSON.stringify(previousTicketPreview({
          adjustments: [{
            lineType: "PROMOTION_BENEFIT",
            name: "Promoción histórica descartada",
            base: "-4.13",
            tax: "-0.87",
            total: "-5.00",
          }],
        })), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify({
          ...authoritativeQuote(products[0], "8.00"),
          promotionPreview: {
            appliedPromotions: [{
              id: "promotion-current",
              name: "Promoción vigente hoy",
              discountAmount: "-2.00",
            }],
            usedCoupon: null,
            generatedCoupon: null,
          },
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    await importPreviousTicketFromMenu();

    expect(await screen.findByText("Promoción vigente hoy")).toBeInTheDocument();
    expect(screen.queryByText("Promoción histórica descartada")).not.toBeInTheDocument();
    expect(screen.queryByText(/Ticket anterior ·/)).not.toBeInTheDocument();
  });

  it("shows the localized backend detail when the previous ticket cannot be loaded", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        return new Response(JSON.stringify({ detail: "No existe un ticket anterior importable en este terminal." }), {
          status: 409, headers: { "Content-Type": "application/problem+json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    await importPreviousTicketFromMenu();

    expect(await screen.findByText(/No existe un ticket anterior importable en este terminal/)).toBeInTheDocument();
    expect(checkoutProps.current?.sale?.lines).toEqual([]);
  });

  it("aborts an unresponsive previous-ticket request and unlocks the cart", async () => {
    let importSignal: AbortSignal | undefined;
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return Promise.resolve(new Response(JSON.stringify(products), {
          status: 200, headers: { "Content-Type": "application/json" },
        }));
      }
      if (path.endsWith("/stock/settings")) {
        return Promise.resolve(new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200, headers: { "Content-Type": "application/json" },
        }));
      }
      if (path.endsWith("/tickets/previous-current-terminal/import-preview")) {
        importSignal = init?.signal as AbortSignal | undefined;
        return new Promise<Response>((_resolve, reject) => {
          importSignal?.addEventListener("abort", () => {
            reject(new DOMException("Aborted", "AbortError"));
          }, { once: true });
        });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: /FACTURA\/TICKET/ }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Importar ticket anterior" }));
    expect(importSignal?.aborted).toBe(false);
    expect(search).toBeDisabled();

    await act(async () => {
      vi.advanceTimersByTime(12_000);
      await Promise.resolve();
      await Promise.resolve();
    });
    vi.useRealTimers();

    expect(importSignal?.aborted).toBe(true);
    expect(screen.getByText("El servidor no respondió a tiempo. El carrito no se ha modificado; vuelve a intentarlo.")).toBeInTheDocument();
    expect(search).toBeEnabled();
    expect(search).toHaveFocus();
    expect(checkoutProps.current?.sale?.lines).toEqual([]);
  });

  it("changes the selected line with touch plus and minus controls and the numeric keypad", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    renderSaleScreen(vi.fn(), "es", { interfaceMode: "TOUCH" });
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    fireEvent.click(await screen.findByRole("button", { name: /Aumentar cantidad: Cafe molido/ }));
    expect(screen.getByRole("button", { name: /Cafe molido.*2 x 10,00/s })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Reducir cantidad: Cafe molido/ }));
    expect(screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Cantidad" }));
    const dialog = screen.getByRole("dialog", { name: "Cambiar cantidad" });
    expect(within(dialog).getByRole("group", { name: "Teclado numérico" })).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Borrar todo" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "3" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));

    expect(screen.getByRole("button", { name: /Cafe molido.*3 x 10,00/s })).toBeInTheDocument();
  });

  it.each(["en", "zh"] as const)("keeps dynamic product names and codes literal in %s", async (locale) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { id: "literal-product", name: "Café 原样", code: "SKU-原样-001", salePrice: 12.34 },
    ]), { status: 200, headers: { "Content-Type": "application/json" } })));
    renderSaleScreen(vi.fn(), locale);

    const searchInput = await screen.findByRole("combobox", { name: locale === "en" ? "Search product" : "搜索商品" });
    await waitFor(() => expect(searchInput).toBeEnabled());
    submitQuickEntry(searchInput, "SKU-原样-001");

    expect(await screen.findByText("Café 原样")).toBeInTheDocument();
    expect(screen.getByText("SKU-原样-001")).toBeInTheDocument();
  });

  it.each(["en", "zh"] as const)("does not leak fixed Spanish main-view labels in %s", (locale) => {
    const html = renderToStaticMarkup(
      <SaleScreen app="venta" locale={locale} session={session} terminalContext={terminalContext} onBack={vi.fn()} onLocaleChange={vi.fn()} onLogout={vi.fn()} />,
    );

    ["Líneas de venta", "Sin venta iniciada", "Buscar producto", "Cantidad", "Descuento", "Anular línea", "Cobro"].forEach((label) => {
      expect(html).not.toContain(label);
    });
  });

  it("interpolates localized customer and product counters", () => {
    const tEn = createTranslator("en");
    const tZh = createTranslator("zh");
    expect(saleMainMessage(tEn, "sale.main.selectedCustomer", { name: "ACME" })).toBe("Customer: ACME");
    expect(saleMainProductCount(tEn, 1)).toBe("1 product");
    expect(saleMainProductCount(tEn, 2)).toBe("2 products");
    expect(saleMainProductCount(tZh, 2)).toBe("2 件商品");
  });

  it("filters products by name without case sensitivity", () => {
    expect(filterSaleProducts(products, "  CAFE ").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("filters products by internal code or barcode", () => {
    expect(filterSaleProducts(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProducts(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProducts(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("excludes inactive products unless the store setting allows them", () => {
    const inactive = { ...products[0], id: "inactive", active: false };

    expect(saleSelectableProducts([...products, inactive], false)).not.toContainEqual(inactive);
    expect(saleSelectableProducts([...products, inactive], true)).toContainEqual(inactive);
  });

  it("hides inactive products from sale search when the store setting is disabled", async () => {
    const inactive = { id: "inactive", code: "OFF-001", name: "Producto desactivado", salePrice: 3, active: false };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([inactive]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();

    const searchInput = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(searchInput).toBeEnabled());
    submitQuickEntry(searchInput, "OFF-001");

    expect(await screen.findByText("No se encontraron productos")).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Producto desactivado/ })).not.toBeInTheDocument();
  });

  it("requires Enter confirmation before adding an allowed inactive product and cancels with Escape", async () => {
    const inactive = { id: "inactive", code: "OFF-001", name: "Producto desactivado", salePrice: 3, active: false };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([inactive]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: true }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();

    const searchInput = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(searchInput).toBeEnabled());
    submitQuickEntry(searchInput, "OFF-001");

    const confirmationDialog = screen.getByRole("dialog", { name: "Producto desactivado" });
    expect(confirmationDialog).toBeInTheDocument();
    fireEvent.keyDown(confirmationDialog, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Producto desactivado" })).not.toBeInTheDocument();
    expect(screen.queryByText("Sin venta iniciada")).not.toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Líneas del ticket" })).toBeInTheDocument();

    submitQuickEntry(searchInput, "OFF-001");
    fireEvent.keyDown(screen.getByRole("dialog", { name: "Producto desactivado" }), { key: "Enter" });

    expect(screen.queryByRole("dialog", { name: "Producto desactivado" })).not.toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Líneas del ticket" })).getByText("Producto desactivado")).toBeInTheDocument();
  });

  it("limits visible search results", () => {
    const manyProducts = Array.from({ length: 12 }, (_, index) => ({
      ...products[0],
      id: String(index),
      code: `CODE-${index}`,
      name: `Product ${index}`,
      salePrice: index
    }));

    expect(filterSaleProducts(manyProducts, "product")).toHaveLength(10);
  });

  it("prioritizes an exact code or barcode when selecting with Enter", () => {
    const ambiguous: SaleProduct[] = [
      { ...products[0], id: "code-in-name", code: "OTHER", name: "Accessory CAF-001", salePrice: 3 },
      ...products
    ];

    expect(selectSaleProduct(ambiguous, "caf-001")?.id).toBe("coffee");
    expect(selectSaleProduct(products, "8410000000028")?.id).toBe("bread");
    expect(selectSaleProduct(products, "alt-cafe")?.id).toBe("coffee");
  });

  it("does not select a partial name from quick entry", () => {
    expect(selectSaleProduct(products, "leche")).toBeUndefined();
  });

  it("opens the modal for a non-exact value and adds the selected result with Insert", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    submitQuickEntry(search, "00");

    const results = await screen.findByRole("listbox", { name: "Buscador de productos" });
    const coffeeResult = within(results).getByRole("option", { name: /Cafe molido/ });
    const breadResult = within(results).getByRole("option", { name: /Pan integral/ });
    expect(coffeeResult).toHaveAttribute("aria-selected", "true");
    expect(coffeeResult).toHaveClass("selected");
    expect(breadResult).toHaveAttribute("aria-selected", "false");
    expect(search).toHaveAttribute("aria-expanded", "true");

    fireEvent.keyDown(
      screen.getByRole("combobox", { name: "Código, código de barras o nombre" }),
      { key: "Insert" },
    );

    expect(await screen.findByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Pan integral.*1 x 2,50/s })).not.toBeInTheDocument();
    expect(search).toHaveValue("");
  });

  it("moves through modal product results with vertical arrows and confirms the active result with Insert", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "00");

    const coffee = await screen.findByRole("option", { name: /Cafe molido/ });
    const bread = screen.getByRole("option", { name: /Pan integral/ });
    expect(coffee).toHaveAttribute("aria-selected", "true");

    const modalInput = screen.getByRole("combobox", { name: "Código, código de barras o nombre" });
    fireEvent.keyDown(modalInput, { key: "ArrowDown" });
    expect(bread).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(modalInput, { key: "ArrowUp" });
    expect(coffee).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(modalInput, { key: "ArrowDown" });
    fireEvent.keyDown(modalInput, { key: "Insert" });

    expect(await screen.findByRole("button", { name: /Pan integral.*1 x 2,50/s })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Cafe molido.*1 x 10,00/s })).not.toBeInTheDocument();
  });

  it("opens product information with the explicit touch action and adds from that dialog", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const parsed = new URL(url, "http://localhost");
      const path = parsed.pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products.slice(0, 2)), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/products/coffee")) {
        return new Response(JSON.stringify({
          ...products[0],
          productType: "UNIT",
          discountType: "NORMAL",
          priceUseMode: "NORMAL",
          familyId: "family-1",
          subfamilyId: "subfamily-1",
          active: true,
          offerActive: false,
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock")) {
        return new Response(JSON.stringify([
          { productId: "coffee", warehouseId: "warehouse-1", quantity: 5 },
        ]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/families")) {
        return new Response(JSON.stringify([{ id: "family-1", name: "Bebidas" }]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/families/family-1/subfamilies")) {
        return new Response(JSON.stringify([
          { id: "subfamily-1", familyId: "family-1", name: "Café" },
        ]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/taxes/selectable")) {
        return new Response(JSON.stringify([{ id: "tax-iva-21", percentage: 21 }]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        return new Response(JSON.stringify(authoritativeQuote(products[0])), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    renderSaleScreen(vi.fn(), "es", {
      session: { ...session, role: "VENTA", permissions: ["VENTA"] },
      interfaceMode: "TOUCH",
    });
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "Cafe");

    const productOption = await screen.findByRole("option", { name: /Cafe molido/ });
    fireEvent.click(productOption);
    fireEvent.click(screen.getByRole("button", { name: "Ver información" }));

    const informationDialog = await screen.findByRole("dialog", { name: "Cafe molido" });
    expect(screen.queryByRole("dialog", { name: "Buscador de productos" })).not.toBeInTheDocument();
    expect(await within(informationDialog).findByText("Información del producto")).toBeVisible();

    fireEvent.click(within(informationDialog).getByRole("button", { name: /Añadir al carrito/ }));
    expect(await screen.findByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Cafe molido" })).not.toBeInTheDocument();
  });

  it("keeps one active modal option when the query changes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "Cafe");
    expect(await screen.findByRole("option", { name: /Cafe molido/ })).toHaveAttribute("aria-selected", "true");

    const modalInput = screen.getByRole("combobox", { name: "Código, código de barras o nombre" });
    fireEvent.change(modalInput, { target: { value: "Pan" } });

    const currentOptions = screen.getAllByRole("option");
    const selectedOptions = currentOptions.filter((option) => option.getAttribute("aria-selected") === "true");
    expect(selectedOptions).toHaveLength(1);
    expect(selectedOptions[0]).toHaveAccessibleName(/Pan integral/);
  });

  it("adds products, increments repeated quantities and calculates the total", () => {
    const first = addSaleLine([], products[0]);
    const repeated = addSaleLine(first, products[0]);
    const completed = addSaleLine(repeated, products[1]);

    expect(completed.map(({ cartLineId: _cartLineId, ...line }) => line)).toEqual([
      { product: products[0], quantity: 2, discountPercent: 0 },
      { product: products[1], quantity: 1, discountPercent: 0 }
    ]);
    expect(saleTotal(completed)).toBe(22.5);
  });

  it("never merges a normal addition with an F10 return line for the same product", () => {
    const returnLine: SaleLine = {
      cartLineId: "return-line",
      product: products[0],
      quantity: -1,
      returnUnitPrice: 10,
      discountPercent: 0,
      returnOrigin: {
        sourceType: "TICKET",
        sourceCode: "001-260807-00001",
        sourceTicketId: "ticket-1",
        sourceTicketNumber: "001-260807-00001",
        sourceLineId: "source-line-1",
      },
    };

    const result = addSaleLine([returnLine], products[0]);

    expect(result).toHaveLength(2);
    expect(result[0]).toEqual(returnLine);
    expect(result[1]).toMatchObject({ product: products[0], quantity: 1, discountPercent: 0 });
    expect(result[1].returnOrigin).toBeUndefined();
  });

  it("prevalidates a previous-ticket import as one atomic batch", () => {
    const preview = previousTicketPreview({
      lines: [{ ...previousTicketPreview().lines[0], quantity: 0 }],
    });
    const prepared = preparePreviousTicketImport(preview);

    expect(prepared.lines).toEqual([]);
    expect(prepared.issues).toEqual([
      { type: "INVALID_QUANTITY", productLabel: "CAF-001" },
    ]);
  });

  it("keeps repeated historical occurrences and a new occurrence as separate lines", () => {
    const preview = previousTicketPreview({
      lines: [
        { ...previousTicketPreview().lines[0], sourceLineId: "history-1", total: "20.00" },
        { ...previousTicketPreview().lines[0], sourceLineId: "history-2", total: "18.00", discount: "28.00" },
      ],
    });

    const prepared = preparePreviousTicketImport(preview);
    const appended = appendPreviousTicketImport(
      addSaleLine([], products[0]),
      prepared.lines,
    );

    expect(prepared.issues).toEqual([]);
    expect(prepared.lines).toHaveLength(2);
    expect(appended.lines).toHaveLength(3);
    expect(new Set(appended.lines.map(saleCartLineIdentity)).size).toBe(3);
    expect(appended.lines.filter((line) => line.previousTicketImportOrigin)).toHaveLength(2);
    expect(appended.lines.filter((line) => !line.previousTicketImportOrigin)).toHaveLength(1);
  });

  it("locks historical economic fields and only allows new serials for a confirmed source", () => {
    const preview = previousTicketPreview({
      lines: [{
        ...previousTicketPreview().lines[0],
        quantity: 1,
        total: "10.00",
        serialNumbers: ["OLD-SN"],
      }],
    });
    const historical = preparePreviousTicketImport(preview).lines[0].line;

    expect(historical.serialNumbers).toEqual([]);
    expect(historical.previousTicketImportOrigin?.requiresNewSerialNumbers).toBe(true);
    expect(previousTicketImportSerialNumbersReady([historical])).toBe(false);
    expect(() => updateSaleLineQuantity([historical], saleCartLineIdentity(historical), 3)).toThrow("invalid_quantity");
    expect(() => updateSaleLineDiscount([historical], saleCartLineIdentity(historical), 10)).toThrow("historical_import_locked");
    expect(() => removeSaleLine([historical], saleCartLineIdentity(historical))).toThrow("historical_import_locked");
    const withNewSerial = updateSaleLineSerialNumbers(
      [historical],
      saleCartLineIdentity(historical),
      ["NEW-SN"],
    );
    expect(withNewSerial[0].serialNumbers).toEqual(["NEW-SN"]);
    expect(previousTicketImportSerialNumbersReady(withNewSerial)).toBe(true);
  });

  it("preserves and locks original serials when the source ticket is cancelled", () => {
    const preview = previousTicketPreview({
      status: "ANULADO",
      pricingMode: "FROZEN_EXACT",
      lines: [{
        ...previousTicketPreview().lines[0],
        quantity: 1,
        serialNumbers: ["ORIGINAL-SN"],
        requiresNewSerialNumbers: false,
      }],
    });
    const historical = preparePreviousTicketImport(preview).lines[0].line;

    expect(historical.serialNumbers).toEqual(["ORIGINAL-SN"]);
    expect(historical.previousTicketImportOrigin?.requiresNewSerialNumbers).toBe(false);
    expect(previousTicketImportSerialNumbersReady([historical])).toBe(true);
    expect(() => updateSaleLineSerialNumbers(
      [historical],
      saleCartLineIdentity(historical),
      ["REPLACEMENT-SN"],
    )).toThrow("historical_import_locked");
  });

  it("derives the exact historical total adjustment without changing product lines", () => {
    const preview = previousTicketPreview({
      status: "ANULADO",
      pricingMode: "FROZEN_EXACT",
      globalDiscount: "10.00",
      total: "85.00",
      lines: [{ ...previousTicketPreview().lines[0], total: "100.00" }],
      adjustments: [{
        lineType: "PROMOTION_BENEFIT",
        name: "Promoción histórica",
        base: "-4.13",
        tax: "-0.87",
        total: "-5.00",
      }],
    });

    expect(previousTicketImportReconciliationAdjustment(preview)).toBe(-10);
    expect(preparePreviousTicketImport(preview).lines[0].line.previousTicketImportOrigin)
      .toMatchObject({ historicalTotal: 100 });
  });

  it("keeps every open-price occurrence as an independent cart line", () => {
    const openProduct = { ...products[0], id: "open", salePrice: 0 };
    const first = addSaleLine([], openProduct, 7.25);
    const repeated = addSaleLine(first, openProduct, 2);

    expect(repeated).toHaveLength(2);
    expect(repeated[0]).toMatchObject({
      product: openProduct, quantity: 1, discountPercent: 0, openUnitPrice: 7.25,
    });
    expect(repeated[1]).toMatchObject({
      product: openProduct, quantity: 1, discountPercent: 0, openUnitPrice: 2,
    });
    expect(saleCartLineIdentity(repeated[0])).not.toBe(saleCartLineIdentity(repeated[1]));
    expect(saleLineUnitPrice(repeated[0])).toBe(7.25);
    expect(saleLineUnitPrice(repeated[1])).toBe(2);
    expect(saleTotal(repeated)).toBe(9.25);
  });

  it("updates and removes only the selected occurrence of a repeated open-price product", () => {
    const openProduct = { ...products[0], id: "open-lines", salePrice: 0 };
    const lines = addSaleLine(
      addSaleLine([], openProduct, 1),
      openProduct,
      2,
    );
    const firstId = saleCartLineIdentity(lines[0]);
    const secondId = saleCartLineIdentity(lines[1]);

    const withQuantity = updateSaleLineQuantity(lines, firstId, 3);
    const withDiscount = updateSaleLineDiscount(withQuantity, secondId, 10);
    const withSerial = updateSaleLineSerialNumbers(withDiscount, secondId, ["SN-2"]);

    expect(withSerial[0]).toMatchObject({
      quantity: 3,
      openUnitPrice: 1,
      discountPercent: 0,
    });
    expect(withSerial[1]).toMatchObject({
      quantity: 1,
      openUnitPrice: 2,
      discountPercent: 10,
      serialNumbers: ["SN-2"],
    });
    expect(removeSaleLine(withSerial, firstId)).toEqual([withSerial[1]]);
  });

  it("requires an open price only for an explicit zero catalog price", () => {
    expect(saleProductRequiresOpenPrice({ ...products[0], salePrice: 0 })).toBe(true);
    expect(saleProductRequiresOpenPrice({ ...products[0], salePrice: "0.00" })).toBe(true);
    expect(saleProductRequiresOpenPrice({ ...products[0], salePrice: null })).toBe(false);
    expect(saleProductRequiresOpenPrice({ ...products[0], salePrice: "" })).toBe(false);
    expect(saleProductRequiresOpenPrice({ ...products[0], salePrice: "invalid" })).toBe(false);
  });

  it("asks for an open price every time and sends repeated products as separate lines", async () => {
    const openProduct = {
      ...products[0],
      id: "open-product",
      code: "OPEN-001",
      name: "Producto abierto",
      salePrice: 0,
    };
    const quoteBodies: Array<Record<string, unknown>> = [];
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([openProduct]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (path.endsWith("/pos/sales/quote")) {
        const body = JSON.parse(String(options?.body)) as {
          lines: Array<{ productId: string; quantity: number; discount: number; openUnitPrice: number }>;
        };
        quoteBodies.push(body as unknown as Record<string, unknown>);
        const total = body.lines.reduce(
          (sum, line) => sum + line.quantity * line.openUnitPrice,
          0,
        );
        const breakdown = body.lines.map((line, index) => ({
          ...authoritativeQuote(openProduct, line.openUnitPrice.toFixed(2)).lineBreakdown[0],
          lineId: `product:${openProduct.id}:${index + 1}`,
          position: index + 1,
          quantity: line.quantity.toFixed(3),
          normalUnitPrice: "0.00",
          baseUnitPrice: line.openUnitPrice.toFixed(2),
          baseSubtotal: (line.quantity * line.openUnitPrice).toFixed(2),
          finalSubtotal: (line.quantity * line.openUnitPrice).toFixed(2),
        }));
        return new Response(JSON.stringify({
          total: total.toFixed(2),
          productTotal: total.toFixed(2),
          promotionPreview: { appliedPromotions: [] },
          pricingVersion: 1,
          quoteFingerprint: `quote-${body.lines.length}-${total}`,
          lineBreakdown: breakdown,
        }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    submitQuickEntry(search, "OPEN-001");
    const priceDialog = await screen.findByRole("dialog", { name: "Introducir precio" });
    fireEvent.change(within(priceDialog).getByLabelText("Precio de venta"), {
      target: { value: "1,00" },
    });
    fireEvent.click(within(priceDialog).getByRole("button", { name: "Añadir" }));

    await waitFor(() => expect(quoteBodies).toHaveLength(1));
    expect(quoteBodies[0]).toMatchObject({
      lines: [{
        productId: "open-product",
        quantity: 1,
        discount: 0,
        openUnitPrice: 1,
      }],
    });
    expect(checkoutProps.current?.sale?.lines[0]).toMatchObject({
      productId: "open-product",
      quantity: 1,
      openUnitPrice: 1,
    });

    submitQuickEntry(search, "OPEN-001");
    const secondPriceDialog = await screen.findByRole("dialog", { name: "Introducir precio" });
    fireEvent.change(within(secondPriceDialog).getByLabelText("Precio de venta"), {
      target: { value: "2,00" },
    });
    fireEvent.click(within(secondPriceDialog).getByRole("button", { name: "Añadir" }));

    await waitFor(() => expect(quoteBodies).toHaveLength(2));
    expect(quoteBodies[1]).toMatchObject({
      lines: [
        { productId: "open-product", quantity: 1, openUnitPrice: 1 },
        { productId: "open-product", quantity: 1, openUnitPrice: 2 },
      ],
    });
    expect(checkoutProps.current?.sale?.lines).toMatchObject([
      { productId: "open-product", quantity: 1, openUnitPrice: 1 },
      { productId: "open-product", quantity: 1, openUnitPrice: 2 },
    ]);
  });

  it("uses a valid member price only for an active member", () => {
    expect(effectiveSaleProductPrice({
      ...products[0],
      id: "member",
      salePrice: 10,
      memberPrice: 8.5,
      discountType: "MEMBER_PRICE"
    }, true)).toBe(8.5);
    expect(effectiveSaleProductPrice({
      ...products[0],
      id: "member",
      salePrice: 10,
      memberPrice: 8.5,
      discountType: "MEMBER_PRICE"
    }, false)).toBe(10);
    expect(effectiveSaleProductPrice({
      ...products[0],
      id: "member",
      salePrice: 10,
      memberPrice: 0,
      discountType: "MEMBER_PRICE"
    }, true)).toBe(10);
    expect(effectiveSaleProductPrice({ ...products[0], id: "normal", salePrice: 10 }, true)).toBe(10);
  });

  it("displays a current offer price and falls back after expiry", () => {

    const offered: SaleProduct = {
      ...products[0],
      id: "offer",
      salePrice: 10,
      offerPrice: 7.5,
      priceUseMode: "OFFER_PRICE",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    };
    expect(saleOfferIsCurrent(offered, "2026-07-11")).toBe(true);
    expect(effectiveSaleProductPrice(offered, false, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice(offered, false, "2026-08-01")).toBe(10);
    expect(effectiveSaleProductPrice({
      ...offered,
      offerPrice: null,
      offerDiscountPercent: 25,
      priceUseMode: "OFFER_DISCOUNT"
    }, false, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice({ ...offered, offerActive: false }, false, "2026-07-11")).toBe(10);
  });

  it("supports positive quantities and only the explicit -1 manual return", () => {
    const lines = addSaleLine([], products[0]);
    const lineId = saleCartLineIdentity(lines[0]);

    expect(updateSaleLineQuantity(lines, lineId, 4)[0].quantity).toBe(4);
    expect(updateSaleLineQuantity(lines, lineId, -1)[0].quantity).toBe(-1);
    expect(() => updateSaleLineQuantity(lines, lineId, 0)).toThrow("invalid_quantity");
    expect(() => updateSaleLineQuantity(lines, lineId, -2)).toThrow("invalid_quantity");
    expect(() => updateSaleLineQuantity(lines, lineId, 1.5)).toThrow("invalid_quantity");
    const weighted = addSaleLine([], { ...products[0], id: "weight", productType: "WEIGHT" }, undefined, 0.125);
    expect(weighted[0].quantity).toBe(0.125);
    expect(updateSaleLineQuantity(weighted, saleCartLineIdentity(weighted[0]), 4.992)[0].quantity).toBe(4.992);
    expect(() => updateSaleLineQuantity(weighted, saleCartLineIdentity(weighted[0]), 1.2345)).toThrow("invalid_quantity");
    expect(saleQuickOperand("5")).toBe(5);
    expect(saleQuickOperand("-1")).toBeNull();
    expect(salePauseQuantity("-1")).toBe(-1);
  });

  it("allows zero plus Pause to remove an F10 return without making it editable", () => {
    const returnLine = {
      ...addSaleLine([], products[0])[0],
      quantity: -1,
      returnOrigin: {
        sourceType: "TICKET" as const,
        sourceCode: "001-260805-00001",
        sourceTicketId: "ticket-1",
        sourceTicketNumber: "001-260805-00001",
        sourceLineId: "line-1",
      },
    };

    expect(salePauseQuantityAllowed(returnLine, 0)).toBe(true);
    expect(salePauseQuantityAllowed(returnLine, 1)).toBe(false);
    expect(salePauseQuantityAllowed(returnLine, -1)).toBe(false);
    expect(saleKeyboardReturnRemovalAllowed(returnLine, "0", false)).toBe(true);
    expect(saleKeyboardReturnRemovalAllowed(returnLine, "1", false)).toBe(false);
    expect(saleKeyboardReturnRemovalAllowed(returnLine, "0", true)).toBe(false);
    expect(removeSaleLine([returnLine], saleCartLineIdentity(returnLine))).toEqual([]);
  });

  it("keeps a single F10 source and merges additional units into its existing cart row", () => {
    const baseReturn: SaleLine = {
      ...addSaleLine([], products[0])[0],
      quantity: -1,
      serialNumbers: ["SN-1"],
      returnOrigin: {
        sourceType: "TICKET",
        sourceCode: "T-001",
        sourceTicketId: "ticket-1",
        sourceTicketNumber: "T-001",
        sourceLineId: "line-1",
      },
    };
    const additional: SaleLine = {
      ...baseReturn,
      cartLineId: "additional-return",
      quantity: -2,
      serialNumbers: ["SN-2"],
    };

    const merged = mergeSaleReturnLines([baseReturn], [additional]);

    expect(merged).toHaveLength(1);
    expect(merged[0].quantity).toBe(-3);
    expect(merged[0].serialNumbers).toEqual(["SN-1", "SN-2"]);
    expect(saleReturnCartReservations(merged)).toEqual([{
      sourceTicketId: "ticket-1",
      sourceCode: "T-001",
      lineId: "line-1",
      returnQuantity: 3,
      selectedSerialNumbers: ["SN-1", "SN-2"],
    }]);
    expect(saleReturnSourceConflict(merged, [{
      sourceType: "TICKET", sourceCode: "T-001", sourceTicketId: "ticket-1",
      sourceTicketNumber: "T-001", lineId: "line-2", productId: "p-1",
      code: "P-1", name: "Producto", lineType: "PRODUCT", productType: "UNIT",
      refundableQuantity: 1, unitPrice: 10, refundableTotal: 10,
      refundableSerialNumbers: [], discount: 0, taxesIncluded: true,
      taxRegime: "IVA", taxPercentage: 21, returnQuantity: 1,
      selectedSerialNumbers: [],
    }])).toBe(false);
    expect(saleReturnSourceConflict(merged, [{
      sourceType: "TICKET", sourceCode: "T-002", sourceTicketId: "ticket-2",
      sourceTicketNumber: "T-002", lineId: "line-2", productId: "p-1",
      code: "P-1", name: "Producto", lineType: "PRODUCT", productType: "UNIT",
      refundableQuantity: 1, unitPrice: 10, refundableTotal: 10,
      refundableSerialNumbers: [], discount: 0, taxesIncluded: true,
      taxRegime: "IVA", taxPercentage: 21, returnQuantity: 1,
      selectedSerialNumbers: [],
    }])).toBe(true);
  });

  it("applies a line discount and recalculates subtotal and total", () => {
    const initial = addSaleLine([], products[0]);
    const lineId = saleCartLineIdentity(initial[0]);
    const lines = updateSaleLineQuantity(initial, lineId, 2);
    const discounted = updateSaleLineDiscount(lines, lineId, 25);

    expect(discounted[0].discountPercent).toBe(25);
    expect(saleLineSubtotal(discounted[0])).toBe(15);
    expect(saleTotal(discounted)).toBe(15);
    expect(() => updateSaleLineDiscount(lines, lineId, 101)).toThrow("invalid_discount");
    expect(() => updateSaleLineDiscount(lines, lineId, 12.345)).toThrow("invalid_discount");
  });

  it("blocks manual discounts when the backend discount type is NONE", () => {
    const blockedProduct: SaleProduct = { ...products[0], discountType: "NONE" };
    const lines = addSaleLine([], blockedProduct);
    const lineId = saleCartLineIdentity(lines[0]);

    expect(saleProductBlocksManualDiscount(blockedProduct)).toBe(true);
    expect(() => updateSaleLineDiscount(lines, lineId, 10)).toThrow("discount_blocked");
    expect(updateSaleLineDiscount(lines, lineId, 0)[0].discountPercent).toBe(0);
  });

  it("keeps the next available line selected after removal", () => {
    const lines = [
      { product: products[0], quantity: 1, discountPercent: 0 },
      { product: products[1], quantity: 1, discountPercent: 0 },
      { product: products[2], quantity: 1, discountPercent: 0 }
    ];

    expect(selectedProductAfterRemoval(lines, "bread")).toBe("milk");
    expect(selectedProductAfterRemoval(lines, "milk")).toBe("bread");
  });

  it("selects ticket lines after vertical arrows and stops at the boundaries", () => {
    const lines = addSaleLine(addSaleLine([], products[0]), products[1]);
    const coffeeLineId = saleCartLineIdentity(lines[0]);
    const breadLineId = saleCartLineIdentity(lines[1]);

    expect(saleLineSelectionAfterArrow([], null, "ArrowDown")).toBeNull();
    expect(saleLineSelectionAfterArrow(lines, null, "ArrowDown")).toBe(coffeeLineId);
    expect(saleLineSelectionAfterArrow(lines, null, "ArrowUp")).toBe(breadLineId);
    expect(saleLineSelectionAfterArrow(lines, coffeeLineId, "ArrowDown")).toBe(breadLineId);
    expect(saleLineSelectionAfterArrow(lines, breadLineId, "ArrowUp")).toBe(coffeeLineId);
    expect(saleLineSelectionAfterArrow(lines, coffeeLineId, "ArrowUp")).toBe(coffeeLineId);
    expect(saleLineSelectionAfterArrow(lines, breadLineId, "ArrowDown")).toBe(breadLineId);
  });

  it("selects product search results after vertical arrows and stops at the boundaries", () => {
    expect(saleSearchSelectionAfterArrow([], "", "ArrowDown")).toBe("");
    expect(saleSearchSelectionAfterArrow(products, "", "ArrowDown")).toBe("coffee");
    expect(saleSearchSelectionAfterArrow(products, "", "ArrowUp")).toBe("milk");
    expect(saleSearchSelectionAfterArrow(products, "coffee", "ArrowUp")).toBe("coffee");
    expect(saleSearchSelectionAfterArrow(products, "coffee", "ArrowDown")).toBe("bread");
    expect(saleSearchSelectionAfterArrow(products, "milk", "ArrowDown")).toBe("milk");
  });

  it("does not increment a line above the maximum quantity", () => {
    const lines = [{ product: products[0], quantity: 9999, discountPercent: 0 }];
    expect(addSaleLine(lines, products[0])[0].quantity).toBe(9999);
  });

  it("removes only the requested sale line", () => {
    const lines = addSaleLine(addSaleLine([], products[0]), products[1]);

    expect(removeSaleLine(lines, saleCartLineIdentity(lines[0]))).toEqual([lines[1]]);
  });

  it("filters customers by name, document or client code", () => {
    expect(filterSaleCustomers(customers, "pruebas").map((customer) => customer.id)).toEqual(["customer-1"]);
    expect(filterSaleCustomers(customers, "12345678").map((customer) => customer.id)).toEqual(["customer-2"]);
    expect(filterSaleCustomers(customers, "c-002").map((customer) => customer.id)).toEqual(["customer-2"]);
  });

  it("applies the member tier discount to every product line", () => {
    const lines = addSaleLine(addSaleLine([], memberDiscountProduct), products[0]);
    const bronze: SaleCustomer = {
      id: "bronze",
      fiscalName: "Cliente Bronce",
      activeMember: true,
      memberCategoryName: "Bronce",
      memberDiscountPercent: 5
    };

    const discounted = applyMemberDiscounts(lines, bronze);

    expect(discounted[0].memberDiscountPercent).toBe(5);
    expect(effectiveSaleLineDiscount(discounted[0])).toBe(5);
    expect(discounted[1].memberDiscountPercent).toBe(5);
  });

  it("preserves a greater manual discount when a member is selected or removed", () => {
    const added = addSaleLine([], memberDiscountProduct);
    const manuallyDiscounted = updateSaleLineDiscount(added, saleCartLineIdentity(added[0]), 8);
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };

    const withMember = applyMemberDiscounts(manuallyDiscounted, bronze);
    const withoutMember = applyMemberDiscounts(withMember, null);

    expect(effectiveSaleLineDiscount(withMember[0])).toBe(8);
    expect(withMember[0].memberDiscountPercent).toBe(5);
    expect(effectiveSaleLineDiscount(withoutMember[0])).toBe(8);
    expect(withoutMember[0].memberDiscountPercent).toBe(0);
  });

  it("uses a greater member tier discount than the manual discount", () => {
    const added = addSaleLine([], memberDiscountProduct);
    const manuallyDiscounted = updateSaleLineDiscount(added, saleCartLineIdentity(added[0]), 3);
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };

    expect(effectiveSaleLineDiscount(applyMemberDiscounts(manuallyDiscounted, bronze)[0])).toBe(5);
  });

  it("applies member discount to a product added after selecting the customer", () => {
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };
    const added = applyMemberDiscounts(addSaleLine([], memberDiscountProduct), bronze);

    expect(saleTotal(added)).toBe(9.5);
  });

  it("applies member discount when the customer is selected after adding a product", () => {
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };
    const addedBeforeSelection = addSaleLine([], products[0]);

    expect(saleTotal(applyMemberDiscounts(addedBeforeSelection, bronze))).toBe(9.5);
  });

  it("keeps member pricing backend-authoritative and serializes only the manual discount", () => {
    const member: SaleCustomer = { id: "member-customer", activeMember: true, memberDiscountPercent: 5 };
    const lines = [{ ...addSaleLine([], memberDiscountProduct)[0], discountPercent: 3, memberDiscountPercent: 5 }];

    const pending = pendingSaleDraftForCustomer(lines, member, "warehouse-1", new Date(2026, 6, 16), "checkout-1");

    expect(pending.customerId).toBe("member-customer");
    expect(pending.lines[0].discount).toBe("3.00");
    expect(pending.lines[0].temporaryPriceOverride).toBe(false);
    expect(pending.lines[0]).not.toHaveProperty("memberDiscountPercent");
  });

  it("serializes explicit temporary name and price intent separately from automatic pricing", () => {
    const catalogLine = addSaleLine([], products[0])[0];
    const pending = pendingSaleDraftForCustomer(
      [{
        ...catalogLine,
        temporaryName: "Nombre solo para esta venta",
        openUnitPrice: 7.5,
      }],
      customers[0],
      "warehouse-1",
      new Date(2026, 6, 16),
      "checkout-overrides",
    );

    expect(pending.lines[0]).toMatchObject({
      name: "Nombre solo para esta venta",
      price: "7.50",
      temporaryNameOverride: true,
      temporaryPriceOverride: true,
    });
  });

  it("uses the selected customer's configured payment term", () => {
    const pending = pendingSaleDraftForCustomer(
      addSaleLine([], products[0]),
      { ...customers[0], paymentTermDays: 15 },
      "warehouse-1",
      new Date(2026, 6, 16, 23, 30),
      "checkout-terms",
    );

    expect(pending.date).toBe("2026-07-16");
    expect(pending.dueDate).toBe("2026-07-31");
  });

  it("requires a valid fiscal percentage and regime for every pending-sale line", () => {
    const validLines = addSaleLine([], products[0]);
    const customer = customers[0];
    const now = new Date(2026, 6, 16);

    expect(pendingSaleDraftForCustomer(validLines, customer, "warehouse-1", now, "checkout-1")
      .lines[0]).toMatchObject({ taxPercentage: "21.00", taxRegime: "IVA" });

    expect(() => pendingSaleDraftForCustomer(
      [{ ...validLines[0], product: { ...validLines[0].product, taxPercentage: undefined as never } }],
      customer, "warehouse-1", now, "checkout-1",
    )).toThrow("Producto sin porcentaje fiscal válido");

    expect(() => pendingSaleDraftForCustomer(
      [{ ...validLines[0], product: { ...validLines[0].product, taxRegime: "GENERAL" as never } }],
      customer, "warehouse-1", now, "checkout-1",
    )).toThrow("Producto sin régimen fiscal válido");
  });

  it("rejects empty, blank, and null fiscal percentages", () => {
    const validLine = addSaleLine([], products[0])[0];
    const customer = customers[0];
    const now = new Date(2026, 6, 16);

    for (const taxPercentage of ["", " ", null]) {
      expect(() => pendingSaleDraftForCustomer(
        [{ ...validLine, product: { ...validLine.product, taxPercentage: taxPercentage as never } }],
        customer, "warehouse-1", now, "checkout-1",
      )).toThrow("Producto sin porcentaje fiscal válido");
    }
  });

  it("rejects missing, null, and non-boolean tax inclusion flags", () => {
    const validLine = addSaleLine([], products[0])[0];
    const customer = customers[0];
    const now = new Date(2026, 6, 16);
    const { taxesIncluded: _taxesIncluded, ...withoutTaxesIncluded } = validLine.product;

    for (const product of [
      withoutTaxesIncluded,
      { ...validLine.product, taxesIncluded: undefined as never },
      { ...validLine.product, taxesIncluded: null as never },
      { ...validLine.product, taxesIncluded: "true" as never },
    ]) {
      expect(() => pendingSaleDraftForCustomer(
        [{ ...validLine, product: product as SaleProduct }],
        customer, "warehouse-1", now, "checkout-1",
      )).toThrow("Producto sin configuración de impuestos válida");
    }
  });

  it("uses confirmed server amounts in the cash payment result", () => {
    expect(resolveCashPaymentResult(
      { number: "T-42", total: "12.34", received: "50.00", change: "7.66" },
      1230,
      2000
    )).toEqual({
      ticketNumber: "T-42",
      totalCents: 1234,
      receivedCents: 2000,
      changeCents: 766
    });
  });

  it("opens direct cash completion in PRINTING with the authoritative snapshot", () => {
    const snapshot = printSnapshot("DIRECT-CASH");

    expect(cashPaymentResultForAutomaticPrinting(
      { number: "DIRECT-CASH", total: "12.10", change: "7.90", printTicket: snapshot },
      1210,
      2000,
    )).toEqual({
      ticketNumber: "DIRECT-CASH",
      totalCents: 1210,
      receivedCents: 2000,
      changeCents: 790,
      printTicket: snapshot,
      printStatus: "PRINTING",
    });
  });

  it("retains the automatic print technical failure for diagnostics without exposing it as UI text", () => {
    const current = cashPaymentResultForAutomaticPrinting(
      { number: "T-DIAG", total: "12.10", change: "0.00", printTicket: printSnapshot("T-DIAG") },
      1210,
      1210,
    );

    expect(updateCashResultPrintOutcome(current, "document-T-DIAG", {
      status: "FAILED",
      technicalMessage: "USB endpoint stalled",
    })).toMatchObject({
      printStatus: "FAILED",
      printTechnicalMessage: "USB endpoint stalled",
    });
  });

  it("replaces the retained diagnostic when a retry fails for a different technical reason", () => {
    const current = {
      ...cashPaymentResultForAutomaticPrinting(
        { number: "T-RETRY", total: "12.10", change: "0.00", printTicket: printSnapshot("T-RETRY") },
        1210,
        1210,
      ),
      printStatus: "PRINTING" as const,
      printTechnicalMessage: "paper jam",
    };

    expect(updateCashResultPrintOutcome(current, "document-T-RETRY", {
      status: "FAILED",
      technicalMessage: "printer offline",
    })).toMatchObject({
      printStatus: "FAILED",
      printTechnicalMessage: "printer offline",
    });
  });

  it("sends only the manual discount when member pricing is active", async () => {
    const activeMember: SaleCustomer = {
      id: "member-customer",
      fiscalName: "Cliente Bronce",
      activeMember: true,
      memberDiscountPercent: 5,
    };
    const snapshot: ConfirmedTicketPrintSnapshot = {
      ...printSnapshot("DIRECT-UI"),
      total: "10.00",
      lines: [{ name: "Cafe molido", quantity: "1", price: "10.00", total: "10.00" }],
      payments: [{ method: "EFECTIVO", amount: "10.00" }],
    };
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([memberDiscountProduct]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/customers/sale-options")) return new Response(JSON.stringify([activeMember]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) return new Response(JSON.stringify(authoritativeQuote(memberDiscountProduct)), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash/quote")) return new Response(JSON.stringify({ total: "10.00" }), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash")) {
        expect(options?.method).toBe("POST");
        const request = JSON.parse(String(options?.body));
        expect(request.sale).toEqual({
          customerId: "member-customer",
          lines: [{
            productId: "member-coffee",
            cartLineId: expect.any(String),
            quantity: 1,
            discount: 3,
          }],
        });
        return new Response(JSON.stringify({ number: "DIRECT-UI", total: "10.00", change: "10.00", printTicket: snapshot }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    let failFirstPrint!: (result: { ok: false; code: "PRINT_FAILED"; message: string }) => void;
    const printTicket = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { failFirstPrint = resolve; }))
      .mockResolvedValueOnce({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();

    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "MEM-CAFE");
    fireEvent.change(search, { target: { value: "3" } });
    fireEvent.keyDown(search, { key: "/" });
    fireEvent.keyDown(window, { key: "End" });
    fireEvent.doubleClick(await screen.findByRole("button", { name: /Cliente Bronce/ }));
    const cashAction = screen.getByRole("button", { name: /Efectivo/ });
    await waitFor(() => expect(cashAction).toBeEnabled());
    fireEvent.click(cashAction);
    const cashDialog = await screen.findByRole("dialog", { name: "Cobro en efectivo" });
    fireEvent.click(within(cashDialog).getByRole("button", { name: /20/ }));
    fireEvent.click(within(cashDialog).getByRole("button", { name: "Confirmar cobro" }));

    expect(await screen.findByText("Pago completado")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    expect(printTicket).toHaveBeenNthCalledWith(1, expect.objectContaining({ documentNumber: "DIRECT-UI" }), expect.anything());
    failFirstPrint({ ok: false, code: "PRINT_FAILED", message: "paper jam" });
    expect(await screen.findByRole("alert")).toHaveTextContent("El cobro se ha completado");
    expect(screen.queryByRole("button", { name: "Finalizar" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar impresión" }));

    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/pos/cash"))).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/finalize"))).toHaveLength(0);
  });

  it("excludes duplicate cash quotes and ignores a quote resolved after finalization", async () => {
    let resolveQuote!: (response: Response) => void;
    const pendingQuote = new Promise<Response>((resolve) => { resolveQuote = resolve; });
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) return new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash/quote")) return pendingQuote;
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");

    const cashAction = screen.getByRole("button", { name: /Efectivo/ });
    await waitFor(() => expect(cashAction).toBeEnabled());
    fireEvent.click(cashAction);
    fireEvent.click(cashAction);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/pos/cash/quote"))).toHaveLength(1);
    expect(cashAction).toBeDisabled();
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CARD-WINS"), { kind: "CARD", totalCents: 1000 }));
    resolveQuote(new Response(JSON.stringify({ total: "10.00" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByText("CARD-WINS")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Cobro en efectivo" })).not.toBeInTheDocument();
  });

  it("ignores an obsolete quote rejection after another payment finalizes", async () => {
    let rejectQuote!: (error: Error) => void;
    const pendingQuote = new Promise<Response>((_resolve, reject) => { rejectQuote = reject; });
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/sales/quote")) return new Response(JSON.stringify(authoritativeQuote(products[0])), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash/quote")) return pendingQuote;
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    submitQuickEntry(search, "CAF-001");
    const cashAction = screen.getByRole("button", { name: /Efectivo/ });
    await waitFor(() => expect(cashAction).toBeEnabled());
    fireEvent.click(cashAction);
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/pos/cash/quote"))).toHaveLength(1));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CARD-WINS-ERROR"), { kind: "CARD", totalCents: 1000 }));

    rejectQuote(new Error("stale quote failure"));
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByText("CARD-WINS-ERROR")).toBeInTheDocument();
    expect(screen.queryByText("stale quote failure")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Cobro en efectivo" })).not.toBeInTheDocument();
  });

  it("reads the current cash mode on every opening", () => {
    let value = "touch";
    const storage = { getItem: vi.fn(() => value) } as unknown as Storage;

    expect(readCashModeForOpening(storage)).toBe("touch");
    value = "keyboard";
    expect(readCashModeForOpening(storage)).toBe("keyboard");
    expect(storage.getItem).toHaveBeenCalledTimes(2);
  });

  it("transitions a successful payment to a clean sale with a result", () => {
    const result = { ticketNumber: "T-44", totalCents: 1200, receivedCents: 2000, changeCents: 800 };
    expect(cashPaymentSuccessTransition(result)).toEqual({
      cashDialogOpen: false,
      cashResult: result,
      lines: [],
      selectedLineId: null,
      selectedCustomer: null,
      query: ""
    });
  });

  it("keeps the sale snapshot and dialog on a payment error", () => {
    const snapshot = { cashDialogOpen: true, lines: [{ id: "line" }], selectedLineId: "line-coffee" };
    expect(cashPaymentErrorTransition(snapshot, "Servidor no disponible")).toEqual({
      ...snapshot,
      cashError: "Servidor no disponible"
    });
  });

  it("finishes the result by clearing it and restoring search focus", () => {
    const clear = vi.fn();
    const focus = vi.fn();
    finishCashPaymentResult(clear, focus);
    expect(clear).toHaveBeenCalledWith(null);
    expect(focus).toHaveBeenCalledOnce();
  });

  it("allows only one immediate cash submission until the first settles", async () => {
    const guard = { current: false };
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const request = vi.fn(() => pending);

    const first = runGuardedCashSubmission(guard, request);
    const second = runGuardedCashSubmission(guard, request);
    expect(request).toHaveBeenCalledOnce();
    expect(await second).toBe(false);
    release();
    expect(await first).toBe(true);
    expect(guard.current).toBe(false);
  });

  it("falls back safely to the quote and sent cash when optional server amounts are absent", () => {
    expect(resolveCashPaymentResult(
      { number: "T-43", change: "7.70" },
      1230,
      2000
    )).toEqual({
      ticketNumber: "T-43",
      totalCents: 1230,
      receivedCents: 2000,
      changeCents: 770
    });
  });

  it("clears the sale only when a card payment is approved", () => {
    expect(resolveCardPaymentOutcome({ status: "APPROVED", ticketNumber: "T-9", total: "12.34", authorization: "AUTH-1" }, 1200).clearSale).toBe(true);
    expect(resolveCardPaymentOutcome({ status: "DECLINED", message: "Denegada" }, 1200)).toMatchObject({ clearSale: false, retryable: true });
  });

  it("does not offer a new checkout after an uncertain timeout", () => {
    expect(resolveCardPaymentOutcome({ status: "TIMEOUT", message: "Sin respuesta" }, 1200)).toMatchObject({ clearSale: false, retryable: false, uncertain: true });
    expect(cardRetryCheckoutId("TIMEOUT", () => "new-id")).toBeNull();
    expect(cardRetryCheckoutId("DECLINED", () => "new-id")).toBe("new-id");
    expect(cardRetryCheckoutId("ERROR", () => "new-id")).toBe("new-id");
    expect(cardRetryCheckoutId("CANCELLED", () => "new-id")).toBe("new-id");
  });

  it("retains the checkout and request body after a transport failure", () => {
    expect(cardTransportFailureOutcome("checkout-1", "Sin conexión")).toMatchObject({ status: "UNCERTAIN", checkoutId: "checkout-1", uncertain: true });
    expect(buildCardChargeBody("checkout-1", { customerId: null, lines: [] }, 1234)).toEqual({ checkoutId: "checkout-1", sale: { customerId: null, lines: [] }, quotedTotal: "12.34" });
  });

  it("guards quote and initial charge as one synchronous card opening", async () => {
    const guard = { current: false, generation: 0 };
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const quoteAndCharge = vi.fn(async () => pending);
    const first = runGuardedCardOpening(guard, quoteAndCharge);
    const second = runGuardedCardOpening(guard, quoteAndCharge);
    expect(quoteAndCharge).toHaveBeenCalledOnce();
    expect(await second).toBe(false);
    release();
    expect(await first).toBe(true);
    expect(guard.current).toBe(false);
  });

  it("releases a failed opening and ignores stale completion tokens", async () => {
    const guard = { current: false, generation: 0 };
    await expect(runGuardedCardOpening(guard, async () => { throw new Error("quote failed"); })).rejects.toThrow("quote failed");
    expect(guard.current).toBe(false);
    expect(await runGuardedCardOpening(guard, async (opening) => {
      guard.generation += 1;
      expect(opening.isCurrent()).toBe(false);
    })).toBe(true);
  });
});

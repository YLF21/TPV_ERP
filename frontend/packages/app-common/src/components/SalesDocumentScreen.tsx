import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from "react";
import { ApiError, apiRequest } from "../api/client";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  formatQuantityValue,
  isProductQuantityPrecisionValid,
  normalizeProductQuantity,
  parseProductQuantityInput,
  productQuantityStep,
} from "../sale/productQuantity";
import {
  addLocalDays,
  pendingCreateBody,
  resolvePendingCardPaymentMode,
  type PendingCardPaymentMode,
  type PendingSaleDraft,
  type PendingTerminalPaymentConfiguration,
} from "../sale/customerReceivables";
import {
  findSaleOperationAuthorization,
  loadSalesOperationSecurity,
  type SaleOperationCredentials,
  type SalesOperationSecurityConfiguration,
} from "../sale/operationSecurity";
import {
  detectSaleMutationOperations,
  saleMutationAuthorizationRequirements,
  saleMutationCredentialsRequired,
  type SaleMutationOperationAuthorizations,
} from "../sale/saleMutationAuthorizations";
import { saleCommandFromKeyboard, type SaleCommandId } from "../sale/saleCommands";
import type { PendingSaleRecoveryEnvelope } from "../sale/pendingSaleRecovery";
import { retryPrintSucceeded } from "../sale/printRetry";
import type { LocaleCode, TerminalContext, UserSession } from "../types";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import { CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";
import {
  addSaleLine,
  createSaleCartLineId,
  effectiveSaleLineDiscount,
  saleCartLineIdentity,
  saleLineHasValidTemporaryPriceAuthorization,
  saleLineSelectionAfterArrow,
  saleLineSubtotal,
  saleLineUnitPrice,
  salePauseQuantity,
  saleProductBlocksManualDiscount,
  saleProductFiscalSnapshot,
  saleProductRequiresOpenPrice,
  saleTotal,
  selectSaleProduct,
  selectedProductAfterRemoval,
  updateSaleLineDiscount,
  updateSaleLineQuantity,
  updateSaleLineSerialNumbers,
  updateSaleLineTemporaryName,
  updateSaleLineTemporaryPrice,
  type SaleCustomer,
  type SaleLine,
  type SaleProduct,
} from "./SaleScreen";
import { SaleMutationAuthorizationDialog } from "./SaleMutationAuthorizationDialog";
import { SaleOpenPriceDialog } from "./SaleOpenPriceDialog";
import { SaleProductInformationDialog } from "./SaleProductInformationDialog";
import { SaleProductSearchDialog } from "./SaleProductSearchDialog";
import { SaleSerialNumberDialog } from "./SaleSerialNumberDialog";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { sortTableRows, useTableSortPreference } from "./tableSorting";
import {
  SalesDocumentDraftDialog,
  type SalesDocumentDraftDetail,
} from "./SalesDocumentDraftDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { userCanManageStockProducts } from "./stockAccess";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

type DocumentType = "FACTURA_VENTA" | "ALBARAN_VENTA";
type CheckoutMode = "CONFIRM_PENDING" | "CONFIRM_AND_PAY";
type LineEditAction = "temporaryName" | "temporaryPrice";
type SalesDocumentLineColumnKey =
  | "code"
  | "name"
  | "quantity"
  | "price"
  | "discount"
  | "total";
type WarehouseOption = {
  id: string;
  active?: boolean;
  defaultWarehouse?: boolean;
  isDefaultWarehouse?: boolean;
};

type Props = {
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  interfaceMode?: SaleInterfaceMode;
};

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;

const salesDocumentLineTableKey = "sales-document.lines.v1";
const salesDocumentLineColumnDefinitions = [
  { key: "code", defaultWidth: 150, minWidth: 72 },
  { key: "name", defaultWidth: 360, minWidth: 140 },
  { key: "quantity", defaultWidth: 90, minWidth: 64 },
  { key: "price", defaultWidth: 110, minWidth: 72 },
  { key: "discount", defaultWidth: 110, minWidth: 76 },
  { key: "total", defaultWidth: 120, minWidth: 76 },
] as const satisfies readonly TableColumnDefinition<SalesDocumentLineColumnKey>[];

const salesDocumentNumericColumns = new Set<SalesDocumentLineColumnKey>([
  "quantity",
  "price",
  "discount",
  "total",
]);

function localDate() {
  return addLocalDays(new Date(), 0);
}

function formatMoney(value: number, locale: LocaleCode) {
  return new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

function recoveryStorageKey(terminalCode: string) {
  return `tpverp.sales-document-checkout.${terminalCode}`;
}

function formatPercentage(value: number, locale: LocaleCode) {
  if (value <= 0) return "";
  return `${new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale, {
    maximumFractionDigits: 2,
  }).format(value)} %`;
}

function shortcutTargetIsEditable(target: EventTarget | null) {
  return target instanceof HTMLElement && (
    target.matches("input, textarea, select")
    || target.isContentEditable
    || target.contentEditable === "true"
    || target.closest('[contenteditable]:not([contenteditable="false"])') !== null
  );
}

function readRecovery(terminalCode: string) {
    try {
      const raw = localStorage.getItem(recoveryStorageKey(terminalCode));
      if (!raw) return null;
      const parsed = JSON.parse(raw) as PendingSaleRecoveryEnvelope;
      if (parsed?.version !== 2 || parsed.terminalCode !== terminalCode) return null;
      return parsed;
    } catch {
      return null;
    }
}

export function SalesDocumentScreen({
  locale,
  session,
  terminalContext,
  interfaceMode = "KEYBOARD",
}: Props) {
  const t = createTranslator(locale);
  const [documentType, setDocumentType] = useState<DocumentType>("FACTURA_VENTA");
  const [products, setProducts] = useState<SaleProduct[]>([]);
  const [lines, setLines] = useState<SaleLine[]>([]);
  const [selectedLineId, setSelectedLineId] = useState<string | null>(null);
  const [nextScanQuantity, setNextScanQuantity] = useState(1);
  const [nextScanMode, setNextScanMode] = useState<"UNIT" | "PACKAGE">("UNIT");
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [customer, setCustomer] = useState<SaleCustomer | null>(null);
  const [selectedCustomerResultId, setSelectedCustomerResultId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [query, setQuery] = useState("");
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [draftsOpen, setDraftsOpen] = useState(false);
  const [editingDraft, setEditingDraft] = useState<{ id: string; version: number } | null>(null);
  const [issueDate, setIssueDate] = useState(localDate);
  const [importedDueDate, setImportedDueDate] = useState<string | null>(null);
  const [importedGlobalDiscount, setImportedGlobalDiscount] = useState("0.00");
  const [importedInternalComment, setImportedInternalComment] = useState<string | null>(null);
  const [wholesaleMode, setWholesaleMode] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [productSearchQuery, setProductSearchQuery] = useState("");
  const [productSearchSelectedId, setProductSearchSelectedId] = useState("");
  const [productInformationProduct, setProductInformationProduct] =
    useState<SaleProduct | null>(null);
  const [customerOpen, setCustomerOpen] = useState(false);
  const [customerQuery, setCustomerQuery] = useState("");
  const [pendingOpenPriceProduct, setPendingOpenPriceProduct] = useState<SaleProduct | null>(null);
  const [pendingOpenPriceQuantity, setPendingOpenPriceQuantity] = useState(1);
  const [lineEditAction, setLineEditAction] = useState<LineEditAction | null>(null);
  const [lineEditValue, setLineEditValue] = useState("");
  const [lineEditError, setLineEditError] = useState("");
  const [serialNumberOpen, setSerialNumberOpen] = useState(false);
  const [pendingTemporaryPriceChange, setPendingTemporaryPriceChange] = useState<{
    lineId: string;
    productId: string;
    unitPrice: number;
  } | null>(null);
  const [temporaryPriceAuthorizationBusy, setTemporaryPriceAuthorizationBusy] = useState(false);
  const [temporaryPriceAuthorizationError, setTemporaryPriceAuthorizationError] = useState("");
  const [draftAuthorizationOpen, setDraftAuthorizationOpen] = useState(false);
  const [draftAuthorizationError, setDraftAuthorizationError] = useState("");
  const [shortcutMessage, setShortcutMessage] = useState("");
  const [shortcutError, setShortcutError] = useState("");
  const [checkoutId, setCheckoutId] = useState(uuid);
  const [checkoutMode, setCheckoutMode] = useState<CheckoutMode | null>(null);
  const [recovery, setRecovery] = useState<PendingSaleRecoveryEnvelope | null>(
    () => readRecovery(terminalContext.terminalCode),
  );
  const [quotedTotal, setQuotedTotal] = useState<number | null>(null);
  const [quoteLoading, setQuoteLoading] = useState(false);
  const [quoteError, setQuoteError] = useState("");
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState("");
  const [pendingPrintRetry, setPendingPrintRetry] = useState<(() => Promise<unknown>) | null>(null);
  const [printFailureMessage, setPrintFailureMessage] = useState("");
  const [printRetrying, setPrintRetrying] = useState(false);
  const [operationSecurity, setOperationSecurity] =
    useState<SalesOperationSecurityConfiguration | null>(null);
  const [cardPaymentMode, setCardPaymentMode] =
    useState<PendingCardPaymentMode | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const customerDialogRef = useRef<HTMLElement>(null);
  const lineEditDialogRef = useRef<HTMLElement>(null);
  const lineEditInputRef = useRef<HTMLInputElement>(null);
  const lineTableLayout = useTableLayoutPreference({
    app: "venta",
    username: session.username,
    accessToken: session.accessToken,
    tableKey: salesDocumentLineTableKey,
    definitions: salesDocumentLineColumnDefinitions,
  });
  const lineTableSorting = useTableSortPreference({
    app: "venta",
    username: session.username,
    tableKey: salesDocumentLineTableKey,
    columns: salesDocumentLineColumnDefinitions.map((column) => column.key),
    defaultSort: null,
  });
  const dueDate = importedDueDate ?? (customer
    ? addLocalDays(new Date(`${issueDate}T12:00:00`), Math.max(0, customer.paymentTermDays ?? 30))
    : issueDate);
  const effectiveDraftId = editingDraft?.id ?? recovery?.sourceDocumentId ?? null;
  const activeMember = customer?.activeMember === true;
  const visibleLineColumns = visibleTableColumns(lineTableLayout.layout);
  const lineTableWidth = visibleLineColumns.reduce(
    (totalWidth, column) => totalWidth + column.width,
    0,
  );
  const sortedLines = useMemo(() => sortTableRows(
    lines,
    lineTableSorting.sort,
    (line, column) => {
      if (column === "code") return line.product.code ?? line.product.barcode;
      if (column === "name") return line.temporaryName ?? line.product.name;
      if (column === "quantity") return line.quantity;
      if (column === "price") return saleLineUnitPrice(line, activeMember, wholesaleMode);
      if (column === "discount") return effectiveSaleLineDiscount(line);
      return saleLineSubtotal(line, activeMember, wholesaleMode);
    },
    locale,
  ), [activeMember, lineTableSorting.sort, lines, locale, wholesaleMode]);
  const selectedLine = lines.find((line) => saleCartLineIdentity(line) === selectedLineId);
  const fallbackTotal = saleTotal(lines, activeMember, wholesaleMode);
  const total = quotedTotal ?? fallbackTotal;
  const canWrite = hasPermission(session, "ADMIN")
    || hasPermission(session, "VENTA")
    || hasPermission(session, "GESTION_VENTAS")
    || hasPermission(session, documentType === "FACTURA_VENTA"
      ? "INVOICES_WRITE" : "DELIVERY_NOTES_WRITE");
  const ready = canWrite && Boolean(customer && warehouseId && lines.length > 0)
    && !quoteLoading && !quoteError && quotedTotal != null;
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
  const transferPaymentAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CONFIRM_TRANSFER_PAYMENT",
    session.permissions,
  );
  const manualCardPaymentAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CONFIRM_MANUAL_CARD_PAYMENT",
    session.permissions,
  );
  const temporaryPriceChangeAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "TEMPORARY_PRICE_CHANGE",
    session.permissions,
  );
  const canApplyManualDiscount = Boolean(operationSecurity?.operations.some(
    (operation) => operation.code === "APPLY_SALE_DISCOUNT",
  ));
  const detectedSaleMutationOperations = detectSaleMutationOperations(lines.map((line) => ({
    quantity: line.quantity,
    discountPercent: line.discountPercent,
    catalogName: line.product.name,
    temporaryName: line.temporaryName,
    catalogUnitPrice: line.product.salePrice,
    openUnitPrice: line.openUnitPrice,
  })));
  const temporaryPriceLines = lines.filter((line) => (
    line.openUnitPrice != null && Number(line.product.salePrice ?? 0) !== 0
  ));
  const temporaryPriceAuthorizationsReady = temporaryPriceLines.every((line) => (
    saleLineHasValidTemporaryPriceAuthorization(
      line,
      Date.now(),
      operationSecurity?.version,
    )
  ));
  const saleMutationAuthorizations = saleMutationAuthorizationRequirements(
    operationSecurity,
    detectedSaleMutationOperations.filter((operation) => (
      operation.code !== "TEMPORARY_PRICE_CHANGE" || !temporaryPriceAuthorizationsReady
    )).map((operation) => ({
      ...operation,
      label: t(`gestion.salesOperationSecurity.operation.${operation.code}`),
    })),
    session.permissions,
    session.permissions.includes("ADMIN")
      ? 100
      : Number(session.maxDiscountPercent ?? 0),
  );

  const customerResults = useMemo(() => {
    const normalized = customerQuery.trim().toLocaleLowerCase();
    if (!normalized) return customers.slice(0, 100);
    return customers.filter((option) => [
      option.clientId, option.fiscalName, option.documentNumber,
    ].some((value) => value?.toLocaleLowerCase().includes(normalized))).slice(0, 100);
  }, [customerQuery, customers]);
  const customerSelectionIds = useMemo(
    () => customerResults.map((option) => option.id),
    [customerResults],
  );

  function closeCustomerDialog() {
    setCustomerOpen(false);
    setCustomerQuery("");
  }

  function chooseDocumentCustomer(option: SaleCustomer) {
    setCustomer(option);
    setImportedDueDate(null);
    setSelectedCustomerResultId(option.id);
    closeCustomerDialog();
    invalidate();
    queueMicrotask(() => inputRef.current?.focus());
  }

  function scrollCustomerSelectionIntoView(customerId: string) {
    queueMicrotask(() => {
      document.getElementById(
        `sales-document-customer-option-${encodeURIComponent(customerId)}`,
      )?.scrollIntoView?.({ block: "nearest" });
    });
  }

  function handleCustomerDialogKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      closeCustomerDialog();
      return;
    }
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      event.stopPropagation();
      if (customerSelectionIds.length === 0) return;
      const currentIndex = customerSelectionIds.indexOf(selectedCustomerResultId);
      const nextIndex = currentIndex < 0
        ? event.key === "ArrowDown" ? 0 : customerSelectionIds.length - 1
        : (
            currentIndex
            + (event.key === "ArrowDown" ? 1 : -1)
            + customerSelectionIds.length
          ) % customerSelectionIds.length;
      const nextId = customerSelectionIds[nextIndex];
      setSelectedCustomerResultId(nextId);
      scrollCustomerSelectionIntoView(nextId);
      return;
    }
    if (event.key !== "Insert") return;
    event.preventDefault();
    event.stopPropagation();
    const selected = customerResults.find(
      (option) => option.id === selectedCustomerResultId,
    );
    if (selected) chooseDocumentCustomer(selected);
  }

  useEffect(() => {
    if (!customerOpen) return;
    setSelectedCustomerResultId((current) => {
      if (customerSelectionIds.includes(current)) return current;
      if (customer?.id && customerSelectionIds.includes(customer.id)) return customer.id;
      return customerSelectionIds[0] ?? "";
    });
  }, [customer?.id, customerOpen, customerSelectionIds]);

  useEffect(() => {
    if (!customerOpen || !customerDialogRef.current) return;
    const dialog = customerDialogRef.current;
    const deactivate = activateModalFocusTrap(
      dialog as unknown as ModalFocusRoot,
      document,
    );
    return deactivate;
  }, [customerOpen]);

  useEffect(() => {
    if (!lineEditAction || !lineEditDialogRef.current) return;
    const dialog = lineEditDialogRef.current;
    const deactivate = activateModalFocusTrap(
      dialog as unknown as ModalFocusRoot,
      document,
    );
    lineEditInputRef.current?.focus();
    lineEditInputRef.current?.select();
    return deactivate;
  }, [lineEditAction]);

  function invalidate() {
    setCheckoutId(uuid());
    setQuotedTotal(null);
    setQuoteError("");
    setStatus("");
  }

  function clearShortcutFeedback() {
    setShortcutMessage("");
    setShortcutError("");
  }

  function clearQuickEntry(message = "") {
    setQuery("");
    setShortcutError("");
    setShortcutMessage(message);
    queueMicrotask(() => inputRef.current?.focus());
  }

  function reportShortcutError(message: string) {
    setShortcutMessage("");
    setShortcutError(message);
  }

  function resetDocument(message = "") {
    setLines([]);
    setSelectedLineId(null);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setCustomer(null);
    setEditingDraft(null);
    setDraftsOpen(false);
    setIssueDate(localDate());
    setImportedDueDate(null);
    setImportedGlobalDiscount("0.00");
    setImportedInternalComment(null);
    setWholesaleMode(false);
    setQuery("");
    setSearchOpen(false);
    setProductSearchQuery("");
    setProductSearchSelectedId("");
    setProductInformationProduct(null);
    setCheckoutId(uuid());
    setQuotedTotal(null);
    setQuoteError("");
    setCheckoutMode(null);
    setRecovery(null);
    setLineEditAction(null);
    setSerialNumberOpen(false);
    setDraftAuthorizationOpen(false);
    setDraftAuthorizationError("");
    clearShortcutFeedback();
    localStorage.removeItem(recoveryStorageKey(terminalContext.terminalCode));
    setStatus(message);
    queueMicrotask(() => inputRef.current?.focus());
  }

  async function retryPendingPrint() {
    if (!pendingPrintRetry || printRetrying) return;
    setPrintRetrying(true);
    try {
      if (await retryPrintSucceeded(pendingPrintRetry)) {
        setPendingPrintRetry(null);
        setPrintFailureMessage("");
      }
    } finally {
      setPrintRetrying(false);
    }
  }

  function draft(mode: PendingSaleDraft["completionMode"]): PendingSaleDraft {
    if (!customer || !warehouseId) throw new Error(t("salesDocument.validation.customer"));
    return {
      checkoutId,
      warehouseId,
      type: documentType,
      date: issueDate,
      customerId: customer.id,
      dueDate,
      ...(wholesaleMode ? { wholesaleMode: true } : {}),
      // Sales-document percentages use the same backend allocator as Ctrl+/.
      // Keep the legacy field at zero so protected products are never reduced
      // by CommercialDocument's historical all-lines factor.
      globalDiscount: "0.00",
      documentDiscountPercent: importedGlobalDiscount,
      ...(importedInternalComment ? { internalComment: importedInternalComment } : {}),
      ...(editingDraft ? { draftVersion: editingDraft.version } : {}),
      completionMode: mode,
      lines: lines.map((line) => ({
        productId: line.product.id,
        quantity: line.quantity,
        code: line.product.code ?? line.product.barcode ?? line.product.id,
        name: line.temporaryName
          ?? line.product.name ?? line.product.code ?? t("sale.main.unnamedProduct"),
        rate: line.product.rate ?? null,
        price: saleLineUnitPrice(line, activeMember, wholesaleMode).toFixed(2),
        discount: line.discountPercent.toFixed(2),
        ...saleProductFiscalSnapshot(line.product),
        serialNumbers: line.serialNumbers ?? [],
        temporaryNameOverride: Boolean(line.temporaryName),
        temporaryPriceOverride: line.openUnitPrice !== undefined
          && Number(line.product.salePrice ?? 0) !== 0,
        cartLineId: saleCartLineIdentity(line),
        ...(line.temporaryPriceAuthorization ? {
          temporaryPriceAuthorizationToken: line.temporaryPriceAuthorization.token,
        } : {}),
      })),
    };
  }

  useEffect(() => {
    let active = true;
    void loadSalesOperationSecurity(session.accessToken)
      .then((configuration) => {
        if (active) setOperationSecurity(configuration);
      })
      .catch(() => {
        if (active) setOperationSecurity(null);
      });
    return () => { active = false; };
  }, [session.accessToken]);

  useEffect(() => {
    let active = true;
    void apiRequest<PendingTerminalPaymentConfiguration>(
      "/terminal-configuration/payment",
      { token: session.accessToken },
    ).then((configuration) => {
      if (active) setCardPaymentMode(resolvePendingCardPaymentMode(configuration));
    }).catch(() => {
      if (active) setCardPaymentMode(null);
    });
    return () => { active = false; };
  }, [session.accessToken]);

  useEffect(() => {
    let active = true;
    Promise.all([
      apiRequest<SaleProduct[]>("/products/sale", { token: session.accessToken }),
      apiRequest<SaleCustomer[]>("/customers/sale-options", { token: session.accessToken }),
      apiRequest<WarehouseOption[]>("/warehouses", { token: session.accessToken }),
    ]).then(([loadedProducts, loadedCustomers, warehouses]) => {
      if (!active) return;
      setProducts(loadedProducts.filter((product) => product.active !== false));
      setCustomers(loadedCustomers);
      const warehouse = warehouses.find((option) => option.active !== false
        && (option.defaultWarehouse || option.isDefaultWarehouse))
        ?? warehouses.find((option) => option.active !== false);
      setWarehouseId(warehouse?.id ?? "");
      if (!warehouse) setLoadError(t("salesDocument.validation.warehouse"));
    }).catch((error) => {
      if (active) setLoadError(error instanceof Error ? error.message : t("salesDocument.loadError"));
    }).finally(() => {
      if (active) setCatalogLoading(false);
    });
    return () => { active = false; };
  }, [session.accessToken]);

  useEffect(() => {
    if (!customer || !warehouseId || lines.length === 0 || checkoutMode) {
      setQuotedTotal(null);
      return;
    }
    let active = true;
    setQuoteLoading(true);
    setQuoteError("");
    const timer = window.setTimeout(() => {
      let requestDraft: PendingSaleDraft;
      try {
        requestDraft = draft("DRAFT");
      } catch (error) {
        if (active) {
          setQuoteError(error instanceof Error ? error.message : t("salesDocument.quoteError"));
          setQuoteLoading(false);
        }
        return;
      }
      const quotePath = editingDraft
        ? `/pos/sales-document-drafts/${encodeURIComponent(editingDraft.id)}/quote`
        : "/pos/sales-document-checkouts/quote";
      apiRequest<{ total: number | string }>(quotePath, {
        token: session.accessToken,
        body: pendingCreateBody(requestDraft, [], 0),
      }).then((quote) => {
        if (active) setQuotedTotal(Number(quote.total));
      }).catch((error) => {
        if (active) setQuoteError(error instanceof Error ? error.message : t("salesDocument.quoteError"));
      }).finally(() => {
        if (active) setQuoteLoading(false);
      });
    }, 180);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [
    activeMember,
    checkoutId,
    customer,
    documentType,
    editingDraft,
    importedDueDate,
    importedGlobalDiscount,
    importedInternalComment,
    issueDate,
    lines,
    warehouseId,
    wholesaleMode,
  ]);

  function addProduct(product: SaleProduct, openUnitPrice?: number, quantity = 1) {
    const existing = openUnitPrice == null
      ? lines.find((line) => line.product.id === product.id && line.openUnitPrice == null)
      : undefined;
    const lineId = existing ? saleCartLineIdentity(existing) : createSaleCartLineId();
    setLines((current) => addSaleLine(current, product, openUnitPrice, quantity, lineId));
    setSelectedLineId(lineId);
    invalidate();
    clearQuickEntry();
  }

  function requestAddProduct(product: SaleProduct) {
    const packageQuantity = Number(product.packageQuantity ?? 1);
    const quantity = nextScanMode === "PACKAGE"
      ? nextScanQuantity * (Number.isFinite(packageQuantity) && packageQuantity > 0
        ? packageQuantity : 1)
      : nextScanQuantity;
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    if (!isProductQuantityPrecisionValid(quantity, product.productType)
        || quantity < productQuantityStep(product.productType)
        || quantity > 9999) {
      reportShortcutError(t("sale.quantity.invalid"));
      return;
    }
    if (saleProductRequiresOpenPrice(product)) {
      setPendingOpenPriceQuantity(quantity);
      setPendingOpenPriceProduct(product);
      return;
    }
    addProduct(product, undefined, quantity);
  }

  function openProductSearch() {
    setProductSearchQuery(query);
    setProductSearchSelectedId("");
    setSearchOpen(true);
  }

  function clearProductSearch() {
    setSearchOpen(false);
    setProductSearchQuery("");
    setProductSearchSelectedId("");
    setQuery("");
  }

  function closeProductSearch() {
    clearProductSearch();
    queueMicrotask(() => inputRef.current?.focus());
  }

  function selectProductFromSearch(product: SaleProduct) {
    clearProductSearch();
    setProductInformationProduct(null);
    requestAddProduct(product);
  }

  function openProductInformation(product: SaleProduct) {
    setProductSearchSelectedId(product.id);
    setSearchOpen(false);
    setProductInformationProduct(product);
  }

  function closeProductInformation() {
    setProductInformationProduct(null);
    setSearchOpen(true);
  }

  function addProductFromInformation(product: SaleProduct) {
    setProductInformationProduct(null);
    selectProductFromSearch(product);
  }

  async function importDocumentDraft(detail: SalesDocumentDraftDetail) {
    const importedCustomer = customers.find((option) => option.id === detail.customerId) ?? {
      id: detail.customerId,
      fiscalName: detail.customerName ?? null,
      activeMember: false,
    } satisfies SaleCustomer;
    const importedLines = detail.lines.map((line) => {
      const catalogProduct = products.find((product) => product.id === line.productId);
      const storedPrice = Number(line.unitPrice);
      const catalogPrice = Number(catalogProduct?.salePrice ?? storedPrice);
      const openPrice = Boolean(catalogProduct && catalogPrice === 0);
      const product: SaleProduct = {
        ...(catalogProduct ?? {}),
        id: line.productId,
        active: catalogProduct?.active ?? true,
        productType: catalogProduct?.productType ?? "UNIT",
        code: line.code ?? catalogProduct?.code ?? line.productId,
        barcode: line.barcode ?? catalogProduct?.barcode ?? null,
        name: line.temporaryNameOverride
          ? catalogProduct?.name ?? line.name
          : line.name ?? catalogProduct?.name,
        salePrice: line.temporaryPriceOverride || openPrice
          ? (catalogProduct?.salePrice ?? (storedPrice || 1))
          : storedPrice,
        memberPrice: null,
        offerPrice: null,
        offerDiscountPercent: null,
        priceUseMode: "NORMAL",
        discountType: "NORMAL",
        offerActive: false,
        taxId: catalogProduct?.taxId ?? "",
        taxesIncluded: line.taxesIncluded,
        taxRegime: line.taxRegime,
        taxPercentage: line.taxPercentage,
        rate: line.rate ?? null,
      };
      return {
        cartLineId: line.id,
        product,
        quantity: Number(line.quantity),
        discountPercent: Number(line.discount),
        serialNumbers: line.serialNumbers ?? [],
        ...(line.temporaryNameOverride && line.name
          ? { temporaryName: line.name }
          : {}),
        ...(line.temporaryPriceOverride || openPrice
          ? { openUnitPrice: storedPrice }
          : {}),
      } satisfies SaleLine;
    });
    setDocumentType(detail.type);
    setIssueDate(detail.date);
    setImportedDueDate(detail.dueDate);
    setImportedGlobalDiscount(Number(
      detail.documentDiscountPercent ?? detail.globalDiscount,
    ).toFixed(2));
    setImportedInternalComment(detail.internalComment ?? null);
    setWholesaleMode(detail.wholesaleMode === true);
    setWarehouseId(detail.warehouseId);
    setCustomer(importedCustomer);
    setSelectedCustomerResultId(detail.customerId);
    setLines(importedLines);
    setSelectedLineId(importedLines[0] ? saleCartLineIdentity(importedLines[0]) : null);
    setEditingDraft({ id: detail.id, version: detail.version });
    setCheckoutId(uuid());
    setQuotedTotal(Number(detail.total));
    setQuoteError("");
    setCheckoutMode(null);
    setRecovery(null);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setDraftsOpen(false);
    setStatus(t("salesDocument.drafts.imported"));
    clearShortcutFeedback();
    localStorage.removeItem(recoveryStorageKey(terminalContext.terminalCode));
    queueMicrotask(() => inputRef.current?.focus());
  }

  function submitSearch() {
    const exact = selectSaleProduct(products, query);
    if (exact) {
      requestAddProduct(exact);
      return;
    }
    if (query.trim()) openProductSearch();
  }

  function removeLine(lineId: string) {
    setSelectedLineId(selectedProductAfterRemoval(sortedLines, lineId));
    setLines((current) => current.filter((line) => saleCartLineIdentity(line) !== lineId));
    invalidate();
  }

  function updateQuantity(lineId: string, change: number) {
    const line = lines.find((candidate) => saleCartLineIdentity(candidate) === lineId);
    if (!line) return;
    const quantity = normalizeProductQuantity(line.quantity + change);
    if (quantity <= 0) {
      removeLine(lineId);
      return;
    }
    if (quantity > 9999 || !isProductQuantityPrecisionValid(quantity, line.product.productType)) {
      reportShortcutError(t("sale.quantity.invalid"));
      return;
    }
    setLines((current) => updateSaleLineQuantity(current, lineId, quantity));
    invalidate();
  }

  function requireSelectedLine() {
    if (selectedLine) return selectedLine;
    reportShortcutError(t("salesDocument.shortcut.selectLine"));
    return null;
  }

  function quantityOperand() {
    const quantity = parseProductQuantityInput(query);
    return Number.isFinite(quantity) ? quantity : null;
  }

  function wholeOperand() {
    if (!/^\d+$/.test(query)) return null;
    const value = Number(query);
    return Number.isSafeInteger(value) ? value : null;
  }

  function applyPauseQuantity() {
    const line = requireSelectedLine();
    if (!line) return;
    const quantity = salePauseQuantity(query);
    if (quantity === 0) {
      removeLine(saleCartLineIdentity(line));
      clearQuickEntry();
      return;
    }
    if (quantity == null || quantity < productQuantityStep(line.product.productType)
        || quantity > 9999
        || !isProductQuantityPrecisionValid(quantity, line.product.productType)) {
      reportShortcutError(t("sale.quantity.invalid"));
      return;
    }
    setLines((current) => updateSaleLineQuantity(
      current,
      saleCartLineIdentity(line),
      quantity,
    ));
    invalidate();
    clearQuickEntry();
  }

  function adjustSelectedQuantity(direction: 1 | -1) {
    const line = requireSelectedLine();
    if (!line) return;
    const operand = quantityOperand();
    if (operand == null || operand < productQuantityStep(line.product.productType)
        || !isProductQuantityPrecisionValid(operand, line.product.productType)) {
      reportShortcutError(t("sale.quantity.invalid"));
      return;
    }
    const quantity = normalizeProductQuantity(line.quantity + direction * operand);
    if (quantity === 0) {
      removeLine(saleCartLineIdentity(line));
      clearQuickEntry();
      return;
    }
    if (quantity < productQuantityStep(line.product.productType) || quantity > 9999
        || !isProductQuantityPrecisionValid(quantity, line.product.productType)) {
      reportShortcutError(t("sale.quantity.invalid"));
      return;
    }
    setLines((current) => updateSaleLineQuantity(
      current,
      saleCartLineIdentity(line),
      quantity,
    ));
    invalidate();
    clearQuickEntry();
  }

  function prepareNextProductQuantity(asPackage: boolean) {
    const operand = asPackage ? wholeOperand() : quantityOperand();
    if (operand == null || operand <= 0) {
      reportShortcutError(t("sale.quantity.invalid"));
      return;
    }
    setNextScanQuantity(operand);
    setNextScanMode(asPackage ? "PACKAGE" : "UNIT");
    clearQuickEntry();
  }

  function applyQuickLineDiscount() {
    const line = requireSelectedLine();
    if (!line) return;
    if (!canApplyManualDiscount) {
      reportShortcutError(t("salesDocument.shortcut.discountUnavailable"));
      return;
    }
    if (saleProductBlocksManualDiscount(line.product)) {
      reportShortcutError(t("sale.discountBlocked"));
      return;
    }
    const discount = wholeOperand();
    if (discount == null || discount < 0 || discount > 100) {
      reportShortcutError(t("sale.discount.invalid"));
      return;
    }
    setLines((current) => updateSaleLineDiscount(
      current,
      saleCartLineIdentity(line),
      discount,
    ));
    invalidate();
    clearQuickEntry();
  }

  function applyDesiredLinePrice() {
    const line = requireSelectedLine();
    if (!line) return;
    if (!canApplyManualDiscount || saleProductBlocksManualDiscount(line.product)) {
      reportShortcutError(t("salesDocument.shortcut.discountUnavailable"));
      return;
    }
    const desiredPrice = wholeOperand();
    const currentPrice = saleLineUnitPrice(line, activeMember, wholesaleMode);
    if (desiredPrice == null || desiredPrice < 0 || desiredPrice > currentPrice
        || currentPrice <= 0) {
      reportShortcutError(t("salesDocument.shortcut.desiredPriceInvalid"));
      return;
    }
    const discount = Math.round((1 - desiredPrice / currentPrice) * 10_000) / 100;
    setLines((current) => updateSaleLineDiscount(
      current,
      saleCartLineIdentity(line),
      discount,
    ));
    invalidate();
    clearQuickEntry();
  }

  function openLineEdit(action: LineEditAction) {
    const line = requireSelectedLine();
    if (!line) return;
    if (action === "temporaryPrice" && saleProductRequiresOpenPrice(line.product)) return;
    setLineEditAction(action);
    setLineEditValue(action === "temporaryName"
      ? line.temporaryName ?? line.product.name ?? ""
      : line.openUnitPrice == null ? "" : String(line.openUnitPrice));
    setLineEditError("");
  }

  function saveTemporaryName() {
    if (!selectedLine) return;
    try {
      setLines((current) => updateSaleLineTemporaryName(
        current,
        saleCartLineIdentity(selectedLine),
        lineEditValue,
      ));
      setLineEditAction(null);
      invalidate();
      queueMicrotask(() => inputRef.current?.focus());
    } catch {
      setLineEditError(t("sale.temporaryName.invalid"));
    }
  }

  async function authorizeTemporaryPriceChange(
    change: { lineId: string; productId: string; unitPrice: number },
    credentials: SaleOperationCredentials,
  ) {
    setTemporaryPriceAuthorizationBusy(true);
    setTemporaryPriceAuthorizationError("");
    setLineEditError("");
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
      setLineEditAction(null);
      invalidate();
      queueMicrotask(() => inputRef.current?.focus());
    } catch (error) {
      const message = error instanceof ApiError
        ? error.message
        : t("sale.temporaryPrice.invalid");
      if (pendingTemporaryPriceChange) setTemporaryPriceAuthorizationError(message);
      else setLineEditError(message);
    } finally {
      setTemporaryPriceAuthorizationBusy(false);
    }
  }

  async function saveTemporaryPrice() {
    if (!selectedLine) return;
    const normalized = lineEditValue.trim().replace(",", ".");
    if (normalized && !/^\d+(?:\.\d{1,2})?$/.test(normalized)) {
      setLineEditError(t("sale.temporaryPrice.invalid"));
      return;
    }
    try {
      const unitPrice = normalized ? Number(normalized) : undefined;
      const lineId = saleCartLineIdentity(selectedLine);
      if (unitPrice == null || Number(selectedLine.product.salePrice ?? 0) === 0) {
        setLines((current) => updateSaleLineTemporaryPrice(current, lineId, unitPrice));
        setLineEditAction(null);
        invalidate();
        queueMicrotask(() => inputRef.current?.focus());
        return;
      }
      if (!temporaryPriceChangeAuthorization) {
        setLineEditError(t("sale.temporaryPrice.authorizationUnavailable"));
        return;
      }
      const change = {
        lineId,
        productId: selectedLine.product.id,
        unitPrice,
      };
      if (temporaryPriceChangeAuthorization.mode === "DIRECT") {
        await authorizeTemporaryPriceChange(change, {});
        return;
      }
      setPendingTemporaryPriceChange(change);
      setTemporaryPriceAuthorizationError("");
      setLineEditAction(null);
    } catch {
      setLineEditError(t("sale.temporaryPrice.invalid"));
    }
  }

  function executeDocumentCommand(command: SaleCommandId) {
    if (saving || checkoutMode || recovery) return;
    switch (command) {
      case "wholesale-mode":
        if (lines.length > 0) {
          setShortcutError(t("sale.wholesale.blocked"));
          return;
        }
        setWholesaleMode((current) => {
          const next = !current;
          setShortcutMessage(t(next ? "sale.wholesale.enabled" : "sale.wholesale.disabled"));
          return next;
        });
        break;
      case "product-search":
        openProductSearch();
        break;
      case "quantity":
        applyPauseQuantity();
        break;
      case "add-quantity":
        adjustSelectedQuantity(1);
        break;
      case "subtract-quantity":
        adjustSelectedQuantity(-1);
        break;
      case "next-units":
        prepareNextProductQuantity(false);
        break;
      case "next-package":
        prepareNextProductQuantity(true);
        break;
      case "temporary-name":
        openLineEdit("temporaryName");
        break;
      case "desired-price":
        applyDesiredLinePrice();
        break;
      case "temporary-price":
        openLineEdit("temporaryPrice");
        break;
      case "line-discount":
        applyQuickLineDiscount();
        break;
      case "serial-number":
        if (requireSelectedLine()) setSerialNumberOpen(true);
        break;
      case "customer":
        setCustomerOpen(true);
        break;
      case "checkout":
        startCheckout("CONFIRM_AND_PAY");
        break;
    }
  }

  useEffect(() => {
    function handleDocumentShortcut(event: KeyboardEvent) {
      if (event.repeat || document.querySelector(
        '[role="dialog"][aria-modal="true"], dialog[open]',
      )) return;
      const command = saleCommandFromKeyboard(event);
      const supported = command && [
        "product-search",
        "wholesale-mode",
        "quantity",
        "add-quantity",
        "subtract-quantity",
        "next-units",
        "next-package",
        "temporary-name",
        "desired-price",
        "temporary-price",
        "line-discount",
        "serial-number",
        "customer",
        "checkout",
      ].includes(command);
      if (command && supported) {
        if (!event.ctrlKey && shortcutTargetIsEditable(event.target)
            && event.target !== inputRef.current) return;
        event.preventDefault();
        executeDocumentCommand(command);
        return;
      }
      if (event.ctrlKey && !event.altKey && !event.metaKey) return;
      if (shortcutTargetIsEditable(event.target) && event.target !== inputRef.current) return;
      if (event.key === "ArrowUp" || event.key === "ArrowDown") {
        if (lines.length === 0) return;
        setSelectedLineId(saleLineSelectionAfterArrow(
          sortedLines,
          selectedLineId,
          event.key,
        ));
        event.preventDefault();
        return;
      }
      if (!event.altKey && !event.metaKey && !event.ctrlKey && event.key.length === 1
          && event.target !== inputRef.current && !shortcutTargetIsEditable(event.target)) {
        event.preventDefault();
        setQuery((current) => current + event.key);
        clearShortcutFeedback();
        inputRef.current?.focus();
      }
    }
    window.addEventListener("keydown", handleDocumentShortcut);
    return () => window.removeEventListener("keydown", handleDocumentShortcut);
  }, [
    activeMember,
    canApplyManualDiscount,
    checkoutMode,
    lines,
    query,
    ready,
    recovery,
    saving,
    selectedLineId,
    sortedLines,
    temporaryPriceChangeAuthorization,
    wholesaleMode,
  ]);

  function lineColumnLabel(column: SalesDocumentLineColumnKey) {
    if (column === "code") return t("sale.searchDialog.code");
    if (column === "name") return t("sale.searchDialog.name");
    if (column === "quantity") return t("sale.main.quantity");
    if (column === "price") return t("sale.searchDialog.price");
    if (column === "discount") return t("sale.main.discount");
    return t("sale.main.total");
  }

  function renderLineCell(line: SaleLine, column: SalesDocumentLineColumnKey) {
    const numeric = salesDocumentNumericColumns.has(column);
    const className = [
      numeric ? "sales-document-line-number" : "",
      column === "name" ? "sales-document-line-name" : "",
      column === "discount" ? "sales-document-discount" : "",
      column === "total" ? "sales-document-line-total" : "",
    ].filter(Boolean).join(" ");
    let content: ReactNode;
    if (column === "code") {
      content = line.product.code ?? line.product.barcode ?? "\u2014";
    } else if (column === "name") {
      content = line.temporaryName ?? line.product.name ?? t("sale.main.unnamedProduct");
    } else if (column === "quantity") {
      content = interfaceMode === "TOUCH" ? (
        <div className="sales-document-quantity">
          <button
            type="button"
            aria-label={`${t("sale.main.quantity")} -1`}
            disabled={line.quantity <= productQuantityStep(line.product.productType)}
            onClick={() => updateQuantity(
              saleCartLineIdentity(line),
              -productQuantityStep(line.product.productType),
            )}
          >{"\u2212"}</button>
          <b>{formatQuantityValue(line.quantity, locale)}</b>
          <button
            type="button"
            aria-label={`${t("sale.main.quantity")} +1`}
            disabled={line.quantity >= 9999}
            onClick={() => updateQuantity(
              saleCartLineIdentity(line),
              productQuantityStep(line.product.productType),
            )}
          >+</button>
        </div>
      ) : formatQuantityValue(line.quantity, locale);
    } else if (column === "price") {
      content = formatMoney(saleLineUnitPrice(line, activeMember, wholesaleMode), locale);
    } else if (column === "discount") {
      content = formatPercentage(effectiveSaleLineDiscount(line), locale);
    } else {
      content = formatMoney(saleLineSubtotal(line, activeMember, wholesaleMode), locale);
    }
    return <td className={className} data-column-key={column} key={column}>{content}</td>;
  }

  async function saveDraft(
    saleMutations: SaleMutationOperationAuthorizations = {},
  ) {
    if (!ready || saving) return;
    setSaving(true);
    setStatus("");
    setDraftAuthorizationError("");
    try {
      const requestDraft = draft("DRAFT");
      await apiRequest<{ document: { id: string } }>(
        editingDraft
          ? `/pos/sales-document-drafts/${encodeURIComponent(editingDraft.id)}`
          : "/pos/sales-document-checkouts",
        {
          ...(editingDraft ? { method: "PUT" } : {}),
          token: session.accessToken,
          body: pendingCreateBody(
            requestDraft,
            [],
            Math.round(total * 100),
            { saleMutations },
          ),
        },
      );
      resetDocument(t("salesDocument.savedDraft"));
    } catch (error) {
      const message = error instanceof Error ? error.message : t("salesDocument.saveError");
      if (draftAuthorizationOpen) setDraftAuthorizationError(message);
      else setStatus(message);
    } finally {
      setSaving(false);
    }
  }

  function requestSaveDraft() {
    if (!ready || saving) return;
    if (saleMutationAuthorizations == null) {
      setStatus(t("salesDocument.saveError"));
      return;
    }
    const requirements = saleMutationCredentialsRequired(saleMutationAuthorizations);
    if (requirements.length === 0) {
      void saveDraft();
      return;
    }
    setDraftAuthorizationError("");
    setDraftAuthorizationOpen(true);
  }

  function startCheckout(mode: CheckoutMode) {
    if (!ready) return;
    // A previous draft save may have reached the backend even when its HTTP response
    // was lost. Never reuse that operation identity for the subsequent confirmation.
    setCheckoutId(uuid());
    setCheckoutMode(mode);
  }

  const recoveredCheckoutDraft = recovery?.draft.completionMode === "CONFIRM_PENDING"
    || recovery?.draft.completionMode === "CONFIRM_AND_PAY"
    ? recovery.draft
    : null;
  const checkoutDraft = checkoutMode ? draft(checkoutMode) : recoveredCheckoutDraft;
  const effectiveMode = checkoutMode
    ?? (recovery?.draft.completionMode === "CONFIRM_PENDING"
      || recovery?.draft.completionMode === "CONFIRM_AND_PAY"
      ? recovery.draft.completionMode : null);

  return (
    <main className="sales-document-screen">
      <header className="sales-document-topbar">
        <div className="sales-document-heading">
          <span className="sales-document-app-badge">APP VENTA</span>
          <h1>{t("salesDocument.title")}</h1>
        </div>
        <div className="sales-document-type-switch" role="group" aria-label={t("salesDocument.type")}>
          <button
            type="button"
            aria-pressed={documentType === "FACTURA_VENTA"}
            onClick={() => { setDocumentType("FACTURA_VENTA"); invalidate(); }}
          >{t("receivables.type.invoice")}</button>
          <button
            type="button"
            aria-pressed={documentType === "ALBARAN_VENTA"}
            onClick={() => { setDocumentType("ALBARAN_VENTA"); invalidate(); }}
          >{t("receivables.type.deliveryNote")}</button>
        </div>
        <button type="button" className="sales-document-close" onClick={() => window.close()}>
          {t("common.close")}
        </button>
      </header>

      <section className="sales-document-context">
        <button type="button" onClick={() => setCustomerOpen(true)}>
          <span>{t("salesDocument.customer")}</span>
          <strong>{customer?.fiscalName ?? customer?.clientId ?? t("salesDocument.selectCustomer")}</strong>
        </button>
        <div><span>{t("salesDocument.issueDate")}</span><strong>{issueDate}</strong></div>
        <div><span>{t("pendingSale.dueDate")}</span><strong>{dueDate}</strong></div>
        <div><span>{t("salesDocument.terminal")}</span><strong>{terminalContext.terminalCode}</strong></div>
      </section>

      <section className="sales-document-workspace">
        <div className="sales-document-lines-panel">
          <header>
            <h2>{t("salesDocument.lines")}</h2>
            <span>{lines.length}</span>
          </header>
          <div className="sales-document-lines">
            <table
              className="sales-document-lines-table"
              aria-label={t("salesDocument.lines")}
              style={{ width: `max(100%, ${lineTableWidth}px)` }}
            >
              <colgroup>
                {visibleLineColumns.map((column) => (
                  <col
                    data-column-key={column.key}
                    key={column.key}
                    style={{ width: column.width }}
                  />
                ))}
              </colgroup>
              <thead>
                <tr className="sales-document-line-head">
                  {visibleLineColumns.map((column) => (
                    <TableLayoutHeaderCell
                      column={column}
                      key={column.key}
                      className={salesDocumentNumericColumns.has(column.key)
                        ? "sales-document-line-number" : ""}
                      sortDirection={lineTableSorting.sort?.column === column.key
                        ? lineTableSorting.sort.direction : null}
                      sortLabel={`${t("party.sortBy")} ${lineColumnLabel(column.key)}`}
                      onSort={lineTableSorting.toggleSort}
                      resizeLabel={`${t("stock.columns.resize")} ${lineColumnLabel(column.key)}`}
                      onReorder={lineTableLayout.reorderColumns}
                      onMove={lineTableLayout.moveColumn}
                      onResize={lineTableLayout.resizeColumn}
                    >
                      {lineColumnLabel(column.key)}
                    </TableLayoutHeaderCell>
                  ))}
                </tr>
              </thead>
              <tbody>
                {sortedLines.length === 0 && (
                  <tr className="sales-document-lines-empty">
                    <td colSpan={visibleLineColumns.length}>{t("salesDocument.empty")}</td>
                  </tr>
                )}
                {sortedLines.map((line) => {
                  const lineId = saleCartLineIdentity(line);
                  return (
                    <tr
                      key={lineId}
                      className={selectedLineId === lineId ? "selected" : undefined}
                      aria-selected={selectedLineId === lineId}
                      data-sales-document-line-id={lineId}
                      onClick={() => setSelectedLineId(lineId)}
                    >
                      {visibleLineColumns.map((column) => renderLineCell(line, column.key))}
                    </tr>
                  );
                })}
                <tr className="sales-document-lines-filler" aria-hidden="true">
                  {visibleLineColumns.map((column) => (
                    <td data-column-key={column.key} key={column.key} />
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <aside className="sales-document-entry-panel">
          <div className="sales-document-total">
            <span>{t("sale.main.total")}</span>
            <strong>{formatMoney(total, locale)}</strong>
            {quoteLoading && <small>{t("sale.quote.loading")}</small>}
          </div>
          <form onSubmit={(event) => { event.preventDefault(); submitSearch(); }}>
            <label>
              <span>{t("sale.main.quickEntry")}</span>
              <input
                ref={inputRef}
                autoFocus
                value={query}
                disabled={catalogLoading || Boolean(loadError)}
                placeholder={t("sale.main.searchPlaceholder")}
                onChange={(event) => {
                  setQuery(event.target.value);
                  clearShortcutFeedback();
                }}
              />
            </label>
            <button type="submit">{t("sale.main.search")}</button>
          </form>
          <p className="sale-next-quantity sales-document-next-quantity">
            {t("sale.main.quantity")}: {formatQuantityValue(nextScanQuantity, locale)}
            {nextScanMode === "PACKAGE"
              ? ` ${t(nextScanQuantity === 1
                ? "sale.quantity.package" : "sale.quantity.packages")}`
              : ""}
          </p>
          {(loadError || quoteError || shortcutError || shortcutMessage || status) && (
            <p
              className={loadError || quoteError || shortcutError
                ? "sale-action-error" : "sales-document-status"}
              role={loadError || quoteError || shortcutError ? "alert" : "status"}
            >
              {loadError || quoteError || shortcutError || shortcutMessage || status}
            </p>
          )}
          {pendingPrintRetry && (
            <aside className="receivables-error" role="alert">
              <div>
                <strong>{t("payment.result.printFailed")}</strong>
                {printFailureMessage && <small>{printFailureMessage}</small>}
              </div>
              <button
                type="button"
                disabled={printRetrying}
                onClick={() => void retryPendingPrint()}
              >
                {t("payment.result.retryPrint")}
              </button>
            </aside>
          )}
          <div className="sales-document-draft-entry">
            <span>{t("salesDocument.drafts.section")}</span>
            <button
              type="button"
              disabled={saving || Boolean(checkoutMode)}
              onClick={() => setDraftsOpen(true)}
            >
              {t("salesDocument.drafts.open")}
            </button>
          </div>
          <div className="sales-document-final-actions">
            <button type="button" disabled={!ready || saving} onClick={requestSaveDraft}>
              {t("salesDocument.saveDraft")}
            </button>
            <button type="button" className="primary" disabled={!ready || saving} onClick={() => startCheckout("CONFIRM_AND_PAY")}>
              {t("salesDocument.confirmAndPay")}
            </button>
          </div>
        </aside>
      </section>

      <footer className="sales-document-footer">
        <span>{terminalContext.storeName}</span>
        <span>{session.displayName}</span>
        <span>Ctrl+F {"\u00b7"} {t("salesDocument.shortcut")}</span>
      </footer>

      {draftsOpen && (
        <SalesDocumentDraftDialog
          locale={locale}
          token={session.accessToken}
          hasCurrentWork={lines.length > 0}
          onClose={() => {
            setDraftsOpen(false);
            queueMicrotask(() => inputRef.current?.focus());
          }}
          onImport={importDocumentDraft}
        />
      )}

      {searchOpen && !productInformationProduct && (
        <SaleProductSearchDialog
          initialQuery={productSearchQuery}
          initialSelectedId={productSearchSelectedId}
          interfaceMode={interfaceMode}
          locale={locale}
          products={products}
          token={session.accessToken}
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
            add: t("salesDocument.productSearch.add"),
            details: t("sale.searchDialog.details"),
            navigate: t("sale.searchDialog.navigate"),
            selected: t("sale.searchDialog.selected"),
            unnamedProduct: t("sale.main.unnamedProduct"),
            missingCode: t("sale.main.missingCode"),
          }}
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
          onCancel={() => {
            setPendingOpenPriceProduct(null);
            setPendingOpenPriceQuantity(1);
            queueMicrotask(() => inputRef.current?.focus());
          }}
          onAccept={(price) => {
            const product = pendingOpenPriceProduct;
            const quantity = pendingOpenPriceQuantity;
            setPendingOpenPriceProduct(null);
            setPendingOpenPriceQuantity(1);
            if (product) addProduct(product, price, quantity);
          }}
        />
      )}

      {lineEditAction && selectedLine && (
        <div className="sale-action-overlay" role="presentation">
          <section
            ref={lineEditDialogRef}
            className="sale-action-dialog sale-inline-edit-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sales-document-line-edit-title"
            onKeyDown={(event) => {
              if (event.key !== "Escape") return;
              event.preventDefault();
              setLineEditAction(null);
              queueMicrotask(() => inputRef.current?.focus());
            }}
          >
            <header>
              <div>
                <h2 id="sales-document-line-edit-title">{t(lineEditAction === "temporaryName"
                  ? "sale.temporaryName.title" : "sale.temporaryPrice.title")}</h2>
                <p>{selectedLine.product.code ?? selectedLine.product.barcode ?? ""}</p>
              </div>
              <button
                type="button"
                aria-label={t("common.close")}
                disabled={temporaryPriceAuthorizationBusy}
                onClick={() => {
                  setLineEditAction(null);
                  queueMicrotask(() => inputRef.current?.focus());
                }}
              >{"\u00d7"}</button>
            </header>
            <form onSubmit={(event) => {
              event.preventDefault();
              if (lineEditAction === "temporaryName") saveTemporaryName();
              else void saveTemporaryPrice();
            }}>
              <p>
                <span>{t("sale.main.product")}</span>
                <strong>{selectedLine.temporaryName
                  ?? selectedLine.product.name ?? t("sale.main.unnamedProduct")}</strong>
              </p>
              <label>
                <span>{t(lineEditAction === "temporaryName"
                  ? "sale.temporaryName.label" : "sale.temporaryPrice.label")}</span>
                <input
                  ref={lineEditInputRef}
                  maxLength={lineEditAction === "temporaryName" ? 255 : undefined}
                  inputMode={lineEditAction === "temporaryPrice" ? "decimal" : undefined}
                  disabled={temporaryPriceAuthorizationBusy}
                  value={lineEditValue}
                  onChange={(event) => {
                    setLineEditValue(event.target.value);
                    setLineEditError("");
                  }}
                />
              </label>
              <small>{t(lineEditAction === "temporaryName"
                ? "sale.temporaryName.hint" : "sale.temporaryPrice.hint")}</small>
              {lineEditError && <p className="sale-action-error" role="alert">{lineEditError}</p>}
              <footer className="sale-action-buttons">
                <button
                  type="button"
                  disabled={temporaryPriceAuthorizationBusy}
                  onClick={() => {
                    setLineEditAction(null);
                    queueMicrotask(() => inputRef.current?.focus());
                  }}
                >{t("sale.dialog.cancel")}</button>
                <button type="submit" className="primary" disabled={temporaryPriceAuthorizationBusy}>
                  {t("sale.dialog.save")}
                </button>
              </footer>
            </form>
          </section>
        </div>
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
            queueMicrotask(() => inputRef.current?.focus());
          }}
          onConfirm={(authorizations: SaleMutationOperationAuthorizations) => {
            const credentials = authorizations.TEMPORARY_PRICE_CHANGE ?? {};
            void authorizeTemporaryPriceChange(pendingTemporaryPriceChange, credentials);
          }}
        />
      )}

      {draftAuthorizationOpen && saleMutationAuthorizations && (
        <SaleMutationAuthorizationDialog
          open
          locale={locale}
          currentUsername={session.username}
          requirements={saleMutationCredentialsRequired(saleMutationAuthorizations)}
          busy={saving}
          error={draftAuthorizationError}
          onCancel={() => {
            setDraftAuthorizationOpen(false);
            setDraftAuthorizationError("");
            queueMicrotask(() => inputRef.current?.focus());
          }}
          onConfirm={(authorizations) => {
            void saveDraft(authorizations);
          }}
        />
      )}

      {serialNumberOpen && selectedLine && (
        <SaleSerialNumberDialog
          locale={locale}
          productName={selectedLine.temporaryName
            ?? selectedLine.product.name ?? selectedLine.product.code ?? ""}
          quantity={selectedLine.quantity}
          initialSerialNumbers={selectedLine.serialNumbers ?? []}
          onCancel={() => {
            setSerialNumberOpen(false);
            queueMicrotask(() => inputRef.current?.focus());
          }}
          onConfirm={(serialNumbers) => {
            setLines((current) => updateSaleLineSerialNumbers(
              current,
              saleCartLineIdentity(selectedLine),
              serialNumbers,
            ));
            setSerialNumberOpen(false);
            invalidate();
            setShortcutMessage(t("sale.serialNumber.saved"));
            queueMicrotask(() => inputRef.current?.focus());
          }}
        />
      )}

      {customerOpen && (
        <div className="sale-action-overlay" role="presentation">
          <section
            ref={customerDialogRef}
            className="sales-document-customer-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sales-document-customer-title"
            onKeyDown={handleCustomerDialogKeyDown}
          >
            <header>
              <h2 id="sales-document-customer-title">{t("salesDocument.selectCustomer")}</h2>
              <button
                type="button"
                aria-label={t("common.close")}
                onClick={closeCustomerDialog}
              >{"\u00d7"}</button>
            </header>
            <input
              autoFocus
              aria-label={t("sale.customer.search")}
              aria-activedescendant={selectedCustomerResultId
                ? `sales-document-customer-option-${encodeURIComponent(selectedCustomerResultId)}`
                : undefined}
              value={customerQuery}
              placeholder={t("salesDocument.customerSearch")}
              onChange={(event) => {
                setCustomerQuery(event.target.value);
                setSelectedCustomerResultId("");
              }}
            />
            <div
              className="sales-document-customer-results"
              role="listbox"
              aria-label={t("salesDocument.selectCustomer")}
            >
              {customerResults.map((option) => (
                <button
                  id={`sales-document-customer-option-${encodeURIComponent(option.id)}`}
                  type="button"
                  role="option"
                  aria-selected={option.id === selectedCustomerResultId}
                  className={option.id === selectedCustomerResultId ? "selected" : undefined}
                  key={option.id}
                  onFocus={() => setSelectedCustomerResultId(option.id)}
                  onClick={() => setSelectedCustomerResultId(option.id)}
                  onDoubleClick={() => chooseDocumentCustomer(option)}
                >
                  <strong>{option.fiscalName ?? option.clientId ?? option.id}</strong>
                  <span>{option.documentNumber ?? "\u2014"}</span>
                </button>
              ))}
            </div>
            <footer className="sales-document-customer-shortcuts">
              <span><kbd>{"\u2191"}</kbd><kbd>{"\u2193"}</kbd>{t("sale.searchDialog.navigate")}</span>
              <span><kbd>Insert</kbd>{t("sale.customer.select")}</span>
              <span><kbd>Esc</kbd>{t("sale.dialog.close")}</span>
            </footer>
          </section>
        </div>
      )}

      {checkoutDraft && effectiveMode && (
        <CustomerPendingSaleDialog
          customerName={recovery?.customer.name
            ?? customer?.fiscalName ?? customer?.clientId ?? t("salesDocument.customer")}
          locale={locale}
          currentUsername={session.username}
          draft={checkoutDraft}
          recovery={recovery ?? undefined}
          token={session.accessToken}
          permissions={session.permissions}
          createPendingAuthorization={createPendingAuthorization}
          creditOverrideAuthorization={creditOverrideAuthorization}
          manualCardPaymentAuthorization={manualCardPaymentAuthorization}
          transferPaymentAuthorization={transferPaymentAuthorization}
          cardPaymentMode={cardPaymentMode}
          saleMutationAuthorizations={saleMutationAuthorizations}
          terminalContext={terminalContext}
          endpointBase={effectiveDraftId
            ? `/pos/sales-document-drafts/${encodeURIComponent(effectiveDraftId)}`
            : "/pos/sales-document-checkouts"}
          sourceDocumentId={effectiveDraftId ?? undefined}
          allowPayments
          allowPendingCompletion
          lockDocumentType
          standardCheckout
          interfaceMode={interfaceMode}
          title={t(effectiveMode === "CONFIRM_AND_PAY"
            ? "salesDocument.paymentTitle" : "salesDocument.pendingTitle")}
          confirmLabel={t(effectiveMode === "CONFIRM_AND_PAY"
            ? "salesDocument.confirmAndPay" : "salesDocument.confirmPending")}
          onPersistRecovery={(envelope) => {
            localStorage.setItem(
              recoveryStorageKey(terminalContext.terminalCode),
              JSON.stringify(envelope),
            );
            setRecovery(envelope);
          }}
          onClearRecovery={() => {
            localStorage.removeItem(recoveryStorageKey(terminalContext.terminalCode));
            setRecovery(null);
          }}
          onCancel={() => {
            if (!recovery) setCheckoutMode(null);
          }}
          onSuccess={(result, retryPrint, technicalMessage) => {
            setPendingPrintRetry(() => retryPrint ?? null);
            setPrintFailureMessage(technicalMessage ?? "");
            resetDocument(
              t("salesDocument.completed").replace(
                "{number}",
                result.documentNumber ?? result.documentId,
              ),
            );
          }}
        />
      )}
    </main>
  );
}

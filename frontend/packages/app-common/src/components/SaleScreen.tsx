import { useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ProductCreateDialog } from "./ProductCreateDialog";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { CashPaymentResultDialog } from "./CashPaymentResultDialog";
import { CardPaymentDialog } from "./CardPaymentDialog";
import { readCashInputMode, type CashInputMode } from "../sale/cashInputMode";
import { PromotionPreviewPanel } from "./PromotionPreviewPanel";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { queryPaymentOperation } from "../sale/paymentOperations";
import { SalePaymentCheckout } from "./SalePaymentCheckout";

export type SaleProduct = {
  id: string;
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
};

export type SaleLine = {
  product: SaleProduct;
  quantity: number;
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
};

function normalizedSearchValue(value: string | null | undefined) {
  return value?.trim().toLocaleLowerCase() ?? "";
}

export function filterSaleProducts(products: SaleProduct[], query: string, limit = 10) {
  const normalizedQuery = normalizedSearchValue(query);
  if (!normalizedQuery) {
    return [];
  }
  return products
    .filter((product) => [product.code, product.barcode, product.barcode2, product.name]
      .some((value) => normalizedSearchValue(value).includes(normalizedQuery)))
    .slice(0, limit);
}

export function selectSaleProduct(products: SaleProduct[], query: string) {
  const normalizedQuery = normalizedSearchValue(query);
  if (!normalizedQuery) {
    return undefined;
  }
  const exact = products.find((product) => [product.code, product.barcode, product.barcode2]
    .some((value) => normalizedSearchValue(value) === normalizedQuery));
  if (exact) {
    return exact;
  }
  const matches = filterSaleProducts(products, query);
  return matches.length === 1 ? matches[0] : undefined;
}

export function addSaleLine(lines: SaleLine[], product: SaleProduct) {
  const existing = lines.find((line) => line.product.id === product.id);
  if (!existing) {
    return [...lines, { product, quantity: 1, discountPercent: 0 }];
  }
  return lines.map((line) => line.product.id === product.id
    ? { ...line, quantity: Math.min(9999, line.quantity + 1) }
    : line);
}

export function updateSaleLineQuantity(lines: SaleLine[], productId: string, quantity: number) {
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 9999) {
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

export function saleLineSubtotal(line: SaleLine) {
  return effectiveSaleProductPrice(line.product) * line.quantity * (1 - effectiveSaleLineDiscount(line) / 100);
}

export function effectiveSaleLineDiscount(line: SaleLine) {
  return Math.max(line.discountPercent, line.memberDiscountPercent ?? 0);
}

export function applyMemberDiscounts(lines: SaleLine[], customer: SaleCustomer | null) {
  const customerDiscount = customer?.activeMember ? Number(customer.memberDiscountPercent ?? 0) : 0;
  return lines.map((line) => ({
    ...line,
    memberDiscountPercent: line.product.discountType === "MEMBER_DISCOUNT" ? customerDiscount : 0
  }));
}

export function saleTotal(lines: SaleLine[]) {
  return lines.reduce((total, line) => total + saleLineSubtotal(line), 0);
}

type CashPaymentResponse = {
  number: string;
  change: number | string;
  total?: number | string;
  received?: number | string;
};

type CashPaymentResult = {
  ticketNumber: string;
  totalCents: number;
  receivedCents?: number;
  changeCents?: number;
  method?: string;
  authorization?: string;
  reference?: string;
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
  response: CashPaymentResponse,
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

export function saleProductBlocksManualDiscount(product: SaleProduct) {
  return String(product.discountType ?? "NORMAL").toUpperCase() === "NONE";
}

export function effectiveSaleProductPrice(product: SaleProduct, currentDate = currentSaleDate()) {
  const salePrice = salePriceNumber(product.salePrice);
  const mode = String(product.priceUseMode ?? "NORMAL").toUpperCase();
  if (mode === "MEMBER_PRICE") {
    return salePriceNumber(product.memberPrice, salePrice);
  }
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

type SaleScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};

export function SaleScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout
}: SaleScreenProps) {
  const t = createTranslator(locale);
  const [productCreateOpen, setProductCreateOpen] = useState(false);
  const [products, setProducts] = useState<SaleProduct[]>([]);
  const [query, setQuery] = useState("");
  const [lines, setLines] = useState<SaleLine[]>([]);
  const [selectedProductId, setSelectedProductId] = useState<string | null>(null);
  const [actionDialog, setActionDialog] = useState<"quantity" | "discount" | "customer" | "remove" | null>(null);
  const [quantityInput, setQuantityInput] = useState("1");
  const [discountInput, setDiscountInput] = useState("0");
  const [actionError, setActionError] = useState("");
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [customerQuery, setCustomerQuery] = useState("");
  const [customerLoading, setCustomerLoading] = useState(false);
  const [customerError, setCustomerError] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState<SaleCustomer | null>(null);
  const [cashDialogOpen, setCashDialogOpen] = useState(false);
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
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogReload, setCatalogReload] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const cashSubmissionRef = useRef(false);
  const cardSubmissionRef = useRef(false);
  const cardOpeningRef = useRef({ current: false, generation: 0 });
  const results = useMemo(() => filterSaleProducts(products, query), [products, query]);
  const customerResults = useMemo(() => filterSaleCustomers(customers, customerQuery), [customers, customerQuery]);
  const selectedLine = lines.find((line) => line.product.id === selectedProductId);
  const total = saleTotal(lines);

  useEffect(() => {
    let cancelled = false;
    setCatalogLoading(true);
    setCatalogError(false);
    apiRequest<SaleProduct[]>("/products", { token: session.accessToken })
      .then((loadedProducts) => {
        if (!cancelled) {
          setProducts(loadedProducts);
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

  function addProduct(product: SaleProduct) {
    setLines((current) => applyMemberDiscounts(addSaleLine(current, product), selectedCustomer));
    setSelectedProductId(product.id);
    setQuery("");
    searchInputRef.current?.focus();
  }

  function openQuantityDialog() {
    if (!selectedLine) return;
    setQuantityInput(String(selectedLine.quantity));
    setActionError("");
    setActionDialog("quantity");
  }

  function openDiscountDialog() {
    if (!selectedLine || saleProductBlocksManualDiscount(selectedLine.product)) return;
    setDiscountInput(String(selectedLine.discountPercent));
    setActionError("");
    setActionDialog("discount");
  }

  function saveQuantity() {
    if (!selectedProductId) return;
    try {
      setLines((current) => updateSaleLineQuantity(current, selectedProductId, Number(quantityInput)));
      setActionDialog(null);
    } catch {
      setActionError("La cantidad debe ser un numero entero entre 1 y 9999");
    }
  }

  function saveDiscount() {
    if (!selectedProductId) return;
    try {
      setLines((current) => updateSaleLineDiscount(current, selectedProductId, Number(discountInput)));
      setActionDialog(null);
    } catch (error) {
      setActionError(error instanceof Error && error.message === "discount_blocked"
        ? t("sale.discountBlocked")
        : "El descuento debe estar entre 0 y 100");
    }
  }

  function openCustomerDialog() {
    setActionDialog("customer");
    setCustomerQuery("");
    setCustomerLoading(true);
    setCustomerError(false);
    apiRequest<SaleCustomer[]>("/customers/sale-options", { token: session.accessToken })
      .then(setCustomers)
      .catch(() => setCustomerError(true))
      .finally(() => setCustomerLoading(false));
  }

  function confirmRemoveLine() {
    if (!selectedProductId) return;
    setLines((current) => {
      const nextSelectedProductId = selectedProductAfterRemoval(current, selectedProductId);
      const remaining = removeSaleLine(current, selectedProductId);
      setSelectedProductId(nextSelectedProductId);
      return remaining;
    });
    setActionDialog(null);
  }

  function submitSearch() {
    const selected = selectSaleProduct(products, query);
    if (selected) {
      addProduct(selected);
    }
  }

  function cashSaleRequest() {
    return {
      customerId: selectedCustomer?.id ?? null,
      lines: lines.map((line) => ({
        productId: line.product.id,
        quantity: line.quantity,
        discount: line.discountPercent
      }))
    };
  }

  async function openCashDialog() {
    if (lines.length === 0 || total <= 0) return;
    setCashError("");
    setCashStatus("");
    setCashInputMode(readCashModeForOpening());
    try {
      const quote = await apiRequest<{ total: number | string }>("/pos/cash/quote", {
        token: session.accessToken,
        body: cashSaleRequest()
      });
      setCashQuoteCents(Math.round(Number(quote.total) * 100));
      setCashCheckoutId(globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`);
      setCashDialogOpen(true);
    } catch (error) {
      setCashStatus(error instanceof Error ? error.message : "No se pudo calcular el total de la venta");
    }
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
      const confirmedResult = resolveCashPaymentResult(result, cashQuoteCents, receivedCents);
      const transition = cashPaymentSuccessTransition(confirmedResult);
      setCashDialogOpen(transition.cashDialogOpen);
      setLines(transition.lines);
      setSelectedProductId(transition.selectedProductId);
      setSelectedCustomer(transition.selectedCustomer);
      setCashResult(transition.cashResult);
      setQuery(transition.query);
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
    if (lines.length === 0 || total <= 0) return;
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
        if (opening.isCurrent()) setCashStatus(error instanceof Error ? error.message : "No se pudo calcular el total de la venta");
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
      })
      .catch((error) => {
        setCardMessage(error instanceof Error ? error.message : "No se pudo consultar la operacion");
      })
      .finally(() => setCardSubmitting(false));
  }

  return (
    <main className="sale-screen work-screen">
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

      <section className="work-shell" aria-label="Venta">
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">Venta</h1>
        </header>

        <section className="sale-ticket work-panel" aria-label="Ticket actual">
          <header className="work-panel-heading">
            <h2>Ticket actual</h2>
            <span>{selectedCustomer ? `Cliente: ${selectedCustomer.fiscalName}` : lines.length === 0 ? "Sin venta iniciada" : `${lines.length} producto${lines.length === 1 ? "" : "s"}`}</span>
          </header>
          {lines.length === 0 ? (
            <div className="sale-ticket-lines sale-empty-state">Sin venta iniciada</div>
          ) : (
            <div className="sale-ticket-lines" aria-label="Lineas del ticket">
              {lines.map((line) => (
                <button
                  type="button"
                  className={`sale-ticket-line${selectedProductId === line.product.id ? " selected" : ""}`}
                  key={line.product.id}
                  aria-pressed={selectedProductId === line.product.id}
                  onClick={() => setSelectedProductId(line.product.id)}
                >
                  <div>
                    <strong>{line.product.name ?? "Producto sin nombre"}</strong>
                    <span>{line.product.code ?? line.product.barcode ?? "Sin codigo"}</span>
                  </div>
                  <span>
                    {line.quantity} x {formatSaleAmount(effectiveSaleProductPrice(line.product))}
                    {effectiveSaleLineDiscount(line) > 0 && (
                      <small>
                        {line.memberDiscountPercent != null
                          && line.memberDiscountPercent >= line.discountPercent
                          && line.memberDiscountPercent > 0
                          ? ` - Socio ${formatSaleAmount(line.memberDiscountPercent)}%`
                          : ` - ${formatSaleAmount(line.discountPercent)}%`}
                      </small>
                    )}
                    {saleProductBlocksManualDiscount(line.product) && <small> {t("sale.discountBlockedShort")}</small>}
                  </span>
                  <b>{formatSaleAmount(saleLineSubtotal(line))}</b>
                </button>
              ))}
            </div>
          )}
          <PromotionPreviewPanel locale={locale} preview={null} />
          <footer className="sale-total">
            <span>Total</span>
            <strong>{formatSaleAmount(total)}</strong>
          </footer>
        </section>

        <section className="sale-tools work-panel" aria-label="Busqueda y cobro">
          <div className="work-panel-heading sale-product-heading">
            <div>
              <h2>Producto</h2>
              <span>Entrada rapida por codigo, nombre o referencia</span>
            </div>
            <button type="button" className="stock-add-product-button" onClick={() => setProductCreateOpen(true)}>
              {t("product.create.button")}
            </button>
          </div>
          <label className="work-search">
            <span>Buscar producto</span>
            <input
              ref={searchInputRef}
              aria-label="Buscar producto"
              aria-controls="sale-product-results"
              aria-expanded={query.trim().length > 0}
              autoComplete="off"
              disabled={catalogLoading || catalogError || paymentLocked}
              placeholder="Codigo o nombre"
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
          <div className="sale-search-results" id="sale-product-results" aria-live="polite">
            {catalogLoading && <p className="sale-search-status">Cargando productos...</p>}
            {catalogError && (
              <div className="sale-search-status sale-search-error">
                <span>No se pudo cargar el catalogo</span>
                <button type="button" onClick={() => setCatalogReload((value) => value + 1)}>Reintentar</button>
              </div>
            )}
            {!catalogLoading && !catalogError && query.trim() && results.length === 0 && (
              <p className="sale-search-status">No se encontraron productos</p>
            )}
            {!catalogLoading && !catalogError && results.map((product) => (
              <button className="sale-search-result" type="button" disabled={paymentLocked} key={product.id} onClick={() => addProduct(product)}>
                <span>
                  <strong>{product.name ?? "Producto sin nombre"}</strong>
                  <small>{product.code ?? product.barcode ?? "Sin codigo"}</small>
                </span>
                <b>{formatSaleAmount(effectiveSaleProductPrice(product))}</b>
              </button>
            ))}
          </div>
          <div className="sale-quick-grid">
            <button type="button" disabled={!selectedLine || paymentLocked} onClick={openQuantityDialog}>Cantidad</button>
            <button
              type="button"
              disabled={!selectedLine || paymentLocked || saleProductBlocksManualDiscount(selectedLine.product)}
              title={selectedLine && saleProductBlocksManualDiscount(selectedLine.product) ? t("sale.discountBlocked") : undefined}
              onClick={openDiscountDialog}
            >
              Descuento
            </button>
            <button type="button" disabled={paymentLocked} onClick={openCustomerDialog}>Cliente</button>
            <button type="button" disabled={!selectedLine || paymentLocked} onClick={() => setActionDialog("remove")}>Anular linea</button>
          </div>
          <section className="sale-payment" aria-label="Cobro">
            <h2>Cobro</h2>
            <SalePaymentCheckout totalCents={Math.round(total*100)} sale={cashSaleRequest()} token={session.accessToken} permissions={session.permissions} terminal={terminalContext} disabled={lines.length===0||total<=0} onLockedChange={setPaymentLocked} onFinalized={(ticketNumber,authoritativeTotalCents)=>{setLines([]);setSelectedProductId(null);setSelectedCustomer(null);setQuery("");setCashResult({ticketNumber,totalCents:authoritativeTotalCents});}} />
            {cashStatus && <p className="sale-payment-status" role="status">{cashStatus}</p>}
          </section>
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>

      <ProductCreateDialog
        open={productCreateOpen}
        locale={locale}
        token={session.accessToken}
        onClose={() => setProductCreateOpen(false)}
      />

      {cashDialogOpen && (
        <CashPaymentDialog
          totalCents={cashQuoteCents}
          initialMode={cashInputMode}
          submitting={cashSubmitting}
          error={cashError}
          onCancel={() => setCashDialogOpen(false)}
          onConfirm={(receivedCents) => void confirmCashPayment(receivedCents)}
        />
      )}

      {cashResult && (
        <CashPaymentResultDialog
          {...cashResult}
          onFinish={() => {
            finishCashPaymentResult(setCashResult, () => searchInputRef.current?.focus());
          }}
        />
      )}

      {cardDialogOpen && <CardPaymentDialog totalCents={cardQuoteCents} status={cardStatus} submitting={cardSubmitting} message={cardMessage} onCancel={() => setCardDialogOpen(false)} onConsult={consultCardPayment} onNewOperation={retryCardPayment} />}

      {actionDialog === "quantity" && selectedLine && (
        <SaleActionDialog title="Cambiar cantidad" onClose={() => setActionDialog(null)}>
          <label>
            <span>Cantidad</span>
            <input aria-label="Nueva cantidad" type="number" min="1" max="9999" step="1" value={quantityInput} onChange={(event) => setQuantityInput(event.target.value)} />
          </label>
          {actionError && <strong className="sale-action-error">{actionError}</strong>}
          <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>Cancelar</button><button type="button" onClick={saveQuantity}>Guardar</button></div>
        </SaleActionDialog>
      )}

      {actionDialog === "discount" && selectedLine && (
        <SaleActionDialog title="Aplicar descuento" onClose={() => setActionDialog(null)}>
          <label>
            <span>Descuento (%)</span>
            <input aria-label="Nuevo descuento" type="number" min="0" max="100" step="0.01" value={discountInput} onChange={(event) => setDiscountInput(event.target.value)} />
          </label>
          {actionError && <strong className="sale-action-error">{actionError}</strong>}
          <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>Cancelar</button><button type="button" onClick={saveDiscount}>Guardar</button></div>
        </SaleActionDialog>
      )}

      {actionDialog === "customer" && (
        <SaleActionDialog title="Seleccionar cliente" onClose={() => setActionDialog(null)} wide>
          <label>
            <span>Buscar cliente</span>
            <input aria-label="Buscar cliente" value={customerQuery} onChange={(event) => setCustomerQuery(event.target.value)} placeholder="Nombre, documento o codigo" />
          </label>
          {customerLoading && <p className="sale-search-status">Cargando clientes...</p>}
          {customerError && <p className="sale-action-error">No se pudieron cargar los clientes</p>}
          {!customerLoading && !customerError && (
            <div className="sale-customer-results">
              <button type="button" onClick={() => { setSelectedCustomer(null); setLines((current) => applyMemberDiscounts(current, null)); setActionDialog(null); }}>Sin cliente</button>
              {customerResults.map((customer) => (
                <button type="button" key={customer.id} onClick={() => { setSelectedCustomer(customer); setLines((current) => applyMemberDiscounts(current, customer)); setActionDialog(null); }}>
                  <strong>{customer.fiscalName ?? "Cliente sin nombre"}</strong>
                  <span>{customer.clientId ?? customer.documentNumber ?? "Sin codigo"}</span>
                </button>
              ))}
            </div>
          )}
          <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>Cerrar</button></div>
        </SaleActionDialog>
      )}

      {actionDialog === "remove" && selectedLine && (
        <SaleActionDialog title="Anular linea" onClose={() => setActionDialog(null)}>
          <p>Se eliminara {selectedLine.product.name ?? "el producto"} del ticket.</p>
          <div className="sale-action-buttons"><button type="button" onClick={() => setActionDialog(null)}>Cancelar</button><button type="button" className="danger" onClick={confirmRemoveLine}>Anular linea</button></div>
        </SaleActionDialog>
      )}
    </main>
  );
}

function SaleActionDialog({ title, children, onClose, wide = false }: { title: string; children: React.ReactNode; onClose: () => void; wide?: boolean }) {
  return (
    <div className="sale-action-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className={`sale-action-dialog${wide ? " wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <header><h2>{title}</h2><button type="button" aria-label="Cerrar" onClick={onClose}>x</button></header>
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

import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import { addLocalDays, pendingCreateBody, type PendingSaleDraft } from "../sale/customerReceivables";
import type { PendingSaleRecoveryEnvelope } from "../sale/pendingSaleRecovery";
import type { LocaleCode, TerminalContext, UserSession } from "../types";
import { CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";
import {
  addSaleLine,
  saleLineUnitPrice,
  saleProductFiscalSnapshot,
  saleProductRequiresOpenPrice,
  saleTotal,
  selectSaleProduct,
  type SaleCustomer,
  type SaleLine,
  type SaleProduct,
} from "./SaleScreen";
import { SaleOpenPriceDialog } from "./SaleOpenPriceDialog";
import { SaleProductSearchDialog } from "./SaleProductSearchDialog";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type DocumentType = "FACTURA_VENTA" | "ALBARAN_VENTA";
type CheckoutMode = "CONFIRM_PENDING" | "CONFIRM_AND_PAY";
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
};

const uuid = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;

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

function readRecovery(terminalCode: string) {
  try {
    const raw = localStorage.getItem(recoveryStorageKey(terminalCode));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as PendingSaleRecoveryEnvelope;
    return parsed?.version === 2 && parsed.terminalCode === terminalCode ? parsed : null;
  } catch {
    return null;
  }
}

export function SalesDocumentScreen({ locale, session, terminalContext }: Props) {
  const t = createTranslator(locale);
  const [documentType, setDocumentType] = useState<DocumentType>("FACTURA_VENTA");
  const [products, setProducts] = useState<SaleProduct[]>([]);
  const [lines, setLines] = useState<SaleLine[]>([]);
  const [customers, setCustomers] = useState<SaleCustomer[]>([]);
  const [customer, setCustomer] = useState<SaleCustomer | null>(null);
  const [warehouseId, setWarehouseId] = useState("");
  const [query, setQuery] = useState("");
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [customerOpen, setCustomerOpen] = useState(false);
  const [customerQuery, setCustomerQuery] = useState("");
  const [pendingOpenPriceProduct, setPendingOpenPriceProduct] = useState<SaleProduct | null>(null);
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
  const inputRef = useRef<HTMLInputElement>(null);
  const customerDialogRef = useRef<HTMLElement>(null);
  const issueDate = localDate();
  const dueDate = customer
    ? addLocalDays(new Date(), Math.max(0, customer.paymentTermDays ?? 30))
    : issueDate;
  const activeMember = customer?.activeMember === true;
  const fallbackTotal = saleTotal(lines, activeMember);
  const total = quotedTotal ?? fallbackTotal;
  const canWrite = hasPermission(session, "ADMIN")
    || hasPermission(session, "VENTA")
    || hasPermission(session, "GESTION_VENTAS")
    || hasPermission(session, documentType === "FACTURA_VENTA"
      ? "INVOICES_WRITE" : "DELIVERY_NOTES_WRITE");
  const ready = canWrite && Boolean(customer && warehouseId && lines.length > 0)
    && !quoteLoading && !quoteError && quotedTotal != null;

  const customerResults = useMemo(() => {
    const normalized = customerQuery.trim().toLocaleLowerCase();
    if (!normalized) return customers.slice(0, 100);
    return customers.filter((option) => [
      option.clientId, option.fiscalName, option.documentNumber,
    ].some((value) => value?.toLocaleLowerCase().includes(normalized))).slice(0, 100);
  }, [customerQuery, customers]);

  useEffect(() => {
    if (!customerOpen || !customerDialogRef.current) return;
    const dialog = customerDialogRef.current;
    const deactivate = activateModalFocusTrap(
      dialog as unknown as ModalFocusRoot,
      document,
    );
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setCustomerOpen(false);
      setCustomerQuery("");
    };
    dialog.addEventListener("keydown", closeOnEscape);
    return () => {
      dialog.removeEventListener("keydown", closeOnEscape);
      deactivate();
    };
  }, [customerOpen]);

  function invalidate() {
    setCheckoutId(uuid());
    setQuotedTotal(null);
    setQuoteError("");
    setStatus("");
  }

  function resetDocument(message = "") {
    setLines([]);
    setCustomer(null);
    setQuery("");
    setCheckoutId(uuid());
    setQuotedTotal(null);
    setQuoteError("");
    setCheckoutMode(null);
    setRecovery(null);
    localStorage.removeItem(recoveryStorageKey(terminalContext.terminalCode));
    setStatus(message);
    queueMicrotask(() => inputRef.current?.focus());
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
      globalDiscount: "0.00",
      completionMode: mode,
      lines: lines.map((line) => ({
        productId: line.product.id,
        quantity: line.quantity,
        code: line.product.code ?? line.product.barcode ?? line.product.id,
        name: line.product.name ?? line.product.code ?? t("sale.main.unnamedProduct"),
        rate: line.product.rate ?? null,
        price: saleLineUnitPrice(line, activeMember).toFixed(2),
        discount: line.discountPercent.toFixed(2),
        ...saleProductFiscalSnapshot(line.product),
      })),
    };
  }

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
      apiRequest<{ total: number | string }>("/pos/sales-document-checkouts/quote", {
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
  }, [activeMember, checkoutId, customer, documentType, lines, warehouseId]);

  function addProduct(product: SaleProduct, openUnitPrice?: number) {
    setLines((current) => addSaleLine(current, product, openUnitPrice));
    invalidate();
    setQuery("");
    queueMicrotask(() => inputRef.current?.focus());
  }

  function requestAddProduct(product: SaleProduct) {
    const existing = lines.some((line) => line.product.id === product.id);
    if (saleProductRequiresOpenPrice(product) && !existing) {
      setPendingOpenPriceProduct(product);
      return;
    }
    addProduct(product);
  }

  function submitSearch() {
    const exact = selectSaleProduct(products, query);
    if (exact) {
      requestAddProduct(exact);
      return;
    }
    if (query.trim()) setSearchOpen(true);
  }

  function updateQuantity(productId: string, change: number) {
    setLines((current) => current.flatMap((line) => {
      if (line.product.id !== productId) return [line];
      const quantity = line.quantity + change;
      return quantity <= 0 ? [] : [{ ...line, quantity: Math.min(9999, quantity) }];
    }));
    invalidate();
  }

  async function saveDraft() {
    if (!ready || saving) return;
    setSaving(true);
    setStatus("");
    try {
      const requestDraft = draft("DRAFT");
      const result = await apiRequest<{ document: { id: string } }>(
        "/pos/sales-document-checkouts",
        {
          token: session.accessToken,
          body: pendingCreateBody(requestDraft, [], Math.round(total * 100)),
        },
      );
      resetDocument(t("salesDocument.savedDraft").replace("{id}", result.document.id));
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("salesDocument.saveError"));
    } finally {
      setSaving(false);
    }
  }

  function startCheckout(mode: CheckoutMode) {
    if (!ready) return;
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
        <div>
          <span>APP VENTA</span>
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
          <div className="sales-document-line-head" aria-hidden="true">
            <span>{t("sale.searchDialog.code")}</span>
            <span>{t("sale.searchDialog.name")}</span>
            <span>{t("sale.main.quantity")}</span>
            <span>{t("sale.searchDialog.price")}</span>
            <span>{t("sale.main.total")}</span>
            <span>{t("salesDocument.actions")}</span>
          </div>
          <div className="sales-document-lines">
            {lines.length === 0 && <p>{t("salesDocument.empty")}</p>}
            {lines.map((line) => (
              <article key={line.product.id}>
                <span>{line.product.code ?? line.product.barcode ?? "\u2014"}</span>
                <strong>{line.product.name ?? t("sale.main.unnamedProduct")}</strong>
                <div className="sales-document-quantity">
                  <button
                    type="button"
                    aria-label={`${t("sale.main.quantity")} -1`}
                    onClick={() => updateQuantity(line.product.id, -1)}
                  >{"\u2212"}</button>
                  <b>{line.quantity}</b>
                  <button
                    type="button"
                    aria-label={`${t("sale.main.quantity")} +1`}
                    onClick={() => updateQuantity(line.product.id, 1)}
                  >+</button>
                </div>
                <span>{formatMoney(saleLineUnitPrice(line, activeMember), locale)}</span>
                <b>{formatMoney(saleLineUnitPrice(line, activeMember) * line.quantity, locale)}</b>
                <button type="button" onClick={() => {
                  setLines((current) => current.filter((candidate) => candidate.product.id !== line.product.id));
                  invalidate();
                }}>{t("salesDocument.remove")}</button>
              </article>
            ))}
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
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <button type="submit">{t("sale.main.search")}</button>
          </form>
          {(loadError || quoteError || status) && (
            <p className={loadError || quoteError ? "sale-action-error" : "sales-document-status"} role="status">
              {loadError || quoteError || status}
            </p>
          )}
          <div className="sales-document-final-actions">
            <button type="button" disabled={!ready || saving} onClick={() => void saveDraft()}>
              {t("salesDocument.saveDraft")}
            </button>
            <button type="button" disabled={!ready || saving} onClick={() => startCheckout("CONFIRM_PENDING")}>
              {t("salesDocument.confirmPending")}
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

      {searchOpen && (
        <SaleProductSearchDialog
          initialQuery={query}
          products={products}
          labels={{
            title: t("sale.searchDialog.title"),
            query: t("sale.searchDialog.query"),
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
          onClose={() => setSearchOpen(false)}
          onSelect={(product) => { setSearchOpen(false); requestAddProduct(product); }}
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
          onCancel={() => setPendingOpenPriceProduct(null)}
          onAccept={(price) => {
            const product = pendingOpenPriceProduct;
            setPendingOpenPriceProduct(null);
            if (product) addProduct(product, price);
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
          >
            <header>
              <h2 id="sales-document-customer-title">{t("salesDocument.selectCustomer")}</h2>
              <button
                type="button"
                aria-label={t("common.close")}
                onClick={() => {
                  setCustomerOpen(false);
                  setCustomerQuery("");
                }}
              >{"\u00d7"}</button>
            </header>
            <input
              autoFocus
              value={customerQuery}
              placeholder={t("salesDocument.customerSearch")}
              onChange={(event) => setCustomerQuery(event.target.value)}
            />
            <div>
              {customerResults.map((option) => (
                <button type="button" key={option.id} onClick={() => {
                  setCustomer(option);
                  setCustomerOpen(false);
                  setCustomerQuery("");
                  invalidate();
                }}>
                  <strong>{option.fiscalName ?? option.clientId ?? option.id}</strong>
                  <span>{option.documentNumber ?? "\u2014"}</span>
                </button>
              ))}
            </div>
          </section>
        </div>
      )}

      {checkoutDraft && effectiveMode && (
        <CustomerPendingSaleDialog
          customerName={recovery?.customer.name
            ?? customer?.fiscalName ?? customer?.clientId ?? t("salesDocument.customer")}
          locale={locale}
          draft={checkoutDraft}
          recovery={recovery ?? undefined}
          token={session.accessToken}
          permissions={session.permissions}
          terminalContext={terminalContext}
          endpointBase="/pos/sales-document-checkouts"
          allowPayments={effectiveMode === "CONFIRM_AND_PAY"}
          requireFullPayment={effectiveMode === "CONFIRM_AND_PAY"}
          lockDocumentType
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
          onSuccess={(result) => {
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

import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ProductCreateDialog } from "./ProductCreateDialog";
import { PromotionPreviewPanel } from "./PromotionPreviewPanel";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";

export type SaleProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  salePrice?: number | string | null;
};

export type SaleLine = {
  product: SaleProduct;
  quantity: number;
  discountPercent: number;
};

export type SaleCustomer = {
  id: string;
  clientId?: string | null;
  fiscalName?: string | null;
  documentNumber?: string | null;
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
    .filter((product) => [product.code, product.barcode, product.name]
      .some((value) => normalizedSearchValue(value).includes(normalizedQuery)))
    .slice(0, limit);
}

export function selectSaleProduct(products: SaleProduct[], query: string) {
  const normalizedQuery = normalizedSearchValue(query);
  if (!normalizedQuery) {
    return undefined;
  }
  const exact = products.find((product) => [product.code, product.barcode]
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
  return Number(line.product.salePrice ?? 0) * line.quantity * (1 - line.discountPercent / 100);
}

export function saleTotal(lines: SaleLine[]) {
  return lines.reduce((total, line) => total + saleLineSubtotal(line), 0);
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
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogReload, setCatalogReload] = useState(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
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
    setLines((current) => addSaleLine(current, product));
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
    if (!selectedLine) return;
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
    } catch {
      setActionError("El descuento debe estar entre 0 y 100");
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
                    {line.quantity} x {formatSaleAmount(line.product.salePrice)}
                    {line.discountPercent > 0 && <small> - {formatSaleAmount(line.discountPercent)}%</small>}
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
              disabled={catalogLoading || catalogError}
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
              <button className="sale-search-result" type="button" key={product.id} onClick={() => addProduct(product)}>
                <span>
                  <strong>{product.name ?? "Producto sin nombre"}</strong>
                  <small>{product.code ?? product.barcode ?? "Sin codigo"}</small>
                </span>
                <b>{formatSaleAmount(product.salePrice)}</b>
              </button>
            ))}
          </div>
          <div className="sale-quick-grid">
            <button type="button" disabled={!selectedLine} onClick={openQuantityDialog}>Cantidad</button>
            <button type="button" disabled={!selectedLine} onClick={openDiscountDialog}>Descuento</button>
            <button type="button" onClick={openCustomerDialog}>Cliente</button>
            <button type="button" disabled={!selectedLine} onClick={() => setActionDialog("remove")}>Anular linea</button>
          </div>
          <section className="sale-payment" aria-label="Cobro">
            <h2>Cobro</h2>
            <div className="sale-payment-actions">
              <button type="button">Efectivo</button>
              <button type="button">Tarjeta</button>
              <button type="button">Pendiente cliente</button>
            </div>
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
              <button type="button" onClick={() => { setSelectedCustomer(null); setActionDialog(null); }}>Sin cliente</button>
              {customerResults.map((customer) => (
                <button type="button" key={customer.id} onClick={() => { setSelectedCustomer(customer); setActionDialog(null); }}>
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

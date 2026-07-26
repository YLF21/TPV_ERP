import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import { createPortal } from "react-dom";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import type { SaleProduct } from "./SaleScreen";
import { SaleProductInformationPanel } from "./SaleProductInformationPanel";
import type { StockInventoryRow } from "./StockScreen";

type ProductDetailView = {
  id: string;
  imageId?: string | null;
  familyId?: string | null;
  subfamilyId?: string | null;
  taxId?: string | null;
  productType?: string | null;
  discountType?: string | null;
  priceUseMode?: string | null;
  name?: string | null;
  description?: string | null;
  comments?: string | null;
  purchasePrice?: number | string | null;
  purchaseDiscountPercent?: number | string | null;
  stockMin?: number | string | null;
  stockMax?: number | string | null;
  packageQuantity?: number | string | null;
  active?: boolean | null;
  taxesIncluded?: boolean | null;
  offerActive?: boolean | null;
  offerFrom?: string | null;
  offerUntil?: string | null;
  offerDiscountPercent?: number | string | null;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  salePrice?: number | string | null;
  memberPrice?: number | string | null;
  wholesalePrice?: number | string | null;
  offerPrice?: number | string | null;
};

type StockLevelView = {
  productId: string;
  warehouseId: string;
  quantity?: number | string | null;
};

type NamedView = {
  id: string;
  name?: string | null;
  percentage?: number | string | null;
};

type SubfamilyView = NamedView & {
  familyId?: string | null;
};

type PromotionView = {
  name?: string | null;
  status?: string | null;
  scope?: string | null;
  targets?: Array<{ type?: string | null; targetId?: string | null }>;
};

type ProductInformationCatalog = {
  families: NamedView[];
  subfamilies: SubfamilyView[];
  taxes: NamedView[];
  promotions: PromotionView[];
};

type Props = {
  product: SaleProduct;
  locale: LocaleCode;
  token?: string;
  interfaceMode: SaleInterfaceMode;
  canManageProducts: boolean;
  onAdd: (product: SaleProduct) => void;
  onClose: () => void;
};

function valueText(value: unknown) {
  return value === null || value === undefined || value === "" ? "" : String(value);
}

function promotionAppliesToProduct(promotion: PromotionView, product: ProductDetailView) {
  if (promotion.status !== "ACTIVE") return false;
  if (promotion.scope === "SALE") return true;
  return (promotion.targets ?? []).some((target) => (
    target.type === "PRODUCT" && target.targetId === product.id
  ) || (
    target.type === "FAMILY" && target.targetId === product.familyId
  ) || (
    target.type === "SUBFAMILY" && target.targetId === product.subfamilyId
  ));
}

export function buildSaleProductInformationRow(
  product: ProductDetailView,
  stock: StockLevelView[],
  catalog: ProductInformationCatalog,
): StockInventoryRow {
  const family = catalog.families.find((candidate) => candidate.id === product.familyId);
  const subfamily = catalog.subfamilies.find((candidate) => candidate.id === product.subfamilyId);
  const tax = catalog.taxes.find((candidate) => candidate.id === product.taxId);
  const totalStock = stock
    .filter((item) => item.productId === product.id)
    .reduce((sum, item) => {
      const quantity = Number(item.quantity ?? 0);
      return sum + (Number.isFinite(quantity) ? quantity : 0);
    }, 0);
  const promotionNames = catalog.promotions
    .filter((promotion) => promotionAppliesToProduct(promotion, product))
    .map((promotion) => valueText(promotion.name))
    .filter(Boolean)
    .join("; ");

  return {
    productId: product.id,
    imageId: product.imageId ?? null,
    active: product.active === false ? "common.no" : "common.yes",
    warehouseId: "TOTAL",
    warehouseName: "TOTAL",
    code: valueText(product.code),
    barcode: valueText(product.barcode),
    barcode2: valueText(product.barcode2),
    name: valueText(product.name || product.id),
    description: valueText(product.description),
    comments: valueText(product.comments),
    purchasePrice: valueText(product.purchasePrice),
    purchaseDiscountPercent: valueText(product.purchaseDiscountPercent),
    packageQuantity: valueText(product.packageQuantity ?? 1),
    stockMin: valueText(product.stockMin),
    stockMax: valueText(product.stockMax),
    salePrice: valueText(product.salePrice),
    memberPrice: valueText(product.memberPrice),
    wholesalePrice: valueText(product.wholesalePrice),
    offerPrice: valueText(product.offerPrice),
    offerDiscountPercent: valueText(product.offerDiscountPercent),
    productType: valueText(product.productType),
    discountType: valueText(product.priceUseMode ?? product.discountType),
    backendDiscountType: valueText(product.discountType),
    familyId: valueText(product.familyId),
    familyName: valueText(family?.name ?? product.familyId),
    subfamilyId: valueText(product.subfamilyId),
    subfamilyName: valueText(subfamily?.name ?? product.subfamilyId),
    taxId: valueText(product.taxId),
    taxName: tax?.percentage === null || tax?.percentage === undefined
      ? valueText(product.taxId)
      : `${valueText(tax.percentage)}%`,
    taxesIncluded: product.taxesIncluded === null || product.taxesIncluded === undefined
      ? "-"
      : product.taxesIncluded ? "common.yes" : "common.no",
    offerActive: product.offerActive === null || product.offerActive === undefined
      ? "-"
      : product.offerActive ? "common.yes" : "common.no",
    offerFrom: valueText(product.offerFrom),
    offerUntil: valueText(product.offerUntil),
    promotionNames: promotionNames || "-",
    quantity: totalStock,
    totalQuantity: totalStock,
  };
}

async function optionalRequest<T>(path: string, token?: string): Promise<T[]> {
  try {
    return await apiRequest<T[]>(path, { token });
  } catch {
    return [];
  }
}

export async function loadSaleProductInformation(
  productId: string,
  token: string | undefined,
  canManageProducts: boolean,
) {
  const productPath = canManageProducts
    ? `/products/management/${encodeURIComponent(productId)}`
    : `/products/${encodeURIComponent(productId)}`;
  const [product, stock, families, taxes, promotions] = await Promise.all([
    apiRequest<ProductDetailView>(productPath, { token }),
    apiRequest<StockLevelView[]>(`/stock?productId=${encodeURIComponent(productId)}`, { token }),
    optionalRequest<NamedView>("/families", token),
    optionalRequest<NamedView>("/taxes/selectable", token),
    canManageProducts ? optionalRequest<PromotionView>("/promotions", token) : Promise.resolve([]),
  ]);
  const subfamilies = product.familyId
    ? await optionalRequest<SubfamilyView>(
      `/families/${encodeURIComponent(product.familyId)}/subfamilies`,
      token,
    )
    : [];
  return buildSaleProductInformationRow(product, stock, {
    families,
    subfamilies,
    taxes,
    promotions,
  });
}

export function SaleProductInformationDialog({
  product,
  locale,
  token,
  interfaceMode,
  canManageProducts,
  onAdd,
  onClose,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const addStartedRef = useRef(false);
  const [information, setInformation] = useState<StockInventoryRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (typeof dialog.showModal === "function") {
      if (!dialog.open) dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    return () => {
      if (dialog.open && typeof dialog.close === "function") {
        dialog.close();
      } else {
        dialog.removeAttribute("open");
      }
    };
  }, []);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    root.focus();
    return deactivate;
  }, []);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    setInformation(null);
    void loadSaleProductInformation(product.id, token, canManageProducts)
      .then((result) => {
        if (active) setInformation(result);
      })
      .catch((failure) => {
        if (active) {
          setError(failure instanceof Error ? failure.message : t("sale.productInformation.loadError"));
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [canManageProducts, product.id, token]);

  useLayoutEffect(() => {
    if (!information) return;
    const dialog = dialogRef.current;
    const content = contentRef.current;
    const resetHorizontalPosition = () => {
      if (dialog) dialog.scrollLeft = 0;
      if (content) content.scrollLeft = 0;
    };
    resetHorizontalPosition();
    const frame = typeof window.requestAnimationFrame === "function"
      ? window.requestAnimationFrame(resetHorizontalPosition)
      : null;
    return () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
    };
  }, [information]);

  function addProduct() {
    if (addStartedRef.current) return;
    addStartedRef.current = true;
    onAdd(product);
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key === "Insert" && !event.repeat) {
      event.preventDefault();
      event.stopPropagation();
      addProduct();
    }
  }

  return createPortal((
    <dialog
      ref={dialogRef}
      className={`sale-product-information-dialog ${interfaceMode === "TOUCH" ? "is-touch" : "is-keyboard"}`}
      aria-labelledby="sale-product-information-title"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onMouseDown={(event) => {
        if (event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        const outsideDialog = event.clientX < bounds.left
          || event.clientX > bounds.right
          || event.clientY < bounds.top
          || event.clientY > bounds.bottom;
        if (outsideDialog) onClose();
      }}
      onKeyDown={handleKeyDown}
    >
      <header>
        <h2 id="sale-product-information-title">{product.name ?? t("sale.main.unnamedProduct")}</h2>
        <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
      </header>
      <div ref={contentRef} className="sale-product-information-content">
        {loading && <p className="sale-product-information-state" role="status">{t("sale.productInformation.loading")}</p>}
        {error && <p className="sale-action-error sale-product-information-state" role="alert">{error}</p>}
        {information && (
          <SaleProductInformationPanel
            product={information}
            locale={locale}
            token={token}
            canReadSuppliers={canManageProducts}
            canViewPurchaseFields={canManageProducts}
          />
        )}
      </div>
      <footer>
        <button type="button" onClick={onClose}>{t("common.close")}</button>
        <button className="primary" type="button" onClick={addProduct}>
          {interfaceMode === "KEYBOARD" && <kbd>Insert</kbd>}
          {t("sale.productInformation.addToCart")}
        </button>
      </footer>
    </dialog>
  ), document.body);
}

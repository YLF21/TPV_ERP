import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { StockInventoryRow } from "./StockScreen";

type ProductSupplierView = {
  supplierId: string;
  legalName: string;
  documentType?: string | null;
  documentNumber?: string | null;
  active: boolean;
  supplierReference?: string | null;
  principal: boolean;
  lastSupplier: boolean;
  grossPurchasePrice?: number | string | null;
  purchaseDiscount?: number | string | null;
  netPurchasePrice?: number | string | null;
  lastEntryAt?: string | null;
};

type StockProductInformationPanelProps = {
  product: StockInventoryRow;
  locale: LocaleCode;
  token?: string;
  canReadSuppliers: boolean;
  canViewPurchaseFields: boolean;
};

type InformationField = {
  label: string;
  value: string;
  wide?: boolean;
};

export function sortProductInformationSuppliers(suppliers: ProductSupplierView[]) {
  return [...suppliers].sort((left, right) => {
    const principal = Number(right.principal) - Number(left.principal);
    if (principal !== 0) return principal;
    const last = Number(right.lastSupplier) - Number(left.lastSupplier);
    if (last !== 0) return last;
    return left.legalName.localeCompare(right.legalName, undefined, { sensitivity: "base" });
  });
}

function numericValue(value: unknown) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(String(value).replace(",", "."));
  return Number.isFinite(parsed) ? parsed : null;
}

export function calculateNetPurchasePrice(purchasePrice: unknown, discountPercent: unknown) {
  const price = numericValue(purchasePrice);
  if (price === null) return null;
  const discount = discountPercent === null || discountPercent === undefined || discountPercent === ""
    ? 0
    : numericValue(discountPercent);
  return discount === null ? null : price * (1 - discount / 100);
}

function valueOrDash(value: unknown) {
  return value === null || value === undefined || value === "" ? "-" : String(value);
}

function translatedValue(value: unknown, t: (key: string) => string) {
  const text = valueOrDash(value);
  return text.startsWith("common.") ? t(text) : text;
}

function productTypeValue(value: string, t: (key: string) => string) {
  if (value === "WEIGHT") return t("product.type.weight");
  if (value === "SERVICE") return t("product.type.service");
  if (value === "UNIT") return t("product.type.unit");
  return valueOrDash(value);
}

function priceUseValue(value: string, t: (key: string) => string) {
  if (value === "MEMBER_PRICE") return t("product.discount.memberPrice");
  if (value === "OFFER_PRICE") return t("product.discount.offerPrice");
  if (value === "OFFER_DISCOUNT") return t("product.discount.offerDiscount");
  if (value === "NONE") return t("product.discount.none");
  if (value === "NORMAL") return t("product.discount.salePrice");
  return valueOrDash(value);
}

function InformationSection({ title, fields }: { title: string; fields: InformationField[] }) {
  return (
    <section className="stock-product-information-section">
      <h3>{title}</h3>
      <dl className="stock-product-information-fields">
        {fields.map((field) => (
          <div className={field.wide ? "wide" : ""} key={field.label}>
            <dt>{field.label}</dt>
            <dd title={field.value}>{field.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function StockProductInformationPanel({
  product,
  locale,
  token,
  canReadSuppliers,
  canViewPurchaseFields
}: StockProductInformationPanelProps) {
  const t = createTranslator(locale);
  const [imageSource, setImageSource] = useState("");
  const [suppliers, setSuppliers] = useState<ProductSupplierView[]>([]);
  const [supplierState, setSupplierState] = useState<"idle" | "loading" | "loaded" | "error">("idle");
  const numberFormatter = useMemo(() => new Intl.NumberFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { minimumFractionDigits: 2, maximumFractionDigits: 2 }
  ), [locale]);
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { dateStyle: "short" }
  ), [locale]);

  useEffect(() => {
    if (!product.imageId || !token) {
      setImageSource("");
      return;
    }
    let active = true;
    let objectUrl = "";
    void fetch(`${apiBaseUrl}/products/${encodeURIComponent(product.productId)}/image`, {
      headers: { Authorization: `Bearer ${token}` }
    }).then((response) => {
      if (!response.ok) throw new Error("product_image_unavailable");
      return response.blob();
    }).then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setImageSource(objectUrl);
    }).catch(() => {
      if (active) setImageSource("");
    });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [product.imageId, product.productId, token]);

  useEffect(() => {
    if (!token || !canReadSuppliers) {
      setSuppliers([]);
      setSupplierState("idle");
      return;
    }
    let active = true;
    setSupplierState("loading");
    void apiRequest<ProductSupplierView[]>(
      `/products/${encodeURIComponent(product.productId)}/suppliers`,
      { token }
    ).then((values) => {
      if (!active) return;
      setSuppliers(sortProductInformationSuppliers(values));
      setSupplierState("loaded");
    }).catch(() => {
      if (!active) return;
      setSuppliers([]);
      setSupplierState("error");
    });
    return () => {
      active = false;
    };
  }, [canReadSuppliers, product.productId, token]);

  function decimal(value: unknown) {
    if (value === null || value === undefined || value === "") return "-";
    const number = Number(String(value ?? "").replace(",", "."));
    return Number.isFinite(number) ? numberFormatter.format(number) : valueOrDash(value);
  }

  function percentage(value: unknown) {
    const formatted = decimal(value);
    return formatted === "-" ? formatted : `${formatted}%`;
  }

  function date(value: string | null | undefined) {
    if (!value) return "-";
    const parsed = new Date(value.includes("T") ? value : `${value}T00:00:00`);
    return Number.isNaN(parsed.getTime()) ? value : dateFormatter.format(parsed);
  }

  return (
    <aside className="stock-product-information" aria-label={t("stock.detail.informationTitle")}>
      <header>
        <h2>{t("stock.detail.informationTitle")}</h2>
        <span>{translatedValue(product.active, t)}</span>
      </header>
      <div className="stock-product-information-image">
        {imageSource
          ? <img src={imageSource} alt={product.name} />
          : <span>{product.name.slice(0, 1).toLocaleUpperCase() || "-"}</span>}
      </div>

      <InformationSection
        title={t("stock.detail.identification")}
        fields={[
          { label: t("stock.column.code"), value: valueOrDash(product.code) },
          { label: t("stock.column.barcode"), value: valueOrDash(product.barcode) },
          { label: t("stock.column.barcode2"), value: valueOrDash(product.barcode2) },
          { label: t("stock.column.name"), value: valueOrDash(product.name), wide: true },
          { label: t("product.field.description"), value: valueOrDash(product.description), wide: true },
          { label: t("product.field.comments"), value: valueOrDash(product.comments), wide: true }
        ]}
      />

      <InformationSection
        title={t("stock.detail.classification")}
        fields={[
          { label: t("stock.column.type"), value: productTypeValue(product.productType, t) },
          { label: t("stock.column.family"), value: valueOrDash(product.familyName) },
          { label: t("stock.column.subfamily"), value: valueOrDash(product.subfamilyName) },
          { label: t("stock.column.tax"), value: valueOrDash(product.taxName) },
          { label: t("stock.column.taxIncluded"), value: translatedValue(product.taxesIncluded, t) },
          { label: t("stock.column.packageQuantity"), value: decimal(product.packageQuantity) }
        ]}
      />

      {canViewPurchaseFields && (
        <InformationSection
          title={t("stock.detail.purchasePrices")}
          fields={[
            { label: t("stock.column.purchasePrice"), value: decimal(product.purchasePrice) },
            { label: t("stock.column.purchaseDiscount"), value: percentage(product.purchaseDiscountPercent) },
            {
              label: t("stock.column.netPurchasePrice"),
              value: decimal(calculateNetPurchasePrice(product.purchasePrice, product.purchaseDiscountPercent))
            }
          ]}
        />
      )}

      <InformationSection
        title={t("stock.detail.salePrices")}
        fields={[
          { label: t("stock.column.salePrice"), value: decimal(product.salePrice) },
          { label: t("stock.column.memberPrice"), value: decimal(product.memberPrice) },
          { label: t("stock.column.wholesalePrice"), value: decimal(product.wholesalePrice) },
          { label: t("product.field.usePrice"), value: priceUseValue(product.discountType, t) }
        ]}
      />

      <InformationSection
        title={t("stock.detail.offer")}
        fields={[
          { label: t("stock.column.offerActive"), value: translatedValue(product.offerActive, t) },
          { label: t("stock.column.offerPrice"), value: decimal(product.offerPrice) },
          { label: t("product.field.offerDiscountPercent"), value: percentage(product.offerDiscountPercent) },
          { label: t("stock.column.offerFrom"), value: date(product.offerFrom) },
          { label: t("stock.column.offerUntil"), value: date(product.offerUntil) },
          { label: t("stock.column.promotion"), value: valueOrDash(product.promotionNames), wide: true }
        ]}
      />

      <InformationSection
        title={t("stock.detail.inventory")}
        fields={[
          { label: t("stock.column.stockMin"), value: decimal(product.stockMin) },
          { label: t("stock.column.stockMax"), value: decimal(product.stockMax) },
          { label: t("stock.column.totalStock"), value: decimal(product.totalQuantity) }
        ]}
      />

      <section className="stock-product-information-section stock-product-suppliers">
        <h3>{t("stock.detail.suppliers")}</h3>
        {!canReadSuppliers && <p>{t("stock.detail.suppliersRestricted")}</p>}
        {canReadSuppliers && supplierState === "loading" && <p>{t("common.loading")}</p>}
        {canReadSuppliers && supplierState === "error" && <p>{t("stock.detail.suppliersLoadError")}</p>}
        {canReadSuppliers && supplierState === "loaded" && suppliers.length === 0 && <p>{t("stock.detail.suppliersEmpty")}</p>}
        {suppliers.map((supplier) => (
          <article className={`stock-product-supplier ${supplier.principal ? "principal" : ""}`} key={supplier.supplierId}>
            <header>
              <strong>
                {supplier.principal && (
                  <span className="stock-product-principal-star" aria-label={t("stock.detail.principalSupplier")} title={t("stock.detail.principalSupplier")}>★</span>
                )}
                {supplier.legalName}
              </strong>
              {supplier.lastSupplier && <span>{t("stock.detail.lastSupplier")}</span>}
            </header>
            <dl>
              <div><dt>{t("stock.detail.supplierDocument")}</dt><dd>{valueOrDash(supplier.documentNumber)}</dd></div>
              <div><dt>{t("stock.detail.supplierReference")}</dt><dd>{valueOrDash(supplier.supplierReference)}</dd></div>
              <div><dt>{t("stock.detail.supplierGrossPrice")}</dt><dd>{decimal(supplier.grossPurchasePrice)}</dd></div>
              <div><dt>{t("stock.detail.supplierDiscount")}</dt><dd>{percentage(supplier.purchaseDiscount)}</dd></div>
              <div><dt>{t("stock.detail.supplierNetPrice")}</dt><dd>{decimal(supplier.netPurchasePrice)}</dd></div>
              <div><dt>{t("stock.detail.supplierLastEntry")}</dt><dd>{date(supplier.lastEntryAt)}</dd></div>
            </dl>
          </article>
        ))}
      </section>
    </aside>
  );
}

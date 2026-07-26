import { useMemo } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import {
  calculateNetPurchasePrice,
  useProductInformationResources,
  type ProductInformationSupplierView,
} from "./productInformationResources";
import type { StockInventoryRow } from "./StockScreen";

type SaleProductInformationPanelProps = {
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

function InformationSection({
  title,
  fields,
  className = "",
}: {
  title: string;
  fields: InformationField[];
  className?: string;
}) {
  return (
    <section className={`sale-product-details-section${className ? ` ${className}` : ""}`}>
      <h3>{title}</h3>
      <dl className="sale-product-details-fields">
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

function SupplierCard({
  supplier,
  decimal,
  percentage,
  date,
  t,
}: {
  supplier: ProductInformationSupplierView;
  decimal: (value: unknown) => string;
  percentage: (value: unknown) => string;
  date: (value: string | null | undefined) => string;
  t: (key: string) => string;
}) {
  return (
    <article className={`sale-product-supplier${supplier.principal ? " principal" : ""}`}>
      <header>
        <strong>
          {supplier.principal && (
            <span aria-label={t("stock.detail.principalSupplier")} title={t("stock.detail.principalSupplier")}>★</span>
          )}
          {supplier.legalName}
        </strong>
        {supplier.lastSupplier && <small>{t("stock.detail.lastSupplier")}</small>}
      </header>
      <dl className="sale-product-details-fields">
        <div><dt>{t("stock.detail.supplierDocument")}</dt><dd>{valueOrDash(supplier.documentNumber)}</dd></div>
        <div><dt>{t("stock.detail.supplierReference")}</dt><dd>{valueOrDash(supplier.supplierReference)}</dd></div>
        <div><dt>{t("stock.detail.supplierGrossPrice")}</dt><dd>{decimal(supplier.grossPurchasePrice)}</dd></div>
        <div><dt>{t("stock.detail.supplierDiscount")}</dt><dd>{percentage(supplier.purchaseDiscount)}</dd></div>
        <div><dt>{t("stock.detail.supplierNetPrice")}</dt><dd>{decimal(supplier.netPurchasePrice)}</dd></div>
        <div><dt>{t("stock.detail.supplierLastEntry")}</dt><dd>{date(supplier.lastEntryAt)}</dd></div>
      </dl>
    </article>
  );
}

export function SaleProductInformationPanel({
  product,
  locale,
  token,
  canReadSuppliers,
  canViewPurchaseFields,
}: SaleProductInformationPanelProps) {
  const t = createTranslator(locale);
  const { imageSource, suppliers, supplierState } = useProductInformationResources({
    productId: product.productId,
    imageId: product.imageId,
    token,
    canReadSuppliers,
  });
  const numberFormatter = useMemo(() => new Intl.NumberFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { minimumFractionDigits: 2, maximumFractionDigits: 2 },
  ), [locale]);
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { dateStyle: "short" },
  ), [locale]);

  function decimal(value: unknown) {
    if (value === null || value === undefined || value === "") return "-";
    const number = Number(String(value).replace(",", "."));
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
    <article className="sale-product-details" aria-label={t("stock.detail.informationTitle")}>
      <div className="sale-product-details-inner">
        <section className="sale-product-details-hero">
          <div className="sale-product-details-image">
            {imageSource
              ? <img src={imageSource} alt={product.name} />
              : <span>{product.name.slice(0, 1).toLocaleUpperCase() || "-"}</span>}
          </div>
          <div className="sale-product-details-summary">
            <p className="sale-product-details-eyebrow">{t("stock.detail.informationTitle")}</p>
            <div className="sale-product-details-status">
              {product.active === "common.no" && (
                <strong className="sale-product-details-inactive">
                  {t("sale.inactiveProduct.title")}
                </strong>
              )}
              <b>{valueOrDash(product.code)}</b>
            </div>
            <h3>{valueOrDash(product.name)}</h3>
            <dl className="sale-product-details-quick">
              <div><dt>{t("stock.column.barcode")}</dt><dd>{valueOrDash(product.barcode)}</dd></div>
              <div><dt>{t("stock.column.salePrice")}</dt><dd>{decimal(product.salePrice)}</dd></div>
              <div><dt>{t("stock.column.totalStock")}</dt><dd>{decimal(product.totalQuantity)}</dd></div>
            </dl>
          </div>
        </section>

        <div className="sale-product-details-sections">
          <InformationSection
            title={t("stock.detail.identification")}
            className="wide"
            fields={[
              { label: t("stock.column.code"), value: valueOrDash(product.code) },
              { label: t("stock.column.barcode"), value: valueOrDash(product.barcode) },
              { label: t("stock.column.barcode2"), value: valueOrDash(product.barcode2) },
              { label: t("stock.column.name"), value: valueOrDash(product.name), wide: true },
              { label: t("product.field.description"), value: valueOrDash(product.description), wide: true },
              { label: t("product.field.comments"), value: valueOrDash(product.comments), wide: true },
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
              { label: t("stock.column.packageQuantity"), value: decimal(product.packageQuantity) },
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
                  value: decimal(calculateNetPurchasePrice(product.purchasePrice, product.purchaseDiscountPercent)),
                },
              ]}
            />
          )}

          <InformationSection
            title={t("stock.detail.salePrices")}
            fields={[
              { label: t("stock.column.salePrice"), value: decimal(product.salePrice) },
              { label: t("stock.column.memberPrice"), value: decimal(product.memberPrice) },
              { label: t("stock.column.wholesalePrice"), value: decimal(product.wholesalePrice) },
              { label: t("product.field.usePrice"), value: priceUseValue(product.discountType, t) },
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
              { label: t("stock.column.promotion"), value: valueOrDash(product.promotionNames), wide: true },
            ]}
          />

          <InformationSection
            title={t("stock.detail.inventory")}
            fields={[
              { label: t("stock.column.stockMin"), value: decimal(product.stockMin) },
              { label: t("stock.column.stockMax"), value: decimal(product.stockMax) },
              { label: t("stock.column.totalStock"), value: decimal(product.totalQuantity) },
            ]}
          />

          <section className="sale-product-details-section sale-product-details-suppliers">
            <h3>{t("stock.detail.suppliers")}</h3>
            {!canReadSuppliers && <p>{t("stock.detail.suppliersRestricted")}</p>}
            {canReadSuppliers && supplierState === "loading" && <p>{t("common.loading")}</p>}
            {canReadSuppliers && supplierState === "error" && <p>{t("stock.detail.suppliersLoadError")}</p>}
            {canReadSuppliers && supplierState === "loaded" && suppliers.length === 0 && (
              <p>{t("stock.detail.suppliersEmpty")}</p>
            )}
            {suppliers.length > 0 && (
              <div className="sale-product-supplier-grid">
                {suppliers.map((supplier) => (
                  <SupplierCard
                    key={supplier.supplierId}
                    supplier={supplier}
                    decimal={decimal}
                    percentage={percentage}
                    date={date}
                    t={t}
                  />
                ))}
              </div>
            )}
          </section>
        </div>
      </div>
    </article>
  );
}

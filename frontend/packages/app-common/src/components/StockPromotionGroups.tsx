import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent
} from "react";
import type { LocaleCode } from "../types";
import type { PromotionTargetType, PromotionView } from "./PromotionForm";

export const stockPromotionGroupsMessageKeys = {
  tableLabel: "stock.promotions",
  empty: "stock.promotions.groups.empty",
  noProducts: "stock.promotions.groups.noProducts",
  rules: "stock.promotions.groups.rules",
  columns: {
    promotion: "stock.column.promotion",
    type: "stock.column.promotionType",
    validity: "stock.column.promotionValidity",
    scope: "promotion.field.scope",
    products: "salesReport.column.products",
    status: "stock.column.promotionStatus",
    code: "stock.column.code",
    name: "stock.column.name",
    family: "stock.column.family",
    subfamily: "stock.column.subfamily",
    stock: "stock.column.stock"
  },
  sections: {
    conditions: "promotion.step.conditions",
    benefit: "promotion.step.benefit",
    products: "salesReport.column.products"
  },
  fields: {
    startDate: "promotion.field.startDate",
    endDate: "promotion.field.endDate",
    noEndDate: "promotion.noEndDate",
    customerSegment: "promotion.field.customerSegment",
    memberCategory: "promotion.field.memberCategoryId",
    minimumAmount: "promotion.field.minimumAmount",
    minimumQuantity: "promotion.field.minimumQuantity",
    buyQuantity: "promotion.field.buyQuantity",
    payQuantity: "promotion.field.payQuantity",
    discountPercent: "promotion.field.discountPercent",
    discountAmount: "promotion.field.discountAmount",
    packPrice: "promotion.field.packPrice",
    buyXPayYMode: "promotion.field.buyXPayYMode",
    maximumDiscount: "promotion.field.maximumDiscount"
  },
  dynamicPrefixes: {
    type: "promotion.type.",
    scope: "promotion.scope.",
    segment: "promotion.segment."
  }
} as const;

export type StockPromotionProductRow = {
  id?: string | null;
  productId?: string | null;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  familyId?: string | null;
  familyName?: string | null;
  subfamilyId?: string | null;
  subfamilyName?: string | null;
  quantity?: number | string | null;
  totalQuantity?: number | string | null;
};

export type StockPromotionProduct = {
  productId: string;
  code: string;
  barcode: string;
  name: string;
  familyId: string;
  familyName: string;
  subfamilyId: string;
  subfamilyName: string;
  stock: number | null;
};

export type StockPromotionGroup = {
  promotion: PromotionView;
  products: StockPromotionProduct[];
};

export type StockPromotionGroupsProps = {
  locale: LocaleCode;
  promotions: readonly PromotionView[];
  productRows: readonly StockPromotionProductRow[];
  t: (key: string) => string;
  className?: string;
  defaultExpandedPromotionIds?: readonly string[];
  hideEmptyGroups?: boolean;
};

type ProductAccumulator = Omit<StockPromotionProduct, "stock"> & {
  totalStock: number | null;
  quantityStock: number;
  hasQuantityStock: boolean;
};

type PromotionDetailItem = {
  label: string;
  value: string;
};

export type StockPromotionNavigationKey = "ArrowDown" | "ArrowUp" | "Home" | "End";

const promotionRowStyle: CSSProperties = {
  gridTemplateColumns: "38px minmax(190px, 1.5fr) minmax(150px, 1fr) minmax(150px, 0.9fr) minmax(120px, 0.8fr) 90px 90px",
  cursor: "pointer"
};

const groupListStyle: CSSProperties = {
  display: "grid",
  gap: 8
};

const groupStyle: CSSProperties = {
  display: "grid",
  gap: 8
};

const detailSectionsStyle: CSSProperties = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))",
  gap: 12
};

const detailSectionStyle: CSSProperties = {
  minWidth: 0
};

/**
 * StockScreen integration contract:
 * - pass the raw `/promotions` result in `promotions` and either ProductView[] or
 *   StockInventoryRow[] in `productRows`;
 * - pass StockScreen's current `locale` and `t` translator;
 * - add the three `stock.promotions.groups.*` keys above to each message catalog;
 * - export this component from app-common's index when the screen is wired.
 */
export function StockPromotionGroups({
  locale,
  promotions,
  productRows,
  t,
  className = "",
  defaultExpandedPromotionIds = [],
  hideEmptyGroups = false
}: StockPromotionGroupsProps) {
  const groups = useMemo(
    () => buildStockPromotionGroups(promotions, productRows, !hideEmptyGroups),
    [hideEmptyGroups, productRows, promotions]
  );
  const groupIdsKey = groups.map((group) => group.promotion.id).join("\u0000");
  const [expandedPromotionIds, setExpandedPromotionIds] = useState<Set<string>>(() => {
    const activeIds = new Set(groups.map((group) => group.promotion.id));
    return new Set(defaultExpandedPromotionIds.filter((id) => activeIds.has(id)));
  });
  const [activePromotionId, setActivePromotionId] = useState(groups[0]?.promotion.id ?? "");
  const rowRefs = useRef(new Map<string, HTMLDivElement>());
  const componentId = useId();

  useEffect(() => {
    const activeIds = new Set(groups.map((group) => group.promotion.id));
    setExpandedPromotionIds((current) => {
      const next = new Set(Array.from(current).filter((id) => activeIds.has(id)));
      return next.size === current.size ? current : next;
    });
    setActivePromotionId((current) => activeIds.has(current) ? current : groups[0]?.promotion.id ?? "");
  }, [groupIdsKey]);

  function setExpanded(promotionId: string, expanded?: boolean) {
    setExpandedPromotionIds((current) => {
      const next = new Set(current);
      const shouldExpand = expanded ?? !next.has(promotionId);
      if (shouldExpand) {
        next.add(promotionId);
      } else {
        next.delete(promotionId);
      }
      return next;
    });
  }

  function focusGroup(index: number) {
    const group = groups[index];
    if (!group) {
      return;
    }
    setActivePromotionId(group.promotion.id);
    rowRefs.current.get(group.promotion.id)?.focus();
  }

  function handleRowKeyDown(
    event: KeyboardEvent<HTMLDivElement>,
    groupIndex: number,
    promotionId: string
  ) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      setExpanded(promotionId);
      return;
    }
    if (event.key === "ArrowRight") {
      event.preventDefault();
      setExpanded(promotionId, true);
      return;
    }
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      setExpanded(promotionId, false);
      return;
    }
    if (!isStockPromotionNavigationKey(event.key)) {
      return;
    }
    event.preventDefault();
    focusGroup(stockPromotionNavigationIndex(groupIndex, event.key, groups.length));
  }

  const rootClassName = ["stock-table", "stock-promotion-groups", className].filter(Boolean).join(" ");

  return (
    <section className={rootClassName} aria-label={t(stockPromotionGroupsMessageKeys.tableLabel)}>
      <div className="stock-row stock-row-head" style={promotionRowStyle} aria-hidden="true">
        <span />
        <span>{t(stockPromotionGroupsMessageKeys.columns.promotion)}</span>
        <span>{t(stockPromotionGroupsMessageKeys.columns.type)}</span>
        <span>{t(stockPromotionGroupsMessageKeys.columns.validity)}</span>
        <span>{t(stockPromotionGroupsMessageKeys.columns.scope)}</span>
        <span>{t(stockPromotionGroupsMessageKeys.columns.products)}</span>
        <span>{t(stockPromotionGroupsMessageKeys.columns.status)}</span>
      </div>

      {groups.length === 0 ? (
        <p className="stock-empty-state" role="status">
          {t(stockPromotionGroupsMessageKeys.empty)}
        </p>
      ) : (
        <div role="list" style={groupListStyle}>
          {groups.map((group, groupIndex) => {
            const { promotion, products } = group;
            const expanded = expandedPromotionIds.has(promotion.id);
            const selected = activePromotionId === promotion.id;
            const nameId = `${componentId}-promotion-${groupIndex}`;
            const detailId = `${componentId}-detail-${groupIndex}`;
            const conditions = promotionConditionItems(promotion, locale, t);
            const benefits = promotionBenefitItems(promotion, locale, t);
            const rules = promotionRuleItems(promotion, products, locale, t);

            return (
              <article key={promotion.id} role="listitem" style={groupStyle}>
                <div
                  ref={(node) => {
                    if (node) {
                      rowRefs.current.set(promotion.id, node);
                    } else {
                      rowRefs.current.delete(promotion.id);
                    }
                  }}
                  className={`stock-row ${selected ? "selected" : ""}`}
                  style={promotionRowStyle}
                  role="button"
                  tabIndex={selected ? 0 : -1}
                  aria-expanded={expanded}
                  aria-controls={detailId}
                  onFocus={() => setActivePromotionId(promotion.id)}
                  onClick={(event) => {
                    event.currentTarget.focus();
                    if (event.detail <= 1) {
                      setExpanded(promotion.id);
                    }
                  }}
                  onDoubleClick={(event) => {
                    event.preventDefault();
                    event.currentTarget.focus();
                    setExpanded(promotion.id, true);
                  }}
                  onKeyDown={(event) => handleRowKeyDown(event, groupIndex, promotion.id)}
                >
                  <span className="stock-cell" aria-hidden="true"><strong>{expanded ? "-" : "+"}</strong></span>
                  <span className="stock-cell"><strong id={nameId}>{promotion.name}</strong></span>
                  <span className="stock-cell">{translatedValue(stockPromotionGroupsMessageKeys.dynamicPrefixes.type, promotion.type, t)}</span>
                  <span className="stock-cell">{promotionValidity(promotion, locale, t)}</span>
                  <span className="stock-cell">{translatedValue(stockPromotionGroupsMessageKeys.dynamicPrefixes.scope, promotion.scope, t)}</span>
                  <span className="stock-cell"><b>{products.length}</b></span>
                  <span className="stock-cell">{t(`promotion.status.${promotion.status}`)}</span>
                </div>

                {expanded && (
                  <div id={detailId} role="region" aria-labelledby={nameId} className="promotion-preview-panel">
                    <div className="promotion-preview-heading">
                      <h3>{t(stockPromotionGroupsMessageKeys.sections.products)}</h3>
                      <span>{products.length}</span>
                    </div>
                    <PromotionProductsTable products={products} locale={locale} t={t} />
                    <div style={detailSectionsStyle}>
                      <PromotionDetailSection
                        title={t(stockPromotionGroupsMessageKeys.sections.conditions)}
                        items={conditions}
                      />
                      <PromotionDetailSection
                        title={t(stockPromotionGroupsMessageKeys.sections.benefit)}
                        items={benefits}
                      />
                      <PromotionDetailSection
                        title={t(stockPromotionGroupsMessageKeys.rules)}
                        items={rules}
                      />
                    </div>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

export function buildStockPromotionGroups(
  promotions: readonly PromotionView[],
  productRows: readonly StockPromotionProductRow[],
  includeEmptyGroups = true
): StockPromotionGroup[] {
  const products = normalizeStockPromotionProducts(productRows);
  const seenPromotionIds = new Set<string>();

  return promotions.flatMap((promotion) => {
    if (promotion.status !== "ACTIVE" || seenPromotionIds.has(promotion.id)) {
      return [];
    }
    seenPromotionIds.add(promotion.id);
    const matchingProducts = products.filter((product) => promotionIncludesProduct(promotion, product));
    if (!includeEmptyGroups && matchingProducts.length === 0) {
      return [];
    }
    return [{
      promotion,
      products: matchingProducts
    }];
  });
}

export function stockPromotionNavigationIndex(
  currentIndex: number,
  key: StockPromotionNavigationKey,
  rowCount: number
) {
  if (rowCount <= 0) {
    return -1;
  }
  if (key === "Home") {
    return 0;
  }
  if (key === "End") {
    return rowCount - 1;
  }
  if (key === "ArrowDown") {
    return Math.min(rowCount - 1, currentIndex + 1);
  }
  return Math.max(0, currentIndex - 1);
}

function normalizeStockPromotionProducts(rows: readonly StockPromotionProductRow[]) {
  const products = new Map<string, ProductAccumulator>();

  rows.forEach((row) => {
    const productId = textValue(row.productId) || textValue(row.id);
    if (!productId) {
      return;
    }
    const totalQuantity = numericValue(row.totalQuantity);
    const quantity = numericValue(row.quantity);
    const current = products.get(productId);
    if (!current) {
      products.set(productId, {
        productId,
        code: displayText(row.code),
        barcode: displayText(row.barcode),
        name: displayText(row.name) || productId,
        familyId: textValue(row.familyId),
        familyName: displayText(row.familyName),
        subfamilyId: textValue(row.subfamilyId),
        subfamilyName: displayText(row.subfamilyName),
        totalStock: totalQuantity,
        quantityStock: quantity ?? 0,
        hasQuantityStock: quantity !== null
      });
      return;
    }

    current.code = current.code || displayText(row.code);
    current.barcode = current.barcode || displayText(row.barcode);
    current.name = current.name || displayText(row.name) || productId;
    current.familyId = current.familyId || textValue(row.familyId);
    current.familyName = current.familyName || displayText(row.familyName);
    current.subfamilyId = current.subfamilyId || textValue(row.subfamilyId);
    current.subfamilyName = current.subfamilyName || displayText(row.subfamilyName);
    if (totalQuantity !== null) {
      current.totalStock = current.totalStock === null ? totalQuantity : Math.max(current.totalStock, totalQuantity);
    }
    if (quantity !== null) {
      current.quantityStock += quantity;
      current.hasQuantityStock = true;
    }
  });

  return Array.from(products.values()).map((product): StockPromotionProduct => ({
    productId: product.productId,
    code: product.code,
    barcode: product.barcode,
    name: product.name,
    familyId: product.familyId,
    familyName: product.familyName,
    subfamilyId: product.subfamilyId,
    subfamilyName: product.subfamilyName,
    stock: product.totalStock ?? (product.hasQuantityStock ? product.quantityStock : null)
  }));
}

function promotionIncludesProduct(promotion: PromotionView, product: StockPromotionProduct) {
  if (promotion.scope === "SALE") {
    return true;
  }
  return promotion.targets.some((target) => {
    if (target.type === "PRODUCT") {
      return target.targetId === product.productId;
    }
    if (target.type === "FAMILY") {
      return target.targetId === product.familyId;
    }
    return target.targetId === product.subfamilyId;
  });
}

function PromotionProductsTable({
  products,
  locale,
  t
}: {
  products: readonly StockPromotionProduct[];
  locale: LocaleCode;
  t: (key: string) => string;
}) {
  if (products.length === 0) {
    return <p className="promotion-empty">{t(stockPromotionGroupsMessageKeys.noProducts)}</p>;
  }

  return (
    <div className="report-table-scroll">
      <table className="report-table">
        <thead>
          <tr>
            <th scope="col">{t(stockPromotionGroupsMessageKeys.columns.code)}</th>
            <th scope="col">{t(stockPromotionGroupsMessageKeys.columns.name)}</th>
            <th scope="col">{t(stockPromotionGroupsMessageKeys.columns.family)}</th>
            <th scope="col">{t(stockPromotionGroupsMessageKeys.columns.subfamily)}</th>
            <th scope="col">{t(stockPromotionGroupsMessageKeys.columns.stock)}</th>
          </tr>
        </thead>
        <tbody>
          {products.map((product) => (
            <tr key={product.productId}>
              <td><strong>{product.code || "-"}</strong></td>
              <td><span className="product-name-text">{product.name}</span></td>
              <td>{product.familyName || product.familyId || "-"}</td>
              <td>{product.subfamilyName || product.subfamilyId || "-"}</td>
              <td>{formatNumber(product.stock, locale)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PromotionDetailSection({ title, items }: { title: string; items: readonly PromotionDetailItem[] }) {
  return (
    <section style={detailSectionStyle}>
      <div className="promotion-preview-heading"><h3>{title}</h3></div>
      {items.length === 0 ? (
        <p className="promotion-empty">-</p>
      ) : (
        <dl className="promotion-summary">
          {items.map((item, index) => (
            <div key={`${item.label}-${index}`}>
              <dt>{item.label}</dt>
              <dd>{item.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function promotionConditionItems(
  promotion: PromotionView,
  locale: LocaleCode,
  t: (key: string) => string
): PromotionDetailItem[] {
  const items: PromotionDetailItem[] = [
    {
      label: t(stockPromotionGroupsMessageKeys.fields.startDate),
      value: formatDate(promotion.startDate, locale)
    },
    {
      label: t(stockPromotionGroupsMessageKeys.fields.endDate),
      value: promotion.endDate
        ? formatDate(promotion.endDate, locale)
        : t(stockPromotionGroupsMessageKeys.fields.noEndDate)
    }
  ];

  if (promotion.customerSegment) {
    items.push({
      label: t(stockPromotionGroupsMessageKeys.fields.customerSegment),
      value: translatedValue(stockPromotionGroupsMessageKeys.dynamicPrefixes.segment, promotion.customerSegment, t)
    });
  }
  pushDetailValue(items, t(stockPromotionGroupsMessageKeys.fields.memberCategory), promotion.memberCategoryId, locale);
  if (hasValue(promotion.minimumAmount)) {
    items.push({
      label: t(stockPromotionGroupsMessageKeys.fields.minimumAmount),
      value: formatNumber(promotion.minimumAmount, locale)
    });
  }
  pushDetailValue(items, t(stockPromotionGroupsMessageKeys.fields.minimumQuantity), promotion.minimumQuantity, locale);
  pushDetailValue(items, t(stockPromotionGroupsMessageKeys.fields.buyQuantity), promotion.buyQuantity, locale);
  return items;
}

function promotionBenefitItems(
  promotion: PromotionView,
  locale: LocaleCode,
  t: (key: string) => string
): PromotionDetailItem[] {
  const items: PromotionDetailItem[] = [];
  pushDetailValue(items, t(stockPromotionGroupsMessageKeys.fields.discountAmount), promotion.discountAmount, locale);
  if (hasValue(promotion.discountPercent)) {
    items.push({
      label: t(stockPromotionGroupsMessageKeys.fields.discountPercent),
      value: `${formatNumber(promotion.discountPercent, locale)}%`
    });
  }
  pushDetailValue(items, t(stockPromotionGroupsMessageKeys.fields.payQuantity), promotion.payQuantity, locale);
  pushDetailValue(items, t(stockPromotionGroupsMessageKeys.fields.packPrice), promotion.packPrice, locale);
  return items;
}

function promotionRuleItems(
  promotion: PromotionView,
  products: readonly StockPromotionProduct[],
  locale: LocaleCode,
  t: (key: string) => string
): PromotionDetailItem[] {
  const items: PromotionDetailItem[] = [];
  if (promotion.scope) {
    items.push({
      label: t(stockPromotionGroupsMessageKeys.columns.scope),
      value: translatedValue(stockPromotionGroupsMessageKeys.dynamicPrefixes.scope, promotion.scope, t)
    });
  }

  (["PRODUCT", "FAMILY", "SUBFAMILY"] as const).forEach((targetType) => {
    const labels = promotionTargetLabels(promotion, targetType, products);
    if (labels.length > 0) {
      items.push({
        label: t(targetLabelKey(targetType)),
        value: labels.join(", ")
      });
    }
  });

  if (promotion.buyXPayYMode) {
    items.push({
      label: t(stockPromotionGroupsMessageKeys.fields.buyXPayYMode),
      value: t(promotion.buyXPayYMode === "SAME_PRODUCT"
        ? "warehouseDocument.product"
        : "salesReport.column.products")
    });
  }
  if (hasValue(promotion.maximumDiscount)) {
    items.push({
      label: t(stockPromotionGroupsMessageKeys.fields.maximumDiscount),
      value: formatNumber(promotion.maximumDiscount, locale)
    });
  }
  return items;
}

function promotionTargetLabels(
  promotion: PromotionView,
  targetType: PromotionTargetType,
  products: readonly StockPromotionProduct[]
) {
  const labels = promotion.targets
    .filter((target) => target.type === targetType)
    .map((target) => {
      if (targetType === "PRODUCT") {
        const product = products.find((candidate) => candidate.productId === target.targetId);
        return product ? productDisplayName(product) : target.targetId;
      }
      if (targetType === "FAMILY") {
        return products.find((product) => product.familyId === target.targetId)?.familyName || target.targetId;
      }
      return products.find((product) => product.subfamilyId === target.targetId)?.subfamilyName || target.targetId;
    });
  return Array.from(new Set(labels));
}

function targetLabelKey(targetType: PromotionTargetType) {
  if (targetType === "PRODUCT") {
    return "salesReport.column.product";
  }
  return targetType === "FAMILY"
    ? stockPromotionGroupsMessageKeys.columns.family
    : stockPromotionGroupsMessageKeys.columns.subfamily;
}

function productDisplayName(product: StockPromotionProduct) {
  return [product.code, product.name].filter(Boolean).join(" - ") || product.productId;
}

function promotionValidity(promotion: PromotionView, locale: LocaleCode, t: (key: string) => string) {
  const start = formatDate(promotion.startDate, locale);
  const end = promotion.endDate
    ? formatDate(promotion.endDate, locale)
    : t(stockPromotionGroupsMessageKeys.fields.noEndDate);
  return `${start} - ${end}`;
}

function pushDetailValue(
  items: PromotionDetailItem[],
  label: string,
  value: string | number | null | undefined,
  locale: LocaleCode
) {
  if (hasValue(value)) {
    items.push({ label, value: formatNumber(value, locale) });
  }
}

function translatedValue(
  prefix: string,
  value: string | null | undefined,
  t: (key: string) => string
) {
  return value ? t(`${prefix}${value}`) : "-";
}

function formatDate(value: string, locale: LocaleCode) {
  const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!parts) {
    return value;
  }
  const date = new Date(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]));
  return new Intl.DateTimeFormat(localeTag(locale)).format(date);
}

function formatNumber(value: string | number | null | undefined, locale: LocaleCode) {
  const numeric = numericValue(value);
  if (numeric === null) {
    return hasValue(value) ? String(value) : "-";
  }
  return new Intl.NumberFormat(localeTag(locale), { maximumFractionDigits: 4 }).format(numeric);
}

function localeTag(locale: LocaleCode) {
  if (locale === "es") {
    return "es-ES";
  }
  return locale === "zh" ? "zh-CN" : "en-GB";
}

function numericValue(value: string | number | null | undefined) {
  if (!hasValue(value)) {
    return null;
  }
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

function hasValue(value: unknown): value is string | number {
  return value !== null && value !== undefined && String(value).trim() !== "";
}

function textValue(value: string | null | undefined) {
  return value?.trim() ?? "";
}

function displayText(value: string | null | undefined) {
  const text = textValue(value);
  return text === "-" ? "" : text;
}

function isStockPromotionNavigationKey(key: string): key is StockPromotionNavigationKey {
  return key === "ArrowDown" || key === "ArrowUp" || key === "Home" || key === "End";
}

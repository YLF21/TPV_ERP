import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import type { AppKind, LocaleCode } from "../types";
import { ArrowClockwise, CalendarBlank, CaretDown, ChartBar, Circle, MagnifyingGlass, Tag } from "@phosphor-icons/react";
import type { PromotionView } from "./PromotionForm";
import { formatPromotionDateRange, promotionConditionSentences, promotionTypeLabel } from "./promotionPresentation";
import { ErpSelect } from "./ErpSelect";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import type { UseTableLayoutPreferenceResult } from "./useTableLayoutPreference";
import { useTableSortPreference, type TableSort } from "./tableSorting";
import { sortProductTableRows } from "./productCodeSorting";
import "./StockPromotionGroups.css";

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
  salePrice?: number | string | null;
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
  salePrice: number | null;
};

export type StockPromotionGroup = {
  promotion: PromotionView;
  products: StockPromotionProduct[];
};

export type StockPromotionExportContext = {
  promotionId: string | null;
  promotionName: string;
  columns: { key: string; label: string }[];
  sort: TableSort | null;
};

export type StockPromotionGroupsProps = {
  locale: LocaleCode;
  promotions: readonly PromotionView[];
  productRows: readonly StockPromotionProductRow[];
  t: (key: string) => string;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  className?: string;
  defaultExpandedPromotionIds?: readonly string[];
  hideEmptyGroups?: boolean;
  loading?: boolean;
  statusMessage?: string;
  onRefresh?: () => void;
  onExportContextChange?: (context: StockPromotionExportContext) => void;
};

type ProductAccumulator = Omit<StockPromotionProduct, "stock"> & {
  totalStock: number | null;
  quantityStock: number;
  hasQuantityStock: boolean;
};

export type StockPromotionNavigationKey = "ArrowDown" | "ArrowUp" | "Home" | "End";

const stockPromotionProductColumns = [
  { key: "code", labelKey: stockPromotionGroupsMessageKeys.columns.code, defaultWidth: 90 },
  { key: "name", labelKey: stockPromotionGroupsMessageKeys.columns.name, defaultWidth: 210 },
  { key: "price", labelKey: "promotion.detail.price", defaultWidth: 80 },
  { key: "stock", labelKey: stockPromotionGroupsMessageKeys.columns.stock, defaultWidth: 76 },
  { key: "family", labelKey: stockPromotionGroupsMessageKeys.columns.family, defaultWidth: 130 },
  { key: "subfamily", labelKey: stockPromotionGroupsMessageKeys.columns.subfamily, defaultWidth: 130 }
] as const;

type StockPromotionProductColumnKey = typeof stockPromotionProductColumns[number]["key"];

const stockPromotionProductColumnByKey = new Map(
  stockPromotionProductColumns.map((column) => [column.key, column] as const)
);

/**
 * StockScreen integration contract:
 * - pass the raw `/promotions` result in `promotions` and either ProductView[] or
 *   StockInventoryRow[] in `productRows`;
 * - pass StockScreen's current `locale`, `t`, `app`, username, and access token;
 * - add the three `stock.promotions.groups.*` keys above to each message catalog;
 * - export this component from app-common's index when the screen is wired.
 */
export function StockPromotionGroups({
  locale,
  promotions,
  productRows,
  t,
  app = "venta",
  username = "",
  accessToken,
  className = "",
  defaultExpandedPromotionIds = [],
  hideEmptyGroups = false,
  loading = false,
  statusMessage = "",
  onRefresh,
  onExportContextChange
}: StockPromotionGroupsProps) {
  const productTableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken,
    tableKey: "stock.promotions.products",
    definitions: stockPromotionProductColumns
  });
  const productTableSort = useTableSortPreference({
    app,
    username,
    tableKey: "stock.promotions.products",
    columns: stockPromotionProductColumns.map((column) => column.key),
    defaultSort: app === "venta" ? { column: "code", direction: "asc" } : null,
    persistent: Boolean(username)
  });
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const groups = useMemo(
    () => buildStockPromotionGroups(promotions, productRows, loading || Boolean(statusMessage) || !hideEmptyGroups),
    [promotions, productRows, loading, statusMessage, hideEmptyGroups]
  );
  const filteredGroups = useMemo(() => {
    const search = query.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase(locale).trim();
    const matches = groups.filter(({ promotion }) => {
      const isExpired = isStockPromotionExpired(promotion);
      if (statusFilter === "EXPIRED" ? !isExpired
        : statusFilter !== "ALL" && (isExpired || promotion.status !== statusFilter)) return false;
      if (!search) return true;
      return [promotion.name, t("promotion.status." + promotion.status),
        t("promotion.type." + promotion.type)]
        .some((value) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "")
          .toLocaleLowerCase(locale).includes(search));
    });
    return [
      ...matches.filter(({ promotion }) => !isStockPromotionExpired(promotion)),
      ...matches.filter(({ promotion }) => isStockPromotionExpired(promotion))
    ];
  }, [groups, locale, query, statusFilter, t]);
  const groupIdsKey = filteredGroups.map((group) => group.promotion.id).join("\u0000");
  const [activePromotionId, setActivePromotionId] = useState(() =>
    defaultExpandedPromotionIds.find((id) => filteredGroups.some((group) => group.promotion.id === id))
      ?? filteredGroups[0]?.promotion.id ?? ""
  );
  const rowRefs = useRef(new Map<string, HTMLButtonElement>());
  const componentId = useId();
  const selectedGroup = filteredGroups.find((group) => group.promotion.id === activePromotionId)
    ?? filteredGroups[0];
  const selectedPromotion = selectedGroup?.promotion;
  const expired = selectedPromotion ? isStockPromotionExpired(selectedPromotion) : false;
  const number = new Intl.NumberFormat(localeTag(locale), { maximumFractionDigits: 0 });
  const conditions = selectedPromotion ? stockPromotionConditionSentences(selectedPromotion, t, locale) : [];
  const exportColumns = visibleTableColumns(productTableLayout.layout).map(column => ({
    key: column.key === "price" ? "salePrice" : column.key,
    label: t(stockPromotionProductColumnByKey.get(column.key)?.labelKey ?? column.key)
  }));
  const exportColumnsKey = JSON.stringify(exportColumns);

  useEffect(() => {
    onExportContextChange?.({
      promotionId: selectedPromotion?.id ?? null,
      promotionName: selectedPromotion?.name ?? "",
      columns: exportColumns,
      sort: productTableSort.sort ? {
        ...productTableSort.sort,
        column: productTableSort.sort.column === "price" ? "salePrice" : productTableSort.sort.column
      } : null
    });
  }, [selectedPromotion?.id, selectedPromotion?.name, exportColumnsKey,
    productTableSort.sort?.column, productTableSort.sort?.direction, onExportContextChange]);

  useEffect(() => {
    const activeIds = new Set(filteredGroups.map((group) => group.promotion.id));
    setActivePromotionId((current) =>
      activeIds.has(current) ? current : filteredGroups[0]?.promotion.id ?? "");
  }, [groupIdsKey]);

  function focusGroup(index: number) {
    const group = filteredGroups[index];
    if (!group) return;
    setActivePromotionId(group.promotion.id);
    rowRefs.current.get(group.promotion.id)?.focus();
  }

  function handleRowKeyDown(event: KeyboardEvent<HTMLButtonElement>, groupIndex: number) {
    if (!isStockPromotionNavigationKey(event.key)) return;
    event.preventDefault();
    focusGroup(stockPromotionNavigationIndex(groupIndex, event.key, filteredGroups.length));
  }

  const rootClassName = ["stock-promotion-groups", className].filter(Boolean).join(" ");

  return (
    <section className={rootClassName} aria-label={t(stockPromotionGroupsMessageKeys.tableLabel)}>
      <section className="stock-promotion-list-panel" aria-label={t(stockPromotionGroupsMessageKeys.tableLabel)}>
        <div className="stock-promotion-list-filters">
          <label className="stock-promotion-search">
            <input type="search" aria-label={t("promotion.list.search")}
              placeholder={t("promotion.list.search")} value={query}
              onChange={(event) => setQuery(event.target.value)} />
            <MagnifyingGlass size={16} aria-hidden="true" />
          </label>
          <div className="stock-promotion-status-filter">
            <ErpSelect value={statusFilter} aria-label={t("promotion.column.status")}
              options={[
                { value: "ALL", label: t("promotion.filter.all") },
                ...(["ACTIVE", "DRAFT", "INACTIVE"] as const)
                  .map((value) => ({ value, label: t("promotion.status." + value) })),
                { value: "EXPIRED", label: t("promotion.list.expired") }
              ]}
              onChange={setStatusFilter}
            />
            <CaretDown size={13} aria-hidden="true" />
          </div>
        </div>
        {filteredGroups.length === 0 ? (
          <p className="stock-promotion-empty" role={statusMessage ? "alert" : "status"}>
            {statusMessage || (loading ? t("common.loading") : t("promotion.list.empty"))}
          </p>
        ) : (
          <div className="stock-promotion-list-scroll" role="list">
            {filteredGroups.map((group, groupIndex) => {
              const promotion = group.promotion;
              const isExpired = isStockPromotionExpired(promotion);
              const selected = selectedGroup?.promotion.id === promotion.id;
              return (
                <div key={promotion.id} role="listitem">
                  <button type="button"
                    ref={(node) => {
                      if (node) rowRefs.current.set(promotion.id, node);
                      else rowRefs.current.delete(promotion.id);
                    }}
                    className={"stock-promotion-list-row " + promotion.status.toLowerCase()
                      + (isExpired ? " expired" : "") + (selected ? " selected" : "")}
                    aria-pressed={selected} aria-controls={componentId + "-detail"}
                    tabIndex={selected ? 0 : -1}
                    onFocus={() => setActivePromotionId(promotion.id)}
                    onClick={() => setActivePromotionId(promotion.id)}
                    onKeyDown={(event) => handleRowKeyDown(event, groupIndex)}
                  >
                    <Circle size={10} weight="fill" className="stock-promotion-indicator" aria-hidden="true" />
                    <span className="stock-promotion-list-copy">
                      <strong>{promotion.name}</strong>
                      <small>{promotionValidity(promotion, locale, t)}</small>
                    </span>
                    <span className="stock-promotion-list-status">
                      {isExpired ? t("promotion.list.expired") : t("promotion.status." + promotion.status)}
                    </span>
                  </button>
                </div>
              );
            })}
          </div>
        )}
        <div className="stock-promotion-list-footer">
          <span>{t("promotion.list.heading")}: {filteredGroups.length}</span>
          {onRefresh && <button type="button" onClick={onRefresh} disabled={loading}
            aria-label={t("promotion.action.refresh")} title={t("promotion.action.refresh")}>
            <ArrowClockwise size={17} aria-hidden="true" />
          </button>}
        </div>
      </section>

      <section id={componentId + "-detail"} className="stock-promotion-detail-panel"
        aria-label={selectedPromotion?.name ?? t(stockPromotionGroupsMessageKeys.tableLabel)}>
        {selectedGroup && selectedPromotion ? (
          <>
            <section className="stock-promotion-overview">
              <header className="stock-promotion-detail-heading">
                <h2>{selectedPromotion.name}</h2>
                <span className={"stock-promotion-state " + selectedPromotion.status.toLowerCase()
                  + (expired ? " expired" : "")}>
                  <Circle size={10} weight="fill" aria-hidden="true" />
                  {expired ? t("promotion.list.expired") : t("promotion.status." + selectedPromotion.status)}
                </span>
              </header>
              <dl className="stock-promotion-facts">
                <div>
                  <dt><CalendarBlank size={16} aria-hidden="true" />{t("stock.column.promotionValidity")}</dt>
                  <dd>{promotionValidity(selectedPromotion, locale, t)}</dd>
                </div>
                <div>
                  <dt><Tag size={16} aria-hidden="true" />{t("promotion.field.type")}</dt>
                  <dd>{promotionTypeLabel(selectedPromotion, t, locale)}</dd>
                </div>
                <div>
                  <dt><ChartBar size={16} aria-hidden="true" />{t("promotion.detail.usageCount")}</dt>
                  <dd>{number.format(selectedPromotion.usageCount ?? 0)}</dd>
                </div>
              </dl>
              <section className="stock-promotion-conditions">
                <h3>{t("promotion.detail.conditions")}</h3>
                <ol>
                  {conditions.map((text, index) => (
                    <li key={index}><span aria-hidden="true">{index + 1}</span><p>{text}</p></li>
                  ))}
                </ol>
              </section>
            </section>
            <section className="stock-promotion-products">
              <h3>{t("promotion.detail.products")} ({number.format(selectedGroup.products.length)})</h3>
              {loading ? <p className="stock-promotion-empty" role="status">{t("common.loading")}</p>
                : statusMessage ? <p className="stock-promotion-empty" role="alert">{statusMessage}</p>
                  : <PromotionProductsTable products={selectedGroup.products} locale={locale} t={t}
                    tableLayout={productTableLayout} sort={productTableSort.sort}
                    onSort={productTableSort.toggleSort} />}
            </section>
          </>
        ) : null}
      </section>
    </section>
  );
}

function isStockPromotionExpired(promotion: PromotionView) {
  if (!promotion.endDate) return false;
  const now = new Date();
  const today = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, "0"),
    String(now.getDate()).padStart(2, "0")].join("-");
  return promotion.endDate < today;
}

function stockPromotionConditionSentences(
  promotion: PromotionView, t: (key: string) => string, locale: LocaleCode
) {
  const sentences = promotionConditionSentences(promotion, t, locale);
  if (promotion.type === "BUY_X_PAY_Y" || promotion.type === "SECOND_UNIT_PERCENT"
    || promotion.type === "FIXED_PACK_PRICE") {
    const minimums: string[] = [];
    if (hasValue(promotion.minimumAmount)) {
      minimums.push(t("promotion.rule.minimumAmount").replace(
        "{value}", new Intl.NumberFormat(localeTag(locale), { style: "currency", currency: "EUR" })
          .format(Number(promotion.minimumAmount))
      ));
    }
    if (hasValue(promotion.minimumQuantity)
        && Number(promotion.minimumQuantity) !== Number(promotion.buyQuantity)) {
      minimums.push(t("promotion.rule.minimumQuantity").replace(
        "{value}", formatNumber(promotion.minimumQuantity, locale)
      ));
    }
    sentences.unshift(...minimums);
  }
  if (promotion.memberCategoryId) {
    sentences.splice(Math.max(0, sentences.length - 2), 0,
      t("promotion.field.memberCategoryId") + ": " + promotion.memberCategoryId);
  }
  return sentences;
}
export function buildStockPromotionGroups(
  promotions: readonly PromotionView[],
  productRows: readonly StockPromotionProductRow[],
  includeEmptyGroups = true
): StockPromotionGroup[] {
  const products = normalizeStockPromotionProducts(productRows);
  const seenPromotionIds = new Set<string>();

  return promotions.flatMap((promotion) => {
    if (seenPromotionIds.has(promotion.id)) {
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
        salePrice: numericValue(row.salePrice),
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
    current.salePrice = current.salePrice ?? numericValue(row.salePrice);
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
    salePrice: product.salePrice,
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
  t,
  tableLayout,
  sort,
  onSort
}: {
  products: readonly StockPromotionProduct[];
  locale: LocaleCode;
  t: (key: string) => string;
  tableLayout: UseTableLayoutPreferenceResult<StockPromotionProductColumnKey>;
  sort: TableSort<StockPromotionProductColumnKey> | null;
  onSort: (column: StockPromotionProductColumnKey) => void;
}) {
  if (products.length === 0) {
    return <p className="promotion-empty">{t(stockPromotionGroupsMessageKeys.noProducts)}</p>;
  }

  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const sortedProducts = sortProductTableRows(products, sort, (product, column) => {
    if (column === "code") return product.code;
    if (column === "name") return product.name;
    if (column === "family") return product.familyName || product.familyId;
    if (column === "subfamily") return product.subfamilyName || product.subfamilyId;
    if (column === "price") return product.salePrice;
    return product.stock;
  }, locale);

  return (
    <div className="report-table-scroll">
      <table className="report-table">
        <colgroup>
          {visibleColumns.map((column) => <col key={column.key} style={{ width: column.width }} />)}
        </colgroup>
        <thead>
          <tr>
            {visibleColumns.map((column) => {
              const definition = stockPromotionProductColumnByKey.get(column.key);
              const label = t(definition?.labelKey ?? column.key);
              return (
                <TableLayoutHeaderCell
                  column={column}
                  key={column.key}
                  sortDirection={sort?.column === column.key ? sort.direction : null}
                  sortLabel={`${t("party.sortBy")} ${label}`}
                  onSort={onSort}
                  resizeLabel={`${t("stock.columns.resize")} ${label}`}
                  onReorder={tableLayout.reorderColumns}
                  onMove={tableLayout.moveColumn}
                  onResize={tableLayout.resizeColumn}
                >
                  {label}
                </TableLayoutHeaderCell>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sortedProducts.map((product) => {
            const cellsByKey: Record<StockPromotionProductColumnKey, ReactNode> = {
              code: (
                <td key="code" data-column-key="code" style={{ textAlign: "left" }}>
                  <strong>{product.code || "-"}</strong>
                </td>
              ),
              name: (
                <td key="name" data-column-key="name" style={{ textAlign: "left" }}>
                  <span className="product-name-text">{product.name}</span>
                </td>
              ),
              family: (
                <td key="family" data-column-key="family" style={{ textAlign: "left" }}>
                  {product.familyName || product.familyId || "-"}
                </td>
              ),
              subfamily: (
                <td key="subfamily" data-column-key="subfamily" style={{ textAlign: "left" }}>
                  {product.subfamilyName || product.subfamilyId || "-"}
                </td>
              ),
              price: (
                <td key="price" data-column-key="price" style={{ textAlign: "right" }}>
                  {product.salePrice === null ? "-" : new Intl.NumberFormat(localeTag(locale), {
                    style: "currency", currency: "EUR"
                  }).format(product.salePrice)}
                </td>
              ),
              stock: (
                <td key="stock" data-column-key="stock" style={{ textAlign: "right" }}>
                  {formatNumber(product.stock, locale)}
                </td>
              )
            };
            return (
              <tr key={product.productId}>
                {visibleColumns.map((column) => cellsByKey[column.key])}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function promotionValidity(promotion: PromotionView, locale: LocaleCode, t: (key: string) => string) {
  return formatPromotionDateRange(promotion, locale, t(stockPromotionGroupsMessageKeys.fields.noEndDate));
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

import { localeTag } from "../money";
import type { LocaleCode } from "../types";

export type SaasSalesHistoryView = "detail" | "comparison";
export type SaasSalesHistoryStore = { id: string; code: string; name: string };
export type SaasSalesHistoryItem = {
  documentId: string;
  documentType: string;
  documentNumber?: string | null;
  status: string;
  businessDate: string;
  occurredAt: string;
  storeId: string;
  storeCode: string;
  storeName: string;
  installationId: string;
  linePosition: number;
  productCode: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  discountPercent: string;
  lineTotal: string;
  currency: string;
  customerName?: string | null;
  userName?: string | null;
  warehouseName?: string | null;
  countsAsSale: boolean;
};
export type SaasSalesHistoryTotals = {
  currency: string;
  quantitySold: string;
  quantityReturned: string;
  netQuantity: string;
  netAmount: string;
};
export type SaasSalesHistoryComparison = SaasSalesHistoryTotals & {
  storeId: string;
  storeCode: string;
  storeName: string;
};
export type SaasSalesHistoryResponse = {
  companyId: string;
  productCode: string;
  coverage: "RECEIVED_IN_SAAS";
  items: SaasSalesHistoryItem[];
  stores: SaasSalesHistoryStore[];
  totals: SaasSalesHistoryTotals[];
  comparison: SaasSalesHistoryComparison[];
  nextCursor?: string | null;
  hasMore: boolean;
  incompleteDocuments: number;
  receivedAt?: string | null;
};
export type SaasSalesHistoryQuery = {
  from: string;
  to: string;
  status: string;
  storeIds: string[];
  sortBy: string;
  sortDirection: "asc" | "desc";
};

export function saasSalesHistoryBasePath(productId: string) {
  return `/stock/products/${encodeURIComponent(productId)}/sales-history/saas`;
}

export function saasSalesHistoryPath(productId: string, query: SaasSalesHistoryQuery, cursor?: string | null) {
  const params = new URLSearchParams({
    sortBy: query.sortBy,
    sortDirection: query.sortDirection, size: "200",
  });
  if (query.from) params.set("from", query.from);
  if (query.to) params.set("to", query.to);
  if (query.status) params.set("status", query.status);
  if (query.storeIds.length) params.set("storeIds", query.storeIds.join(","));
  if (cursor) params.set("cursor", cursor);
  return `${saasSalesHistoryBasePath(productId)}?${params}`;
}

export function compareSaasHistoryDecimals(left: string, right: string) {
  const decimal = (value: string) => /^(-?)(\d+)(?:\.(\d+))?$/.exec(value);
  const a = decimal(left);
  const b = decimal(right);
  if (!a || !b) return 0;
  const scale = Math.max(a[3]?.length ?? 0, b[3]?.length ?? 0);
  const integer = (match: RegExpExecArray) => BigInt(`${match[1]}${match[2]}${(match[3] ?? "").padEnd(scale, "0")}`);
  const first = integer(a);
  const second = integer(b);
  return first < second ? -1 : first > second ? 1 : 0;
}

/** Keep decimal strings exact: grouped integers use BigInt and fractions never pass through Number. */
export function formatSaasHistoryDecimal(value: string | null | undefined, locale: LocaleCode, minimumFractionDigits = 0) {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value ?? "");
  if (!match) return "—";
  const integer = BigInt(match[2]);
  const signed = match[1] ? (integer === 0n ? -0 : -integer) : integer;
  const fraction = (match[3] ?? "").replace(/0+$/, "").padEnd(minimumFractionDigits, "0");
  return new Intl.NumberFormat(localeTag(locale), {
    minimumFractionDigits: 1, maximumFractionDigits: 1,
  }).formatToParts(signed).map((part) => {
    if (part.type === "fraction") return fraction;
    if (part.type === "decimal" && !fraction) return "";
    return part.value;
  }).join("");
}

import { apiRequest } from "../../../packages/app-common/src/api/client";

export type Family = {
  id: string;
  name: string;
  familyCode: string;
  defaultFamily: boolean;
};

export type Subfamily = {
  id: string;
  familyId: string;
  name: string;
  subfamilyCode: string;
  subfamilySuffix: string;
  familySuffix?: string;
};

export type FamilyNode = Family & {
  subfamilies: Subfamily[];
};

export type FamilyProduct = {
  id: string;
  code: string;
  barcode: string;
  name: string;
  salePrice: number | null;
  active: boolean;
  imageId: string;
  familyId: string;
  subfamilyId: string;
  version: number;
};

export type FamilyProductPage = {
  items: FamilyProduct[];
  page: number;
  size: number;
  total?: number;
  totalPages?: number;
  nextCursor: string;
  hasMore: boolean;
};

export type FamilyProductSortBy = "code" | "name" | "salePrice";
export type FamilyProductSortDirection = "asc" | "desc";

export type FamilyHierarchySearch = {
  kind: "FAMILY" | "SUBFAMILY";
  id: string;
  familyId: string | null;
  subfamilyId: string | null;
  code: string;
  name: string;
  familyCode: string;
  suffix: string;
  defaultFamily: boolean;
};

export type FamilyHierarchySearchPage = {
  items: FamilyHierarchySearch[];
  nextCursor: string;
  hasMore: boolean;
};

export type DeleteImpact = {
  products: number;
  promotions: number;
  rules: number;
  blocked: boolean;
  dependencies: Array<string | DeleteDependency>;
};

export type DeleteDependency = {
  sourceType?: string;
  targetType?: string;
  id?: string;
  targetId?: string;
  name?: string;
};

type RawRecord = Record<string, unknown>;

function text(value: unknown, fallback = "") {
  return typeof value === "string" ? value : fallback;
}

function numberValue(value: unknown, fallback: number) {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function booleanValue(value: unknown, fallback = false) {
  return typeof value === "boolean" ? value : fallback;
}

function arrayFrom(value: unknown, key: string): unknown[] {
  if (Array.isArray(value)) return value;
  if (
    value &&
    typeof value === "object" &&
    Array.isArray((value as RawRecord)[key])
  )
    return (value as RawRecord)[key] as unknown[];
  return [];
}

export function normalizeFamily(value: unknown): Family {
  const row = (value && typeof value === "object" ? value : {}) as RawRecord;
  const name = text(row.name ?? row.nombre);
  const defaultFamily = Boolean(
    row.defaultFamily ??
    row.predeterminada ??
    row.default ??
    name === "GENERAL",
  );
  const rawCode = String(row.familyCode ?? row.familyId ?? row.code ?? "");
  return {
    id: text(row.id ?? row.uuid),
    name,
    familyCode: defaultFamily
      ? "000"
      : rawCode.replace(/\D/g, "").padStart(3, "0").slice(-3),
    defaultFamily,
  };
}

export function normalizeSubfamily(
  value: unknown,
  familyId: string,
  familyCode = "",
): Subfamily {
  const row = (value && typeof value === "object" ? value : {}) as RawRecord;
  const name = text(row.name ?? row.nombre);
  const rawCode = String(
    row.subfamilyCode ?? row.subfamilyId ?? row.code ?? "",
  );
  const rawDigits = rawCode.replace(/\D/g, "");
  const fullCode = /^\d{6}$/.test(rawDigits) ? rawDigits : "";
  const prefix =
    text(row.familyCode ?? familyCode)
      .replace(/\D/g, "")
      .padStart(3, "0")
      .slice(-3) ||
    fullCode.slice(0, 3) ||
    "000";
  const inferredSuffix = fullCode ? fullCode.slice(3) : rawDigits;
  const suffix = text(
    row.subfamilySuffix ?? row.familySuffix ?? row.suffix,
    inferredSuffix,
  )
    .replace(/\D/g, "")
    .padStart(3, "0")
    .slice(-3);
  const subfamilyCode = fullCode || `${prefix}${suffix}`;
  return {
    id: text(row.id ?? row.uuid),
    familyId: text(row.familyId ?? row.familiaId ?? row.parentId, familyId),
    name,
    subfamilyCode,
    subfamilySuffix: suffix,
    familySuffix: text(
      row.familySuffix ?? row.subfamilySuffix ?? row.suffix,
      suffix,
    ),
  };
}

export async function loadFamilies(token?: string, request = apiRequest) {
  const value = await request<unknown>("/families", { token });
  return arrayFrom(value, "families").map((item) => normalizeFamily(item));
}

export async function suggestNextFamilyCode(
  token?: string,
  request = apiRequest,
) {
  const value = await request<unknown>("/families/next-code", { token });
  const code =
    value &&
    typeof value === "object" &&
    (typeof (value as RawRecord).familyCode === "string" ||
      typeof (value as RawRecord).familyCode === "number")
      ? String((value as RawRecord).familyCode)
      : "";
  if (!/^[0-9]{3}$/.test(code) || Number(code) < 1 || Number(code) > 999)
    throw new Error("invalid_family_code_suggestion");
  return code;
}

export async function loadSubfamilies(
  familyId: string,
  token?: string,
  request = apiRequest,
  familyCode = "",
) {
  const value = await request<unknown>(`/families/${familyId}/subfamilies`, {
    token,
  });
  return arrayFrom(value, "subfamilies").map((item) =>
    normalizeSubfamily(item, familyId, familyCode),
  );
}

export function familyProductsPath(
  familyId: string,
  cursor = "",
  limit = 25,
  sortBy: FamilyProductSortBy = "name",
  sortDirection: FamilyProductSortDirection = "asc",
) {
  const query = new URLSearchParams({
    familyId,
    limit: String(limit),
    sortBy,
    sortDirection,
  });
  if (cursor) query.set("cursor", cursor);
  return `/families/products?${query.toString()}`;
}

export function subfamilyProductsPath(
  subfamilyId: string,
  cursor = "",
  limit = 25,
  sortBy: FamilyProductSortBy = "name",
  sortDirection: FamilyProductSortDirection = "asc",
) {
  const query = new URLSearchParams({
    subfamilyId,
    limit: String(limit),
    sortBy,
    sortDirection,
  });
  if (cursor) query.set("cursor", cursor);
  return `/families/products?${query.toString()}`;
}

export function familyHierarchySearchPath(query: string, limit = 50, cursor = "") {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return `/families/search?${params.toString()}`;
}

export async function searchFamilyHierarchy(
  query: string,
  token?: string,
  request = apiRequest,
  limit = 50,
  cursor = "",
): Promise<FamilyHierarchySearchPage> {
  const value = await request<unknown>(familyHierarchySearchPath(query, limit, cursor), { token });
  const raw = (value && typeof value === "object" ? value : {}) as RawRecord;
  const rows = Array.isArray(raw.items) ? raw.items : [];
  return {
    items: rows.map((item) => {
      const row = (item && typeof item === "object" ? item : {}) as RawRecord;
      return {
        kind: row.kind === "SUBFAMILY" ? "SUBFAMILY" : "FAMILY",
        id: text(row.id),
        familyId: row.familyId == null ? null : text(row.familyId),
        subfamilyId: row.subfamilyId == null ? null : text(row.subfamilyId),
        code: text(row.code),
        name: text(row.name),
        familyCode: text(row.familyCode),
        suffix: text(row.suffix),
        defaultFamily: booleanValue(row.defaultFamily),
      };
    }),
    nextCursor: text(raw.nextCursor),
    hasMore: Boolean(raw.hasMore),
  };
}

function normalizeProduct(value: unknown): FamilyProduct {
  const row = (value && typeof value === "object" ? value : {}) as RawRecord;
  return {
    id: text(row.id ?? row.productId ?? row.uuid),
    code: text(row.code ?? row.codigo),
    barcode: text(
      row.barcode ??
        row.codigoBarras ??
        row.codigo_barras ??
        row.primaryBarcode ??
        row.ean,
    ),
    name: text(row.name ?? row.nombre),
    salePrice: Number.isFinite(Number(row.salePrice ?? row.precioVenta))
      ? Number(row.salePrice ?? row.precioVenta)
      : null,
    active: booleanValue(row.active ?? row.activo, true),
    imageId: text(row.imageId ?? row.imagenId),
    familyId: text(row.familyId ?? row.familiaId),
    subfamilyId: text(row.subfamilyId ?? row.subfamiliaId),
    version: numberValue(row.version, 0),
  };
}

function normalizeProductPage(
  value: unknown,
  page: number,
  size: number,
): FamilyProductPage {
  const raw = (value && typeof value === "object" ? value : {}) as RawRecord;
  const rawItems = Array.isArray(value)
    ? value
    : Array.isArray(raw.items)
      ? raw.items
      : Array.isArray(raw.content)
        ? raw.content
        : [];
  const totalRaw = raw.total ?? raw.totalElements ?? raw.count;
  const total =
    totalRaw === undefined ? undefined : numberValue(totalRaw, rawItems.length);
  const totalPages =
    total === undefined
      ? undefined
      : numberValue(raw.totalPages, Math.max(1, Math.ceil(total / size)));
  return {
    items: rawItems.map(normalizeProduct),
    page: numberValue(raw.page ?? raw.number, page),
    size: numberValue(raw.size, size),
    total,
    totalPages,
    nextCursor: text(raw.nextCursor ?? raw.next),
    hasMore: Boolean(raw.hasMore ?? raw.nextCursor),
  };
}

export async function loadFamilyProducts(
  node: { kind: "family" | "subfamily"; id: string },
  token?: string,
  request = apiRequest,
  cursor = "",
  page = 0,
  size = 25,
  sortBy: FamilyProductSortBy = "name",
  sortDirection: FamilyProductSortDirection = "asc",
) {
  const path =
    node.kind === "family"
      ? familyProductsPath(node.id, cursor, size, sortBy, sortDirection)
      : subfamilyProductsPath(
          node.id,
          cursor,
          size,
          sortBy,
          sortDirection,
        );
  const value = await request<unknown>(path, { token });
  return normalizeProductPage(value, page, size);
}

export type MoveProductsRequest = {
  items: Array<{ productId: string; expectedVersion: number }>;
  familyId?: string | null;
  subfamilyId?: string | null;
};

export function moveProducts(
  body: MoveProductsRequest,
  token?: string,
  request = apiRequest,
) {
  return request<void>("/products/classification/move", {
    method: "POST",
    token,
    body,
  });
}

export async function suggestNextSubfamilySuffix(
  familyId: string,
  token?: string,
  request = apiRequest,
) {
  const value = await request<unknown>(
    `/families/${familyId}/subfamilies/next-suffix`,
    { token },
  );
  const suffix =
    value &&
    typeof value === "object" &&
    (typeof (value as RawRecord).subfamilySuffix === "string" ||
      typeof (value as RawRecord).subfamilySuffix === "number")
      ? String((value as RawRecord).subfamilySuffix)
      : "";
  if (!/^[0-9]{3}$/.test(suffix) || Number(suffix) < 1 || Number(suffix) > 999)
    throw new Error("invalid_subfamily_suffix_suggestion");
  return suffix;
}

export function createFamily(
  name: string,
  familyCode: string,
  token?: string,
  request = apiRequest,
) {
  return request<unknown>("/families", {
    method: "POST",
    token,
    body: { name, familyCode },
  }).then(normalizeFamily);
}

export function updateFamily(
  id: string,
  name: string,
  token?: string,
  request = apiRequest,
) {
  return request<unknown>(`/families/${id}`, {
    method: "PUT",
    token,
    body: { name },
  }).then(normalizeFamily);
}

export function deleteFamily(
  id: string,
  confirmProductReassignment = false,
  token?: string,
  request = apiRequest,
) {
  const query = confirmProductReassignment
    ? "?confirmProductReassignment=true"
    : "";
  return request<void>(`/families/${id}${query}`, { method: "DELETE", token });
}

export function loadFamilyDeleteImpact(
  id: string,
  token?: string,
  request = apiRequest,
) {
  return request<RawRecord>(`/families/${id}/delete-impact`, { token });
}

export function createSubfamily(
  familyId: string,
  name: string,
  subfamilySuffix: string,
  token?: string,
  request = apiRequest,
) {
  return request<unknown>(`/families/${familyId}/subfamilies`, {
    method: "POST",
    token,
    body: { name, subfamilySuffix },
  }).then((value) => normalizeSubfamily(value, familyId));
}

export function updateSubfamily(
  id: string,
  name: string,
  token?: string,
  request = apiRequest,
) {
  return request<unknown>(`/families/subfamilies/${id}`, {
    method: "PUT",
    token,
    body: { name },
  }).then((value) => normalizeSubfamily(value, ""));
}

export function deleteSubfamily(
  id: string,
  confirmProductCleanup = false,
  token?: string,
  request = apiRequest,
) {
  const query = confirmProductCleanup ? "?confirmProductCleanup=true" : "";
  return request<void>(`/families/subfamilies/${id}${query}`, {
    method: "DELETE",
    token,
  });
}

export function loadSubfamilyDeleteImpact(
  id: string,
  token?: string,
  request = apiRequest,
) {
  return request<RawRecord>(`/families/subfamilies/${id}/delete-impact`, {
    token,
  });
}

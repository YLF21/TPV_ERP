import { apiRequest } from "../api/client";

export type FamilyCatalogView = {
  id: string;
  familyId?: string | null;
  familyCode?: string | number | null;
  name?: string | null;
  defaultFamily?: boolean | null;
};

export type SubfamilyCatalogView = {
  id: string;
  familyId?: string | null;
  subfamilyId?: string | null;
  subfamilySuffix?: string | number | null;
  subfamilyCode?: string | number | null;
  name?: string | null;
};

export type FamilyCatalogResolution = {
  family?: FamilyCatalogView | null;
  subfamily?: SubfamilyCatalogView | null;
};

export type FamilyHierarchySearchView = {
  kind: "FAMILY" | "SUBFAMILY";
  id: string;
  familyId?: string | null;
  subfamilyId?: string | null;
  code?: string | null;
  name?: string | null;
  familyCode?: string | null;
  suffix?: string | null;
  defaultFamily?: boolean | null;
};

export const familyCatalogPath = "/families";

export function familySubfamiliesPath(familyId: string) {
  return `${familyCatalogPath}/${encodeURIComponent(familyId)}/subfamilies`;
}

export function familyResolvePath(code: string) {
  return `${familyCatalogPath}/resolve?code=${encodeURIComponent(code)}`;
}

export function loadFamilyCatalog(token?: string) {
  return apiRequest<FamilyCatalogView[]>(familyCatalogPath, { token });
}

export function loadFamilySubfamilies(familyId: string, token?: string) {
  return apiRequest<SubfamilyCatalogView[]>(familySubfamiliesPath(familyId), {
    token,
  });
}

export function resolveFamilyBusinessCode(code: string, token?: string) {
  return apiRequest<FamilyCatalogResolution>(familyResolvePath(code), {
    token,
  });
}

export function familyHierarchySearchPath(query: string, limit = 50, cursor = "") {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  return `${familyCatalogPath}/search?${params.toString()}`;
}

export async function searchFamilyHierarchy(
  query: string,
  token?: string,
  limit = 50,
  cursor = "",
  request = apiRequest,
) {
  const value = await request<unknown>(familyHierarchySearchPath(query, limit, cursor), { token });
  const raw = (value && typeof value === "object" ? value : {}) as Record<string, unknown>;
  const items = Array.isArray(raw.items) ? raw.items : [];
  return {
    items: items.map((item) => {
      const row = (item && typeof item === "object" ? item : {}) as Record<string, unknown>;
      return {
        kind: row.kind === "SUBFAMILY" ? "SUBFAMILY" as const : "FAMILY" as const,
        id: String(row.id ?? ""),
        familyId: row.familyId == null ? null : String(row.familyId),
        subfamilyId: row.subfamilyId == null ? null : String(row.subfamilyId),
        code: row.code == null ? null : String(row.code),
        name: row.name == null ? null : String(row.name),
        familyCode: row.familyCode == null ? null : String(row.familyCode),
        suffix: row.suffix == null ? null : String(row.suffix),
        defaultFamily: Boolean(row.defaultFamily),
      };
    }),
    nextCursor: typeof raw.nextCursor === "string" ? raw.nextCursor : "",
    hasMore: Boolean(raw.hasMore),
  };
}

export function familyBusinessCode(
  family?: FamilyCatalogView | null,
  subfamily?: SubfamilyCatalogView | null,
) {
  if (!family) {
    return "";
  }
  const familyCodeRaw = String(family.familyCode ?? "").trim();
  const familyCode = /^\d{1,3}$/.test(familyCodeRaw)
    ? familyCodeRaw.padStart(3, "0")
    : familyCodeRaw;
  if (!subfamily) {
    return /^\d{3}$/.test(familyCode) ? familyCode : "";
  }
  if (!/^\d{3}$/.test(familyCode)) {
    return "";
  }
  const rawSubfamilyCode = String(subfamily.subfamilyCode ?? "").trim();
  if (/^\d{6}$/.test(rawSubfamilyCode) && rawSubfamilyCode.startsWith(familyCode)) {
    return rawSubfamilyCode;
  }
  const rawSuffix = String(subfamily.subfamilySuffix ?? "").trim();
  const suffixSource = /^\d{1,3}$/.test(rawSuffix)
    ? rawSuffix
    : /^\d{1,3}$/.test(rawSubfamilyCode)
      ? rawSubfamilyCode
      : /^\d{6}$/.test(rawSubfamilyCode)
        ? rawSubfamilyCode.slice(-3)
        : "";
  const suffix = suffixSource.padStart(3, "0");
  return /^\d{3}$/.test(familyCode) && /^\d{3}$/.test(suffix)
    ? `${familyCode}${suffix}`
    : "";
}

export function sortFamilyCatalog<
  T extends {
    id?: string | null;
    name?: string | null;
    familyCode?: string | number | null;
    subfamilyCode?: string | number | null;
  },
>(rows: T[]) {
  return [...rows].sort(
    (left, right) =>
      String(left.subfamilyCode ?? left.familyCode ?? "").localeCompare(
        String(right.subfamilyCode ?? right.familyCode ?? ""),
        undefined,
        { numeric: true },
      ) ||
      String(left.name ?? "").localeCompare(String(right.name ?? ""), "es", {
        sensitivity: "base",
      }) ||
      String(left.id ?? "").localeCompare(String(right.id ?? "")),
  );
}

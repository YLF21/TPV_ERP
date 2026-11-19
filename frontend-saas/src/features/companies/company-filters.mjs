import { parseUtcDateOnly } from "../../lib/utc-date-time.mjs";
import { findSpanishProvince } from "../../shared/provinces/spanish-provinces.mjs";

export function emptyCompanyFilters() {
  return {
    query: "", province: "", taxpayerType: "", dateFrom: "", dateTo: "",
    contactName: "", contactPhone: "", contactEmail: "", owner: "", city: "", postalCode: "",
  };
}

function text(value) {
  return typeof value === "string"
    ? value.normalize("NFD").replace(/\p{M}/gu, "").trim().toLocaleLowerCase("es-ES").replace(/\s+/g, " ")
    : "";
}

function contains(value, query) {
  const expected = text(query);
  return !expected || text(value).includes(expected);
}

function phone(value) {
  return text(value).replace(/[\s()\-\u2010-\u2015]/gu, "");
}

/** Unknown historical values keep their exact spelling and never become catalogue aliases. */
export function companyProvinceFilterValue(value) {
  if (typeof value !== "string" || value === "") return "";
  return findSpanishProvince(value)?.code ?? `raw:${value}`;
}

/** Bounds match the UTC dates shown in the company creation column. */
export function companyDateRange(from, to) {
  const start = from ? parseUtcDateOnly(from) : null;
  const end = to ? parseUtcDateOnly(to) : null;
  if ((from && !start) || (to && !end) || (start && end && start > end)) return null;
  if (end) {
    end.setUTCDate(end.getUTCDate() + 1);
  }
  return { from: start?.getTime() ?? null, toExclusive: end?.getTime() ?? null };
}

export function matchesCompanyFilters(company, filters) {
  const interval = companyDateRange(filters.dateFrom, filters.dateTo);
  if (!interval) return false;
  if (interval.from !== null || interval.toExclusive !== null) {
    const createdAt = typeof company.createdAt === "string" ? Date.parse(company.createdAt) : NaN;
    if (!Number.isFinite(createdAt) || (interval.from !== null && createdAt < interval.from)
      || (interval.toExclusive !== null && createdAt >= interval.toExclusive)) return false;
  }
  if (!contains(company.companyName, filters.query) && !contains(company.taxId, filters.query)) return false;
  if (filters.province && companyProvinceFilterValue(company.companyAddress?.provincia) !== filters.province) return false;
  if (filters.taxpayerType && company.taxpayerType !== filters.taxpayerType) return false;
  if (!contains(company.contactName, filters.contactName) || !contains(company.contactEmail, filters.contactEmail)
    || !contains(company.companyAddress?.ciudad, filters.city) || !contains(company.companyAddress?.codigoPostal, filters.postalCode)) return false;
  const expectedPhone = phone(filters.contactPhone);
  if (expectedPhone && !phone(company.contactPhone).includes(expectedPhone)) return false;
  if (text(filters.owner) && !(company.owners ?? []).some(owner =>
    contains(owner.name, filters.owner) || contains(owner.taxId, filters.owner))) return false;
  return true;
}

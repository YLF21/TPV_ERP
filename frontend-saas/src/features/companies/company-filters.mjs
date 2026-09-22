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

function localDay(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(`${value}T00:00:00`);
  return Number.isFinite(date.getTime()) && date.getFullYear() === year
    && date.getMonth() + 1 === month && date.getDate() === day ? date : null;
}

/** Millisecond bounds use browser-local calendar days, including 23/25-hour DST days. */
export function companyDateRange(from, to) {
  const start = from ? localDay(from) : null;
  const end = to ? localDay(to) : null;
  if ((from && !start) || (to && !end) || (start && end && start > end)) return null;
  if (end) {
    end.setDate(end.getDate() + 1);
    end.setHours(0, 0, 0, 0);
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

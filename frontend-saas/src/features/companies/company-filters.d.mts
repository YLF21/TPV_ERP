import type { CompanySummary } from "../../lib/types";

export type CompanyFilters = {
  query: string;
  province: string;
  taxpayerType: string;
  dateFrom: string;
  dateTo: string;
  contactName: string;
  contactPhone: string;
  contactEmail: string;
  owner: string;
  city: string;
  postalCode: string;
};

export type CompanyDateRange = { from: number | null; toExclusive: number | null };
export function emptyCompanyFilters(): CompanyFilters;
export function companyProvinceFilterValue(value: unknown): string;
export function companyDateRange(from: string, to: string): CompanyDateRange | null;
export function matchesCompanyFilters(company: Partial<CompanySummary>, filters: CompanyFilters): boolean;

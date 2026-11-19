import type { LicenseStatus } from "../../lib/types";
import type { PeriodMetrics, StatisticsCurrency, StatisticsPeriod } from "../reports/statistics-types";

export type SummaryCount = {
  key: string;
  count: number;
};

export type AdminSummary = {
  asOf: string;
  period?: StatisticsPeriod;
  newCompanies?: number;
  newStores?: number;
  newLicenses?: number;
  periodActivity?: PeriodMetrics;
  catalogue?: { stores: number; coveredStores: number; products: number | null; activeProducts: number | null; oldestCapturedAt?: string | null; latestCapturedAt?: string | null };
  daily?: { date: string; syncEvents: number; currencies: StatisticsCurrency[] }[];
  companies: number;
  stores: { total: number; active: number };
  licenses: { total: number; valid: number; expired: number; blocked: number };
  installations: number;
  events: {
    total: number;
    byType: SummaryCount[];
    byStatus: SummaryCount[];
    firstReceivedAt: string | null;
    lastReceivedAt: string | null;
  };
  documents: {
    total: number;
    byType: SummaryCount[];
    byStatus: SummaryCount[];
    amounts: { currency: string; documents: number; amount: string }[];
    storesWithDocuments: number;
    incompleteDocuments: number;
    unknownRelationships: number;
    observedProductReferences: number;
  };
  recentLicenses: {
    id: string;
    reference: string;
    companyName: string;
    storeName: string | null;
    storeCount: number;
    province?: string | null;
    status: LicenseStatus;
    createdAt: string;
    validUntil: string;
  }[];
};

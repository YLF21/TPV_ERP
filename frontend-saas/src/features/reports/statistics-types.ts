export type StatisticsComparison = "NONE" | "PREVIOUS_PERIOD" | "PREVIOUS_YEAR" | "CUSTOM";
export type StatisticsGrouping = "DAY" | "WEEK" | "MONTH" | "COMPANY" | "STORE" | "PROVINCE";
export type StatisticsMode = "sales" | "averages" | "provinces" | "sync";

export type StatisticsFilters = {
  from: string;
  to: string;
  comparison: StatisticsComparison;
  comparisonFrom: string;
  comparisonTo: string;
  companyId: string;
  storeId: string;
  province: string;
  groupBy: StatisticsGrouping;
};

export type StatisticsOptions = {
  companies: { id: string; name: string }[];
  stores: { id: string; companyId: string; name: string }[];
};

export type StatisticsPeriod = { from: string; to: string; days: number };

export type StatisticsCurrency = {
  currency: string;
  documents: number | null;
  amount: string | null;
  averageAmount: string | null;
  amountPerDay: string | null;
  contributingStores?: number;
  amountPerStore?: string | null;
};

export type PeriodMetrics = {
  documents: number;
  eligibleDocuments: number | null;
  cancelledDocuments: number;
  syncEvents: number;
  syncErrors: number;
  stockMovementEvents: number;
  saleDocuments?: number;
  returnDocuments?: number;
  stores?: number;
  licenses?: number;
  currencies: StatisticsCurrency[];
};

export type StatisticsBreakdown = {
  current: PeriodMetrics;
  comparison: PeriodMetrics | null;
  deltas?: { currency: string; amount: string | null; percent: string | null }[];
};

export type StatisticsRow = Omit<StatisticsBreakdown, "current"> & {
  current: PeriodMetrics | null;
  key: string;
  label: string | null;
  comparisonLabel: string | null;
};

export type StatisticsSyncCount = { key: string; current: number; comparison: number | null };

export type StatisticsReport = {
  asOf: string;
  period: StatisticsPeriod;
  comparisonPeriod: StatisticsPeriod | null;
  groupBy: StatisticsGrouping;
  page: number;
  size: number;
  totalGroups: number;
  coverage: {
    unknownRelationships: number;
    currentIncompleteDocuments: number;
    comparisonIncompleteDocuments: number | null;
  };
  totals: StatisticsBreakdown;
  rows: StatisticsRow[];
  syncByType: StatisticsSyncCount[];
  syncByStatus: StatisticsSyncCount[];
};

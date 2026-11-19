export const statisticsPath = "/api/v1/admin/reports/statistics";
export const statisticsAsOf = "2026-07-31T00:34:56Z";

export const statisticsOptions = {
  companies: [
    { id: "0f100001-0000-4000-8000-000000000001", name: "Empresa Atlántica" },
    { id: "0f100001-0000-4000-8000-000000000002", name: "Empresa Peninsular" },
  ],
  stores: [
    { id: "0f100001-0000-4000-8000-000000000101", companyId: "0f100001-0000-4000-8000-000000000001", name: "Tienda Puerto" },
    { id: "0f100001-0000-4000-8000-000000000102", companyId: "0f100001-0000-4000-8000-000000000002", name: "Tienda Interior" },
  ],
};

const currentCurrencies = [
  { currency: "EUR", documents: 201, amount: "9007199254740993.17", averageAmount: "44811936590751.21", amountPerDay: "300239975158033.11" },
  { currency: "USD", documents: 21, amount: "-15.25", averageAmount: "-0.73", amountPerDay: "-0.51" },
  { currency: "GBP", documents: 17, amount: "0.00", averageAmount: "0.00", amountPerDay: "0.00" },
];
const previousCurrencies = [
  { currency: "EUR", documents: 201, amount: "603.00", averageAmount: "3.00", amountPerDay: "20.10" },
];
const screenshotCurrencies = [
  { ...currentCurrencies[0], amount: "2493.66", averageAmount: "12.41", amountPerDay: "831.22" },
  { ...currentCurrencies[1], amountPerDay: "-5.08" },
  currentCurrencies[2],
];

const emptyMetrics = {
  documents: 0,
  eligibleDocuments: 0,
  cancelledDocuments: 0,
  syncEvents: 0,
  syncErrors: 0,
  stockMovementEvents: 0,
  currencies: [],
};
const currentMetrics = {
  documents: 243,
  eligibleDocuments: 239,
  cancelledDocuments: 4,
  syncEvents: 523,
  syncErrors: 17,
  stockMovementEvents: 211,
  currencies: currentCurrencies,
};
const previousMetrics = {
  documents: 205,
  eligibleDocuments: 201,
  cancelledDocuments: 4,
  syncEvents: 302,
  syncErrors: 8,
  stockMovementEvents: 87,
  currencies: previousCurrencies,
};

const dayInMs = 86_400_000;
const parse = date => Date.parse(`${date}T00:00:00Z`);
const day = (date, offset) => new Date(parse(date) + offset * dayInMs).toISOString().slice(0, 10);
const period = (from, to) => ({ from, to, days: Math.round((parse(to) - parse(from)) / dayInMs) + 1 });

function comparisonPeriod(query, current, comparison) {
  if (comparison === "NONE") return null;
  if (comparison === "CUSTOM") return period(query.get("comparisonFrom"), query.get("comparisonTo"));
  if (comparison === "PREVIOUS_YEAR") {
    const priorYear = date => `${Number(date.slice(0, 4)) - 1}${date.slice(4)}`;
    return period(priorYear(current.from), priorYear(current.to));
  }
  return period(day(current.from, -current.days), day(current.from, -1));
}

function groupRows(groupBy, currentPeriod, priorPeriod, options) {
  if (groupBy === "DAY") {
    return Array.from({ length: Math.max(currentPeriod.days, priorPeriod?.days ?? 0) }, (_, ordinal) => ({
      key: String(ordinal),
      label: ordinal < currentPeriod.days ? day(currentPeriod.from, ordinal) : null,
      comparisonLabel: priorPeriod && ordinal < priorPeriod.days ? day(priorPeriod.from, ordinal) : null,
    }));
  }
  if (groupBy === "PROVINCE") {
    return Array.from({ length: 25 }, (_, index) => String(index + 1).padStart(2, "0"))
      .concat("UNKNOWN").map(key => ({ key, label: key, comparisonLabel: null }));
  }
  return options[groupBy === "STORE" ? "stores" : "companies"].map(({ id, name }) => ({ key: id, label: name, comparisonLabel: null }));
}

export function statisticsSnapshot(query, { empty = false, unknown = false, marker, ordinaryAmounts = false } = {}) {
  const currentPeriod = period(query.get("from") ?? "2026-07-01", query.get("to") ?? "2026-07-31");
  const priorPeriod = comparisonPeriod(query, currentPeriod, query.get("comparison") ?? "NONE");
  const groupBy = query.get("groupBy") ?? "DAY";
  const page = Number(query.get("page") ?? 0);
  const size = Number(query.get("size") ?? 25);
  const companyId = query.get("companyId");
  const storeId = query.get("storeId");
  const options = {
    companies: statisticsOptions.companies.filter(company => !companyId || company.id === companyId),
    stores: statisticsOptions.stores.filter(store => (!companyId || store.companyId === companyId) && (!storeId || store.id === storeId)),
  };
  const metrics = source => empty ? emptyMetrics : {
    ...source,
    eligibleDocuments: unknown ? null : source.eligibleDocuments,
    currencies: unknown ? source.currencies.map(entry => ({ ...entry, documents: null, amount: null, averageAmount: null, amountPerDay: null })) : source.currencies,
  };
  const current = {
    ...metrics(ordinaryAmounts ? { ...currentMetrics, currencies: screenshotCurrencies } : currentMetrics),
    documents: marker ?? (empty ? 0 : currentMetrics.documents),
  };
  const comparison = priorPeriod ? metrics(ordinaryAmounts ? {
    ...previousMetrics, currencies: previousCurrencies.map(entry => ({ ...entry, amountPerDay: "150.75" })),
  } : previousMetrics) : null;
  const groups = empty && groupBy !== "DAY" ? [] : groupRows(groupBy, currentPeriod, priorPeriod, options);
  return {
    asOf: statisticsAsOf,
    period: currentPeriod,
    comparisonPeriod: priorPeriod,
    groupBy, page, size, totalGroups: groups.length,
    coverage: {
      unknownRelationships: unknown ? 7 : 0,
      currentIncompleteDocuments: empty ? 0 : 13,
      comparisonIncompleteDocuments: comparison ? (empty ? 0 : 2) : null,
    },
    totals: { current, comparison },
    rows: groups.map((group, index) => ({
      ...group,
      current: index === 0 ? groupMetrics(groupBy, current) : emptyMetrics,
      comparison: comparison && (index === 0 ? groupMetrics(groupBy, comparison) : emptyMetrics),
    })).slice(page * size, (page + 1) * size),
    syncByType: empty ? [] : [
      { key: "DOCUMENTO", current: 312, comparison: comparison ? 215 : null },
      { key: "STOCK_MOVEMENT", current: 211, comparison: comparison ? 87 : null },
    ],
    syncByStatus: [
      ["RECEIVED", 11, 3], ["PROJECTED", 491, 288], ["IGNORED", 4, 3], ["ERROR", 17, 8],
    ].map(([key, current, previous]) => ({
      key, current: empty ? 0 : current, comparison: comparison ? (empty ? 0 : previous) : null,
    })),
  };
}

function groupMetrics(groupBy, metrics) {
  if (groupBy !== "DAY") return metrics;
  return { ...metrics, currencies: metrics.currencies.map(entry => ({ ...entry, amountPerDay: entry.amount })) };
}

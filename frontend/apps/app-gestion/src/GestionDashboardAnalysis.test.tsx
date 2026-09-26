import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { createTranslator } from "@tpverp/app-common";
import { FamilySalesWidget } from "./GestionDashboardAnalysis";
import type { SalesOverviewData } from "./dashboardModel";

const data: SalesOverviewData = {
  from: "2026-09-01", to: "2026-09-16", previousFrom: "2026-08-16", previousTo: "2026-08-31", storeTimezone: "Atlantic/Canary", currency: "EUR",
  current: { netSales: 90, operationCount: 2, averageAmount: 45, netUnits: 8 },
  previous: { netSales: 0, operationCount: 0, averageAmount: 0, netUnits: 0 },
  daily: [], previousDaily: [], topProducts: [],
  families: [
    { key: "food", name: "Alimentación", currentSales: 100, previousSales: 0, currentUnits: 8, previousUnits: 0 },
    { key: "ADJUSTMENTS", name: null, currentSales: -10, previousSales: 0, currentUnits: 0, previousUnits: 0 }
  ]
};

describe("family sales analysis", () => {
  it.each(["es", "en", "zh"] as const)("keeps localized graph and data amounts consistent including negative adjustments in %s", (locale) => {
    const t = createTranslator(locale);
    const draw = (display: "BAR" | "TABLE") => renderToStaticMarkup(<FamilySalesWidget
      state={{ loading: false, error: false, data }} t={t} locale={locale} display={display} comparison />);
    const chart = draw("BAR");
    const table = draw("TABLE");
    for (const html of [chart, table]) {
      expect(html).toContain(t("gestion.dashboard.unassignedAdjustments"));
      expect(html).toContain(t("gestion.dashboard.currentFamilies"));
      expect(html).not.toMatch(/gestion\.dashboard\.|NaN|Infinity/);
      expect(html).toContain(locale === "es" ? "-10,00" : "-€10.00");
    }
    expect(chart).toContain('class="negative"');
    expect(chart).toContain(locale === "zh" ? "2026/08/16" : "16/08/2026");
    expect(table).toContain("<tfoot>");
    expect(table).toContain(locale === "es" ? "90,00" : "€90.00");
    expect(table).toContain('class="numeric">—</td>');
  });

  it("aggregates long charts into Others while retaining adjustments and all table rows", () => {
    const many = { ...data, families: [...Array.from({ length: 11 }, (_, index) => ({
      key: String(index), name: `Familia ${index}`, currentSales: 1, previousSales: 2, currentUnits: 1, previousUnits: 2
    })), data.families![1]] };
    const html = renderToStaticMarkup(<FamilySalesWidget state={{ loading: false, error: false, data: many }}
      t={createTranslator("en")} locale="en" display="BAR" comparison />);
    expect(html.match(/<li>/g)).toHaveLength(10);
    expect(html).toContain("Other families");
    expect(html).toContain("€3.00");
    expect(html).toContain("€6.00");
    expect(html).toContain("-€10.00");
  });
});

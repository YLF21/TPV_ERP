import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { createTranslator } from "@tpverp/app-common";
import { SalesMetricWidget, SalesTrendWidget } from "./GestionDashboardWidgets";
import { defaultDashboardOptions, type SalesOverviewData } from "./dashboardModel";

const data: SalesOverviewData = {
  from: "2026-09-16", to: "2026-09-16", previousFrom: "2026-09-15", previousTo: "2026-09-15", storeTimezone: "Atlantic/Canary", currency: "EUR",
  current: { netSales: 0, operationCount: 0, averageAmount: 0 }, previous: { netSales: 0, operationCount: 0, averageAmount: 0 },
  daily: [{ date: "2026-09-16", netSales: 0, operationCount: 0 }], previousDaily: [{ date: "2026-09-15", netSales: 0, operationCount: 0 }], topProducts: []
};
describe("sales trend presentation", () => {
  it.each([true, false])("shows the one-day comparison marker and exact values only when comparison is %s", (showComparison) => {
    const oneDay = { ...data,
      daily: [{ date: data.from, netSales: 12, operationCount: 1 }],
      previousDaily: [{ date: data.previousFrom, netSales: 8, operationCount: 1 }]
    };
    const html = renderToStaticMarkup(<SalesTrendWidget state={{ loading: false, error: false, data: oneDay }} t={createTranslator("en")} locale="en" options={{ ...defaultDashboardOptions, showComparison }} />);
    expect(html.match(/<circle /g)).toHaveLength(showComparison ? 2 : 1);
    expect(html).toContain('<title>16/09/2026: €12.00</title>');
    expect(html).toContain('<td class="numeric">€12.00</td>');
    if (showComparison) {
      expect(html).toContain('class="gd-chart-previous-dot"');
      expect(html).toContain('<title>15/09/2026: €8.00</title>');
      expect(html).toContain('<td class="numeric">€8.00</td>');
    } else {
      expect(html).not.toContain('class="gd-chart-previous-dot"');
      expect(html).not.toContain('€8.00');
    }
  });

  it("keeps small-axis ticks distinct and exposes the exact dated values in its accessible table", () => {
    const html = renderToStaticMarkup(<SalesTrendWidget state={{ loading: false, error: false, data }} t={createTranslator("es")} locale="es" options={defaultDashboardOptions} />);
    expect(html).toContain('role="img"');
    expect(html).toContain('>0,25</text>');
    expect(html).toContain('>0,5</text>');
    expect(html).toContain('>0,75</text>');
    expect(html).toContain("16/09/2026");
    expect(html).toContain("15/09/2026");
    expect(html).not.toMatch(/NaN|Infinity/);
  });

  it("renders negative daily sales in bar and table modes without losing their sign", () => {
    const negative = { ...data, daily: [{ date: data.from, netSales: -12.5, operationCount: 1 }] };
    const html = renderToStaticMarkup(<SalesTrendWidget state={{ loading: false, error: false, data: negative }} t={createTranslator("en")} locale="en" options={{ ...defaultDashboardOptions, trendDisplay: "BAR", showComparison: false }} />);
    expect(html).toContain("gd-chart-bar current");
    expect(html).toContain("-€12.50");
    expect(html).not.toContain("Previous sales");
    expect(html).not.toMatch(/height="-|NaN|Infinity/);
    const table = renderToStaticMarkup(<SalesTrendWidget state={{ loading: false, error: false, data: negative }} t={createTranslator("en")} locale="en" options={{ ...defaultDashboardOptions, trendDisplay: "TABLE" }} />);
    expect(table).not.toContain('role="img"');
    expect(table).toContain("-€12.50");
    expect(table).toContain("Previous sales");
  });
});

describe("sales metric definitions", () => {
  it.each(["es", "en", "zh"] as const)("provides localized tooltip and accessible descriptions for operations and the server-calculated average in %s", (locale) => {
    const t = createTranslator(locale);
    // Deliberately different from netSales / count: presentation must use the API's amount.
    const metrics = { ...data, current: { netSales: 100, operationCount: 3, averageAmount: 24.57 } };
    for (const [metric, definition] of [["operationCount", "operationsDefinition"], ["averageAmount", "averageDefinition"]] as const) {
      const html = renderToStaticMarkup(<SalesMetricWidget state={{ loading: false, error: false, data: metrics }} t={t} locale={locale} onOpen={() => undefined} metric={metric} showComparison={false} />);
      const description = t(`gestion.dashboard.${definition}`);
      expect(description).not.toContain("gestion.dashboard.");
      expect(html).toContain(description);
      expect(html).toContain(`title="${description}`);
      const descriptionId = html.match(/aria-describedby="([^"]+)"/)?.[1];
      expect(descriptionId).toBeTruthy();
      expect(html).toContain(`id="${descriptionId}" class="gd-visually-hidden"`);
      if (metric === "averageAmount") {
        expect(html).toContain(locale === "es" ? "24,57" : "24.57");
        expect(html).not.toContain(locale === "es" ? "33,33" : "33.33");
      }
    }
  });
});

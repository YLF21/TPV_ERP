import { afterEach, describe, expect, it, vi } from "vitest";
import {
  controlAlertTypes,
  loadControlAlertGroups,
  loadControlAlertsAnalytics,
  loadControlAlerts,
  loadControlRuleCatalog,
  saveControlRule,
  transitionControlAlert,
  updateControlAlertWork
} from "./controlAlertsApi";

afterEach(() => vi.unstubAllGlobals());

describe("control alerts API", () => {
  it("uses backend pagination and real filters", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      content: [], number: 2, size: 25, totalElements: 0, totalPages: 0
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await loadControlAlerts({
      search: "T-100",
      status: "NEW",
      type: "TICKET_CANCELLED",
      ruleId: "rule-1",
      from: "2026-07-18T00:00:00.000Z",
      to: "2026-07-19T00:00:00.000Z",
      page: 2,
      size: 25
    }, "token");

    const [url] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/control/alerts?");
    expect(String(url)).toContain("search=T-100");
    expect(String(url)).toContain("status=NEW");
    expect(String(url)).toContain("type=TICKET_CANCELLED");
    expect(String(url)).toContain("ruleId=rule-1");
    expect(String(url)).toContain("from=2026-07-18T00%3A00%3A00.000Z");
    expect(String(url)).toContain("to=2026-07-19T00%3A00%3A00.000Z");
    expect(String(url)).toContain("page=2");
  });

  it("loads date-scoped rule groups and the real system catalog", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await loadControlAlertGroups("2026-07-18T00:00:00.000Z", "2026-07-19T00:00:00.000Z", "token");
    await loadControlRuleCatalog("token");

    expect(String(fetchMock.mock.calls[0][0])).toContain("/control/alerts/groups?from=2026-07-18T00%3A00%3A00.000Z&to=2026-07-19T00%3A00%3A00.000Z");
    expect(String(fetchMock.mock.calls[1][0])).toContain("/control/rules/catalog");
  });

  it("loads analytics scoped by range and overdue threshold", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ total: 0, overdueCount: 0, byStatus: [], byType: [], byUser: [], byTerminal: [] }), {
      status: 200, headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await loadControlAlertsAnalytics("2026-07-18T00:00:00.000Z", "2026-07-19T00:00:00.000Z", 24, "token");
    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).toContain("/control/alerts/analytics?");
    expect(url).toContain("overdueHours=24");
    expect(url).toContain("from=2026-07-18T00%3A00%3A00.000Z");
  });

  it("posts versioned transitions and exposes manual-price types for unsupported catalog display", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ alert: { id: "a-1" }, history: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await transitionControlAlert("a-1", "REVIEW", "checked", 4, "token");
    const [, options] = fetchMock.mock.calls[0];
    expect(String(fetchMock.mock.calls[0][0])).toContain("/control/alerts/a-1/review");
    expect(JSON.parse(String(options.body))).toEqual({ comment: "checked", version: 4 });
    expect(controlAlertTypes).toContain("MANUAL_PRICE_CHANGED");
  });

  it("creates a system rule without editable name or severity", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "r-1" }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await saveControlRule({ type: "TICKET_CANCELLED", active: false, configuration: {} }, null, "token");
    const [, options] = fetchMock.mock.calls[0];
    expect(JSON.parse(String(options.body))).toEqual({ type: "TICKET_CANCELLED", active: false, configuration: {} });
  });

  it("sends work queue filters and a versioned assignment update", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ content: [], number: 0, size: 25, totalElements: 0, totalPages: 0 }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ alert: { id: "a-1", priority: "CRITICAL", version: 5 }, history: [], workHistory: [] }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await loadControlAlerts({ search: "", status: "", priority: "HIGH", assigneeId: "u-1", overdue: true, page: 0, size: 25 }, "token");
    await updateControlAlertWork({ id: "a-1", type: "TICKET_CANCELLED", status: "NEW", priority: "HIGH", occurredAt: "2026-07-18T10:00:00Z", version: 4 }, {
      priority: "CRITICAL", assigneeId: "u-1", dueAt: "2026-07-19T10:00:00Z", comment: "Urgente"
    }, "token");

    const filterUrl = String(fetchMock.mock.calls[0][0]);
    expect(filterUrl).toContain("priority=HIGH");
    expect(filterUrl).toContain("assigneeId=u-1");
    expect(filterUrl).toContain("overdue=true");
    expect(String(fetchMock.mock.calls[1][0])).toContain("/control/alerts/a-1/work");
    expect(JSON.parse(String(fetchMock.mock.calls[1][1].body))).toEqual({ priority: "CRITICAL", assigneeId: "u-1", dueAt: "2026-07-19T10:00:00Z", comment: "Urgente", version: 4 });
  });
});

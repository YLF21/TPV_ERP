import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AppKind } from "../types";
import {
  applySavedVisualizationPreferences,
  loadReportVisualizationPreferences,
  sanitizeVisibleAttributes,
  saveReportVisualizationPreference
} from "./salesReportVisualizationPreferences";
import { apiRequest } from "../api/client";

vi.mock("../api/client", () => ({
  apiRequest: vi.fn()
}));

const apiRequestMock = vi.mocked(apiRequest);

const reports = {
  "salesReport.tickets": {
    availableAttributes: ["date", "ticket", "user", "total"],
    defaultVisibleAttributes: ["date", "ticket", "total"]
  },
  "salesReport.invoices": {
    availableAttributes: ["date", "invoice", "customerName", "total"],
    defaultVisibleAttributes: ["date", "invoice", "total"]
  }
};

describe("sales report visualization preferences", () => {
  beforeEach(() => {
    apiRequestMock.mockReset();
  });

  it("sanitizes saved attributes and keeps required total at the end", () => {
    expect(sanitizeVisibleAttributes(
      ["ticket", "missing", "ticket", "date"],
      reports["salesReport.tickets"]
    )).toEqual(["ticket", "date", "total"]);
  });

  it("falls back to defaults when saved attributes are unusable", () => {
    expect(sanitizeVisibleAttributes(
      ["missing"],
      reports["salesReport.invoices"]
    )).toEqual(["date", "invoice", "total"]);
  });

  it("applies saved preferences per known report only", () => {
    const current = {
      "salesReport.tickets": ["date", "ticket", "total"],
      "salesReport.invoices": ["date", "invoice", "total"]
    };

    expect(applySavedVisualizationPreferences(current, reports, [
      { reportKey: "salesReport.tickets", visibleAttributes: ["user", "ticket"] },
      { reportKey: "salesReport.unknown", visibleAttributes: ["x"] }
    ])).toEqual({
      "salesReport.tickets": ["user", "ticket", "total"],
      "salesReport.invoices": ["date", "invoice", "total"]
    });
  });

  it("loads preferences with bearer token and app", async () => {
    apiRequestMock.mockResolvedValueOnce({ preferences: [] });

    await loadReportVisualizationPreferences("venta", "token");

    expect(apiRequestMock).toHaveBeenCalledWith(
      "/sales-reports/visualization-preferences?app=venta",
      { token: "token" }
    );
  });

  it("saves preferences without terminal identity", async () => {
    apiRequestMock.mockResolvedValueOnce({
      reportKey: "salesReport.tickets",
      visibleAttributes: ["date", "ticket", "total"]
    });

    await saveReportVisualizationPreference(
      "venta" as AppKind,
      "token",
      "salesReport.tickets",
      ["date", "ticket", "total"]
    );

    expect(apiRequestMock).toHaveBeenCalledWith(
      "/sales-reports/visualization-preferences/salesReport.tickets",
      {
        method: "PUT",
        token: "token",
        body: {
          app: "venta",
          visibleAttributes: ["date", "ticket", "total"]
        }
      }
    );
    expect(JSON.stringify(apiRequestMock.mock.calls[0][1])).not.toContain("terminal");
  });
});

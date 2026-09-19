import { describe, expect, it } from "vitest";
import { compareSaasHistoryDecimals, formatSaasHistoryDecimal, saasSalesHistoryPath } from "./stockSalesHistory";

describe("SaaS product sales history", () => {
  it("encodes server filters and opaque cursors without sending a local product code", () => {
    const path = saasSalesHistoryPath("product/1", {
      from: "2026-07-01", to: "2026-07-31", status: "CONFIRMADO", storeIds: ["store-1", "store-2"],
      sortBy: "quantity", sortDirection: "desc",
    }, "opaque+/=");
    const url = new URL(path, "http://local.test");
    expect(url.pathname).toBe("/stock/products/product%2F1/sales-history/saas");
    expect(url.searchParams.get("storeIds")).toBe("store-1,store-2");
    expect(url.searchParams.get("cursor")).toBe("opaque+/=");
    expect(url.searchParams.get("size")).toBe("200");
    expect(url.searchParams.has("view")).toBe(false);
  });

  it("formats decimals without float rounding or replacing missing values with zero", () => {
    expect(formatSaasHistoryDecimal("9007199254740993.125", "es")).toBe("9.007.199.254.740.993,125");
    expect(formatSaasHistoryDecimal("-0.005", "en", 2)).toBe("-0.005");
    expect(formatSaasHistoryDecimal("4.500", "es", 2)).toBe("4,50");
    expect(formatSaasHistoryDecimal("2.000", "zh")).toBe("2");
    expect(formatSaasHistoryDecimal(null, "es")).toBe("—");
    expect(formatSaasHistoryDecimal("invalid", "es")).toBe("—");
  });

  it("ranks signed decimal strings exactly regardless of precision or scale", () => {
    expect(compareSaasHistoryDecimals("9007199254740993.001", "9007199254740993.002")).toBe(-1);
    expect(compareSaasHistoryDecimals("-0.001", "0")).toBe(-1);
    expect(compareSaasHistoryDecimals("3.10", "3.1")).toBe(0);
    expect(compareSaasHistoryDecimals("2.001", "2.000")).toBe(1);
  });
});

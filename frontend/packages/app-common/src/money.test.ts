import { describe, expect, it } from "vitest";
import { applyMoneyDiscount, formatEuroAmount, formatEuroUnitPrice, parseMoneyValue, roundMoneyProduct, roundUnitPrice } from "./money";

describe("decimal money arithmetic", () => {
  it.each([
    [1.95, 6, 5, 11.12], [1.45, 6, 5, 8.27], [1.65, 6, 5, 9.41], [2.65, 2, 5, 5.04],
    [2.208, 10, 0, 22.08], [1.235, 2, 0, 2.47], [2.675, 1, 0, 2.68],
    [0.01, 0.5, 50, 0.01], [1.005, 0.005, 0, 0.01], [1.95, 6, 100, 0],
    [11.7, 1, 33.33, 7.80], [0, 6, 5, 0]
  ])("rounds %s x %s with %s percent to %s", (price, quantity, discount, expected) => {
    expect(applyMoneyDiscount(roundMoneyProduct(price, quantity), discount)).toBe(expected);
  });

  it("handles decimal ties, scientific notation and invalid values deterministically", () => {
    expect(roundMoneyProduct(-1.005, 1)).toBe(-1.01);
    expect(roundMoneyProduct(1e-7, 50000)).toBe(0.01);
    expect(roundMoneyProduct(1e21, 1e-21)).toBe(1);
    expect(roundMoneyProduct(Infinity, 1)).toBe(0);
    expect(roundMoneyProduct(1, NaN)).toBe(0);
    expect(applyMoneyDiscount(NaN, 5)).toBe(0);
    expect(applyMoneyDiscount(11.7, NaN)).toBe(0);
    expect(applyMoneyDiscount(11.7, -1)).toBe(11.7);
    expect(applyMoneyDiscount(11.7, 101)).toBe(0);
    expect(applyMoneyDiscount(11.7, 5.005)).toBe(11.11);
  });

  it("rounds the document discount only after adding cent-rounded line totals", () => {
    expect(applyMoneyDiscount(0.1 + 0.2, 5)).toBe(0.29);
    expect(applyMoneyDiscount(1993.21, 5)).toBe(1893.55);
  });
});

describe("money formatters", () => {
  it("treats three decimal separators as decimals only for prices", () => {
    for (const value of ["1.234", "1,234"]) {
      expect(parseMoneyValue(value, 3)).toBe(1.234);
      expect(parseMoneyValue(value)).toBe(1234);
    }
    expect(formatEuroUnitPrice("2.208", "es")).toContain("2,208");
    expect(formatEuroAmount(2.208 * 10, "es")).toContain("22,08");
    expect(formatEuroUnitPrice("2.20", "es")).toContain("2,20");
    expect(parseMoneyValue("1,234,567", 3)).toBe(1234567);
    expect(parseMoneyValue("1,234,56", 3)).toBeNull();
    expect(parseMoneyValue("1,234,56")).toBeNull();
    expect(parseMoneyValue("1.2345", 3)).toBe(1.2345);
    expect(roundUnitPrice(2.469 * 0.5)).toBe(1.235);
    expect(roundUnitPrice(-2.469 * 0.5)).toBe(-1.235);
  });
  it("parses API and localized monetary values without losing thousands", () => {
    expect(parseMoneyValue("1018.96")).toBe(1018.96);
    expect(parseMoneyValue("1,018.96")).toBe(1018.96);
    expect(parseMoneyValue("1.018,96 €")).toBe(1018.96);
    expect(parseMoneyValue("-6,05 €")).toBe(-6.05);
    expect(parseMoneyValue("(12,10 €)")).toBe(-12.1);
  });

  it("formats every valid value as EUR in the requested locale", () => {
    expect(formatEuroAmount("1,018.96", "es")).toContain("1.018,96");
    expect(formatEuroAmount("-6.05", "es")).toContain("-6,05");
    expect(formatEuroAmount("12.10", "en")).toContain("€");
  });
});

import { describe, expect, it } from "vitest";
import { formatEuroAmount, parseMoneyValue } from "./money";

describe("money formatters", () => {
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

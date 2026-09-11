import { describe, expect, it } from "vitest";
import { customerDocumentAmount } from "./customerDocumentAmount";

describe("customerDocumentAmount", () => {
  it.each(["es", "en", "zh"])("preserves large decimal cents in %s", (locale) => {
    const formatted = customerDocumentAmount("9007199254740993.27", "EUR", locale);
    expect(formatted.replace(/\D/g, "")).toBe("900719925474099327");
  });
  it("keeps signed small amounts and currencies without rounding unit prices", () => {
    expect(customerDocumentAmount("-0.01", "EUR", "es")).toContain("-0,01");
    expect(customerDocumentAmount("12.10", "USD", "en")).toBe("$12.10");
    expect(customerDocumentAmount("0", "EUR", "es")).toContain("0,00");
  });
  it.each(["NaN", "1.234", "1e3", "1,23", "", "Infinity"])("does not silently coerce invalid historical money: %s", (value) => {
    expect(customerDocumentAmount(value, "EUR", "es")).toBe("—");
  });
});

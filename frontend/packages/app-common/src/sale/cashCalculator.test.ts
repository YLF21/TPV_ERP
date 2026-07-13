import { describe, expect, it } from "vitest";
import { cashChangeCents, cashInputCents, pressCashKey, setCashShortcut } from "./cashCalculator";

describe("cash calculator", () => {
  it("builds a two-decimal amount with the numeric keypad", () => {
    let value = "";
    for (const key of ["2", "0", ",", "5", "0"]) value = pressCashKey(value, key);
    expect(value).toBe("20,50");
    expect(cashInputCents(value)).toBe(2050);
  });

  it("rejects more than two decimals and supports delete and clear", () => {
    expect(pressCashKey("12,34", "5")).toBe("12,34");
    expect(pressCashKey("12,34", "BACKSPACE")).toBe("12,3");
    expect(pressCashKey("12,34", "CLEAR")).toBe("");
  });

  it("sets exact and banknote shortcuts", () => {
    expect(setCashShortcut("EXACT", 1543)).toBe("15,43");
    expect(setCashShortcut(20, 1543)).toBe("20,00");
  });

  it("calculates change without returning a negative value", () => {
    expect(cashChangeCents(1543, 2000)).toBe(457);
    expect(cashChangeCents(1543, 1000)).toBe(0);
  });
});

import { describe, expect, it } from "vitest";
import {
  formatProductQuantity,
  isProductQuantityPrecisionValid,
  parseProductQuantityInput,
  productQuantityStep,
} from "./productQuantity";

describe("productQuantity", () => {
  it("requires whole quantities for unit products", () => {
    expect(isProductQuantityPrecisionValid(5, "UNIT")).toBe(true);
    expect(isProductQuantityPrecisionValid(4.992, "UNIT")).toBe(false);
    expect(productQuantityStep("UNIT")).toBe(1);
    expect(formatProductQuantity("5.000", "UNIT", "es")).toBe("5");
  });

  it("accepts up to three decimals for weight and service products", () => {
    expect(isProductQuantityPrecisionValid(4.992, "WEIGHT")).toBe(true);
    expect(isProductQuantityPrecisionValid(1.2345, "SERVICE")).toBe(false);
    expect(productQuantityStep("WEIGHT")).toBe(0.001);
    expect(formatProductQuantity("4.900", "WEIGHT", "es")).toBe("4,9");
  });

  it("parses point or comma inputs without accepting more than three decimals", () => {
    expect(parseProductQuantityInput("1,25")).toBe(1.25);
    expect(parseProductQuantityInput("1.234")).toBe(1.234);
    expect(parseProductQuantityInput("1.2345")).toBeNaN();
  });
});

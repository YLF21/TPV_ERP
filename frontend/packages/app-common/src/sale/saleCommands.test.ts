import { describe, expect, it } from "vitest";
import { saleCommandFromKeyboard, type SaleKeyboardCommandEvent } from "./saleCommands";

function key(
  value: string,
  options: Partial<SaleKeyboardCommandEvent> = {},
): SaleKeyboardCommandEvent {
  return {
    key: value,
    code: options.code ?? "",
    ctrlKey: options.ctrlKey ?? false,
    shiftKey: options.shiftKey ?? false,
    altKey: options.altKey ?? false,
    metaKey: options.metaKey ?? false,
  };
}

describe("saleCommandFromKeyboard", () => {
  it("resolves the EAN and product-label shortcuts exactly", () => {
    expect(saleCommandFromKeyboard(key("F2", { ctrlKey: true })))
      .toBe("ean-generator");
    expect(saleCommandFromKeyboard(key("i", { ctrlKey: true })))
      .toBe("print-product-label");
    expect(saleCommandFromKeyboard(key("F2"))).toBe("calculator");
  });

  it("resolves the agreed document shortcuts without changing their keys", () => {
    expect(saleCommandFromKeyboard(key("d", { ctrlKey: true })))
      .toBe("customer-receivables");
    expect(saleCommandFromKeyboard(key("o", { ctrlKey: true }))).toBe("sale-comment");
    expect(saleCommandFromKeyboard(key("F4", { ctrlKey: true }))).toBe("clear-sale");
    expect(saleCommandFromKeyboard(key("A", { ctrlKey: true, shiftKey: true }))).toBe("clear-lines");
    expect(saleCommandFromKeyboard(key("D", { ctrlKey: true, shiftKey: true }))).toBe("clear-discounts");
    expect(saleCommandFromKeyboard(key("p", { ctrlKey: true }))).toBe("print-method");
  });

  it("opens product search with Delete without modifying the other shortcuts", () => {
    expect(saleCommandFromKeyboard(key("Delete"))).toBe("product-search");
    expect(saleCommandFromKeyboard(key("Backspace"))).toBeNull();
  });

  it("keeps native editing shortcuts outside the sales command registry", () => {
    for (const value of ["c", "v", "x", "z", "y"]) {
      expect(saleCommandFromKeyboard(key(value, { ctrlKey: true }))).toBeNull();
    }
  });

  it("recognizes numeric-keypad quantity operations", () => {
    expect(saleCommandFromKeyboard(key("+", { ctrlKey: true, code: "NumpadAdd" })))
      .toBe("add-quantity");
    expect(saleCommandFromKeyboard(key("-", { ctrlKey: true, code: "NumpadSubtract" })))
      .toBe("subtract-quantity");
  });

  it("keeps the cash session commands on F8 and F9", () => {
    expect(saleCommandFromKeyboard(key("F8"))).toBe("close-cash");
    expect(saleCommandFromKeyboard(key("F9"))).toBe("cash-withdrawal");
  });

  it("keeps Inicio and Ctrl+RePág as distinct temporary overrides", () => {
    expect(saleCommandFromKeyboard(key("Home"))).toBe("temporary-name");
    expect(saleCommandFromKeyboard(key("PageUp", { ctrlKey: true })))
      .toBe("temporary-price");
    expect(saleCommandFromKeyboard(key("PageUp"))).toBe("desired-price");
  });

  it("does not reserve shortcuts modified with Alt or Meta", () => {
    expect(saleCommandFromKeyboard(key("F2", { altKey: true }))).toBeNull();
    expect(saleCommandFromKeyboard(key("F2", { metaKey: true }))).toBeNull();
  });
});

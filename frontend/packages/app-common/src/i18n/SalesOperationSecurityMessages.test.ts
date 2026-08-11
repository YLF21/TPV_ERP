import { describe, expect, it } from "vitest";
import { messages } from "./LocalizedMessages";

const categories = [
  "CASH",
  "TICKET",
  "INVOICE",
  "PRODUCT",
  "DISCOUNT",
  "CREDIT",
  "PAYMENT",
  "PAYMENT_TERMINAL",
] as const;

const operations = [
  "OPEN_CASH_DRAWER",
  "EDIT_CATALOG_PRODUCT",
  "CLOSE_CASH_SESSION",
  "CASH_MOVEMENT",
  "RETURN_TICKET",
  "RETURN_SALES_INVOICE",
  "CANCEL_TICKET",
  "CONVERT_TICKET_TO_INVOICE",
  "MANUAL_RETURN_WITHOUT_TICKET",
  "DELETE_PARKED_SALE",
  "TEMPORARY_NAME",
  "TEMPORARY_PRICE_CHANGE",
  "OPEN_PRICE_PRODUCT",
  "APPLY_SALE_DISCOUNT",
  "APPLY_CHECKOUT_DISCOUNT",
  "CREATE_PENDING_RECEIVABLE",
  "CREDIT_OVERRIDE",
  "CONFIRM_MANUAL_CARD_PAYMENT",
  "CONFIRM_TRANSFER_PAYMENT",
  "PAYMENT_TERMINAL_VOID",
  "PAYMENT_TERMINAL_REFUND",
  "PAYMENT_COMPENSATION_ACK",
] as const;

const shortcuts = [
  "NEGATIVE_PAUSE",
  "HOME",
  "CTRL_PAGE_UP",
  "PAGE_UP",
] as const;

const effectiveProtectionKeys = [
  "direct",
  "password",
  "permission",
  "permissionAndPassword",
  "delegated",
] as const;

describe("sales operation security translations", () => {
  it.each(["es", "en", "zh"] as const)(
    "translates every backend category and operation in %s",
    (locale) => {
      const keys = [
        ...categories.map((category) => (
          `gestion.salesOperationSecurity.category.${category}`
        )),
        ...operations.map((operation) => (
          `gestion.salesOperationSecurity.operation.${operation}`
        )),
        ...shortcuts.map((shortcut) => (
          `gestion.salesOperationSecurity.shortcut.${shortcut}`
        )),
        ...effectiveProtectionKeys.map((protection) => (
          `gestion.salesOperationSecurity.effective.${protection}`
        )),
      ];

      keys.forEach((key) => {
        expect(messages[locale][key]).toBeTruthy();
        expect(messages[locale][key]).not.toBe(key);
      });

      effectiveProtectionKeys.forEach((protection) => {
        expect(messages[locale][
          `gestion.salesOperationSecurity.effective.${protection}`
        ]).not.toContain(";");
      });
    },
  );
});

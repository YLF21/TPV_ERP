import { describe, expect, it } from "vitest";
import type { SalesOperationSecurityConfiguration } from "./operationSecurity";
import {
  detectSaleMutationOperations,
  saleMutationAuthorizationRequirements,
  saleMutationCredentialsRequired,
  saleWithOperationAuthorizations,
} from "./saleMutationAuthorizations";

function configuration(
  requirePermission: boolean,
  requirePassword: boolean,
): SalesOperationSecurityConfiguration {
  return {
    storeId: "store-1",
    version: 3,
    operations: [{
      code: "APPLY_SALE_DISCOUNT",
      category: "DISCOUNT",
      shortcuts: ["/"],
      permissions: ["APLICAR_DESCUENTO"],
      defaultRequirePermission: true,
      defaultRequirePassword: false,
      requirePermission,
      requirePassword,
      customized: true,
    }],
  };
}

describe("sale mutation authorization requirements", () => {
  it("detects each protected cart mutation without treating open price as a price change", () => {
    expect(detectSaleMutationOperations([{
      quantity: -1,
      discountPercent: 15,
      catalogName: "Catalog name",
      temporaryName: "Ticket name",
      catalogUnitPrice: "10.00",
      openUnitPrice: 8,
    }, {
      quantity: 1,
      discountPercent: 5,
      catalogName: "Open product",
      catalogUnitPrice: 0,
      openUnitPrice: 2,
    }], 20)).toEqual([
      { code: "MANUAL_RETURN_WITHOUT_TICKET" },
      { code: "TEMPORARY_NAME" },
      { code: "TEMPORARY_PRICE_CHANGE" },
      { code: "OPEN_PRICE_PRODUCT" },
      { code: "APPLY_SALE_DISCOUNT", requestedDiscountPercent: 15 },
      { code: "APPLY_CHECKOUT_DISCOUNT", requestedDiscountPercent: 20 },
    ]);
  });

  it("ignores the personal discount ceiling when permission control is disabled", () => {
    const requirements = saleMutationAuthorizationRequirements(
      configuration(false, false),
      [{
        code: "APPLY_SALE_DISCOUNT",
        label: "Descuento",
        requestedDiscountPercent: 80,
      }],
      [],
      5,
    );

    expect(requirements?.[0].authorization.mode).toBe("DIRECT");
  });

  it("requires a named authorizer when a permission-protected discount exceeds the operator limit", () => {
    const requirements = saleMutationAuthorizationRequirements(
      configuration(true, false),
      [{
        code: "APPLY_SALE_DISCOUNT",
        label: "Descuento",
        requestedDiscountPercent: 20,
      }],
      ["APLICAR_DESCUENTO"],
      10,
    );

    expect(requirements?.[0].authorization).toEqual({
      mode: "DELEGATED",
      requireUsername: true,
      requirePassword: true,
    });
  });

  it("fails closed if any active operation is missing from the effective configuration", () => {
    expect(saleMutationAuthorizationRequirements(
      configuration(true, false),
      [{ code: "TEMPORARY_PRICE_CHANGE", label: "Precio temporal" }],
      ["APLICAR_DESCUENTO"],
      100,
    )).toBeNull();
  });

  it("only prompts non-direct operations and adds credentials without mutating the sale", () => {
    const sale = { customerId: null, lines: [] };
    const credentials = {
      APPLY_SALE_DISCOUNT: {
        authorizerUsername: "manager",
        authorizerPassword: "secret",
      },
    };
    const result = saleWithOperationAuthorizations(sale, credentials);

    expect(saleMutationCredentialsRequired([{
      code: "OPEN_PRICE_PRODUCT",
      label: "Precio abierto",
      authorization: {
        mode: "DIRECT",
        requireUsername: false,
        requirePassword: false,
      },
    }, {
      code: "APPLY_SALE_DISCOUNT",
      label: "Descuento",
      authorization: {
        mode: "DELEGATED",
        requireUsername: true,
        requirePassword: true,
      },
    }])).toHaveLength(1);
    expect(result).toEqual({ ...sale, operationAuthorizations: credentials });
    expect(sale).not.toHaveProperty("operationAuthorizations");
  });
});

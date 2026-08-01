import { describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import {
  findSaleOperationAuthorization,
  loadSalesOperationSecurity,
  resetSalesOperationSecurity,
  resolveSaleOperationAuthorization,
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  saveSalesOperationSecurity,
  type SalesOperationSecurityConfiguration,
} from "./operationSecurity";

const configuration: SalesOperationSecurityConfiguration = {
  storeId: "store-1",
  version: 4,
  operations: [{
    code: "OPEN_CASH_DRAWER",
    category: "CASH",
    shortcuts: ["F3"],
    permissions: ["ABRIR_CAJON"],
    defaultRequirePermission: true,
    defaultRequirePassword: false,
    requirePermission: true,
    requirePassword: false,
    customized: false,
  }],
};

describe("sales operation security API", () => {
  it("loads the effective store configuration", async () => {
    const request = vi.fn(async <T,>() => configuration as T);

    await expect(loadSalesOperationSecurity(
      "token",
      request as unknown as typeof apiRequest,
    )).resolves.toEqual(configuration);

    expect(request).toHaveBeenCalledWith("/sales/operation-security", {
      token: "token",
    });
  });

  it("saves a versioned complete draft", async () => {
    const request = vi.fn(async <T,>() => configuration as T);
    const operations = [{
      code: "OPEN_CASH_DRAWER",
      requirePermission: false,
      requirePassword: true,
    }];

    await saveSalesOperationSecurity(
      4,
      operations,
      "token",
      request as unknown as typeof apiRequest,
    );

    expect(request).toHaveBeenCalledWith("/sales/operation-security", {
      token: "token",
      method: "PUT",
      body: {
        expectedVersion: 4,
        operations,
      },
    });
  });

  it("asks the backend to restore its own defaults", async () => {
    const request = vi.fn(async <T,>() => configuration as T);

    await resetSalesOperationSecurity(
      4,
      "token",
      request as unknown as typeof apiRequest,
    );

    expect(request).toHaveBeenCalledWith("/sales/operation-security/reset", {
      token: "token",
      method: "POST",
      body: { expectedVersion: 4 },
    });
  });

  it.each([
    [false, false, [], "DIRECT", false],
    [false, true, [], "CURRENT_PASSWORD", false],
    [true, false, ["ABRIR_CAJON"], "DIRECT", false],
    [true, true, ["ABRIR_CAJON"], "CURRENT_PASSWORD", false],
    [true, false, [], "DELEGATED", true],
    [true, true, [], "DELEGATED", true],
  ] as const)(
    "resolves P/W policy permission=%s password=%s",
    (requirePermission, requirePassword, permissions, mode, requireUsername) => {
      expect(resolveSaleOperationAuthorization({
        ...configuration.operations[0],
        requirePermission,
        requirePassword,
      }, permissions)).toEqual({
        mode,
        requireUsername,
        requirePassword: mode !== "DIRECT",
      });
    },
  );

  it("treats ADMIN as a permission holder and returns null for an unknown operation", () => {
    expect(resolveSaleOperationAuthorization({
      ...configuration.operations[0],
      requirePermission: true,
      requirePassword: true,
    }, ["ADMIN"]).mode).toBe("CURRENT_PASSWORD");
    expect(findSaleOperationAuthorization(configuration, "MISSING", [])).toBeNull();
  });

  it("builds credentials without ever delegating a current-password policy", () => {
    const currentPassword = {
      mode: "CURRENT_PASSWORD",
      requireUsername: false,
      requirePassword: true,
    } as const;
    const delegated = {
      mode: "DELEGATED",
      requireUsername: true,
      requirePassword: true,
    } as const;

    expect(saleOperationCredentials(currentPassword, "other-user", "secret"))
      .toEqual({ authorizerPassword: "secret" });
    expect(saleOperationCredentials(delegated, " manager ", "secret"))
      .toEqual({ authorizerUsername: "manager", authorizerPassword: "secret" });
    expect(saleOperationAuthorizationComplete(delegated, "", "secret")).toBe(false);
    expect(saleOperationAuthorizationComplete(delegated, "manager", "secret")).toBe(true);
  });
});

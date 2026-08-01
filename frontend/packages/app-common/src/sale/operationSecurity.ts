import { apiRequest } from "../api/client";

export type SalesOperationSecurityOperation = {
  code: string;
  category: string;
  shortcuts: string[];
  permissions: string[];
  defaultRequirePermission: boolean;
  defaultRequirePassword: boolean;
  requirePermission: boolean;
  requirePassword: boolean;
  customized: boolean;
};

export type SalesOperationSecurityConfiguration = {
  storeId: string;
  version: number;
  operations: SalesOperationSecurityOperation[];
};

export type SalesOperationSecurityUpdate = {
  code: string;
  requirePermission: boolean;
  requirePassword: boolean;
};

export type SaleOperationAuthorizationMode =
  | "DIRECT"
  | "CURRENT_PASSWORD"
  | "DELEGATED";

export type SaleOperationAuthorization = {
  mode: SaleOperationAuthorizationMode;
  requireUsername: boolean;
  requirePassword: boolean;
};

export type SaleOperationCredentials = {
  authorizerUsername?: string;
  authorizerPassword?: string;
};

export function resolveSaleOperationAuthorization(
  operation: SalesOperationSecurityOperation,
  userPermissions: readonly string[],
): SaleOperationAuthorization {
  const userHasPermission = userPermissions.includes("ADMIN")
    || operation.permissions.some((permission) => userPermissions.includes(permission));

  if (operation.requirePermission && !userHasPermission) {
    return {
      mode: "DELEGATED",
      requireUsername: true,
      // Delegated authorization always validates the delegated user's password.
      requirePassword: true,
    };
  }

  if (operation.requirePassword) {
    return {
      mode: "CURRENT_PASSWORD",
      requireUsername: false,
      requirePassword: true,
    };
  }

  return {
    mode: "DIRECT",
    requireUsername: false,
    requirePassword: false,
  };
}

export function findSaleOperationAuthorization(
  configuration: SalesOperationSecurityConfiguration | null | undefined,
  code: string,
  userPermissions: readonly string[],
): SaleOperationAuthorization | null {
  const operation = configuration?.operations?.find((candidate) => candidate.code === code);
  return operation
    ? resolveSaleOperationAuthorization(operation, userPermissions)
    : null;
}

export function saleOperationAuthorizationComplete(
  authorization: SaleOperationAuthorization,
  username: string,
  password: string,
) {
  return authorization.mode === "DIRECT"
    || (Boolean(password) && (
      authorization.mode !== "DELEGATED" || Boolean(username.trim())
    ));
}

export function saleOperationCredentials(
  authorization: SaleOperationAuthorization,
  username: string,
  password: string,
): SaleOperationCredentials {
  if (authorization.mode === "DIRECT") return {};
  return {
    ...(authorization.mode === "DELEGATED"
      ? { authorizerUsername: username.trim() }
      : {}),
    authorizerPassword: password,
  };
}

export function loadSalesOperationSecurity(
  token?: string,
  request = apiRequest,
) {
  return request<SalesOperationSecurityConfiguration>("/sales/operation-security", {
    token,
  });
}

export function saveSalesOperationSecurity(
  expectedVersion: number,
  operations: SalesOperationSecurityUpdate[],
  token?: string,
  request = apiRequest,
) {
  return request<SalesOperationSecurityConfiguration>("/sales/operation-security", {
    token,
    method: "PUT",
    body: {
      expectedVersion,
      operations,
    },
  });
}

export function resetSalesOperationSecurity(
  expectedVersion: number,
  token?: string,
  request = apiRequest,
) {
  return request<SalesOperationSecurityConfiguration>("/sales/operation-security/reset", {
    token,
    method: "POST",
    body: { expectedVersion },
  });
}

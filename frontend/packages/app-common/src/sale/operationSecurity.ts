import { apiRequest } from "../api/client";
import type {
  SaleOperationAuthorization,
  SalesOperationSecurityConfiguration,
} from "./operationAuthorization";

export {
  findSaleOperationAuthorization,
  resolveSaleOperationAuthorization,
} from "./operationAuthorization";
export type {
  SaleOperationAuthorization,
  SaleOperationAuthorizationMode,
  SalesOperationSecurityConfiguration,
  SalesOperationSecurityOperation,
} from "./operationAuthorization";

export type SalesOperationSecurityUpdate = {
  code: string;
  requirePermission: boolean;
  requirePassword: boolean;
};

export type SaleOperationCredentials = {
  authorizerUsername?: string;
  authorizerPassword?: string;
};

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

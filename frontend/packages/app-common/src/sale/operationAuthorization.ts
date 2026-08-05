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

export type SaleOperationAuthorizationMode =
  | "DIRECT"
  | "CURRENT_PASSWORD"
  | "DELEGATED";

export type SaleOperationAuthorization = {
  mode: SaleOperationAuthorizationMode;
  requireUsername: boolean;
  requirePassword: boolean;
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

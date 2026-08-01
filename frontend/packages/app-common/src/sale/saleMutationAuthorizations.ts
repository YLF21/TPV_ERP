import {
  resolveSaleOperationAuthorization,
  type SaleOperationAuthorization,
  type SaleOperationCredentials,
  type SalesOperationSecurityConfiguration,
} from "./operationSecurity";

export type ActiveSaleMutationOperation = {
  code: string;
  label: string;
  requestedDiscountPercent?: number;
};

export type SaleMutationLineState = {
  quantity: number;
  discountPercent: number;
  catalogName?: string | null;
  temporaryName?: string | null;
  catalogUnitPrice?: number | string | null;
  openUnitPrice?: number | null;
};

export type DetectedSaleMutationOperation = Omit<
  ActiveSaleMutationOperation,
  "label"
>;

export type SaleMutationAuthorizationRequirement = {
  code: string;
  label: string;
  authorization: SaleOperationAuthorization;
};

export type SaleMutationOperationAuthorizations = Record<
  string,
  SaleOperationCredentials
>;

export function detectSaleMutationOperations(
  lines: readonly SaleMutationLineState[],
  checkoutDiscountPercent = 0,
): DetectedSaleMutationOperation[] {
  const operations: DetectedSaleMutationOperation[] = [];
  if (lines.some((line) => line.quantity === -1)) {
    operations.push({ code: "MANUAL_RETURN_WITHOUT_TICKET" });
  }
  if (lines.some((line) => {
    const temporaryName = line.temporaryName?.trim();
    return Boolean(temporaryName) && temporaryName !== (line.catalogName ?? "");
  })) {
    operations.push({ code: "TEMPORARY_NAME" });
  }
  if (lines.some((line) => (
    line.openUnitPrice != null && Number(line.catalogUnitPrice ?? 0) !== 0
  ))) {
    operations.push({ code: "TEMPORARY_PRICE_CHANGE" });
  }
  if (lines.some((line) => (
    line.openUnitPrice != null && Number(line.catalogUnitPrice ?? 0) === 0
  ))) {
    operations.push({ code: "OPEN_PRICE_PRODUCT" });
  }
  const maximumLineDiscount = lines.reduce(
    (maximum, line) => Math.max(maximum, line.discountPercent),
    0,
  );
  if (maximumLineDiscount > 0) {
    operations.push({
      code: "APPLY_SALE_DISCOUNT",
      requestedDiscountPercent: maximumLineDiscount,
    });
  }
  if (checkoutDiscountPercent > 0) {
    operations.push({
      code: "APPLY_CHECKOUT_DISCOUNT",
      requestedDiscountPercent: checkoutDiscountPercent,
    });
  }
  return operations;
}

/**
 * Resolves the effective authorization for the operations currently present
 * in the cart. A discount ceiling only applies while the operation policy
 * requires permission; P0 deliberately ignores the user's personal ceiling.
 */
export function saleMutationAuthorizationRequirements(
  configuration: SalesOperationSecurityConfiguration | null | undefined,
  activeOperations: readonly ActiveSaleMutationOperation[],
  userPermissions: readonly string[],
  userDiscountLimit: number,
): SaleMutationAuthorizationRequirement[] | null {
  if (activeOperations.length === 0) return [];
  if (!configuration) return null;

  const deduplicated = new Map<string, ActiveSaleMutationOperation>();
  for (const operation of activeOperations) {
    const current = deduplicated.get(operation.code);
    if (!current) {
      deduplicated.set(operation.code, operation);
      continue;
    }
    const currentDiscount = current.requestedDiscountPercent ?? 0;
    const nextDiscount = operation.requestedDiscountPercent ?? 0;
    if (nextDiscount > currentDiscount) {
      deduplicated.set(operation.code, operation);
    }
  }

  const requirements: SaleMutationAuthorizationRequirement[] = [];
  for (const active of deduplicated.values()) {
    const policy = configuration.operations.find(
      (candidate) => candidate.code === active.code,
    );
    if (!policy) return null;

    let authorization = resolveSaleOperationAuthorization(policy, userPermissions);
    const requestedDiscount = active.requestedDiscountPercent;
    if (
      policy.requirePermission
      && requestedDiscount != null
      && requestedDiscount > userDiscountLimit
    ) {
      authorization = {
        mode: "DELEGATED",
        requireUsername: true,
        requirePassword: true,
      };
    }
    requirements.push({
      code: active.code,
      label: active.label,
      authorization,
    });
  }
  return requirements;
}

export function saleMutationCredentialsRequired(
  requirements: readonly SaleMutationAuthorizationRequirement[],
) {
  return requirements.filter(
    (requirement) => requirement.authorization.mode !== "DIRECT",
  );
}

export function saleWithOperationAuthorizations<T extends object>(
  sale: T,
  authorizations: SaleMutationOperationAuthorizations,
): T & { operationAuthorizations?: SaleMutationOperationAuthorizations } {
  return Object.keys(authorizations).length === 0
    ? sale
    : { ...sale, operationAuthorizations: authorizations };
}

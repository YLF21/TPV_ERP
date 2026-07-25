import { apiRequest } from "../api/client";
import { getHardwareBridge, type HardwareBridge } from "../hardware/hardware";

export type CashDrawerAuthorization = {
  operationId: string;
  authorizedBy: string;
  delegated: boolean;
  expiresAt: string;
};

type CashDrawerOpenInput = {
  terminalId: string;
  token?: string;
  authorizerUsername?: string;
  authorizerPassword?: string;
};

type ApiRequest = typeof apiRequest;

export class CashDrawerResultReportingError extends Error {
  constructor() {
    super("CASH_DRAWER_OPENED_BUT_RESULT_NOT_RECORDED");
    this.name = "CashDrawerResultReportingError";
  }
}

export async function executeAuthorizedCashDrawerOpen(
  input: CashDrawerOpenInput,
  request: ApiRequest = apiRequest,
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<CashDrawerAuthorization> {
  const authorization = await request<CashDrawerAuthorization>("/pos/cash-drawer/open-authorizations", {
    token: input.token,
    body: {
      terminalId: input.terminalId,
      ...(input.authorizerUsername ? { authorizerUsername: input.authorizerUsername } : {}),
      ...(input.authorizerPassword ? { authorizerPassword: input.authorizerPassword } : {}),
    }
  });
  const result = await hardware.openCashDrawer();
  try {
    await request(`/pos/cash-drawer/open-authorizations/${encodeURIComponent(authorization.operationId)}/result`, {
      token: input.token,
      body: result.ok
        ? { opened: true }
        : { opened: false, errorCode: result.code, errorMessage: result.message }
    });
  } catch (error) {
    if (result.ok) {
      throw new CashDrawerResultReportingError();
    }
    throw error;
  }
  if (!result.ok) {
    throw new Error(result.message);
  }
  return authorization;
}

import { apiRequest } from "@tpverp/app-common";

export type CashCurrentBalanceStatus = "ABIERTA" | "CERRADA" | "SIN_SESION";

export type CashCurrentBalance = {
  terminalId: string;
  terminalName: string;
  status: CashCurrentBalanceStatus;
  openingUserId: string | null;
  openingUserName: string | null;
  openingUsername: string | null;
  openedAt: string | null;
  expectedCash: number;
  lastActivityAt: string | null;
};

export type CashCurrentBalances = {
  asOf: string;
  timezone: string;
  terminals: CashCurrentBalance[];
};

export function loadCashCurrentBalances(token?: string) {
  return apiRequest<CashCurrentBalances>("/cash/current-balances", { token });
}

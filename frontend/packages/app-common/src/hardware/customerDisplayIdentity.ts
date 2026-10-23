import { apiRequest } from "../api/client";
import type { TerminalContext } from "../types";
import type { CustomerDisplayIdentity } from "./hardware";

export async function loadCustomerDisplayIdentity(
  token: string | undefined,
  terminalContext: TerminalContext,
): Promise<CustomerDisplayIdentity> {
  const fallback: CustomerDisplayIdentity = {
    storeName: terminalContext.storeName,
    companyName: terminalContext.companyName,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    currency: "EUR",
  };
  try {
    const identity = await apiRequest<CustomerDisplayIdentity>("/hardware/customer-display-identity", { token });
    return { ...fallback, ...identity };
  } catch {
    return fallback;
  }
}

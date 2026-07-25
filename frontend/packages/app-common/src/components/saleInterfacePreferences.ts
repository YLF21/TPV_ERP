import { apiRequest } from "../api/client";

export type SaleInterfaceMode = "KEYBOARD" | "TOUCH";

export type SaleInterfaceConfiguration = {
  terminalId: string;
  saleMode: SaleInterfaceMode;
};

export const defaultSaleInterfaceMode: SaleInterfaceMode = "KEYBOARD";

export function isSaleInterfaceMode(value: unknown): value is SaleInterfaceMode {
  return value === "KEYBOARD" || value === "TOUCH";
}

export async function loadSaleInterfaceConfiguration(
  token: string,
  request: typeof apiRequest = apiRequest
): Promise<SaleInterfaceConfiguration> {
  const configuration = await request<SaleInterfaceConfiguration>(
    "/terminal-configuration/interface",
    { token }
  );
  if (!configuration?.terminalId || !isSaleInterfaceMode(configuration.saleMode)) {
    throw new Error("invalid_terminal_interface_configuration");
  }
  return configuration;
}

export async function saveSaleInterfaceConfiguration(
  saleMode: SaleInterfaceMode,
  token: string,
  request: typeof apiRequest = apiRequest
): Promise<SaleInterfaceConfiguration> {
  return request<SaleInterfaceConfiguration>("/terminal-configuration/interface", {
    token,
    method: "PATCH",
    body: { saleMode }
  });
}

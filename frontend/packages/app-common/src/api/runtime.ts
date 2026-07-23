import type { TerminalContext } from "../types";

type ViteImportMeta = ImportMeta & {
  env?: Record<string, string | boolean | undefined>;
};

const env = (import.meta as ViteImportMeta).env ?? {};

export const apiBaseUrl = String(env.VITE_TPV_API_BASE_URL ?? "/api/v1").replace(/\/$/, "");
export const frontendVersion = String(env.VITE_TPV_APP_VERSION ?? "0.0.1");

export const devTerminalContext: TerminalContext = {
  storeName: String(env.VITE_TPV_STORE_NAME ?? "TIENDA DEMO"),
  terminalCode: String(env.VITE_TPV_TERMINAL_CODE ?? "SERVIDOR"),
  terminalId: env.VITE_TPV_TERMINAL_ID ? String(env.VITE_TPV_TERMINAL_ID) : undefined,
  terminalCredential: env.VITE_TPV_TERMINAL_CREDENTIAL
    ? String(env.VITE_TPV_TERMINAL_CREDENTIAL)
    : undefined
};

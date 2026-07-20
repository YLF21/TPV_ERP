import type { TerminalContext } from "../types";

type ViteImportMeta = ImportMeta & {
  env?: Record<string, string | boolean | undefined>;
};

const env = (import.meta as ViteImportMeta).env ?? {};

export const apiBaseUrl = String(env.VITE_TPV_API_BASE_URL ?? "/api/v1").replace(/\/$/, "");

export const devTerminalContext: TerminalContext = {
  storeName: String(env.VITE_TPV_STORE_NAME ?? "TIENDA DEMO"),
  terminalCode: String(env.VITE_TPV_TERMINAL_CODE ?? "SERVIDOR"),
  terminalId: String(env.VITE_TPV_TERMINAL_ID ?? "06d2ce45-8ead-349d-b844-4ecdead5e1ec"),
  terminalCredential: env.VITE_TPV_TERMINAL_CREDENTIAL
    ? String(env.VITE_TPV_TERMINAL_CREDENTIAL)
    : env.DEV ? "DEV-SERVER" : undefined
};

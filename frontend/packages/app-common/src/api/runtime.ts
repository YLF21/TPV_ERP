import type { TerminalContext } from "../types";
import { version as applicationVersion } from "../../../../package.json";
import { version as pdaApplicationVersion } from "../../../../apps/app-pda/package.json";

declare const __TPV_APP_KIND__: "venta" | "gestion" | "pda" | "test";

type ViteImportMeta = ImportMeta & {
  env?: Record<string, string | boolean | undefined>;
};

const env = (import.meta as ViteImportMeta).env ?? {};

export const apiBaseUrl = String(env.VITE_TPV_API_BASE_URL ?? "/api/v1").replace(/\/$/, "");
export const frontendVersion = typeof __TPV_APP_KIND__ !== "undefined" && __TPV_APP_KIND__ === "pda"
  ? pdaApplicationVersion : applicationVersion;

export const devTerminalContext: TerminalContext = {
  companyName: String(env.VITE_TPV_COMPANY_NAME ?? "EMPRESA DEMO"),
  storeName: String(env.VITE_TPV_STORE_NAME ?? "TIENDA DEMO"),
  terminalCode: String(env.VITE_TPV_TERMINAL_CODE ?? "SERVIDOR"),
  terminalId: env.VITE_TPV_TERMINAL_ID ? String(env.VITE_TPV_TERMINAL_ID) : undefined,
  terminalCredential: env.VITE_TPV_TERMINAL_CREDENTIAL
    ? String(env.VITE_TPV_TERMINAL_CREDENTIAL)
    : undefined
};

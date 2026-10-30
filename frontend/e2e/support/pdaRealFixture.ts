import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { APIRequestContext, APIResponse } from "@playwright/test";

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const pdaRealFixturePath = path.resolve(frontendRoot, "../output/playwright/pda-real-fixture.json");
export const realPdaBackendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18082";
export const realPdaApiUrl = `${realPdaBackendUrl}/api/v1`;
export const realPdaServerTerminalId = process.env.E2E_TERMINAL_ID ?? "06d2ce45-8ead-349d-b844-4ecdead5e1ec";
export const realPdaServerCredential = process.env.E2E_TERMINAL_CREDENTIAL ?? "DEV-SERVER";

export type PdaRealFixture = {
  marker: string;
  roleId: string;
  userId: string;
  userName: string;
  userPassword: string;
  pdaTerminalId: string;
  pairingCode: string;
  warehouseId: string;
  warehouseName: string;
  stockQuantity: number;
  productId: string;
  productCode: string;
  productName: string;
  workId: string;
  workTitle: string;
};

export function readPdaRealFixture() {
  return JSON.parse(fs.readFileSync(pdaRealFixturePath, "utf8")) as PdaRealFixture;
}

export function writePdaRealFixture(fixture: PdaRealFixture) {
  fs.mkdirSync(path.dirname(pdaRealFixturePath), { recursive: true });
  fs.writeFileSync(pdaRealFixturePath, `${JSON.stringify(fixture, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
}

export function removePdaRealFixture() {
  fs.rmSync(pdaRealFixturePath, { force: true });
}

export function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

export async function requireOk(response: APIResponse, operation: string) {
  if (response.ok()) return response;
  throw new Error(`${operation}: HTTP ${response.status()} ${await response.text()}`);
}

export async function adminLogin(request: APIRequestContext) {
  const response = await request.post(`${realPdaApiUrl}/auth/login`, {
    data: {
      terminalId: realPdaServerTerminalId,
      terminalCredential: realPdaServerCredential,
      userName: "ADMIN",
      password: "0000"
    }
  });
  await requireOk(response, "inicio de sesión ADMIN en la base E2E aislada");
  return response.json() as Promise<{ accessToken: string; userId: string }>;
}

export async function apiJson<T>(
  request: APIRequestContext,
  token: string | null,
  method: "get" | "post" | "put" | "patch" | "delete",
  pathName: string,
  data?: unknown
) {
  const response = await request[method](`${realPdaApiUrl}${pathName}`, {
    ...(token ? { headers: bearer(token) } : {}),
    ...(data === undefined ? {} : { data })
  });
  await requireOk(response, `${method.toUpperCase()} ${pathName}`);
  if (response.status() === 204) return undefined as T;
  return response.json() as Promise<T>;
}

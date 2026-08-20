import { request, type APIRequestContext } from "@playwright/test";
import {
  apiUrl,
  authorization,
  terminalCredential,
  terminalId
} from "./support/testApi";

const readinessTimeoutMs = 60_000;
const retryDelayMs = 500;

export default async function waitForOperationalDemoData() {
  const context = await request.newContext();
  const deadline = Date.now() + readinessTimeoutMs;
  let lastFailure = "backend_unavailable";

  try {
    while (Date.now() < deadline) {
      const result = await operationalReadiness(context);
      if (result.ready) return;
      lastFailure = result.failure;
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  } finally {
    await context.dispose();
  }

  throw new Error(`Los datos demo E2E no quedaron preparados: ${lastFailure}`);
}

async function operationalReadiness(context: APIRequestContext) {
  try {
    const login = await context.post(`${apiUrl}/auth/login`, {
      data: {
        terminalId,
        terminalCredential,
        userName: process.env.E2E_ADMIN_USERNAME ?? "ADMIN",
        password: process.env.E2E_ADMIN_PASSWORD ?? "0000"
      }
    });
    if (!login.ok()) {
      return { ready: false, failure: `login HTTP ${login.status()} ${await login.text()}` };
    }

    const session = await login.json() as { accessToken: string };
    const warehouses = await context.get(`${apiUrl}/warehouses`, {
      headers: authorization(session.accessToken)
    });
    if (!warehouses.ok()) {
      return {
        ready: false,
        failure: `almacenes HTTP ${warehouses.status()} ${await warehouses.text()}`
      };
    }

    const body = await warehouses.json() as unknown;
    return Array.isArray(body) && body.length > 1
      ? { ready: true, failure: "" }
      : { ready: false, failure: "el catálogo de almacenes todavía está incompleto" };
  } catch (error) {
    return {
      ready: false,
      failure: error instanceof Error ? error.message : String(error)
    };
  }
}

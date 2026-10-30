import { defineConfig, devices } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

if (process.env.E2E_PDA_REAL_DISPOSABLE !== "true") {
  throw new Error(
    "Activa explícitamente E2E_PDA_REAL_DISPOSABLE=true para ejecutar el E2E PDA contra backend real."
  );
}

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18082";
const pdaUrl = process.env.E2E_PDA_URL ?? "http://127.0.0.1:4177";
const databaseName = process.env.E2E_DB_NAME ?? "tpv_erp_e2e_pda";
const databaseHost = process.env.E2E_DB_HOST ?? "127.0.0.1";
const databasePort = process.env.E2E_DB_PORT ?? "55432";
const psqlPath = process.env.E2E_PSQL_PATH
  ?? (fs.existsSync("E:/postgreSQL/bin/psql.exe") ? "E:/postgreSQL/bin/psql.exe" : "psql");

if (!/^tpv_erp_e2e_pda(?:_[a-z0-9_]+)?$/.test(databaseName)) {
  throw new Error(`La base ${databaseName} no pertenece al espacio desechable PDA E2E`);
}

// Global setup, workers y teardown deben compartir exactamente el mismo
// backend y la misma base aislada, aunque el llamador no defina las variables.
process.env.E2E_BACKEND_URL = backendUrl;
process.env.E2E_PDA_URL = pdaUrl;
process.env.E2E_DB_NAME = databaseName;
process.env.E2E_PSQL_PATH = psqlPath;
process.env.E2E_DB_HOST = databaseHost;
process.env.E2E_DB_PORT = databasePort;

export default defineConfig({
  testDir: "./e2e",
  testMatch: "app-pda-real-backend.spec.ts",
  globalSetup: "./e2e/pda-real.global-setup.ts",
  globalTeardown: "./e2e/pda-real.global-teardown.ts",
  outputDir: "../output/playwright/pda-real-test-results",
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: "../output/playwright/pda-real-report" }]
  ],
  use: {
    ...devices["Desktop Chrome"],
    baseURL: pdaUrl,
    locale: "es-ES",
    viewport: { width: 390, height: 844 },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "retain-on-failure"
  },
  webServer: [
    {
      command: "node e2e/support/start-real-pda-backend.mjs",
      cwd: frontendRoot,
      env: {
        ...process.env,
        E2E_PDA_REAL_DISPOSABLE: "true",
        E2E_BACKEND_URL: backendUrl,
        E2E_DB_NAME: databaseName,
        E2E_PSQL_PATH: psqlPath,
        E2E_DB_HOST: databaseHost,
        E2E_DB_PORT: databasePort
      },
      url: `${backendUrl}/actuator/health/readiness`,
      reuseExistingServer: false,
      timeout: 420_000
    },
    {
      command: "npm run dev --workspace @tpverp/app-pda -- --host 127.0.0.1 --port 4177 --strictPort",
      cwd: frontendRoot,
      env: { ...process.env, VITE_TPV_BACKEND_URL: backendUrl },
      url: pdaUrl,
      reuseExistingServer: false,
      timeout: 60_000
    }
  ]
});

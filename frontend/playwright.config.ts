import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(frontendRoot, "..");
const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
const ventaUrl = process.env.E2E_VENTA_URL ?? "http://127.0.0.1:4173";
const gestionUrl = process.env.E2E_GESTION_URL ?? "http://127.0.0.1:4174";

export default defineConfig({
  testDir: "./e2e",
  outputDir: "../output/playwright/test-results",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: "../output/playwright/report" }]
  ],
  use: {
    ...devices["Desktop Chrome"],
    baseURL: ventaUrl,
    locale: "es-ES",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "retain-on-failure"
  },
  webServer: [
    {
      command: ".\\mvnw.cmd -q spring-boot:run",
      cwd: path.join(repositoryRoot, "backend"),
      env: {
        ...process.env,
        TPV_SERVER_ADDRESS: "127.0.0.1",
        TPV_SERVER_PORT: new URL(backendUrl).port || "18080",
        TPV_DB_USERNAME: process.env.E2E_DB_USERNAME ?? process.env.TPV_DB_USERNAME ?? "tpv_erp",
        TPV_DB_PASSWORD: process.env.E2E_DB_PASSWORD ?? process.env.TPV_DB_PASSWORD ?? "admin"
      },
      url: `${backendUrl}/actuator/health`,
      reuseExistingServer: true,
      timeout: 120_000
    },
    {
      command: "npm run dev --workspace @tpverp/app-venta -- --host 127.0.0.1 --port 4173 --strictPort",
      cwd: frontendRoot,
      env: { ...process.env, VITE_TPV_BACKEND_URL: backendUrl },
      url: ventaUrl,
      reuseExistingServer: true,
      timeout: 60_000
    },
    {
      command: "npm run dev --workspace @tpverp/app-gestion -- --host 127.0.0.1 --port 4174 --strictPort",
      cwd: frontendRoot,
      env: { ...process.env, VITE_TPV_BACKEND_URL: backendUrl },
      url: gestionUrl,
      reuseExistingServer: true,
      timeout: 60_000
    }
  ]
});

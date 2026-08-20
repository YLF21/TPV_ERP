import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(frontendRoot, "..");
const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
const ventaUrl = process.env.E2E_VENTA_URL ?? "http://127.0.0.1:4173";
const gestionUrl = process.env.E2E_GESTION_URL ?? "http://127.0.0.1:4174";
const reuseExternalServers = process.env.E2E_REUSE_EXTERNAL_SERVERS === "true";
const backendMavenCommand = process.env.E2E_MAVEN_COMMAND ?? ".\\mvnw.cmd";
const terminalId = process.env.E2E_TERMINAL_ID ?? "06d2ce45-8ead-349d-b844-4ecdead5e1ec";
const terminalCredential = process.env.E2E_TERMINAL_CREDENTIAL ?? "DEV-SERVER";

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
  webServer: reuseExternalServers ? undefined : [
    {
      command: `${backendMavenCommand} --batch-mode spring-boot:run`,
      cwd: path.join(repositoryRoot, "backend"),
      env: {
        ...process.env,
        SPRING_PROFILES_ACTIVE: process.env.SPRING_PROFILES_ACTIVE ?? "dev",
        TPV_SERVER_ADDRESS: "127.0.0.1",
        TPV_SERVER_PORT: new URL(backendUrl).port || "18080",
        TPV_DB_USERNAME: process.env.E2E_DB_USERNAME ?? process.env.TPV_DB_USERNAME ?? "tpv_erp",
        TPV_DB_PASSWORD: process.env.E2E_DB_PASSWORD ?? process.env.TPV_DB_PASSWORD ?? "admin"
      },
      stdout: "pipe",
      stderr: "pipe",
      url: `${backendUrl}/api/v1/installation/status`,
      reuseExistingServer: true,
      timeout: 120_000
    },
    {
      command: "npm run dev --workspace @tpverp/app-venta -- --host 127.0.0.1 --port 4173 --strictPort",
      cwd: frontendRoot,
      env: {
        ...process.env,
        VITE_TPV_BACKEND_URL: backendUrl,
        VITE_TPV_TERMINAL_ID: terminalId,
        VITE_TPV_TERMINAL_CREDENTIAL: terminalCredential
      },
      url: ventaUrl,
      reuseExistingServer: true,
      timeout: 60_000
    },
    {
      command: "npm run dev --workspace @tpverp/app-gestion -- --host 127.0.0.1 --port 4174 --strictPort",
      cwd: frontendRoot,
      env: {
        ...process.env,
        VITE_TPV_BACKEND_URL: backendUrl,
        VITE_TPV_TERMINAL_ID: terminalId,
        VITE_TPV_TERMINAL_CREDENTIAL: terminalCredential
      },
      url: gestionUrl,
      reuseExistingServer: true,
      timeout: 60_000
    }
  ]
});

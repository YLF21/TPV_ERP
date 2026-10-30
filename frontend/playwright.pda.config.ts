import { defineConfig, devices } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const pdaUrl = process.env.E2E_PDA_URL ?? "http://127.0.0.1:4176";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "app-pda-readiness.spec.ts",
  outputDir: "../output/playwright/pda-test-results",
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: "../output/playwright/pda-report" }]
  ],
  use: {
    ...devices["Desktop Chrome"],
    baseURL: pdaUrl,
    locale: "es-ES",
    screenshot: "only-on-failure",
    trace: "retain-on-failure"
  },
  webServer: {
    command: "npm run dev --workspace @tpverp/app-pda -- --host 127.0.0.1 --port 4176 --strictPort",
    cwd: frontendRoot,
    env: { ...process.env, VITE_TPV_BACKEND_URL: "http://127.0.0.1:18080" },
    url: pdaUrl,
    reuseExistingServer: true,
    timeout: 60_000
  }
});

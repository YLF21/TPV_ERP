import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  // Server-only setting: never expose internal API addresses or credentials in the bundle.
  const env = loadEnv(mode, ".", "PRODUCT_");
  const target = env.PRODUCT_API_PROXY_TARGET?.trim() || "http://127.0.0.1:8090";
  if (!/^https?:\/\/(?:\[[\da-f:]+\]|[\da-z](?:[\da-z.-]*[\da-z])?)(?::\d{1,5})?\/?$/i.test(target)) {
    throw new Error("PRODUCT_API_PROXY_TARGET must be an HTTP(S) origin without credentials");
  }
  const proxy = {
    // Keep the browser-visible Host aligned with Origin for Spring's same-origin checks.
    "/api/v1/public/demo-requests": { target: target.replace(/\/$/, ""), changeOrigin: false, timeout: 20_000, proxyTimeout: 20_000 }
  };
  return {
    plugins: [react()],
    server: { host: "127.0.0.1", port: 5176, strictPort: true, proxy },
    preview: { host: "127.0.0.1", port: 4176, strictPort: true, proxy }
  };
});

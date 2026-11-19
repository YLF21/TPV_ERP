import { defineConfig, mergeConfig } from "vite";
import desktopConfig from "./vite.config.ts";

// A separate output prevents shipping local Electron terminal credentials or
// replacing a packaged desktop build with the browser distribution.
export default mergeConfig(desktopConfig, defineConfig({
  envDir: false,
  envPrefix: "ESPOS_WEB_PUBLIC_",
  define: {
    "import.meta.env.VITE_TPV_API_BASE_URL": JSON.stringify("/api/v1"),
    "import.meta.env.VITE_TPV_TERMINAL_ID": "undefined",
    "import.meta.env.VITE_TPV_TERMINAL_CREDENTIAL": "undefined",
    "import.meta.env.VITE_TPV_WEB_TERMINAL": JSON.stringify("true")
  },
  build: {
    outDir: "dist-web",
    sourcemap: false
  },
  preview: {
    host: "127.0.0.1",
    port: 5177,
    strictPort: true,
    headers: {
      "Cache-Control": "no-store",
      "Content-Security-Policy": "default-src 'self'; connect-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Referrer-Policy": "no-referrer"
    },
    proxy: {
      "/api/v1": {
        target: process.env.VITE_TPV_BACKEND_URL ?? "http://127.0.0.1:8080",
        changeOrigin: false
      }
    }
  }
}));

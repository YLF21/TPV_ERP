import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import { backendAddressPlugin } from "../../scripts/vite-backend-address.mjs";

export default defineConfig({
  plugins: [react(), backendAddressPlugin()],
  define: {
    __TPV_APP_KIND__: JSON.stringify("pda")
  },
  resolve: {
    alias: {
      "@tpverp/app-common": fileURLToPath(new URL("../../packages/app-common/src/index.ts", import.meta.url))
    }
  },
  build: {
    outDir: "dist",
    rollupOptions: {
      output: {
        manualChunks(id) {
          const normalized = id.replace(/\\/g, "/");
          const messages = normalized.match(/\/i18n\/Messages(Es|En|Zh)\.ts$/);
          if (messages) return `messages-${messages[1].toLowerCase()}`;
          if (normalized.includes("node_modules/react") || normalized.includes("node_modules/scheduler")) {
            return "react-vendor";
          }
          return undefined;
        }
      }
    }
  },
  server: {
    proxy: {
      "/api/v1": {
        target: process.env.VITE_TPV_BACKEND_URL ?? "http://127.0.0.1:8080",
        changeOrigin: true
      }
    }
  },
  preview: {
    proxy: {
      "/api/v1": {
        target: process.env.VITE_TPV_BACKEND_URL ?? "http://127.0.0.1:8080",
        changeOrigin: true
      }
    }
  }
});

import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  define: {
    __TPV_APP_KIND__: JSON.stringify("venta")
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
          // Keep language dictionaries independently cacheable instead of
          // rebuilding the startup monolith on every UI change.
          const messages = id.replace(/\\/g, "/").match(/\/i18n\/Messages(Es|En|Zh)\.ts$/);
          if (messages) return `messages-${messages[1].toLowerCase()}`;
          if (id.includes("node_modules/react") || id.includes("node_modules/scheduler")) {
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
        target: process.env.VITE_TPV_BACKEND_URL ?? "http://localhost:8080",
        changeOrigin: true
      }
    }
  }
});

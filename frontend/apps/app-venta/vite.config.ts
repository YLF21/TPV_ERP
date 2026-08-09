import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
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

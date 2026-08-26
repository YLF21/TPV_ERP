import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  define: {
    __TPV_APP_KIND__: JSON.stringify("gestion")
  },
  resolve: {
    alias: {
      "@tpverp/app-common": fileURLToPath(new URL("../../packages/app-common/src/index.ts", import.meta.url))
    }
  },
  build: {
    outDir: "dist",
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [{
            name: "app",
            tags: ["$initial"],
            maxSize: 500_000,
            includeDependenciesRecursively: false
          }]
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

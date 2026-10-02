import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  build: {
    rollupOptions: {
      input: { admin: "index.html", tienda: "tienda.html" }
    }
  },
  plugins: [react()],
  cacheDir: "vite-cache",
  server: {
    port: 5175,
    proxy: {
      "/api": {
        target: "http://localhost:8090",
        changeOrigin: true
      }
    }
  }
});

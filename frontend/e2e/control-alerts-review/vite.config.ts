import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// No proxy: this review never connects to the store backend.
export default defineConfig({ plugins: [react()], publicDir: "../../../output/control-alerts-review", server: { host: "127.0.0.1", port: 5182, strictPort: true } });

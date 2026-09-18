import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Every API response is simulated in main.tsx. Never proxy to the store backend.
export default defineConfig({ plugins: [react()], publicDir: "../../../output/dashboard-review", server: { host: "127.0.0.1", port: 5183, strictPort: true } });

import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Standalone review fixture: never proxies requests to a real terminal/backend.
export default defineConfig({ plugins: [react()], server: { host: "127.0.0.1", port: 5181, strictPort: true } });

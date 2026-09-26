import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Aislado: este fixture no configura proxy ni conecta con terminales/backend.
export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", port: 5187, strictPort: true }
});

import electron from "electron";
import { spawn } from "node:child_process";
import http from "node:http";
import { resolve } from "node:path";

const appKey = process.argv[2];
const configs = {
  venta: {
    name: "APP VENTA",
    workspace: "@tpverp/app-venta",
    port: 5173
  },
  gestion: {
    name: "APP GESTION",
    workspace: "@tpverp/app-gestion",
    port: 5174
  }
};

const config = configs[appKey];
if (!config) {
  throw new Error("Uso: node scripts/start-electron-app.mjs venta|gestion");
}

const root = resolve(import.meta.dirname, "..");
const url = `http://127.0.0.1:${config.port}`;

if (await canConnect(url)) {
  throw new Error(`El puerto ${config.port} ya esta en uso. Cierra la instancia anterior de ${config.name} antes de iniciar otra.`);
}

const vite = spawn(process.env.ComSpec || "cmd.exe", ["/d", "/s", "/c", `npm run dev --workspace ${config.workspace}`], {
  cwd: root,
  stdio: "inherit"
});

await waitFor(url);

const desktop = spawn(electron, [resolve(root, "desktop", "main.cjs")], {
  cwd: root,
  stdio: "inherit",
  env: {
    ...process.env,
    TPV_DESKTOP_APP_NAME: config.name,
    TPV_DESKTOP_APP_URL: url
  }
});

desktop.on("exit", (code) => {
  vite.kill();
  process.exit(code ?? 0);
});

process.on("SIGINT", () => {
  desktop.kill();
  vite.kill();
});

async function waitFor(targetUrl) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await canConnect(targetUrl)) {
      return;
    }
    await new Promise((resolveTimeout) => setTimeout(resolveTimeout, 250));
  }
  throw new Error(`No se pudo iniciar Vite en ${targetUrl}`);
}

function canConnect(targetUrl) {
  return new Promise((resolveResult) => {
    const request = http.get(targetUrl, (response) => {
      response.resume();
      resolveResult(response.statusCode >= 200 && response.statusCode < 500);
    });
    request.on("error", () => resolveResult(false));
    request.setTimeout(500, () => {
      request.destroy();
      resolveResult(false);
    });
  });
}

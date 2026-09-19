import http from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { backendAddressPlugin } from "./vite-backend-address.mjs";

const servers = [];

afterEach(async () => {
  while (servers.length) {
    const server = servers.pop();
    await new Promise((resolve) => server.close(resolve));
  }
});

async function startMetadataServer({ env = {}, proxy, preview = false } = {}) {
  let middleware;
  const config = { env, server: { proxy }, preview: { proxy } };
  const server = { config, middlewares: { use(handler) { middleware = handler; } } };
  const plugin = backendAddressPlugin();
  if (preview) plugin.configurePreviewServer(server); else plugin.configureServer(server);
  const httpServer = http.createServer((req, res) => middleware(req, res, () => {
    res.statusCode = 404;
    res.end();
  }));
  servers.push(httpServer);
  await new Promise((resolve) => httpServer.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${httpServer.address().port}`;
}

function request(url, method = "GET") {
  return new Promise((resolve, reject) => {
    const req = http.request(url, { method }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => resolve({
        status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString("utf8")
      }));
    });
    req.on("error", reject);
    req.end();
  });
}

describe("Vite backend address metadata", () => {
  it.each([false, true])("uses the actual proxy target in development and preview (preview=%s)", async (preview) => {
    const origin = await startMetadataServer({ proxy: { "/api/v1": { target: "https://192.0.2.50:8443" } }, preview });
    const response = await request(`${origin}/__tpv/backend-address?target=http://127.0.0.1:8080`);
    expect(response.status).toBe(200);
    expect(JSON.parse(response.body)).toEqual({ backendLabel: "192.0.2.50" });
    expect(response.headers["cache-control"]).toBe("no-store");
  });

  it("uses an absolute API base URL instead of the unused proxy target", async () => {
    const origin = await startMetadataServer({
      env: { VITE_TPV_API_BASE_URL: "https://192.0.2.60:8443/api/v1" },
      proxy: { "/api/v1": { target: "http://127.0.0.1:8080" } }
    });
    expect(JSON.parse((await request(`${origin}/__tpv/backend-address`)).body)).toEqual({ backendLabel: "192.0.2.60" });
  });

  it("reports unknown without an API target and does not infer LOCAL from a relative API URL", async () => {
    const origin = await startMetadataServer();
    expect(JSON.parse((await request(`${origin}/__tpv/backend-address`)).body)).toEqual({ backendLabel: null });
    expect((await request(`${origin}/__tpv/backend-address`, "POST")).status).toBe(405);
    expect((await request(`${origin}/unrelated`)).status).toBe(404);
  });
});

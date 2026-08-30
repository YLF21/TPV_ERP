import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { createDesktopServer, safeStaticPath } from "./loopback-server.cjs";

const resources = [];

function request(url, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, options, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString("utf8") }));
    });
    req.on("error", reject);
    if (options.body) req.end(options.body); else req.end();
  });
}

afterEach(async () => {
  while (resources.length) {
    const resource = resources.pop();
    await resource.close?.();
    if (resource.root) fs.rmSync(resource.root, { recursive: true, force: true });
  }
});

describe("desktop loopback server", () => {
  it("serves the SPA from loopback with security headers and fallback routing", async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-desktop-test-"));
    fs.writeFileSync(path.join(root, "index.html"), "<!doctype html><title>APP</title>");
    const desktop = createDesktopServer({ staticRoot: root, backendUrl: "http://127.0.0.1:1" });
    resources.push({ root, close: desktop.close });
    const origin = await desktop.start();
    const response = await request(`${origin}/ventas/123`);
    expect(response.status).toBe(200);
    expect(response.body).toContain("APP");
    expect(response.headers["content-security-policy"]).toContain("default-src 'self'");
    expect(response.headers["x-content-type-options"]).toBe("nosniff");
    expect(response.headers["cache-control"]).toBe("no-store");
  });

  it("streams API requests to the configured backend without buffering the response", async () => {
    const backend = http.createServer((req, res) => {
      expect(req.url).toBe("/api/v1/export?format=csv");
      expect(req.headers.origin).toMatch(/^http:\/\/127\.0\.0\.1(?::\d+)?$/);
      res.writeHead(200, { "content-type": "text/csv" });
      res.write("row-1\n");
      setTimeout(() => res.end("row-2\n"), 5);
    });
    await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
    const port = backend.address().port;
    resources.push({ close: () => new Promise((resolve) => backend.close(resolve)) });
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-desktop-test-"));
    fs.writeFileSync(path.join(root, "index.html"), "ok");
    const desktop = createDesktopServer({ staticRoot: root, backendUrl: `http://127.0.0.1:${port}` });
    resources.push({ root, close: desktop.close });
    const origin = await desktop.start();
    const response = await request(`${origin}/api/v1/export?format=csv`);
    expect(response.status).toBe(200);
    expect(response.body).toBe("row-1\nrow-2\n");
    expect(response.headers["cache-control"]).toBe("no-store");
  });

  it("rejects traversal and oversized requests", async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-desktop-test-"));
    fs.writeFileSync(path.join(root, "index.html"), "ok");
    const desktop = createDesktopServer({ staticRoot: root, backendUrl: "http://127.0.0.1:1", maxRequestBytes: 2 });
    resources.push({ root, close: desktop.close });
    const origin = await desktop.start();
    expect(safeStaticPath(root, "/../../windows.ini")).toBeNull();
    expect((await request(`${origin}/api/v1/test`, { method: "POST", headers: { "content-length": "3" }, body: "abc" })).status).toBe(413);
  });
});

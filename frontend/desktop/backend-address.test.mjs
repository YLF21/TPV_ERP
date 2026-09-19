import { describe, expect, it, vi } from "vitest";
import { createBackendAddressResolver } from "./backend-address.cjs";

const networkInterfaces = () => ({
  ethernet: [{ address: "192.168.1.20" }, { address: "2001:db8::20" }],
  loopback: [{ address: "127.0.0.1" }, { address: "::1" }]
});

describe("configured backend address", () => {
  it.each([
    "http://127.44.22.1:8080", "http://[::1]:8080",
    "http://[::ffff:127.0.0.1]:8080", "https://192.168.1.20:8443",
    "https://[2001:0db8:0:0:0:0:0:20]:8443"
  ])("identifies a local backend for a local terminal: %s", async (url) => {
    const resolve = createBackendAddressResolver(url, { networkInterfaces });
    expect(await resolve("::ffff:127.0.0.1", "127.0.0.1")).toEqual({ backendLabel: "LOCAL" });
  });

  it("resolves the configured hostname to an IP without disclosing its URL", async () => {
    const lookup = vi.fn().mockResolvedValue({ address: "192.168.1.99", family: 4 });
    const resolve = createBackendAddressResolver("https://backend.example:8443/api/v1?private=value", { lookup, networkInterfaces });
    expect(await resolve("127.0.0.1", "127.0.0.1")).toEqual({ backendLabel: "192.168.1.99" });
    expect(lookup).toHaveBeenCalledWith("backend.example");
  });

  it("recognizes a hostname resolving to an interface on the terminal", async () => {
    const resolve = createBackendAddressResolver("https://store.example", {
      lookup: async () => ({ address: "192.168.1.20", family: 4 }), networkInterfaces
    });
    expect(await resolve("127.0.0.1", "127.0.0.1")).toEqual({ backendLabel: "LOCAL" });
  });

  it("shows the dev server's LAN address to a remote browser when the backend is loopback", async () => {
    const resolve = createBackendAddressResolver("http://127.0.0.1:8080", { networkInterfaces });
    expect(await resolve("192.168.1.30", "::ffff:192.168.1.20")).toEqual({ backendLabel: "192.168.1.20" });
  });

  it("compares remote browser and backend addresses rather than the proxy's machine", async () => {
    const resolve = createBackendAddressResolver("https://192.168.1.30", { networkInterfaces });
    expect(await resolve("::ffff:192.168.1.30", "192.168.1.20")).toEqual({ backendLabel: "LOCAL" });
    expect(await resolve("192.168.1.40", "192.168.1.20")).toEqual({ backendLabel: "192.168.1.30" });
  });

  it("treats an absolute loopback API URL as local to the browser, including remote browsers", async () => {
    const resolve = createBackendAddressResolver("http://127.0.0.1:8080/api/v1", {
      networkInterfaces, directConnection: true
    });
    expect(await resolve("192.168.1.30", "192.168.1.20")).toEqual({ backendLabel: "LOCAL" });
  });

  it("caches DNS lookups for a bounded time and then reflects address changes", async () => {
    let time = 0;
    const lookup = vi.fn()
      .mockResolvedValueOnce({ address: "192.168.1.30", family: 4 })
      .mockResolvedValueOnce({ address: "192.168.1.40", family: 4 });
    const resolve = createBackendAddressResolver("https://store.example", {
      lookup, networkInterfaces, now: () => time, cacheMs: 15_000
    });
    expect(await resolve("127.0.0.1")).toEqual({ backendLabel: "192.168.1.30" });
    expect(await resolve("127.0.0.1")).toEqual({ backendLabel: "192.168.1.30" });
    expect(lookup).toHaveBeenCalledTimes(1);
    time = 15_001;
    expect(await resolve("127.0.0.1")).toEqual({ backendLabel: "192.168.1.40" });
  });

  it("reports unknown on invalid configuration, DNS failure or timeout", async () => {
    const invalid = createBackendAddressResolver(undefined, { networkInterfaces });
    const failed = createBackendAddressResolver("https://missing.example", {
      lookup: async () => { throw new Error("ENOTFOUND"); }, networkInterfaces
    });
    const stalled = createBackendAddressResolver("https://slow.example", {
      lookup: () => new Promise(() => {}), timeoutMs: 5, networkInterfaces
    });
    expect(await invalid("127.0.0.1")).toEqual({ backendLabel: null });
    expect(await failed("127.0.0.1")).toEqual({ backendLabel: null });
    expect(await stalled("127.0.0.1")).toEqual({ backendLabel: null });
  });
});

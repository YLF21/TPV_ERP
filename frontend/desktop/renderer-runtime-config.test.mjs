import { describe, expect, it } from "vitest";
import { resolveRendererAppUrl } from "./renderer-runtime-config.cjs";

describe("desktop renderer runtime URL", () => {
  it("always uses the generated loopback URL when packaged", () => {
    expect(resolveRendererAppUrl({
      isPackaged: true,
      envValue: "https://untrusted.example.test",
      localUrl: "http://127.0.0.1:43210"
    })).toBe("http://127.0.0.1:43210");
    expect(() => resolveRendererAppUrl({ isPackaged: true, localUrl: "https://backend.example.test" })).toThrow(/loopback/);
  });

  it("requires an explicit development opt-in for remote renderer URLs", () => {
    expect(resolveRendererAppUrl({ isPackaged: false, envValue: "http://127.0.0.1:5173" })).toBe("http://127.0.0.1:5173");
    expect(() => resolveRendererAppUrl({ isPackaged: false, envValue: "https://dev.example.test" })).toThrow(/desarrollo explícito/);
    expect(resolveRendererAppUrl({
      isPackaged: false,
      envValue: "https://dev.example.test",
      allowRemoteDevelopment: true
    })).toBe("https://dev.example.test");
  });
});

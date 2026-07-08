import { describe, expect, it } from "vitest";
import { resolveViteStartup } from "./start-electron-app.mjs";

describe("resolveViteStartup", () => {
  it("reuses an existing Vite server for Electron", () => {
    expect(resolveViteStartup(true)).toEqual({
      shouldStartVite: false,
      ownsViteProcess: false
    });
  });

  it("starts Vite when no server is running", () => {
    expect(resolveViteStartup(false)).toEqual({
      shouldStartVite: true,
      ownsViteProcess: true
    });
  });
});

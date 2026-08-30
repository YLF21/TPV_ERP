import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { APP_CONFIGS, DESKTOP_APP_VERSION, getDesktopAppConfig } from "./app-config.cjs";
import { DEFAULT_BACKEND_URL, productionBackendConfigPath, readBackendConfig, resolveBackendConfig, resolveBackendUrl, validateBackendUrl } from "./backend-config.cjs";

const temporaryFiles = [];

afterEach(() => {
  while (temporaryFiles.length) fs.rmSync(temporaryFiles.pop(), { force: true, recursive: true });
});

describe("production desktop app contracts", () => {
  it("selects separate versioned Venta and Gestión entrypoints", () => {
    expect(DESKTOP_APP_VERSION).toBe("4.2.0");
    expect(getDesktopAppConfig("venta")).toMatchObject({ main: "desktop/main-venta.cjs", windowMode: "FULLSCREEN" });
    expect(getDesktopAppConfig("gestion")).toMatchObject({ main: "desktop/main-gestion.cjs", windowMode: "MAXIMIZED" });
    expect(Object.keys(APP_CONFIGS)).toEqual(["venta", "gestion"]);
  });

  it("defaults to loopback and accepts only origin HTTP(S) backend URLs", () => {
    expect(resolveBackendUrl()).toBe(DEFAULT_BACKEND_URL);
    expect(validateBackendUrl("https://backend.example.test:8443", {
      allowedHosts: ["backend.example.test"]
    })).toBe("https://backend.example.test:8443");
    expect(() => validateBackendUrl("https://backend.example.test:8443")).toThrow(/allowlist/);
    expect(() => validateBackendUrl("http://backend.example.test:8080", {
      allowedHosts: ["backend.example.test"]
    })).toThrow(/HTTPS/);
    expect(() => validateBackendUrl("file:///tmp/backend")).toThrow();
    expect(() => validateBackendUrl("http://user:pass@127.0.0.1:8080")).toThrow();
    expect(() => validateBackendUrl("http://127.0.0.1:8080/api/v1")).toThrow();
  });

  it("takes a remote HTTPS host allowlist from the installer-managed config", () => {
    const configPath = path.join(os.tmpdir(), `tpv-backend-config-${process.pid}-${Date.now()}.json`);
    temporaryFiles.push(configPath);
    fs.writeFileSync(configPath, JSON.stringify({
      backendUrl: "https://backend.example.test:8443",
      allowedHosts: ["backend.example.test"]
    }));
    expect(resolveBackendUrl({ configPath })).toBe("https://backend.example.test:8443");
    expect(resolveBackendConfig({ configPath })).toMatchObject({
      backendUrl: "https://backend.example.test:8443",
      allowedHosts: ["backend.example.test"]
    });
  });

  it("uses a fixed ProgramData production path and rejects non-regular config paths", () => {
    expect(productionBackendConfigPath("C:\\ProgramData")).toBe("C:\\ProgramData\\TPV ERP\\desktop\\backend-config.json");
    const directoryPath = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-backend-directory-"));
    temporaryFiles.push(directoryPath);
    expect(() => readBackendConfig(directoryPath)).toThrow(/fichero regular/);
  });
});

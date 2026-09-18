import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createPackage } from "@electron/asar";
import { afterEach, describe, expect, it, vi } from "vitest";
import desktopBuild from "../build/desktop-build-config.cjs";
import { validateDirectory, verifyAuthenticode, verifyChecksum } from "./check-desktop-packages.mjs";

const temporary = [];
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-desktop-unit-"));
  temporary.push(root);
  return root;
}
function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value));
}
afterEach(async () => {
  for (const root of temporary.splice(0)) await fs.promises.rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
});

describe("desktop release metadata", () => {
  it("shares the locked Electron and application versions across both targets", () => {
    const metadata = desktopBuild.readBuildMetadata();
    for (const appKey of ["venta", "gestion"]) {
      const config = desktopBuild.createDesktopConfig(appKey);
      expect(config.electronVersion).toBe(metadata.electronVersion);
      expect(config.extraMetadata.version).toBe(metadata.version);
      expect(config.extraMetadata.tpvBuild.electronVersion).toBe(metadata.electronVersion);
      expect(config.win.target).toEqual([{ target: "nsis", arch: ["x64"] }]);
      expect(config.nsis.oneClick).toBe(false);
      expect(config.publish).toBeNull();
    }
  });

  it("refuses a stale installed runtime before staging can be replaced", () => {
    const root = fixture();
    writeJson(path.join(root, "package.json"), { version: "1.0.0", devDependencies: { electron: "^44.2.0" } });
    writeJson(path.join(root, "package-lock.json"), { packages: {
      "": { version: "1.0.0", devDependencies: { electron: "^44.2.0" } },
      "node_modules/electron": { version: "44.2.0" }
    } });
    writeJson(path.join(root, "node_modules/electron/package.json"), { version: "43.4.1" });
    expect(() => desktopBuild.readBuildMetadata(root, true)).toThrow(/npm ci/);
    writeJson(path.join(root, "node_modules/electron/package.json"), { version: "44.2.0" });
    expect(desktopBuild.readBuildMetadata(root, true)).toEqual({ version: "1.0.0", electronVersion: "44.2.0" });
  });

  it("keeps unpacked commands and makes signed NSIS opt-in with publishing disabled", () => {
    const manifest = JSON.parse(fs.readFileSync(path.join(desktopBuild.frontendRoot, "package.json"), "utf8"));
    for (const app of ["venta", "gestion"]) {
      expect(manifest.scripts[`package:desktop:${app}`]).toContain("--dir --publish never");
      expect(manifest.scripts[`package:installer:${app}`]).toContain("--win nsis --x64 --publish never --config.forceCodeSigning=true");
      expect(manifest.scripts[`package:installer:${app}`]).toContain(`--installer ${app}`);
    }
  });
});

describe("desktop artifact verification", () => {
  it("writes and checks the generated artifact checksum, rejecting modified bytes", async () => {
    const file = path.join(fixture(), "unit-fixture-setup.exe");
    fs.writeFileSync(file, "unit fixture, not a Windows executable");
    expect(await desktopBuild.writeArtifactChecksums({ artifactPaths: [file] })).toEqual([`${file}.sha256`]);
    await expect(verifyChecksum(file)).resolves.toBeUndefined();
    fs.appendFileSync(file, "tampered");
    await expect(verifyChecksum(file)).rejects.toThrow(/SHA-256/);
  });

  it("does not accept a missing checksum or a missing expected signer", async () => {
    const file = path.join(fixture(), "unit-fixture-setup.exe");
    fs.writeFileSync(file, "unit fixture");
    await expect(verifyChecksum(file)).rejects.toThrow();
    const execute = vi.fn();
    expect(() => verifyAuthenticode(file, undefined, execute)).toThrow(/TPV_DESKTOP_SIGNER_THUMBPRINT/);
    expect(execute).not.toHaveBeenCalled();
  });

  it("requires Windows to validate signature, expected signer and timestamp without interpolating paths", () => {
    const execute = vi.fn();
    const file = path.join(fixture(), "file with spaces.exe");
    verifyAuthenticode(file, "AB".repeat(20), execute);
    const [program, args, options] = execute.mock.calls[0];
    expect(program).toBe("powershell.exe");
    expect(args.at(-1)).toContain("Get-AuthenticodeSignature -LiteralPath $env:TPV_DESKTOP_VERIFY_FILE");
    expect(args.at(-1)).toContain("TimeStamperCertificate");
    expect(args.at(-1)).toContain("SignerCertificate.Thumbprint");
    expect(args.at(-1)).not.toContain(file);
    expect(options.env.TPV_DESKTOP_VERIFY_FILE).toBe(file);
    expect(options.windowsHide).toBe(true);
    expect(() => verifyAuthenticode(file, "AB".repeat(20), () => { throw new Error("signature rejected"); })).toThrow("signature rejected");
  });

  it("checks the packaged application and Electron identities in the ASAR", async () => {
    const root = fixture();
    const source = path.join(root, "source");
    const output = path.join(root, "unpacked");
    const metadata = { version: "1.0.0", electronVersion: "44.2.0" };
    const app = { key: "venta", title: "APP VENTA", main: "main-venta.cjs", dist: "apps/app-venta/dist" };
    writeJson(path.join(source, "package.json"), { version: metadata.version, main: "desktop/main-venta.cjs", tpvBuild: { electronVersion: metadata.electronVersion } });
    for (const name of ["desktop/main-venta.cjs", "desktop/preload.cjs", "apps/app-venta/dist/index.html"]) {
      const file = path.join(source, name);
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, "fixture");
    }
    fs.mkdirSync(path.join(output, "resources"), { recursive: true });
    await createPackage(source, path.join(output, "resources/app.asar"));
    expect(validateDirectory(output, app, metadata)).toBeGreaterThan(3);
    expect(() => validateDirectory(output, app, { ...metadata, electronVersion: "43.4.0" })).toThrow(/manifest/);
  });
});

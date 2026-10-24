import { describe, expect, it, vi } from "vitest";
import { createRequire } from "node:module";
import fs from "node:fs";
import vm from "node:vm";
import { EventEmitter } from "node:events";

const require = createRequire(import.meta.url);
const { ACTIONS, sanitizeStatus, publicStatusPath } = require("./disaster-recovery-maintenance.cjs");

describe("disaster recovery desktop bridge", () => {
  it("accepts only fixed local maintenance actions", () => {
    expect([...ACTIONS]).toEqual(["request", "receive", "activate", "status"]);
    for (const unsafe of ["restore", "delete", "download", "../../tool"]) expect(ACTIONS.has(unsafe)).toBe(false);
  });

  it("returns only allowlisted public status fields", () => {
    expect(sanitizeStatus({ state: "SUPPORT_REVIEW", recoveryId: "id", destinationPublicKey: "public",
      recoverySecret: "private", token: "secret", file: "C:\\secret" })).toEqual({
        state: "SUPPORT_REVIEW", recoveryId: "id", destinationPublicKey: "public"
      });
    expect(sanitizeStatus({ state: "RESTORED" })).toBeNull();
  });

  it("anchors public status under ProgramData", () => {
    expect(publicStatusPath("C:\\ProgramData")).toContain("disaster-recovery");
    expect(publicStatusPath("C:\\elsewhere")).toBeNull();
  });
});

describe("installed disaster recovery IPC launch", () => {
  const source = fs.readFileSync(new URL("./main.cjs", import.meta.url), "utf8");
  const start = source.indexOf('registerIpc("tpv:disaster-recovery:maintenance"');
  const end = source.indexOf('registerIpc("tpv:disaster-recovery:read-status"', start);
  const script = "C:\\ProgramData\\TPV ERP\\Backend\\releases\\test-release\\tools\\Start-TpvDisasterRecoveryMaintenance.ps1";

  function bridge({ platform = "win32", app = "gestion", installed = true, local = true, spawnError = false } = {}) {
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    let handler;
    const child = new EventEmitter();
    child.unref = vi.fn();
    const spawn = vi.fn(() => {
      queueMicrotask(() => child.emit(spawnError ? "error" : "spawn", spawnError ? new Error("spawn failed") : undefined));
      return child;
    });
    vm.runInNewContext(source.slice(start, end), {
      registerIpc: (_channel, callback) => { handler = callback; },
      process: { platform }, desktopAppConfig: { key: app },
      DISASTER_RECOVERY_ACTIONS: ACTIONS,
      installedDisasterRecoveryScript: () => installed ? script : null,
      installedLocalEndpoint: () => local ? "http://127.0.0.1:8080" : null,
      structuredError: (code, message) => ({ ok: false, code, message }), spawn,
    });
    return { invoke: request => handler({}, request), spawn, child };
  }

  it.each([...ACTIONS])("launches %s with a console-capable PowerShell process and no shell interpolation", async action => {
    const test = bridge();
    expect(await test.invoke({ action })).toEqual({ ok: true, started: true });
    // Windows PowerShell exits before executing -File when Node detaches it from the console.
    expect(test.spawn).toHaveBeenCalledExactlyOnceWith("powershell.exe", [
      "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, "-Action", action,
    ], { detached: false, stdio: "ignore", windowsHide: false });
    expect(test.child.unref).toHaveBeenCalledOnce();
  });

  it.each([undefined, [], { action: "receive", script: "C:\\other.ps1" },
    { action: "receive; another-command" }, { action: "delete" }])("rejects an untrusted request before spawning: %j", async request => {
    const test = bridge();
    expect(await test.invoke(request)).toMatchObject({ ok: false, code: "RECOVERY_MAINTENANCE_DENIED" });
    expect(test.spawn).not.toHaveBeenCalled();
  });

  it.each([{ platform: "linux" }, { app: "venta" }])("rejects an unsupported desktop context: %j", async options => {
    const test = bridge(options);
    expect(await test.invoke({ action: "receive" })).toMatchObject({ ok: false, code: "RECOVERY_MAINTENANCE_DENIED" });
    expect(test.spawn).not.toHaveBeenCalled();
  });

  it.each([{ installed: false }, { local: false }])("requires the installed local maintenance context: %j", async options => {
    const test = bridge(options);
    expect(await test.invoke({ action: "receive" })).toMatchObject({ ok: false, code: "RECOVERY_MAINTENANCE_UNAVAILABLE" });
    expect(test.spawn).not.toHaveBeenCalled();
  });

  it("reports process creation failure without claiming the assistant started", async () => {
    const test = bridge({ spawnError: true });
    expect(await test.invoke({ action: "receive" })).toMatchObject({ ok: false, code: "RECOVERY_MAINTENANCE_START_FAILED" });
    expect(test.child.unref).not.toHaveBeenCalled();
  });
});

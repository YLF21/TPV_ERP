import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import electron from "electron";

// Opt-in real-process check. It does not run as part of the normal Vitest suite.
const runFile = promisify(execFile);
const prefix = "tpverp-control-electron-";
const root = await fs.mkdtemp(path.join(os.tmpdir(), prefix));
const nonce = randomUUID();
const fixture = fileURLToPath(new URL("./fixtures/sale-control-electron-restart.cjs", import.meta.url));
const reports = [];
const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;
delete env.NODE_OPTIONS;
delete env.NODE_PATH;

try {
  await fs.writeFile(path.join(root, "harness.marker"), nonce, { flag: "wx" });
  for (const phase of ["write", "recover-and-ack", "verify-empty"]) {
    const { stdout, stderr } = await runFile(electron, [fixture, phase, root, nonce], {
      cwd: path.dirname(fixture), env, windowsHide: true, timeout: 30_000, maxBuffer: 1024 * 1024,
    });
    const line = stdout.split(/\r?\n/).find(value => value.startsWith("CONTROL_OUTBOX_ELECTRON "));
    assert.ok(line, `Missing Electron evidence for ${phase}; stderr: ${stderr.slice(-2000)}`);
    const report = JSON.parse(line.slice("CONTROL_OUTBOX_ELECTRON ".length));
    assert.equal(report.phase, phase);
    assert.equal(report.processType, "browser");
    assert.ok(report.electron);
    assert.equal(report.windowsCreated, 0);
    assert.equal(report.isolatedUserData, true);
    reports.push(report);
    process.stdout.write(`${line}\n`);
  }
  assert.equal(new Set(reports.map(report => report.pid)).size, 3, "Expected three independent Electron processes");
  assert.equal(new Set(reports.map(report => report.payloadSha256)).size, 1);
  assert.deepEqual(reports.map(report => report.remaining), [1, 0, 0]);
  assert.equal(reports[1].recovered, true);
  assert.equal(reports[1].isolationVerified, true);
  assert.equal(reports[1].acknowledged, true);
  process.stdout.write("PASS: 3 real Electron processes; exact event recovered; identity/backend isolation verified; acknowledged deletion persisted; no windows or hardware.\n");
} finally {
  // Never recursively remove a computed target without checking its absolute boundary and marker.
  const absolute = path.resolve(root);
  assert.equal(path.dirname(absolute), path.resolve(os.tmpdir()));
  assert.ok(path.basename(absolute).startsWith(prefix));
  const stat = await fs.lstat(absolute);
  assert.ok(stat.isDirectory() && !stat.isSymbolicLink());
  assert.equal(await fs.readFile(path.join(absolute, "harness.marker"), "utf8"), nonce);
  await fs.rm(absolute, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
}

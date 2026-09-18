// Explicit integration fixture: never imports the application bootstrap or hardware modules.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createHash } = require("node:crypto");
const { app, BrowserWindow } = require("electron");
const { createSaleControlOutbox } = require("../../desktop/sale-control-outbox.cjs");

const PREFIX = "tpverp-control-electron-";
const backendScope = "https://control-outbox.integration.invalid";
const uuid = value => `00000000-0000-4000-8000-${String(value).padStart(12, "0")}`;
const context = { storeId: uuid(1), terminalId: uuid(2), userId: uuid(3) };
const event = {
  version: 1,
  context,
  occurredAt: "2026-09-17T10:00:00.123Z",
  saleOperationId: uuid(4),
  deletionOperationId: uuid(5),
  fullTicketClear: true,
  lines: [
    { productId: uuid(6), code: "", name: "Integration item", quantity: 0.125, unitPrice: 12.345 },
    { productId: uuid(7), code: "RETURN", name: "Integration return", quantity: -0.125, unitPrice: 12.345 },
  ],
};

async function run() {
  assert.ok(process.versions.electron, "Must run in a real Electron process");
  assert.equal(process.type, "browser", "Must run in Electron's main process");
  const [phase, candidate, nonce] = process.argv.slice(2);
  assert.ok(["write", "recover-and-ack", "verify-empty"].includes(phase));
  const root = path.resolve(candidate || ".");
  assert.equal(path.dirname(root), path.resolve(os.tmpdir()), "Only a direct temporary-directory child is allowed");
  assert.ok(path.basename(root).startsWith(PREFIX));
  assert.ok(fs.lstatSync(root).isDirectory() && !fs.lstatSync(root).isSymbolicLink());
  assert.equal(fs.readFileSync(path.join(root, "harness.marker"), "utf8"), nonce);
  assert.match(nonce, /^[0-9a-f-]{36}$/i);

  const userData = path.join(root, "user-data");
  const isolatedPaths = {
    userData,
    sessionData: path.join(root, "session-data"),
    logs: path.join(root, "logs"),
    crashDumps: path.join(root, "crash-dumps"),
  };
  for (const [name, directory] of Object.entries(isolatedPaths)) {
    fs.mkdirSync(directory, { recursive: true });
    app.setPath(name, directory);
  }
  app.disableHardwareAcceleration();
  let windowsCreated = 0;
  app.on("browser-window-created", () => { windowsCreated += 1; });
  await app.whenReady();
  assert.equal(app.getPath("userData"), userData);
  const store = createSaleControlOutbox({ userDataPath: app.getPath("userData"), backendScope });
  let recovered = false;
  let isolationVerified = false;
  let acknowledged = false;
  if (phase === "write") {
    assert.deepEqual(store.list(context), []);
    store.put(event);
    assert.deepEqual(store.list(context), [event]);
  } else if (phase === "recover-and-ack") {
    assert.deepEqual(store.list(context), [event], "Fresh process must recover the exact original payload");
    recovered = true;
    for (const key of ["storeId", "terminalId", "userId"]) {
      assert.deepEqual(store.list({ ...context, [key]: uuid(99) }), [], `${key} must isolate stored events`);
    }
    const otherBackend = createSaleControlOutbox({
      userDataPath: app.getPath("userData"), backendScope: "https://other-backend.integration.invalid",
    });
    assert.deepEqual(otherBackend.list(context), []);
    isolationVerified = true;
    store.remove(context, event.deletionOperationId);
    assert.deepEqual(store.list(context), []);
    acknowledged = true;
  } else {
    assert.deepEqual(store.list(context), [], "Acknowledged removal must survive a third process start");
  }
  assert.equal(windowsCreated, 0);
  assert.equal(BrowserWindow.getAllWindows().length, 0);
  const report = {
    phase, pid: process.pid, electron: process.versions.electron, processType: process.type,
    windowsCreated, isolatedUserData: true,
    remaining: store.list(context).length,
    recovered, isolationVerified, acknowledged,
    payloadSha256: createHash("sha256").update(JSON.stringify(event)).digest("hex"),
  };
  // Flush the evidence before terminating this independent Electron process.
  fs.writeSync(1, `CONTROL_OUTBOX_ELECTRON ${JSON.stringify(report)}\n`);
  app.exit(0);
}

run().catch(error => {
  fs.writeSync(2, `CONTROL_OUTBOX_ELECTRON_FAILED ${error?.stack || String(error)}\n`);
  if (app) app.exit(1);
  else process.exitCode = 1;
});

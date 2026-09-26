import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID, randomBytes, createHash } from "node:crypto";
import { spawn, spawnSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const directory = path.join(root, ".codex-runtime", "saas-incident-demo");
const statePath = path.join(directory, "state.json");
const base = "http://127.0.0.1:8090";
const mode = process.argv[2] ?? "start";
fs.mkdirSync(directory, { recursive: true });
const save = state => {
  const temporary = statePath + "." + process.pid + ".tmp";
  fs.writeFileSync(temporary, JSON.stringify(state, null, 2), { mode: 0o600 });
  fs.renameSync(temporary, statePath);
};
const read = () => JSON.parse(fs.readFileSync(statePath, "utf8"));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function post(url, body, token) {
  const response = await fetch(base + url, {
    method: "POST", headers: { "Content-Type": "application/json", "X-TPV-Installation-Token": token },
    body: JSON.stringify(body), signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) throw Error(url + ": HTTP " + response.status);
  return response.json();
}
function snapshot(state, scenario, status, revision) {
  return {
    eventId: randomUUID(), companyId: state.companyId, storeId: scenario.storeId,
    entityType: "STORE_FAILURE", entityId: scenario.sourceId, operation: "ACTUALIZAR",
    payload: {
      schemaVersion: 2, installationId: scenario.publicInstallationId,
      source: scenario.source, sourceId: scenario.sourceId, sourceRevision: revision,
      status, severity: "DANGER", code: scenario.source === "LOCAL_SYNC" ? "SYNC_DELIVERY_FAILED" : "APPLICATION_ERROR",
      firstSeenAt: scenario.firstSeenAt, lastSeenAt: new Date().toISOString(), occurrences: scenario.number + 1,
      module: scenario.source === "LOCAL_SYNC" ? "SYNC" : "PRINTING",
      appVersion: "DEMO-INCIDENCIAS-1.0", traceId: scenario.trace,
      exceptionType: scenario.source === "LOCAL_SYNC" ? "java.net.ConnectException" : "java.io.IOException",
      errorLocation: scenario.source === "LOCAL_SYNC" ? "com.tpverp.demo.SyncSimulation.send:42" : "com.tpverp.demo.PrintSimulation.print:60"
    }
  };
}
function sqlLiteral(value) { return "'" + String(value).replaceAll("'", "''") + "'"; }
function seed(state) {
  const config = Object.fromEntries(fs.readFileSync(path.join(root, "backend-saas", ".env"), "utf8").split(/\r?\n/)
    .filter(line => /^[A-Z_]+=/.test(line)).map(line => {
      const split = line.indexOf("=");
      return [line.slice(0, split), line.slice(split + 1).trim().replace(/^(['"])(.*)\1$/, "$2")];
    }));
  // Deliberately fixed to the already configured local development database.
  const statements = ["BEGIN;",
    "INSERT INTO saas_company(id,name,tax_id,taxpayer_type,created_at) VALUES (" +
    [state.companyId, state.name, state.taxId, "SOCIEDAD"].map(sqlLiteral).join(",") + ",now());",
    "INSERT INTO saas_license(id,company_id,reference,valid_until,status,max_windows,max_pda,created_at) VALUES (" +
    [state.licenseId, state.companyId, "DEMO-INCIDENCIAS-" + state.companyId].map(sqlLiteral).join(",") +
    ",now()+interval '30 days','VALIDA',3,0,now());"
  ];
  for (const scenario of state.scenarios) {
    statements.push("INSERT INTO saas_store(id,company_id,code,name,tax_regime,commercial_profile,created_at) VALUES (" +
      [scenario.storeId, state.companyId, String(scenario.number).padStart(3, "0"), scenario.name, "IVA", "MINORISTA"].map(sqlLiteral).join(",") + ",now());");
    const hash = createHash("sha256").update(scenario.token).digest("hex");
    statements.push("INSERT INTO saas_installation(id,company_id,store_id,license_id,installation_id,installation_reference,token_hash,linked_at,app_version,terminal_name) VALUES (" +
      [scenario.installationId, state.companyId, scenario.storeId, state.licenseId, scenario.publicInstallationId, "DEMO-" + scenario.number, hash].map(sqlLiteral).join(",") +
      ",now(),'DEMO-INCIDENCIAS-1.0'," + sqlLiteral(scenario.name) + ");");
  }
  statements.push("COMMIT;");
  const psql = process.env.SAAS_DEMO_PSQL ?? "E:/postgreSQL/bin/psql.exe";
  const result = spawnSync(psql, ["-h", "127.0.0.1", "-p", "5432", "-U", "tpv_erp_saas", "-d", "tpv_erp_saas", "-X", "-q", "-v", "ON_ERROR_STOP=1"], {
    input: statements.join("\n"), encoding: "utf8", windowsHide: true,
    env: { ...process.env, PGCLIENTENCODING: "UTF8", PGPASSWORD: config.TPV_SAAS_DB_PASSWORD || config.POSTGRES_PASSWORD || "" }
  });
  if (result.status !== 0) throw Error("Demo seed failed: " + (result.stderr || result.error?.message));
}
function newState() {
  const digits = "94" + String(Math.floor(Math.random() * 100000)).padStart(5, "0");
  let sum = 0;
  [...digits].forEach((digit, index) => { const n = Number(digit) * (index % 2 === 0 ? 2 : 1); sum += Math.floor(n / 10) + n % 10; });
  return {
    name: "DEMO - Laboratorio de incidencias", companyId: randomUUID(), licenseId: randomUUID(),
    taxId: "B" + digits + ((10 - sum % 10) % 10), seeded: false, workerPid: null,
    scenarios: [
      { number: 1, name: "DEMO 01 - Sincronizacion recuperable", outcome: "SUCCEEDED", source: "LOCAL_SYNC", trace: "DEMO-01-SYNC-RECUPERABLE" },
      { number: 2, name: "DEMO 02 - Sincronizacion requiere asistencia", outcome: "FAILED", source: "LOCAL_SYNC", trace: "DEMO-02-SYNC-ASISTENCIA" },
      { number: 3, name: "DEMO 03 - Impresora sin respuesta", outcome: "MANUAL", source: "LOCAL_APPLICATION", trace: "DEMO-03-IMPRESION-MANUAL" }
    ].map(scenario => ({
      ...scenario, storeId: randomUUID(), installationId: randomUUID(), publicInstallationId: randomUUID(),
      sourceId: randomUUID(), token: randomBytes(32).toString("hex"),
      firstSeenAt: new Date(Date.now() - 20 * 60000).toISOString(), initial: null, reported: false, receipts: {}
    }))
  };
}
async function prepare() {
  const health = await fetch(base + "/actuator/health", { signal: AbortSignal.timeout(5000) }).then(r => r.json());
  if (health.status !== "UP") throw Error("Start the local SaaS backend first.");
  const state = fs.existsSync(statePath) ? read() : newState();
  if (!state.seeded) {
    // Save identities first; a failed seed remains inspectable and never targets existing companies.
    save(state);
    seed(state); state.seeded = true; save(state);
  }
  for (const scenario of state.scenarios) {
    if (scenario.reported) continue;
    scenario.initial ??= snapshot(state, scenario, "OPEN", 1); save(state);
    await post("/api/v1/sync/events", scenario.initial, scenario.token);
    scenario.reported = true; save(state);
  }
  return state;
}
async function tick(state) {
  for (const scenario of state.scenarios.filter(s => s.outcome !== "MANUAL")) {
    const commands = await post("/api/v1/sync/repairs/claim", { installationId: scenario.publicInstallationId }, scenario.token);
    for (const command of commands) {
      if (command.companyId !== state.companyId || command.storeId !== scenario.storeId ||
          command.eventId !== scenario.sourceId || command.action !== "RETRY_SYNC_OUTBOX") {
        throw Error("Refusing a command outside the demo allowlist.");
      }
      await post("/api/v1/sync/repairs/" + command.commandId + "/result", {
        installationId: scenario.publicInstallationId, status: "RUNNING", resultCode: "RETRY_QUEUED"
      }, scenario.token);
      await delay(2500);
      if (scenario.outcome === "SUCCEEDED") {
        scenario.receipts[command.commandId] ??= snapshot(state, scenario, "RESOLVED", command.expectedVersion + 1);
        save(state);
        // Recovery evidence is an authenticated failure snapshot, never a direct DB status update.
        await post("/api/v1/sync/events", scenario.receipts[command.commandId], scenario.token);
      }
      await post("/api/v1/sync/repairs/" + command.commandId + "/result", {
        installationId: scenario.publicInstallationId, status: scenario.outcome,
        resultCode: scenario.outcome === "SUCCEEDED" ? "SYNC_DELIVERED" : "RETRY_FAILED"
      }, scenario.token);
      console.log(new Date().toISOString() + " DEMO " + scenario.number + ": " + scenario.outcome);
    }
  }
}
if (mode === "worker" || mode === "once") {
  const state = read();
  if (!state.seeded || !state.name.startsWith("DEMO -")) throw Error("Demo state is not initialized.");
  const deadline = Date.now() + 8 * 60 * 60 * 1000;
  do {
    try { await tick(state); } catch (error) {
      console.error(new Date().toISOString() + " " + error.message);
      if (mode === "once") process.exitCode = 1;
    }
    if (mode === "once") break;
    await delay(4000);
  } while (Date.now() < deadline);
} else if (mode === "start" || mode === "setup") {
  const state = await prepare();
  if (mode === "start") {
    let running = false;
    if (state.workerPid) { try { process.kill(state.workerPid, 0); running = true; } catch {} }
    if (!running) {
      const log = fs.openSync(path.join(directory, "worker.log"), "a");
      const child = spawn(process.execPath, [fileURLToPath(import.meta.url), "worker"], {
        cwd: root, detached: true, windowsHide: true, stdio: ["ignore", log, log]
      });
      child.unref(); fs.closeSync(log);
      state.workerPid = child.pid; save(state);
    }
  }
  console.log(JSON.stringify({ company: state.name, companyId: state.companyId, workerPid: state.workerPid,
    scenarios: state.scenarios.map(({ number, name, trace }) => ({ number, name, trace })) }, null, 2));
} else {
  throw Error("Usage: node tools/saas-incident-demo.mjs [start|setup|once]");
}

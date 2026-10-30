import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

if (process.env.E2E_PDA_REAL_DISPOSABLE !== "true") {
  throw new Error(
    "La prueba PDA real solo puede iniciar con E2E_PDA_REAL_DISPOSABLE=true; "
      + "usa una base PostgreSQL desechable y nunca la base de desarrollo habitual."
  );
}

const databaseName = process.env.E2E_DB_NAME ?? "tpv_erp_e2e_pda";
if (!/^tpv_erp_e2e_pda(?:_[a-z0-9_]+)?$/.test(databaseName)) {
  throw new Error(`Nombre de base E2E no permitido: ${databaseName}`);
}

const here = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(here, "../../..");
const backendRoot = path.join(repositoryRoot, "backend");
const bundledPsql = "E:/postgreSQL/bin/psql.exe";
const psql = process.env.E2E_PSQL_PATH ?? (fs.existsSync(bundledPsql) ? bundledPsql : "psql");
const postgresBin = process.env.E2E_PG_BIN_DIR ?? path.dirname(psql);
const initdb = path.join(postgresBin, process.platform === "win32" ? "initdb.exe" : "initdb");
const pgCtl = path.join(postgresBin, process.platform === "win32" ? "pg_ctl.exe" : "pg_ctl");
const databaseHost = process.env.E2E_DB_HOST ?? "127.0.0.1";
const databasePort = process.env.E2E_DB_PORT ?? "55432";
const databaseAdmin = "postgres";
const applicationUser = process.env.E2E_DB_USERNAME ?? "tpv_erp";
const applicationPassword = process.env.E2E_DB_PASSWORD ?? "admin";
const backendPort = new URL(process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18082").port || "18082";
const clusterRoot = path.resolve(process.env.E2E_PG_DATA_DIR ?? path.join(repositoryRoot, "output/playwright/pda-real-postgres"));
const allowedClusterRoot = path.resolve(repositoryRoot, "output/playwright/pda-real-postgres");

if (clusterRoot !== allowedClusterRoot && !clusterRoot.startsWith(`${allowedClusterRoot}${path.sep}`)) {
  throw new Error(`Directorio PostgreSQL E2E no permitido: ${clusterRoot}`);
}
if (!/^[a-z_][a-z0-9_]*$/.test(applicationUser)) {
  throw new Error(`Usuario PostgreSQL E2E no permitido: ${applicationUser}`);
}

function postgres(sql) {
  execFileSync(psql, [
    "-X", "-q", "-v", "ON_ERROR_STOP=1",
    "-h", databaseHost, "-p", databasePort,
    "-U", databaseAdmin, "-d", "postgres", "-c", sql
  ], {
    env: { ...process.env, PGCLIENTENCODING: "UTF8" },
    stdio: ["ignore", "inherit", "inherit"]
  });
}

function pgControl(args, stdio = "inherit") {
  return execFileSync(pgCtl, args, { env: process.env, stdio });
}

if (!fs.existsSync(initdb) || !fs.existsSync(pgCtl)) {
  throw new Error(`La instalación PostgreSQL no contiene initdb/pg_ctl en ${postgresBin}`);
}

// Clúster completamente efímero: no usa usuarios, contraseñas, bases ni
// procesos de la instalación operativa que pueda existir en el equipo.
if (fs.existsSync(path.join(clusterRoot, "PG_VERSION"))) {
  try { pgControl(["-D", clusterRoot, "-m", "fast", "-w", "stop"], "ignore"); } catch { /* Ya estaba detenido. */ }
}
fs.rmSync(clusterRoot, { recursive: true, force: true });
fs.mkdirSync(clusterRoot, { recursive: true });
execFileSync(initdb, ["-D", clusterRoot, "-U", databaseAdmin, "-A", "trust", "--encoding=UTF8", "--locale=C"], {
  env: process.env,
  stdio: "inherit"
});
pgControl([
  "-D", clusterRoot,
  "-l", path.join(clusterRoot, "postgres.log"),
  "-o", `-h ${databaseHost} -p ${databasePort}`,
  "-w", "start"
]);
postgres(`CREATE ROLE ${applicationUser} LOGIN`);
postgres(`CREATE DATABASE ${databaseName} OWNER ${applicationUser}`);

const maven = process.env.E2E_MAVEN_COMMAND ?? (process.platform === "win32" ? ".\\mvnw.cmd" : "./mvnw");
// The real-browser suite exercises the already-covered production code. Skipping
// test compilation here keeps Playwright's webServer startup deterministic; unit
// and integration tests remain separate build stages.
const backend = spawn(maven, ["--batch-mode", "-Dmaven.test.skip=true", "spring-boot:run"], {
  cwd: backendRoot,
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: "dev",
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true",
    TPV_SERVER_ADDRESS: "127.0.0.1",
    TPV_SERVER_PORT: backendPort,
    TPV_DB_URL: `jdbc:postgresql://${databaseHost}:${databasePort}/${databaseName}`,
    TPV_DB_USERNAME: applicationUser,
    TPV_DB_PASSWORD: applicationPassword,
    TPV_DEV_SAMPLE_DATA_ENABLED: "true",
    TPV_DEV_SAMPLE_DATA_BASE_DATE: process.env.TPV_DEV_SAMPLE_DATA_BASE_DATE ?? "2026-08-24"
  },
  stdio: "inherit",
  shell: process.platform === "win32"
});

let stopping = false;
function stopCluster() {
  if (!fs.existsSync(path.join(clusterRoot, "PG_VERSION"))) return;
  try { pgControl(["-D", clusterRoot, "-m", "fast", "-w", "stop"], "ignore"); } catch { /* Teardown may have stopped it first. */ }
}
function stop(signal) {
  if (stopping) return;
  stopping = true;
  backend.kill(signal);
}

process.on("SIGINT", () => stop("SIGINT"));
process.on("SIGTERM", () => stop("SIGTERM"));
backend.on("error", (error) => {
  console.error("No se pudo iniciar el backend E2E PDA", error);
  process.exitCode = 1;
});
backend.on("exit", (code, signal) => {
  stopCluster();
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 1);
});

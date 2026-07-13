import { execFileSync } from "node:child_process";

export function executeE2eSql(sql: string) {
  execFileSync(process.env.E2E_PSQL_PATH ?? "psql", [
    "-X", "-q", "-v", "ON_ERROR_STOP=1",
    "-h", process.env.E2E_DB_HOST ?? "localhost",
    "-U", process.env.E2E_DB_ADMIN_USERNAME ?? "postgres",
    "-d", process.env.E2E_DB_NAME ?? "tpv_erp_dev"
  ], {
    env: {
      ...process.env,
      PGPASSWORD: process.env.E2E_DB_ADMIN_PASSWORD ?? "admin"
    },
    input: sql,
    stdio: ["pipe", "pipe", "pipe"]
  });
}

export function sqlUuid(value: string) {
  if (!/^[0-9a-f-]{36}$/i.test(value)) throw new Error(`UUID E2E no valido: ${value}`);
  return `'${value}'::uuid`;
}

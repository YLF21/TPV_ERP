import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request } from "@playwright/test";
import { executeE2eSql, sqlUuid } from "./support/database";
import {
  adminLogin, bearer, pdaRealFixturePath, readPdaRealFixture, realPdaApiUrl,
  removePdaRealFixture
} from "./support/pdaRealFixture";

export default async function cleanupRealPdaFixture() {
  const hasFixture = fs.existsSync(pdaRealFixturePath);
  const fixture = hasFixture ? readPdaRealFixture() : null;
  const context = hasFixture ? await request.newContext() : null;
  try {
    if (!fixture || !context) return;
    const warehouseId = sqlUuid(fixture.warehouseId);
    const productId = sqlUuid(fixture.productId);
    const terminalId = sqlUuid(fixture.pdaTerminalId);
    const userId = sqlUuid(fixture.userId);
    const roleId = sqlUuid(fixture.roleId);
    const workId = sqlUuid(fixture.workId);

    // Primero elimina las proyecciones y el historial creados por el ajuste
    // aislado. Así las APIs pueden borrar producto y almacén respetando sus
    // reglas normales de integridad.
    executeE2eSql(`
begin;
delete from pda_evento_operativo where terminal_id = ${terminalId} or trabajo_id = ${workId};
delete from pda_trabajo_evidencia where trabajo_id = ${workId};
delete from pda_trabajo_operativo where id = ${workId};
delete from sync_outbox where tipo_entidad = 'STOCK_MOVEMENT' and entidad_id in (
  select id from movimiento_stock where producto_id = ${productId} and almacen_id = ${warehouseId}
);
delete from ajuste_stock_historial where producto_id = ${productId} and almacen_id = ${warehouseId};
delete from movimiento_stock where producto_id = ${productId} and almacen_id = ${warehouseId};
delete from existencia where producto_id = ${productId} and almacen_id = ${warehouseId};
commit;
`);

    try {
      const admin = await adminLogin(context);
      const warehouseResponse = await context.delete(`${realPdaApiUrl}/warehouses/${encodeURIComponent(fixture.warehouseId)}`, {
        headers: bearer(admin.accessToken)
      });
      if (!warehouseResponse.ok() && warehouseResponse.status() !== 404) {
        console.warn(`No se pudo borrar el almacén E2E: HTTP ${warehouseResponse.status()} ${await warehouseResponse.text()}`);
      }
    } catch (error) {
      console.warn("La limpieza API del almacén PDA E2E falló", error);
    }

    executeE2eSql(`
begin;
delete from pda_vinculacion_temporal where terminal_id = ${terminalId};
delete from auditoria where terminal_id = ${terminalId} or usuario_id = ${userId};
delete from gestion_group_unlock_state where session_id in (
  select id from sesion where usuario_id = ${userId} or terminal_id = ${terminalId}
);
delete from sesion where usuario_id = ${userId} or revocada_por_usuario_id = ${userId} or terminal_id = ${terminalId};
delete from usuario_tienda where usuario_id = ${userId};
delete from usuario where id = ${userId};
delete from rol_permiso where rol_id = ${roleId};
delete from rol where id = ${roleId};
delete from terminal where id = ${terminalId};
commit;
`);
  } finally {
    removePdaRealFixture();
    await context?.dispose();
    stopEphemeralPostgres();
  }
}

function stopEphemeralPostgres() {
  const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const repositoryRoot = path.resolve(frontendRoot, "..");
  const clusterRoot = path.resolve(process.env.E2E_PG_DATA_DIR
    ?? path.join(repositoryRoot, "output/playwright/pda-real-postgres"));
  const allowedRoot = path.resolve(repositoryRoot, "output/playwright/pda-real-postgres");
  if (clusterRoot !== allowedRoot && !clusterRoot.startsWith(`${allowedRoot}${path.sep}`)) {
    console.warn(`No se elimina un directorio PostgreSQL E2E fuera del espacio permitido: ${clusterRoot}`);
    return;
  }
  if (!fs.existsSync(path.join(clusterRoot, "PG_VERSION"))) return;
  const psqlPath = process.env.E2E_PSQL_PATH ?? "E:/postgreSQL/bin/psql.exe";
  const pgCtl = path.join(path.dirname(psqlPath), process.platform === "win32" ? "pg_ctl.exe" : "pg_ctl");
  try {
    execFileSync(pgCtl, ["-D", clusterRoot, "-m", "fast", "-w", "stop"], {
      env: process.env,
      stdio: "ignore"
    });
    fs.rmSync(clusterRoot, { recursive: true, force: true });
  } catch (error) {
    console.warn("No se pudo detener/eliminar el PostgreSQL efímero PDA E2E", error);
  }
}

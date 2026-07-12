import type { APIRequestContext } from "@playwright/test";
import { apiPost, apiPut } from "./testApi";
import { executeE2eSql, sqlUuid } from "./database";

type RoleView = { id: string; name: string; permissions: string[] };
type UserView = { id: string; name: string; userName: string; role: string };

export type ProductManagerFixtures = {
  role: RoleView;
  creator: UserView & { password: string };
  colleague: UserView & { password: string };
};

export async function createProductManagerFixtures(
  request: APIRequestContext,
  adminToken: string,
  marker: string
): Promise<ProductManagerFixtures> {
  const role = await apiPost<RoleView>(request, adminToken, "/roles", {
    name: `E2E GESTION PRODUCTO ${marker}`
  });
  const configuredRole = await apiPut<RoleView>(
    request,
    adminToken,
    `/roles/${encodeURIComponent(role.id)}/permissions`,
    { codes: ["GESTION_PRODUCTO"] }
  );
  const password = "0000";
  const creator = await apiPost<UserView>(request, adminToken, "/users", {
    name: `E2E CREADOR ${marker}`,
    userName: `e2e_creator_${marker.toLowerCase()}`,
    password,
    roleId: role.id
  });
  const colleague = await apiPost<UserView>(request, adminToken, "/users", {
    name: `E2E COLEGA ${marker}`,
    userName: `e2e_colleague_${marker.toLowerCase()}`,
    password,
    roleId: role.id
  });
  return {
    role: configuredRole,
    creator: { ...creator, password },
    colleague: { ...colleague, password }
  };
}

export function cleanupProductManagerFixtures(fixtures: ProductManagerFixtures) {
  const userIds = [fixtures.creator.id, fixtures.colleague.id];
  const quotedUsers = userIds.map(sqlUuid).join(", ");
  const roleId = sqlUuid(fixtures.role.id);
  const sql = `
begin;
delete from sesion where usuario_id in (${quotedUsers}) or revocada_por_usuario_id in (${quotedUsers});
delete from auditoria where usuario_id in (${quotedUsers});
delete from usuario_tienda where usuario_id in (${quotedUsers});
delete from usuario where id in (${quotedUsers});
delete from rol_permiso where rol_id = ${roleId};
delete from rol where id = ${roleId};
commit;
`;
  executeE2eSql(sql);
}

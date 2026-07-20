import { apiRequest } from "@tpverp/app-common";

export type SecurityUser = {
  id: string;
  userId: string;
  name: string;
  userName: string;
  role: string;
  active: boolean;
  protectedUser: boolean;
};

export type SecurityRole = {
  id: string;
  name: string;
  protectedRole: boolean;
  permissions: string[];
};

export type RoleOption = {
  id: string;
  name: string;
};

export type PermissionCatalogItem = {
  code: string;
  translationKey: string;
  group: string;
};

export function loadSecurityUsers(token?: string) {
  return apiRequest<SecurityUser[]>("/users", { token });
}

export function loadRoleOptions(token?: string) {
  return apiRequest<RoleOption[]>("/roles/options", { token });
}

export function createSecurityUser(
  value: { name: string; userName: string; password: string; roleId: string },
  token?: string
) {
  return apiRequest<SecurityUser>("/users", { token, method: "POST", body: value });
}

export function updateSecurityUserIdentity(
  userId: string,
  value: { name: string; userName: string },
  token?: string
) {
  return apiRequest<SecurityUser>(`/users/${encodeURIComponent(userId)}/identity`, {
    token,
    method: "PATCH",
    body: value
  });
}

export function updateSecurityUserRole(userId: string, roleId: string, token?: string) {
  return apiRequest<SecurityUser>(`/users/${encodeURIComponent(userId)}/role`, {
    token,
    method: "PUT",
    body: { roleId }
  });
}

export function updateSecurityUserActive(userId: string, active: boolean, token?: string) {
  return apiRequest<void>(`/users/${encodeURIComponent(userId)}/active`, {
    token,
    method: "PATCH",
    body: { active }
  });
}

export function resetSecurityUserPassword(userId: string, password: string, token?: string) {
  return apiRequest<void>(`/users/${encodeURIComponent(userId)}/password`, {
    token,
    method: "PUT",
    body: { password }
  });
}

export function loadSecurityRoles(token?: string) {
  return apiRequest<SecurityRole[]>("/roles", { token });
}

export function loadPermissionCatalog(token?: string) {
  return apiRequest<PermissionCatalogItem[]>("/permissions/catalog", { token });
}

export function createSecurityRole(name: string, token?: string) {
  return apiRequest<SecurityRole>("/roles", { token, method: "POST", body: { name } });
}

export function renameSecurityRole(roleId: string, name: string, token?: string) {
  return apiRequest<SecurityRole>(`/roles/${encodeURIComponent(roleId)}`, {
    token,
    method: "PATCH",
    body: { name }
  });
}

export function deleteSecurityRole(roleId: string, token?: string) {
  return apiRequest<void>(`/roles/${encodeURIComponent(roleId)}`, {
    token,
    method: "DELETE"
  });
}

export function saveSecurityRolePermissions(roleId: string, codes: string[], token?: string) {
  return apiRequest<SecurityRole>(`/roles/${encodeURIComponent(roleId)}/permissions`, {
    token,
    method: "PUT",
    body: { codes }
  });
}

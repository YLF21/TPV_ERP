import { apiRequest } from "../api/client";
import type { AppKind, Permission, TerminalContext, UserSession } from "../types";

type LocalUser = UserSession & {
  password: string;
};

const localUsers: LocalUser[] = [
  {
    username: "admin",
    password: "admin",
    displayName: "ADMIN",
    permissions: ["ADMIN", "VENTA", "GESTION_VENTAS", "GESTION_PRODUCTO", "GESTION_CUENTAS",
      "GESTION_ALMACEN", "CUSTOMER_RECEIVABLES_READ", "CUSTOMER_RECEIVABLES_CREATE", "CUSTOMER_RECEIVABLES_PAY"]
  },
  {
    username: "venta",
    password: "venta",
    displayName: "VENTA",
    permissions: ["VENTA", "TICKETS_CREATE"]
  },
  {
    username: "gestor",
    password: "gestor",
    displayName: "GESTION",
    permissions: ["VENTA", "GESTION_VENTAS", "GESTION_PRODUCTO", "INVOICES_WRITE", "DELIVERY_NOTES_WRITE"]
  },
  {
    username: "producto",
    password: "producto",
    displayName: "PRODUCTO",
    permissions: ["GESTION_PRODUCTO"]
  },
  {
    username: "almacen",
    password: "almacen",
    displayName: "ALMACEN",
    permissions: ["GESTION_ALMACEN"]
  }
];

export function authenticate(username: string, password: string, app: AppKind): UserSession {
  const user = localUsers.find((candidate) => candidate.username === username && candidate.password === password);
  if (!user) {
    throw new Error("invalid_credentials");
  }
  const session = toSession(user);
  if (!canAccessApp(session.permissions, app)) {
    throw new Error("no_access");
  }
  return session;
}

export function canAccessApp(permissions: Permission[], app: AppKind): boolean {
  if (permissions.includes("ADMIN")) {
    return true;
  }
  if (app === "venta") {
    return permissions.includes("VENTA")
      || permissions.includes("GESTION_VENTAS")
      || permissions.includes("GESTION_PRODUCTO")
      || permissions.includes("GESTION_ALMACEN")
      || permissions.includes("GESTION_CUENTAS");
  }
  return permissions.includes("GESTION_VENTAS")
    || permissions.includes("GESTION_PRODUCTO")
    || permissions.includes("GESTION_ALMACEN");
}

export function hasPermission(session: UserSession, permission: Permission): boolean {
  return session.permissions.includes("ADMIN") || session.permissions.includes(permission);
}

type LoginResult = {
  accessToken: string;
  userId?: string;
  userName: string;
  role: string;
  permissions?: string[];
};

export async function authenticateRemote(
  username: string,
  password: string,
  app: AppKind,
  terminalContext: TerminalContext
): Promise<UserSession> {
  if (!terminalContext.terminalId) {
    throw new Error("terminal_not_configured");
  }

  const result = await apiRequest<LoginResult>("/auth/login", {
    method: "POST",
    body: {
      terminalId: terminalContext.terminalId,
      terminalCredential: terminalContext.terminalCredential,
      userName: username,
      password
    }
  });
  const session: UserSession = {
    userId: result.userId,
    username,
    displayName: result.userName,
    role: result.role,
    accessToken: result.accessToken,
    permissions: permissionsFromLogin(result.role, result.permissions)
  };
  if (!canAccessApp(session.permissions, app)) {
    throw new Error("no_access");
  }
  return session;
}

function permissionsFromRole(role: string): Permission[] {
  const normalized = role.toUpperCase();
  if (normalized === "ADMIN") {
    return ["ADMIN", "VENTA", "GESTION_VENTAS", "GESTION_PRODUCTO", "GESTION_CUENTAS",
      "GESTION_ALMACEN", "CUSTOMER_RECEIVABLES_READ", "CUSTOMER_RECEIVABLES_CREATE", "CUSTOMER_RECEIVABLES_PAY"];
  }

  const permissions: Permission[] = [];
  if (normalized.includes("VENTA") || normalized.includes("VENDEDOR")) {
    permissions.push("VENTA");
  }
  if (normalized.includes("GESTION_VENTAS")) {
    permissions.push("GESTION_VENTAS");
  }
  if (normalized.includes("GESTION_PRODUCTO")) {
    permissions.push("GESTION_PRODUCTO");
  }
  if (normalized.includes("GESTION_ALMACEN")) {
    permissions.push("GESTION_ALMACEN");
  }
  if (normalized.includes("GESTION_CUENTAS")) {
    permissions.push("GESTION_CUENTAS");
  }
  return permissions;
}

function permissionsFromLogin(role: string, remotePermissions: string[] | undefined): Permission[] {
  const permissions = new Set<Permission>(permissionsFromRole(role));
  remotePermissions?.forEach((permission) => permissions.add(permission as Permission));
  return Array.from(permissions);
}

function toSession(user: LocalUser): UserSession {
  return {
    username: user.username,
    displayName: user.displayName,
    permissions: [...user.permissions]
  };
}

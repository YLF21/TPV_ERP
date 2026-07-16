import { useEffect, useState } from "react";
import type { KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode } from "../types";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

export type StockSecurityRole = {
  id: string;
  name: string;
  protectedRole: boolean;
  permissions: string[];
};

type StockPermissionsStatus = {
  kind: "error" | "success";
  message: string;
};

type StockPermissionsDialogProps = {
  open: boolean;
  app: AppKind;
  username: string;
  locale: LocaleCode;
  token?: string;
  onClose: () => void;
};

export const stockPermissionMatrixColumns = [
  { code: "STOCK_READ", labelKey: "stock.permissions.read" },
  { code: "STOCK_ADJUST", labelKey: "stock.permissions.adjust" },
  { code: "STOCK_TRANSFER", labelKey: "stock.permissions.transfer" },
  { code: "WAREHOUSES_MANAGE", labelKey: "stock.permissions.warehouses" },
  { code: "GESTION_ALMACEN", labelKey: "stock.permissions.warehouseManagement" },
  { code: "GESTION_PRODUCTO", labelKey: "stock.permissions.productManagement" }
] as const;

export type StockPermissionTableColumnKey = "role" | (typeof stockPermissionMatrixColumns)[number]["code"];

export const stockPermissionTableColumnDefinitions = [
  { key: "role", defaultWidth: 160 },
  ...stockPermissionMatrixColumns.map((permission) => ({
    key: permission.code,
    defaultWidth: 128
  }))
] satisfies readonly TableColumnDefinition<StockPermissionTableColumnKey>[];

export function roleHasStockPermission(role: Pick<StockSecurityRole, "permissions">, code: string) {
  return role.permissions.includes("ALL") || role.permissions.includes(code);
}

export function stockRolePermissionsPath(roleId: string) {
  return `/roles/${encodeURIComponent(roleId)}/permissions`;
}

export function setRoleStockPermission(
  role: StockSecurityRole,
  code: string,
  granted: boolean
): StockSecurityRole {
  if (role.protectedRole || role.permissions.includes("ALL")) {
    return role;
  }
  const permissions = new Set(role.permissions);
  if (granted) {
    permissions.add(code);
  } else {
    permissions.delete(code);
  }
  return { ...role, permissions: [...permissions].sort() };
}

export function rolesHaveSamePermissions(
  left: Pick<StockSecurityRole, "permissions">,
  right: Pick<StockSecurityRole, "permissions">
) {
  if (left.permissions.length !== right.permissions.length) {
    return false;
  }
  const rightPermissions = new Set(right.permissions);
  return left.permissions.every((permission) => rightPermissions.has(permission));
}

export async function persistStockRolePermissions(roles: StockSecurityRole[], token: string) {
  return Promise.all(roles.map((role) => apiRequest<StockSecurityRole>(
    stockRolePermissionsPath(role.id),
    {
      method: "PUT",
      token,
      body: { codes: role.permissions.filter((permission) => permission !== "ALL").sort() }
    }
  )));
}

export function StockPermissionsDialog({
  open,
  app,
  username,
  locale,
  token,
  onClose
}: StockPermissionsDialogProps) {
  const t = createTranslator(locale);
  const [roles, setRoles] = useState<StockSecurityRole[]>([]);
  const [savedRoles, setSavedRoles] = useState<StockSecurityRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<StockPermissionsStatus | null>(null);
  const tableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken: open ? token : undefined,
    tableKey: "stock.permissions.matrix",
    definitions: stockPermissionTableColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableWidth = visibleColumns.reduce((sum, column) => sum + column.width, 0);
  const dirtyRoles = roles.filter((role) => {
    const savedRole = savedRoles.find((candidate) => candidate.id === role.id);
    return savedRole !== undefined && !rolesHaveSamePermissions(role, savedRole);
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape" && !saving) {
        event.preventDefault();
        onClose();
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose, open, saving]);

  useEffect(() => {
    let cancelled = false;
    if (!open) {
      return;
    }
    setRoles([]);
    setSavedRoles([]);
    setSaving(false);
    setStatus(null);
    if (!token) {
      setLoading(false);
      setStatus({ kind: "error", message: t("stock.permissions.loadError") });
      return;
    }
    setLoading(true);
    void apiRequest<StockSecurityRole[]>("/roles", { token })
      .then((values) => {
        if (!cancelled) {
          setRoles(values);
          setSavedRoles(values);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setRoles([]);
          setSavedRoles([]);
          setStatus({
            kind: "error",
            message: error instanceof Error ? error.message : t("stock.permissions.loadError")
          });
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [open, token]);

  function togglePermission(roleId: string, code: string, granted: boolean) {
    setRoles((current) => current.map((role) => (
      role.id === roleId ? setRoleStockPermission(role, code, granted) : role
    )));
    setStatus(null);
  }

  async function savePermissions() {
    if (!token || saving || dirtyRoles.length === 0) {
      return;
    }
    setSaving(true);
    setStatus(null);
    try {
      const updatedRoles = await persistStockRolePermissions(dirtyRoles, token);
      const updatedById = new Map(updatedRoles.map((role) => [role.id, role]));
      setRoles((current) => current.map((role) => updatedById.get(role.id) ?? role));
      setSavedRoles((current) => current.map((role) => updatedById.get(role.id) ?? role));
      setStatus({ kind: "success", message: t("stock.settings.saved") });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : t("stock.settings.saveError")
      });
    } finally {
      setSaving(false);
    }
  }

  if (!open) {
    return null;
  }

  function handlePermissionEnter(event: KeyboardEvent<HTMLElement>) {
    const target = event.target as HTMLInputElement;
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      ctrlKey: event.ctrlKey,
      altKey: event.altKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
    if (!intent || !target.matches("input[type='checkbox']:not(:disabled)")) return;
    event.preventDefault();
    if (intent === "next") target.click();
    focusRelativeEnterTarget(
      event.currentTarget,
      target,
      intent,
      ".stock-permissions-table input[type='checkbox']:not(:disabled)"
    );
  }

  return (
    <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-permissions-title">
      <section
        className="filter-dialog stock-settings-dialog stock-permissions-dialog"
        onKeyDown={handlePermissionEnter}
      >
        <header className="filter-header">
          <div>
            <h2 id="stock-permissions-title">{t("stock.settings.permissions")}</h2>
            <span>{t("stock.permissions.subtitle")}</span>
          </div>
          <button type="button" disabled={saving} onClick={onClose}>{t("common.close")}</button>
        </header>

        <div className="stock-permissions-table-scroll">
          <table
            className="report-table stock-permissions-table"
            style={{ tableLayout: "fixed", minWidth: tableWidth }}
          >
            <thead>
              <tr>
                {visibleColumns.map((column) => {
                  const permission = stockPermissionMatrixColumns.find((candidate) => candidate.code === column.key);
                  const label = column.key === "role"
                    ? t("stock.permissions.role")
                    : t(permission?.labelKey ?? column.key);
                  return (
                    <TableLayoutHeaderCell
                      column={column}
                      key={column.key}
                      resizeLabel={`${t("stock.columns.resize")} ${label}`}
                      onReorder={tableLayout.reorderColumns}
                      onMove={tableLayout.moveColumn}
                      onResize={tableLayout.resizeColumn}
                    >
                      {label}
                    </TableLayoutHeaderCell>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {roles.map((role) => (
                <tr key={role.id}>
                  {visibleColumns.map((column) => {
                    if (column.key === "role") {
                      return <td key={column.key}>{role.name}</td>;
                    }
                    const permission = stockPermissionMatrixColumns.find((candidate) => candidate.code === column.key);
                    if (!permission) return null;
                    return (
                      <td key={permission.code} aria-label={`${role.name}: ${permission.code}`}>
                        <input
                          type="checkbox"
                          aria-label={`${role.name}: ${t(permission.labelKey)}`}
                          checked={roleHasStockPermission(role, permission.code)}
                          disabled={loading || saving || role.protectedRole || role.permissions.includes("ALL")}
                          onChange={(event) => togglePermission(role.id, permission.code, event.target.checked)}
                        />
                      </td>
                    );
                  })}
                </tr>
              ))}
              {!loading && roles.length === 0 && (
                <tr><td colSpan={visibleColumns.length}>{t("stock.permissions.empty")}</td></tr>
              )}
            </tbody>
          </table>
        </div>

        {(loading || saving || status) && (
          <p
            className={`stock-operation-status ${status?.kind === "error" ? "error" : loading || saving ? "loading" : ""}`}
            aria-live="polite"
          >
            {loading || saving ? t("common.loading") : status?.message}
          </p>
        )}
        <footer className="filter-actions">
          <button type="button" disabled={saving} onClick={onClose}>{t("common.close")}</button>
          <button
            type="button"
            disabled={loading || saving || !token || dirtyRoles.length === 0}
            onClick={() => void savePermissions()}
          >
            {t("common.save")}
          </button>
        </footer>
      </section>
    </div>
  );
}

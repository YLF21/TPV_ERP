import { useEffect, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { enterNavigationIntent } from "./keyboardNavigation";
import { ErpSelect } from "./ErpSelect";

export type StockSettingsView = {
  defaultWarehouseId: string;
  allowNegativeStock: boolean;
  defaultMinimumStock: number;
  alertsEnabled: boolean;
};

export type StockSettingsWarehouse = {
  id: string;
  name?: string | null;
  nombre?: string | null;
  active?: boolean;
};

export type StockSecurityRole = {
  id: string;
  name: string;
  protectedRole: boolean;
  permissions: string[];
};

export type StockSettingsMode = "configuration" | "permissions";

type StockSettingsDialogProps = {
  open: boolean;
  mode: StockSettingsMode;
  locale: LocaleCode;
  token?: string;
  warehouses: StockSettingsWarehouse[];
  selectedProduct?: { id: string; name: string } | null;
  selectedWarehouseId?: string;
  isAdmin: boolean;
  canEdit: boolean;
  onClose: () => void;
  onSaved?: (settings: StockSettingsView) => void;
};

export const stockPermissionMatrixColumns = [
  { code: "STOCK_READ", labelKey: "stock.permissions.read" },
  { code: "STOCK_ADJUST", labelKey: "stock.permissions.adjust" },
  { code: "STOCK_TRANSFER", labelKey: "stock.permissions.transfer" },
  { code: "WAREHOUSES_MANAGE", labelKey: "stock.permissions.warehouses" },
  { code: "WAREHOUSE_OUTPUTS_READ", labelKey: "stock.permissions.outputsRead" },
  { code: "WAREHOUSE_OUTPUTS_EDIT", labelKey: "stock.permissions.outputsEdit" },
  { code: "WAREHOUSE_OUTPUTS_DELETE", labelKey: "stock.permissions.outputsDelete" },
  { code: "WAREHOUSE_OUTPUTS_CONFIRM", labelKey: "stock.permissions.outputsConfirm" },
  { code: "GESTION_PRODUCTO", labelKey: "stock.permissions.productManagement" }
] as const;

const emptySettings: StockSettingsView = {
  defaultWarehouseId: "",
  allowNegativeStock: false,
  defaultMinimumStock: 0,
  alertsEnabled: true
};

export function stockMinimumPath(productId: string, warehouseId: string) {
  return `/stock/minimums/${encodeURIComponent(productId)}/${encodeURIComponent(warehouseId)}`;
}

export function roleHasStockPermission(role: Pick<StockSecurityRole, "permissions">, code: string) {
  return role.permissions.includes("ALL") || role.permissions.includes(code);
}

export function normalizeStockMinimum(value: unknown) {
  if (typeof value === "number") {
    return value;
  }
  if (!value || typeof value !== "object") {
    return null;
  }
  const candidate = value as { minimumStock?: unknown; minimum?: unknown; quantity?: unknown };
  const parsed = Number(candidate.minimumStock ?? candidate.minimum ?? candidate.quantity);
  return Number.isFinite(parsed) ? parsed : null;
}

export function StockSettingsDialog({
  open,
  mode,
  locale,
  token,
  warehouses,
  selectedProduct,
  selectedWarehouseId,
  isAdmin,
  canEdit,
  onClose,
  onSaved
}: StockSettingsDialogProps) {
  const t = createTranslator(locale);
  const activeWarehouses = warehouses.filter((warehouse) => warehouse.active !== false);
  const [settings, setSettings] = useState<StockSettingsView>(emptySettings);
  const [minimumStock, setMinimumStock] = useState("");
  const [minimumExists, setMinimumExists] = useState(false);
  const [roles, setRoles] = useState<StockSecurityRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState("");
  const defaultMinimumRef = useRef<HTMLInputElement | null>(null);
  const allowNegativeRef = useRef<HTMLInputElement | null>(null);
  const alertsRef = useRef<HTMLInputElement | null>(null);
  const minimumRef = useRef<HTMLInputElement | null>(null);
  const settingsSaveRef = useRef<HTMLButtonElement | null>(null);
  const minimumWarehouseId = selectedWarehouseId || settings.defaultWarehouseId;

  useEffect(() => {
    if (!open) {
      return;
    }
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose, open]);

  useEffect(() => {
    let cancelled = false;
    if (!open || !token) {
      return;
    }
    setLoading(true);
    setStatus("");
    if (mode === "permissions") {
      if (!isAdmin) {
        setRoles([]);
        setLoading(false);
        setStatus(t("stock.permissions.adminOnly"));
        return;
      }
      void apiRequest<StockSecurityRole[]>("/roles", { token })
        .then((result) => {
          if (!cancelled) {
            setRoles(result);
          }
        })
        .catch((error) => {
          if (!cancelled) {
            setRoles([]);
            setStatus(error instanceof Error ? error.message : t("stock.permissions.loadError"));
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
    }

    void apiRequest<StockSettingsView>("/stock/settings", { token })
      .then((result) => {
        if (!cancelled) {
          setSettings({
            defaultWarehouseId: result.defaultWarehouseId || activeWarehouses[0]?.id || "",
            allowNegativeStock: Boolean(result.allowNegativeStock),
            defaultMinimumStock: Number(result.defaultMinimumStock) || 0,
            alertsEnabled: Boolean(result.alertsEnabled)
          });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setSettings((current) => ({
            ...current,
            defaultWarehouseId: current.defaultWarehouseId || activeWarehouses[0]?.id || ""
          }));
          setStatus(error instanceof Error ? error.message : t("stock.settings.loadError"));
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
  }, [isAdmin, mode, open, token]);

  useEffect(() => {
    let cancelled = false;
    if (!open || mode !== "configuration" || !token || !selectedProduct?.id || !minimumWarehouseId) {
      setMinimumStock("");
      setMinimumExists(false);
      return;
    }
    void apiRequest<unknown>(stockMinimumPath(selectedProduct.id, minimumWarehouseId), { token })
      .then((result) => {
        if (cancelled) {
          return;
        }
        const minimum = normalizeStockMinimum(result);
        setMinimumStock(minimum === null ? "" : String(minimum));
        setMinimumExists(minimum !== null);
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        if (error instanceof ApiError && error.status === 404) {
          setMinimumStock("");
          setMinimumExists(false);
          return;
        }
        setStatus(error instanceof Error ? error.message : t("stock.minimum.loadError"));
      });
    return () => {
      cancelled = true;
    };
  }, [minimumWarehouseId, mode, open, selectedProduct?.id, token]);

  if (!open) {
    return null;
  }

  async function saveSettings() {
    if (!token || !canEdit || saving) {
      return;
    }
    setSaving(true);
    setStatus(t("stock.settings.saving"));
    try {
      const saved = await apiRequest<StockSettingsView>("/stock/settings", {
        token,
        method: "PUT",
        body: settings
      });
      setSettings(saved);
      setStatus(t("stock.settings.saved"));
      onSaved?.(saved);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("stock.settings.saveError"));
    } finally {
      setSaving(false);
    }
  }

  async function saveMinimum() {
    const parsed = Number(minimumStock);
    if (!token || !canEdit || !selectedProduct?.id || !minimumWarehouseId || !Number.isFinite(parsed) || parsed < 0) {
      setStatus(t("stock.minimum.invalid"));
      return;
    }
    setSaving(true);
    setStatus(t("stock.minimum.saving"));
    try {
      await apiRequest(stockMinimumPath(selectedProduct.id, minimumWarehouseId), {
        token,
        method: "PUT",
        body: { minimumStock: parsed }
      });
      setMinimumExists(true);
      setStatus(t("stock.minimum.saved"));
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("stock.minimum.saveError"));
    } finally {
      setSaving(false);
    }
  }

  async function clearMinimum() {
    if (!token || !canEdit || !selectedProduct?.id || !minimumWarehouseId || !minimumExists) {
      return;
    }
    setSaving(true);
    try {
      await apiRequest(stockMinimumPath(selectedProduct.id, minimumWarehouseId), { token, method: "DELETE" });
      setMinimumStock("");
      setMinimumExists(false);
      setStatus(t("stock.minimum.deleted"));
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("stock.minimum.deleteError"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="filter-overlay stock-settings-overlay" role="dialog" aria-modal="true" aria-labelledby="stock-settings-title">
      <section className="filter-dialog stock-settings-dialog">
        <header className="filter-header">
          <div>
            <h2 id="stock-settings-title">
              {t(mode === "configuration" ? "stock.settings.configuration" : "stock.settings.permissions")}
            </h2>
            <span>{t(mode === "configuration" ? "stock.settings.subtitle" : "stock.permissions.subtitle")}</span>
          </div>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </header>

        {mode === "configuration" ? (
          <div className="stock-settings-content">
            <div className="stock-settings-form">
              <div className="stock-settings-field">
                <span>{t("stock.settings.defaultWarehouse")}</span>
                <ErpSelect
                  aria-label={t("stock.settings.defaultWarehouse")}
                  value={settings.defaultWarehouseId}
                  disabled={!canEdit}
                  options={[
                    { value: "", label: t("common.select") },
                    ...activeWarehouses.map((warehouse) => ({
                      value: warehouse.id,
                      label: warehouse.name ?? warehouse.nombre ?? warehouse.id
                    }))
                  ]}
                  onChange={(next) => setSettings((current) => ({ ...current, defaultWarehouseId: next }))}
                  onCommit={() => defaultMinimumRef.current?.focus()}
                />
              </div>
              <label>
                <span>{t("stock.settings.defaultMinimum")}</span>
                <input
                  ref={defaultMinimumRef}
                  type="number"
                  min="0"
                  step="1"
                  value={settings.defaultMinimumStock}
                  disabled={!canEdit}
                  onChange={(event) => setSettings((current) => ({ ...current, defaultMinimumStock: Number(event.target.value) }))}
                  onKeyDown={(event) => {
                    const intent = enterNavigationIntent(event.key, event);
                    if (!intent) return;
                    event.preventDefault();
                    if (intent === "next") allowNegativeRef.current?.focus();
                  }}
                />
              </label>
              <label className="stock-settings-check">
                <input
                  ref={allowNegativeRef}
                  type="checkbox"
                  checked={settings.allowNegativeStock}
                  disabled={!canEdit}
                  onChange={(event) => setSettings((current) => ({ ...current, allowNegativeStock: event.target.checked }))}
                  onKeyDown={(event) => {
                    const intent = enterNavigationIntent(event.key, event);
                    if (!intent) return;
                    event.preventDefault();
                    if (intent === "next") {
                      event.currentTarget.click();
                      alertsRef.current?.focus();
                    } else {
                      defaultMinimumRef.current?.focus();
                    }
                  }}
                />
                <span>{t("stock.settings.allowNegative")}</span>
              </label>
              <label className="stock-settings-check">
                <input
                  ref={alertsRef}
                  type="checkbox"
                  checked={settings.alertsEnabled}
                  disabled={!canEdit}
                  onChange={(event) => setSettings((current) => ({ ...current, alertsEnabled: event.target.checked }))}
                  onKeyDown={(event) => {
                    const intent = enterNavigationIntent(event.key, event);
                    if (!intent) return;
                    event.preventDefault();
                    if (intent === "next") {
                      event.currentTarget.click();
                      if (minimumRef.current && !minimumRef.current.disabled) minimumRef.current.focus();
                      else settingsSaveRef.current?.focus();
                    } else {
                      allowNegativeRef.current?.focus();
                    }
                  }}
                />
                <span>{t("stock.settings.alerts")}</span>
              </label>
            </div>

            <section className="stock-minimum-editor" aria-label={t("stock.minimum.title")}>
              <div>
                <strong>{t("stock.minimum.title")}</strong>
                <span>
                  {selectedProduct
                    ? `${selectedProduct.name} / ${warehouseName(activeWarehouses, minimumWarehouseId)}`
                    : t("stock.minimum.selectProduct")}
                </span>
              </div>
              <label>
                <span>{t("stock.minimum.quantity")}</span>
                <input
                  ref={minimumRef}
                  type="number"
                  min="0"
                  step="1"
                  value={minimumStock}
                  disabled={!canEdit || !selectedProduct || !minimumWarehouseId}
                  placeholder={String(settings.defaultMinimumStock)}
                  onChange={(event) => setMinimumStock(event.target.value)}
                  onKeyDown={(event) => {
                    const intent = enterNavigationIntent(event.key, event);
                    if (intent === "next") {
                      event.preventDefault();
                      void saveMinimum();
                    } else if (intent === "previous") {
                      event.preventDefault();
                      alertsRef.current?.focus();
                    }
                  }}
                />
              </label>
              <button type="button" disabled={!canEdit || !selectedProduct || !minimumWarehouseId || saving} onClick={() => void saveMinimum()}>
                {t("stock.minimum.save")}
              </button>
              <button type="button" disabled={!canEdit || !minimumExists || saving} onClick={() => void clearMinimum()}>
                {t("stock.minimum.useGeneral")}
              </button>
            </section>
          </div>
        ) : (
          <div className="stock-permissions-table-scroll">
            <table className="report-table stock-permissions-table">
              <thead>
                <tr>
                  <th>{t("stock.permissions.role")}</th>
                  {stockPermissionMatrixColumns.map((permission) => (
                    <th key={permission.code}>{t(permission.labelKey)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {roles.map((role) => (
                  <tr key={role.id}>
                    <td>{role.name}</td>
                    {stockPermissionMatrixColumns.map((permission) => (
                      <td key={permission.code} aria-label={`${role.name}: ${permission.code}`}>
                        {roleHasStockPermission(role, permission.code) ? t("common.yes") : t("common.no")}
                      </td>
                    ))}
                  </tr>
                ))}
                {!loading && isAdmin && roles.length === 0 && (
                  <tr><td colSpan={stockPermissionMatrixColumns.length + 1}>{t("stock.permissions.empty")}</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}

        {(loading || status) && (
          <p className={`stock-operation-status ${status && !loading ? "" : "loading"}`} aria-live="polite">
            {loading ? t("common.loading") : status}
          </p>
        )}

        <footer className="filter-actions">
          <button type="button" onClick={onClose}>{t("common.close")}</button>
          {mode === "configuration" && (
            <button ref={settingsSaveRef} type="button" disabled={!canEdit || saving} onClick={() => void saveSettings()}>
              {saving ? t("stock.settings.saving") : t("common.save")}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}

function warehouseName(warehouses: StockSettingsWarehouse[], warehouseId: string) {
  const warehouse = warehouses.find((candidate) => candidate.id === warehouseId);
  return warehouse?.name ?? warehouse?.nombre ?? (warehouseId || "-");
}

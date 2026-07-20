import { useEffect, useMemo, useState, type FormEvent } from "react";
import { ApiError, userCanManageWarehouses, type UserSession } from "@tpverp/app-common";
import {
  createManagedWarehouse,
  loadManagedWarehouses,
  renameManagedWarehouse,
  setManagedWarehouseActive,
  type WarehouseManagementRecord
} from "./warehouseManagementApi";

type WarehouseManagementScreenProps = {
  session: UserSession;
  t: (key: string) => string;
};

type WarehouseForm = {
  mode: "create" | "rename";
  warehouse?: WarehouseManagementRecord;
};

function sortedWarehouses(warehouses: WarehouseManagementRecord[]) {
  return [...warehouses].sort((left, right) => (
    Number(right.defaultWarehouse) - Number(left.defaultWarehouse)
      || left.name.localeCompare(right.name, "es")
  ));
}

function operationErrorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError || error instanceof Error ? error.message : fallback;
}

export function WarehouseManagementScreen({ session, t }: WarehouseManagementScreenProps) {
  const canManage = userCanManageWarehouses(session);
  const token = session.accessToken ?? "";
  const loadErrorFallback = t("warehouse.management.loadError");
  const [warehouses, setWarehouses] = useState<WarehouseManagementRecord[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(canManage);
  const [loadError, setLoadError] = useState("");
  const [operationStatus, setOperationStatus] = useState("");
  const [reloadCounter, setReloadCounter] = useState(0);
  const [form, setForm] = useState<WarehouseForm | null>(null);
  const [name, setName] = useState("");
  const [formError, setFormError] = useState("");
  const [pendingActiveChange, setPendingActiveChange] = useState<WarehouseManagementRecord | null>(null);
  const [confirmationError, setConfirmationError] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!canManage) return;
    let cancelled = false;
    setLoading(true);
    setLoadError("");
    void loadManagedWarehouses(token)
      .then((loaded) => {
        if (cancelled) return;
        const next = sortedWarehouses(loaded);
        setWarehouses(next);
        setSelectedId((current) => next.some((warehouse) => warehouse.id === current)
          ? current
          : next[0]?.id ?? "");
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadError(operationErrorMessage(error, loadErrorFallback));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [canManage, loadErrorFallback, reloadCounter, token]);

  const visibleWarehouses = useMemo(() => {
    const normalizedSearch = search.trim().toLocaleLowerCase("es");
    return normalizedSearch
      ? warehouses.filter((warehouse) => warehouse.name.toLocaleLowerCase("es").includes(normalizedSearch))
      : warehouses;
  }, [search, warehouses]);

  const selected = warehouses.find((warehouse) => warehouse.id === selectedId) ?? null;

  function openCreate() {
    setName("");
    setFormError("");
    setOperationStatus("");
    setForm({ mode: "create" });
  }

  function openRename(warehouse: WarehouseManagementRecord) {
    setName(warehouse.name);
    setFormError("");
    setOperationStatus("");
    setForm({ mode: "rename", warehouse });
  }

  function replaceWarehouse(updated: WarehouseManagementRecord) {
    setWarehouses((current) => sortedWarehouses(current.map((warehouse) => (
      warehouse.id === updated.id ? updated : warehouse
    ))));
    setSelectedId(updated.id);
  }

  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form || saving) return;
    const normalizedName = name.trim();
    if (!normalizedName) {
      setFormError(t("warehouse.management.nameRequired"));
      return;
    }
    setSaving(true);
    setFormError("");
    try {
      if (form.mode === "create") {
        const created = await createManagedWarehouse(normalizedName, token);
        setWarehouses((current) => sortedWarehouses([...current, created]));
        setSelectedId(created.id);
        setOperationStatus(t("warehouse.management.created"));
      } else if (form.warehouse) {
        const updated = await renameManagedWarehouse(form.warehouse.id, normalizedName, token);
        replaceWarehouse(updated);
        setOperationStatus(t("warehouse.management.renamed"));
      }
      setForm(null);
    } catch (error) {
      setFormError(operationErrorMessage(error, t("warehouse.management.saveError")));
    } finally {
      setSaving(false);
    }
  }

  async function confirmActiveChange() {
    if (!pendingActiveChange || saving) return;
    setSaving(true);
    setConfirmationError("");
    try {
      const updated = await setManagedWarehouseActive(
        pendingActiveChange.id,
        !pendingActiveChange.active,
        token
      );
      replaceWarehouse(updated);
      setOperationStatus(updated.active
        ? t("warehouse.management.activated")
        : t("warehouse.management.deactivated"));
      setPendingActiveChange(null);
    } catch (error) {
      setConfirmationError(operationErrorMessage(error, t("warehouse.management.statusError")));
    } finally {
      setSaving(false);
    }
  }

  if (!canManage) {
    return <div className="gestion-security-state error" role="alert">{t("warehouse.management.noAccess")}</div>;
  }

  return (
    <section className="gestion-warehouse-workspace" aria-labelledby="warehouse-management-title">
      <header className="gestion-warehouse-header">
        <div>
          <span>{t("warehouse.management.section")}</span>
          <h2 id="warehouse-management-title">{t("warehouse.management.title")}</h2>
          <p>{t("warehouse.management.subtitle")}</p>
        </div>
        <button type="button" onClick={openCreate}>{t("warehouse.management.create")}</button>
      </header>

      <div className="gestion-warehouse-toolbar">
        <label>
          <span>{t("warehouse.management.search")}</span>
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            placeholder={t("warehouse.management.searchPlaceholder")}
          />
        </label>
        <span>{t("warehouse.management.results").replace("{count}", String(visibleWarehouses.length))}</span>
      </div>

      {operationStatus && <p className="gestion-warehouse-operation-status" role="status">{operationStatus}</p>}

      {loading ? (
        <div className="gestion-security-state">{t("common.loading")}</div>
      ) : loadError ? (
        <div className="gestion-security-state error" role="alert">
          <span>{loadError}</span>
          <button type="button" onClick={() => setReloadCounter((current) => current + 1)}>
            {t("warehouse.management.retry")}
          </button>
        </div>
      ) : (
        <div className="gestion-warehouse-grid">
          <section className="gestion-warehouse-list" aria-label={t("warehouse.management.list")}>
            <div className="gestion-warehouse-row head" role="row">
              <span role="columnheader">{t("warehouse.management.column.name")}</span>
              <span role="columnheader">{t("warehouse.management.column.default")}</span>
              <span role="columnheader">{t("warehouse.management.column.status")}</span>
            </div>
            {visibleWarehouses.map((warehouse) => (
              <button
                type="button"
                role="row"
                className={`gestion-warehouse-row${selectedId === warehouse.id ? " selected" : ""}`}
                key={warehouse.id}
                aria-label={`${warehouse.name} ${warehouse.active ? t("warehouse.management.active") : t("warehouse.management.inactive")}`}
                aria-selected={selectedId === warehouse.id}
                onClick={() => setSelectedId(warehouse.id)}
              >
                <span role="cell"><strong>{warehouse.name}</strong></span>
                <span role="cell">{warehouse.defaultWarehouse ? t("common.yes") : t("common.no")}</span>
                <span role="cell">
                  <b className={`gestion-security-status ${warehouse.active ? "active" : "inactive"}`}>
                    {warehouse.active ? t("warehouse.management.active") : t("warehouse.management.inactive")}
                  </b>
                </span>
              </button>
            ))}
            {visibleWarehouses.length === 0 && (
              <div className="gestion-security-state">{t("warehouse.management.empty")}</div>
            )}
          </section>

          <aside className="gestion-warehouse-detail">
            {selected ? (
              <>
                <header>
                  <div>
                    <span>{t("warehouse.management.detail")}</span>
                    <h3>{selected.name}</h3>
                  </div>
                  <b className={`gestion-security-status ${selected.active ? "active" : "inactive"}`}>
                    {selected.active ? t("warehouse.management.active") : t("warehouse.management.inactive")}
                  </b>
                </header>
                <dl>
                  <div>
                    <dt>{t("warehouse.management.column.default")}</dt>
                    <dd>{selected.defaultWarehouse ? t("common.yes") : t("common.no")}</dd>
                  </div>
                  <div>
                    <dt>{t("warehouse.management.identifier")}</dt>
                    <dd>{selected.id}</dd>
                  </div>
                </dl>
                {selected.defaultWarehouse ? (
                  <div className="gestion-security-notice">
                    <strong>{t("warehouse.management.generalProtected")}</strong>
                    <p>{t("warehouse.management.generalProtectedDetail")}</p>
                  </div>
                ) : (
                  <div className="gestion-security-actions">
                    <button type="button" onClick={() => openRename(selected)}>
                      {t("warehouse.management.rename")}
                    </button>
                    <button
                      type="button"
                      className={selected.active ? "danger" : undefined}
                      onClick={() => {
                        setConfirmationError("");
                        setOperationStatus("");
                        setPendingActiveChange(selected);
                      }}
                    >
                      {selected.active
                        ? t("warehouse.management.deactivate")
                        : t("warehouse.management.activate")}
                    </button>
                  </div>
                )}
              </>
            ) : (
              <div className="gestion-security-state">{t("warehouse.management.select")}</div>
            )}
          </aside>
        </div>
      )}

      {form && (
        <div className="gestion-modal-backdrop">
          <section
            className="gestion-security-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="warehouse-form-title"
          >
            <header>
              <h2 id="warehouse-form-title">
                {form.mode === "create"
                  ? t("warehouse.management.dialog.create")
                  : t("warehouse.management.dialog.rename")}
              </h2>
              <button type="button" aria-label={t("common.close")} onClick={() => setForm(null)}>×</button>
            </header>
            <form className="gestion-security-form" onSubmit={(event) => void submitForm(event)}>
              <label>
                {t("warehouse.management.name")}
                <input
                  autoFocus
                  required
                  maxLength={128}
                  value={name}
                  onChange={(event) => setName(event.currentTarget.value)}
                />
              </label>
              {formError && <p className="gestion-inline-error" role="alert">{formError}</p>}
              <footer>
                <button type="button" disabled={saving} onClick={() => setForm(null)}>{t("common.cancel")}</button>
                <button type="submit" disabled={saving}>{saving ? t("warehouse.management.saving") : t("common.save")}</button>
              </footer>
            </form>
          </section>
        </div>
      )}

      {pendingActiveChange && (
        <div className="gestion-modal-backdrop">
          <section
            className="gestion-security-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="warehouse-confirm-title"
          >
            <header>
              <h2 id="warehouse-confirm-title">
                {pendingActiveChange.active
                  ? t("warehouse.management.dialog.deactivate")
                  : t("warehouse.management.dialog.activate")}
              </h2>
              <button type="button" aria-label={t("common.close")} onClick={() => setPendingActiveChange(null)}>×</button>
            </header>
            <div className="gestion-confirm-content">
              <p>{(pendingActiveChange.active
                ? t("warehouse.management.confirmDeactivate")
                : t("warehouse.management.confirmActivate"))
                .replace("{name}", pendingActiveChange.name)}</p>
              {pendingActiveChange.active && (
                <p className="gestion-confirm-warning">{t("warehouse.management.zeroStockWarning")}</p>
              )}
              {confirmationError && <p className="gestion-inline-error" role="alert">{confirmationError}</p>}
              <footer>
                <button type="button" disabled={saving} onClick={() => setPendingActiveChange(null)}>{t("common.cancel")}</button>
                <button
                  type="button"
                  className={pendingActiveChange.active ? "danger" : undefined}
                  disabled={saving}
                  onClick={() => void confirmActiveChange()}
                >
                  {saving
                    ? t("warehouse.management.saving")
                    : pendingActiveChange.active
                      ? t("warehouse.management.deactivate")
                      : t("warehouse.management.activate")}
                </button>
              </footer>
            </div>
          </section>
        </div>
      )}
    </section>
  );
}

export default WarehouseManagementScreen;

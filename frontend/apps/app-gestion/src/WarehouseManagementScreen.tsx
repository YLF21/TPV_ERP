import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, userCanManageWarehouses, type UserSession } from "@tpverp/app-common";
import { ArrowDown, ArrowUp, ArrowsLeftRight, Cube, Gear, MapPin, Note, Package, WarningCircle } from "@phosphor-icons/react";
import { activateModalFocusTrap, type ModalFocusRoot } from "../../../packages/app-common/src/components/modalFocusTrap";
import {
  applyWarehouseStockConfigurationToAll, createManagedWarehouse, deleteManagedWarehouse,
  loadGeneralStockConfiguration, loadManagedWarehouses, loadWarehouseOverview,
  loadWarehouseStockConfiguration, renameManagedWarehouse, resetWarehouseStockConfiguration,
  saveGeneralStockConfiguration, saveInactiveProductSales, saveWarehouseStockConfiguration,
  setManagedWarehouseActive, type GeneralStockConfiguration, type WarehouseDetailsInput,
  type WarehouseManagementRecord, type WarehouseOverview, type WarehouseStockConfiguration
} from "./warehouseManagementApi";

type Props = { session: UserSession; t: (key: string) => string;
  locale?: "es" | "en" | "zh";
  onCreateDocument?: (kind: "input" | "output" | "transfer") => void };
type Dialog = "create" | "general" | "warehouse" | null;
const blank: WarehouseDetailsInput = { name: "", address: "", notes: "" };
const sortWarehouses = (items: WarehouseManagementRecord[]) => [...items].sort((a, b) =>
  Number(b.defaultWarehouse) - Number(a.defaultWarehouse) || a.name.localeCompare(b.name, "es"));

export function WarehouseManagementScreen({ session, t, locale = "es", onCreateDocument }: Props) {
  const quantity = (value: number) => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { maximumFractionDigits: 3 }).format(value);
  const token = session.accessToken ?? "";
  const canManage = userCanManageWarehouses(session);
  const canCreateDocuments = session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_ALMACEN");
  const canTransfer = canCreateDocuments || session.permissions.includes("STOCK_TRANSFER");
  const canDelete = session.permissions.includes("ADMIN") || session.permissions.includes("WAREHOUSES_MANAGE");
  const canManageInactive = session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_PRODUCTO");
  const [warehouses, setWarehouses] = useState<WarehouseManagementRecord[]>([]);
  const [overview, setOverview] = useState<Map<string, WarehouseOverview>>(new Map());
  const [general, setGeneral] = useState<GeneralStockConfiguration | null>(null);
  const [bulk, setBulk] = useState({ allowNegativeStock: false, defaultMinimumStock: 0, alertsEnabled: false });
  const [details, setDetails] = useState<WarehouseDetailsInput>(blank);
  const [configuration, setConfiguration] = useState<WarehouseStockConfiguration | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [pending, setPending] = useState<{ warehouse: WarehouseManagementRecord; action: "delete" | "activate" | "deactivate" } | null>(null);
  const [loading, setLoading] = useState(canManage);
  const [loadError, setLoadError] = useState("");
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const dialogRef = useRef<HTMLElement>(null);
  const selected = warehouses.find((warehouse) => warehouse.id === selectedId) ?? null;

  useEffect(() => {
    if (!canManage) return;
    let cancelled = false;
    setLoading(true); setLoadError("");
    void Promise.all([loadManagedWarehouses(token), loadWarehouseOverview(token), loadGeneralStockConfiguration(token)])
      .then(([items, totals, settings]) => {
        if (cancelled) return;
        setWarehouses(sortWarehouses(items));
        setOverview(new Map(totals.map((item) => [item.warehouseId, item])));
        setGeneral(settings);
      }).catch(() => { if (!cancelled) setLoadError(t("warehouse.management.loadError")); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [canManage, reloadKey, t, token]);

  useEffect(() => {
    if (!dialog && !pending) return;
    if (dialogRef.current) return activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document);
  }, [dialog, pending]);

  const refresh = () => setReloadKey((value) => value + 1);
  const close = () => { setDialog(null); setError(""); };

  function openCreate() { setDetails(blank); setStatus(""); setError(""); setDialog("create"); }
  function openGeneral() {
    if (!general) return;
    setBulk({ allowNegativeStock: general.allowNegativeStock,
      defaultMinimumStock: general.defaultMinimumStock, alertsEnabled: general.alertsEnabled });
    setError(""); setStatus(""); setDialog("general");
  }
  async function openWarehouse(warehouse: WarehouseManagementRecord) {
    setSelectedId(warehouse.id);
    setDetails({ name: warehouse.name, address: warehouse.address ?? "", notes: warehouse.notes ?? "" });
    setConfiguration(null); setError(""); setStatus(""); setDialog("warehouse");
    try { setConfiguration(await loadWarehouseStockConfiguration(warehouse.id, token)); }
    catch { setError(t("warehouse.management.settingsLoadError")); }
  }

  async function saveDetails(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || !details.name.trim()) return;
    const input = { name: details.name.trim(), address: details.address?.trim() ?? "", notes: details.notes.trim() };
    setSaving(true); setError("");
    try {
      if (dialog === "create") {
        const created = await createManagedWarehouse(input, token);
        setWarehouses((current) => sortWarehouses([...current, created]));
        setStatus(t("warehouse.management.created"));
      } else if (selected) {
        const updated = await renameManagedWarehouse(selected.id,
          selected.defaultWarehouse ? { ...input, address: null } : input, token);
        setWarehouses((current) => sortWarehouses(current.map((item) => item.id === updated.id ? updated : item)));
        setStatus(t("warehouse.management.renamed"));
      }
      if (dialog === "create") close();
      refresh();
    } catch { setError(t("warehouse.management.saveError")); }
    finally { setSaving(false); }
  }
  async function saveConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || !configuration || saving) return;
    setSaving(true); setError("");
    try { setConfiguration(await saveWarehouseStockConfiguration(selected.id, configuration, token));
      setStatus(t("warehouse.management.settingsSaved")); refresh(); }
    catch { setError(t("warehouse.management.settingsSaveError")); }
    finally { setSaving(false); }
  }
  async function resetConfiguration() {
    if (!selected || saving) return;
    setSaving(true); setError("");
    try { setConfiguration(await resetWarehouseStockConfiguration(selected.id, token));
      setStatus(t("warehouse.management.settingsSaved")); refresh(); }
    catch { setError(t("warehouse.management.settingsSaveError")); }
    finally { setSaving(false); }
  }
  async function applyGeneral(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving) return;
    setSaving(true); setError("");
    try { setGeneral(await applyWarehouseStockConfigurationToAll(bulk, token));
      setStatus(t("warehouse.management.bulkSaved")); close(); refresh(); }
    catch { setError(t("warehouse.management.settingsSaveError")); }
    finally { setSaving(false); }
  }
  async function saveDefaultWarehouse() {
    if (!general || saving) return;
    setSaving(true); setError("");
    try { setGeneral(await saveGeneralStockConfiguration(general.defaultWarehouseId, token));
      setStatus(t("warehouse.management.settingsSaved")); }
    catch { setError(t("warehouse.management.settingsSaveError")); }
    finally { setSaving(false); }
  }
  async function toggleInactive(value: boolean) {
    if (!general || saving) return;
    setSaving(true); setError("");
    try { setGeneral(await saveInactiveProductSales(value, token)); }
    catch { setError(t("warehouse.management.settingsSaveError")); }
    finally { setSaving(false); }
  }
  async function confirmAction() {
    if (!pending || saving) return;
    setSaving(true); setError("");
    const { warehouse, action } = pending;
    try {
      if (action === "delete") { await deleteManagedWarehouse(warehouse.id, token);
        setStatus(t("warehouse.management.deleted")); }
      else { await setManagedWarehouseActive(warehouse.id, action === "activate", token);
        setStatus(t(action === "activate" ? "warehouse.management.activated" : "warehouse.management.deactivated")); }
      setPending(null); refresh();
    } catch (cause) {
      setError(action === "deactivate" && cause instanceof ApiError && cause.status === 409
        ? t("warehouse.management.zeroStockWarning")
        : t(action === "delete" ? "warehouse.management.deleteError" : "warehouse.management.statusError"));
    } finally { setSaving(false); }
  }

  if (!canManage) return <div className="gestion-security-state error" role="alert">{t("warehouse.management.noAccess")}</div>;
  return <section className="gestion-warehouse-workspace" aria-labelledby="warehouse-management-title">
    <header className="gestion-warehouse-header"><div><h2 id="warehouse-management-title">{t("warehouse.management.title")}</h2>
      <p>{t("warehouse.management.subtitle")}</p></div><div className="gestion-warehouse-header-actions">
      <button type="button" onClick={openCreate}>{t("warehouse.management.create")}</button>
      <button type="button" disabled={!general} onClick={openGeneral}>{t("warehouse.management.generalSettings")}</button></div></header>
    <div className="gestion-warehouse-document-actions">
      {canCreateDocuments && <button type="button" onClick={() => onCreateDocument?.("input")}><ArrowDown size={18} aria-hidden="true" />{t("warehouse.management.createInput")}</button>}
      {canCreateDocuments && <button type="button" onClick={() => onCreateDocument?.("output")}><ArrowUp size={18} aria-hidden="true" />{t("warehouse.management.createOutput")}</button>}
      {canTransfer && <button type="button" onClick={() => onCreateDocument?.("transfer")}><ArrowsLeftRight size={18} aria-hidden="true" />{t("warehouse.management.createTransfer")}</button>}
    </div>
    {status && <p className="gestion-warehouse-operation-status" role="status">{status}</p>}
    {loading ? <div className="gestion-security-state">{t("common.loading")}</div> : loadError ?
      <div className="gestion-security-state error" role="alert">{loadError}
        <button type="button" onClick={refresh}>{t("warehouse.management.retry")}</button></div> :
      <section className="gestion-warehouse-cards" aria-label={t("warehouse.management.list")}>
        {warehouses.map((warehouse) => { const data = overview.get(warehouse.id); return <article className="gestion-warehouse-card" key={warehouse.id}>
          <div className="gestion-warehouse-card-identity"><div className="gestion-warehouse-card-heading">
            <strong>{warehouse.name}</strong>
            {general?.defaultWarehouseId === warehouse.id && <span className="default">{t("warehouse.management.column.default")}</span>}
            <span className={warehouse.active ? "active" : "inactive"}>{t(warehouse.active
              ? "warehouse.management.active" : "warehouse.management.inactive")}</span></div>
            <p><MapPin size={17} aria-hidden="true" /><span><b>{t("warehouse.management.address")}:</b> {warehouse.address || "—"}</span></p>
            <p><Note size={17} aria-hidden="true" /><span><b>{t("warehouse.management.notes")}:</b> {warehouse.defaultWarehouse
              ? <>{t("warehouse.management.generalSalesNote")}{warehouse.notes ? ` ${warehouse.notes}` : ""}</>
              : warehouse.notes || "—"}</span></p></div>
          <div className="gestion-warehouse-card-settings">
            <span className="gestion-warehouse-card-settings-heading"><Gear size={18} aria-hidden="true" />
              <span>{t("warehouse.management.settingsMode")}: <b>{data ? t(data.inheritsStoreSettings
                ? "warehouse.management.settingsModeGeneral" : "warehouse.management.settingsModeOwn") : "—"}</b></span></span>
            <span>{t("warehouse.management.allowNegative")}: <b>{data ? t(data.allowNegativeStock ? "common.yes" : "common.no") : "—"}</b></span>
            <span>{t("warehouse.management.minimum")}: <b>{data ? quantity(data.defaultMinimumStock) : "—"}</b></span>
            <span>{t("warehouse.management.alerts")}: <b>{data ? t(data.alertsEnabled ? "common.yes" : "common.no") : "—"}</b></span></div>
          <div className="gestion-warehouse-card-metrics">
            <div><span><Cube size={19} aria-hidden="true" />{t("warehouse.management.productsDistinct")}</span><strong>{data ? quantity(data.productCount) : "—"}</strong></div>
            <div><span><Package size={19} aria-hidden="true" />{t("warehouse.management.unitsTotal")}</span><strong>{data ? quantity(data.totalQuantity) : "—"}</strong></div></div>
          <button type="button" onClick={() => void openWarehouse(warehouse)}>{t("warehouse.management.configure")}</button>
        </article>; })}
        {warehouses.length === 0 && <p className="gestion-security-state">{t("warehouse.management.empty")}</p>}
      </section>}

    {dialog && <div className="gestion-modal-backdrop"><section ref={dialogRef} className={`gestion-security-dialog gestion-warehouse-dialog is-${dialog}`}
      role="dialog" aria-modal="true" aria-labelledby="warehouse-dialog-title"><header>
      <h2 id="warehouse-dialog-title">{t(dialog === "create" ? "warehouse.management.dialog.create"
        : dialog === "general" ? "warehouse.management.generalSettings" : "warehouse.management.settingsTitle")}</h2>
      <button type="button" aria-label={t("common.close")} onClick={close}>×</button></header>
      {dialog === "general" ? <div className="gestion-warehouse-dialog-content">
        <form className="gestion-security-form gestion-warehouse-bulk-form" onSubmit={(event) => void applyGeneral(event)}>
          <h3>{t("warehouse.management.bulkSection")}</h3>
          <p className="gestion-warehouse-dialog-hint">{t("warehouse.management.bulkWarning").replace("{count}", String(warehouses.length))}</p>
          <label><input type="checkbox" checked={bulk.allowNegativeStock} onChange={(event) => setBulk({ ...bulk,
            allowNegativeStock: event.target.checked })} />{t("warehouse.management.allowNegative")}</label>
          <label>{t("warehouse.management.minimum")}<input type="number" min="0" step="0.001" required
            value={bulk.defaultMinimumStock} onChange={(event) => setBulk({ ...bulk, defaultMinimumStock: Number(event.target.value) })} /></label>
          <label><input type="checkbox" checked={bulk.alertsEnabled} onChange={(event) => setBulk({ ...bulk,
            alertsEnabled: event.target.checked })} />{t("warehouse.management.alerts")}</label>
          <footer><button type="button" onClick={close}>{t("common.cancel")}</button>
            <button type="submit" disabled={saving}>{t("warehouse.management.applyAll")}</button></footer></form>
        {general && <div className="gestion-warehouse-store-settings"><h3>{t("warehouse.management.storeSection")}</h3>
          <div className="gestion-warehouse-default-row"><label>{t("warehouse.management.defaultStockWarehouse")}
            <select value={general.defaultWarehouseId} onChange={(event) => setGeneral({ ...general,
              defaultWarehouseId: event.target.value })}>{warehouses.filter((item) => item.active).map((item) =>
              <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
            <button type="button" disabled={saving} onClick={() => void saveDefaultWarehouse()}>{t("warehouse.management.saveSettings")}</button></div>
          {canManageInactive && <label><input type="checkbox" checked={general.allowInactiveProductSales}
            onChange={(event) => void toggleInactive(event.target.checked)} />{t("warehouse.management.inactiveSales")}</label>}</div>}
      </div> : <div className="gestion-warehouse-dialog-content">
        <form className="gestion-security-form gestion-warehouse-details-form" onSubmit={(event) => void saveDetails(event)}>
          {dialog === "warehouse" && <h3>{t("warehouse.management.detailsSection")}</h3>}
          <label>{t("warehouse.management.name")}<input autoFocus required maxLength={128}
            disabled={dialog === "warehouse" && selected?.defaultWarehouse} value={details.name}
            onChange={(event) => setDetails({ ...details, name: event.target.value })} /></label>
          <label>{t("warehouse.management.address")}<input maxLength={512} value={details.address ?? ""}
            readOnly={dialog === "warehouse" && !!selected?.defaultWarehouse}
            onChange={(event) => setDetails({ ...details, address: event.target.value })} /></label>
          {dialog === "warehouse" && selected?.defaultWarehouse &&
            <p className="gestion-warehouse-dialog-hint">{t("warehouse.management.storeAddressHint")}</p>}
          <label>{t("warehouse.management.notes")}<textarea maxLength={4000} rows={3} value={details.notes}
            onChange={(event) => setDetails({ ...details, notes: event.target.value })} /></label>
          {dialog === "warehouse" && selected?.defaultWarehouse &&
            <p className="gestion-warehouse-dialog-hint">{t("warehouse.management.generalSalesNote")}</p>}
          <footer><button type="button" onClick={close}>{t("common.cancel")}</button>
            <button type="submit" disabled={saving}>{t("common.save")}</button></footer></form>
        {dialog === "warehouse" && selected && <>
          {configuration && <form className="gestion-security-form gestion-warehouse-config-form"
            onSubmit={(event) => void saveConfiguration(event)}><h3>{t("warehouse.management.stockSection")}</h3>
            <label><input type="checkbox" checked={configuration.allowNegativeStock} onChange={(event) =>
              setConfiguration({ ...configuration, allowNegativeStock: event.target.checked })} />{t("warehouse.management.allowNegative")}</label>
            <label>{t("warehouse.management.minimum")}<input type="number" min="0" step="0.001" required
              value={configuration.defaultMinimumStock} onChange={(event) =>
                setConfiguration({ ...configuration, defaultMinimumStock: Number(event.target.value) })} /></label>
            <label><input type="checkbox" checked={configuration.alertsEnabled} onChange={(event) =>
              setConfiguration({ ...configuration, alertsEnabled: event.target.checked })} />{t("warehouse.management.alerts")}</label>
            <footer>{!configuration.inheritsStoreSettings && <button type="button" disabled={saving}
                onClick={() => void resetConfiguration()}>{t("warehouse.management.resetSettings")}</button>}
              <button type="submit" disabled={saving || !selected.active}>{t("warehouse.management.saveSettings")}</button></footer></form>}
          {!selected.defaultWarehouse && <div className="gestion-warehouse-danger-actions">
            <button type="button" onClick={() => { close(); setPending({ warehouse: selected,
              action: selected.active ? "deactivate" : "activate" }); }}>{t(selected.active
                ? "warehouse.management.deactivate" : "warehouse.management.activate")}</button>
            {canDelete && <button type="button" className="danger" onClick={() => { close();
              setPending({ warehouse: selected, action: "delete" }); }}>{t("warehouse.management.delete")}</button>}</div>}
        </>}
      </div>}
      {error && <p className="gestion-inline-error" role="alert">{error}</p>}
      {status && <p className="gestion-warehouse-dialog-status" role="status">{status}</p>}
    </section></div>}
    {pending && <div className="gestion-modal-backdrop"><section ref={dialogRef}
      className={`gestion-security-dialog gestion-warehouse-confirm-dialog is-${pending.action}`}
      role="dialog" aria-modal="true" aria-labelledby="warehouse-action-title"><header>
      <h2 id="warehouse-action-title">{t(pending.action === "delete" ? "warehouse.management.delete"
        : pending.action === "activate" ? "warehouse.management.dialog.activate" : "warehouse.management.dialog.deactivate")}</h2>
      <button type="button" aria-label={t("common.close")} onClick={() => setPending(null)}>×</button></header>
      <div className="gestion-confirm-content"><div className="gestion-warehouse-confirm-message">
        {pending.action === "delete" && <WarningCircle size={24} weight="regular" aria-hidden="true" />}
        <p>{t(pending.action === "delete" ? "warehouse.management.confirmDelete"
        : pending.action === "activate" ? "warehouse.management.confirmActivate" : "warehouse.management.confirmDeactivate")
        .replace("{name}", pending.warehouse.name)}</p></div>
        {pending.action === "deactivate" && <p>{t("warehouse.management.zeroStockWarning")}</p>}
        {error && <p className="gestion-inline-error" role="alert">{error}</p>}
        <footer><button type="button" onClick={() => setPending(null)}>{t("common.cancel")}</button>
          <button type="button" className={pending.action === "delete" ? "danger" : undefined}
            disabled={saving} onClick={() => void confirmAction()}>{t(pending.action === "delete"
            ? "warehouse.management.delete" : pending.action === "activate"
              ? "warehouse.management.activate" : "warehouse.management.deactivate")}</button></footer></div>
    </section></div>}
  </section>;
}

export default WarehouseManagementScreen;

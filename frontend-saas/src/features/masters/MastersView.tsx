import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";
import { isCurrentSelection } from "../../lib/frontend-runtime.mjs";
import type { Credentials, ErpCustomer, ErpProduct, ErpSupplier, ErpWarehouse, LicenseSummary } from "../../lib/types";
import { Notice, MasterMode } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { uniqueCompanies, isMissingPhase3Endpoint, isRecoverableBackendDataError, errorMessage, formatMoney } from "../../shared/lib";
import { SectionHeader, RetryError, Segmented, Input, EmptyState, StatusPill } from "../../shared/ui";

import { useWorkspaceLabels } from "../../i18n/workspace";

export function MastersView({
  credentials,
  licenses,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const l = useWorkspaceLabels();
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [companyId, setCompanyId] = useState("");
  const [mode, setMode] = useState<MasterMode>("customers");
  const [customers, setCustomers] = useState<ErpCustomer[]>([]);
  const [products, setProducts] = useState<ErpProduct[]>([]);
  const [suppliers, setSuppliers] = useState<ErpSupplier[]>([]);
  const [warehouses, setWarehouses] = useState<ErpWarehouse[]>([]);
  const [partyForm, setPartyForm] = useState({ code: "", name: "", taxId: "", documentType: "NIF", email: "", phone: "" });
  const [productForm, setProductForm] = useState({ sku: "", name: "", category: "", price: "0.00", taxRate: "21.00", minStock: "0.00" });
  const [warehouseForm, setWarehouseForm] = useState({ code: "", name: "", address: "" });
  const [busy, setBusy] = useState(false);
  const [mastersLoadError, setMastersLoadError] = useState<string | null>(null);
  const mastersRequestId = useRef(0);
  const selectedMastersCompanyRef = useRef(companyId);
  selectedMastersCompanyRef.current = companyId;
  const canManage = permissions.has("MANAGE_ERP_MASTERS");

  useEffect(() => {
    if (!companies.some(c => c.companyId === companyId)) {
      setCompanyId(companies[0]?.companyId ?? "");
    }
  }, [companies, companyId]);

  useEffect(() => {
    mastersRequestId.current += 1;
    setCustomers([]); setProducts([]); setSuppliers([]); setWarehouses([]);
    if (companyId) void loadMasters(companyId);
  }, [companyId, credentials.accessToken, refreshVersion]);

  async function loadMasters(nextCompanyId: string) {
    setMastersLoadError(null);
    const requestId = ++mastersRequestId.current;
    const [nextCustomers, nextProducts, nextSuppliers, nextWarehouses] = await Promise.all([
      loadMasterList(() => api.erpCustomers(credentials, nextCompanyId), requestId, nextCompanyId),
      loadMasterList(() => api.erpProducts(credentials, nextCompanyId), requestId, nextCompanyId),
      loadMasterList(() => api.erpSuppliers(credentials, nextCompanyId), requestId, nextCompanyId),
      loadMasterList(() => api.erpWarehouses(credentials, nextCompanyId), requestId, nextCompanyId)
    ]);
    if (requestId !== mastersRequestId.current || !isCurrentSelection(nextCompanyId, selectedMastersCompanyRef.current)) return;
    setCustomers(nextCustomers);
    setProducts(nextProducts);
    setSuppliers(nextSuppliers);
    setWarehouses(nextWarehouses);
    onNotice(null);
  }

  async function loadMasterList<T>(loader: () => Promise<T[]>, requestId: number, requestedCompanyId: string): Promise<T[]> {
    try {
      return await loader();
    } catch (error) {
      if (requestId !== mastersRequestId.current || !isCurrentSelection(requestedCompanyId, selectedMastersCompanyRef.current)) return [];
      const message = isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error) ? t("mastersBackendPending") : errorMessage(error);
      setMastersLoadError(message);
      onNotice({ type: "error", text: message });
      return [];
    }
  }

  async function createMaster(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    setBusy(true);
    try {
      if (mode === "customers") {
        await api.createErpCustomer(credentials, companyId, partyForm);
        setPartyForm({ code: "", name: "", taxId: "", documentType: "NIF", email: "", phone: "" });
      } else if (mode === "products") {
        await api.createErpProduct(credentials, companyId, productForm);
        setProductForm({ sku: "", name: "", category: "", price: "0.00", taxRate: "21.00", minStock: "0.00" });
      } else if (mode === "suppliers") {
        await api.createErpSupplier(credentials, companyId, partyForm);
        setPartyForm({ code: "", name: "", taxId: "", documentType: "NIF", email: "", phone: "" });
      } else {
        await api.createErpWarehouse(credentials, companyId, warehouseForm);
        setWarehouseForm({ code: "", name: "", address: "" });
      }
      onNotice({ type: "success", text: t("masterCreated") });
      await loadMasters(companyId);
    } catch (error) {
      if (isMissingPhase3Endpoint(error)) {
        onNotice({ type: "error", text: t("mastersBackendPending") });
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function deactivateMaster(id: string) {
    if (!companyId || !window.confirm(t("confirmDestructive"))) return;
    setBusy(true);
    try {
      if (mode === "customers") {
        await api.deactivateErpCustomer(credentials, companyId, id);
      } else if (mode === "products") {
        await api.deactivateErpProduct(credentials, companyId, id);
      } else if (mode === "suppliers") {
        await api.deactivateErpSupplier(credentials, companyId, id);
      } else {
        await api.deactivateErpWarehouse(credentials, companyId, id);
      }
      await loadMasters(companyId);
      onNotice({ type: "success", text: t("masterDisabled") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader title={t("erpMasters")} subtitle={t("erpMastersSubtitle")} />
      {mastersLoadError && <RetryError message={mastersLoadError} onRetry={() => companyId && void loadMasters(companyId)} />}
      <div className="toolbar">
        <Segmented
          value={mode}
          options={[
            ["customers", t("customers")],
            ["products", t("products")],
            ["suppliers", t("suppliers")],
            ["warehouses", t("warehouses")]
          ]}
          onChange={(value) => setMode(value as MasterMode)}
        />
        <label className="toolbar-field">
          {t("company")}
          <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
            {companies.map((company) => (
              <option key={company.companyId} value={company.companyId}>
                {company.companyName}
              </option>
            ))}
          </select>
        </label>
      </div>
      {canManage && (
        <form className="compact-form-grid masters-form" onSubmit={createMaster}>
          {mode === "products" ? (
            <>
              <Input label={t("sku")} value={productForm.sku} onChange={(sku) => setProductForm({ ...productForm, sku })} required />
              <Input label={t("name")} value={productForm.name} onChange={(name) => setProductForm({ ...productForm, name })} required />
              <Input label={t("category")} value={productForm.category} onChange={(category) => setProductForm({ ...productForm, category })} />
              <Input label={t("price")} value={productForm.price} onChange={(price) => setProductForm({ ...productForm, price })} required />
              <Input label={t("taxRate")} value={productForm.taxRate} onChange={(taxRate) => setProductForm({ ...productForm, taxRate })} required />
              <Input label={t("minStock")} value={productForm.minStock} onChange={(minStock) => setProductForm({ ...productForm, minStock })} required />
            </>
          ) : mode === "warehouses" ? (
            <>
              <Input label={t("code")} value={warehouseForm.code} onChange={(code) => setWarehouseForm({ ...warehouseForm, code })} required />
              <Input label={t("name")} value={warehouseForm.name} onChange={(name) => setWarehouseForm({ ...warehouseForm, name })} required />
              <Input label={t("address")} value={warehouseForm.address} onChange={(address) => setWarehouseForm({ ...warehouseForm, address })} />
            </>
          ) : (
            <>
              <Input label={t("code")} value={partyForm.code} onChange={(code) => setPartyForm({ ...partyForm, code })} required />
              <Input label={t("name")} value={partyForm.name} onChange={(name) => setPartyForm({ ...partyForm, name })} required />
              {mode === "customers" && <label>{l("documentType")}<select value={partyForm.documentType} onChange={e => setPartyForm({ ...partyForm, documentType: e.target.value })}>{["NIF", "DNI", "NIE", "PASAPORTE"].map(type => <option key={type}>{type}</option>)}</select></label>}
              <Input required={mode === "customers"} label={t("taxId")} value={partyForm.taxId} onChange={(taxId) => setPartyForm({ ...partyForm, taxId })} />
              <Input label={t("email")} value={partyForm.email} onChange={(email) => setPartyForm({ ...partyForm, email })} />
              <Input label={t("phone")} value={partyForm.phone} onChange={(phone) => setPartyForm({ ...partyForm, phone })} />
            </>
          )}
          <div className="form-actions">
            <button className="primary-button" type="submit" disabled={busy || !companyId}>
              {mode === "customers" && t("createCustomer")}
              {mode === "products" && t("createProduct")}
              {mode === "suppliers" && t("createSupplier")}
              {mode === "warehouses" && t("createWarehouse")}
            </button>
          </div>
        </form>
      )}
      <MasterTable
        mode={mode}
        customers={customers}
        products={products}
        suppliers={suppliers}
        warehouses={warehouses}
        canManage={canManage}
        onDeactivate={(id) => void deactivateMaster(id)}
      />
    </section>
  );
}

export function MasterTable({
  mode,
  customers,
  products,
  suppliers,
  warehouses,
  canManage = false,
  onDeactivate = () => undefined
}: {
  mode: MasterMode;
  customers: ErpCustomer[];
  products: ErpProduct[];
  suppliers: ErpSupplier[];
  warehouses: ErpWarehouse[];
  canManage?: boolean;
  onDeactivate?: (id: string) => void;
}) {
  const { t } = useI18n();
  if (mode === "products") {
    if (products.length === 0) return <EmptyState text={t("noMasterData")} />;
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t("sku")}</th>
              <th>{t("name")}</th>
              <th>{t("category")}</th>
              <th>{t("price")}</th>
              <th>{t("taxRate")}</th>
              <th>{t("minStock")}</th>
              {canManage && <th></th>}
            </tr>
          </thead>
          <tbody>
            {products.map((item) => (
              <tr key={item.id}>
                <td>{item.sku}</td>
                <td>{item.name}</td>
                <td>{item.category || "-"}</td>
                <td>{formatMoney(item.price)}</td>
                <td>{formatMoney(item.taxRate)}%</td>
                <td>{formatMoney(item.minStock)}</td>
                {canManage && (
                  <td className="table-actions">
                    {item.active && <button className="danger-button subtle" type="button" onClick={() => onDeactivate(item.id)}>{t("deactivate")}</button>}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  if (mode === "warehouses") {
    if (warehouses.length === 0) return <EmptyState text={t("noMasterData")} />;
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t("code")}</th>
              <th>{t("name")}</th>
              <th>{t("address")}</th>
              <th>{t("status")}</th>
              {canManage && <th></th>}
            </tr>
          </thead>
          <tbody>
            {warehouses.map((item) => (
              <tr key={item.id}>
                <td>{item.code}</td>
                <td>{item.name}</td>
                <td>{item.address || "-"}</td>
                <td><StatusPill status={item.active ? t("active") : t("inactive")} tone={item.active ? "ok" : "muted"} /></td>
                {canManage && (
                  <td className="table-actions">
                    {item.active && <button className="danger-button subtle" type="button" onClick={() => onDeactivate(item.id)}>{t("deactivate")}</button>}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  const rows = mode === "customers" ? customers : suppliers;
  if (rows.length === 0) return <EmptyState text={t("noMasterData")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("code")}</th>
            <th>{t("name")}</th>
            <th>{t("taxId")}</th>
            <th>{t("email")}</th>
            <th>{t("phone")}</th>
            <th>{t("status")}</th>
            {canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((item) => (
            <tr key={item.id}>
              <td>{item.code}</td>
              <td>{item.name}</td>
              <td>{item.taxId || "-"}</td>
              <td>{item.email || "-"}</td>
              <td>{item.phone || "-"}</td>
              <td><StatusPill status={item.active ? t("active") : t("inactive")} tone={item.active ? "ok" : "muted"} /></td>
              {canManage && (
                <td className="table-actions">
                  {item.active && <button className="danger-button subtle" type="button" onClick={() => onDeactivate(item.id)}>{t("deactivate")}</button>}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";
import { isCurrentSelection } from "../../lib/frontend-runtime.mjs";
import type { Credentials, InventoryMovement, InventoryStock, LicenseSummary, SalesDocument } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { uniqueCompanies, toLocalInput, normalizeSearch, errorMessage, isRecoverableBackendDataError, isPositiveAmount, uniqueStrings, formatCurrency, formatDate, formatQuantity } from "../../shared/lib";
import { SectionHeader, RetryError, Input, Select, EmptyState, StatusPill } from "../../shared/ui";

export function OperationsView({
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
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [companyId, setCompanyId] = useState("");
  const [sales, setSales] = useState<SalesDocument[]>([]);
  const [movements, setMovements] = useState<InventoryMovement[]>([]);
  const [stock, setStock] = useState<InventoryStock[]>([]);
  const [salesStatusFilter, setSalesStatusFilter] = useState("");
  const [inventoryFilter, setInventoryFilter] = useState("");
  const [saleForm, setSaleForm] = useState({
    storeId: "",
    documentNumber: "",
    customerCode: "",
    total: "0.00",
    currency: "EUR",
    status: "CONFIRMADA",
    issuedAt: toLocalInput(new Date())
  });
  const [movementForm, setMovementForm] = useState({
    warehouseCode: "",
    productSku: "",
    movementType: "ENTRADA",
    quantity: "1.00",
    reason: "",
    movedAt: toLocalInput(new Date())
  });
  const [busy, setBusy] = useState(false);
  const [operationsLoadError, setOperationsLoadError] = useState<string | null>(null);
  const operationsRequestId = useRef(0);
  const selectedOperationsCompanyRef = useRef(companyId);
  selectedOperationsCompanyRef.current = companyId;
  const canManage = permissions.has("MANAGE_OPERATIONS");
  const filteredSales = sales.filter((item) => !salesStatusFilter || item.status === salesStatusFilter);
  const filteredMovements = movements.filter((item) =>
    [item.warehouseCode, item.productSku, item.movementType, item.reason ?? ""].some((value) => normalizeSearch(value).includes(normalizeSearch(inventoryFilter)))
  );
  const filteredStock = stock.filter((item) =>
    [item.warehouseCode, item.productSku].some((value) => normalizeSearch(value).includes(normalizeSearch(inventoryFilter)))
  );

  useEffect(() => {
    if (!companies.some(c => c.companyId === companyId)) setCompanyId(companies[0]?.companyId ?? "");
  }, [companies, companyId]);

  useEffect(() => {
    operationsRequestId.current += 1;
    setSales([]); setMovements([]); setStock([]);
    if (companyId) void loadOperations(companyId);
  }, [companyId, credentials.accessToken, refreshVersion]);

  async function loadOperations(nextCompanyId: string) {
    const requestId = ++operationsRequestId.current;
    try {
      const [nextSales, nextMovements, nextStock] = await Promise.all([
        api.salesDocuments(credentials, nextCompanyId),
        api.inventoryMovements(credentials, nextCompanyId),
        api.inventoryStock(credentials, nextCompanyId)
      ]);
      if (requestId !== operationsRequestId.current || !isCurrentSelection(nextCompanyId, selectedOperationsCompanyRef.current)) return;
      setSales(nextSales);
      setMovements(nextMovements);
      setStock(nextStock);
      setOperationsLoadError(null);
      onNotice(null);
    } catch (error) {
      if (requestId !== operationsRequestId.current || !isCurrentSelection(nextCompanyId, selectedOperationsCompanyRef.current)) return;
      setOperationsLoadError(errorMessage(error));
      if (isRecoverableBackendDataError(error)) {
        setSales([]);
        setMovements([]);
        setStock([]);
        onNotice({ type: "error", text: t("phase11Pending") });
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createSale(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(saleForm.total)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    if (sales.some((item) => item.documentNumber.toLowerCase() === saleForm.documentNumber.trim().toLowerCase())) {
      onNotice({ type: "error", text: t("duplicateCode") });
      return;
    }
    setBusy(true);
    try {
      await api.createSalesDocument(credentials, companyId, {
        ...saleForm,
        storeId: saleForm.storeId || null,
        issuedAt: new Date(saleForm.issuedAt).toISOString()
      });
      setSaleForm({ storeId: "", documentNumber: "", customerCode: "", total: "0.00", currency: "EUR", status: "CONFIRMADA", issuedAt: toLocalInput(new Date()) });
      await loadOperations(companyId);
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function createMovement(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (!isPositiveAmount(movementForm.quantity)) {
      onNotice({ type: "error", text: t("invalidAmount") });
      return;
    }
    setBusy(true);
    try {
      await api.createInventoryMovement(credentials, companyId, {
        ...movementForm,
        movedAt: new Date(movementForm.movedAt).toISOString()
      });
      setMovementForm({ warehouseCode: "", productSku: "", movementType: "ENTRADA", quantity: "1.00", reason: "", movedAt: toLocalInput(new Date()) });
      await loadOperations(companyId);
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="view-grid">
      <section className="content-section">
        <SectionHeader title={t("realOperations")} subtitle={t("realOperationsSubtitle")} />
        {operationsLoadError && <RetryError message={operationsLoadError} onRetry={() => companyId && void loadOperations(companyId)} />}
        <div className="toolbar">
          <label className="toolbar-field">
            {t("company")}
            <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
              {companies.map((company) => (
                <option key={company.companyId} value={company.companyId}>{company.companyName}</option>
              ))}
            </select>
          </label>
          <label className="toolbar-field">
            {t("status")}
            <select className="control-input" value={salesStatusFilter} onChange={(event) => setSalesStatusFilter(event.target.value)}>
              <option value="">{t("allStatuses")}</option>
              {uniqueStrings(sales.map((item) => item.status)).map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </label>
        </div>
        {canManage && (
          <form className="compact-form-grid" onSubmit={createSale}>
            <Input label={t("store")} value={saleForm.storeId} onChange={(storeId) => setSaleForm({ ...saleForm, storeId })} />
            <Select label={t("status")} value={saleForm.status} options={["CONFIRMADA", "BORRADOR", "ANULADA"]} onChange={(status) => setSaleForm({ ...saleForm, status })} />
            <Input label={t("documentNumber")} value={saleForm.documentNumber} onChange={(documentNumber) => setSaleForm({ ...saleForm, documentNumber })} required />
            <Input label={t("customerCode")} value={saleForm.customerCode} onChange={(customerCode) => setSaleForm({ ...saleForm, customerCode })} />
            <Input label={t("amount")} value={saleForm.total} onChange={(total) => setSaleForm({ ...saleForm, total })} required />
            <Input label={t("currency")} value={saleForm.currency} onChange={(currency) => setSaleForm({ ...saleForm, currency })} required />
            <Input label={t("issuedAt")} type="datetime-local" value={saleForm.issuedAt} onChange={(issuedAt) => setSaleForm({ ...saleForm, issuedAt })} required />
            <button className="primary-button" type="submit" disabled={busy}>{t("issueSale")}</button>
          </form>
        )}
        <SimpleSalesTable sales={filteredSales} />
      </section>

      <section className="content-section">
        <SectionHeader title={t("inventoryMovements")} subtitle={t("stockCurrent")} />
        <div className="toolbar">
          <Input label={t("globalSearch")} value={inventoryFilter} onChange={setInventoryFilter} />
        </div>
        {canManage && (
          <form className="compact-form-grid" onSubmit={createMovement}>
            <Input label={t("warehouse")} value={movementForm.warehouseCode} onChange={(warehouseCode) => setMovementForm({ ...movementForm, warehouseCode })} required />
            <Input label={t("sku")} value={movementForm.productSku} onChange={(productSku) => setMovementForm({ ...movementForm, productSku })} required />
            <Select
              label={t("movementType")}
              value={movementForm.movementType}
              options={["ENTRADA", "SALIDA", "AJUSTE"]}
              onChange={(movementType) => setMovementForm({ ...movementForm, movementType })}
            />
            <Input label={t("quantity")} value={movementForm.quantity} onChange={(quantity) => setMovementForm({ ...movementForm, quantity })} required />
            <Input label={t("reason")} value={movementForm.reason} onChange={(reason) => setMovementForm({ ...movementForm, reason })} />
            <Input label={t("created")} type="datetime-local" value={movementForm.movedAt} onChange={(movedAt) => setMovementForm({ ...movementForm, movedAt })} required />
            <button className="primary-button" type="submit" disabled={busy}>{t("createMovement")}</button>
          </form>
        )}
        <SimpleStockTable stock={filteredStock} movements={filteredMovements} />
      </section>
    </div>
  );
}

export function SimpleSalesTable({ sales }: { sales: SalesDocument[] }) {
  const { t } = useI18n();
  if (sales.length === 0) return <EmptyState text={t("noBillingData")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("documentNumber")}</th>
            <th>{t("customerCode")}</th>
            <th>{t("amount")}</th>
            <th>{t("status")}</th>
            <th>{t("issuedAt")}</th>
          </tr>
        </thead>
        <tbody>
          {sales.map((item) => (
            <tr key={item.id}>
              <td><strong>{item.documentNumber}</strong></td>
              <td>{item.customerCode || "-"}</td>
              <td>{formatCurrency(item.total, item.currency)}</td>
              <td><StatusPill status={item.status} tone={item.status === "ANULADA" ? "warning" : "ok"} /></td>
              <td>{formatDate(item.issuedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function SimpleStockTable({ stock, movements }: { stock: InventoryStock[]; movements: InventoryMovement[] }) {
  const { t } = useI18n();
  return (
    <div className="tenant-master-grid">
      <div>
        <h3>{t("stockCurrent")}</h3>
        {stock.length === 0 ? (
          <EmptyState text={t("noStockForFilter")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("warehouse")}</th>
                  <th>{t("sku")}</th>
                  <th>{t("quantity")}</th>
                </tr>
              </thead>
              <tbody>
                {stock.map((item) => (
                  <tr key={`${item.warehouseCode}-${item.productSku}`}>
                    <td>{item.warehouseCode}</td>
                    <td>{item.productSku}</td>
                    <td>{formatQuantity(item.quantity)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div>
        <h3>{t("inventoryMovements")}</h3>
        {movements.length === 0 ? (
          <EmptyState text={t("noEventsForFilter")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("warehouse")}</th>
                  <th>{t("sku")}</th>
                  <th>{t("movementType")}</th>
                  <th>{t("quantity")}</th>
                  <th>{t("created")}</th>
                </tr>
              </thead>
              <tbody>
                {movements.map((item) => (
                  <tr key={item.id}>
                    <td>{item.warehouseCode}</td>
                    <td>{item.productSku}</td>
                    <td>{item.movementType}</td>
                    <td>{formatQuantity(item.quantity)}</td>
                    <td>{formatDate(item.movedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

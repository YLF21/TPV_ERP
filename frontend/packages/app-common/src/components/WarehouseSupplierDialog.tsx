import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import {
  buildPartyRequest,
  emptyPartyForm,
  partyFormFromView,
  validatePartyForm,
  type PartyForm,
  type SupplierView
} from "./PartyDirectoryPanel";
import { PartyFormFields } from "./PartyFormFields";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type Props = {
  open: boolean;
  locale: LocaleCode;
  session?: UserSession;
  suppliers: SupplierView[];
  selectedId?: string;
  onClose: () => void;
  onSelected: (supplier: SupplierView) => void;
  onChanged: (supplier: SupplierView) => void;
};

function canWriteSuppliers(session?: UserSession) {
  return Boolean(session?.permissions.some((permission) => [
    "ADMIN", "SUPPLIERS_WRITE", "GESTION_CLIENTE_PROVEEDOR", "GESTION_ALMACEN"
  ].includes(permission)));
}

export function WarehouseSupplierDialog({
  open,
  locale,
  session,
  suppliers,
  selectedId = "",
  onClose,
  onSelected,
  onChanged
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const [query, setQuery] = useState("");
  const [activeId, setActiveId] = useState(selectedId);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<PartyForm>({ ...emptyPartyForm });
  const [errors, setErrors] = useState<string[]>([]);
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const canWrite = canWriteSuppliers(session);

  const filteredSuppliers = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return suppliers
      .filter((supplier) => !normalized || [
        supplier.supplierId,
        supplier.legalName,
        supplier.tradeName,
        supplier.documentNumber,
        supplier.phone,
        supplier.email
      ].some((value) => value?.toLocaleLowerCase().includes(normalized)));
  }, [query, suppliers]);
  const selectedSupplier = suppliers.find((supplier) => supplier.id === activeId);
  const canSelectSupplier = Boolean(selectedSupplier?.active);

  useEffect(() => {
    if (!open) return;
    setActiveId(selectedId);
    setEditingId(null);
    setQuery("");
    setStatus("");
  }, [open, selectedId]);

  useEffect(() => {
    if (!open || !dialogRef.current) return;
    return activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document);
  }, [open, editingId]);

  function update<K extends keyof PartyForm>(field: K, value: PartyForm[K]) {
    setForm((current) => ({ ...current, [field]: value }));
    setErrors((current) => current.filter((candidate) => candidate !== field));
  }

  function closeForm() {
    setEditingId(null);
    setErrors([]);
    setStatus("");
    setForm({ ...emptyPartyForm });
  }

  function openNew() {
    if (!canWrite) return;
    setEditingId("");
    setForm({ ...emptyPartyForm });
    setErrors([]);
    setStatus("");
  }

  async function openEdit() {
    if (!canWrite || !activeId || !session?.accessToken) return;
    setEditingId(activeId);
    setLoading(true);
    setStatus("");
    try {
      const supplier = await apiRequest<SupplierView>("/suppliers/" + activeId, { token: session.accessToken });
      setForm(partyFormFromView(supplier, true));
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("party.loadError"));
      setEditingId(null);
    } finally {
      setLoading(false);
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    const nextErrors = validatePartyForm(form, true);
    if (nextErrors.length > 0) {
      setErrors(nextErrors);
      setStatus(t("party.form.invalid"));
      return;
    }
    if (!session?.accessToken) return;
    setSaving(true);
    setStatus("");
    try {
      const supplier = await apiRequest<SupplierView>(editingId
        ? "/suppliers/" + editingId
        : "/suppliers", {
        token: session.accessToken,
        method: editingId ? "PUT" : "POST",
        body: buildPartyRequest(form, true)
      });
      onChanged(supplier);
      onSelected(supplier);
      onClose();
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("party.saveError"));
    } finally {
      setSaving(false);
    }
  }

  function selectActive() {
    const supplier = suppliers.find((candidate) => candidate.id === activeId);
    if (!supplier?.active) return;
    onSelected(supplier);
    onClose();
  }

  function moveSelection(offset: -1 | 1) {
    if (filteredSuppliers.length === 0) return;
    const index = Math.max(0, filteredSuppliers.findIndex((supplier) => supplier.id === activeId));
    setActiveId(filteredSuppliers[Math.min(Math.max(index + offset, 0), filteredSuppliers.length - 1)].id);
  }

  if (!open) return null;

  return (
    <div className="filter-overlay warehouse-supplier-overlay" role="dialog" aria-modal="true" aria-labelledby="warehouse-supplier-title">
      <section
        ref={dialogRef}
        className="filter-dialog party-create-dialog warehouse-supplier-dialog"
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Escape") {
            event.preventDefault();
            if (editingId !== null) closeForm(); else onClose();
          }
        }}
      >
        <header className="filter-header">
          <div>
            <h2 id="warehouse-supplier-title">{editingId !== null ? (editingId ? t("warehouseDocument.supplierEdit") : t("party.suppliers.new")) : t("warehouseDocument.supplierListTitle")}</h2>
            <span>{editingId !== null ? t("party.form.subtitle") : t("warehouseDocument.supplierListHelp")}</span>
          </div>
          <button type="button" disabled={saving} onClick={editingId !== null ? closeForm : onClose}>{t("common.close")}</button>
        </header>
        {editingId === null ? (
          <div
            className="warehouse-supplier-picker"
            onKeyDown={(event) => {
              if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onClose(); return; }
              if (event.key === "F5") { event.preventDefault(); event.stopPropagation(); openNew(); return; }
              if (event.ctrlKey && event.key.toLocaleLowerCase() === "f7") { event.preventDefault(); event.stopPropagation(); void openEdit(); return; }
              if (event.key === "ArrowUp" || event.key === "ArrowDown") { event.preventDefault(); event.stopPropagation(); moveSelection(event.key === "ArrowUp" ? -1 : 1); return; }
              if (event.key === "Enter" || event.key === "Insert") { event.preventDefault(); event.stopPropagation(); selectActive(); }
            }}
          >
            <div className="warehouse-supplier-action-bar" aria-label={t("warehouseDocument.supplierListTitle")}>
              {canWrite && <button type="button" onClick={openNew}><kbd>F5</kbd> {t("party.suppliers.new")}</button>}
              {canWrite && <button type="button" disabled={!activeId} onClick={() => void openEdit()}><kbd>Ctrl+F7</kbd> {t("warehouseDocument.supplierEdit")}</button>}
            </div>
            <div className="warehouse-supplier-toolbar">
              <label className="warehouse-supplier-search">
                <span>{t("warehouseDocument.supplierSearch")}</span>
                <input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} />
              </label>
            </div>
            <div className="warehouse-supplier-table" role="table" aria-label={t("warehouseDocument.supplierListTitle")}>
              <div className="warehouse-supplier-table-header" role="row">
                <span role="columnheader">{t("warehouseDocument.supplierColumn.code")}</span>
                <span role="columnheader">{t("warehouseDocument.supplierColumn.legalName")}</span>
                <span role="columnheader">{t("warehouseDocument.supplierColumn.tradeName")}</span>
                <span role="columnheader">{t("warehouseDocument.supplierColumn.document")}</span>
                <span role="columnheader">{t("warehouseDocument.supplierColumn.phone")}</span>
                <span role="columnheader">{t("warehouseDocument.supplierColumn.email")}</span>
                <span role="columnheader">{t("warehouseDocument.supplierColumn.status")}</span>
              </div>
              <div className="warehouse-supplier-table-body">
                {filteredSuppliers.map((supplier) => (
                  <button
                    type="button"
                    role="row"
                    aria-selected={supplier.id === activeId}
                    aria-disabled={!supplier.active}
                    className={`warehouse-supplier-table-row${supplier.id === activeId ? " selected" : ""}${supplier.active ? "" : " inactive"}`}
                    key={supplier.id}
                    onClick={() => setActiveId(supplier.id)}
                    onDoubleClick={selectActive}
                  >
                    <strong>{supplier.supplierId || "-"}</strong>
                    <span>{supplier.legalName}</span>
                    <span>{supplier.tradeName || "-"}</span>
                    <span>{[supplier.documentType, supplier.documentNumber].filter(Boolean).join(" · ") || "-"}</span>
                    <span>{supplier.phone || "-"}</span>
                    <span>{supplier.email || "-"}</span>
                    <span>
                      <em className={`warehouse-supplier-status ${supplier.active ? "active" : "inactive"}`}>
                        {t(supplier.active ? "warehouseDocument.supplierStatus.active" : "warehouseDocument.supplierStatus.inactive")}
                      </em>
                    </span>
                  </button>
                ))}
                {filteredSuppliers.length === 0 && <p className="warehouse-supplier-empty">{t("warehouseDocument.supplierListEmpty")}</p>}
              </div>
            </div>
            {status && <p className="product-create-status" role="alert">{status}</p>}
            <footer className="warehouse-supplier-selection-footer">
              <p><kbd>Insert</kbd> {t("warehouseDocument.supplierSelectHint")}</p>
              <div className="filter-actions">
                <button type="button" disabled={!canSelectSupplier} className="primary" onClick={selectActive}>{t("common.select")}</button>
                <button type="button" onClick={onClose}>{t("common.cancel")}</button>
              </div>
            </footer>
          </div>
        ) : (
          <form className="product-create-form party-create-form" onSubmit={save}>
            <fieldset disabled={saving || loading}>
              <PartyFormFields form={form} errors={errors} channels={[]} supplier autoFocusName t={t} onChange={update} />
            </fieldset>
            {status && <p className="product-create-status" role="alert">{status}</p>}
            <footer className="filter-actions">
              <button type="button" disabled={saving} onClick={closeForm}>{t("common.cancel")}</button>
              <button type="submit" disabled={saving || loading}>{saving ? t("party.saving") : t("common.save")}</button>
            </footer>
          </form>
        )}
      </section>
    </div>
  );
}

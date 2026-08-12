import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { SalePrintMode } from "../sale/ticketPrinting";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

export type ParkedSaleSummary = {
  id: string;
  createdAt: string;
  customerId?: string | null;
  comment?: string | null;
  total: number | string;
};

export type OpenedParkedSale = {
  document: {
    clienteId?: string | null;
    comentarioInterno?: string | null;
    lineas: Array<{
      productoId: string;
      cantidad: number | string;
      descuento: number | string;
      precioUnitario?: number | string | null;
      nombre?: string | null;
      temporaryNameOverride?: boolean | null;
      temporaryPriceOverride?: boolean | null;
      serialNumbers?: string[];
      returnSourceType?: "TICKET" | "GIFT_RECEIPT" | "SALES_INVOICE" | null;
      returnSourceCode?: string | null;
      returnSourceTicketId?: string | null;
      originalDocumentLineId?: string | null;
      giftReceiptLineId?: string | null;
    }>;
  };
  comment?: string | null;
  printMode?: SalePrintMode | null;
};

type ParkedSaleRecovery = {
  recoveryId: string;
  parkedSaleId: string;
  status: "CLAIMED" | "ACKNOWLEDGED";
  sale: OpenedParkedSale;
};

type Props = {
  token?: string;
  locale: LocaleCode;
  currentUsername?: string;
  canManageSales: boolean;
  onClose: () => void;
  onRecovered: (sale: OpenedParkedSale) => void | Promise<void>;
};

export function ParkedSalesDialog({
  token,
  locale,
  currentUsername = "",
  canManageSales,
  onClose,
  onRecovered,
}: Props) {
  const t = createTranslator(locale);
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const singleCancelRef = useRef<HTMLButtonElement>(null);
  const singleConfirmRef = useRef<HTMLButtonElement>(null);
  const singleDialogRef = useRef<HTMLElement>(null);
  const deleteAllDialogRef = useRef<HTMLElement>(null);
  const closeRef = useRef(onClose);
  const busyRef = useRef("");
  const [sales, setSales] = useState<ParkedSaleSummary[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState("");
  const [pendingDeleteId, setPendingDeleteId] = useState("");
  const [deleteAllOpen, setDeleteAllOpen] = useState(false);
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [error, setError] = useState("");
  const selectedSale = sales.find((sale) => sale.id === selectedId) ?? null;
  const pendingDeleteSale = sales.find((sale) => sale.id === pendingDeleteId) ?? null;
  const bulkAuthorization = useMemo<SaleOperationAuthorization>(() => canManageSales
    ? { mode: "CURRENT_PASSWORD", requireUsername: false, requirePassword: true }
    : { mode: "DELEGATED", requireUsername: true, requirePassword: true }, [canManageSales]);

  useEffect(() => { closeRef.current = onClose; }, [onClose]);
  useEffect(() => { busyRef.current = busyId; }, [busyId]);
  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);
  useEffect(() => pendingDeleteId && singleDialogRef.current
    ? activateModalFocusTrap(
      singleDialogRef.current as unknown as ModalFocusRoot,
      document,
      { restoreFocus: false },
    ) : undefined, [pendingDeleteId]);
  useEffect(() => deleteAllOpen && deleteAllDialogRef.current
    ? activateModalFocusTrap(
      deleteAllDialogRef.current as unknown as ModalFocusRoot,
      document,
      { restoreFocus: false },
    ) : undefined, [deleteAllOpen]);

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement
      ? document.activeElement : null;
    function handleKey(event: KeyboardEvent) {
      if (event.key === "Escape" && !busyRef.current) {
        event.preventDefault();
        if (pendingDeleteId) setPendingDeleteId("");
        else if (deleteAllOpen) closeDeleteAll();
        else closeRef.current();
      }
    }
    document.addEventListener("keydown", handleKey);
    return () => {
      document.removeEventListener("keydown", handleKey);
      previousFocus?.focus();
    };
  }, [deleteAllOpen, pendingDeleteId]);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const loaded = await apiRequest<ParkedSaleSummary[]>("/parked-sales", { token });
      setSales(loaded);
      setSelectedId((current) => loaded.some((sale) => sale.id === current)
        ? current : loaded[0]?.id ?? "");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.load"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [token]);
  useEffect(() => {
    if (!loading && sales.length > 0 && !pendingDeleteId && !deleteAllOpen) {
      queueMicrotask(() => listRef.current?.focus());
    }
  }, [loading, sales.length, pendingDeleteId, deleteAllOpen]);

  async function recover(id: string) {
    if (busyId) return;
    setBusyId(id);
    setError("");
    try {
      const storageKey = `tpverp:parked-sale-recovery:${id}`;
      const recoveryId = localStorage.getItem(storageKey)
        || globalThis.crypto?.randomUUID?.()
        || `${Date.now()}-${Math.random().toString(16).slice(2)}-4000-8000-${Math.random().toString(16).slice(2)}`;
      localStorage.setItem(storageKey, recoveryId);
      const recovery = await apiRequest<ParkedSaleRecovery>(
        `/parked-sales/${encodeURIComponent(id)}/recoveries`,
        { token, method: "POST", body: { recoveryId } },
      );
      if (recovery.status === "CLAIMED") {
        await onRecovered(recovery.sale);
        await apiRequest(
          `/parked-sales/${encodeURIComponent(id)}/recoveries/${encodeURIComponent(recoveryId)}/acknowledge`,
          { token, method: "POST" },
        );
      }
      localStorage.removeItem(storageKey);
      onClose();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.recover"));
    } finally {
      setBusyId("");
    }
  }

  async function removeOne() {
    if (!pendingDeleteSale || busyId) return;
    const id = pendingDeleteSale.id;
    setBusyId(id);
    setError("");
    try {
      await apiRequest(`/parked-sales/${encodeURIComponent(id)}/deletions`, {
        token,
        method: "POST",
      });
      const remaining = sales.filter((sale) => sale.id !== id);
      const deletedIndex = sales.findIndex((sale) => sale.id === id);
      setSales(remaining);
      setSelectedId(remaining[Math.min(deletedIndex, remaining.length - 1)]?.id ?? "");
      setPendingDeleteId("");
      queueMicrotask(() => listRef.current?.focus());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.delete"));
    } finally {
      setBusyId("");
    }
  }

  async function removeAll() {
    if (busyId || !saleOperationAuthorizationComplete(
      bulkAuthorization,
      authorizerUsername,
      authorizerPassword,
    )) return;
    setBusyId("all");
    setError("");
    try {
      await apiRequest("/parked-sales/deletions", {
        token,
        method: "POST",
        body: saleOperationCredentials(
          bulkAuthorization,
          authorizerUsername,
          authorizerPassword,
        ),
      });
      setSales([]);
      setSelectedId("");
      closeDeleteAll();
      queueMicrotask(() => listRef.current?.focus());
    } catch (reason) {
      setAuthorizerPassword("");
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.deleteAll"));
    } finally {
      setBusyId("");
    }
  }

  function closeDeleteAll() {
    setDeleteAllOpen(false);
    setAuthorizerUsername("");
    setAuthorizerPassword("");
  }

  function handleListKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (busyId || pendingDeleteId || deleteAllOpen || sales.length === 0) return;
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      const current = Math.max(0, sales.findIndex((sale) => sale.id === selectedId));
      const next = Math.min(sales.length - 1, Math.max(
        0,
        current + (event.key === "ArrowDown" ? 1 : -1),
      ));
      setSelectedId(sales[next].id);
      return;
    }
    if (event.key === "Enter" && selectedSale
        && !(event.target instanceof HTMLButtonElement)) {
      event.preventDefault();
      void recover(selectedSale.id);
    }
  }

  function handleSingleDeleteKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.repeat || (event.key !== "ArrowRight" && event.key !== "ArrowLeft")) return;
    event.preventDefault();
    if (event.key === "ArrowRight") singleConfirmRef.current?.focus();
    else singleCancelRef.current?.focus();
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-business-dialog wide parked-sales-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        aria-busy={Boolean(busyId)}
        aria-hidden={pendingDeleteSale || deleteAllOpen ? true : undefined}
      >
        <header>
          <div>
            <h2 id={titleId}>{t("parkedSales.title")}</h2>
            <p id={descriptionId}>{t("parkedSales.listDescription")}</p>
          </div>
          <button type="button" aria-label={`${t("common.close")} ${t("parkedSales.title")}`} disabled={Boolean(busyId)} onClick={onClose}>×</button>
        </header>
        <p className="parked-sales-keyboard-hint">{t("parkedSales.keyboardHint")}</p>
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <div
          ref={listRef}
          className="parked-sales-list"
          role="listbox"
          aria-label={t("parkedSales.title")}
          tabIndex={sales.length > 0 ? 0 : -1}
          onKeyDown={handleListKeyDown}
        >
          {loading && <p>{t("parkedSales.loading")}</p>}
          {!loading && sales.length === 0 && (
            <div className="parked-sales-empty">
              <strong>{t("parkedSales.empty")}</strong>
              <span>{t("parkedSales.emptyHint")}</span>
              <button type="button" onClick={() => void load()}>{t("parkedSales.reload")}</button>
            </div>
          )}
          {sales.map((sale) => {
            const label = sale.comment?.trim() || t("parkedSales.untitled");
            return (
              <article
                key={sale.id}
                role="option"
                aria-selected={sale.id === selectedId}
                className={sale.id === selectedId ? "selected" : ""}
                onClick={() => setSelectedId(sale.id)}
                onDoubleClick={() => void recover(sale.id)}
              >
                <div>
                  <strong>{label}</strong>
                  <span>{new Date(sale.createdAt).toLocaleString(locale)}</span>
                </div>
                <b>{new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(Number(sale.total))}</b>
                <button
                  type="button"
                  aria-label={`${t("parkedSales.delete")} ${label}`}
                  className="danger parked-sales-delete-button"
                  disabled={Boolean(busyId)}
                  onClick={(event) => {
                    event.stopPropagation();
                    setSelectedId(sale.id);
                    setPendingDeleteId(sale.id);
                    setError("");
                  }}
                >{t("parkedSales.delete")}</button>
              </article>
            );
          })}
        </div>
        <footer className="parked-sales-footer sale-action-buttons sale-business-dialog-actions">
          <button type="button" className="danger parked-sales-delete-all-button" disabled={sales.length === 0 || Boolean(busyId)} onClick={() => { setDeleteAllOpen(true); setError(""); }}>
            {t("parkedSales.deleteAll")}
          </button>
          <button type="button" className="parked-sales-close-button" disabled={Boolean(busyId)} onClick={onClose}>{t("common.close")}</button>
        </footer>
      </section>

      {pendingDeleteSale && (
        <div className="sale-action-suboverlay" role="presentation">
          <section
            ref={singleDialogRef}
            className="sale-action-dialog sale-business-dialog sale-clear-sale-dialog parked-sale-confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={t("parkedSales.deleteTitle")}
            onKeyDown={handleSingleDeleteKeyDown}
          >
            <header><h2>{t("parkedSales.deleteTitle")}</h2></header>
            <div className="sale-clear-sale-warning" role="note">
              <span className="sale-clear-sale-warning-icon" aria-hidden="true">!</span>
              <div><strong>{t("sale.clearSale.warning")}</strong><p>{t("parkedSales.deleteConfirm")}</p></div>
            </div>
            <div className="sale-action-buttons sale-clear-sale-actions">
              <button ref={singleCancelRef} autoFocus type="button" disabled={Boolean(busyId)} onClick={() => setPendingDeleteId("")}>{t("common.cancel")}</button>
              <button ref={singleConfirmRef} type="button" className="danger" disabled={Boolean(busyId)} onClick={() => void removeOne()}>{t("parkedSales.delete")}</button>
            </div>
          </section>
        </div>
      )}

      {deleteAllOpen && (
        <div className="sale-action-suboverlay" role="presentation">
          <section ref={deleteAllDialogRef} className="sale-action-dialog sale-business-dialog parked-sales-delete-all-dialog" role="dialog" aria-modal="true" aria-label={t("parkedSales.deleteAllTitle")}>
            <header><h2>{t("parkedSales.deleteAllTitle")}</h2></header>
            <div className="sale-clear-sale-warning" role="note">
              <span className="sale-clear-sale-warning-icon" aria-hidden="true">!</span>
              <div><strong>{t("sale.clearSale.warning")}</strong><p>{t("parkedSales.deleteAllConfirm")}</p></div>
            </div>
            <SaleOperationAuthorizationFields
              locale={locale}
              currentUsername={currentUsername}
              authorization={bulkAuthorization}
              username={authorizerUsername}
              password={authorizerPassword}
              disabled={Boolean(busyId)}
              autoFocus
              onUsernameChange={setAuthorizerUsername}
              onPasswordChange={setAuthorizerPassword}
            />
            <div className="sale-action-buttons sale-business-dialog-actions">
              <button type="button" disabled={Boolean(busyId)} onClick={closeDeleteAll}>{t("common.cancel")}</button>
              <button type="button" className="danger" disabled={Boolean(busyId) || !saleOperationAuthorizationComplete(bulkAuthorization, authorizerUsername, authorizerPassword)} onClick={() => void removeAll()}>{t("parkedSales.deleteAll")}</button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

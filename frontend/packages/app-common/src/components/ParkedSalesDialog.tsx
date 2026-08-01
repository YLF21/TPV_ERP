import { useEffect, useId, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { SalePrintMode } from "../sale/ticketPrinting";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import {
  saleMutationCredentialsRequired,
  saleWithOperationAuthorizations,
  type SaleMutationAuthorizationRequirement,
  type SaleMutationOperationAuthorizations,
} from "../sale/saleMutationAuthorizations";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import { SaleMutationAuthorizationDialog } from "./SaleMutationAuthorizationDialog";

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
  currentSale: unknown;
  printMode?: SalePrintMode;
  canPark: boolean;
  saleMutationAuthorizations?: readonly SaleMutationAuthorizationRequirement[];
  deletionAuthorization?: SaleOperationAuthorization;
  onClose: () => void;
  onParked: () => void;
  onRecovered: (sale: OpenedParkedSale) => void | Promise<void>;
};

export function ParkedSalesDialog({
  token,
  locale,
  currentSale,
  printMode = "DEFAULT",
  canPark,
  saleMutationAuthorizations = [],
  deletionAuthorization = {
    mode: "DIRECT",
    requireUsername: false,
    requirePassword: false,
  },
  onClose,
  onParked,
  onRecovered,
}: Props) {
  const t = createTranslator(locale);
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLElement>(null);
  const closeRef = useRef(onClose);
  const busyRef = useRef("");
  const [sales, setSales] = useState<ParkedSaleSummary[]>([]);
  const [comment, setComment] = useState("");
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState("");
  const [pendingDeleteId, setPendingDeleteId] = useState("");
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [error, setError] = useState("");
  const [parkAuthorizationOpen, setParkAuthorizationOpen] = useState(false);
  const requiredParkCredentials = saleMutationCredentialsRequired(
    saleMutationAuthorizations,
  );

  useEffect(() => { closeRef.current = onClose; }, [onClose]);
  useEffect(() => { busyRef.current = busyId; }, [busyId]);

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement
      ? document.activeElement : null;
    const focusable = () => Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(
      "button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex='-1'])"
    ) ?? []).filter((element) => !element.hidden);
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busyRef.current) {
        event.preventDefault();
        closeRef.current();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0]; const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener("keydown", handleKey);
    queueMicrotask(() => {
      const preferred = dialogRef.current?.querySelector<HTMLElement>("input:not([disabled])");
      (preferred ?? focusable()[0])?.focus();
    });
    return () => {
      document.removeEventListener("keydown", handleKey);
      previousFocus?.focus();
    };
  }, []);

  async function load() {
    setLoading(true);
    setError("");
    try {
      setSales(await apiRequest<ParkedSaleSummary[]>("/parked-sales", { token }));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.load"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [token]);

  async function parkCurrent(
    operationAuthorizations: SaleMutationOperationAuthorizations = {},
  ) {
    if (!canPark || busyId) return;
    setBusyId("new");
    setError("");
    try {
      await apiRequest("/parked-sales/from-pos", {
        token,
        method: "POST",
        body: {
          sale: saleWithOperationAuthorizations(
            currentSale as object,
            operationAuthorizations,
          ),
          comment: comment.trim() || null,
          printMode,
        }
      });
      setParkAuthorizationOpen(false);
      setComment("");
      onParked();
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.park"));
    } finally {
      setBusyId("");
    }
  }

  function requestParkCurrent() {
    if (!canPark || busyId) return;
    if (requiredParkCredentials.length > 0) {
      setError("");
      setParkAuthorizationOpen(true);
      return;
    }
    void parkCurrent();
  }

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
      const recovery = await apiRequest<ParkedSaleRecovery>(`/parked-sales/${encodeURIComponent(id)}/recoveries`, {
        token,
        method: "POST",
        body: { recoveryId }
      });
      if (recovery.status === "CLAIMED") {
        await onRecovered(recovery.sale);
        await apiRequest(`/parked-sales/${encodeURIComponent(id)}/recoveries/${encodeURIComponent(recoveryId)}/acknowledge`, { token, method: "POST" });
      }
      localStorage.removeItem(storageKey);
      setSales((current) => current.filter((sale) => sale.id !== id));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.recover"));
    } finally {
      setBusyId("");
    }
  }

  async function remove(id: string) {
    if (busyId) return;
    if (!saleOperationAuthorizationComplete(
      deletionAuthorization,
      authorizerUsername,
      authorizerPassword,
    )) return;
    setBusyId(id);
    setError("");
    try {
      await apiRequest(`/parked-sales/${encodeURIComponent(id)}/deletions`, {
        token,
        method: "POST",
        body: saleOperationCredentials(
          deletionAuthorization,
          authorizerUsername,
          authorizerPassword,
        ),
      });
      setSales((current) => current.filter((sale) => sale.id !== id));
      setPendingDeleteId("");
      setAuthorizerUsername("");
      setAuthorizerPassword("");
    } catch (reason) {
      setAuthorizerPassword("");
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.delete"));
    } finally {
      setBusyId("");
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section ref={dialogRef} className="sale-action-dialog wide parked-sales-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId} aria-busy={Boolean(busyId)}>
        <header><div><h2 id={titleId}>{t("parkedSales.title")}</h2><p id={descriptionId}>{t("parkedSales.description")}</p></div><button type="button" aria-label={`${t("common.close")} ${t("parkedSales.title")}`} disabled={Boolean(busyId)} onClick={onClose}>×</button></header>
        <div className="parked-sale-create">
          <label><span>{t("parkedSales.comment")}</span><input value={comment} onChange={(event) => setComment(event.target.value)} placeholder={t("parkedSales.commentPlaceholder")} /></label>
          <button type="button" className="primary" disabled={!canPark || Boolean(busyId)} onClick={requestParkCurrent}>
            {t("parkedSales.parkCurrent")}
          </button>
        </div>
        {!canPark && <p className="parked-sales-empty-hint" role="status">{t("parkedSales.nothingToPark")}</p>}
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <div className="parked-sales-list">
          {loading && <p>{t("parkedSales.loading")}</p>}
          {!loading && sales.length === 0 && <div className="parked-sales-empty"><strong>{t("parkedSales.empty")}</strong><span>{t("parkedSales.emptyHint")}</span><button type="button" onClick={() => void load()}>{t("parkedSales.reload")}</button></div>}
          {sales.map((sale) => (
            <article key={sale.id}>
              <div><strong>{sale.comment?.trim() || t("parkedSales.untitled")}</strong><span>{new Date(sale.createdAt).toLocaleString(locale)}</span></div>
              <b>{new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(Number(sale.total))}</b>
              <button type="button" aria-label={`${t("parkedSales.recover")} ${sale.comment?.trim() || t("parkedSales.untitled")}`} disabled={Boolean(busyId)} onClick={() => void recover(sale.id)}>{t("parkedSales.recover")}</button>
              <button
                type="button"
                aria-label={`${t("parkedSales.delete")} ${sale.comment?.trim() || t("parkedSales.untitled")}`}
                className="danger"
                disabled={Boolean(busyId)}
                onClick={() => {
                  if (deletionAuthorization.mode === "DIRECT") {
                    void remove(sale.id);
                    return;
                  }
                  setPendingDeleteId(sale.id);
                  setAuthorizerUsername("");
                  setAuthorizerPassword("");
                  setError("");
                }}
              >
                {t("parkedSales.delete")}
              </button>
              {pendingDeleteId === sale.id && (
                <div className="parked-sale-delete-authorization">
                  <SaleOperationAuthorizationFields
                    locale={locale}
                    authorization={deletionAuthorization}
                    username={authorizerUsername}
                    password={authorizerPassword}
                    disabled={Boolean(busyId)}
                    autoFocus
                    onUsernameChange={setAuthorizerUsername}
                    onPasswordChange={setAuthorizerPassword}
                  />
                  <div className="sale-action-buttons">
                    <button
                      type="button"
                      disabled={Boolean(busyId)}
                      onClick={() => {
                        setPendingDeleteId("");
                        setAuthorizerUsername("");
                        setAuthorizerPassword("");
                      }}
                    >
                      {t("common.cancel")}
                    </button>
                    <button
                      type="button"
                      className="danger"
                      disabled={Boolean(busyId) || !saleOperationAuthorizationComplete(
                        deletionAuthorization,
                        authorizerUsername,
                        authorizerPassword,
                      )}
                      onClick={() => void remove(sale.id)}
                    >
                      {t("parkedSales.delete")}
                    </button>
                  </div>
                </div>
              )}
            </article>
          ))}
        </div>
        <footer><button type="button" disabled={Boolean(busyId)} onClick={onClose}>{t("common.close")}</button></footer>
      </section>
      <SaleMutationAuthorizationDialog
        open={parkAuthorizationOpen}
        locale={locale}
        requirements={requiredParkCredentials}
        busy={busyId === "new"}
        error={parkAuthorizationOpen ? error : ""}
        onCancel={() => {
          if (busyId) return;
          setParkAuthorizationOpen(false);
          setError("");
        }}
        onConfirm={(authorizations) => void parkCurrent(authorizations)}
      />
    </div>
  );
}

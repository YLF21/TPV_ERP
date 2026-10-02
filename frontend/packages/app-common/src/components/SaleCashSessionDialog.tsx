import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, apiRequest } from "../api/client";
import type { LocaleCode } from "../types";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import {
  closeCashSession,
  createCashCloseWithdrawalIdempotencyKey,
  openCashSession,
  recoverCashCloseOperation,
  type CashSessionView,
} from "../sale/cashSessions";
import { createCashCloseUiFlow, type CashCloseUiFlow, type CashCloseUiPhase } from "../sale/cashCloseUiFlow";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import type { CashDenominationCount } from "./CashDenominationDialog";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import "./CashSessionFlow.css";

const CashDenominationDialog = lazy(() => import("./CashDenominationDialog")
  .then(module => ({ default: module.CashDenominationDialog })));

type Props = {
  locale: LocaleCode;
  interfaceMode?: SaleInterfaceMode;
  currentUsername?: string;
  mode: "OPEN" | "CLOSE";
  openContext?: "SALES" | "HOME";
  terminalId: string;
  token: string;
  request?: typeof apiRequest;
  embedded?: boolean;
  denominations?: number[];
  withdrawalDenominations?: number[];
  requireClosingBreakdown?: boolean;
  requireWithdrawalBreakdown?: boolean;
  onBusyChange?: (busy: boolean) => void;
  authorization?: SaleOperationAuthorization;
  closeFlow?: CashCloseUiFlow;
  onCloseFlowChange?: (flow: CashCloseUiFlow) => void;
  onExitSales?: () => void;
  onOpened?: (session: CashSessionView) => void;
  onClosed?: (session: CashSessionView) => void;
  onCancel?: () => void;
};

export { createCashCloseUiFlow };
export type { CashCloseUiPhase, CashCloseUiFlow };

const copy = {
  es: {
    openTitle: "Abrir caja",
    openText: "Cuenta e introduce el efectivo que hay en caja antes de vender.",
    countedFund: "Efectivo contado / fondo inicial",
    count: "Contar monedas y billetes",
    withdrawing: "1. Retirada de efectivo",
    retaining: "2. Fondo que queda",
    closingUser: "3. Usuario que cierra",
    countedRequired: "Completa el recuento de monedas y billetes de ambos importes.",
    closeWindow: "Cerrar ventana",
    openAction: "Abrir caja",
    opening: "Abriendo…",
    exit: "Salir de Ventas",
    closeTitle: "Arqueo y cierre de caja",
    closeText: "Introduce el efectivo que quedará como fondo y la retirada final realizada.",
    retained: "Fondo que queda en caja",
    withdrawal: "Retirada final",
    comment: "Motivo o comentario",
    closeAction: "Cerrar caja",
    closing: "Comprobando arqueo…",
    retryAction: "Reintentar cierre",
    cancel: "Cancelar",
    invalidAmount: "Los importes deben ser números iguales o superiores a cero.",
    authorizationRequired: "Completa la autorización necesaria para cerrar la caja.",
    mismatch: "El arqueo presenta un descuadre. Revisa el efectivo y realiza el segundo intento.",
    attempted:
      "El cierre ya se ha iniciado. Debes reintentar o completarlo; no se puede cancelar ni modificar la retirada final.",
    reconciliationRequired:
      "La retirada final ya se ha procesado. Revisa solo el fondo que queda en caja y completa el segundo intento.",
    error: "No se pudo completar la operación de caja.",
  },
  en: {
    openTitle: "Open cash register",
    openText: "Count and enter the cash in the register before selling.",
    countedFund: "Counted cash / opening fund",
    count: "Count coins and notes",
    withdrawing: "1. Cash withdrawal",
    retaining: "2. Cash retained",
    closingUser: "3. Closing user",
    countedRequired: "Complete the coin and note count for both amounts.",
    closeWindow: "Close window",
    openAction: "Open register",
    opening: "Opening…",
    exit: "Exit Sales",
    closeTitle: "Cash count and close",
    closeText: "Enter the cash retained as opening fund and the final withdrawal performed.",
    retained: "Cash retained in register",
    withdrawal: "Final withdrawal",
    comment: "Reason or comment",
    closeAction: "Close register",
    closing: "Checking cash count…",
    retryAction: "Retry close",
    cancel: "Cancel",
    invalidAmount: "Amounts must be numbers greater than or equal to zero.",
    authorizationRequired: "Complete the authorization required to close the register.",
    mismatch: "The cash count does not match. Check the cash and submit the second attempt.",
    attempted:
      "The close has already started. You must retry or complete it; it cannot be cancelled and the final withdrawal cannot be changed.",
    reconciliationRequired:
      "The final withdrawal has already been processed. Review only the cash retained and complete the second attempt.",
    error: "The cash operation could not be completed.",
  },
  zh: {
    openTitle: "开启收银会话",
    openText: "销售前请清点并输入钱箱中的现金。",
    countedFund: "清点现金／初始备用金",
    count: "清点硬币和纸币",
    withdrawing: "1. 取出现金",
    retaining: "2. 保留现金",
    closingUser: "3. 关箱用户",
    countedRequired: "请完成两个金额的硬币和纸币清点。",
    closeWindow: "关闭窗口",
    openAction: "开启收银会话",
    opening: "正在开启…",
    exit: "退出销售",
    closeTitle: "盘点并关闭收银会话",
    closeText: "请输入保留为备用金的现金和最终取出的现金。",
    retained: "保留在钱箱中的现金",
    withdrawal: "最终取款",
    comment: "原因或备注",
    closeAction: "关闭收银会话",
    closing: "正在核对盘点…",
    retryAction: "重试关闭",
    cancel: "取消",
    invalidAmount: "金额必须是大于或等于零的数字。",
    authorizationRequired: "请完成关闭钱箱所需的授权。",
    mismatch: "盘点存在差额。请检查现金并进行第二次盘点。",
    attempted: "关闭流程已开始。必须重试或完成，不能取消或修改最终取款。",
    reconciliationRequired: "最终取款已处理。请仅检查保留现金并完成第二次盘点。",
    error: "无法完成收银操作。",
  },
} as const;

function operationError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const detail = error.problem?.detail;
    const title = error.problem?.title;
    return (typeof detail === "string" && detail)
      || (typeof title === "string" && title)
      || error.message
      || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

function amount(value: string) {
  const text = value.trim();
  if (!/^(?:\d+|\d*[.,]\d{1,2})$/.test(text)) return null;
  const parsed = Number(text.replace(",", "."));
  return Number.isSafeInteger(Math.round(parsed * 100)) && parsed >= 0 ? parsed : null;
}

export function SaleCashSessionDialog({
  locale,
  interfaceMode = "KEYBOARD",
  currentUsername = "",
  mode,
  openContext = "SALES",
  terminalId,
  token,
  request = apiRequest,
  embedded = false,
  denominations,
  withdrawalDenominations: configuredWithdrawalDenominations,
  requireClosingBreakdown = false,
  requireWithdrawalBreakdown = false,
  onBusyChange,
  authorization = {
    mode: "DIRECT",
    requireUsername: false,
    requirePassword: false,
  },
  closeFlow,
  onCloseFlowChange,
  onExitSales,
  onOpened,
  onClosed,
  onCancel,
}: Props) {
  const t = copy[locale];
  const [initialCloseFlow] = useState(() => closeFlow ?? createCashCloseUiFlow());
  const [retainedFund, setRetainedFund] = useState(initialCloseFlow.retainedFund);
  const [finalWithdrawal, setFinalWithdrawal] = useState(initialCloseFlow.finalWithdrawal);
  const [comment, setComment] = useState(initialCloseFlow.comment);
  const [countedFund, setCountedFund] = useState("");
  const [openingDenominations, setOpeningDenominations] = useState<CashDenominationCount[]>([]);
  const [retainedDenominations, setRetainedDenominations] = useState<CashDenominationCount[]>(initialCloseFlow.retainedFundDenominations ?? []);
  const [withdrawalDenominations, setWithdrawalDenominations] = useState<CashDenominationCount[]>(initialCloseFlow.finalWithdrawalDenominations ?? []);
  const [countTarget, setCountTarget] = useState<"opening" | "retained" | "withdrawal" | null>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const [reconciliationAttemptId, setReconciliationAttemptId] =
    useState(initialCloseFlow.reconciliationAttemptId);
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [closePhase, setClosePhase] = useState<CashCloseUiPhase>(initialCloseFlow.phase);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const closeAttemptLocked = mode === "CLOSE" && closePhase !== "READY";

  useEffect(() => { onBusyChange?.(busy); }, [busy, onBusyChange]);
  useEffect(() => () => { onBusyChange?.(false); }, [onBusyChange]);
  useEffect(() => {
    if (embedded) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.querySelector<HTMLInputElement>("input:not(:disabled)")?.focus();
    return () => { if (opener?.isConnected) opener.focus(); };
  }, [embedded]);

  function updateCloseFlow(
    phase: CashCloseUiPhase,
    nextReconciliationAttemptId: string = reconciliationAttemptId,
  ) {
    setClosePhase(phase);
    onCloseFlowChange?.({
      closeOperationId: initialCloseFlow.closeOperationId,
      reconciliationAttemptId: nextReconciliationAttemptId,
      phase,
      retainedFund,
      finalWithdrawal,
      comment,
      retainedFundDenominations: retainedDenominations,
      finalWithdrawalDenominations: withdrawalDenominations,
    });
  }

  async function recoverRejectedClose(failure: unknown): Promise<boolean> {
    if (!(failure instanceof ApiError)) return false;
    try {
      const recovery = await recoverCashCloseOperation(
        terminalId,
        initialCloseFlow.closeOperationId,
        token,
        request,
      );
      if ((recovery.status === "CERRADA" || recovery.result?.status === "CERRADA")
        && recovery.result) {
        onClosed?.(recovery.result);
        return true;
      }
      if (recovery.status === "REQUIERE_ARQUEO") {
        const nextAttemptId = recovery.latestReconciliationAttemptId === reconciliationAttemptId
          ? createCashCloseWithdrawalIdempotencyKey()
          : reconciliationAttemptId;
        setReconciliationAttemptId(nextAttemptId);
        updateCloseFlow("RECONCILIATION_REQUIRED", nextAttemptId);
        setError(t.mismatch);
        return true;
      }
    } catch (recoveryFailure) {
      if (recoveryFailure instanceof ApiError
        && recoveryFailure.status === 404
        && recoveryFailure.problem?.code === "NOT_FOUND") {
        updateCloseFlow("READY");
      }
    }
    return false;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      if (mode === "OPEN") {
        const counted = amount(countedFund);
        if (counted == null) { setError(t.invalidAmount); return; }
        const openedSession = await openCashSession(terminalId, token, request, counted, openingDenominations);
        onOpened?.(openedSession);
        return;
      }
      const retained = amount(retainedFund);
      const withdrawal = amount(finalWithdrawal);
      if (retained == null || withdrawal == null) {
        setError(t.invalidAmount);
        return;
      }
      if ((requireClosingBreakdown && retained > 0 && !retainedDenominations.length)
        || (requireWithdrawalBreakdown && withdrawal > 0 && !withdrawalDenominations.length)) {
        setError(t.countedRequired);
        return;
      }
      if (!saleOperationAuthorizationComplete(
        authorization,
        authorizerUsername,
        authorizerPassword,
      )) {
        setError(t.authorizationRequired);
        return;
      }
      updateCloseFlow("ATTEMPTED");
      const session = await closeCashSession(
        terminalId,
        retained,
        withdrawal,
        comment,
        token,
        request,
        saleOperationCredentials(
          authorization,
          authorizerUsername,
          authorizerPassword,
        ),
        initialCloseFlow.closeOperationId,
        reconciliationAttemptId,
        { retainedFundDenominations: retainedDenominations, finalWithdrawalDenominations: withdrawalDenominations },
      );
      if (session.status === "ABIERTA") {
        const nextAttemptId = createCashCloseWithdrawalIdempotencyKey();
        setReconciliationAttemptId(nextAttemptId);
        updateCloseFlow("RECONCILIATION_REQUIRED", nextAttemptId);
        setError(t.mismatch);
        return;
      }
      onClosed?.(session);
    } catch (failure) {
      setAuthorizerPassword("");
      if (await recoverRejectedClose(failure)) return;
      setError(operationError(failure, t.error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={embedded ? "cash-session-embedded" : "sale-cash-session-overlay filter-overlay erp-classic-overlay"} role="presentation">
      <section
        ref={dialogRef}
        className={embedded ? "cash-session-flow" : "sale-cash-session-dialog filter-dialog erp-classic-window cash-session-flow"}
        role={embedded ? undefined : "dialog"}
        aria-modal={embedded ? undefined : true}
        aria-labelledby="sale-cash-session-title"
        onKeyDown={(event) => {
          if (countTarget) return;
          if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            if (!closeAttemptLocked && !busy) {
              if (mode === "OPEN") onExitSales?.(); else onCancel?.();
            }
          }
          if (event.key === "Tab" && !embedded) {
            const nodes = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("button:not(:disabled),input:not(:disabled),textarea:not(:disabled)"));
            const first = nodes[0], last = nodes.at(-1);
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
          }
        }}
      >
        <header>
          <h2 id="sale-cash-session-title">{mode === "OPEN" ? t.openTitle : t.closeTitle}</h2>
          {!embedded && <button type="button" aria-label={t.closeWindow} disabled={busy || closeAttemptLocked}
            onClick={() => { if (mode === "OPEN") onExitSales?.(); else onCancel?.(); }}>×</button>}
        </header>
        <form onSubmit={(event) => void submit(event)}>
          <p>{mode === "OPEN" ? t.openText : t.closeText}</p>
          {mode === "OPEN" && (
            <div className="cash-session-opening">
              <strong>{currentUsername}</strong>
              <label><span>{t.countedFund}</span><input inputMode="decimal" value={countedFund}
                disabled={busy} onChange={event => { setCountedFund(event.target.value); setOpeningDenominations([]); }} /></label>
              <button type="button" className="cash-denomination-trigger" disabled={busy} onClick={() => setCountTarget("opening")}>{t.count}</button>
            </div>
          )}
          {mode === "CLOSE" && (
            <>
            <div className="cash-session-close-parts">
              <section className="cash-session-part"><h4>{t.withdrawing}</h4><label>
                <span>{t.withdrawal}</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={finalWithdrawal}
                  onChange={(event) => { setFinalWithdrawal(event.currentTarget.value); setWithdrawalDenominations([]); }}
                  disabled={busy || closeAttemptLocked}
                />
              </label>
              <button type="button" className="cash-denomination-trigger" disabled={busy || closeAttemptLocked}
                onClick={() => setCountTarget("withdrawal")}>{t.count}</button></section>
              <section className="cash-session-part"><h4>{t.retaining}</h4><label>
                <span>{t.retained}</span>
                <input type="text" inputMode="decimal" value={retainedFund}
                  onChange={event => { setRetainedFund(event.target.value); setRetainedDenominations([]); }}
                  disabled={busy || closePhase === "ATTEMPTED"} />
              </label>
              <button type="button" className="cash-denomination-trigger" disabled={busy || closePhase === "ATTEMPTED"}
                onClick={() => setCountTarget("retained")}>{t.count}</button></section>
              <section className="cash-session-part"><h4>{t.closingUser}</h4>
              {authorization.mode === "DIRECT" && <strong className="cash-session-current-user">{currentUsername || "—"}</strong>}
              <SaleOperationAuthorizationFields locale={locale} currentUsername={currentUsername}
                authorization={authorization} username={authorizerUsername} password={authorizerPassword}
                disabled={busy} onUsernameChange={setAuthorizerUsername} onPasswordChange={setAuthorizerPassword} />
              </section>
            </div>
              <label className="sale-cash-session-comment cash-session-comment">
                <span>{t.comment}</span>
                <input
                  value={comment}
                  onChange={(event) => setComment(event.currentTarget.value)}
                  disabled={busy || closeAttemptLocked}
                />
              </label>
            </>
          )}
          {closeAttemptLocked && (
            <p className="sale-cash-session-progress" role="status">
              {closePhase === "RECONCILIATION_REQUIRED"
                ? t.reconciliationRequired
                : t.attempted}
            </p>
          )}
          {error && <p className="sale-cash-session-error" role="alert">{error}</p>}
          <footer className="filter-actions">
            {mode === "OPEN" ? (
              <button type="button" className="secondary" disabled={busy} onClick={onExitSales}>
                {openContext === "HOME" ? t.cancel : t.exit}
              </button>
            ) : !embedded ? (
              <button
                type="button"
                className="secondary"
                disabled={busy || closeAttemptLocked}
                onClick={onCancel}
              >
                {t.cancel}
              </button>
            ) : null}
            <button type="submit" className={mode === "CLOSE" ? "cash-close-action" : ""} disabled={busy || (mode === "OPEN" && amount(countedFund) == null)}>
              {busy
                ? mode === "OPEN" ? t.opening : t.closing
                : mode === "OPEN"
                  ? t.openAction
                  : closeAttemptLocked ? t.retryAction : t.closeAction}
            </button>
          </footer>
        </form>
      </section>
      {countTarget && <Suspense fallback={null}><CashDenominationDialog locale={locale} interfaceMode={interfaceMode}
        title={countTarget === "opening" ? t.countedFund : countTarget === "retained" ? t.retained : t.withdrawal}
        denominations={countTarget === "withdrawal"
          ? configuredWithdrawalDenominations?.length ? configuredWithdrawalDenominations : undefined
          : denominations?.length ? denominations : undefined}
        value={countTarget === "opening" ? openingDenominations : countTarget === "retained" ? retainedDenominations : withdrawalDenominations}
        onCancel={() => setCountTarget(null)} onAccept={(rows, total) => {
          const countedRows = rows.filter(row => row.quantity > 0);
          if (countTarget === "opening") { setCountedFund(total.toFixed(2)); setOpeningDenominations(countedRows); }
          else if (countTarget === "retained") { setRetainedFund(total.toFixed(2)); setRetainedDenominations(countedRows); }
          else { setFinalWithdrawal(total.toFixed(2)); setWithdrawalDenominations(countedRows); }
          setCountTarget(null);
        }} /></Suspense>}
    </div>
  );
}

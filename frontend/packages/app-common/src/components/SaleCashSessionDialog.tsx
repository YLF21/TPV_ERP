import { useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
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
import type { CashCloseRecoveryFlow } from "../sale/cashCloseRecovery";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type Props = {
  locale: LocaleCode;
  mode: "OPEN" | "CLOSE";
  terminalId: string;
  token: string;
  authorization?: SaleOperationAuthorization;
  closeFlow?: CashCloseUiFlow;
  onCloseFlowChange?: (flow: CashCloseUiFlow) => void;
  onExitSales?: () => void;
  onOpened?: (session: CashSessionView) => void;
  onClosed?: (session: CashSessionView) => void;
  onCancel?: () => void;
};

export type CashCloseUiPhase = CashCloseRecoveryFlow["phase"];
export type CashCloseUiFlow = CashCloseRecoveryFlow;

export function createCashCloseUiFlow(): CashCloseUiFlow {
  return {
    closeOperationId: createCashCloseWithdrawalIdempotencyKey(),
    reconciliationAttemptId: createCashCloseWithdrawalIdempotencyKey(),
    phase: "READY",
    retainedFund: "0",
    finalWithdrawal: "0",
    comment: "",
  };
}

const copy = {
  es: {
    openTitle: "Abrir caja",
    openText: "La sesión de caja es obligatoria. Debes abrirla para continuar en Ventas.",
    openAction: "Abrir caja",
    opening: "Abriendo…",
    exit: "Salir de Ventas",
    closeTitle: "Arqueo y cierre de caja",
    closeText: "Introduce el efectivo que quedará como fondo y la retirada final realizada.",
    retained: "Fondo que queda en caja",
    withdrawal: "Retirada final",
    comment: "Comentario de la retirada",
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
    openText: "A cash session is required. Open it to continue in Sales.",
    openAction: "Open register",
    opening: "Opening…",
    exit: "Exit Sales",
    closeTitle: "Cash count and close",
    closeText: "Enter the cash retained as opening fund and the final withdrawal performed.",
    retained: "Cash retained in register",
    withdrawal: "Final withdrawal",
    comment: "Withdrawal comment",
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
    openText: "必须开启收银会话。开启后才能继续销售。",
    openAction: "开启收银会话",
    opening: "正在开启…",
    exit: "退出销售",
    closeTitle: "盘点并关闭收银会话",
    closeText: "请输入保留为备用金的现金和最终取出的现金。",
    retained: "保留在钱箱中的现金",
    withdrawal: "最终取款",
    comment: "取款备注",
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
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

export function SaleCashSessionDialog({
  locale,
  mode,
  terminalId,
  token,
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
  const [reconciliationAttemptId, setReconciliationAttemptId] =
    useState(initialCloseFlow.reconciliationAttemptId);
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [closePhase, setClosePhase] = useState<CashCloseUiPhase>(initialCloseFlow.phase);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const closeAttemptLocked = mode === "CLOSE" && closePhase !== "READY";

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
    });
  }

  async function recoverRejectedClose(failure: unknown): Promise<boolean> {
    if (!(failure instanceof ApiError)) return false;
    try {
      const recovery = await recoverCashCloseOperation(
        terminalId,
        initialCloseFlow.closeOperationId,
        token,
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
        onOpened?.(await openCashSession(terminalId, token));
        return;
      }
      const retained = amount(retainedFund);
      const withdrawal = amount(finalWithdrawal);
      if (retained == null || withdrawal == null) {
        setError(t.invalidAmount);
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
        undefined,
        saleOperationCredentials(
          authorization,
          authorizerUsername,
          authorizerPassword,
        ),
        initialCloseFlow.closeOperationId,
        reconciliationAttemptId,
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
    <div className="sale-cash-session-overlay" role="presentation">
      <section
        className="sale-cash-session-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-cash-session-title"
        onKeyDown={(event) => {
          if (mode !== "CLOSE" || event.key !== "Escape") return;
          event.preventDefault();
          event.stopPropagation();
          if (!closeAttemptLocked && !busy) onCancel?.();
        }}
      >
        <header>
          <h2 id="sale-cash-session-title">{mode === "OPEN" ? t.openTitle : t.closeTitle}</h2>
        </header>
        <form onSubmit={(event) => void submit(event)}>
          <p>{mode === "OPEN" ? t.openText : t.closeText}</p>
          {mode === "CLOSE" && (
            <div className="sale-cash-session-fields">
              <label>
                <span>{t.retained}</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={retainedFund}
                  onChange={(event) => setRetainedFund(event.currentTarget.value)}
                  disabled={busy || closePhase === "ATTEMPTED"}
                  autoFocus
                />
              </label>
              <label>
                <span>{t.withdrawal}</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={finalWithdrawal}
                  onChange={(event) => setFinalWithdrawal(event.currentTarget.value)}
                  disabled={busy || closeAttemptLocked}
                />
              </label>
              <label className="sale-cash-session-comment">
                <span>{t.comment}</span>
                <input
                  value={comment}
                  onChange={(event) => setComment(event.currentTarget.value)}
                  disabled={busy || closeAttemptLocked}
                />
              </label>
              <SaleOperationAuthorizationFields
                locale={locale}
                authorization={authorization}
                username={authorizerUsername}
                password={authorizerPassword}
                disabled={busy}
                onUsernameChange={setAuthorizerUsername}
                onPasswordChange={setAuthorizerPassword}
              />
            </div>
          )}
          {closeAttemptLocked && (
            <p className="sale-cash-session-progress" role="status">
              {closePhase === "RECONCILIATION_REQUIRED"
                ? t.reconciliationRequired
                : t.attempted}
            </p>
          )}
          {error && <p className="sale-cash-session-error" role="alert">{error}</p>}
          <footer>
            {mode === "OPEN" ? (
              <button type="button" className="secondary" disabled={busy} onClick={onExitSales}>
                {t.exit}
              </button>
            ) : (
              <button
                type="button"
                className="secondary"
                disabled={busy || closeAttemptLocked}
                onClick={onCancel}
              >
                {t.cancel}
              </button>
            )}
            <button type="submit" disabled={busy}>
              {busy
                ? mode === "OPEN" ? t.opening : t.closing
                : mode === "OPEN"
                  ? t.openAction
                  : closeAttemptLocked ? t.retryAction : t.closeAction}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

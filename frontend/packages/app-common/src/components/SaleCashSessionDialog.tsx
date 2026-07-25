import { useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import type { LocaleCode } from "../types";
import {
  closeCashSession,
  openCashSession,
  type CashSessionView,
} from "../sale/cashSessions";

type Props = {
  locale: LocaleCode;
  mode: "OPEN" | "CLOSE";
  terminalId: string;
  token: string;
  onExitSales?: () => void;
  onOpened?: (session: CashSessionView) => void;
  onClosed?: (session: CashSessionView) => void;
  onCancel?: () => void;
};

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
    cancel: "Cancelar",
    invalidAmount: "Los importes deben ser números iguales o superiores a cero.",
    mismatch: "El arqueo presenta un descuadre. Revisa el efectivo y realiza el segundo intento.",
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
    cancel: "Cancel",
    invalidAmount: "Amounts must be numbers greater than or equal to zero.",
    mismatch: "The cash count does not match. Check the cash and submit the second attempt.",
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
    cancel: "取消",
    invalidAmount: "金额必须是大于或等于零的数字。",
    mismatch: "盘点存在差额。请检查现金并进行第二次盘点。",
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
  onExitSales,
  onOpened,
  onClosed,
  onCancel,
}: Props) {
  const t = copy[locale];
  const [retainedFund, setRetainedFund] = useState("0");
  const [finalWithdrawal, setFinalWithdrawal] = useState("0");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

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
      const session = await closeCashSession(
        terminalId,
        retained,
        withdrawal,
        comment,
        token,
      );
      if (session.status === "ABIERTA") {
        setError(t.mismatch);
        return;
      }
      onClosed?.(session);
    } catch (failure) {
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
                />
              </label>
              <label className="sale-cash-session-comment">
                <span>{t.comment}</span>
                <input value={comment} onChange={(event) => setComment(event.currentTarget.value)} />
              </label>
            </div>
          )}
          {error && <p className="sale-cash-session-error" role="alert">{error}</p>}
          <footer>
            {mode === "OPEN" ? (
              <button type="button" className="secondary" disabled={busy} onClick={onExitSales}>
                {t.exit}
              </button>
            ) : (
              <button type="button" className="secondary" disabled={busy} onClick={onCancel}>
                {t.cancel}
              </button>
            )}
            <button type="submit" disabled={busy}>
              {busy
                ? mode === "OPEN" ? t.opening : t.closing
                : mode === "OPEN" ? t.openAction : t.closeAction}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

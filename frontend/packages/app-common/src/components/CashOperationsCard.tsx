import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createCashCloseWithdrawalIdempotencyKey } from "../sale/cashSessions";
import type { LocaleCode } from "../types";
import "./ErpClassicTables.css";

type RequestFunction = typeof apiRequest;

type CashSession = {
  id: string;
  terminalId: string;
  status: string;
  openedAt?: string | null;
  openingFund: number;
  expectedCash: number;
  availableCash: number;
  retainedFund: number;
  discrepancy?: number | null;
  closedAt?: string | null;
};

type CashReport = {
  totalsByType?: Record<string, number>;
  retainedFunds?: number;
  discrepancies?: number;
};

type Props = {
  locale: LocaleCode;
  currentUsername?: string;
  token?: string;
  terminalId?: string;
  request?: RequestFunction;
};

const copy = {
  es: {
    title: "Caja y turno",
    description: "Controla la apertura, movimientos, efectivo esperado y cierre de la caja.",
    missingContext: "Inicia sesión y configura un terminal para gestionar la caja.",
    loading: "Consultando el estado de caja…",
    refresh: "Actualizar",
    noSession: "No hay una caja abierta en este terminal.",
    sessionOpen: "Caja abierta",
    openedAt: "Apertura",
    openingFund: "Fondo inicial",
    expectedCash: "Efectivo esperado",
    availableCash: "Disponible",
    retainedFund: "Fondo retenido",
    amount: "Importe",
    comment: "Motivo o comentario",
    prepareFund: "Preparar fondo",
    openCash: "Abrir caja",
    entry: "Entrada de efectivo",
    withdrawal: "Retirada de efectivo",
    currentOperator: "Operador actual",
    managerUser: "Usuario autorizador",
    managerPassword: "Contraseña autorizador",
    registerEntry: "Registrar entrada",
    registerWithdrawal: "Registrar retirada",
    closeTitle: "Cierre y conciliación",
    finalWithdrawal: "Retirada final",
    closeCash: "Cerrar caja",
    closeNav: "Cierre",
    entryNav: "Entrada",
    withdrawalNav: "Retirada",
    concept: "Concepto",
    dailySummary: "Resumen de hoy",
    noMovements: "Sin movimientos registrados hoy.",
    retained: "Fondos retenidos",
    discrepancies: "Descuadres",
    success: "Operación de caja completada.",
    sessionClosed: "La caja se ha cerrado correctamente.",
    error: "No se pudo completar la operación de caja.",
    movementTypes: {
      COBRO_EFECTIVO: "Cobros en efectivo",
      DEVOLUCION_EFECTIVO: "Devoluciones en efectivo",
      ENTRADA: "Entradas de efectivo",
      RETIRADA: "Retiradas de efectivo",
      RETIRADA_CIERRE: "Retiradas de cierre",
      ENTRADA_ENTRE_SESIONES: "Entradas entre sesiones",
      RETIRADA_ENTRE_SESIONES: "Retiradas entre sesiones",
    },
  },
  en: {
    title: "Cash register and shift",
    description: "Manage opening, movements, expected cash and register closing.",
    missingContext: "Sign in and configure a terminal to manage the cash register.",
    loading: "Checking cash register status…",
    refresh: "Refresh",
    noSession: "There is no open cash register on this terminal.",
    sessionOpen: "Cash register open",
    openedAt: "Opened",
    openingFund: "Opening fund",
    expectedCash: "Expected cash",
    availableCash: "Available",
    retainedFund: "Retained fund",
    amount: "Amount",
    comment: "Reason or comment",
    prepareFund: "Prepare fund",
    openCash: "Open register",
    entry: "Cash entry",
    withdrawal: "Cash withdrawal",
    currentOperator: "Current operator",
    managerUser: "Authorizer username",
    managerPassword: "Authorizer password",
    registerEntry: "Register entry",
    registerWithdrawal: "Register withdrawal",
    closeTitle: "Closing and reconciliation",
    finalWithdrawal: "Final withdrawal",
    closeCash: "Close register",
    closeNav: "Closing",
    entryNav: "Entry",
    withdrawalNav: "Withdrawal",
    concept: "Concept",
    dailySummary: "Today's summary",
    noMovements: "No movements registered today.",
    retained: "Retained funds",
    discrepancies: "Discrepancies",
    success: "Cash register operation completed.",
    sessionClosed: "The cash register was closed successfully.",
    error: "The cash register operation could not be completed.",
    movementTypes: {
      COBRO_EFECTIVO: "Cash payments",
      DEVOLUCION_EFECTIVO: "Cash refunds",
      ENTRADA: "Cash entries",
      RETIRADA: "Cash withdrawals",
      RETIRADA_CIERRE: "Closing withdrawals",
      ENTRADA_ENTRE_SESIONES: "Entries between sessions",
      RETIRADA_ENTRE_SESIONES: "Withdrawals between sessions",
    },
  },
  zh: {
    title: "钱箱与班次",
    description: "管理开箱、现金变动、预期现金和关箱对账。",
    missingContext: "请登录并配置终端后管理钱箱。",
    loading: "正在查询钱箱状态…",
    refresh: "刷新",
    noSession: "此终端当前没有打开的钱箱。",
    sessionOpen: "钱箱已打开",
    openedAt: "开箱时间",
    openingFund: "备用金",
    expectedCash: "预期现金",
    availableCash: "可用现金",
    retainedFund: "保留资金",
    amount: "金额",
    comment: "原因或备注",
    prepareFund: "准备备用金",
    openCash: "打开钱箱",
    entry: "现金存入",
    withdrawal: "现金取出",
    currentOperator: "当前操作员",
    managerUser: "授权用户名",
    managerPassword: "授权密码",
    registerEntry: "登记存入",
    registerWithdrawal: "登记取出",
    closeTitle: "关箱与对账",
    finalWithdrawal: "最终取出",
    closeCash: "关闭钱箱",
    closeNav: "关箱",
    entryNav: "存入",
    withdrawalNav: "取出",
    concept: "项目",
    dailySummary: "今日汇总",
    noMovements: "今天没有现金变动。",
    retained: "保留资金",
    discrepancies: "差额",
    success: "钱箱操作已完成。",
    sessionClosed: "钱箱已成功关闭。",
    error: "无法完成钱箱操作。",
    movementTypes: {
      COBRO_EFECTIVO: "现金收款",
      DEVOLUCION_EFECTIVO: "现金退款",
      ENTRADA: "现金存入",
      RETIRADA: "现金取出",
      RETIRADA_CIERRE: "关箱取出",
      ENTRADA_ENTRE_SESIONES: "班次间存入",
      RETIRADA_ENTRE_SESIONES: "班次间取出",
    },
  },
} as const;

function getErrorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    const detail = error.problem?.detail;
    const title = error.problem?.title;
    return (
      (typeof detail === "string" && detail) ||
      (typeof title === "string" && title) ||
      error.message ||
      fallback
    );
  }
  return error instanceof Error ? error.message : fallback;
}

function isMissingSession(error: unknown) {
  const message = getErrorMessage(error, "").toLocaleLowerCase();
  return (
    message.includes("no hay una sesion de caja abierta") ||
    message.includes("no hay una sesión de caja abierta") ||
    message.includes("cash session") && message.includes("not")
  );
}

function parsePositiveAmount(value: string) {
  const amount = Number(value.replace(",", "."));
  return Number.isFinite(amount) && amount >= 0 ? amount : null;
}

function startOfTodayIso() {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  return start.toISOString();
}

export function CashOperationsCard({
  locale,
  currentUsername = "",
  token,
  terminalId,
  request = apiRequest,
}: Props) {
  const t = copy[locale];
  const [session, setSession] = useState<CashSession | null>(null);
  const [report, setReport] = useState<CashReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [openingFund, setOpeningFund] = useState("0");
  const [entryAmount, setEntryAmount] = useState("");
  const [withdrawalAmount, setWithdrawalAmount] = useState("");
  const [retainedFund, setRetainedFund] = useState("0");
  const [finalWithdrawal, setFinalWithdrawal] = useState("0");
  const [comment, setComment] = useState("");
  const [managerUsername, setManagerUsername] = useState("");
  const [managerPassword, setManagerPassword] = useState("");
  const [selectedOperation, setSelectedOperation] = useState<"close" | "entry" | "withdrawal">("close");
  const [closeFlow, setCloseFlow] = useState(() => ({
    sessionId: null as string | null,
    operationId: createCashCloseWithdrawalIdempotencyKey(),
    attemptId: createCashCloseWithdrawalIdempotencyKey(),
  }));

  const money = useMemo(
    () =>
      new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
        style: "currency",
        currency: "EUR",
      }),
    [locale],
  );

  const load = useCallback(async () => {
    if (!token || !terminalId) {
      setSession(null);
      setReport(null);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const current = await request<CashSession>(
        `/cash/status?terminalId=${encodeURIComponent(terminalId)}`,
        { token },
      );
      setSession(current);
      setRetainedFund(String(current.retainedFund ?? 0));
    } catch (loadError) {
      if (isMissingSession(loadError)) {
        setSession(null);
      } else {
        setError(getErrorMessage(loadError, t.error));
      }
    }
    try {
      const daily = await request<CashReport>(
        `/cash/reports?terminalId=${encodeURIComponent(terminalId)}&from=${encodeURIComponent(
          startOfTodayIso(),
        )}&to=${encodeURIComponent(new Date().toISOString())}`,
        { token },
      );
      setReport(daily);
    } catch {
      setReport(null);
    } finally {
      setLoading(false);
    }
  }, [request, t.error, terminalId, token]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (session?.id && closeFlow.sessionId !== session.id) {
      setCloseFlow({
        sessionId: session.id,
        operationId: createCashCloseWithdrawalIdempotencyKey(),
        attemptId: createCashCloseWithdrawalIdempotencyKey(),
      });
    }
  }, [closeFlow.sessionId, session?.id]);

  const execute = async (
    path: string,
    body: Record<string, unknown>,
    successMessage: string = t.success,
  ) => {
    if (!token) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await request<{ status?: string }>(path, { token, method: "POST", body });
      setNotice(successMessage);
      setComment("");
      setEntryAmount("");
      setWithdrawalAmount("");
      setManagerPassword("");
      await load();
      return result;
    } catch (operationError) {
      setError(getErrorMessage(operationError, t.error));
    } finally {
      setBusy(false);
    }
  };

  const commonMovementBody = (amount: number) => ({
    terminalId,
    amount,
    comment: comment.trim(),
    authorizerUsername: managerUsername.trim(),
    authorizerPassword: managerPassword,
    denominations: [],
  });

  if (!token || !terminalId) {
    return (
      <section className="settings-card cash-operations-card">
        <h3>{t.title}</h3>
        <p>{t.description}</p>
        <div className="settings-empty-state">{t.missingContext}</div>
      </section>
    );
  }

  const reportEntries = Object.entries(report?.totalsByType ?? {});

  return (
    <section className="settings-card cash-operations-card">
      <div className="settings-card-heading cash-operations-heading">
        <div>
          <h3>{t.title}</h3>
          <p>{t.description}</p>
        </div>
        <button className="secondary-button" type="button" onClick={() => void load()} disabled={loading || busy}>
          {t.refresh}
        </button>
      </div>

      {loading ? <div className="settings-empty-state">{t.loading}</div> : null}
      {error ? <div className="settings-inline-message error">{error}</div> : null}
      {notice ? <div className="settings-inline-message success">{notice}</div> : null}

      {!loading && !session ? (
        <div className="cash-operation-panel">
          <strong>{t.noSession}</strong>
          <div className="cash-operation-form cash-opening-form">
            <label>
              <span>{t.openingFund}</span>
              <input
                inputMode="decimal"
                value={openingFund}
                onChange={(event) => setOpeningFund(event.target.value)}
              />
            </label>
            <label>
              <span>{t.comment}</span>
              <input value={comment} onChange={(event) => setComment(event.target.value)} />
            </label>
            <div className="cash-operation-actions">
              <button
                className="secondary-button"
                type="button"
                disabled={busy || parsePositiveAmount(openingFund) === null}
                onClick={() => {
                  const amount = parsePositiveAmount(openingFund);
                  if (amount !== null) {
                    void execute("/cash/movements/between-sessions", {
                      terminalId,
                      amount,
                      comment: comment.trim(),
                      denominations: [],
                      withdrawal: false,
                    });
                  }
                }}
              >
                {t.prepareFund}
              </button>
              <button
                className="primary-button"
                type="button"
                disabled={busy || parsePositiveAmount(openingFund) === null}
                onClick={() => {
                  const amount = parsePositiveAmount(openingFund);
                  if (amount !== null) {
                    void execute("/cash/sessions/open", {
                      terminalId,
                    });
                  }
                }}
              >
                {t.openCash}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {session ? (
        <>
          <div className="cash-session-summary">
            <div>
              <span>{t.sessionOpen}</span>
              <strong>{session.status}</strong>
            </div>
            <div>
              <span>{t.openedAt}</span>
              <strong>{session.openedAt ? new Date(session.openedAt).toLocaleString() : "—"}</strong>
            </div>
            <div>
              <span>{t.openingFund}</span>
              <strong>{money.format(session.openingFund ?? 0)}</strong>
            </div>
            <div>
              <span>{t.expectedCash}</span>
              <strong>{money.format(session.expectedCash ?? 0)}</strong>
            </div>
            <div>
              <span>{t.availableCash}</span>
              <strong>{money.format(session.availableCash ?? 0)}</strong>
            </div>
          </div>

          <div className="cash-operation-workspace">
            <nav className="cash-operation-nav" aria-label={t.title}>
              {([
                ["close", t.closeNav],
                ["entry", t.entryNav],
                ["withdrawal", t.withdrawalNav],
              ] as const).map(([operation, label]) => (
                <button
                  key={operation}
                  className={selectedOperation === operation ? "cash-selected-operation" : ""}
                  type="button"
                  aria-pressed={selectedOperation === operation}
                  disabled={busy}
                  onClick={() => setSelectedOperation(operation)}
                >
                  {label}
                </button>
              ))}
            </nav>

            <div className="cash-operation-panel cash-selected-operation-panel">
              <h4>{selectedOperation === "close" ? t.closeTitle : selectedOperation === "entry" ? t.entry : t.withdrawal}</h4>
              <div className="cash-operation-fields">
                {selectedOperation === "close" ? (
                  <>
                    <label>
                      <span>{t.retainedFund}</span>
                      <input inputMode="decimal" value={retainedFund} disabled={busy} onChange={(event) => setRetainedFund(event.target.value)} />
                    </label>
                    <label>
                      <span>{t.finalWithdrawal}</span>
                      <input
                        inputMode="decimal"
                        value={finalWithdrawal}
                        disabled={busy}
                        onChange={(event) => setFinalWithdrawal(event.target.value)}
                      />
                    </label>
                  </>
                ) : selectedOperation === "entry" ? (
                  <label>
                    <span>{t.amount}</span>
                    <input inputMode="decimal" value={entryAmount} disabled={busy} onChange={(event) => setEntryAmount(event.target.value)} />
                  </label>
                ) : (
                  <label>
                    <span>{t.amount}</span>
                    <input
                      inputMode="decimal"
                      value={withdrawalAmount}
                      disabled={busy}
                      onChange={(event) => setWithdrawalAmount(event.target.value)}
                    />
                  </label>
                )}
              </div>

              <div className="cash-authorization-row">
                <div className="cash-authorization-operator sale-operation-authorization-identity">
                  <span className="sale-operation-authorization-avatar" aria-hidden="true">
                    {currentUsername.trim().slice(0, 1).toLocaleUpperCase(locale) || "?"}
                  </span>
                  <span className="sale-operation-authorization-user">
                    <small>{t.currentOperator}</small>
                    <strong>{currentUsername.trim() || "-"}</strong>
                  </span>
                </div>
                <label>
                  <span>{t.comment}</span>
                  <input value={comment} disabled={busy} onChange={(event) => setComment(event.target.value)} />
                </label>
                <label>
                  <span>{t.managerUser}</span>
                  <input value={managerUsername} disabled={busy} onChange={(event) => setManagerUsername(event.target.value)} />
                </label>
                <label>
                  <span>{t.managerPassword}</span>
                  <input
                    type="password"
                    value={managerPassword}
                    disabled={busy}
                    onChange={(event) => setManagerPassword(event.target.value)}
                  />
                </label>
              </div>

              <div className="cash-operation-submit">
                {selectedOperation === "close" ? (
                  <button
                    className="danger-button"
                    type="button"
                    disabled={
                      busy ||
                      parsePositiveAmount(retainedFund) === null ||
                      parsePositiveAmount(finalWithdrawal) === null
                    }
                    onClick={() => {
                      const retained = parsePositiveAmount(retainedFund);
                      const withdrawal = parsePositiveAmount(finalWithdrawal);
                      if (retained !== null && withdrawal !== null) {
                        void execute(
                          "/cash/sessions/close",
                          {
                            terminalId,
                            retainedFund: retained,
                            retainedFundDenominations: [],
                            finalWithdrawalAmount: withdrawal,
                            finalWithdrawalComment: comment.trim(),
                            finalWithdrawalDenominations: [],
                            closeOperationId: closeFlow.operationId,
                            reconciliationAttemptId: closeFlow.attemptId,
                            authorizerUsername: managerUsername.trim(),
                            authorizerPassword: managerPassword,
                          },
                          t.sessionClosed,
                        ).then((result) => {
                          if (result?.status === "ABIERTA") {
                            setCloseFlow((current) => ({
                              ...current,
                              attemptId: createCashCloseWithdrawalIdempotencyKey(),
                            }));
                          }
                        });
                      }
                    }}
                  >
                    {t.closeCash}
                  </button>
                ) : selectedOperation === "entry" ? (
                  <button
                    className="primary-button"
                    type="button"
                    disabled={busy || !parsePositiveAmount(entryAmount)}
                    onClick={() => {
                      const amount = parsePositiveAmount(entryAmount);
                      if (amount) void execute("/cash/movements/entry", commonMovementBody(amount));
                    }}
                  >
                    {t.registerEntry}
                  </button>
                ) : (
                  <button
                    className="primary-button"
                    type="button"
                    disabled={busy || !parsePositiveAmount(withdrawalAmount)}
                    onClick={() => {
                      const amount = parsePositiveAmount(withdrawalAmount);
                      if (amount) {
                        void execute("/cash/movements/withdrawal", {
                          terminalId,
                          amount,
                          comment: comment.trim(),
                          denominations: [],
                          withdrawal: true,
                          authorizerUsername: managerUsername.trim(),
                          authorizerPassword: managerPassword,
                        });
                      }
                    }}
                  >
                    {t.registerWithdrawal}
                  </button>
                )}
              </div>
            </div>
          </div>
        </>
      ) : null}

      <div className="cash-daily-summary">
        <h4>{t.dailySummary}</h4>
        {report ? (
          <div className="cash-report-table-wrap erp-classic-tables">
            <table className="cash-report-table" aria-label={t.dailySummary}>
              <thead>
                <tr>
                  <th scope="col">{t.concept}</th>
                  <th scope="col">{t.amount}</th>
                </tr>
              </thead>
              <tbody>
                {reportEntries.map(([type, amount]) => (
                  <tr key={type}>
                    <td>{t.movementTypes[type as keyof typeof t.movementTypes] ?? type.replaceAll("_", " ")}</td>
                    <td>{money.format(amount ?? 0)}</td>
                  </tr>
                ))}
                <tr>
                  <td>{t.retained}</td>
                  <td>{money.format(report.retainedFunds ?? 0)}</td>
                </tr>
                <tr>
                  <td>{t.discrepancies}</td>
                  <td>{money.format(report.discrepancies ?? 0)}</td>
                </tr>
              </tbody>
            </table>
          </div>
        ) : (
          <span className="settings-muted-text">{t.noMovements}</span>
        )}
      </div>
    </section>
  );
}

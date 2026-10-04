import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { clearCashCloseRecovery, loadCashCloseRecovery, saveCashCloseRecovery, type CashCloseRecoveryIdentity } from "../sale/cashCloseRecovery";
import { printCashEntryReceipt, printCashWithdrawalReceipt } from "../sale/cashWithdrawal";
import { createCashCloseWithdrawalIdempotencyKey, loadCashSessionReadiness, prepareCashSessionForSales, recoverCashCloseOperation, type CashSalesSessionReadiness, type CashSessionView } from "../sale/cashSessions";
import type { TicketPrintOutcome } from "../sale/ticketPrinting";
import { findSaleOperationAuthorization, loadSalesOperationSecurity, saleOperationAuthorizationComplete, saleOperationCredentials, type SaleOperationAuthorization } from "../sale/operationSecurity";
import type { LocaleCode, TerminalContext } from "../types";
import { CashDenominationDialog, type CashDenominationCount } from "./CashDenominationDialog";
import { SaleCashSessionDialog, createCashCloseUiFlow, type CashCloseUiFlow } from "./SaleCashSessionDialog";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import "./CashOperationsFlow.css";

type RequestFunction = typeof apiRequest;
type Operation = "close" | "entry" | "withdrawal";
type Movement = "entry" | "withdrawal";
type DraftKey = Movement | "betweenEntry" | "betweenWithdrawal";
type MovementDraft = { amount: string; comment: string; denominations: CashDenominationCount[]; username: string; password: string };
type PendingReceipt = { id: string; entry: boolean; technicalMessage?: string };
type Props = { locale: LocaleCode; refreshContainer?: HTMLElement | null; interfaceMode?: SaleInterfaceMode; currentUsername?: string; permissions?: string[]; token?: string; terminalId?: string; terminalCode?: string; recoveryIdentity?: TerminalContext; storeName?: string; request?: RequestFunction };
const blankDraft = (): MovementDraft => ({ amount: "", comment: "", denominations: [], username: "", password: "" });
const fallbackAuthorization: SaleOperationAuthorization = { mode: "CURRENT_PASSWORD", requireUsername: false, requirePassword: true };
const copy = {
  es: { title: "Caja y turno", description: "Controla la apertura, movimientos y cierre de caja.", missingContext: "Inicia sesión y configura un terminal para gestionar la caja.", loading: "Consultando el estado de caja…", refresh: "Actualizar", noSession: "No hay una caja abierta en este terminal.", sessionOpen: "Caja abierta", openedAt: "Apertura", openingFund: "Fondo inicial", expectedCash: "Efectivo esperado", availableCash: "Disponible", closeNav: "Cierre", entryNav: "Entrada", withdrawalNav: "Retirada", betweenTitle: "Movimientos entre sesiones", betweenExplanation: "El efectivo entregado o retirado después del cierre modifica el próximo fondo inicial. No se incluye automáticamente en el importe contado al abrir.", betweenEntry: "Entrada entre sesiones", betweenWithdrawal: "Retirada entre sesiones", amount: "Importe", comment: "Motivo o comentario", count: "Contar monedas y billetes", currentOperator: "Operador actual", registerEntry: "Registrar entrada", registerWithdrawal: "Registrar retirada", registerBetweenEntry: "Registrar entrada entre sesiones", registerBetweenWithdrawal: "Registrar retirada entre sesiones", invalidAmount: "Introduce un importe positivo con hasta dos decimales.", reasonRequired: "Introduce un motivo o comentario.", breakdownRequired: "Cuenta las monedas y billetes de este importe.", authorizationRequired: "Completa la autorización de la operación.", success: "Operación de caja completada.", closed: "La caja se ha cerrado correctamente.", error: "No se pudo completar la operación de caja.", recoveryBlocked: "Hay un cierre pendiente que no se puede recuperar. Revisa los datos locales del terminal antes de continuar.", timeline: "Historial de hoy", hour: "Hora", user: "Usuario", action: "Acción", concept: "Concepto", quantity: "Cantidad", balance: "Saldo", reference: "Referencia", filterUser: "Filtrar usuario", filterAction: "Filtrar acción", all: "Todos", noData: "SIN DATOS", actions: { OPENING: "Apertura", CLOSING: "Cierre", ENTRADA: "Entrada", RETIRADA: "Retirada", RETIRADA_CIERRE: "Retirada de cierre", ENTRADA_ENTRE_SESIONES: "Entrada entre sesiones", RETIRADA_ENTRE_SESIONES: "Retirada entre sesiones", COBRO_EFECTIVO: "Cobro en efectivo", DEVOLUCION_EFECTIVO: "Devolución en efectivo" } },
  en: { title: "Cash register and shift", description: "Manage opening, movements and closing.", missingContext: "Sign in and configure a terminal to manage the cash register.", loading: "Checking cash register status…", refresh: "Refresh", noSession: "There is no open cash register on this terminal.", sessionOpen: "Cash register open", openedAt: "Opened", openingFund: "Opening fund", expectedCash: "Expected cash", availableCash: "Available", closeNav: "Closing", entryNav: "Entry", withdrawalNav: "Withdrawal", betweenTitle: "Between-session movements", betweenExplanation: "Cash delivered or withdrawn after closing changes the next opening fund. It is not automatically included in the counted opening amount.", betweenEntry: "Between-session entry", betweenWithdrawal: "Between-session withdrawal", amount: "Amount", comment: "Reason or comment", count: "Count coins and notes", currentOperator: "Current operator", registerEntry: "Register entry", registerWithdrawal: "Register withdrawal", registerBetweenEntry: "Register between-session entry", registerBetweenWithdrawal: "Register between-session withdrawal", invalidAmount: "Enter a positive amount with up to two decimal places.", reasonRequired: "Enter a reason or comment.", breakdownRequired: "Count the coins and notes for this amount.", authorizationRequired: "Complete the operation authorization.", success: "Cash operation completed.", closed: "The register was closed successfully.", error: "The cash operation could not be completed.", recoveryBlocked: "A pending close cannot be recovered. Review the terminal's local data before continuing.", timeline: "Today's history", hour: "Time", user: "User", action: "Action", concept: "Concept", quantity: "Amount", balance: "Balance", reference: "Reference", filterUser: "Filter user", filterAction: "Filter action", all: "All", noData: "NO DATA", actions: { OPENING: "Opening", CLOSING: "Closing", ENTRADA: "Entry", RETIRADA: "Withdrawal", RETIRADA_CIERRE: "Closing withdrawal", ENTRADA_ENTRE_SESIONES: "Between-session entry", RETIRADA_ENTRE_SESIONES: "Between-session withdrawal", COBRO_EFECTIVO: "Cash payment", DEVOLUCION_EFECTIVO: "Cash refund" } },
  zh: { title: "钱箱与班次", description: "管理开箱、现金变动和关箱。", missingContext: "请登录并配置终端后管理钱箱。", loading: "正在查询钱箱状态…", refresh: "刷新", noSession: "此终端当前没有打开的钱箱。", sessionOpen: "钱箱已打开", openedAt: "开箱时间", openingFund: "初始备用金", expectedCash: "预期现金", availableCash: "可用现金", closeNav: "关箱", entryNav: "存入", withdrawalNav: "取出", betweenTitle: "班次间现金变动", betweenExplanation: "关箱后交入或取出的现金会改变下次开箱备用金，开箱清点金额不会自动包含这笔现金。", betweenEntry: "班次间存入", betweenWithdrawal: "班次间取出", amount: "金额", comment: "原因或备注", count: "清点硬币和纸币", currentOperator: "当前操作员", registerEntry: "登记存入", registerWithdrawal: "登记取出", registerBetweenEntry: "登记班次间存入", registerBetweenWithdrawal: "登记班次间取出", invalidAmount: "请输入最多两位小数的正金额。", reasonRequired: "请输入原因或备注。", breakdownRequired: "请清点此金额的硬币和纸币。", authorizationRequired: "请完成操作授权。", success: "钱箱操作已完成。", closed: "钱箱已成功关闭。", error: "无法完成钱箱操作。", recoveryBlocked: "无法恢复未完成的关箱操作，请先检查此终端的本地数据。", timeline: "今日流水", hour: "时间", user: "用户", action: "操作", concept: "项目", quantity: "金额", balance: "余额", reference: "参考", filterUser: "筛选用户", filterAction: "筛选操作", all: "全部", noData: "无数据", actions: { OPENING: "开箱", CLOSING: "关箱", ENTRADA: "存入", RETIRADA: "取出", RETIRADA_CIERRE: "关箱取出", ENTRADA_ENTRE_SESIONES: "班次间存入", RETIRADA_ENTRE_SESIONES: "班次间取出", COBRO_EFECTIVO: "现金收款", DEVOLUCION_EFECTIVO: "现金退款" } },
} as const;
const automaticCopy = {
  es: { title: "Apertura automática", explanation: "La política de esta tienda abre la caja sin recuento manual.", action: "Abrir caja automáticamente" },
  en: { title: "Automatic opening", explanation: "This store opens the register without a manual cash count.", action: "Open register automatically" },
  zh: { title: "自动开箱", explanation: "此门店按策略自动开箱，无需手工清点现金。", action: "自动打开钱箱" },
} as const;

function operationError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (typeof error.problem?.detail === "string" && error.problem.detail) return error.problem.detail;
    if (typeof error.problem?.title === "string" && error.problem.title) return error.problem.title;
    return error.message || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}
function missingSession(error: unknown): boolean {
  const message = operationError(error, "").toLocaleLowerCase();
  return message.includes("no hay una sesion de caja abierta") || message.includes("no hay una sesión de caja abierta")
    || message.includes("cash session") && message.includes("not open");
}
function positiveAmount(value: string): number | null {
  if (!/^\d+(?:[.,]\d{1,2})?$/.test(value.trim())) return null;
  const cents = Math.round(Number(value.trim().replace(",", ".")) * 100);
  return Number.isSafeInteger(cents) && cents > 0 ? cents / 100 : null;
}
export function CashOperationsCard({ locale, refreshContainer, interfaceMode = "KEYBOARD", currentUsername = "", permissions = [], token, terminalId, terminalCode, recoveryIdentity, storeName = "", request = apiRequest }: Props) {
  const recoveryScope = useMemo<CashCloseRecoveryIdentity>(() => recoveryIdentity?.bindingId ? {
    terminalCode: terminalCode ?? "", installationId: recoveryIdentity.installationId ?? "",
    terminalId: recoveryIdentity.terminalId ?? "", bindingId: recoveryIdentity.bindingId,
    legacyTerminalCode: recoveryIdentity.legacyTerminalCode,
  } : terminalCode ?? "", [terminalCode, recoveryIdentity?.installationId, recoveryIdentity?.terminalId,
    recoveryIdentity?.bindingId, recoveryIdentity?.legacyTerminalCode]);
  const t = copy[locale];
  const printLabels = createTranslator(locale);
  const [session, setSession] = useState<CashSessionView | null>(null);
  const [readiness, setReadiness] = useState<CashSalesSessionReadiness | null>(null);
  const [statusKnown, setStatusKnown] = useState(false);
  const [closeAuthorization, setCloseAuthorization] = useState<SaleOperationAuthorization>(fallbackAuthorization);
  const [movementAuthorization, setMovementAuthorization] = useState<SaleOperationAuthorization>(fallbackAuthorization);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const movementBusyRef = useRef(false);
  const [pendingReceipts, setPendingReceipts] = useState<PendingReceipt[]>([]);
  const [closeBusy, setCloseBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedOperation, setSelectedOperation] = useState<Operation>("close");
  const [openFormKey, setOpenFormKey] = useState(0);
  const [betweenOperation, setBetweenOperation] = useState<Movement>("entry");
  const [entryDraft, setEntryDraft] = useState<MovementDraft>(blankDraft);
  const [withdrawalDraft, setWithdrawalDraft] = useState<MovementDraft>(blankDraft);
  const [betweenEntryDraft, setBetweenEntryDraft] = useState<MovementDraft>(blankDraft);
  const [betweenWithdrawalDraft, setBetweenWithdrawalDraft] = useState<MovementDraft>(blankDraft);
  const [countTarget, setCountTarget] = useState<DraftKey | null>(null);
  const [flowSessionId, setFlowSessionId] = useState<string | null>(null);
  const flowSessionRef = useRef<string | null>(null);
  const [closeFlow, setCloseFlow] = useState<CashCloseUiFlow>(createCashCloseUiFlow);
  const [recoveryBlocked, setRecoveryBlocked] = useState(false);
  const money = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { style: "currency", currency: "EUR" }), [locale]);
  const dateLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const permissionKey = permissions.join("\u0000");

  const load = useCallback(async () => {
    if (!token || !terminalId) { setSession(null); setStatusKnown(false); return; }
    setLoading(true); setError(null);
    let recovered: { flow: CashCloseUiFlow; sessionId: string } | null = null;
    let recoveryFailed = false;
    if (terminalCode) {
      try {
        const saved = loadCashCloseRecovery(localStorage, recoveryScope);
        if (saved.status === "blocked") recoveryFailed = true;
        else if (saved.status === "valid" && saved.envelope.flow.phase !== "READY") {
          const prior = saved.envelope.flow;
          try {
            const operation = await recoverCashCloseOperation(terminalId, prior.closeOperationId, token, request);
            if (operation.status === "CERRADA" || operation.result?.status === "CERRADA") {
              clearCashCloseRecovery(localStorage, recoveryScope);
            } else {
              const flow: CashCloseUiFlow = {
                ...prior,
                phase: operation.status === "REQUIERE_ARQUEO" ? "RECONCILIATION_REQUIRED" : "ATTEMPTED",
                reconciliationAttemptId: operation.latestReconciliationAttemptId === prior.reconciliationAttemptId
                  ? createCashCloseWithdrawalIdempotencyKey() : prior.reconciliationAttemptId,
                finalWithdrawal: String(operation.finalWithdrawalAmount),
                comment: operation.finalWithdrawalComment ?? "",
              };
              recovered = { flow, sessionId: operation.sessionId };
            }
          } catch (failure) {
            if (failure instanceof ApiError && failure.status === 404 && failure.problem?.code === "NOT_FOUND") {
              // The attempted request never created an operation; the draft can be edited again.
              recovered = { flow: { ...prior, phase: "READY" }, sessionId: "" };
              clearCashCloseRecovery(localStorage, recoveryScope);
            } else recoveryFailed = true;
          }
        }
      } catch { recoveryFailed = true; }
    }
    const [statusResult, readinessResult, securityResult] = await Promise.allSettled([
      request<CashSessionView>(`/cash/status?terminalId=${encodeURIComponent(terminalId)}`, { token }),
      loadCashSessionReadiness(terminalId, token, request),
      loadSalesOperationSecurity(token, request),
    ]);
    if (statusResult.status === "fulfilled") {
      const current = statusResult.value;
      setSession(current); setStatusKnown(true);
      if (recovered?.sessionId && recovered.sessionId !== current.id) recoveryFailed = true;
      else if (flowSessionRef.current !== current.id && !recoveryFailed) {
        const flow = recovered?.flow ?? createCashCloseUiFlow();
        flowSessionRef.current = current.id;
        setCloseFlow(flow); setFlowSessionId(current.id);
        if (recovered?.sessionId && terminalCode) {
          try { saveCashCloseRecovery(localStorage, recoveryScope, flow); }
          catch { recoveryFailed = true; }
        }
      }
    } else if (missingSession(statusResult.reason)) {
      setSession(null); setStatusKnown(true);
      if (recovered?.sessionId) recoveryFailed = true;
    } else { recoveryFailed = true; setError(operationError(statusResult.reason, t.error)); }
    setRecoveryBlocked(recoveryFailed);
    if (readinessResult.status === "fulfilled") setReadiness(readinessResult.value);
    else { setReadiness(null); setError(operationError(readinessResult.reason, t.error)); }
    if (securityResult.status === "fulfilled") {
      setCloseAuthorization(findSaleOperationAuthorization(securityResult.value, "CLOSE_CASH_SESSION", permissions) ?? fallbackAuthorization);
      setMovementAuthorization(findSaleOperationAuthorization(securityResult.value, "CASH_MOVEMENT", permissions) ?? fallbackAuthorization);
    } else { setCloseAuthorization(fallbackAuthorization); setMovementAuthorization(fallbackAuthorization); }
    setLoading(false);
  // permissionKey captures the permission contents without depending on the caller's array identity.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [permissionKey, request, t.error, terminalCode, terminalId, token, recoveryScope]);

  useEffect(() => { void load(); }, [load]);

  const changeCloseFlow = useCallback((flow: CashCloseUiFlow) => {
    setCloseFlow(flow);
    if (!terminalCode) return;
    try {
      if (flow.phase === "READY") clearCashCloseRecovery(localStorage, recoveryScope);
      else saveCashCloseRecovery(localStorage, recoveryScope, flow);
    } catch { setRecoveryBlocked(true); }
  }, [terminalCode, recoveryScope]);

  const drafts = { entry: entryDraft, withdrawal: withdrawalDraft, betweenEntry: betweenEntryDraft, betweenWithdrawal: betweenWithdrawalDraft };
  const setters = { entry: setEntryDraft, withdrawal: setWithdrawalDraft, betweenEntry: setBetweenEntryDraft, betweenWithdrawal: setBetweenWithdrawalDraft };
  const activeKey: DraftKey = session ? selectedOperation === "entry" ? "entry" : "withdrawal" : betweenOperation === "entry" ? "betweenEntry" : "betweenWithdrawal";
  const activeDraft = drafts[activeKey];
  const setActiveDraft = setters[activeKey];
  const isEntry = activeKey === "entry" || activeKey === "betweenEntry";
  const breakdownRequired = isEntry ? Boolean(readiness?.requireEntryBreakdown) : Boolean(readiness?.requireWithdrawalBreakdown);

  async function outputMovementReceipt(receipt: PendingReceipt, accessToken: string) {
    let outcome: TicketPrintOutcome;
    try {
      const print = receipt.entry ? printCashEntryReceipt : printCashWithdrawalReceipt;
      outcome = await print(receipt.id, accessToken, { storeName, terminalCode: terminalCode ?? "" }, locale, undefined, request);
    } catch (failure) {
      outcome = { status: "FAILED", technicalMessage: failure instanceof Error ? failure.message : String(failure) };
    }
    setPendingReceipts(previous => {
      const remaining = previous.filter(pending => pending.id !== receipt.id);
      return outcome.status === "PRINTED" ? remaining : [...remaining, { ...receipt, technicalMessage: outcome.technicalMessage }];
    });
  }

  async function retryMovementReceipt(receipt: PendingReceipt) {
    if (!token || busy || closeBusy || movementBusyRef.current) return;
    movementBusyRef.current = true;
    setBusy(true);
    try { await outputMovementReceipt(receipt, token); }
    finally { movementBusyRef.current = false; setBusy(false); }
  }

  async function submitMovement() {
    if (!token || !terminalId || busy || closeBusy || movementBusyRef.current) return;
    const amount = positiveAmount(activeDraft.amount);
    if (amount === null) { setError(t.invalidAmount); return; }
    if (!activeDraft.comment.trim() && session) { setError(t.reasonRequired); return; }
    if (breakdownRequired && !activeDraft.denominations.length) { setError(t.breakdownRequired); return; }
    if (session && !saleOperationAuthorizationComplete(movementAuthorization, activeDraft.username, activeDraft.password)) { setError(t.authorizationRequired); return; }
    movementBusyRef.current = true;
    setBusy(true); setError(null); setNotice(null);
    try {
      const movement = await request<{ id: string }>(session ? isEntry ? "/cash/movements/entry" : "/cash/movements/withdrawal" : "/cash/movements/between-sessions", {
        token, method: "POST", body: { terminalId, amount, comment: activeDraft.comment.trim(), denominations: activeDraft.denominations,
          ...(!session ? { withdrawal: !isEntry } : saleOperationCredentials(movementAuthorization, activeDraft.username, activeDraft.password)) },
      });
      setActiveDraft(blankDraft()); setNotice(t.success);
      await outputMovementReceipt({ id: movement.id, entry: isEntry }, token);
      await load();
    } catch (failure) { setActiveDraft(previous => ({ ...previous, password: "" })); setError(operationError(failure, t.error)); }
    finally { movementBusyRef.current = false; setBusy(false); }
  }

  async function openAutomatically() {
    if (!token || !terminalId || busy || closeBusy) return;
    setBusy(true); setError(null); setNotice(null);
    try { await prepareCashSessionForSales(terminalId, token, request); setNotice(t.success); await load(); }
    catch (failure) { setError(operationError(failure, t.error)); }
    finally { setBusy(false); }
  }

  const navLocked = busy || closeBusy || closeFlow.phase !== "READY" || recoveryBlocked;

  function movementForm(between: boolean) {
    const operation: Movement = between ? betweenOperation : selectedOperation === "entry" ? "entry" : "withdrawal";
    const label = operation === "entry" ? between ? t.betweenEntry : t.entryNav : between ? t.betweenWithdrawal : t.withdrawalNav;
    const target: DraftKey = between ? operation === "entry" ? "betweenEntry" : "betweenWithdrawal" : operation;
    const draft = drafts[target], setDraft = setters[target];
    const currentRequired = operation === "entry" ? Boolean(readiness?.requireEntryBreakdown) : Boolean(readiness?.requireWithdrawalBreakdown);
    return <div className="cash-movement-form"><h4>{label}</h4>
      <div className="cash-operation-fields"><label><span>{t.amount}</span><input inputMode="decimal" value={draft.amount} disabled={busy || closeBusy}
        onChange={event => setDraft(previous => ({ ...previous, amount: event.target.value, denominations: [] }))} /></label>
        <label><span>{t.comment}</span><input value={draft.comment} disabled={busy || closeBusy}
          onChange={event => setDraft(previous => ({ ...previous, comment: event.target.value }))} /></label></div>
      <button type="button" className="cash-denomination-trigger" disabled={busy || closeBusy} onClick={() => setCountTarget(target)}>{t.count}{currentRequired ? " *" : ""}</button>
      {!between && <SaleOperationAuthorizationFields locale={locale} currentUsername={currentUsername} authorization={movementAuthorization}
        username={draft.username} password={draft.password} disabled={busy || closeBusy}
        onUsernameChange={username => setDraft(previous => ({ ...previous, username }))}
        onPasswordChange={password => setDraft(previous => ({ ...previous, password }))} />}
      {between && <div className="cash-between-operator"><small>{t.currentOperator}</small><strong>{currentUsername || "—"}</strong></div>}
      <div className="cash-operation-submit"><button className="primary-button" type="button" disabled={busy || closeBusy || positiveAmount(draft.amount) === null}
        onClick={() => void submitMovement()}>{between ? operation === "entry" ? t.registerBetweenEntry : t.registerBetweenWithdrawal
          : operation === "entry" ? t.registerEntry : t.registerWithdrawal}</button></div>
    </div>;
  }

  if (!token || !terminalId) return <section className="settings-card cash-operations-card"><h3>{t.title}</h3><p>{t.description}</p><div className="settings-empty-state">{t.missingContext}</div></section>;

  const refreshButton = <button className="secondary-button" type="button" onClick={() => void load()} disabled={loading || busy || closeBusy}>{t.refresh}</button>;
  return <section className="settings-card cash-operations-card erp-classic-tables">
    <div className="settings-card-heading cash-operations-heading"><div><h3>{t.title}</h3><p>{t.description}</p></div>
      {refreshContainer === undefined ? refreshButton : refreshContainer && createPortal(refreshButton, refreshContainer)}</div>
    {loading && !statusKnown && <div className="settings-empty-state">{t.loading}</div>}
    {error && <div className="settings-inline-message error" role="alert">{error}</div>}
    {notice && <div className="settings-inline-message success" role="status">{notice}</div>}
    {pendingReceipts.map(receipt => <div key={receipt.id} className="settings-inline-message warning cash-movement-print-warning" role="alert">
      <div><span>{printLabels(receipt.entry ? "sale.cashMovement.entryPrintFailed" : "sale.cashWithdrawal.printFailed")}</span>
        <small>{printLabels("sale.cashWithdrawal.registeredPrintPending")}</small>
        {receipt.technicalMessage && <small>{receipt.technicalMessage}</small>}</div>
      <button className="secondary-button" type="button" disabled={busy || closeBusy}
        onClick={() => void retryMovementReceipt(receipt)}>{printLabels("sale.cashWithdrawal.retryPrint")}</button>
    </div>)}
    {recoveryBlocked && <div className="settings-inline-message error" role="alert">{t.recoveryBlocked}</div>}
    {!session && statusKnown && !recoveryBlocked && <div className="cash-operation-panel cash-no-session">
      <strong>{t.noSession}</strong>
      {readiness?.cashSessionRequired === false ? <div className="cash-automatic-opening"><h4>{automaticCopy[locale].title}</h4>
        <p>{automaticCopy[locale].explanation}</p><button type="button" className="primary-button" disabled={busy || closeBusy}
          onClick={() => void openAutomatically()}>{automaticCopy[locale].action}</button></div>
        : readiness ? <SaleCashSessionDialog key={openFormKey} locale={locale} interfaceMode={interfaceMode} currentUsername={currentUsername} mode="OPEN" openContext="HOME"
          embedded terminalId={terminalId} token={token} request={request} denominations={readiness.entryDenominations}
          onBusyChange={setCloseBusy} onExitSales={() => setOpenFormKey(key => key + 1)} onOpened={() => { setNotice(t.success); void load(); }} />
        : <div className="settings-empty-state">{t.loading}</div>}
      {readiness && <div className="cash-between-session"><h4>{t.betweenTitle}</h4><p>{t.betweenExplanation}</p>
        <nav className="cash-operation-nav" aria-label={t.betweenTitle}>{(["entry", "withdrawal"] as const).map(operation => <button key={operation}
          type="button" aria-pressed={betweenOperation === operation} className={betweenOperation === operation ? "cash-selected-operation" : ""}
          disabled={busy || closeBusy} onClick={() => setBetweenOperation(operation)}>{operation === "entry" ? t.betweenEntry : t.betweenWithdrawal}</button>)}</nav>
        {movementForm(true)}</div>}
    </div>}
    {session && <>
      <div className="cash-session-summary">
        <div><span>{t.sessionOpen}</span><strong>{session.status}</strong></div>
        <div><span>{t.openedAt}</span><strong>{session.openedAt ? new Date(session.openedAt).toLocaleString(dateLocale) : "—"}</strong></div>
        <div><span>{t.openingFund}</span><strong>{session.openingFund == null ? "—" : money.format(session.openingFund)}</strong></div>
        <div><span>{t.expectedCash}</span><strong>{session.expectedCash == null ? "—" : money.format(session.expectedCash)}</strong></div>
        <div><span>{t.availableCash}</span><strong>{session.availableCash == null ? "—" : money.format(session.availableCash)}</strong></div>
      </div>
      <div className="cash-operation-workspace"><nav className="cash-operation-nav" aria-label={t.title}>{(["close", "entry", "withdrawal"] as const).map(operation => <button
        key={operation} type="button" className={selectedOperation === operation ? "cash-selected-operation" : ""}
        aria-pressed={selectedOperation === operation} disabled={navLocked} onClick={() => setSelectedOperation(operation)}>
        {operation === "close" ? t.closeNav : operation === "entry" ? t.entryNav : t.withdrawalNav}</button>)}</nav>
        <div className="cash-operation-panel cash-selected-operation-panel"><div hidden={selectedOperation !== "close"} inert={selectedOperation !== "close"}>
          {flowSessionId === session.id && !recoveryBlocked && <SaleCashSessionDialog key={session.id} locale={locale} interfaceMode={interfaceMode}
            currentUsername={currentUsername} mode="CLOSE" embedded terminalId={terminalId} token={token} request={request}
            denominations={readiness?.closingDenominations}
            withdrawalDenominations={readiness?.withdrawalDenominations}
            requireClosingBreakdown={Boolean(readiness?.requireClosingBreakdown)}
            requireWithdrawalBreakdown={Boolean(readiness?.requireWithdrawalBreakdown)}
            authorization={closeAuthorization} closeFlow={closeFlow} onCloseFlowChange={changeCloseFlow}
            onBusyChange={setCloseBusy} onClosed={() => {
              if (terminalCode) { try { clearCashCloseRecovery(localStorage, recoveryScope); } catch { /* Storage may be unavailable. */ } }
              flowSessionRef.current = null; setCloseBusy(false); setSession(null); setFlowSessionId(null); setCloseFlow(createCashCloseUiFlow()); setNotice(t.closed); void load();
            }} />}</div>
          {selectedOperation !== "close" && movementForm(false)}
        </div>
      </div>
    </>}
    {countTarget && <CashDenominationDialog locale={locale} interfaceMode={interfaceMode} title={t.count}
      denominations={countTarget === "entry" || countTarget === "betweenEntry" ? readiness?.entryDenominations : readiness?.withdrawalDenominations}
      value={drafts[countTarget].denominations} onCancel={() => setCountTarget(null)} onAccept={(rows, total) => {
        setters[countTarget](previous => ({ ...previous, amount: total.toFixed(2), denominations: rows.filter(row => row.quantity > 0) }));
        setCountTarget(null);
      }} />}
  </section>;
}

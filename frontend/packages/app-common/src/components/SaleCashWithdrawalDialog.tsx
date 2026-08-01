import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  printCashWithdrawalReceipt,
  registerCashEntry,
  registerCashWithdrawal,
  type CashWithdrawalDenomination,
  type CashWithdrawalMovement,
} from "../sale/cashWithdrawal";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import type { LocaleCode } from "../types";
import type { TerminalContext } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type Props = {
  locale: LocaleCode;
  terminalId: string;
  terminalContext: Pick<TerminalContext, "storeName" | "terminalCode">;
  token: string;
  authorization?: SaleOperationAuthorization;
  currentUserCanAuthorize?: boolean;
  requireEntryDenominationBreakdown?: boolean;
  entryDenominations?: number[];
  requireDenominationBreakdown: boolean;
  denominations: number[];
  request?: typeof apiRequest;
  printReceipt?: typeof printCashWithdrawalReceipt;
  onCancel: () => void;
  onCompleted: (movement: CashWithdrawalMovement) => void;
};

function operationError(error: unknown, fallback: string) {
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

function parsedPositiveAmount(value: string) {
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) && parsed > 0
    ? Math.round(parsed * 100) / 100
    : null;
}

function denominationKey(value: number) {
  return value.toFixed(2);
}

export function SaleCashWithdrawalDialog({
  locale,
  terminalId,
  terminalContext,
  token,
  authorization,
  currentUserCanAuthorize,
  requireEntryDenominationBreakdown = false,
  entryDenominations = [],
  requireDenominationBreakdown,
  denominations,
  request = apiRequest,
  printReceipt = printCashWithdrawalReceipt,
  onCancel,
  onCompleted,
}: Props) {
  const t = createTranslator(locale);
  const effectiveAuthorization = authorization ?? (
    currentUserCanAuthorize
      ? { mode: "CURRENT_PASSWORD", requireUsername: false, requirePassword: true }
      : { mode: "DELEGATED", requireUsername: true, requirePassword: true }
  ) satisfies SaleOperationAuthorization;
  const dialogRef = useRef<HTMLElement>(null);
  const amountInputRef = useRef<HTMLInputElement>(null);
  const [movementType, setMovementType] = useState<"ENTRY" | "WITHDRAWAL">("WITHDRAWAL");
  const [amountInput, setAmountInput] = useState("");
  const [reason, setReason] = useState("");
  const [authorizerUsername, setAuthorizerUsername] = useState("");
  const [authorizerPassword, setAuthorizerPassword] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [registeredMovement, setRegisteredMovement] = useState<CashWithdrawalMovement | null>(null);

  const currentBreakdownRequired = movementType === "ENTRY"
    ? requireEntryDenominationBreakdown
    : requireDenominationBreakdown;
  const currentDenominations = movementType === "ENTRY"
    ? entryDenominations
    : denominations;
  const normalizedDenominations = useMemo(
    () => [...currentDenominations]
      .filter((value) => Number.isFinite(value) && value > 0)
      .sort((left, right) => right - left),
    [currentDenominations],
  );
  const denominationRows = useMemo<CashWithdrawalDenomination[]>(
    () => normalizedDenominations
      .map((denomination) => ({
        denomination,
        quantity: Math.max(0, Math.trunc(Number(quantities[denominationKey(denomination)] ?? 0))),
      }))
      .filter((row) => row.quantity > 0),
    [normalizedDenominations, quantities],
  );
  const denominationTotal = useMemo(
    () => Math.round(denominationRows.reduce(
      (total, row) => total + row.denomination * row.quantity,
      0,
    ) * 100) / 100,
    [denominationRows],
  );
  const effectiveAmount = currentBreakdownRequired
    ? denominationTotal
    : parsedPositiveAmount(amountInput);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    amountInputRef.current?.focus();
    return deactivate;
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setError("");
    if (effectiveAmount == null || effectiveAmount <= 0) {
      setError(t("sale.cashWithdrawal.invalidAmount"));
      return;
    }
    if (!reason.trim()) {
      setError(t("sale.cashMovement.reasonRequired"));
      return;
    }
    if (!saleOperationAuthorizationComplete(
      effectiveAuthorization,
      authorizerUsername,
      authorizerPassword,
    )) {
      setError(t("sale.cashMovement.authorizationRequired"));
      return;
    }
    setBusy(true);
    try {
      const register = movementType === "ENTRY"
        ? registerCashEntry
        : registerCashWithdrawal;
      const movement = await register({
        terminalId,
        amount: effectiveAmount,
        comment: reason,
        denominations: currentBreakdownRequired ? denominationRows : [],
        ...saleOperationCredentials(
          effectiveAuthorization,
          authorizerUsername,
          authorizerPassword,
        ),
      }, token, request);
      setRegisteredMovement(movement);
      if (movementType === "ENTRY" || await outputReceipt(movement)) {
        onCompleted(movement);
      }
    } catch (failure) {
      setAuthorizerPassword("");
      setError(operationError(
        failure,
        t(movementType === "ENTRY"
          ? "sale.cashMovement.entryError"
          : "sale.cashWithdrawal.error"),
      ));
    } finally {
      setBusy(false);
    }
  }

  async function outputReceipt(movement: CashWithdrawalMovement) {
    try {
      const printOutcome = await printReceipt(
        movement.id,
        token,
        terminalContext,
        locale,
        undefined,
        request,
      );
      if (printOutcome.status === "PRINTED") return true;
    } catch {
      // The movement is already durable. Only printing may be retried.
    }
    setError(t("sale.cashWithdrawal.printFailed"));
    return false;
  }

  async function retryPrint() {
    if (!registeredMovement || busy) return;
    setBusy(true);
    setError("");
    try {
      if (await outputReceipt(registeredMovement)) {
        onCompleted(registeredMovement);
      }
    } finally {
      setBusy(false);
    }
  }

  function finishDialog() {
    if (registeredMovement) onCompleted(registeredMovement);
    else onCancel();
  }

  const reasonOptions = [
    ...(movementType === "ENTRY"
      ? [
          t("sale.cashMovement.reasonChangeFund"),
          t("sale.cashMovement.reasonCorrection"),
          t("sale.cashMovement.reasonOther"),
        ]
      : [
          t("sale.cashWithdrawal.reasonBankDeposit"),
          t("sale.cashWithdrawal.reasonSupplier"),
          t("sale.cashWithdrawal.reasonCashExpense"),
          t("sale.cashWithdrawal.reasonSecurity"),
        ]),
  ];

  return (
    <div className="sale-cash-session-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-cash-session-dialog sale-cash-withdrawal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-cash-withdrawal-title"
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) {
            event.preventDefault();
            finishDialog();
          }
        }}
      >
        <header>
          <h2 id="sale-cash-withdrawal-title">{t("sale.cashMovement.title")}</h2>
        </header>
        <form onSubmit={(event) => void submit(event)}>
          <p>{t("sale.cashMovement.description")}</p>

          {registeredMovement ? (
            <div className="sale-cash-withdrawal-registered" role="status">
              <strong>{t(movementType === "ENTRY"
                ? "sale.cashMovement.entryRegistered"
                : "sale.cashWithdrawal.registered")}</strong>
              <span>{t("sale.cashWithdrawal.registeredPrintPending")}</span>
            </div>
          ) : (
            <>
          <fieldset className="sale-cash-movement-type">
            <legend>{t("sale.cashMovement.type")}</legend>
            <label>
              <input
                type="radio"
                name="sale-cash-movement-type"
                checked={movementType === "WITHDRAWAL"}
                onChange={() => {
                  setMovementType("WITHDRAWAL");
                  setQuantities({});
                  setError("");
                }}
              />
              <span>{t("sale.cashMovement.withdrawal")}</span>
            </label>
            <label>
              <input
                type="radio"
                name="sale-cash-movement-type"
                checked={movementType === "ENTRY"}
                onChange={() => {
                  setMovementType("ENTRY");
                  setQuantities({});
                  setError("");
                }}
              />
              <span>{t("sale.cashMovement.entry")}</span>
            </label>
          </fieldset>
          <div className="sale-cash-withdrawal-primary">
            <label>
              <span>{t("sale.cashWithdrawal.amount")}</span>
              <input
                ref={amountInputRef}
                type="text"
                inputMode="decimal"
                value={currentBreakdownRequired
                  ? denominationTotal.toFixed(2).replace(".", ",")
                  : amountInput}
                readOnly={currentBreakdownRequired}
                onChange={(event) => setAmountInput(event.currentTarget.value)}
              />
            </label>
            <label>
              <span>{t("sale.cashWithdrawal.reason")}</span>
              <input
                list="sale-cash-withdrawal-reasons"
                maxLength={500}
                value={reason}
                placeholder={t("sale.cashWithdrawal.reasonPlaceholder")}
                onChange={(event) => setReason(event.currentTarget.value)}
              />
              <datalist id="sale-cash-withdrawal-reasons">
                {reasonOptions.map((option) => <option value={option} key={option} />)}
              </datalist>
            </label>
          </div>

          {currentBreakdownRequired && (
            <fieldset className="sale-cash-withdrawal-breakdown">
              <legend>{t("sale.cashWithdrawal.breakdown")}</legend>
              <p>{t(movementType === "ENTRY"
                ? "sale.cashMovement.entryBreakdownHint"
                : "sale.cashWithdrawal.breakdownHint")}</p>
              <div className="sale-cash-withdrawal-denominations">
                {normalizedDenominations.map((denomination, index) => {
                  const key = denominationKey(denomination);
                  return (
                    <label key={key}>
                      <span>{denomination.toLocaleString(locale, {
                        style: "currency",
                        currency: "EUR",
                      })}</span>
                      <input
                        ref={index === 0 ? amountInputRef : undefined}
                        type="number"
                        inputMode="numeric"
                        min="0"
                        step="1"
                        aria-label={`${t("sale.cashWithdrawal.quantity")} ${key}`}
                        value={quantities[key] ?? ""}
                        onChange={(event) => {
                          const value = event.currentTarget.value.replace(/\D/g, "");
                          setQuantities((current) => ({
                            ...current,
                            [key]: value,
                          }));
                        }}
                      />
                    </label>
                  );
                })}
              </div>
            </fieldset>
          )}

          <div className="sale-cash-withdrawal-authorization">
            <SaleOperationAuthorizationFields
              locale={locale}
              authorization={effectiveAuthorization}
              username={authorizerUsername}
              password={authorizerPassword}
              disabled={busy}
              onUsernameChange={setAuthorizerUsername}
              onPasswordChange={setAuthorizerPassword}
            />
          </div>
            </>
          )}

          {error && <p className="sale-cash-session-error" role="alert">{error}</p>}
          <footer>
            {registeredMovement ? (
              <>
                <button type="button" className="secondary" disabled={busy} onClick={finishDialog}>
                  {t("sale.cashWithdrawal.close")}
                </button>
                <button type="button" disabled={busy} onClick={() => void retryPrint()}>
                  {busy
                    ? t("sale.cashWithdrawal.printing")
                    : t("sale.cashWithdrawal.retryPrint")}
                </button>
              </>
            ) : (
              <>
                <button type="button" className="secondary" disabled={busy} onClick={onCancel}>
                  {t("sale.cashWithdrawal.cancel")}
                </button>
                <button type="submit" disabled={busy}>
                  {busy
                    ? t("sale.cashWithdrawal.submitting")
                    : t(movementType === "ENTRY"
                      ? "sale.cashMovement.submitEntry"
                      : "sale.cashWithdrawal.submit")}
                </button>
              </>
            )}
          </footer>
        </form>
      </section>
    </div>
  );
}

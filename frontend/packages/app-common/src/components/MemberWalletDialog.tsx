import { useEffect, useId, useMemo, useRef, useState } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./MemberWalletDialog.css";

export type MemberWalletLocale = "es" | "en" | "zh";

export type MemberWalletLot = {
  id: string;
  type: "LOYALTY" | "RETURN_CREDIT";
  documentId?: string;
  sourceMovementType: string;
  originalAmount: number | string;
  availableAmount: number | string;
  obtainedAt: string;
  expiresAt?: string;
};

export type MemberWalletDialogProps = {
  locale: MemberWalletLocale;
  lots: MemberWalletLot[];
  maxAmountCents: number;
  busy?: boolean;
  error?: string;
  onCancel: () => void;
  onConfirm: (amountCents: number) => void;
};

type Copy = {
  title: string;
  subtitle: string;
  close: string;
  lotsTitle: string;
  noLots: string;
  type: string;
  originDocument: string;
  obtainedAt: string;
  available: string;
  expiresAt: string;
  loyalty: string;
  returnCredit: string;
  document: string;
  noDocument: string;
  noExpiration: string;
  amount: string;
  maximum: string;
  amountHint: string;
  invalidAmount: string;
  positiveAmount: string;
  exceedsMaximum: string;
  cancel: string;
  confirm: string;
  confirming: string;
  keyboardHint: string;
  sources: Record<string, string>;
};

const COPY: Record<MemberWalletLocale, Copy> = {
  es: {
    title: "Consumir saldo de socio",
    subtitle: "El consumo es automático: primero el saldo de socio por caducidad y antigüedad, y después el saldo por devolución.",
    close: "Cerrar",
    lotsTitle: "Saldos disponibles por origen",
    noLots: "No hay saldos disponibles.",
    type: "Tipo",
    originDocument: "Origen / documento",
    obtainedAt: "Fecha de obtención",
    available: "Disponible",
    expiresAt: "Caducidad",
    loyalty: "Saldo socio",
    returnCredit: "Saldo por devolución",
    document: "Documento",
    noDocument: "Sin documento asociado",
    noExpiration: "No caduca",
    amount: "Cantidad a consumir",
    maximum: "Máximo disponible",
    amountHint: "Introduce un importe mayor que 0 y no superior al máximo disponible.",
    invalidAmount: "Introduce un importe válido con un máximo de dos decimales.",
    positiveAmount: "La cantidad debe ser mayor que 0.",
    exceedsMaximum: "La cantidad no puede superar el saldo máximo disponible.",
    cancel: "Cancelar",
    confirm: "Aplicar saldo",
    confirming: "Aplicando...",
    keyboardHint: "Enter para aplicar · Esc para cancelar",
    sources: {
      ACUMULACION_SALDO: "Acumulación por compra",
      RESTAURACION_SALDO: "Restauración de saldo",
      DEVOLUCION_RESTAURACION_SALDO: "Restauración por devolución",
      ANULACION_USO_SALDO: "Restauración por anulación",
      AJUSTE_SALDO: "Ajuste manual",
      AJUSTE_MANUAL_SALDO: "Ajuste manual",
      PAGO_DEUDA_SALDO: "Saldo generado al cobrar deuda",
      ABONO_CREDITO_DEVOLUCION: "Abono por devolución",
    },
  },
  en: {
    title: "Use member balance",
    subtitle: "Balance is used automatically: loyalty balance by expiry and age first, followed by return credit.",
    close: "Close",
    lotsTitle: "Available balances by origin",
    noLots: "There are no available balances.",
    type: "Type",
    originDocument: "Origin / document",
    obtainedAt: "Obtained on",
    available: "Available",
    expiresAt: "Expiry",
    loyalty: "Loyalty balance",
    returnCredit: "Return credit",
    document: "Document",
    noDocument: "No associated document",
    noExpiration: "Does not expire",
    amount: "Amount to use",
    maximum: "Maximum available",
    amountHint: "Enter an amount greater than 0 and no higher than the available maximum.",
    invalidAmount: "Enter a valid amount with no more than two decimal places.",
    positiveAmount: "The amount must be greater than 0.",
    exceedsMaximum: "The amount cannot exceed the maximum available balance.",
    cancel: "Cancel",
    confirm: "Apply balance",
    confirming: "Applying...",
    keyboardHint: "Enter to apply · Esc to cancel",
    sources: {
      ACUMULACION_SALDO: "Purchase accrual",
      RESTAURACION_SALDO: "Balance restoration",
      DEVOLUCION_RESTAURACION_SALDO: "Restored by return",
      ANULACION_USO_SALDO: "Restored by cancellation",
      AJUSTE_SALDO: "Manual adjustment",
      AJUSTE_MANUAL_SALDO: "Manual adjustment",
      PAGO_DEUDA_SALDO: "Balance from debt collection",
      ABONO_CREDITO_DEVOLUCION: "Return credit",
    },
  },
  zh: {
    title: "使用会员余额",
    subtitle: "余额将自动抵扣：先按到期日和取得时间使用会员奖励余额，再使用退货余额。",
    close: "关闭",
    lotsTitle: "按来源显示可用余额",
    noLots: "没有可用余额。",
    type: "类型",
    originDocument: "来源 / 单据",
    obtainedAt: "取得日期",
    available: "可用金额",
    expiresAt: "到期时间",
    loyalty: "会员奖励余额",
    returnCredit: "退货余额",
    document: "单据",
    noDocument: "无关联单据",
    noExpiration: "永不过期",
    amount: "使用金额",
    maximum: "最大可用金额",
    amountHint: "请输入大于 0 且不超过最大可用余额的金额。",
    invalidAmount: "请输入有效金额，最多保留两位小数。",
    positiveAmount: "金额必须大于 0。",
    exceedsMaximum: "金额不能超过最大可用余额。",
    cancel: "取消",
    confirm: "使用余额",
    confirming: "正在处理...",
    keyboardHint: "Enter 确认 · Esc 取消",
    sources: {
      ACUMULACION_SALDO: "购物累计",
      RESTAURACION_SALDO: "余额恢复",
      DEVOLUCION_RESTAURACION_SALDO: "退货恢复余额",
      ANULACION_USO_SALDO: "取消后恢复余额",
      AJUSTE_SALDO: "手动调整",
      AJUSTE_MANUAL_SALDO: "手动调整",
      PAGO_DEUDA_SALDO: "收取欠款后生成余额",
      ABONO_CREDITO_DEVOLUCION: "退货入账",
    },
  },
};

const LOCALE_TAG: Record<MemberWalletLocale, string> = {
  es: "es-ES",
  en: "en-GB",
  zh: "zh-CN",
};

export function MemberWalletDialog({
  locale,
  lots,
  maxAmountCents,
  busy = false,
  error,
  onCancel,
  onConfirm,
}: MemberWalletDialogProps) {
  const copy = COPY[locale];
  const safeMaximumCents = useMemo(
    () => Number.isFinite(maxAmountCents) ? Math.max(0, Math.trunc(maxAmountCents)) : 0,
    [maxAmountCents],
  );
  const [amount, setAmount] = useState(() => editableMoney(safeMaximumCents, locale));
  const [localError, setLocalError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const amountRef = useRef<HTMLInputElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const lotsTitleId = useId();
  const maximumId = useId();
  const amountHintId = useId();
  const errorId = useId();
  const displayedError = localError ?? error ?? null;

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    amountRef.current?.focus();
    amountRef.current?.select();
  }, []);

  const attemptConfirm = () => {
    if (busy) return;

    const amountCents = parseAmountCents(amount);
    if (amountCents === null) {
      setLocalError(copy.invalidAmount);
      amountRef.current?.focus();
      amountRef.current?.select();
      return;
    }
    if (amountCents <= 0) {
      setLocalError(copy.positiveAmount);
      amountRef.current?.focus();
      amountRef.current?.select();
      return;
    }
    if (amountCents > safeMaximumCents) {
      setLocalError(copy.exceedsMaximum);
      amountRef.current?.focus();
      amountRef.current?.select();
      return;
    }

    setLocalError(null);
    onConfirm(amountCents);
  };

  return (
    <div className="sale-action-overlay member-wallet-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="member-wallet-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        aria-busy={busy}
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) {
            event.preventDefault();
            event.stopPropagation();
            onCancel();
          }
        }}
      >
        <header className="member-wallet-header">
          <div>
            <h2 id={titleId}>{copy.title}</h2>
            <p id={descriptionId}>{copy.subtitle}</p>
          </div>
          <button type="button" aria-label={copy.close} disabled={busy} onClick={onCancel}>×</button>
        </header>

        <form
          className="member-wallet-form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            attemptConfirm();
          }}
        >
          <div className="member-wallet-section-heading">
            <h3 id={lotsTitleId}>{copy.lotsTitle}</h3>
            <strong>{copy.maximum}: {formatMoneyFromCents(safeMaximumCents, locale)}</strong>
          </div>

          <div className="member-wallet-table-region" role="region" aria-labelledby={lotsTitleId} tabIndex={0}>
            <table className="member-wallet-table">
              <thead>
                <tr>
                  <th scope="col">{copy.type}</th>
                  <th scope="col">{copy.originDocument}</th>
                  <th scope="col">{copy.obtainedAt}</th>
                  <th scope="col" className="member-wallet-money-column">{copy.available}</th>
                  <th scope="col">{copy.expiresAt}</th>
                </tr>
              </thead>
              <tbody>
                {lots.length === 0 && (
                  <tr>
                    <td className="member-wallet-empty" colSpan={5}>{copy.noLots}</td>
                  </tr>
                )}
                {lots.map((lot) => (
                  <tr key={lot.id}>
                    <td>
                      <span className={`member-wallet-type member-wallet-type-${lot.type.toLowerCase()}`}>
                        {lot.type === "LOYALTY" ? copy.loyalty : copy.returnCredit}
                      </span>
                    </td>
                    <td className="member-wallet-origin">
                      <strong>{sourceLabel(lot.sourceMovementType, copy)}</strong>
                      <span title={lot.documentId}>
                        {lot.documentId ? `${copy.document}: ${lot.documentId}` : copy.noDocument}
                      </span>
                    </td>
                    <td>{formatDate(lot.obtainedAt, locale)}</td>
                    <td className="member-wallet-money-column">
                      <strong>{formatMoney(lot.availableAmount, locale)}</strong>
                    </td>
                    <td>{lot.expiresAt ? formatExpiration(lot.expiresAt, locale) : copy.noExpiration}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="member-wallet-amount-panel">
            <label htmlFor={`${amountHintId}-input`}>{copy.amount}</label>
            <div className="member-wallet-amount-control">
              <input
                ref={amountRef}
                id={`${amountHintId}-input`}
                type="text"
                inputMode="decimal"
                autoComplete="off"
                spellCheck={false}
                value={amount}
                disabled={busy}
                aria-invalid={displayedError ? true : undefined}
                aria-describedby={`${maximumId} ${amountHintId}${displayedError ? ` ${errorId}` : ""}`}
                onChange={(event) => {
                  setAmount(event.currentTarget.value);
                  setLocalError(null);
                }}
              />
              <span aria-hidden="true">€</span>
            </div>
            <strong id={maximumId}>{copy.maximum}: {formatMoneyFromCents(safeMaximumCents, locale)}</strong>
            <small id={amountHintId}>{copy.amountHint}</small>
          </div>

          <div className="member-wallet-message-area" aria-live="polite">
            {displayedError && <p id={errorId} className="member-wallet-error" role="alert">{displayedError}</p>}
          </div>

          <footer className="member-wallet-actions">
            <span>{copy.keyboardHint}</span>
            <button type="button" disabled={busy} onClick={onCancel}>{copy.cancel}</button>
            <button className="member-wallet-primary-action" type="submit" disabled={busy}>
              {busy ? copy.confirming : copy.confirm}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

function parseAmountCents(value: string): number | null {
  const match = value.trim().match(/^(\d+)(?:[.,](\d{0,2}))?$/);
  if (!match) return null;

  const euros = Number(match[1]);
  const cents = Number((match[2] ?? "").padEnd(2, "0"));
  const amountCents = euros * 100 + cents;
  return Number.isSafeInteger(amountCents) ? amountCents : null;
}

function editableMoney(cents: number, locale: MemberWalletLocale): string {
  const value = (cents / 100).toFixed(2);
  return locale === "es" ? value.replace(".", ",") : value;
}

function formatMoneyFromCents(cents: number, locale: MemberWalletLocale): string {
  return new Intl.NumberFormat(LOCALE_TAG[locale], {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(cents / 100);
}

function formatMoney(value: number | string, locale: MemberWalletLocale): string {
  const amount = typeof value === "number" ? value : Number(value.replace(",", "."));
  if (!Number.isFinite(amount)) return `${String(value)} €`;
  return new Intl.NumberFormat(LOCALE_TAG[locale], {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function formatDate(value: string, locale: MemberWalletLocale): string {
  const dateOnly = value.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (dateOnly) {
    const [, year, month, day] = dateOnly;
    return locale === "zh" ? `${year}/${month}/${day}` : `${day}/${month}/${year}`;
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(LOCALE_TAG[locale], {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function formatExpiration(value: string, locale: MemberWalletLocale): string {
  const exclusiveInstant = new Date(value);
  if (Number.isNaN(exclusiveInstant.getTime())) return formatDate(value, locale);
  return new Intl.DateTimeFormat(LOCALE_TAG[locale], {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(exclusiveInstant.getTime() - 1));
}

function sourceLabel(sourceMovementType: string, copy: Copy): string {
  return copy.sources[sourceMovementType]
    ?? sourceMovementType.toLocaleLowerCase().replaceAll("_", " ").replace(/^./, (letter) => letter.toLocaleUpperCase());
}

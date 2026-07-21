import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";

type Props = {
  locale?: LocaleCode;
  disabled: boolean;
  busy: boolean;
  cardEnabled: boolean;
  pendingEnabled?: boolean;
  voucherEnabled?: boolean;
  onCash: () => void;
  onCard: () => void;
  onPending: () => void;
  onVoucher?: () => void;
};

export function IndividualPaymentActions(props: Props) {
  const t = createTranslator(props.locale ?? "es");
  return <div className="sale-payment-actions individual-payment-actions">
    <button type="button" disabled={props.disabled || props.busy} onClick={props.onCash}>
      <span>{t("payment.individual.cash")}</span><kbd>{t("sale.main.pageDownKey")}</kbd>
    </button>
    <button type="button" disabled={props.disabled || props.busy || !props.cardEnabled} onClick={props.onCard}>
      <span>{t("payment.individual.card")}</span><kbd>F11</kbd>
    </button>
    <button type="button" disabled={props.disabled || props.busy || props.pendingEnabled === false} onClick={props.onPending}>
      <span>{t("payment.individual.pendingCustomer")}</span><kbd>F12</kbd>
    </button>
    {props.onVoucher && <button type="button" disabled={props.disabled || props.busy || props.voucherEnabled === false} onClick={props.onVoucher}>
      <span>{t("payment.individual.voucher")}</span>
    </button>}
  </div>;
}

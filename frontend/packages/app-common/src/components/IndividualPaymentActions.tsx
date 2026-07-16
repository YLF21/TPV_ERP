type Props = {
  disabled: boolean;
  busy: boolean;
  cardEnabled: boolean;
  onCash: () => void;
  onCard: () => void;
  onPending: () => void;
};

export function IndividualPaymentActions(props: Props) {
  return <div className="sale-payment-actions individual-payment-actions">
    <button type="button" disabled={props.disabled || props.busy} onClick={props.onCash}>
      <span>Efectivo</span><kbd>AvPág</kbd>
    </button>
    <button type="button" disabled={props.disabled || props.busy || !props.cardEnabled} onClick={props.onCard}>
      <span>Tarjeta</span><kbd>F11</kbd>
    </button>
    <button type="button" disabled={props.disabled || props.busy} onClick={props.onPending}>
      <span>Pendiente cliente</span><kbd>F12</kbd>
    </button>
  </div>;
}

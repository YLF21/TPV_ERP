import "./CashDenominationIcon.css";

export type CashDenominationIconProps = {
  denomination: number;
};

function denominationCents(denomination: number): number | null {
  const cents = denomination * 100;
  const rounded = Math.round(cents);
  return Number.isFinite(cents) && Number.isSafeInteger(rounded) && rounded > 0 && Math.abs(cents - rounded) < 1e-8
    ? rounded
    : null;
}

function valueLabel(cents: number | null, denomination: number): string {
  if (cents === 100 || cents === 200) return `${cents / 100}€`;
  if (cents !== null && cents < 100) return `${cents}c`;
  if (cents !== null) return String(cents / 100);
  return String(denomination);
}

/** Small, decorative denomination marker. The adjoining amount remains the accessible label. */
export function CashDenominationIcon({ denomination }: CashDenominationIconProps) {
  const cents = denominationCents(denomination);
  const isBanknote = cents !== null && cents >= 500;
  const isTwoEuro = cents === 200;
  const isOneEuro = cents === 100;
  const isCopperCent = cents !== null && cents <= 5;
  const label = valueLabel(cents, denomination);
  const valueClass = cents === null ? "unknown" : String(cents);

  return isBanknote ? (
    <svg
      className={`cash-value-icon cash-value-icon--banknote cash-value-icon--banknote--${valueClass}`}
      viewBox="0 0 36 22"
      aria-hidden="true"
      focusable="false"
    >
      <rect className="cash-value-icon__note" x="0.5" y="0.5" width="35" height="21" rx="1" />
      <path className="cash-value-icon__note-detail" d="M4 4h3M29 4h3M4 18h3M29 18h3" />
      <ellipse className="cash-value-icon__note-center" cx="18" cy="11" rx="5" ry="7" />
      <text className="cash-value-icon__label" x="18" y="13.7" textAnchor="middle">{label}</text>
    </svg>
  ) : (
    <svg
      className={`cash-value-icon cash-value-icon--coin cash-value-icon--coin--${valueClass}${isTwoEuro ? " cash-value-icon--two-euro" : ""}${isOneEuro ? " cash-value-icon--one-euro" : ""}${isCopperCent ? " cash-value-icon--copper" : ""}`}
      viewBox="0 0 22 22"
      aria-hidden="true"
      focusable="false"
    >
      <circle className="cash-value-icon__coin-rim" cx="11" cy="11" r="10" />
      <circle className="cash-value-icon__coin-center" cx="11" cy="11" r="7.35" />
      <circle className="cash-value-icon__coin-inner-rim" cx="11" cy="11" r="7.35" />
      <text className="cash-value-icon__label" x="11" y="13.3" textAnchor="middle">{label}</text>
    </svg>
  );
}

import { useEffect, useRef, useState, type FormEvent } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type SaleOpenPriceLabels = {
  title: string;
  product: string;
  price: string;
  placeholder: string;
  invalid: string;
  cancel: string;
  accept: string;
};

type SaleOpenPriceDialogProps = {
  labels: SaleOpenPriceLabels;
  productName: string;
  initialValue?: string;
  onCancel: () => void;
  onAccept: (price: number) => void;
};

export function parseSaleOpenPrice(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d+(?:\.\d{0,2})?$/.test(normalized)) return null;
  const price = Number(normalized);
  return Number.isFinite(price) && price > 0 ? price : null;
}

export function SaleOpenPriceDialog({
  labels,
  productName,
  initialValue = "",
  onCancel,
  onAccept,
}: SaleOpenPriceDialogProps) {
  const [value, setValue] = useState(initialValue);
  const [error, setError] = useState("");
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    inputRef.current?.focus();
    inputRef.current?.select();
    return deactivate;
  }, []);

  function submit(event: FormEvent) {
    event.preventDefault();
    const price = parseSaleOpenPrice(value);
    if (price == null) {
      setError(labels.invalid);
      return;
    }
    onAccept(price);
  }

  return (
    <div className="sale-action-overlay sale-open-price-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-open-price-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-open-price-title"
        onKeyDown={(event) => {
          if (event.key !== "Escape") return;
          event.preventDefault();
          event.stopPropagation();
          onCancel();
        }}
      >
        <header>
          <h2 id="sale-open-price-title">{labels.title}</h2>
        </header>
        <form onSubmit={submit}>
          <p><span>{labels.product}</span><strong>{productName}</strong></p>
          <label>
            <span>{labels.price}</span>
            <input
              ref={inputRef}
              inputMode="decimal"
              autoComplete="off"
              value={value}
              placeholder={labels.placeholder}
              aria-invalid={Boolean(error)}
              onChange={(event) => {
                setValue(event.target.value);
                setError("");
              }}
            />
          </label>
          {error && <p className="sale-action-error" role="alert">{error}</p>}
          <footer>
            <button type="button" onClick={onCancel}>{labels.cancel}</button>
            <button type="submit" className="primary">{labels.accept}</button>
          </footer>
        </form>
      </section>
    </div>
  );
}

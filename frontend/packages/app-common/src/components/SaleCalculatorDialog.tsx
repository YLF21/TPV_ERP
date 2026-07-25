import { useState } from "react";

type Props = {
  taxRegime: "IVA" | "IGIC";
  onClose: () => void;
};

function numeric(value: string) {
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : null;
}

function format(value: number | null) {
  return value == null
    ? "—"
    : value.toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function SaleCalculatorDialog({ taxRegime, onClose }: Props) {
  const [amount, setAmount] = useState("");
  const [taxPercent, setTaxPercent] = useState(taxRegime === "IGIC" ? "7" : "21");
  const [operation, setOperation] = useState<"ADD" | "REMOVE">("ADD");
  const base = numeric(amount);
  const tax = numeric(taxPercent);
  const result = base == null || tax == null || tax < 0 || tax > 100
    ? null
    : operation === "ADD"
      ? base * (1 + tax / 100)
      : base / (1 + tax / 100);

  return (
    <div className="sale-action-overlay" role="presentation">
      <section className="sale-action-dialog sale-calculator-dialog" role="dialog" aria-modal="true" aria-label="Calculadora">
        <header>
          <h2>Calculadora</h2>
          <button type="button" aria-label="Cerrar" onClick={onClose}>×</button>
        </header>
        <label>
          Importe
          <input autoFocus inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} />
        </label>
        <label>
          {taxRegime}
          <input inputMode="decimal" value={taxPercent} onChange={(event) => setTaxPercent(event.target.value)} />
        </label>
        <div className="sale-action-buttons">
          <button type="button" className={operation === "ADD" ? "primary" : ""} onClick={() => setOperation("ADD")}>
            Añadir {taxRegime}
          </button>
          <button type="button" className={operation === "REMOVE" ? "primary" : ""} onClick={() => setOperation("REMOVE")}>
            Quitar {taxRegime}
          </button>
        </div>
        <output aria-label="Resultado">{format(result)}</output>
        <div className="sale-action-buttons">
          <button type="button" onClick={onClose}>Cerrar</button>
        </div>
      </section>
    </div>
  );
}

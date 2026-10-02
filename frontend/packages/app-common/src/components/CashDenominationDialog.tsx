import { lazy, Suspense, useEffect, useId, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { LocaleCode } from "../types";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import { CashDenominationIcon } from "./CashDenominationIcon";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./ErpClassicTables.css";
import "./ErpClassicWindow.css";
import "./CashDenominationDialog.css";

const TouchNumericKeypad = lazy(() => import("./TouchNumericKeypad").then(module => ({ default: module.TouchNumericKeypad })));

export type CashDenominationCount = { denomination: number; quantity: number };

export type CashDenominationDialogProps = {
  locale: LocaleCode;
  title: string;
  interfaceMode?: SaleInterfaceMode;
  denominations?: number[];
  value?: CashDenominationCount[];
  onAccept: (rows: CashDenominationCount[], total: number) => void;
  onCancel: () => void;
};

const DEFAULT_DENOMINATIONS = [100, 50, 20, 10, 5, 2, 1, 0.5, 0.2, 0.1, 0.05, 0.02, 0.01];

const LABELS = {
  es: { notes: "Billetes", coins: "Monedas", value: "Valor", units: "Unidades", subtotal: "Subtotal", total: "Total contado", cancel: "Cancelar", accept: "Aceptar", previous: "Anterior", next: "Siguiente", keypad: "Teclado numérico del recuento", clear: "Borrar unidades", backspace: "Borrar último dígito", invalid: "Introduce unidades enteras y no negativas.", amountTooLarge: "El importe supera el límite de cálculo seguro." },
  en: { notes: "Banknotes", coins: "Coins", value: "Value", units: "Units", subtotal: "Subtotal", total: "Counted total", cancel: "Cancel", accept: "Accept", previous: "Previous", next: "Next", keypad: "Cash count numeric keypad", clear: "Clear units", backspace: "Delete last digit", invalid: "Enter whole, non-negative units.", amountTooLarge: "The amount exceeds the safe calculation limit." },
  zh: { notes: "纸币", coins: "硬币", value: "面值", units: "数量", subtotal: "小计", total: "清点总额", cancel: "取消", accept: "确认", previous: "上一个", next: "下一个", keypad: "现金清点数字键盘", clear: "清除数量", backspace: "删除最后一位", invalid: "请输入非负整数数量。", amountTooLarge: "金额超出安全计算范围。" },
} satisfies Record<LocaleCode, Record<string, string>>;

function denominationCents(denomination: number): number | null {
  const cents = denomination * 100;
  const rounded = Math.round(cents);
  return Number.isFinite(cents) && rounded > 0 && Number.isSafeInteger(rounded) && Math.abs(cents - rounded) < 1e-8 ? rounded : null;
}

function parseQuantity(raw: string): number | null {
  if (raw === "") return 0;
  if (!/^\d+$/.test(raw)) return null;
  const quantity = Number(raw);
  return Number.isSafeInteger(quantity) ? quantity : null;
}

function formatEuros(cents: number, locale: LocaleCode): string {
  return new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    style: "currency", currency: "EUR", minimumFractionDigits: 2, maximumFractionDigits: 2,
  }).format(cents / 100);
}

export function CashDenominationDialog({ locale, title, interfaceMode = "KEYBOARD", denominations = DEFAULT_DENOMINATIONS, value, onAccept, onCancel }: CashDenominationDialogProps) {
  const id = useId();
  const root = useRef<HTMLElement>(null);
  const quantityFields = useRef(new Map<number, HTMLInputElement>());
  const activeQuantity = useRef<HTMLInputElement | null>(null);
  const acceptButton = useRef<HTMLButtonElement>(null);
  const rows = useMemo(() => {
    const seen = new Set<number>();
    return denominations.flatMap(denomination => {
      const cents = denominationCents(denomination);
      if (cents === null || seen.has(cents)) return [];
      seen.add(cents);
      return [{ denomination, cents }];
    }).sort((a, b) => b.cents - a.cents);
  }, [denominations]);
  const [quantities, setQuantities] = useState<Record<number, string>>(() => Object.fromEntries(
    rows.map(({ cents }) => [cents, String(value?.find(item => denominationCents(item.denomination) === cents)?.quantity ?? "")]),
  ));
  const [activeCents, setActiveCents] = useState<number | null>(rows[0]?.cents ?? null);
  const touch = interfaceMode === "TOUCH";
  const labels = LABELS[locale];

  useEffect(() => {
    if (!root.current) return;
    const release = activateModalFocusTrap(root.current as unknown as ModalFocusRoot, document);
    quantityFields.current.get(rows[0]?.cents)?.focus();
    return release;
  }, []);

  const parsed = rows.map(row => ({ ...row, quantity: parseQuantity(quantities[row.cents] ?? "") }));
  const hasInvalidQuantity = parsed.some(row => row.quantity === null);
  let totalCents = 0;
  let tooLarge = false;
  for (const row of parsed) {
    if (row.quantity === null) continue;
    const subtotal = row.cents * row.quantity;
    if (!Number.isSafeInteger(subtotal) || !Number.isSafeInteger(totalCents + subtotal)) {
      tooLarge = true;
      break;
    }
    totalCents += subtotal;
  }
  const error = hasInvalidQuantity ? labels.invalid : tooLarge ? labels.amountTooLarge : null;

  function accept() {
    if (error) return;
    onAccept(parsed.map(row => ({ denomination: row.denomination, quantity: row.quantity! })), totalCents / 100);
  }

  function focusQuantity(cents: number) {
    const field = quantityFields.current.get(cents);
    field?.focus();
    field?.select();
  }

  function advance(cents: number | null = activeCents) {
    if (cents === null) { accept(); return; }
    const index = rows.findIndex(row => row.cents === cents);
    const next = rows[index + 1];
    if (next) { focusQuantity(next.cents); return; }
    if (error) {
      const invalid = parsed.find(row => row.quantity === null || !Number.isSafeInteger(row.cents * row.quantity));
      focusQuantity(invalid?.cents ?? cents);
      return;
    }
    acceptButton.current?.focus();
  }

  function previous() {
    const index = activeCents === null ? rows.length : rows.findIndex(row => row.cents === activeCents);
    const prior = rows[index - 1];
    if (prior) focusQuantity(prior.cents);
  }

  function table(group: "notes" | "coins") {
    const groupRows = parsed.filter(row => group === "notes" ? row.cents >= 500 : row.cents < 500);
    if (groupRows.length === 0) return null;
    const headingId = `${id}-${group}`;
    return <section className="cash-denomination-group" aria-labelledby={headingId} key={group}>
      <h3 id={headingId}>{labels[group]}</h3>
      <table>
        <thead><tr><th scope="col">{labels.value}</th><th scope="col">{labels.units}</th><th scope="col">{labels.subtotal}</th></tr></thead>
        <tbody>{groupRows.map(row => {
          const raw = quantities[row.cents] ?? "";
          const subtotal = row.quantity === null || !Number.isSafeInteger(row.cents * row.quantity) ? null : row.cents * row.quantity;
          return <tr key={row.cents} className={touch && activeCents === row.cents ? "cash-denomination-row--active" : undefined}>
            <td><CashDenominationIcon denomination={row.denomination} /><span>{formatEuros(row.cents, locale)}</span></td>
            <td><input ref={field => { if (field) quantityFields.current.set(row.cents, field); else quantityFields.current.delete(row.cents); }} type="number" inputMode="numeric" min="0" step="1" value={raw} data-cash-denomination={row.cents} data-touch-keyboard="off" aria-label={`${labels.units} ${formatEuros(row.cents, locale)}`} aria-invalid={row.quantity === null} onFocus={event => { activeQuantity.current = event.currentTarget; setActiveCents(row.cents); }} onChange={event => setQuantities(previous => ({ ...previous, [row.cents]: event.target.value }))} /></td>
            <td>{subtotal === null ? "—" : formatEuros(subtotal, locale)}</td>
          </tr>;
        })}</tbody>
      </table>
    </section>;
  }

  return createPortal(<div className="filter-overlay erp-classic-overlay cash-denomination-overlay" onKeyDown={event => {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onCancel(); }
    if (event.key === "Enter") {
      event.stopPropagation();
      const target = event.target as HTMLElement;
      const cents = target.getAttribute("data-cash-denomination");
      if (cents !== null || target === acceptButton.current) {
        event.preventDefault();
        if (!event.repeat) advance(cents === null ? null : Number(cents));
      }
    }
  }}>
    <section ref={root} className={`filter-dialog erp-classic-window erp-classic-tables cash-denomination-dialog${touch ? " cash-denomination-dialog--touch" : ""}`} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`}>
      <header><h2 id={`${id}-title`}>{title}</h2></header>
      <form onSubmit={event => { event.preventDefault(); accept(); }} noValidate>
        <div className="cash-denomination-workspace">
          <div className="cash-denomination-groups">{table("notes")}{table("coins")}</div>
          {touch && <section className="cash-denomination-touch" aria-label={labels.keypad}>
            <h3>{activeCents === null ? labels.accept : `${labels.units} ${formatEuros(activeCents, locale)}`}</h3>
            <div className="cash-denomination-keypad-navigation">
              <Suspense fallback={<div className="cash-denomination-keypad-loading" aria-busy="true" aria-label={labels.keypad} />}><TouchNumericKeypad value={activeCents === null ? "" : quantities[activeCents] ?? ""} disabled={activeCents === null} inputRef={activeQuantity} replaceOnFirstKey ariaLabel={labels.keypad} clearLabel={labels.clear} backspaceLabel={labels.backspace} onChange={raw => { if (activeCents !== null) setQuantities(previous => ({ ...previous, [activeCents]: raw })); }} /></Suspense>
              <div className="cash-denomination-navigation">
                <button type="button" onPointerDown={event => event.preventDefault()} onClick={previous} disabled={activeCents === rows[0]?.cents || rows.length === 0}><span aria-hidden="true">↑</span>{labels.previous}</button>
                <button type="button" onPointerDown={event => event.preventDefault()} onClick={() => advance()} disabled={activeCents === null}><span aria-hidden="true">↓</span>{labels.next}</button>
                <button type="button" onPointerDown={event => event.preventDefault()} onClick={() => advance()} disabled={activeCents === null && Boolean(error)}><span aria-hidden="true">↵</span>Enter</button>
              </div>
            </div>
          </section>}
        </div>
        <div className="cash-denomination-total"><span>{labels.total}</span><strong>{tooLarge ? "—" : formatEuros(totalCents, locale)}</strong></div>
        {error && <p className="cash-denomination-error" role="alert">{error}</p>}
        <footer className="filter-actions"><button type="button" onClick={onCancel}>{labels.cancel}</button><button ref={acceptButton} type="submit" disabled={Boolean(error)} onFocus={() => { activeQuantity.current = null; setActiveCents(null); }}>{labels.accept}</button></footer>
      </form>
    </section>
  </div>, document.body);
}

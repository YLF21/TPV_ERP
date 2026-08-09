import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { LocaleCode } from "../types";

export type CalculatorOperator = "ADD" | "SUBTRACT" | "MULTIPLY" | "DIVIDE";

export type CalculatorState = {
  display: string;
  accumulator: number | null;
  pendingOperator: CalculatorOperator | null;
  waitingForOperand: boolean;
  expression: string;
  error: boolean;
  lastOperator: CalculatorOperator | null;
  lastOperand: number | null;
};

export type CalculatorAction =
  | { type: "DIGIT"; digit: string }
  | { type: "DECIMAL" }
  | { type: "OPERATOR"; operator: CalculatorOperator }
  | { type: "PERCENT" }
  | { type: "EQUALS" }
  | { type: "BACKSPACE" }
  | { type: "CLEAR_ENTRY" }
  | { type: "CLEAR_ALL" }
  | { type: "SET_RESULT"; value: number; expression: string; fixedDecimals?: number };

export const initialCalculatorState: CalculatorState = {
  display: "0",
  accumulator: null,
  pendingOperator: null,
  waitingForOperand: false,
  expression: "",
  error: false,
  lastOperator: null,
  lastOperand: null,
};

const operatorSymbols: Record<CalculatorOperator, string> = {
  ADD: "+",
  SUBTRACT: "−",
  MULTIPLY: "×",
  DIVIDE: "÷",
};

const copy = {
  es: {
    title: "Calculadora",
    close: "Cerrar",
    clear: "Borrar todo",
    clearEntry: "Borrar entrada",
    backspace: "Retroceso",
    equals: "Resultado",
    taxTools: "Cálculo de impuestos",
    taxRate: "Porcentaje",
    addTax: "Añadir impuesto",
    removeTax: "Quitar impuesto",
    withTax: "Precio con impuesto",
    withoutTax: "Precio sin impuesto",
    error: "Error",
  },
  en: {
    title: "Calculator",
    close: "Close",
    clear: "Clear all",
    clearEntry: "Clear entry",
    backspace: "Backspace",
    equals: "Result",
    taxTools: "Tax calculations",
    taxRate: "Percentage",
    addTax: "Add tax",
    removeTax: "Remove tax",
    withTax: "Price including tax",
    withoutTax: "Price excluding tax",
    error: "Error",
  },
  zh: {
    title: "计算器",
    close: "关闭",
    clear: "全部清除",
    clearEntry: "清除当前输入",
    backspace: "退格",
    equals: "结果",
    taxTools: "税费计算",
    taxRate: "税率",
    addTax: "添加税费",
    removeTax: "扣除税费",
    withTax: "含税价格",
    withoutTax: "未税价格",
    error: "错误",
  },
} as const;

function parsedDisplay(display: string) {
  const value = Number(display);
  return Number.isFinite(value) ? value : null;
}

function normalizedResult(value: number) {
  if (!Number.isFinite(value)) return null;
  return Number(value.toPrecision(12));
}

function machineValue(value: number, fixedDecimals?: number) {
  if (fixedDecimals != null) return value.toFixed(fixedDecimals);
  const normalized = normalizedResult(value);
  if (normalized == null) return "Error";
  return String(normalized);
}

function execute(left: number, operator: CalculatorOperator, right: number) {
  if (operator === "DIVIDE" && right === 0) return null;
  const result = operator === "ADD"
    ? left + right
    : operator === "SUBTRACT"
      ? left - right
      : operator === "MULTIPLY"
        ? left * right
        : left / right;
  return normalizedResult(result);
}

function errorState(): CalculatorState {
  return { ...initialCalculatorState, display: "Error", error: true, waitingForOperand: true };
}

function numberLabel(value: number) {
  return machineValue(value).replace("-", "−");
}

export function calculatorReducer(
  state: CalculatorState,
  action: CalculatorAction,
): CalculatorState {
  if (action.type === "CLEAR_ALL") return initialCalculatorState;
  if (action.type === "DIGIT") {
    if (!/^\d$/.test(action.digit)) return state;
    if (state.error) {
      return { ...initialCalculatorState, display: action.digit };
    }
    if (state.waitingForOperand) {
      return state.pendingOperator
        ? { ...state, display: action.digit, waitingForOperand: false }
        : { ...initialCalculatorState, display: action.digit };
    }
    if (state.display.replace(/[^0-9]/g, "").length >= 15) return state;
    return { ...state, display: state.display === "0" ? action.digit : state.display + action.digit };
  }
  if (action.type === "DECIMAL") {
    if (state.error) {
      return { ...initialCalculatorState, display: "0." };
    }
    if (state.waitingForOperand) {
      return state.pendingOperator
        ? { ...state, display: "0.", waitingForOperand: false }
        : { ...initialCalculatorState, display: "0." };
    }
    return state.display.includes(".") || state.display.includes("e")
      ? state
      : { ...state, display: `${state.display}.` };
  }
  if (action.type === "BACKSPACE") {
    if (state.error) return initialCalculatorState;
    if (state.waitingForOperand) return state;
    const next = state.display.length > 1 ? state.display.slice(0, -1) : "0";
    return { ...state, display: next === "-" ? "0" : next };
  }
  if (action.type === "CLEAR_ENTRY") {
    if (state.error) return initialCalculatorState;
    return state.pendingOperator
      ? { ...state, display: "0", waitingForOperand: false }
      : initialCalculatorState;
  }
  if (action.type === "SET_RESULT") {
    const value = normalizedResult(action.value);
    if (value == null) return errorState();
    return {
      ...initialCalculatorState,
      display: machineValue(value, action.fixedDecimals),
      expression: action.expression,
      waitingForOperand: true,
    };
  }

  const current = parsedDisplay(state.display);
  if (state.error || current == null) return state;

  if (action.type === "OPERATOR") {
    if (state.pendingOperator && state.accumulator != null && !state.waitingForOperand) {
      const result = execute(state.accumulator, state.pendingOperator, current);
      if (result == null) return errorState();
      return {
        ...state,
        display: machineValue(result),
        accumulator: result,
        pendingOperator: action.operator,
        waitingForOperand: true,
        expression: `${numberLabel(result)} ${operatorSymbols[action.operator]}`,
        lastOperator: null,
        lastOperand: null,
      };
    }
    const accumulator = state.accumulator ?? current;
    return {
      ...state,
      accumulator,
      pendingOperator: action.operator,
      waitingForOperand: true,
      expression: `${numberLabel(accumulator)} ${operatorSymbols[action.operator]}`,
      lastOperator: null,
      lastOperand: null,
    };
  }

  if (action.type === "PERCENT") {
    const contextual = state.pendingOperator && state.accumulator != null
      && (state.pendingOperator === "ADD" || state.pendingOperator === "SUBTRACT")
      ? state.accumulator * current / 100
      : current / 100;
    const result = normalizedResult(contextual);
    if (result == null) return errorState();
    const prefix = state.pendingOperator && state.accumulator != null
      ? `${numberLabel(state.accumulator)} ${operatorSymbols[state.pendingOperator]} `
      : "";
    return {
      ...state,
      display: machineValue(result),
      waitingForOperand: false,
      expression: `${prefix}${numberLabel(current)}%`,
    };
  }

  if (state.pendingOperator && state.accumulator != null) {
    const operand = state.waitingForOperand ? state.accumulator : current;
    const result = execute(state.accumulator, state.pendingOperator, operand);
    if (result == null) return errorState();
    return {
      ...initialCalculatorState,
      display: machineValue(result),
      expression: `${numberLabel(state.accumulator)} ${operatorSymbols[state.pendingOperator]} ${numberLabel(operand)} =`,
      waitingForOperand: true,
      lastOperator: state.pendingOperator,
      lastOperand: operand,
    };
  }
  if (state.lastOperator && state.lastOperand != null) {
    const result = execute(current, state.lastOperator, state.lastOperand);
    if (result == null) return errorState();
    return {
      ...state,
      display: machineValue(result),
      expression: `${numberLabel(current)} ${operatorSymbols[state.lastOperator]} ${numberLabel(state.lastOperand)} =`,
      waitingForOperand: true,
    };
  }
  return state;
}

type Props = {
  locale: LocaleCode;
  defaultTaxPercent?: number | string | null;
  terminalKey?: string;
  onClose: () => void;
};

const calculatorTaxPercentStoragePrefix = "tpverp.sale-calculator.tax-percent";

export function calculatorTaxPercentStorageKey(terminalKey: string) {
  return `${calculatorTaxPercentStoragePrefix}.${terminalKey}`;
}

function localizedDisplay(value: string, locale: LocaleCode, errorLabel: string) {
  if (value === "Error") return errorLabel;
  const decimal = locale === "en" ? "." : ",";
  return value.replace("-", "−").replace(".", decimal);
}

function localizedMoney(value: number | null, locale: LocaleCode) {
  if (value == null || !Number.isFinite(value)) return "—";
  return value.toLocaleString(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function initialTaxPercentage(value: number | string | null | undefined) {
  const rawValue = String(value ?? "").trim();
  if (!rawValue) return "21";
  const percentage = Number(rawValue.replace(",", "."));
  return Number.isFinite(percentage) && percentage >= 0 && percentage <= 100
    ? String(percentage)
    : "21";
}

function rememberedTaxPercentage(terminalKey?: string) {
  if (!terminalKey) return null;
  try {
    return globalThis.localStorage?.getItem(calculatorTaxPercentStorageKey(terminalKey)) ?? null;
  } catch {
    return null;
  }
}

function rememberTaxPercentage(terminalKey: string | undefined, value: string) {
  if (!terminalKey) return;
  const percentage = Number(value.replace(",", "."));
  if (!Number.isFinite(percentage) || percentage < 0 || percentage > 100) return;
  try {
    globalThis.localStorage?.setItem(
      calculatorTaxPercentStorageKey(terminalKey),
      String(percentage),
    );
  } catch {
    // The calculator remains usable when local preferences are unavailable.
  }
}

export function SaleCalculatorDialog({ locale, defaultTaxPercent, terminalKey, onClose }: Props) {
  const t = copy[locale];
  const dialogRef = useRef<HTMLElement>(null);
  const [state, setState] = useState(initialCalculatorState);
  const [taxPercent, setTaxPercent] = useState(() => initialTaxPercentage(
    rememberedTaxPercentage(terminalKey) ?? defaultTaxPercent,
  ));

  const dispatch = (action: CalculatorAction) => setState((current) => calculatorReducer(current, action));
  const currentAmount = parsedDisplay(state.display);
  const tax = Number(taxPercent.replace(",", "."));
  const validTax = Number.isFinite(tax) && tax >= 0 && tax <= 100;
  const taxPreview = useMemo(() => {
    if (currentAmount == null || !validTax) return { added: null, removed: null };
    return {
      added: currentAmount * (1 + tax / 100),
      removed: currentAmount / (1 + tax / 100),
    };
  }, [currentAmount, tax, validTax]);

  useEffect(() => { dialogRef.current?.focus(); }, []);

  function closeDialog() {
    if (validTax) rememberTaxPercentage(terminalKey, taxPercent);
    onClose();
  }

  function applyTax(mode: "ADD" | "REMOVE") {
    const result = mode === "ADD" ? taxPreview.added : taxPreview.removed;
    if (result == null || currentAmount == null) return;
    dispatch({
      type: "SET_RESULT",
      value: Math.round((result + Number.EPSILON) * 100) / 100,
      fixedDecimals: 2,
      expression: `${numberLabel(currentAmount)} ${mode === "ADD" ? "+" : "−"} ${numberLabel(tax)}%`,
    });
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault(); event.stopPropagation(); closeDialog(); return;
    }
    if ((event.target as HTMLElement).hasAttribute("data-calculator-tax-rate")) return;
    let action: CalculatorAction | null = null;
    if (/^\d$/.test(event.key)) action = { type: "DIGIT", digit: event.key };
    else if (event.key === "." || event.key === ",") action = { type: "DECIMAL" };
    else if (event.key === "+") action = { type: "OPERATOR", operator: "ADD" };
    else if (event.key === "-") action = { type: "OPERATOR", operator: "SUBTRACT" };
    else if (event.key === "*" || event.key.toLowerCase() === "x") action = { type: "OPERATOR", operator: "MULTIPLY" };
    else if (event.key === "/") action = { type: "OPERATOR", operator: "DIVIDE" };
    else if (event.key === "%") action = { type: "PERCENT" };
    else if (event.key === "Enter" || event.key === "=") action = { type: "EQUALS" };
    else if (event.key === "Backspace") action = { type: "BACKSPACE" };
    else if (event.key === "Delete") action = { type: "CLEAR_ENTRY" };
    else if (event.key.toLowerCase() === "c") action = { type: "CLEAR_ALL" };
    if (!action) return;
    event.preventDefault();
    event.stopPropagation();
    dispatch(action);
  }

  const keypad: Array<{ label: string; action: CalculatorAction; className?: string; ariaLabel?: string }> = [
    { label: "C", action: { type: "CLEAR_ALL" }, className: "utility", ariaLabel: t.clear },
    { label: "CE", action: { type: "CLEAR_ENTRY" }, className: "utility", ariaLabel: t.clearEntry },
    { label: "⌫", action: { type: "BACKSPACE" }, className: "utility", ariaLabel: t.backspace },
    { label: "÷", action: { type: "OPERATOR", operator: "DIVIDE" }, className: "operator" },
    ...["7", "8", "9"].map((digit) => ({ label: digit, action: { type: "DIGIT", digit } as CalculatorAction })),
    { label: "×", action: { type: "OPERATOR", operator: "MULTIPLY" }, className: "operator" },
    ...["4", "5", "6"].map((digit) => ({ label: digit, action: { type: "DIGIT", digit } as CalculatorAction })),
    { label: "−", action: { type: "OPERATOR", operator: "SUBTRACT" }, className: "operator" },
    ...["1", "2", "3"].map((digit) => ({ label: digit, action: { type: "DIGIT", digit } as CalculatorAction })),
    { label: "+", action: { type: "OPERATOR", operator: "ADD" }, className: "operator" },
    { label: "%", action: { type: "PERCENT" }, className: "percent" },
    { label: "0", action: { type: "DIGIT", digit: "0" } },
    { label: locale === "en" ? "." : ",", action: { type: "DECIMAL" } },
    { label: "=", action: { type: "EQUALS" }, className: "equals", ariaLabel: t.equals },
  ];

  return (
    <div className="sale-action-overlay sale-calculator-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sale-action-dialog sale-calculator-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-calculator-title"
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        <header>
          <h2 id="sale-calculator-title">{t.title}</h2>
          <button type="button" aria-label={t.close} onClick={closeDialog}>×</button>
        </header>

        <div className="sale-calculator-layout">
          <section className="sale-calculator-main" aria-label={t.title}>
            <div className={`sale-calculator-display ${state.error ? "error" : ""}`} aria-live="polite">
              <small>{state.expression || "\u00a0"}</small>
              <output aria-label={t.equals}>{localizedDisplay(state.display, locale, t.error)}</output>
            </div>
            <div className="sale-calculator-keypad">
              {keypad.map((key, index) => (
                <button
                  type="button"
                  key={`${key.label}-${index}`}
                  className={key.className ?? ""}
                  aria-label={key.ariaLabel ?? key.label}
                  onClick={() => dispatch(key.action)}
                >
                  {key.label}
                </button>
              ))}
            </div>
          </section>

          <aside className="sale-calculator-tax">
            <div className="sale-calculator-tax-heading">
              <span>{t.taxTools}</span>
            </div>
            <label>
              <span>{t.taxRate}</span>
              <div className="sale-calculator-tax-input">
                <input
                  data-calculator-tax-rate
                  inputMode="decimal"
                  value={taxPercent}
                  onChange={(event) => setTaxPercent(event.currentTarget.value.replace(/[^0-9,.]/g, ""))}
                  aria-invalid={!validTax}
                />
                <span>%</span>
              </div>
            </label>
            <div className="sale-calculator-tax-preview">
              <div><span>{t.withTax}</span><strong>{localizedMoney(taxPreview.added, locale)}</strong></div>
              <div><span>{t.withoutTax}</span><strong>{localizedMoney(taxPreview.removed, locale)}</strong></div>
            </div>
            <button type="button" disabled={!validTax || currentAmount == null} onClick={() => applyTax("ADD")}>{t.addTax}</button>
            <button type="button" disabled={!validTax || currentAmount == null} onClick={() => applyTax("REMOVE")}>{t.removeTax}</button>
          </aside>
        </div>

        <footer>
          <button type="button" onClick={closeDialog}>{t.close}</button>
        </footer>
      </section>
    </div>
  );
}

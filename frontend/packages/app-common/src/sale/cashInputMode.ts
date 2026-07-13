export type CashInputMode = "touch" | "keyboard";

const CASH_INPUT_MODE_KEY = "tpverp.cashInputMode.v1";

function defaultStorage(): Storage | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

export function readCashInputMode(storage: Storage | undefined = defaultStorage()): CashInputMode {
  try {
    const value = storage?.getItem(CASH_INPUT_MODE_KEY);
    return value === "keyboard" || value === "touch" ? value : "touch";
  } catch {
    return "touch";
  }
}

export function writeCashInputMode(
  mode: CashInputMode,
  storage: Storage | undefined = defaultStorage(),
): void {
  try {
    storage?.setItem(CASH_INPUT_MODE_KEY, mode);
  } catch {
    // The preference is optional when browser storage is unavailable.
  }
}

export function persistCashInputModeSelection(
  value: string,
  storage?: Storage,
): CashInputMode | undefined {
  if (value !== "touch" && value !== "keyboard") {
    return undefined;
  }

  writeCashInputMode(value, storage);
  return value;
}

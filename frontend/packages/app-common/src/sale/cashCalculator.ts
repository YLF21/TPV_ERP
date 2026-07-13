export type CashKey = string | "BACKSPACE" | "CLEAR";

export function pressCashKey(current: string, key: CashKey) {
  if (key === "CLEAR") return "";
  if (key === "BACKSPACE") return current.slice(0, -1);
  if (key === "," || key === ".") {
    return current.includes(",") ? current : `${current || "0"},`;
  }
  if (!/^\d$/.test(key)) return current;
  const decimalPart = current.split(",")[1];
  if (decimalPart != null && decimalPart.length >= 2) return current;
  const next = current === "0" ? key : `${current}${key}`;
  return next.replace(/^0+(?=\d)/, "");
}

export function cashInputCents(value: string) {
  if (!/^\d*(?:[,.]\d{0,2})?$/.test(value) || !/\d/.test(value)) return 0;
  const [euros = "0", decimals = ""] = value.replace(".", ",").split(",");
  return Number(euros || 0) * 100 + Number(decimals.padEnd(2, "0"));
}

export function cashChangeCents(totalCents: number, receivedCents: number) {
  return Math.max(0, receivedCents - totalCents);
}

export function setCashShortcut(shortcut: "EXACT" | number, totalCents: number) {
  const cents = shortcut === "EXACT" ? totalCents : shortcut * 100;
  return `${Math.floor(cents / 100)},${String(cents % 100).padStart(2, "0")}`;
}

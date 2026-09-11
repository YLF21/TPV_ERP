/** Format signed decimal strings without losing cents through IEEE-754 conversion. */
export function customerDocumentAmount(value: string, currency: string, locale: string): string {
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(value);
  if (!match || !/^[A-Z]{3}$/.test(currency)) return "—";
  const integer = BigInt(match[2]);
  const signed = match[1] === "-" ? (integer === 0n ? -0 : -integer) : integer;
  const fraction = (match[3] ?? "").padEnd(2, "0");
  return new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale, {
    style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
  }).formatToParts(signed).map((part) => part.type === "fraction" ? fraction : part.value).join("");
}

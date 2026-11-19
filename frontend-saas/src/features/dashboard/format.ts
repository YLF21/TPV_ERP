export function formatDocumentAmount(value: string, locale: string) {
  const [whole, fraction = "00"] = value.split(".");
  const signed = whole === "-0" ? -0 : BigInt(whole);
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    .formatToParts(signed).map(part => part.type === "fraction" ? fraction.padEnd(2, "0") : part.value).join("");
}

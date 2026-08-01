const LEFT_ODD = [
  "0001101", "0011001", "0010011", "0111101", "0100011",
  "0110001", "0101111", "0111011", "0110111", "0001011"
];
const LEFT_EVEN = [
  "0100111", "0110011", "0011011", "0100001", "0011101",
  "0111001", "0000101", "0010001", "0001001", "0010111"
];
const RIGHT = [
  "1110010", "1100110", "1101100", "1000010", "1011100",
  "1001110", "1010000", "1000100", "1001000", "1110100"
];
const EAN13_PARITY = [
  "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
  "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
];

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function eanBits(code) {
  const normalized = String(code ?? "").trim();
  if (!/^\d{8}$|^\d{13}$/.test(normalized)) {
    throw new Error("PRODUCT_LABEL_EAN_INVALID");
  }
  const payload = normalized.slice(0, -1);
  let sum = 0;
  for (let index = payload.length - 1, position = 0; index >= 0; index -= 1, position += 1) {
    sum += Number(payload[index]) * (position % 2 === 0 ? 3 : 1);
  }
  const expectedCheckDigit = String((10 - (sum % 10)) % 10);
  if (normalized.at(-1) !== expectedCheckDigit) {
    throw new Error("PRODUCT_LABEL_EAN_INVALID");
  }
  if (normalized.length === 8) {
    const left = normalized.slice(0, 4).split("").map((value) => LEFT_ODD[Number(value)]).join("");
    const right = normalized.slice(4).split("").map((value) => RIGHT[Number(value)]).join("");
    return `101${left}01010${right}101`;
  }
  const parity = EAN13_PARITY[Number(normalized[0])];
  const left = normalized.slice(1, 7).split("").map((value, index) =>
    parity[index] === "L" ? LEFT_ODD[Number(value)] : LEFT_EVEN[Number(value)]
  ).join("");
  const right = normalized.slice(7).split("").map((value) => RIGHT[Number(value)]).join("");
  return `101${left}01010${right}101`;
}

function barcodeSvg(code) {
  const bits = eanBits(code);
  const bars = [];
  let start = -1;
  for (let index = 0; index <= bits.length; index += 1) {
    if (bits[index] === "1" && start < 0) start = index;
    if (bits[index] !== "1" && start >= 0) {
      bars.push(`<rect x="${start}" y="0" width="${index - start}" height="38"/>`);
      start = -1;
    }
  }
  return `<svg class="barcode" viewBox="0 0 ${bits.length} 38" preserveAspectRatio="none" aria-label="${escapeHtml(code)}">${bars.join("")}</svg>`;
}

function number(value, fallback, minimum, maximum) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(maximum, Math.max(minimum, parsed)) : fallback;
}

function normalizedProfile(profile = {}) {
  const destination = ["LABEL_PRINTER", "TICKET_PRINTER", "A4"].includes(profile.destination)
    ? profile.destination
    : "TICKET_PRINTER";
  return {
    destination,
    printerName: String(profile.printerName ?? ""),
    widthMm: number(profile.widthMm, 58, 20, 210),
    heightMm: number(profile.heightMm, 40, 15, 297),
    orientation: profile.orientation === "LANDSCAPE" ? "LANDSCAPE" : "PORTRAIT",
    marginTopMm: number(profile.marginTopMm, 5, 0, 50),
    marginRightMm: number(profile.marginRightMm, 5, 0, 50),
    marginBottomMm: number(profile.marginBottomMm, 5, 0, 50),
    marginLeftMm: number(profile.marginLeftMm, 5, 0, 50),
    horizontalGapMm: number(profile.horizontalGapMm, 2, 0, 25),
    verticalGapMm: number(profile.verticalGapMm, 2, 0, 25),
    copies: Math.round(number(profile.copies, 1, 1, 999)),
    showStoreName: profile.showStoreName !== false,
  };
}

function labelMarkup(request, profile) {
  const product = request?.product ?? {};
  return `<article class="label">
    ${profile.showStoreName ? `<div class="store">${escapeHtml(request.storeName)}</div>` : ""}
    <div class="name">${escapeHtml(product.name)}</div>
    <div class="code">${escapeHtml(product.code)}</div>
    ${barcodeSvg(product.barcode)}
    <div class="barcode-text">${escapeHtml(product.barcode)}</div>
    <div class="price">${Number(product.price || 0).toFixed(2)} €</div>
  </article>`;
}

function renderProductLabelHtml(request) {
  const profile = normalizedProfile(request?.profile);
  const copies = Math.max(1, Math.round(Number(request?.copies) || profile.copies));
  const label = labelMarkup(request, profile);
  if (profile.destination === "A4") {
    const pageWidth = profile.orientation === "LANDSCAPE" ? 297 : 210;
    const pageHeight = profile.orientation === "LANDSCAPE" ? 210 : 297;
    const columns = Math.max(1, Math.floor((pageWidth - profile.marginLeftMm - profile.marginRightMm + profile.horizontalGapMm) / (profile.widthMm + profile.horizontalGapMm)));
    const rows = Math.max(1, Math.floor((pageHeight - profile.marginTopMm - profile.marginBottomMm + profile.verticalGapMm) / (profile.heightMm + profile.verticalGapMm)));
    const capacity = columns * rows;
    const startPosition = Math.min(capacity - 1, Math.max(0, Math.round(Number(request?.startPosition) || 0)));
    const sheets = [];
    let pendingCopies = copies;
    let pageStartPosition = startPosition;
    while (pendingCopies > 0) {
      const available = capacity - pageStartPosition;
      const pageCopies = Math.min(available, pendingCopies);
      const cells = [
        ...Array.from({ length: pageStartPosition }, () => '<div class="label empty"></div>'),
        ...Array.from({ length: pageCopies }, () => label),
      ].join("");
      sheets.push(`<main class="sheet">${cells}</main>`);
      pendingCopies -= pageCopies;
      pageStartPosition = 0;
    }
    return `<!doctype html><html><head><meta charset="utf-8"><style>
      @page { size: A4 ${profile.orientation === "LANDSCAPE" ? "landscape" : "portrait"}; margin: 0; }
      * { box-sizing: border-box; } body { margin: 0; color: #000; font-family: Arial, sans-serif; }
      .sheet { width: ${pageWidth}mm; height: ${pageHeight}mm; display: grid; grid-template-columns: repeat(${columns}, ${profile.widthMm}mm); grid-template-rows: repeat(${rows}, ${profile.heightMm}mm); gap: ${profile.verticalGapMm}mm ${profile.horizontalGapMm}mm; padding: ${profile.marginTopMm}mm ${profile.marginRightMm}mm ${profile.marginBottomMm}mm ${profile.marginLeftMm}mm; page-break-after: always; }
      .sheet:last-child { page-break-after: auto; }
      ${labelCss()}
    </style></head><body>${sheets.join("")}</body></html>`;
  }
  const pages = Array.from({ length: copies }, () => label).join("");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @page { size: ${profile.widthMm}mm ${profile.heightMm}mm; margin: 0; }
    * { box-sizing: border-box; } body { margin: 0; color: #000; font-family: Arial, sans-serif; }
    .label { width: ${profile.widthMm}mm; height: ${profile.heightMm}mm; page-break-after: always; }
    ${labelCss()}
  </style></head><body>${pages}</body></html>`;
}

function labelCss() {
  return `.label { overflow: hidden; display: grid; grid-template-columns: 1fr auto; grid-template-rows: auto auto 1fr auto; gap: .5mm 1.5mm; padding: 1.8mm; align-content: stretch; }
  .label.empty { visibility: hidden; } .store { grid-column: 1 / 3; font-size: 7pt; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .name { grid-column: 1 / 3; max-height: 9mm; overflow: hidden; font-size: 8.5pt; line-height: 1.15; font-weight: 800; }
  .code { grid-column: 1; font-size: 7pt; font-weight: 700; } .barcode { grid-column: 1; width: 100%; height: 12mm; fill: #000; }
  .barcode-text { grid-column: 1; text-align: center; font-size: 7pt; letter-spacing: .08em; }
  .price { grid-column: 2; grid-row: 3 / 5; align-self: end; font-size: 15pt; font-weight: 900; white-space: nowrap; }`;
}

module.exports = { barcodeSvg, eanBits, normalizedProfile, renderProductLabelHtml };

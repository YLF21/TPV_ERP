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
const MAX_ITEMS = 200;
const MAX_PLACEMENTS = 1000;
const MAX_PAGES = 100;

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
  return `<svg class="barcode" viewBox="-9 0 ${bits.length + 18} 38" preserveAspectRatio="none" aria-label="${escapeHtml(code)}">${bars.join("")}</svg>`;
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

function printableAddress(issuer) {
  const address = issuer?.address ?? {};
  return [
    address.line1,
    [address.postalCode, address.city].filter(Boolean).join(" "),
    address.province,
    address.country,
  ].filter((value, index, values) => value && values.indexOf(value) === index).join(", ");
}

function companyMarkup(request, profile) {
  if (!profile.showStoreName) return "";
  if (request.version === 2) {
    const address = printableAddress(request.issuer);
    if (!request.issuer?.name || !request.issuer?.taxId || !address) {
      throw new Error("PRODUCT_LABEL_COMPANY_INVALID");
    }
    return `<div class="company"><strong>${escapeHtml(request.issuer.name)}</strong><span>CIF: ${escapeHtml(request.issuer.taxId)}</span><span>${escapeHtml(address)}</span></div>`;
  }
  return `<div class="company"><strong>${escapeHtml(request.storeName)}</strong></div>`;
}

function normalizedCommercial(value) {
  if (value == null) return undefined;
  if (typeof value !== "object" || Array.isArray(value)) {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  const badge = String(value.badge ?? "").trim();
  const promotionLines = Array.isArray(value.promotionLines)
    ? value.promotionLines.map((line) => String(line ?? "").trim())
    : [];
  if (!badge || badge.length > 32 || promotionLines.length > 12
      || promotionLines.some((line) => !line || line.length > 160)) {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  let offer;
  if (value.offer != null) {
    const regularPrice = Number(value.offer.regularPrice);
    const offerPrice = Number(value.offer.offerPrice);
    const discountPercent = Number(value.offer.discountPercent);
    const validUntil = String(value.offer.validUntil ?? "").trim();
    if (![regularPrice, offerPrice, discountPercent].every(Number.isFinite)
        || regularPrice <= 0 || offerPrice < 0 || offerPrice >= regularPrice
        || regularPrice > 9999999 || discountPercent <= 0 || discountPercent > 100
        || validUntil.length > 48) {
      throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
    }
    offer = { regularPrice, offerPrice, discountPercent, ...(validUntil ? { validUntil } : {}) };
  }
  if (!offer && promotionLines.length === 0) {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  return { badge, offer, promotionLines };
}

function labelMarkup(request, profile, product, style = "") {
  const commercial = product.commercial;
  const priceMarkup = commercial?.offer
    ? `<div class="price offer-price"><span class="commercial-badge">${escapeHtml(commercial.badge)}</span><del>${commercial.offer.regularPrice.toFixed(2)} &euro;</del><strong>${commercial.offer.offerPrice.toFixed(2)} &euro;</strong><small>-${commercial.offer.discountPercent.toFixed(2)}%${commercial.offer.validUntil ? ` &middot; ${escapeHtml(commercial.offer.validUntil)}` : ""}</small></div>`
    : `<div class="price">${commercial ? `<span class="commercial-badge">${escapeHtml(commercial.badge)}</span>` : ""}<strong>${Number(product.price || 0).toFixed(2)} &euro;</strong></div>`;
  const promotionMarkup = commercial?.promotionLines?.length
    ? `<div class="promotion-summary">${commercial.promotionLines.map((line) => `<span>${escapeHtml(line)}</span>`).join("")}</div>`
    : "";
  return `<article class="label${profile.showStoreName ? " with-company" : ""}${commercial?.promotionLines?.length ? " with-promotions" : ""}"${style ? ` style="${style}"` : ""}>
    ${companyMarkup(request, profile)}
    <div class="name">${escapeHtml(product.name)}</div>
    <div class="label-content">
      <div class="code">${escapeHtml(product.code)}</div>
      <div class="barcode-block">${barcodeSvg(product.barcode)}<div class="barcode-text">${escapeHtml(product.barcode)}</div></div>
    </div>
    ${priceMarkup}
    ${promotionMarkup}
  </article>`;
}

function labelCss() {
  return `.label { overflow: hidden; display: grid; grid-template-columns: minmax(0,1fr) auto; grid-template-rows: auto minmax(0,1fr); gap: .35mm 1.2mm; padding: 1.6mm; align-content: stretch; background:#fff; }
  .label.with-company { grid-template-rows: auto auto minmax(0,1fr); }
  .label.with-promotions { grid-template-rows: auto minmax(0,1fr) auto; }
  .label.with-company.with-promotions { grid-template-rows: auto auto minmax(0,1fr) auto; }
  .label.empty { visibility: hidden; } .company { grid-column: 1 / 3; min-width:0; display:grid; gap:.15mm; font-size:5.5pt; line-height:1.05; overflow:hidden; }
  .company strong { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .company span { overflow-wrap:anywhere; }
  .name { grid-column: 1 / 3; display:-webkit-box; max-height: 6mm; overflow:hidden; font-size:8pt; line-height:1.1; font-weight:800; white-space:normal; -webkit-box-orient:vertical; -webkit-line-clamp:2; }
  .label-content { grid-column:1; min-width:0; min-height:0; display:grid; grid-template-rows:auto minmax(0,1fr); gap:.35mm; overflow:hidden; }
  .code { min-width:0; overflow:hidden; font-size:6.5pt; font-weight:700; text-overflow:ellipsis; white-space:nowrap; }
  .barcode-block { min-width:0; min-height:0; display:grid; grid-template-rows:minmax(4mm,1fr) auto; gap:.5mm; overflow:hidden; box-sizing:border-box; padding:0 1.2mm .8mm; }
  .barcode { width:100%; height:100%; min-height:4mm; fill:#000; }
  .barcode-text { overflow:hidden; padding:0 .5mm; text-align:center; font-size:6.5pt; line-height:1; letter-spacing:.06em; white-space:nowrap; }
  .price { grid-column:2; align-self:end; display:grid; justify-items:end; gap:.25mm; padding-bottom:.8mm; font-weight:900; white-space:nowrap; }
  .price strong { font-size:14pt; line-height:1; } .price del { font-size:7pt; font-weight:700; }
  .price small { max-width:36mm; overflow:hidden; font-size:6pt; text-overflow:ellipsis; }
  .commercial-badge { border:.25mm solid #000; background:#000; padding:.3mm .8mm; color:#fff; font-size:6pt; font-weight:900; letter-spacing:.04em; }
  .promotion-summary { grid-column:1 / 3; max-height:5.2mm; overflow:hidden; border-top:.35mm solid #000; padding:.55mm .8mm 0; font-size:6.5pt; font-weight:900; line-height:1.12; }
  .promotion-summary span { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }`;
}

function normalizedItems(request) {
  if (!Array.isArray(request.items) || request.items.length < 1 || request.items.length > MAX_ITEMS) {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  const ids = new Set();
  let total = 0;
  const items = request.items.map((item) => {
    const id = String(item?.id ?? "").trim();
    const copies = Math.round(Number(item?.copies));
    if (!id || ids.has(id) || !Number.isFinite(copies) || copies < 1 || copies > 999) {
      throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
    }
    ids.add(id);
    total += copies;
    if (total > MAX_PLACEMENTS) throw new Error("PRODUCT_LABEL_LIMIT_EXCEEDED");
    const product = item?.product ?? {};
    eanBits(product.barcode);
    return {
      id,
      copies,
      product: {
        name: String(product.name ?? ""),
        code: String(product.code ?? ""),
        barcode: String(product.barcode),
        price: Number.isFinite(Number(product.price)) ? Number(product.price) : 0,
        commercial: normalizedCommercial(product.commercial),
      },
    };
  });
  return { items, total };
}

function placementOverlaps(first, second) {
  return first.xMm < second.xMm + second.widthMm
    && first.xMm + first.widthMm > second.xMm
    && first.yMm < second.yMm + second.heightMm
    && first.yMm + first.heightMm > second.yMm;
}

function normalizedA4Pages(request, profile, items, total) {
  if (!Array.isArray(request.pages) || request.pages.length < 1 || request.pages.length > MAX_PAGES) {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  const pageWidth = profile.orientation === "LANDSCAPE" ? 297 : 210;
  const pageHeight = profile.orientation === "LANDSCAPE" ? 210 : 297;
  const minimumWidth = 35;
  const minimumHeight = profile.showStoreName ? 30 : 24;
  const itemsById = new Map(items.map((item) => [item.id, item]));
  const expected = new Map(items.map((item) => [item.id, item.copies]));
  const actual = new Map();
  const instanceIds = new Set();
  let placementTotal = 0;
  const pages = request.pages.map((page) => {
    if (!Array.isArray(page?.placements)) throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
    const placements = page.placements.map((value) => {
      const placement = {
        instanceId: String(value?.instanceId ?? "").trim(),
        itemId: String(value?.itemId ?? "").trim(),
        xMm: Number(value?.xMm), yMm: Number(value?.yMm),
        widthMm: Number(value?.widthMm), heightMm: Number(value?.heightMm),
      };
      if (!placement.instanceId || instanceIds.has(placement.instanceId) || !itemsById.has(placement.itemId)
          || ![placement.xMm, placement.yMm, placement.widthMm, placement.heightMm].every(Number.isFinite)
          || placement.widthMm < minimumWidth || placement.heightMm < minimumHeight
          || placement.xMm < profile.marginLeftMm || placement.yMm < profile.marginTopMm
          || placement.xMm + placement.widthMm > pageWidth - profile.marginRightMm
          || placement.yMm + placement.heightMm > pageHeight - profile.marginBottomMm) {
        throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
      }
      instanceIds.add(placement.instanceId);
      actual.set(placement.itemId, (actual.get(placement.itemId) ?? 0) + 1);
      placementTotal += 1;
      if (placementTotal > MAX_PLACEMENTS) throw new Error("PRODUCT_LABEL_LIMIT_EXCEEDED");
      return placement;
    });
    for (let index = 0; index < placements.length; index += 1) {
      if (placements.slice(index + 1).some((candidate) => placementOverlaps(placements[index], candidate))) {
        throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
      }
    }
    return { placements };
  });
  if (placementTotal !== total || [...expected].some(([itemId, copies]) => actual.get(itemId) !== copies)) {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  return pages;
}

function renderV2(request) {
  const profile = normalizedProfile(request.profile);
  const { items, total } = normalizedItems(request);
  if (profile.showStoreName) companyMarkup(request, profile);
  if (request.kind === "SEQUENTIAL") {
    if (profile.destination === "A4") throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
    const labels = items.flatMap((item) => Array.from(
      { length: item.copies },
      () => labelMarkup(request, profile, item.product),
    )).join("");
    return `<!doctype html><html><head><meta charset="utf-8"><style>
      @page { size: ${profile.widthMm}mm ${profile.heightMm}mm; margin: 0; }
      * { box-sizing: border-box; } body { margin: 0; color: #000; font-family: Arial, sans-serif; }
      .label { width: ${profile.widthMm}mm; height: ${profile.heightMm}mm; page-break-after: always; }
      .label:last-child { page-break-after: auto; }
      ${labelCss()}
    </style></head><body>${labels}</body></html>`;
  }
  if (request.kind !== "A4_LAYOUT" || profile.destination !== "A4") {
    throw new Error("PRODUCT_LABEL_INVALID_REQUEST");
  }
  const pages = normalizedA4Pages(request, profile, items, total);
  const pageWidth = profile.orientation === "LANDSCAPE" ? 297 : 210;
  const pageHeight = profile.orientation === "LANDSCAPE" ? 210 : 297;
  const itemsById = new Map(items.map((item) => [item.id, item]));
  const sheets = pages.map((page) => `<main class="sheet">${page.placements.map((placement) => {
    const item = itemsById.get(placement.itemId);
    return labelMarkup(request, profile, item.product,
      `left:${placement.xMm}mm;top:${placement.yMm}mm;width:${placement.widthMm}mm;height:${placement.heightMm}mm`);
  }).join("")}</main>`).join("");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @page { size: A4 ${profile.orientation === "LANDSCAPE" ? "landscape" : "portrait"}; margin: 0; }
    * { box-sizing: border-box; } body { margin: 0; color: #000; font-family: Arial, sans-serif; }
    .sheet { position:relative; width:${pageWidth}mm; height:${pageHeight}mm; page-break-after:always; overflow:hidden; }
    .sheet:last-child { page-break-after:auto; } .sheet > .label { position:absolute; }
    ${labelCss()}
  </style></head><body>${sheets}</body></html>`;
}

function renderLegacy(request) {
  const profile = normalizedProfile(request?.profile);
  const copies = Math.max(1, Math.min(999, Math.round(Number(request?.copies) || profile.copies)));
  const product = request?.product ?? {};
  const label = labelMarkup(request, profile, {
    ...product,
    commercial: normalizedCommercial(product.commercial),
  });
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
  const labels = Array.from({ length: copies }, () => label).join("");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @page { size: ${profile.widthMm}mm ${profile.heightMm}mm; margin: 0; }
    * { box-sizing: border-box; } body { margin: 0; color: #000; font-family: Arial, sans-serif; }
    .label { width: ${profile.widthMm}mm; height: ${profile.heightMm}mm; page-break-after: always; }
    ${labelCss()}
  </style></head><body>${labels}</body></html>`;
}

function renderProductLabelHtml(request) {
  return request?.version === 2 ? renderV2(request) : renderLegacy(request ?? {});
}

module.exports = { barcodeSvg, eanBits, normalizedProfile, renderProductLabelHtml };

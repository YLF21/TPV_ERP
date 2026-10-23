"use strict";

const MAX_LOGO_BYTES = 2 * 1024 * 1024;

function validLogoDataUri(value) {
  if (typeof value !== "string") return null;
  const match = /^data:image\/(?:png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(value);
  if (!match || match[1].length > Math.ceil(MAX_LOGO_BYTES / 3) * 4 || match[1].length % 4 !== 0) return null;
  const bytes = Buffer.from(match[1], "base64");
  if (bytes.length > MAX_LOGO_BYTES || bytes.toString("base64") !== match[1]) return null;
  const png = value.startsWith("data:image/png;") && bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  const jpeg = value.startsWith("data:image/jpeg;") && bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 && bytes[bytes.length - 2] === 255 && bytes[bytes.length - 1] === 217;
  const webp = value.startsWith("data:image/webp;") && bytes.length >= 12 && bytes.toString("ascii", 0, 4) === "RIFF" && bytes.toString("ascii", 8, 12) === "WEBP";
  if (!png && !jpeg && !webp) return null;
  return value;
}

// This function is serialized into the isolated display page. It has no Node dependencies.
function customerDisplayRuntime(initialState, footerLogo) {
  "use strict";
  const maxLogoBytes = 2 * 1024 * 1024;
  const labels = {
    es: { purchase: "Tu compra", cart: "Tu carrito", last: "ÚLTIMO ARTÍCULO", item: "Artículo", quantity: "Cantidad", amount: "Importe", subtotal: "Subtotal", discount: "Descuento aplicado", lastDiscount: "Descuento", shortDiscount: "Dto.", total: "TOTAL", thanks: "Gracias por su visita", welcome: "Bienvenido", units: "unidades", products: "productos", payment: "Pago", pendingPayment: "Cobro pendiente", change: "Cambio", pending: "Pendiente", error: "Importe no disponible", adjustments: { RETURN_ADJUSTMENT: "Ajustes de devolución", MEMBER_BALANCE: "Saldo socio", OTHER: "Otros ajustes" } },
    en: { purchase: "Your purchase", cart: "Your cart", last: "LAST ITEM", item: "Item", quantity: "Quantity", amount: "Amount", subtotal: "Subtotal", discount: "Discount applied", lastDiscount: "Discount", shortDiscount: "Disc.", total: "TOTAL", thanks: "Thank you for visiting", welcome: "Welcome", units: "units", products: "products", payment: "Payment", pendingPayment: "Payment pending", change: "Change", pending: "Pending", error: "Amount unavailable", adjustments: { RETURN_ADJUSTMENT: "Return adjustments", MEMBER_BALANCE: "Member balance", OTHER: "Other adjustments" } },
    zh: { purchase: "您的购物", cart: "您的购物车", last: "最新商品", item: "商品", quantity: "数量", amount: "金额", subtotal: "小计", discount: "已用折扣", lastDiscount: "折扣", shortDiscount: "折扣", total: "合计", thanks: "感谢您的光临", welcome: "欢迎光临", units: "件", products: "种商品", payment: "付款", pendingPayment: "待付款", change: "找零", pending: "处理中", error: "金额不可用", adjustments: { RETURN_ADJUSTMENT: "退货调整", MEMBER_BALANCE: "会员余额", OTHER: "其他调整" } }
  };
  const localeTags = { es: "es-ES", en: "en-GB", zh: "zh-CN" };
  const byId = (id) => document.getElementById(id);
  const elements = {
    storeLogo: byId("store-logo"), storeName: byId("store-name"), cartHeading: byId("cart-heading"),
    idlePanel: byId("idle-panel"), idleLine1: byId("idle-line-1"), idleLine2: byId("idle-line-2"),
    leftPanel: byId("left-panel"), cartPanel: byId("cart-panel"),
    lastLabel: byId("last-label"), lastName: byId("last-name"), lastDetail: byId("last-detail"),
    lastPrice: byId("last-price"), lastDiscount: byId("last-discount"), statusNote: byId("status-note"),
    subtotalLabel: byId("subtotal-label"), subtotalValue: byId("subtotal-value"),
    discountRow: byId("discount-row"), discountLabel: byId("discount-label"), discountValue: byId("discount-value"), adjustments: byId("adjustments"),
    totalLabel: byId("total-label"), totalValue: byId("total-value"),
    cartColumns: byId("cart-columns"), itemColumn: byId("item-column"), quantityColumn: byId("quantity-column"), amountColumn: byId("amount-column"),
    cartList: byId("cart-list"), cartSummary: byId("cart-summary"), cartTitle: byId("cart-title"),
    paymentRow: byId("payment-row"), paymentLabel: byId("payment-label"), paymentValue: byId("payment-value"),
    changeRow: byId("change-row"), changeLabel: byId("change-label"), changeValue: byId("change-value"),
    footerLogo: byId("footer-logo"), thanks: byId("thanks"), clock: byId("clock")
  };
  const make = (tag, className, value) => {
    const node = document.createElement(tag);
    node.className = className;
    if (value !== undefined) node.textContent = value;
    return node;
  };
  const finite = (value) => typeof value === "number" && Number.isFinite(value);
  const validLogo = (value) => {
    if (typeof value !== "string") return null;
    const match = /^data:image\/(?:png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(value);
    if (!match || match[1].length % 4 !== 0 || match[1].length > Math.ceil(maxLogoBytes / 3) * 4) return null;
    try {
      const bytes = atob(match[1]);
      if (bytes.length > maxLogoBytes || btoa(bytes) !== match[1]) return null;
      const png = value.startsWith("data:image/png;") && bytes.startsWith("\x89PNG\r\n\x1a\n");
      const jpeg = value.startsWith("data:image/jpeg;") && bytes.length >= 4 && bytes.charCodeAt(0) === 255 && bytes.charCodeAt(1) === 216 && bytes.charCodeAt(2) === 255 && bytes.charCodeAt(bytes.length - 2) === 255 && bytes.charCodeAt(bytes.length - 1) === 217;
      const webp = value.startsWith("data:image/webp;") && bytes.length >= 12 && bytes.slice(0, 4) === "RIFF" && bytes.slice(8, 12) === "WEBP";
      return png || jpeg || webp ? value : null;
    } catch { return null; }
  };
  const validCurrency = (value) => {
    if (typeof value !== "string" || !/^[A-Z]{3}$/.test(value)) return "EUR";
    try { new Intl.NumberFormat("en", { style: "currency", currency: value }); return value; }
    catch { return "EUR"; }
  };
  let clockLocale = localeTags.es;
  let clockZone;
  let rows = new Map();

  const scrollLastRowIntoView = () => {
    const row = elements.cartList.querySelector(".cart-row.is-last");
    if (!row) return;
    const listBounds = elements.cartList.getBoundingClientRect();
    const rowBounds = row.getBoundingClientRect();
    if (rowBounds.bottom > listBounds.bottom) elements.cartList.scrollTop += rowBounds.bottom - listBounds.bottom;
    else if (rowBounds.top < listBounds.top) elements.cartList.scrollTop -= listBounds.top - rowBounds.top;
  };
  const fitTotal = () => {
    const amount = elements.totalValue;
    const final = amount.parentElement;
    final.classList.remove("is-stacked");
    amount.style.fontSize = "";
    if (final.clientWidth <= 0 || final.scrollWidth <= final.clientWidth + 1) return;
    final.classList.add("is-stacked");
    if (amount.scrollWidth > final.clientWidth) {
      const baseSize = Number.parseFloat(window.getComputedStyle(amount).fontSize);
      if (Number.isFinite(baseSize)) amount.style.fontSize = `${Math.max(24, Math.floor(baseSize * final.clientWidth / amount.scrollWidth * .97))}px`;
    }
  };

  const money = (value, locale, currency) => finite(value)
    ? new Intl.NumberFormat(locale, { style: "currency", currency }).format(value)
    : "—";
  const number = (value, locale) => finite(value) ? new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(value) : "—";
  const discountText = (value, percent, label, locale, currency) => {
    if (!finite(value) || value <= 0) return "";
    const percentage = finite(percent) ? ` ${number(percent, locale)}%` : "";
    return `${label}${percentage} · −${money(Math.abs(value), locale, currency)}`;
  };
  const setLogo = (img, value) => {
    const uri = validLogo(value);
    if (uri) { if (img.src !== uri) img.src = uri; img.hidden = false; }
    else { img.hidden = true; img.removeAttribute("src"); }
  };
  const tick = () => {
    const now = new Date();
    try {
      const format = (options) => new Intl.DateTimeFormat(clockLocale, { ...options, ...(clockZone ? { timeZone: clockZone } : {}) }).format(now);
      elements.clock.textContent = `${format({ day: "2-digit", month: "2-digit", year: "numeric" })}\n${format({ hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false })}`;
    } catch {
      clockZone = undefined;
      elements.clock.textContent = `${new Intl.DateTimeFormat(clockLocale, { day: "2-digit", month: "2-digit", year: "numeric" }).format(now)}\n${new Intl.DateTimeFormat(clockLocale, { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(now)}`;
    }
  };
  const update = (nextState) => {
    const state = nextState && typeof nextState === "object" ? nextState : {};
    const lang = Object.prototype.hasOwnProperty.call(labels, state.locale) ? state.locale : "es";
    const t = labels[lang];
    const locale = localeTags[lang];
    const identity = state.identity && typeof state.identity === "object" ? state.identity : {};
    const currency = validCurrency(identity.currency);
    const zone = typeof identity.timeZone === "string" && identity.timeZone.trim() ? identity.timeZone.trim() : undefined;
    document.documentElement.lang = lang;
    document.body.dataset.phase = ["IDLE", "SALE", "PAYMENT"].includes(state.phase) ? state.phase : "IDLE";
    clockLocale = locale;
    clockZone = zone;
    setLogo(elements.storeLogo, identity.logoDataUri);
    elements.storeName.textContent = (typeof identity.storeName === "string" && identity.storeName.trim()) || (typeof identity.companyName === "string" && identity.companyName.trim()) || "";
    elements.storeName.hidden = !elements.storeName.textContent;
    elements.cartHeading.textContent = t.purchase;
    elements.cartTitle.textContent = t.cart;
    const phase = document.body.dataset.phase;
    const idle = phase === "IDLE";
    elements.idlePanel.hidden = !idle;
    elements.leftPanel.hidden = idle;
    elements.cartPanel.hidden = idle;
    elements.cartHeading.hidden = idle;
    elements.idleLine1.textContent = idle ? String(state.line1 ?? t.welcome) : "";
    elements.idleLine2.textContent = idle ? String(state.line2 ?? "") : "";
    const payment = state.payment && typeof state.payment === "object" ? state.payment : {};
    elements.lastLabel.textContent = phase === "PAYMENT" ? t.payment : t.last;
    elements.subtotalLabel.textContent = t.subtotal;
    elements.discountLabel.textContent = t.discount;
    elements.totalLabel.textContent = t.total;
    elements.itemColumn.textContent = t.item;
    elements.quantityColumn.textContent = t.quantity;
    elements.amountColumn.textContent = t.amount;
    elements.paymentLabel.textContent = t.payment;
    elements.changeLabel.textContent = t.change;
    elements.thanks.textContent = t.thanks;
    setLogo(elements.footerLogo, footerLogo);

    const sale = state.sale && typeof state.sale === "object" ? state.sale : {};
    const items = Array.isArray(sale.items) ? sale.items.filter((item) => item && typeof item === "object") : [];
    const status = ["READY", "PENDING", "ERROR"].includes(sale.status) ? sale.status : "PENDING";
    document.body.dataset.status = status;
    const ready = status === "READY";
    const activeItems = phase === "IDLE" ? [] : items;
    const last = activeItems.find((item) => item.id === sale.lastItemId) || activeItems[activeItems.length - 1];
    elements.lastName.textContent = phase === "PAYMENT" ? (payment.pending ? t.pendingPayment : t.payment) : (last ? String(last.name || "") : (phase === "IDLE" ? String(state.line1 || t.welcome) : (status === "ERROR" ? t.error : t.pending)));
    elements.lastName.classList.toggle("is-long", elements.lastName.textContent.length > 18);
    elements.lastDetail.textContent = phase !== "PAYMENT" && last && finite(last.quantity) ? `${number(last.quantity, locale)} × ${ready ? money(last.unitPrice, locale, currency) : "—"}` : "";
    elements.lastPrice.textContent = phase === "PAYMENT" ? "" : (last && ready ? money(last.total, locale, currency) : "—");
    elements.lastDiscount.textContent = phase !== "PAYMENT" && last && ready ? discountText(last.discount, last.discountPercent, t.lastDiscount, locale, currency) : "";
    elements.lastDiscount.hidden = !elements.lastDiscount.textContent;
    elements.statusNote.textContent = phase === "SALE" && !ready ? (status === "ERROR" ? t.error : t.pending) : "";
    elements.statusNote.hidden = !elements.statusNote.textContent;
    elements.lastDetail.classList.toggle("is-discounted", !!elements.lastDiscount.textContent);
    elements.subtotalLabel.parentElement.hidden = phase === "PAYMENT";
    elements.subtotalValue.textContent = phase === "IDLE" || !ready ? "—" : money(sale.subtotal, locale, currency);
    elements.discountValue.textContent = ready && phase !== "IDLE" && finite(sale.discount) && sale.discount > 0 ? `−${money(sale.discount, locale, currency)}` : "—";
    elements.discountRow.hidden = phase !== "SALE" || (ready && (!finite(sale.discount) || sale.discount <= 0));
    elements.adjustments.replaceChildren();
    if (phase === "SALE" && Array.isArray(sale.adjustments)) {
      for (const adjustment of sale.adjustments) {
        if (!adjustment || !Object.prototype.hasOwnProperty.call(t.adjustments, adjustment.kind) || !finite(adjustment.amount) || adjustment.amount === 0) continue;
        const row = make("div", "total-row adjustment-row");
        row.append(make("span", "", t.adjustments[adjustment.kind]), make("span", "", money(adjustment.amount, locale, currency)));
        elements.adjustments.append(row);
      }
    }
    elements.totalValue.textContent = phase === "PAYMENT" ? money(payment.total, locale, currency) : (phase === "SALE" && ready ? money(sale.total, locale, currency) : "—");
    elements.paymentRow.hidden = phase !== "PAYMENT";
    elements.paymentLabel.textContent = payment.pending ? t.pendingPayment : t.payment;
    elements.paymentValue.textContent = phase === "PAYMENT" ? money(payment.total, locale, currency) : "—";
    elements.changeRow.hidden = phase !== "PAYMENT" || payment.pending === true || !finite(payment.change);
    elements.changeValue.textContent = finite(payment.change) ? money(payment.change, locale, currency) : "—";

    const nextRows = new Map();
    const longCartValues = ready && activeItems.some((item) => [item.unitPrice, item.total].some((value) => money(value, locale, currency).length > 11));
    elements.cartColumns.classList.toggle("has-long-values", longCartValues);
    elements.cartList.classList.toggle("has-long-values", longCartValues);
    activeItems.forEach((item, index) => {
      const key = `${String(item.id ?? index)}:${index}`;
      let row = rows.get(key);
      if (!row) {
        row = make("div", "cart-row");
        row.append(make("div", "cart-item"), make("div", "cart-quantity"), make("div", "cart-amount"));
      }
      row.dataset.itemId = String(item.id ?? "");
      row.classList.toggle("is-last", item === last);
      const [nameCell, qtyCell, amountCell] = row.children;
      const displayName = String(item.name || "");
      if (nameCell.firstChild?.nodeType === 3) nameCell.firstChild.textContent = displayName;
      else nameCell.insertBefore(document.createTextNode(displayName), nameCell.firstChild);
      const detail = ready ? money(item.unitPrice, locale, currency) : "—";
      qtyCell.textContent = finite(item.quantity) ? `${number(item.quantity, locale)} × ${detail}` : "—";
      amountCell.textContent = ready ? money(item.total, locale, currency) : "—";
      let discount = nameCell.querySelector(".cart-discount");
      const discountValue = ready ? discountText(item.discount, item.discountPercent, t.shortDiscount, locale, currency) : "";
      if (discountValue) {
        if (!discount) { discount = make("span", "cart-discount"); nameCell.append(discount); }
        discount.textContent = discountValue;
      } else if (discount) discount.remove();
      // Move existing nodes only when their order actually changes.
      const atPosition = elements.cartList.children[index];
      if (atPosition !== row) elements.cartList.insertBefore(row, atPosition || null);
      nextRows.set(key, row);
    });
    for (const [key, row] of rows) if (!nextRows.has(key)) row.remove();
    rows = nextRows;
    elements.cartColumns.hidden = activeItems.length === 0;
    const units = activeItems.reduce((sum, item) => sum + (finite(item.quantity) ? item.quantity : 0), 0);
    elements.cartSummary.textContent = activeItems.length ? `${number(units, locale)} ${t.units} · ${number(activeItems.length, locale)} ${t.products}` : (phase === "IDLE" ? String(state.line2 || "") : (phase === "PAYMENT" ? (payment.pending ? t.pendingPayment : "") : (status === "ERROR" ? t.error : t.pending)));
    fitTotal();
    scrollLastRowIntoView();
    tick();
  };
  window.customerDisplayUpdate = update;
  update(initialState);
  if (typeof window.ResizeObserver === "function") {
    const listObserver = new window.ResizeObserver(scrollLastRowIntoView);
    listObserver.observe(elements.cartList);
  }
  window.addEventListener("resize", () => { fitTotal(); scrollLastRowIntoView(); });
  window.setInterval(tick, 1000);
}

function renderCustomerDisplayHtml(initialState, { esposLogo } = {}) {
  const serializedState = JSON.stringify(initialState && typeof initialState === "object" ? initialState : {})
    .replace(/[<>&\u2028\u2029]/g, (character) => ({ "<": "\\u003c", ">": "\\u003e", "&": "\\u0026", "\u2028": "\\u2028", "\u2029": "\\u2029" })[character]);
  const serializedFooterLogo = JSON.stringify(validLogoDataUri(esposLogo));
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Visor de cliente</title>
<style>
  :root { font-family: "Segoe UI", Arial, sans-serif; color: #06265a; background: #fff; font-synthesis: none; }
  * { box-sizing: border-box; }
  html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; }
  body { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; }
  .header, .footer { background: #edf5fb; display: flex; align-items: center; }
  .header { min-height: 7.5vh; padding: .9rem 2.5vw; justify-content: space-between; gap: 1rem; }
  .identity { display: flex; align-items: center; gap: .8rem; min-width: 0; }
  .store-logo { max-height: 3.1rem; max-width: 6.5rem; object-fit: contain; }
  .store-name { font-size: clamp(1.2rem, 2.3vw, 2.5rem); font-weight: 800; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .cart-heading { font-size: clamp(1.05rem, 1.8vw, 2rem); font-weight: 750; white-space: nowrap; }
  .display-main { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); min-height: 0; }
  .idle-panel { grid-column: 1 / -1; grid-row: 1 / -1; min-width: 0; min-height: 0; display: grid; align-content: safe center; justify-items: center; gap: clamp(.8rem, 3vh, 2.5rem); padding: clamp(1rem, 4vw, 4rem); text-align: center; overflow-y: auto; }
  .idle-panel p { max-width: min(70rem, 100%); margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  .idle-line-1 { font-size: clamp(2rem, 5vw, 6rem); font-weight: 800; line-height: 1.15; }
  .idle-line-2 { color: #355077; font-size: clamp(1.25rem, 3.4vw, 4rem); font-weight: 600; line-height: 1.25; }
  .left-panel { display: grid; grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; min-width: 0; min-height: 0; border-right: 1px solid #b8ccdd; }
  .last-item { min-width: 0; max-width: 100%; min-height: 0; display: flex; flex-direction: column; justify-content: center; padding: 2.6vw 3vw; overflow: hidden; }
  .eyebrow { color: #0664fb; font-size: clamp(1.05rem, 1.8vw, 2rem); font-weight: 800; letter-spacing: .015em; }
  .last-name { font-size: clamp(2.4rem, 6.6vw, 7.8rem); font-weight: 800; letter-spacing: -.045em; line-height: 1.12; overflow-wrap: anywhere; max-height: 2.3em; overflow: hidden; }
  .last-name.is-long { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; flex-shrink: 0; font-size: clamp(1.6rem, 3.6vw, 4rem); line-height: 1.1; max-height: 2.2em; }
  .last-transaction { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: .2rem 1rem; }
  .last-detail { color: #355077; font-size: clamp(1.2rem, 2.7vw, 3.1rem); font-weight: 600; }
  .last-detail.is-discounted { text-decoration: line-through; text-decoration-thickness: .08em; }
  .last-price { color: #0065fe; font-size: clamp(2rem, 5vw, 6rem); font-weight: 800; white-space: nowrap; }
  .last-discount, .cart-discount { color: #0065fe; font-weight: 700; }
  .last-discount { font-size: clamp(1rem, 2vw, 2.25rem); }
  .status-note { color: #476389; font-size: clamp(.95rem, 1.6vw, 1.75rem); font-weight: 650; }
  .total-panel { min-width: 0; max-width: 100%; color: #fff; background: #113c67; padding: 1.5rem 3vw 1.8rem; }
  .total-row { display: flex; justify-content: space-between; gap: 1rem; align-items: baseline; font-size: clamp(1.1rem, 1.9vw, 2rem); font-weight: 650; }
  .total-row + .total-row { margin-top: .4rem; }
  .total-final { min-width: 0; max-width: 100%; border-top: .22rem solid #0065fe; margin-top: 1rem; padding-top: .6rem; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 1rem; }
  .total-final.is-stacked { flex-direction: column; align-items: stretch; gap: .1rem; }
  .total-final.is-stacked strong { align-self: flex-end; }
  .total-final span { font-size: clamp(1.7rem, 2.6vw, 3rem); font-weight: 750; }
  .total-final strong { max-width: 100%; font-size: clamp(2.8rem, 6.2vw, 7rem); line-height: 1; white-space: nowrap; }
  .cart-panel { min-width: 0; min-height: 0; display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; padding: 2vw 2.5vw 1.5vw; }
  .cart-title { font-size: clamp(2.5rem, 5.5vw, 6.3rem); font-weight: 800; letter-spacing: -.045em; line-height: 1.1; margin: 0 0 1.2vw; }
  .cart-columns, .cart-row { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, .9fr) minmax(0, .65fr); gap: 1rem; align-items: center; }
  .cart-columns.has-long-values, .cart-list.has-long-values .cart-row { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); grid-template-areas: "name name" "quantity amount"; row-gap: .2rem; }
  .cart-columns.has-long-values #item-column, .cart-list.has-long-values .cart-item { grid-area: name; }
  .cart-columns.has-long-values #quantity-column, .cart-list.has-long-values .cart-quantity { grid-area: quantity; }
  .cart-columns.has-long-values #amount-column, .cart-list.has-long-values .cart-amount { grid-area: amount; }
  .cart-list.has-long-values .cart-quantity, .cart-list.has-long-values .cart-amount { font-size: clamp(1rem, 1.5vw, 1.5rem); }
  .cart-columns { border-bottom: 2px solid #b8ccdd; color: #476389; font-size: clamp(.9rem, 1.55vw, 1.65rem); font-weight: 650; padding-bottom: .55rem; }
  .cart-columns > :last-child, .cart-amount { text-align: right; }
  .cart-list { min-height: 0; overflow-y: auto; scrollbar-gutter: stable; }
  .cart-row { min-height: 4.8rem; padding: .8rem .3rem; border-bottom: 1px solid #ccd9e5; font-size: clamp(1rem, 1.8vw, 2rem); }
  .cart-row.is-last { background: #e7f3ff; border-left: .35rem solid #0065fe; padding-left: .65rem; }
  .cart-item { font-weight: 680; min-width: 0; overflow-wrap: anywhere; }
  .cart-quantity { min-width: 0; overflow-wrap: anywhere; }
  .cart-amount { min-width: 0; font-weight: 750; overflow-wrap: anywhere; }
  .cart-discount { display: block; font-size: .8em; }
  .cart-summary { border-top: 2px solid #b8ccdd; color: #476389; font-size: clamp(1rem, 1.7vw, 1.8rem); font-weight: 620; min-height: 3rem; padding-top: .9rem; }
  .footer { min-height: 7.5vh; padding: .6rem 2.5vw; display: grid; grid-template-columns: 1fr auto 1fr; gap: .6rem; }
  .footer-logo { max-width: clamp(5rem, 11vw, 12rem); max-height: 3rem; object-fit: contain; justify-self: start; }
  .thanks { font-size: clamp(1rem, 2.2vw, 2.4rem); font-weight: 750; text-align: center; }
  .clock { white-space: pre-line; text-align: right; justify-self: end; font-size: clamp(.8rem, 1.35vw, 1.55rem); line-height: 1.2; }
  [hidden] { display: none !important; }
  @media (max-width: 1100px) { .display-main { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); } .left-panel { grid-template-columns: minmax(0, 1fr) minmax(17rem, .75fr); grid-template-rows: auto; border-right: 0; border-bottom: 1px solid #b8ccdd; } .last-item { padding: 1.2rem 2vw; } .last-name { font-size: clamp(2rem, 4.3vw, 4rem); max-height: 1.2em; white-space: nowrap; text-overflow: ellipsis; overflow: hidden; } .last-price { font-size: clamp(2rem, 4vw, 4rem); } .last-detail { font-size: clamp(1rem, 2vw, 2rem); } .total-panel { padding: 1rem 2vw; } .total-row { font-size: clamp(.95rem, 1.6vw, 1.5rem); } .total-final { margin-top: .5rem; } .total-final span { font-size: clamp(1.1rem, 2vw, 2rem); } .total-final strong { font-size: clamp(2rem, 4.2vw, 4rem); } .cart-panel { padding: 1rem 2vw; } .cart-title { font-size: clamp(1.7rem, 3vw, 3rem); margin-bottom: .4rem; } .cart-row { min-height: 3.4rem; font-size: clamp(.95rem, 1.6vw, 1.5rem); } }
  @media (max-width: 620px), (orientation: portrait) and (max-width: 900px) { .header { min-height: 3.8rem; } .store-name { font-size: clamp(1rem, 3vw, 1.5rem); } .cart-heading { display: none; } .display-main { grid-template-rows: auto minmax(0, 1fr); } .left-panel { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto; } .last-item { padding: .65rem 1rem; } .eyebrow { font-size: .85rem; } .last-name { font-size: clamp(1.65rem, 5vw, 2.8rem); } .last-transaction { gap: .1rem .5rem; } .last-detail { font-size: 1rem; } .last-price { font-size: clamp(1.7rem, 5vw, 2.6rem); } .last-discount { font-size: .9rem; } .total-panel { padding: .55rem 1rem .7rem; } .total-row { font-size: .85rem; } .total-row + .total-row { margin-top: .1rem; } .total-final { padding-top: .25rem; margin-top: .3rem; border-width: .13rem; } .total-final span { font-size: 1.2rem; } .total-final strong { font-size: clamp(1.8rem, 5vw, 3rem); } .cart-panel { padding: .65rem 1rem; } .cart-title { font-size: 1.55rem; } .cart-columns, .cart-row { grid-template-columns: minmax(0, 1.5fr) minmax(0, .9fr) minmax(0, .8fr); gap: .35rem; } .cart-columns { font-size: .85rem; } .cart-row { font-size: 1rem; min-height: 2.8rem; } .cart-summary { min-height: 2rem; padding-top: .4rem; font-size: .9rem; } .footer { min-height: 3.8rem; grid-template-columns: auto minmax(0, 1fr) auto; padding: .4rem 1rem; } .footer-logo { max-width: 4rem; max-height: 2rem; } .thanks { font-size: .9rem; } .clock { font-size: .72rem; } }
  @media (max-height: 500px) and (min-width: 621px) { .header, .footer { min-height: 2.6rem; padding-top: .25rem; padding-bottom: .25rem; } .last-item { padding-top: .5rem; padding-bottom: .5rem; } .last-name { font-size: clamp(1.8rem, 4vw, 4rem); max-height: 1.2em; white-space: nowrap; text-overflow: ellipsis; } .last-price { font-size: clamp(2rem, 4vw, 4rem); } .total-panel { padding-top: .45rem; padding-bottom: .5rem; } .total-row { font-size: 1rem; } .total-row + .total-row { margin-top: .1rem; } .total-final { margin-top: .25rem; padding-top: .2rem; } .total-final strong { font-size: clamp(2rem, 4vw, 4rem); } .cart-title { font-size: clamp(2rem, 3vw, 3rem); } .cart-row { min-height: 2.8rem; } }
</style></head><body data-phase="IDLE" data-status="PENDING">
<header class="header"><div class="identity"><img id="store-logo" class="store-logo" alt="" hidden><span id="store-name" class="store-name" hidden></span></div><span id="cart-heading" class="cart-heading"></span></header>
<main class="display-main"><section id="idle-panel" class="idle-panel" hidden><p id="idle-line-1" class="idle-line-1"></p><p id="idle-line-2" class="idle-line-2"></p></section><section id="left-panel" class="left-panel"><div class="last-item"><span id="last-label" class="eyebrow"></span><div id="last-name" class="last-name"></div><div class="last-transaction"><span id="last-detail" class="last-detail"></span><strong id="last-price" class="last-price"></strong></div><div id="last-discount" class="last-discount" hidden></div><div id="status-note" class="status-note" hidden></div></div><div class="total-panel"><div class="total-row"><span id="subtotal-label"></span><span id="subtotal-value"></span></div><div id="discount-row" class="total-row" hidden><span id="discount-label"></span><span id="discount-value"></span></div><div id="adjustments"></div><div id="payment-row" class="total-row" hidden><span id="payment-label"></span><span id="payment-value"></span></div><div id="change-row" class="total-row" hidden><span id="change-label"></span><span id="change-value"></span></div><div class="total-final"><span id="total-label"></span><strong id="total-value"></strong></div></div></section><section id="cart-panel" class="cart-panel"><h1 id="cart-title" class="cart-title"></h1><div id="cart-columns" class="cart-columns" hidden><span id="item-column"></span><span id="quantity-column"></span><span id="amount-column"></span></div><div id="cart-list" class="cart-list"></div><div id="cart-summary" class="cart-summary"></div></section></main>
<footer class="footer"><img id="footer-logo" class="footer-logo" alt="esPOS" hidden><span id="thanks" class="thanks"></span><time id="clock" class="clock"></time></footer>
<script>(${customerDisplayRuntime.toString()})(${serializedState}, ${serializedFooterLogo});</script></body></html>`;
}

module.exports = { renderCustomerDisplayHtml };

export type SaleCommandId =
  | "sales-document"
  | "price-lookup"
  | "product-search"
  | "calculator"
  | "ean-generator"
  | "print-product-label"
  | "cash-drawer"
  | "logout"
  | "stock"
  | "sales-history"
  | "edit-product"
  | "close-cash"
  | "cash-withdrawal"
  | "ticket-return"
  | "gift-receipt"
  | "cancel-last-ticket"
  | "cancel-ticket"
  | "convert-ticket"
  | "import-previous-ticket"
  | "checkout"
  | "customer"
  | "park-sale"
  | "sale-comment"
  | "clear-sale"
  | "clear-lines"
  | "clear-discounts"
  | "print-method"
  | "sale-discount"
  | "quantity"
  | "add-quantity"
  | "subtract-quantity"
  | "next-units"
  | "next-package"
  | "desired-price"
  | "temporary-name"
  | "temporary-price"
  | "serial-number"
  | "line-discount";

export type SaleKeyboardCommandEvent = Pick<
  KeyboardEvent,
  "key" | "code" | "ctrlKey" | "shiftKey" | "altKey" | "metaKey"
>;

/**
 * Single keyboard contract for the sales screen. Menus and touch actions use
 * the same command ids, so the business action is never duplicated per input
 * method.
 */
export function saleCommandFromKeyboard(
  event: SaleKeyboardCommandEvent,
): SaleCommandId | null {
  if (event.altKey || event.metaKey) return null;

  if (event.ctrlKey) {
    const key = event.key.toLocaleLowerCase();
    if (event.shiftKey) {
      if (key === "a") return "clear-lines";
      if (key === "d") return "clear-discounts";
      return null;
    }
    if (key === "f") return "sales-document";
    if (event.key === "F2") return "ean-generator";
    if (key === "i") return "print-product-label";
    if (key === "g") return "park-sale";
    if (key === "n") return "serial-number";
    if (key === "o") return "sale-comment";
    if (key === "p") return "print-method";
    if (key === "r") return "gift-receipt";
    if (event.key === "F4") return "clear-sale";
    if (event.key === "F11") return "cancel-ticket";
    if (event.key === "PageUp") return "temporary-price";
    if (event.key === "+" || event.code === "NumpadAdd") return "add-quantity";
    if (event.key === "-" || event.code === "NumpadSubtract") return "subtract-quantity";
    if (event.key === "/") return "sale-discount";
    return null;
  }

  switch (event.key) {
    case "Delete": return "product-search";
    case "F1": return "price-lookup";
    case "F2": return "calculator";
    case "F3": return "cash-drawer";
    case "F4": return "logout";
    case "F5": return "stock";
    case "F6": return "sales-history";
    case "F7": return "edit-product";
    case "F8": return "close-cash";
    case "F9": return "cash-withdrawal";
    case "F10": return "ticket-return";
    case "F11": return "cancel-last-ticket";
    case "F12": return "convert-ticket";
    case "End": return "customer";
    case "Pause": return "quantity";
    case "Home": return "temporary-name";
    case "PageUp": return "desired-price";
    case "PageDown": return "checkout";
    case "+": return "next-units";
    case "*": return "next-package";
    case "/": return "line-discount";
    default: return null;
  }
}

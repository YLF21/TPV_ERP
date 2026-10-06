import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./ErpClassicTables.css";
import "./ProductCommercialHistory.css";

type CommercialHistoryEntry = {
  id: string;
  source: "OFFER" | "PROMOTION";
  type: string;
  name: string | null;
  dateFrom: string | null;
  dateTo: string | null;
  beforePrice: number | null;
  finalPrice: number | null;
  discountPercent: number | null;
  recordedAt: string;
  status: string;
  initialSnapshot: boolean;
};

type Props = {
  productId: string;
  productName: string;
  locale: LocaleCode;
  token?: string;
};

const promotionTypes = new Set([
  "PURCHASE_THRESHOLD_COUPON",
  "PURCHASE_THRESHOLD_DISCOUNT",
  "BUY_X_PAY_Y",
  "SECOND_UNIT_PERCENT",
  "FIXED_PACK_PRICE",
  "QUANTITY_DISCOUNT",
]);

function CommercialHistoryDialog({ productId, productName, locale, token, onClose }: Props & { onClose: () => void }) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const rowsRef = useRef<Array<HTMLTableRowElement | null>>([]);
  const [entries, setEntries] = useState<CommercialHistoryEntry[]>([]);
  const [state, setState] = useState<"loading" | "loaded" | "error">("loading");
  const [selectedIndex, setSelectedIndex] = useState(0);
  const localeTag = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat(localeTag, { dateStyle: "short" }), [localeTag]);
  const recordedAtFormatter = useMemo(() => new Intl.DateTimeFormat(localeTag, {
    dateStyle: "short", timeStyle: "short",
  }), [localeTag]);
  const priceFormatter = useMemo(() => new Intl.NumberFormat(localeTag, {
    style: "currency", currency: "EUR", minimumFractionDigits: 2, maximumFractionDigits: 3,
  }), [localeTag]);
  const percentFormatter = useMemo(() => new Intl.NumberFormat(localeTag, {
    minimumFractionDigits: 0, maximumFractionDigits: 2,
  }), [localeTag]);

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
    return () => {
      if (dialog.open && typeof dialog.close === "function") dialog.close();
      else dialog.removeAttribute("open");
    };
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    return activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document, { restoreFocus: false });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setState("loading");
    setEntries([]);
    void apiRequest<CommercialHistoryEntry[]>(
      `/products/${encodeURIComponent(productId)}/commercial-history`,
      { token, signal: controller.signal },
    ).then((result) => {
      if (controller.signal.aborted) return;
      setEntries(result);
      setSelectedIndex(0);
      setState("loaded");
    }).catch(() => {
      if (!controller.signal.aborted) setState("error");
    });
    return () => controller.abort();
  }, [productId, token]);

  function displayDate(value: string | null) {
    if (!value) return "—";
    const parsed = new Date(value.includes("T") ? value : `${value}T00:00:00`);
    return Number.isNaN(parsed.getTime()) ? value : dateFormatter.format(parsed);
  }

  function displayType(entry: CommercialHistoryEntry) {
    if (entry.source === "OFFER") {
      if (entry.type === "OFFER_PRICE") return t("product.discount.offerPrice");
      if (entry.type === "OFFER_DISCOUNT") return t("product.discount.offerDiscount");
      if (entry.type === "OFFER_UNKNOWN") return t("product.commercialHistory.unknownOffer");
    }
    if (entry.source === "PROMOTION" && promotionTypes.has(entry.type)) {
      return t(`stock.promotion.type.${entry.type}`);
    }
    return entry.type;
  }

  function unavailableValue(entry: CommercialHistoryEntry) {
    return t(entry.source === "PROMOTION"
      ? "product.commercialHistory.conditionalPrice"
      : "product.commercialHistory.unavailable");
  }

  function displayStatus(value: string) {
    if (["ACTIVE", "INACTIVE", "EXPIRED", "REMOVED"].includes(value)) {
      return t(`product.commercialHistory.status.${value}`);
    }
    return value;
  }

  function recordedAtTitle(value: string) {
    const parsed = new Date(value);
    const formatted = Number.isNaN(parsed.getTime()) ? value : recordedAtFormatter.format(parsed);
    return `${t("product.commercialHistory.recordedAt")}: ${formatted}`;
  }

  function handleRowKeyDown(event: KeyboardEvent<HTMLTableRowElement>, index: number) {
    let next = index;
    if (event.key === "ArrowDown") next = Math.min(index + 1, entries.length - 1);
    else if (event.key === "ArrowUp") next = Math.max(index - 1, 0);
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = entries.length - 1;
    else return;
    event.preventDefault();
    setSelectedIndex(next);
    rowsRef.current[next]?.focus();
  }

  return createPortal(
    <dialog
      ref={dialogRef}
      className="product-commercial-history-dialog erp-classic-tables"
      aria-label={`${t("product.commercialHistory.title")}: ${productName}`}
      onCancel={(event) => { event.preventDefault(); event.stopPropagation(); onClose(); }}
      onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === "Escape") { event.preventDefault(); onClose(); }
      }}
    >
      <header>
        <div>
          <h2>{t("product.commercialHistory.title")}</h2>
          <span>{productName}</span>
        </div>
        <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose} >×</WindowCloseButton>
      </header>
      <div className="product-commercial-history-body">
        {state === "loading" && <p role="status">{t("common.loading")}</p>}
        {state === "error" && <p role="alert">{t("product.commercialHistory.loadError")}</p>}
        {state === "loaded" && (
          <div className="product-commercial-history-scroll">
            <table>
              <thead><tr>
                <th scope="col">{t("product.commercialHistory.type")}</th>
                <th scope="col">{t("product.commercialHistory.from")}</th>
                <th scope="col">{t("product.commercialHistory.to")}</th>
                <th scope="col">{t("product.commercialHistory.discountPercent")}</th>
                <th scope="col">{t("product.commercialHistory.beforePrice")}</th>
                <th scope="col">{t("product.commercialHistory.finalPrice")}</th>
              </tr></thead>
              <tbody>
                {entries.length === 0 && (
                  <tr><td colSpan={6}>{t("product.commercialHistory.empty")}</td></tr>
                )}
                {entries.map((entry, index) => (
                  <tr
                    key={entry.id}
                    ref={(row) => { rowsRef.current[index] = row; }}
                    tabIndex={index === selectedIndex ? 0 : -1}
                    aria-selected={index === selectedIndex}
                    onFocus={() => setSelectedIndex(index)}
                    onClick={() => { setSelectedIndex(index); rowsRef.current[index]?.focus(); }}
                    onKeyDown={(event) => handleRowKeyDown(event, index)}
                  >
                    <td title={recordedAtTitle(entry.recordedAt)}>
                      <span>{displayType(entry)}</span>
                      {entry.name && <small>{entry.name}</small>}
                      <small className="product-commercial-history-status" data-status={entry.status}>
                        {displayStatus(entry.status)}
                      </small>
                    </td>
                    <td>{displayDate(entry.dateFrom)}</td>
                    <td>{displayDate(entry.dateTo)}</td>
                    <td>{entry.discountPercent === null ? unavailableValue(entry) : `${percentFormatter.format(entry.discountPercent)} %`}</td>
                    <td>{entry.beforePrice === null ? unavailableValue(entry) : priceFormatter.format(entry.beforePrice)}</td>
                    <td>{entry.finalPrice === null ? unavailableValue(entry) : priceFormatter.format(entry.finalPrice)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <footer className="erp-dialog-actions-row"><DialogDismissButton type="button" className="erp-dialog-action-cancel erp-dialog-dismiss" onClick={onClose}>{t("common.close")}</DialogDismissButton></footer>
    </dialog>,
    document.body,
  );
}

export function ProductCommercialHistory({ productId, productName, locale, token }: Props) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const t = createTranslator(locale);
  return <>
    <button ref={triggerRef} className="product-commercial-history-trigger" type="button" onClick={() => setOpen(true)}>
      {t("product.commercialHistory.button")}
    </button>
    {open && <CommercialHistoryDialog productId={productId} productName={productName} locale={locale} token={token} onClose={() => {
      setOpen(false);
      requestAnimationFrame(() => triggerRef.current?.focus());
    }} />}
  </>;
}

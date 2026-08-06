import { Fragment, useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import {
  canonicalProductQuantity,
  formatProductQuantity,
  isProductQuantityPrecisionValid,
  productQuantityStep,
} from "../sale/productQuantity";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type ReturnLineOption = {
  lineId: string;
  giftReceiptLineId?: string | null;
  productId: string;
  code: string;
  barcode?: string | null;
  barcode2?: string | null;
  name: string;
  lineType: "PRODUCT";
  productType: "UNIT" | "WEIGHT" | "SERVICE";
  refundableQuantity: number | string;
  unitPrice: number | string;
  refundableTotal: number | string;
  refundableSerialNumbers?: string[];
  discount: number | string;
  taxesIncluded: boolean;
  taxRegime: "IVA" | "IGIC" | string;
  taxPercentage: number | string;
};

type ReturnPreview = {
  sourceType: "TICKET" | "GIFT_RECEIPT";
  sourceCode: string;
  ticketId: string;
  ticketNumber: string;
  date: string;
  total: number | string;
  lines: ReturnLineOption[];
  paymentAvailability?: Array<{
    paymentMethod: string;
    kind?: "CASH" | "MANUAL_CARD" | "INTEGRATED_CARD" | "VOUCHER" | "TRANSFER" | null;
    originalAmount: number | string;
    refundedAmount: number | string;
    reservedAmount: number | string;
    availableAmount: number | string;
  }>;
};

type LineSelection = {
  lineId: string;
  quantity: string;
  serialNumbers: string[];
};

type ReturnValuation = {
  selectedGross: number | string;
  lostBenefits: number | string;
  refundableAmount: number | string;
  eligibleRefundableAmount: number | string;
  cumulativeEligibleRefundableAmount: number | string;
  cumulativeRefundableAmount: number | string;
  previouslyRefundedAmount: number | string;
  remainingBasketValue: number | string;
};

export type ReturnCartLine = ReturnLineOption & {
  sourceType: ReturnPreview["sourceType"];
  sourceCode: string;
  sourceTicketId: string;
  sourceTicketNumber: string;
  returnQuantity: number;
  selectedSerialNumbers: string[];
};

export type ReturnCartReservation = {
  sourceTicketId: string;
  sourceCode: string;
  lineId: string;
  returnQuantity: number;
  selectedSerialNumbers: string[];
};

export function calendarDaysElapsed(date: string, now = new Date()) {
  const [year, month, day] = date.split("-").map(Number);
  if (!year || !month || !day) return 0;
  const ticketDay = Date.UTC(year, month - 1, day);
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.max(0, Math.floor((today - ticketDay) / 86_400_000));
}

type Props = {
  token?: string;
  locale: LocaleCode;
  existingCartLines?: readonly ReturnCartReservation[];
  onClose: () => void;
  onAddToCart: (lines: ReturnCartLine[]) => void;
};

export function TicketReturnDialog({ token, locale, existingCartLines = [], onClose, onAddToCart }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const lockedSourceCode = existingCartLines[0]?.sourceCode ?? "";
  const [identifier, setIdentifier] = useState(lockedSourceCode);
  const [productIdentifier, setProductIdentifier] = useState("");
  const [preview, setPreview] = useState<ReturnPreview | null>(null);
  const [selections, setSelections] = useState<LineSelection[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [valuation, setValuation] = useState<ReturnValuation | null>(null);
  const [valuationBusy, setValuationBusy] = useState(false);
  const [valuationError, setValuationError] = useState("");
  const valuationRequest = useRef(0);
  const initialSearchStarted = useRef(false);
  const productSearchRef = useRef<HTMLInputElement>(null);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  useEffect(() => {
    if (preview) productSearchRef.current?.focus();
  }, [preview?.sourceCode]);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || busy) return;
      event.preventDefault();
      onClose();
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [busy, onClose]);

  useEffect(() => {
    const requestId = ++valuationRequest.current;
    if (!preview || !validSelection()) {
      setValuation(null);
      setValuationBusy(false);
      setValuationError("");
      return;
    }
    setValuation(null);
    setValuationBusy(true);
    setValuationError("");
    const timeout = window.setTimeout(() => {
      void apiRequest<ReturnValuation>("/tickets/return-valuation", {
        token,
        body: {
          ticketNumber: preview.sourceCode,
          lines: selections.map((selection) => ({
            lineId: selection.lineId,
            quantity: Number(selection.quantity),
          })),
        },
      }).then((result) => {
        if (valuationRequest.current !== requestId) return;
        setValuation(result);
      }).catch((reason) => {
        if (valuationRequest.current !== requestId) return;
        setValuationError(reason instanceof Error
          ? reason.message
          : t("ticketReturn.searchError"));
      }).finally(() => {
        if (valuationRequest.current === requestId) setValuationBusy(false);
      });
    }, 120);
    return () => window.clearTimeout(timeout);
  }, [preview, selections, token]);

  async function loadPreview(normalized: string) {
    if (!normalized || busy) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const result = await apiRequest<ReturnPreview>(
        `/tickets/return-preview?ticketNumber=${encodeURIComponent(normalized)}`,
        { token },
      );
      setPreview(result);
      setIdentifier(result.sourceCode);
      setProductIdentifier("");
      setSelections([]);
      setValuation(null);
      setValuationError("");
      if (result.lines.length === 0) setMessage(t("ticketReturn.noAvailableLines"));
      queueMicrotask(() => productSearchRef.current?.focus());
    } catch (reason) {
      setPreview(null);
      setSelections([]);
      setValuation(null);
      setError(reason instanceof Error ? reason.message : t("ticketReturn.searchError"));
    } finally {
      setBusy(false);
    }
  }

  async function search() {
    await loadPreview(identifier.trim());
  }

  useEffect(() => {
    if (!lockedSourceCode || initialSearchStarted.current) return;
    initialSearchStarted.current = true;
    void loadPreview(lockedSourceCode);
  }, [lockedSourceCode]);

  function cartReservation(option: ReturnLineOption) {
    return existingCartLines.filter((line) => line.sourceTicketId === preview?.ticketId
      && line.lineId === option.lineId);
  }

  function quantityInCart(option: ReturnLineOption) {
    return cartReservation(option).reduce((total, line) => total + Number(line.returnQuantity), 0);
  }

  function availableSerialNumbers(option: ReturnLineOption) {
    const reserved = new Set(cartReservation(option).flatMap((line) => line.selectedSerialNumbers));
    return (option.refundableSerialNumbers ?? []).filter((serial) => !reserved.has(serial));
  }

  function availableQuantity(option: ReturnLineOption) {
    const remaining = Math.max(0, Number(option.refundableQuantity) - quantityInCart(option));
    const serials = option.refundableSerialNumbers ?? [];
    return serials.length === 0 ? remaining : Math.min(remaining, availableSerialNumbers(option).length);
  }

  function productMatches(option: ReturnLineOption, value: string) {
    const normalized = value.trim().toLocaleLowerCase();
    if (!normalized) return true;
    const identifiers = [option.code, option.barcode, option.barcode2]
      .filter((candidate): candidate is string => Boolean(candidate))
      .map((candidate) => candidate.trim().toLocaleLowerCase());
    return identifiers.includes(normalized)
      || option.name.toLocaleLowerCase().includes(normalized);
  }

  const visibleLines = preview?.lines.filter((line) => productMatches(line, productIdentifier)) ?? [];

  function selectProduct() {
    const normalized = productIdentifier.trim();
    if (!normalized || !preview || busy) return;
    const matches = preview.lines.filter((line) => productMatches(line, normalized)
      && availableQuantity(line) > 0);
    setError("");
    if (matches.length === 0) {
      setMessage(t("ticketReturn.productNotFound"));
      return;
    }
    const selectedMatches = matches.filter((option) => selected(option.lineId));
    if (matches.length > 1 && selectedMatches.length !== 1) {
      setMessage(t("ticketReturn.productMultipleLines"));
      setProductIdentifier("");
      queueMicrotask(() => productSearchRef.current?.focus());
      return;
    }
    const option = selectedMatches[0] ?? matches[0];
    const currentSelection = selected(option.lineId);
    const serialNumbers = availableSerialNumbers(option);
    if (serialNumbers.length > 1) {
      if (!currentSelection) {
        setSelections((current) => [
          ...current.filter((line) => line.lineId !== option.lineId),
          singleSelection(option),
        ]);
      }
      setMessage(t("ticketReturn.selectSerial"));
      setProductIdentifier("");
      queueMicrotask(() => productSearchRef.current?.focus());
      return;
    }
    const currentQuantity = Number(currentSelection?.quantity ?? 0);
    const nextQuantity = Math.min(availableQuantity(option), currentQuantity + 1);
    setSelections((current) => [
      ...current.filter((line) => line.lineId !== option.lineId),
      {
        lineId: option.lineId,
        quantity: canonicalProductQuantity(nextQuantity),
        serialNumbers: serialNumbers.length === 1 ? serialNumbers : [],
      },
    ]);
    setMessage(nextQuantity === currentQuantity
      ? t("ticketReturn.productMaximumSelected")
      : t("ticketReturn.productQuantityIncreased").replace("{quantity}", canonicalProductQuantity(nextQuantity)));
    setProductIdentifier("");
    queueMicrotask(() => productSearchRef.current?.focus());
  }

  function selected(lineId: string) {
    return selections.find((line) => line.lineId === lineId);
  }

  function fullSelection(option: ReturnLineOption): LineSelection {
    const serialNumbers = availableSerialNumbers(option);
    return {
      lineId: option.lineId,
      quantity: canonicalProductQuantity(availableQuantity(option)),
      serialNumbers,
    };
  }

  function singleSelection(option: ReturnLineOption): LineSelection {
    const available = availableQuantity(option);
    const serialNumbers = availableSerialNumbers(option);
    if (serialNumbers.length === 1) {
      return { lineId: option.lineId, quantity: "1", serialNumbers };
    }
    if (serialNumbers.length > 1) {
      return { lineId: option.lineId, quantity: "0", serialNumbers: [] };
    }
    return {
      lineId: option.lineId,
      quantity: canonicalProductQuantity(Math.min(1, available)),
      serialNumbers: [],
    };
  }

  function toggleLine(option: ReturnLineOption, checked: boolean) {
    setSelections((current) => checked
      ? [...current.filter((line) => line.lineId !== option.lineId), singleSelection(option)]
      : current.filter((line) => line.lineId !== option.lineId));
  }

  function selectAll() {
    if (!preview) return;
    setSelections(preview.lines.filter((line) => availableQuantity(line) > 0).map(fullSelection));
  }

  function updateQuantity(option: ReturnLineOption, quantity: string) {
    setSelections((current) => current.map((line) => line.lineId === option.lineId
      ? { ...line, quantity }
      : line));
  }

  function toggleSerial(option: ReturnLineOption, serial: string, checked: boolean) {
    const current = selected(option.lineId);
    const serialNumbers = checked
      ? [...(current?.serialNumbers ?? []), serial]
      : (current?.serialNumbers ?? []).filter((value) => value !== serial);
    setSelections((values) => [
      ...values.filter((line) => line.lineId !== option.lineId),
      ...[{
        lineId: option.lineId,
        quantity: String(serialNumbers.length),
        serialNumbers,
      }],
    ]);
  }

  function validSelection() {
    if (!preview || selections.length === 0) return false;
    return selections.every((selection) => {
      const option = preview.lines.find((line) => line.lineId === selection.lineId);
      const quantity = Number(selection.quantity);
      if (!option || !Number.isFinite(quantity) || quantity <= 0
        || quantity > availableQuantity(option)
        || !isProductQuantityPrecisionValid(quantity, option.productType)) return false;
      const serials = availableSerialNumbers(option);
      return serials.length === 0 || (Number.isInteger(quantity)
        && selection.serialNumbers.length === quantity
        && selection.serialNumbers.every((serial) => serials.includes(serial)));
    });
  }

  function addSelectedToCart() {
    if (!preview || !validSelection() || !valuation || valuationBusy || busy) return;
    onAddToCart(selections.map((selection) => {
      const option = preview.lines.find((line) => line.lineId === selection.lineId)!;
      return {
        ...option,
        sourceType: preview.sourceType,
        sourceCode: preview.sourceCode,
        sourceTicketId: preview.ticketId,
        sourceTicketNumber: preview.ticketNumber,
        returnQuantity: Number(selection.quantity),
        selectedSerialNumbers: selection.serialNumbers,
      };
    }));
    onClose();
  }

  return (
    <div className="sale-action-overlay ticket-return-overlay" role="presentation">
      <section ref={dialogRef} className="sale-action-dialog wide ticket-return-dialog" role="dialog" aria-modal="true" aria-labelledby="ticket-return-title" aria-busy={busy}>
        <header className="ticket-return-header">
          <div>
            <h2 id="ticket-return-title">{t("ticketReturn.title")}</h2>
            <p>{t("ticketReturn.cartDescription")}</p>
          </div>
          <button type="button" aria-label={t("common.close")} disabled={busy} onClick={onClose}>×</button>
        </header>

        <div className="ticket-return-body">
          <div className="ticket-return-search">
            <label>
              <span>{t("ticketReturn.ticketOrGiftCode")}</span>
              <input autoFocus={!lockedSourceCode} readOnly={Boolean(lockedSourceCode)} autoComplete="off" value={identifier} onChange={(event) => setIdentifier(event.currentTarget.value)} onKeyDown={(event) => {
                if (event.key === "Enter") { event.preventDefault(); void search(); }
              }} />
            </label>
            <button type="button" className="primary" disabled={Boolean(lockedSourceCode) || !identifier.trim() || busy} onClick={() => void search()}>{t("ticketReturn.search")}</button>
          </div>

          {preview && <>
            <div className="ticket-return-product-search">
              <label>
                <span>{t("ticketReturn.productSearch")}</span>
                <input
                  ref={productSearchRef}
                  autoComplete="off"
                  value={productIdentifier}
                  placeholder={t("ticketReturn.productSearchPlaceholder")}
                  onChange={(event) => {
                    setProductIdentifier(event.currentTarget.value);
                    setMessage("");
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") { event.preventDefault(); selectProduct(); }
                  }}
                />
              </label>
              <button type="button" className="primary" disabled={!productIdentifier.trim() || busy} onClick={selectProduct}>{t("ticketReturn.selectProduct")}</button>
            </div>
            <div className="ticket-return-summary">
              <span><small>{preview.sourceType === "GIFT_RECEIPT" ? t("ticketReturn.giftReceipt") : t("ticketReturn.ticket")}</small><strong>{preview.sourceCode}</strong></span>
              <span><small>{t("ticketReturn.date")}</small><strong>{new Date(`${preview.date}T00:00:00`).toLocaleDateString(locale)}</strong><em>{t("ticketReturn.daysElapsed")}: {calendarDaysElapsed(preview.date)}</em></span>
              <span className="ticket-return-summary-total"><small>{t("ticketReturn.selectedTotal")}</small><strong>{valuationBusy
                ? "..."
                : valuation
                  ? money(valuation.refundableAmount, locale)
                   : "-"}</strong></span>
            </div>
            <div className="ticket-return-payment-availability" aria-label={t("ticketReturn.paymentAvailability")}>
              {(preview.paymentAvailability ?? []).filter((payment) => Number(payment.originalAmount) > 0).map((payment) => <article key={`${payment.paymentMethod}-${payment.kind ?? "OTHER"}`}>
                <strong>{payment.kind === "CASH"
                  ? t("ticketReturn.cash")
                  : payment.kind === "MANUAL_CARD" || payment.kind === "INTEGRATED_CARD"
                    ? t("ticketReturn.card")
                    : payment.paymentMethod}</strong>
                <span>{t("ticketReturn.originalPaid")}: {money(payment.originalAmount, locale)}</span>
                <span>{t("ticketReturn.previouslyReturned")}: {money(payment.refundedAmount, locale)}</span>
                <b>{t("ticketReturn.maximumByMethod")}: {money(payment.availableAmount, locale)}</b>
              </article>)}
            </div>
            <div className="ticket-return-selection-tools">
              <button type="button" className="primary" disabled={preview.lines.every((line) => availableQuantity(line) <= 0) || busy} onClick={selectAll}>{t("ticketReturn.selectAll")}</button>
              <button type="button" disabled={selections.length === 0 || busy} onClick={() => setSelections([])}>{t("ticketReturn.clearSelection")}</button>
            </div>
            <div className="ticket-return-lines">
              <table aria-label={t("ticketReturn.productList")}>
                <colgroup>
                  <col className="ticket-return-select-column" />
                  <col className="ticket-return-product-column" />
                  <col className="ticket-return-pending-column" />
                  <col className="ticket-return-price-column" />
                  <col className="ticket-return-quantity-column" />
                </colgroup>
                <thead>
                  <tr>
                    <th aria-label={t("ticketReturn.selectAll")} />
                    <th>{t("ticketReturn.product")}</th>
                    <th>{t("ticketReturn.available")}</th>
                    <th>{t("ticketReturn.price")}</th>
                    <th>{t("ticketReturn.quantity")}</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleLines.map((option) => {
                    const selection = selected(option.lineId);
                    const serials = availableSerialNumbers(option);
                    const key = `${option.giftReceiptLineId ?? "ticket"}-${option.lineId}`;
                    return <Fragment key={key}>
                      <tr className={selection ? "is-selected" : undefined}>
                        <td className="ticket-return-select-cell">
                          <input
                            type="checkbox"
                            aria-label={option.name}
                            checked={Boolean(selection)}
                            onChange={(event) => toggleLine(option, event.currentTarget.checked)}
                          />
                        </td>
                        <td className="ticket-return-product-cell">
                          <strong>{option.name}</strong>
                          <small>{option.code}</small>
                          {quantityInCart(option) > 0 && <small>{t("ticketReturn.inCart")}: {formatProductQuantity(quantityInCart(option), option.productType, locale)}</small>}
                        </td>
                        <td className="ticket-return-number-cell">
                          {formatProductQuantity(availableQuantity(option), option.productType, locale)}
                        </td>
                        <td className="ticket-return-number-cell">
                          <strong>{money(option.unitPrice, locale)}</strong>
                          <small>/{t("ticketReturn.unit")}</small>
                        </td>
                        <td className="ticket-return-quantity-cell">
                          {selection && serials.length === 0 ? <input
                            aria-label={`${t("ticketReturn.quantity")} · ${option.name}`}
                            type="number"
                            min={productQuantityStep(option.productType)}
                            max={availableQuantity(option)}
                            step={productQuantityStep(option.productType)}
                            value={selection.quantity}
                            onChange={(event) => updateQuantity(option, event.currentTarget.value)}
                          /> : selection ? <strong>{selection.serialNumbers.length}</strong> : null}
                        </td>
                      </tr>
                      {selection && serials.length > 0 && <tr className="ticket-return-serial-row">
                        <td colSpan={5}>
                          <div className="ticket-return-serials">
                            {serials.map((serial) => <label key={serial}>
                              <input type="checkbox" checked={selection.serialNumbers.includes(serial)} onChange={(event) => toggleSerial(option, serial, event.currentTarget.checked)} />
                              <span>S/N: {serial}</span>
                            </label>)}
                          </div>
                        </td>
                      </tr>}
                    </Fragment>;
                  })}
                </tbody>
              </table>
            </div>
          </>}

          {(message || valuationError || error) && <div className="ticket-return-feedback">
            {message && <p className="ticket-management-success" role="status">{message}</p>}
            {valuationError && <p className="sale-action-error" role="alert">{valuationError}</p>}
            {error && <p className="sale-action-error" role="alert">{error}</p>}
          </div>}
        </div>
        <footer className="sale-action-buttons ticket-return-footer">
          <button type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</button>
          {preview && <button type="button" className="primary" disabled={!validSelection() || !valuation || valuationBusy || busy} onClick={addSelectedToCart}>{t("ticketReturn.addToCart")}</button>}
        </footer>
      </section>
    </div>
  );
}

function money(value: number | string, locale: LocaleCode) {
  return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(Number(value));
}

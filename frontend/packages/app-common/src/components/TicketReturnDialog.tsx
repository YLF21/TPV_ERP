import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type ReturnLineOption = {
  lineId: string;
  giftReceiptLineId?: string | null;
  productId: string;
  code: string;
  name: string;
  lineType: "PRODUCT";
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

type Props = {
  token?: string;
  locale: LocaleCode;
  onClose: () => void;
  onAddToCart: (lines: ReturnCartLine[]) => void;
};

export function TicketReturnDialog({ token, locale, onClose, onAddToCart }: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const [identifier, setIdentifier] = useState("");
  const [preview, setPreview] = useState<ReturnPreview | null>(null);
  const [selections, setSelections] = useState<LineSelection[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [valuation, setValuation] = useState<ReturnValuation | null>(null);
  const [valuationBusy, setValuationBusy] = useState(false);
  const [valuationError, setValuationError] = useState("");
  const valuationRequest = useRef(0);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

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

  async function search() {
    const normalized = identifier.trim();
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
      setSelections([]);
      setValuation(null);
      setValuationError("");
      if (result.lines.length === 0) setMessage(t("ticketReturn.noAvailableLines"));
    } catch (reason) {
      setPreview(null);
      setSelections([]);
      setValuation(null);
      setError(reason instanceof Error ? reason.message : t("ticketReturn.searchError"));
    } finally {
      setBusy(false);
    }
  }

  function selected(lineId: string) {
    return selections.find((line) => line.lineId === lineId);
  }

  function fullSelection(option: ReturnLineOption): LineSelection {
    const serialNumbers = option.refundableSerialNumbers ?? [];
    return {
      lineId: option.lineId,
      quantity: String(option.refundableQuantity),
      serialNumbers,
    };
  }

  function toggleLine(option: ReturnLineOption, checked: boolean) {
    setSelections((current) => checked
      ? [...current.filter((line) => line.lineId !== option.lineId), fullSelection(option)]
      : current.filter((line) => line.lineId !== option.lineId));
  }

  function selectAll() {
    if (!preview) return;
    setSelections(preview.lines.map(fullSelection));
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
      ...(serialNumbers.length ? [{
        lineId: option.lineId,
        quantity: String(serialNumbers.length),
        serialNumbers,
      }] : []),
    ]);
  }

  function validSelection() {
    if (!preview || selections.length === 0) return false;
    return selections.every((selection) => {
      const option = preview.lines.find((line) => line.lineId === selection.lineId);
      const quantity = Number(selection.quantity);
      if (!option || !Number.isFinite(quantity) || quantity <= 0
        || quantity > Number(option.refundableQuantity)) return false;
      const serials = option.refundableSerialNumbers ?? [];
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
    <div className="sale-action-overlay" role="presentation">
      <section ref={dialogRef} className="sale-action-dialog wide ticket-return-dialog" role="dialog" aria-modal="true" aria-labelledby="ticket-return-title" aria-busy={busy}>
        <header>
          <div>
            <h2 id="ticket-return-title">{t("ticketReturn.title")}</h2>
            <p>{t("ticketReturn.cartDescription")}</p>
          </div>
          <button type="button" aria-label={t("common.close")} disabled={busy} onClick={onClose}>×</button>
        </header>

        <div className="ticket-return-search">
          <label>
            <span>{t("ticketReturn.ticketOrGiftCode")}</span>
            <input autoFocus autoComplete="off" value={identifier} onChange={(event) => setIdentifier(event.currentTarget.value)} onKeyDown={(event) => {
              if (event.key === "Enter") { event.preventDefault(); void search(); }
            }} />
          </label>
          <button type="button" className="primary" disabled={!identifier.trim() || busy} onClick={() => void search()}>{t("ticketReturn.search")}</button>
        </div>

        {preview && <>
          <div className="ticket-return-summary">
            <span><small>{preview.sourceType === "GIFT_RECEIPT" ? t("ticketReturn.giftReceipt") : t("ticketReturn.ticket")}</small><strong>{preview.sourceCode}</strong></span>
            <span><small>{t("ticketReturn.date")}</small><strong>{new Date(`${preview.date}T00:00:00`).toLocaleDateString(locale)}</strong></span>
            <span><small>{t("ticketReturn.selectedTotal")}</small><strong>{valuationBusy
              ? "..."
              : valuation
                ? money(valuation.refundableAmount, locale)
                : "-"}</strong></span>
          </div>
          <div className="ticket-return-selection-tools">
            <button type="button" className="primary" disabled={preview.lines.length === 0 || busy} onClick={selectAll}>{t("ticketReturn.selectAll")}</button>
            <button type="button" disabled={selections.length === 0 || busy} onClick={() => setSelections([])}>{t("ticketReturn.clearSelection")}</button>
          </div>
          <div className="ticket-return-lines">
            {preview.lines.map((option) => {
              const selection = selected(option.lineId);
              const serials = option.refundableSerialNumbers ?? [];
              return <article key={`${option.giftReceiptLineId ?? "ticket"}-${option.lineId}`}>
                <div className="ticket-return-line-heading">
                  <label>
                    <input type="checkbox" checked={Boolean(selection)} onChange={(event) => toggleLine(option, event.currentTarget.checked)} />
                    <span><strong>{option.name}</strong><small>{option.code}</small></span>
                  </label>
                  <span>{t("ticketReturn.available")}: {String(option.refundableQuantity)} · {money(option.unitPrice, locale)}/{t("ticketReturn.unit")}</span>
                </div>
                {selection && serials.length > 0 ? <div className="ticket-return-serials">
                  {serials.map((serial) => <label key={serial}>
                    <input type="checkbox" checked={selection.serialNumbers.includes(serial)} onChange={(event) => toggleSerial(option, serial, event.currentTarget.checked)} />
                    <span>S/N: {serial}</span>
                  </label>)}
                </div> : selection ? <label className="ticket-return-quantity">
                  <span>{t("ticketReturn.quantity")}</span>
                  <input type="number" min="0.001" max={Number(option.refundableQuantity)} step="0.001" value={selection.quantity} onChange={(event) => updateQuantity(option, event.currentTarget.value)} />
                </label> : null}
              </article>;
            })}
          </div>
        </>}

        {message && <p className="ticket-management-success" role="status">{message}</p>}
        {valuationError && <p className="sale-action-error" role="alert">{valuationError}</p>}
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <footer className="sale-action-buttons">
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

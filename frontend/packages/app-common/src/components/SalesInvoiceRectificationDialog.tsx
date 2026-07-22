import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { LocaleCode } from "../types";
import { ErpSelect, type ErpSelectOption } from "./ErpSelect";
import "./SalesInvoiceRectificationDialog.css";

const reasons = [
  "GOODS_RETURN",
  "POST_SALE_DISCOUNT",
  "POST_SALE_PRICE_CHANGE",
  "OPERATION_CANCELLATION",
  "LEGAL_OR_TAX_ERROR",
  "OTHER"
] as const;

type RectificationReason = typeof reasons[number];
type DialogStep = "edit" | "review" | "draft" | "confirmed";

type SourceLine = {
  id: string;
  type: "PRODUCT" | "PROMOTION" | "PROMOTIONAL_COUPON";
  code: string;
  name: string;
  originalQuantity: number | string;
  availableStockQuantity: number | string;
  unitPrice: number | string;
  discount: number | string;
  taxesIncluded: boolean;
  taxRegime: string;
  taxPercentage: number | string;
  base: number | string;
  tax: number | string;
  total: number | string;
};

type RectificationSource = {
  id: string;
  type: "FACTURA_VENTA" | "RECTIFICATIVA_VENTA";
  status: string;
  number: string;
  issueDate: string;
  customerId: string;
  warehouseId: string;
  globalDiscount: number | string;
  base: number | string;
  tax: number | string;
  total: number | string;
  lines: SourceLine[];
};

type RectificationDocument = {
  id: string;
  estado: string;
  numero?: string | null;
  base: number | string;
  impuesto: number | string;
  total: number | string;
};

type RectificationView = {
  document: RectificationDocument;
  original: RectificationSource;
  fiscalType: "R1" | "R4";
  method: "I";
  reason: RectificationReason;
  detail: string;
  affectsStock: boolean;
  lines: Array<{
    id: string;
    originalLineId: string;
    type: SourceLine["type"];
    code: string;
    name: string;
    quantity: number | string;
    unitPrice: number | string;
    base: number | string;
    tax: number | string;
    total: number | string;
  }>;
};

type EditableLine = {
  originalLineId: string;
  quantity: string;
  unitPrice: string;
};

type Props = {
  token: string;
  locale: LocaleCode;
  documentId: string;
  continueDraft: boolean;
  canConfirm: boolean;
  t: (key: string) => string;
  onClose: () => void;
  onChanged: () => void;
};

function affectsStock(reason: RectificationReason) {
  return reason === "GOODS_RETURN" || reason === "OPERATION_CANCELLATION";
}

function formatAmount(value: number | string | undefined, locale: LocaleCode) {
  return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" })
    .format(Number(value ?? 0));
}

function interpolate(value: string, variables: Record<string, string>) {
  return Object.entries(variables).reduce(
    (result, [key, replacement]) => result.replaceAll(`{${key}}`, replacement),
    value
  );
}

function initialLines(source: RectificationSource): EditableLine[] {
  return source.lines.map((line) => ({
    originalLineId: line.id,
    quantity: "0",
    unitPrice: Number(line.unitPrice).toFixed(2)
  }));
}

function savedLines(view: RectificationView): EditableLine[] {
  const saved = new Map(view.lines.map((line) => [line.originalLineId, line]));
  return view.original.lines.map((sourceLine) => {
    const line = saved.get(sourceLine.id);
    if (!line) return {
      originalLineId: sourceLine.id,
      quantity: "0",
      unitPrice: Number(sourceLine.unitPrice).toFixed(2)
    };
    if (sourceLine.type !== "PRODUCT") {
      const sign = view.affectsStock ? -1 : Math.sign(Number(line.unitPrice));
      return {
        originalLineId: sourceLine.id,
        quantity: String(sign || 1),
        unitPrice: Math.abs(Number(line.unitPrice)).toFixed(2)
      };
    }
    return {
      originalLineId: sourceLine.id,
      quantity: String(line.quantity),
      unitPrice: Math.abs(Number(line.unitPrice)).toFixed(2)
    };
  });
}

export function SalesInvoiceRectificationDialog({
  token,
  locale,
  documentId,
  continueDraft,
  canConfirm,
  t,
  onClose,
  onChanged
}: Props) {
  const [step, setStep] = useState<DialogStep>("edit");
  const [source, setSource] = useState<RectificationSource | null>(null);
  const [reason, setReason] = useState<RectificationReason>("GOODS_RETURN");
  const [detail, setDetail] = useState("");
  const [lines, setLines] = useState<EditableLine[]>([]);
  const [preview, setPreview] = useState<RectificationView | null>(null);
  const [persisted, setPersisted] = useState<RectificationView | null>(null);
  const [draftId, setDraftId] = useState<string | null>(continueDraft ? documentId : null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const reasonOptions = useMemo<ErpSelectOption[]>(() => reasons.map((value) => ({
    value,
    label: t(`rectification.reason.${value}`)
  })), [t]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    const path = continueDraft
      ? `/invoices/rectifications/${encodeURIComponent(documentId)}`
      : `/invoices/${encodeURIComponent(documentId)}/rectification-source`;
    void apiRequest<RectificationView | RectificationSource>(path, { token })
      .then((value) => {
        if (!active) return;
        if (continueDraft) {
          const view = value as RectificationView;
          setPersisted(view);
          setSource(view.original);
          setReason(view.reason);
          setDetail(view.detail);
          setLines(savedLines(view));
          setStep(view.document.estado === "BORRADOR" ? "edit" : "confirmed");
        } else {
          const original = value as RectificationSource;
          setSource(original);
          setLines(initialLines(original));
        }
      })
      .catch((failure) => {
        if (active) setError(failure instanceof Error ? failure.message : t("rectification.error.load"));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [continueDraft, documentId, t, token]);

  function selectReason(value: string) {
    if (!source || !reasons.includes(value as RectificationReason)) return;
    const next = value as RectificationReason;
    setReason(next);
    setPreview(null);
    if (next === "OPERATION_CANCELLATION") {
      setLines(source.lines.map((line) => ({
        originalLineId: line.id,
        quantity: Number(line.availableStockQuantity) > 0
          ? String(-Number(line.availableStockQuantity)) : "0",
        unitPrice: Math.abs(Number(line.unitPrice)).toFixed(2)
      })));
      return;
    }
    if (next === "GOODS_RETURN") {
      setLines(initialLines(source));
      return;
    }
    setLines(source.lines.map((line) => ({
      originalLineId: line.id,
      quantity: "0",
      unitPrice: "0.00"
    })));
  }

  function updateLine(originalLineId: string, field: "quantity" | "unitPrice", value: string) {
    setPreview(null);
    setLines((current) => current.map((line) => line.originalLineId === originalLineId
      ? { ...line, [field]: value }
      : line));
  }

  function requestBody() {
    return {
      reason,
      detail: detail.trim(),
      lines: lines
        .filter((line) => Number(line.quantity) !== 0)
        .map((line) => ({
          originalLineId: line.originalLineId,
          quantity: line.quantity,
          unitPrice: line.unitPrice
        }))
    };
  }

  async function review() {
    if (!source || busy) return;
    if (detail.trim().length < 10) {
      setError(t("rectification.error.detail"));
      return;
    }
    const body = requestBody();
    if (body.lines.length === 0) {
      setError(t("rectification.error.lines"));
      return;
    }
    setBusy(true);
    setError("");
    try {
      const value = await apiRequest<RectificationView>(
        `/invoices/${encodeURIComponent(source.id)}/rectifications/preview`,
        { token, method: "POST", body }
      );
      setPreview(value);
      setStep("review");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t("rectification.error.load"));
    } finally {
      setBusy(false);
    }
  }

  async function saveDraft() {
    if (!source || !preview || busy) return;
    setBusy(true);
    setError("");
    try {
      const editing = Boolean(draftId);
      const path = editing
        ? `/invoices/rectifications/${encodeURIComponent(draftId!)}`
        : `/invoices/${encodeURIComponent(source.id)}/rectifications`;
      const value = await apiRequest<RectificationView>(path, {
        token,
        method: editing ? "PUT" : "POST",
        body: requestBody()
      });
      setPersisted(value);
      setDraftId(value.document.id);
      setStep("draft");
      onChanged();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t("rectification.error.load"));
    } finally {
      setBusy(false);
    }
  }

  async function confirmDraft() {
    if (!draftId || busy || !canConfirm) return;
    setBusy(true);
    setError("");
    try {
      const value = await apiRequest<RectificationView>(
        `/invoices/rectifications/${encodeURIComponent(draftId)}/confirm`,
        { token, method: "POST" }
      );
      setPersisted(value);
      setStep("confirmed");
      onChanged();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t("rectification.error.load"));
    } finally {
      setBusy(false);
    }
  }

  const stock = affectsStock(reason);
  const display = preview ?? persisted;

  return (
    <div className="rectification-overlay" role="presentation">
      <section className="rectification-dialog" role="dialog" aria-modal="true" aria-labelledby="rectification-title">
        <header className="rectification-header">
          <div>
            <span>{t("rectification.method")}</span>
            <h2 id="rectification-title">{t("rectification.title")}</h2>
            <p>{t("rectification.subtitle")}</p>
          </div>
          <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
        </header>

        {source && <div className="rectification-context">
          <span><small>{t("rectification.original")}</small><strong>{source.number}</strong></span>
          <span><small>{source.issueDate}</small><strong>{formatAmount(source.total, locale)}</strong></span>
          <span className="rectification-method-badge">I</span>
          {display && <span className="rectification-fiscal-badge">{interpolate(t("rectification.fiscalType"), { type: display.fiscalType })}</span>}
        </div>}

        <div className="rectification-body">
          {loading && <p className="rectification-loading">{t("rectification.loading")}</p>}
          {!loading && error && <p className="rectification-error" role="alert">{error}</p>}

          {!loading && source && step === "edit" && <>
            <div className="rectification-form-head">
              <label>
                <span>{t("rectification.reason")}</span>
                <ErpSelect value={reason} options={reasonOptions} onChange={selectReason} aria-label={t("rectification.reason")} />
              </label>
              <label className="rectification-detail-field">
                <span>{t("rectification.detail")}</span>
                <textarea maxLength={500} value={detail} placeholder={t("rectification.detailPlaceholder")} onChange={(event) => setDetail(event.target.value)} />
                <small>{detail.trim().length}/500</small>
              </label>
            </div>
            <p className={stock ? "rectification-notice stock" : "rectification-notice economic"}>
              {t(stock ? "rectification.stockHint" : "rectification.economicHint")}
            </p>
            <section className="rectification-lines">
              <div className="rectification-section-heading">
                <h3>{t("rectification.lines")}</h3>
                <span>{t("rectification.negativeHint")}</span>
              </div>
              <div className="rectification-table-wrap">
                <table>
                  <thead><tr>
                    <th>{t("rectification.line")}</th>
                    <th>{t("rectification.originalQuantity")}</th>
                    <th>{t("rectification.available")}</th>
                    <th>{t("rectification.differenceQuantity")}</th>
                    <th>{t("rectification.unitDifference")}</th>
                  </tr></thead>
                  <tbody>{source.lines.map((line) => {
                    const editable = lines.find((value) => value.originalLineId === line.id);
                    return <tr key={line.id}>
                      <td><strong>{line.code}</strong><span>{line.name}</span></td>
                      <td>{line.originalQuantity}</td>
                      <td>{line.availableStockQuantity}</td>
                      <td><input aria-label={`${t("rectification.differenceQuantity")} ${line.name}`} type="number" step="0.001" value={editable?.quantity ?? "0"} disabled={reason === "OPERATION_CANCELLATION"} onChange={(event) => updateLine(line.id, "quantity", event.target.value)} /></td>
                      <td><input aria-label={`${t("rectification.unitDifference")} ${line.name}`} type="number" min="0" step="0.01" value={editable?.unitPrice ?? "0.00"} disabled={stock} onChange={(event) => updateLine(line.id, "unitPrice", event.target.value)} /></td>
                    </tr>;
                  })}</tbody>
                </table>
              </div>
            </section>
          </>}

          {!loading && preview && step === "review" && <Summary view={preview} locale={locale} t={t} title={t("rectification.reviewTitle")} hint={t("rectification.reviewHint")} />}
          {!loading && persisted && step === "draft" && <Summary view={persisted} locale={locale} t={t} title={t("rectification.draftTitle")} hint={t("rectification.draftHint")} showId />}
          {!loading && persisted && step === "confirmed" && <div className="rectification-confirmed">
            <span aria-hidden="true">✓</span>
            <h3>{t("rectification.confirmed")}</h3>
            <strong>{persisted.document.numero}</strong>
            <small>{formatAmount(persisted.document.total, locale)}</small>
          </div>}
        </div>

        {!loading && source && <footer className="rectification-footer">
          <button type="button" onClick={onClose}>{step === "confirmed" ? t("rectification.close") : t("common.cancel")}</button>
          {step === "edit" && <button className="primary" type="button" disabled={busy} onClick={() => void review()}>{busy ? t("rectification.saving") : t("rectification.preview")}</button>}
          {step === "review" && <>
            <button type="button" onClick={() => setStep("edit")}>{t("rectification.backToEdit")}</button>
            <button className="primary" type="button" disabled={busy} onClick={() => void saveDraft()}>{busy ? t("rectification.saving") : t(draftId ? "rectification.updateDraft" : "rectification.createDraft")}</button>
          </>}
          {step === "draft" && <>
            <button type="button" disabled={busy} onClick={() => setStep("edit")}>{t("rectification.backToEdit")}</button>
            <button className="primary danger-confirm" type="button" disabled={busy || !canConfirm} onClick={() => void confirmDraft()}>{busy ? t("rectification.saving") : t("rectification.confirm")}</button>
          </>}
        </footer>}
      </section>
    </div>
  );
}

function Summary({
  view,
  locale,
  t,
  title,
  hint,
  showId = false
}: {
  view: RectificationView;
  locale: LocaleCode;
  t: (key: string) => string;
  title: string;
  hint: string;
  showId?: boolean;
}) {
  return <section className="rectification-review">
    <header><div><h3>{title}</h3><p>{hint}</p></div><span>{view.fiscalType} · {view.method}</span></header>
    <div className="rectification-totals">
      <span><small>{t("rectification.total.base")}</small><strong>{formatAmount(view.document.base, locale)}</strong></span>
      <span><small>{t("rectification.total.tax")}</small><strong>{formatAmount(view.document.impuesto, locale)}</strong></span>
      <span className="total"><small>{t("rectification.total.total")}</small><strong>{formatAmount(view.document.total, locale)}</strong></span>
    </div>
    <table><thead><tr><th>{t("rectification.line")}</th><th>{t("rectification.differenceQuantity")}</th><th>{t("rectification.unitDifference")}</th><th>{t("rectification.lineTotal")}</th></tr></thead>
      <tbody>{view.lines.map((line) => <tr key={line.id}><td><strong>{line.code}</strong><span>{line.name}</span></td><td>{line.quantity}</td><td>{formatAmount(line.unitPrice, locale)}</td><td>{formatAmount(line.total, locale)}</td></tr>)}</tbody>
    </table>
    <div className="rectification-reason-summary"><strong>{t(`rectification.reason.${view.reason}`)}</strong><p>{view.detail}</p></div>
    {showId && <small className="rectification-draft-id">{t("rectification.documentId")}: {view.document.id}</small>}
  </section>;
}

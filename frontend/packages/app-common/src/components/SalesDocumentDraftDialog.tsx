import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

export type SalesDocumentDraftSummary = {
  id: string;
  version: number;
  type: "FACTURA_VENTA" | "ALBARAN_VENTA";
  date: string;
  customerId: string;
  customerName?: string | null;
  total: number | string;
  createdAt: string;
};

export type SalesDocumentDraftDetail = SalesDocumentDraftSummary & {
  dueDate: string;
  warehouseId: string;
  globalDiscount: number | string;
  internalComment?: string | null;
  lines: Array<{
    id: string;
    productId: string;
    position: number;
    quantity: number | string;
    code?: string | null;
    barcode?: string | null;
    name?: string | null;
    rate?: string | null;
    unitPrice: number | string;
    discount: number | string;
    taxesIncluded: boolean;
    taxRegime: "IVA" | "IGIC";
    taxPercentage: number | string;
    serialNumbers?: string[];
    temporaryNameOverride: boolean;
    temporaryPriceOverride: boolean;
  }>;
};

type Props = {
  locale: LocaleCode;
  token?: string;
  hasCurrentWork: boolean;
  onClose: () => void;
  onImport: (draft: SalesDocumentDraftDetail) => void | Promise<void>;
};

function formatMoney(value: number | string, locale: LocaleCode) {
  return new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale, {
    style: "currency",
    currency: "EUR",
  }).format(Number(value ?? 0));
}

export function SalesDocumentDraftDialog({
  locale,
  token,
  hasCurrentWork,
  onClose,
  onImport,
}: Props) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const [drafts, setDrafts] = useState<SalesDocumentDraftSummary[]>([]);
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState("");
  const [replaceConfirmation, setReplaceConfirmation] = useState(false);

  useEffect(() => {
    let active = true;
    apiRequest<SalesDocumentDraftSummary[]>("/pos/sales-document-drafts", { token })
      .then((values) => {
        if (!active) return;
        setDrafts(values);
        setSelectedId(values[0]?.id ?? "");
      })
      .catch((failure) => {
        if (active) setError(failure instanceof Error
          ? failure.message : t("salesDocument.drafts.loadError"));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [token]);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as ModalFocusRoot, document);
    searchRef.current?.focus();
    return deactivate;
  }, []);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase(locale === "zh" ? "zh-CN" : locale);
    if (!normalized) return drafts;
    return drafts.filter((draft) => [
      draft.customerName ?? "",
      draft.date,
      draft.type === "FACTURA_VENTA"
        ? t("receivables.type.invoice") : t("receivables.type.deliveryNote"),
      String(draft.total),
    ].some((value) => value.toLocaleLowerCase(
      locale === "zh" ? "zh-CN" : locale,
    ).includes(normalized)));
  }, [drafts, locale, query]);

  useEffect(() => {
    if (!filtered.some((draft) => draft.id === selectedId)) {
      setSelectedId(filtered[0]?.id ?? "");
    }
  }, [filtered, selectedId]);

  function moveSelection(offset: number) {
    if (filtered.length === 0) return;
    const current = Math.max(0, filtered.findIndex((draft) => draft.id === selectedId));
    const next = filtered[Math.min(Math.max(current + offset, 0), filtered.length - 1)];
    setSelectedId(next.id);
    queueMicrotask(() => document.getElementById(`sales-document-draft-${next.id}`)
      ?.scrollIntoView?.({ block: "nearest" }));
  }

  async function importSelected(requestedId = selectedId) {
    if (!requestedId || importing) return;
    if (hasCurrentWork && !replaceConfirmation) {
      setReplaceConfirmation(true);
      return;
    }
    setImporting(true);
    setError("");
    try {
      const detail = await apiRequest<SalesDocumentDraftDetail>(
        `/pos/sales-document-drafts/${encodeURIComponent(requestedId)}`,
        { token },
      );
      await onImport(detail);
    } catch (failure) {
      setError(failure instanceof Error
        ? failure.message : t("salesDocument.drafts.importError"));
      setImporting(false);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      moveSelection(event.key === "ArrowUp" ? -1 : 1);
      return;
    }
    if (event.key === "Enter" && selectedId) {
      event.preventDefault();
      void importSelected();
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="sales-document-draft-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sales-document-draft-title"
        onKeyDown={handleKeyDown}
      >
        <header>
          <div>
            <span>{t("salesDocument.drafts.eyebrow")}</span>
            <h2 id="sales-document-draft-title">{t("salesDocument.drafts.title")}</h2>
          </div>
          <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
        </header>
        <label className="sales-document-draft-search">
          <span>{t("salesDocument.drafts.search")}</span>
          <input
            ref={searchRef}
            value={query}
            placeholder={t("salesDocument.drafts.searchPlaceholder")}
            onChange={(event) => {
              setQuery(event.target.value);
              setReplaceConfirmation(false);
            }}
          />
        </label>
        <div className="sales-document-draft-table-head" aria-hidden="true">
          <span>{t("salesDocument.drafts.date")}</span>
          <span>{t("salesDocument.drafts.type")}</span>
          <span>{t("salesDocument.drafts.customer")}</span>
          <span>{t("salesDocument.drafts.total")}</span>
        </div>
        <div className="sales-document-draft-list" role="listbox">
          {loading && <p role="status">{t("salesDocument.drafts.loading")}</p>}
          {!loading && filtered.map((draft) => (
            <button
              id={`sales-document-draft-${draft.id}`}
              key={draft.id}
              type="button"
              role="option"
              aria-selected={selectedId === draft.id}
              className={selectedId === draft.id ? "selected" : undefined}
              onFocus={() => setSelectedId(draft.id)}
              onClick={() => {
                setSelectedId(draft.id);
                setReplaceConfirmation(false);
              }}
              onDoubleClick={() => void importSelected(draft.id)}
            >
              <span>{draft.date}</span>
              <strong>{t(draft.type === "FACTURA_VENTA"
                ? "receivables.type.invoice" : "receivables.type.deliveryNote")}</strong>
              <span>{draft.customerName ?? t("salesDocument.drafts.unnamedCustomer")}</span>
              <b>{formatMoney(draft.total, locale)}</b>
            </button>
          ))}
          {!loading && filtered.length === 0 && (
            <p role="status">{t("salesDocument.drafts.empty")}</p>
          )}
        </div>
        {replaceConfirmation && (
          <p className="sales-document-draft-warning" role="alert">
            {t("salesDocument.drafts.replaceWarning")}
          </p>
        )}
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <footer>
          <div>
            <span><kbd>↑</kbd><kbd>↓</kbd>{t("sale.searchDialog.navigate")}</span>
            <span><kbd>Enter</kbd>{t("salesDocument.drafts.import")}</span>
            <span><kbd>Esc</kbd>{t("common.close")}</span>
          </div>
          <button type="button" onClick={onClose}>{t("common.cancel")}</button>
          <button
            type="button"
            className="primary"
            disabled={!selectedId || importing}
            onClick={() => void importSelected()}
          >
            {importing
              ? t("salesDocument.drafts.importing")
              : replaceConfirmation
                ? t("salesDocument.drafts.replace")
                : t("salesDocument.drafts.import")}
          </button>
        </footer>
      </section>
    </div>
  );
}

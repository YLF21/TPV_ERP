import { useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiProblemCode, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

export type RetirementEntityPath = "products" | "customers" | "suppliers" | "sales-representatives";
export type RetirementOutcome = "HARD_DELETED" | "DEACTIVATED" | "ALREADY_INACTIVE";

export type RetirementImpact = {
  id: string;
  version: number;
  currentState: string;
  outcomeIfConfirmed: RetirementOutcome;
  reasonCodes: string[];
  executable: boolean;
};

export type RetirementResult = {
  id: string;
  outcome: RetirementOutcome;
  reasonCodes: string[];
};

type SafeRetirementDialogProps = {
  open: boolean;
  entityPath: RetirementEntityPath;
  entityLabel: string;
  entityId: string;
  locale: LocaleCode;
  token?: string;
  onClose: () => void;
  onRetired: (result: RetirementResult) => void | Promise<void>;
};

export function retirementImpactPath(entityPath: RetirementEntityPath, entityId: string): string {
  return `/${entityPath}/management/${encodeURIComponent(entityId)}/retirement-impact`;
}

export function retirementCommandPath(entityPath: RetirementEntityPath, entityId: string): string {
  return `/${entityPath}/management/${encodeURIComponent(entityId)}/retire`;
}

export function retirementOutcomeMessageKey(outcome: RetirementOutcome): string {
  return `safeManagement.retirement.outcome.${outcome}`;
}

const retirementReasonCodes = new Set([
  "PROTECTED_SYSTEM_PRODUCT",
  "HAS_STOCK",
  "HAS_STOCK_MOVEMENTS",
  "HAS_DOCUMENTS",
  "HAS_WAREHOUSE_MOVEMENTS",
  "HAS_PHYSICAL_COUNTS",
  "HAS_EAN_CODES",
  "HAS_PRICE_AUTHORIZATIONS",
  "HAS_SUPPLIER_LINKS",
  "HAS_PROMOTIONS",
  "HAS_PDA_REFERENCES",
  "HAS_IMPORT_REFERENCES",
  "HAS_BULK_EDIT_REFERENCES",
  "HAS_IMAGE",
  "HAS_MEMBER_HISTORY",
  "HAS_BALANCE_HISTORY",
  "HAS_PARKED_SALES",
  "HAS_VOUCHERS",
  "HAS_PURCHASE_DOCUMENTS",
  "HAS_WAREHOUSE_INPUTS",
  "HAS_PRODUCT_LINKS",
  "HAS_REPRESENTATIVE_LINKS"
]);

export function retirementReasonMessageKey(reason: string): string {
  return retirementReasonCodes.has(reason)
    ? `safeManagement.retirement.reason.${reason}`
    : "safeManagement.retirement.reason.HAS_REFERENCES";
}

export function retirementErrorMessage(
  error: unknown,
  fallback: string,
  stale: string,
  denied = fallback
): string {
  if (error instanceof ApiError && (error.status === 409 || apiProblemCode(error) === "STALE_STATE")) {
    return stale;
  }
  if (error instanceof ApiError && error.status === 403) return denied;
  return fallback;
}

export function SafeRetirementDialog({
  open,
  entityPath,
  entityLabel,
  entityId,
  locale,
  token,
  onClose,
  onRetired
}: SafeRetirementDialogProps) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const [impact, setImpact] = useState<RetirementImpact | null>(null);
  const [retirementResult, setRetirementResult] = useState<RetirementResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) {
      setImpact(null);
      setRetirementResult(null);
      setError("");
      setLoading(false);
      setSubmitting(false);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setImpact(null);
    setRetirementResult(null);
    setError("");
    void apiRequest<RetirementImpact>(retirementImpactPath(entityPath, entityId), {
      token,
      signal: controller.signal
    }).then((response) => {
      setImpact(response);
    }).catch((requestError: unknown) => {
      if (controller.signal.aborted) return;
      setError(retirementErrorMessage(
        requestError,
        t("safeManagement.retirement.loadError"),
        t("safeManagement.retirement.stale"),
        t("safeManagement.retirement.denied")
      ));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });

    return () => controller.abort();
  }, [entityId, entityPath, open, t, token]);

  useEffect(() => {
    if (!open) return;
    closeButtonRef.current?.focus();
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape" || submitting) return;
      event.preventDefault();
      onClose();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose, open, submitting]);

  async function confirmRetirement() {
    if (submitting) return;

    if (retirementResult) {
      setSubmitting(true);
      setError("");
      try {
        await onRetired(retirementResult);
      } catch {
        setError(t("safeManagement.retirement.reloadError"));
      } finally {
        setSubmitting(false);
      }
      return;
    }

    if (!impact) return;
    setSubmitting(true);
    setError("");
    try {
      const result = await apiRequest<RetirementResult>(retirementCommandPath(entityPath, entityId), {
        method: "POST",
        token,
        body: { expectedVersion: impact.version }
      });
      setRetirementResult(result);
      try {
        await onRetired(result);
      } catch {
        setError(t("safeManagement.retirement.reloadError"));
      }
    } catch (requestError) {
      setError(retirementErrorMessage(
        requestError,
        t("safeManagement.retirement.saveError"),
        t("safeManagement.retirement.stale"),
        t("safeManagement.retirement.denied")
      ));
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  const titleId = `safe-retirement-title-${entityId}`;
  const descriptionId = `safe-retirement-description-${entityId}`;
  return (
    <div className="filter-overlay safe-retirement-overlay" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId}>
      <section className="filter-dialog safe-retirement-dialog">
        <header className="filter-header">
          <div>
            <h2 id={titleId}>{t("safeManagement.retirement.title")}</h2>
            <span>{entityLabel}</span>
          </div>
          <button ref={closeButtonRef} type="button" onClick={onClose} disabled={submitting}>
            {t("common.close")}
          </button>
        </header>

        <div className="safe-retirement-content" id={descriptionId}>
          {loading && <p role="status">{t("common.loading")}</p>}
          {!loading && retirementResult && <p className="product-create-status" role="status">{t("safeManagement.retirement.success")}</p>}
          {!loading && error && <p className="product-create-status error" role="alert">{error}</p>}
          {!loading && impact && !retirementResult && (
            <>
              <p>{t("safeManagement.retirement.explanation")}</p>
              <div className={`safe-retirement-outcome safe-retirement-outcome--${impact.outcomeIfConfirmed.toLocaleLowerCase()}`}>
                <strong>{t(retirementOutcomeMessageKey(impact.outcomeIfConfirmed))}</strong>
                <span>{t(`safeManagement.retirement.outcomeDetail.${impact.outcomeIfConfirmed}`)}</span>
              </div>
              {impact.reasonCodes.length > 0 && (
                <ul className="safe-retirement-reasons">
                  {impact.reasonCodes.map((reason) => (
                    <li key={reason}>{t(retirementReasonMessageKey(reason))}</li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>

        <footer className="filter-actions">
          <button type="button" onClick={onClose} disabled={submitting}>{t("common.cancel")}</button>
          <button
            type="button"
            className="safe-retirement-confirm"
            onClick={() => void confirmRetirement()}
            disabled={loading || submitting || (!retirementResult && (!impact || !impact.executable))}
          >
            {submitting
              ? t("safeManagement.retirement.saving")
              : retirementResult
                ? t("safeManagement.retirement.retryReload")
                : t("safeManagement.retirement.confirm")}
          </button>
        </footer>
      </section>
    </div>
  );
}

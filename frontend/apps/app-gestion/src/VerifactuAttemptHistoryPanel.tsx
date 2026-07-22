import { useEffect, useRef, useState } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import {
  loadVerifactuAdminAttempts,
  type VerifactuAdminAttemptPage
} from "./verifactuManagementApi";
import {
  formatVerifactuDateTime,
  verifactuStatusLabel,
  type VerifactuTranslator
} from "./verifactuPresentation";

export type VerifactuAttemptTarget = {
  recordId: string;
  documentNumber: string;
};

const emptyPage: VerifactuAdminAttemptPage = {
  items: [], page: 0, size: 10, totalElements: 0, totalPages: 0
};

export function VerifactuAttemptHistoryPanel({
  target,
  token,
  locale,
  t,
  onClose
}: {
  target: VerifactuAttemptTarget | null;
  token?: string;
  locale: LocaleCode;
  t: VerifactuTranslator;
  onClose: () => void;
}) {
  const [pageIndex, setPageIndex] = useState(0);
  const [page, setPage] = useState(emptyPage);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const request = useRef(0);

  useEffect(() => {
    setPageIndex(0);
    setPage(emptyPage);
    setError(false);
  }, [target?.recordId]);

  useEffect(() => {
    if (!target) return;
    const requestId = ++request.current;
    setLoading(true);
    setError(false);
    void loadVerifactuAdminAttempts(target.recordId, pageIndex, 10, token)
      .then((next) => {
        if (requestId === request.current) setPage(next);
      })
      .catch(() => {
        if (requestId !== request.current) return;
        setPage(emptyPage);
        setError(true);
      })
      .finally(() => {
        if (requestId === request.current) setLoading(false);
      });
    return () => { request.current += 1; };
  }, [pageIndex, target, token]);

  if (!target) return null;

  return (
    <aside className="gestion-verifactu-attempts" aria-label={t("verifactu.management.attemptHistory")}>
      <header>
        <div>
          <span>{t("verifactu.management.attemptHistory")}</span>
          <h3>{target.documentNumber}</h3>
        </div>
        <button type="button" onClick={onClose}>{t("verifactu.management.close")}</button>
      </header>

      {loading && (
        <p className="gestion-verifactu-message" role="status" aria-live="polite">
          {t("verifactu.management.loadingAttempts")}
        </p>
      )}
      {!loading && error && (
        <p className="gestion-verifactu-message error" role="alert">
          {t("verifactu.management.attemptsError")}
        </p>
      )}
      {!loading && !error && page.items.length === 0 && (
        <p className="gestion-verifactu-message">{t("verifactu.management.emptyAttempts")}</p>
      )}
      {!error && page.items.length > 0 && (
        <div className="gestion-verifactu-attempt-list">
          {page.items.map((attempt) => (
            <article key={attempt.attemptId}>
              <div>
                <strong>{verifactuStatusLabel(attempt.status, t)}</strong>
                <time>{formatVerifactuDateTime(attempt.attemptedAt, locale)}</time>
              </div>
              <dl>
                <div>
                  <dt>{t("verifactu.management.errorCode")}</dt>
                  <dd>{attempt.errorCode || "—"}</dd>
                </div>
                <div>
                  <dt>{t("verifactu.management.technicalContent")}</dt>
                  <dd>{attempt.hasTechnicalDetail
                    ? t("verifactu.management.protected")
                    : t("verifactu.management.none")}</dd>
                </div>
              </dl>
            </article>
          ))}
        </div>
      )}

      <footer className="gestion-verifactu-pagination">
        <button
          type="button"
          onClick={() => setPageIndex((current) => Math.max(0, current - 1))}
          disabled={loading || pageIndex === 0}
        >
          {t("verifactu.management.previous")}
        </button>
        <span>{t("verifactu.management.page")} {page.page + 1} / {Math.max(1, page.totalPages)}</span>
        <button
          type="button"
          onClick={() => setPageIndex((current) => current + 1)}
          disabled={loading || page.totalPages === 0 || page.page + 1 >= page.totalPages}
        >
          {t("verifactu.management.next")}
        </button>
      </footer>
    </aside>
  );
}

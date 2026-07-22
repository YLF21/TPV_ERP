import { useEffect, useRef, useState } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import {
  loadVerifactuAdminDiagnostics,
  type VerifactuAdminDiagnostics
} from "./verifactuManagementApi";
import {
  formatVerifactuDateTime,
  verifactuEndpointLabel,
  verifactuStatusLabel,
  type VerifactuTranslator
} from "./verifactuPresentation";

export function VerifactuDiagnosticsView({
  locale,
  token,
  revision,
  t
}: {
  locale: LocaleCode;
  token?: string;
  revision: number;
  t: VerifactuTranslator;
}) {
  const [diagnostics, setDiagnostics] = useState<VerifactuAdminDiagnostics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const request = useRef(0);

  useEffect(() => {
    const requestId = ++request.current;
    setLoading(true);
    setError(false);
    void loadVerifactuAdminDiagnostics(token)
      .then((next) => {
        if (requestId === request.current) setDiagnostics(next);
      })
      .catch(() => {
        if (requestId !== request.current) return;
        setDiagnostics(null);
        setError(true);
      })
      .finally(() => {
        if (requestId === request.current) setLoading(false);
      });
    return () => { request.current += 1; };
  }, [revision, token]);

  if (loading && !diagnostics) {
    return <p className="gestion-verifactu-message" role="status">{t("verifactu.management.loadingDiagnostics")}</p>;
  }
  if (error || !diagnostics) {
    return <p className="gestion-verifactu-message error" role="alert">{t("verifactu.management.diagnosticsError")}</p>;
  }

  return (
    <div className="gestion-verifactu-diagnostics">
      <section className="gestion-verifactu-diagnostic-banner">
        <div>
          <span>{t("verifactu.management.passiveDiagnostic")}</span>
          <strong>{diagnostics.endpointConfigured
            ? t("verifactu.management.configurationAvailable")
            : t("verifactu.management.configurationUnavailable")}</strong>
        </div>
        <p>{t("verifactu.management.diagnosticDisclaimer")}</p>
      </section>

      <div className="gestion-verifactu-diagnostic-grid">
        <DiagnosticCard
          label={t("verifactu.management.environment")}
          value={verifactuEndpointLabel(diagnostics.endpointMode, t)}
          meta={diagnostics.endpointConfigured
            ? t("verifactu.management.endpointConfigured")
            : t("verifactu.management.endpointNotConfigured")}
        />
        <DiagnosticCard
          label={t("verifactu.management.worker")}
          value={diagnostics.workerEnabled
            ? t("verifactu.management.workerEnabled")
            : t("verifactu.management.workerDisabled")}
          meta={t("verifactu.management.workerConfigurationOnly")}
        />
        <DiagnosticCard
          label={t("verifactu.management.clock")}
          value={clockValue(diagnostics, t)}
          meta={`${t("verifactu.management.clockThreshold")}: ${diagnostics.clock.thresholdSeconds ?? "—"} s`}
        />
        <DiagnosticCard
          label={t("verifactu.management.lastRecordedAttempt")}
          value={diagnostics.lastAttempt
            ? formatVerifactuDateTime(diagnostics.lastAttempt.occurredAt, locale)
            : "—"}
          meta={diagnostics.lastAttempt
            ? verifactuStatusLabel(diagnostics.lastAttempt.status, t)
            : t("verifactu.management.noAttempts")}
        />
      </div>

      <p className="gestion-verifactu-observed-at">
        {t("verifactu.management.observedAt")}: {formatVerifactuDateTime(diagnostics.observedAt, locale)}
      </p>
    </div>
  );
}

function DiagnosticCard({ label, value, meta }: { label: string; value: string; meta: string }) {
  return (
    <article className="gestion-verifactu-diagnostic-card">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{meta}</small>
    </article>
  );
}

function clockValue(diagnostics: VerifactuAdminDiagnostics, t: VerifactuTranslator) {
  if (!diagnostics.clock.available) return t("verifactu.management.unavailable");
  if (diagnostics.clock.warning) return t("verifactu.management.clockWarning");
  return `${t("verifactu.management.clockCorrect")} · ${diagnostics.clock.driftSeconds ?? 0} s`;
}

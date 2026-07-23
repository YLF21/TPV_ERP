import { useEffect, useState } from "react";
import { ApiError } from "../api/client";
import {
  evaluateCompatibility,
  loadBackendCompatibility,
  type BackendCompatibility,
  type CompatibilityEvaluation
} from "../api/compatibility";
import { frontendVersion } from "../api/runtime";
import type { LocaleCode } from "../types";

type Props = { locale: LocaleCode; token?: string };
type State = { backend?: BackendCompatibility; evaluation?: CompatibilityEvaluation; error?: string };

const copy = {
  es: { title: "Versiones y compatibilidad", frontend: "Frontend", backend: "Backend", api: "API",
    compatible: "Compatible", incompatible: "Actualización necesaria", loading: "Comprobando compatibilidad…",
    failed: "No se pudo comprobar la compatibilidad", capabilities: "Capacidades de pago" },
  en: { title: "Versions and compatibility", frontend: "Frontend", backend: "Backend", api: "API",
    compatible: "Compatible", incompatible: "Update required", loading: "Checking compatibility…",
    failed: "Compatibility could not be checked", capabilities: "Payment capabilities" },
  zh: { title: "版本与兼容性", frontend: "前端", backend: "后端", api: "API",
    compatible: "兼容", incompatible: "需要更新", loading: "正在检查兼容性…",
    failed: "无法检查兼容性", capabilities: "支付功能" }
} as const;

export function SystemCompatibilityCard({ locale, token }: Props) {
  const [state, setState] = useState<State>({});
  useEffect(() => {
    let active = true;
    loadBackendCompatibility(token).then(backend => {
      if (active) setState({ backend, evaluation: evaluateCompatibility(backend) });
    }).catch(error => {
      if (!active) return;
      setState({ error: error instanceof ApiError && error.traceId
        ? `${copy[locale].failed} (Ref: ${error.traceId})`
        : copy[locale].failed });
    });
    return () => { active = false; };
  }, [locale, token]);

  const labels = copy[locale];
  return (
    <article
      className="settings-card settings-card-wide system-compatibility-card"
      aria-label={labels.title}
    >
      <h3>{labels.title}</h3>
      {!state.backend && !state.error && <p>{labels.loading}</p>}
      {state.error && <p role="alert">{state.error}</p>}
      {state.backend && state.evaluation && (
        <>
          <dl>
            <div><dt>{labels.frontend}</dt><dd>{frontendVersion}</dd></div>
            <div><dt>{labels.backend}</dt><dd>{state.backend.backendVersion}</dd></div>
            <div><dt>{labels.api}</dt><dd>{state.backend.apiVersion}</dd></div>
          </dl>
          <p role="status" className={state.evaluation.compatible ? "status-success" : "status-error"}>
            {state.evaluation.compatible ? labels.compatible : labels.incompatible}
          </p>
          <h4>{labels.capabilities}</h4>
          <ul className="system-compatibility-capabilities">
            {state.backend.capabilities.map(capability => <li key={capability}>{capability}</li>)}
          </ul>
        </>
      )}
    </article>
  );
}

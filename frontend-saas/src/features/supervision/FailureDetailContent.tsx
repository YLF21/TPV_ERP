import type { FailureRow } from "../../lib/workspace-api";
import type { Notice } from "../../shared/types";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { useFailureLabels } from "./failure-labels";

export function FailureOverview({ failure }: { failure: FailureRow }) {
  const f = useFailureLabels();
  const l = useWorkspaceLabels();
  const titleKey = failure.source === "LOCAL_APPLICATION"
    ? failure.module === "PRINTING" ? "problemPrinting" : "problemApplication"
    : failure.source === "LOCAL_SYNC" ? "problemSync" : "problemOther";
  return <div className="failure-overview">
    <div className="failure-problem">
      <span className="failure-eyebrow">{f("whatHappened")}</span>
      <h4>{f(titleKey)}</h4>
      <p>{f("detail_" + failure.source)}</p>
    </div>
    <div className="failure-overview-grid">
      <div><span>{f("affectedStore")}</span>
        <strong>{failure.storeName ?? l("central")}</strong>
        <small>{failure.companyName ?? l("central")}</small>
        <small>{f("installationReference")}: {failure.installationReference ?? f("unnamedInstallation")}</small>
      </div>
      <div><span>{l("status")}</span>
        <StatusPill status={f(failure.status)} tone={failure.status === "RESOLVED" ? "ok" : failure.status === "OPEN" ? "warning" : "muted"} />
        <small>{l("severity")}: {f(failure.severity)}</small>
        <small>{l("source")}: {f(failure.source)}</small>
      </div>
      <div><span>{l("lastSeen")}</span>
        <strong>{formatDate(failure.lastSeenAt)}</strong>
        <small>{l("occurrences")}: {failure.occurrences}</small>
        <small>{l("firstSeen")}: {formatDate(failure.firstSeenAt)}</small>
      </div>
    </div>
  </div>;
}

export function FailureTechnicalDetails({ failure, onNotice }: { failure: FailureRow; onNotice: (notice: Notice) => void }) {
  const f = useFailureLabels();
  async function copy(value: string, trace = false) {
    try {
      await navigator.clipboard.writeText(value);
      onNotice({ type: "success", text: f(trace ? "traceCopied" : "identifierCopied") });
    } catch {
      onNotice({ type: "error", text: f(trace ? "traceCopyFailed" : "identifierCopyFailed") });
    }
  }
  function reference(value: string | null | undefined, button: string, hint: string, trace = false) {
    return <>{value ? <div className="failure-reference"><code>{value}</code>
      <button className="small-button" type="button" onClick={() => void copy(value, trace)}>{f(button)}</button></div>
      : f("notReported")}<small className="failure-reference-help">{f(hint)}</small></>;
  }
  return <details className="failure-technical">
    <summary>{f("technicalTitle")}<span>{f("technicalSubtitle")}</span></summary>
    <div className="failure-technical-body">
      <p className="field-hint">{f("technicalHint")}</p>
      <dl className="failure-diagnostic-grid">
        <dt>{f("installation")}</dt><dd>{reference(failure.installationId, "copyInstallation", "installationHint")}</dd>
        <dt>{f("reference")}</dt><dd>{reference(failure.sourceId, "copySource", "sourceHint")}</dd>
        <dt>{f("code")}</dt><dd><code>{failure.code}</code></dd>
        <dt>{f("receivedAt")}</dt><dd>{failure.receivedAt ? formatDate(failure.receivedAt) : f("notReported")}</dd>
        <dt>{f("module")}</dt><dd>{failure.module ? f("module_" + failure.module) : f("notReported")}</dd>
        <dt>{f("appVersion")}</dt><dd>{failure.appVersion ?? f("notReported")}</dd>
        <dt>{f("traceId")}</dt><dd>{reference(failure.traceId, "copyTrace", "traceHint", true)}</dd>
        <dt>{f("exceptionType")}</dt><dd>{failure.exceptionType ?? f("notReported")}</dd>
        <dt>{f("errorLocation")}</dt><dd>{failure.errorLocation ?? f("notReported")}</dd>
        <dt>{f("diagnosticDetail")}</dt><dd className="failure-diagnostic-text">{failure.detail || f("notReported")}</dd>
      </dl>
      <p className="field-hint">{f("receiptScope")}</p>
    </div>
  </details>;
}

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ApiError, apiRequest, type UserSession } from "@tpverp/app-common";
import { canManageTaxes } from "./gestionAccess";

type Translator = (key: string) => string;
type Request = typeof apiRequest;

type Tax = {
  id: string;
  percentage: number | string;
  active: boolean;
  defaultTax: boolean;
};

type Draft = { id: string; percentage: string };

function errorText(cause: unknown, fallback: string, t: Translator) {
  if (cause instanceof ApiError) {
    const code = typeof cause.problem?.code === "string" ? cause.problem.code : "";
    const detail = typeof cause.problem?.detail === "string" ? cause.problem.detail : "";
    if (code && t(code) !== code) return t(code);
    if (detail && t(detail) !== detail) return t(detail);
    if (detail) return detail;
    if (cause.message && cause.message !== "api_error") return cause.message;
  }
  if (cause instanceof Error && cause.message) return cause.message;
  return t(fallback);
}

export function TaxSettingsScreen({ session, t, request = apiRequest }: {
  session: UserSession;
  t: Translator;
  request?: Request;
}) {
  const [taxes, setTaxes] = useState<Tax[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const canManage = canManageTaxes(session);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const rows = await request<Tax[]>("/taxes", { token: session.accessToken });
      setTaxes(Array.isArray(rows) ? rows : []);
    } catch (cause) {
      setTaxes([]);
      setError(errorText(cause, "gestion.taxes.loadError", t));
    } finally {
      setLoading(false);
    }
  }, [request, session.accessToken, t]);

  useEffect(() => { void load(); }, [load]);

  function openNew() {
    setDraft({ id: "", percentage: "" });
    setError("");
    setFeedback("");
  }

  function openEdit(tax: Tax) {
    setDraft({ id: tax.id, percentage: String(tax.percentage) });
    setError("");
    setFeedback("");
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!canManage || busy || !draft) return;
    const percentage = Number(draft.percentage.replace(",", "."));
    if (!Number.isFinite(percentage) || percentage < 0 || percentage > 100) {
      setError(t("gestion.taxes.invalidPercentage"));
      return;
    }
    setBusy(true);
    setError("");
    try {
      const saved = await request<Tax>(draft.id ? `/taxes/${draft.id}` : "/taxes", {
        token: session.accessToken,
        method: draft.id ? "PUT" : "POST",
        body: { percentage },
      });
      setTaxes((current) => draft.id
        ? current.map((tax) => tax.id === saved.id ? saved : tax)
        : [...current, saved]);
      setDraft(null);
      setFeedback(t("gestion.taxes.saved"));
    } catch (cause) {
      setError(errorText(cause, "gestion.taxes.saveError", t));
    } finally {
      setBusy(false);
    }
  }

  async function setActive(tax: Tax, active: boolean) {
    if (!canManage || busy || (tax.defaultTax && !active)) return;
    if (!active && !window.confirm(t("gestion.taxes.confirmDeactivate"))) return;
    setBusy(true);
    setError("");
    try {
      const saved = await request<Tax>(`/taxes/${tax.id}/active`, {
        token: session.accessToken,
        method: "PATCH",
        body: { active },
      });
      setTaxes((current) => current.map((row) => row.id === saved.id ? saved : row));
      setFeedback(t("gestion.taxes.saved"));
    } catch (cause) {
      setError(errorText(cause, "gestion.taxes.saveError", t));
    } finally {
      setBusy(false);
    }
  }

  async function markDefault(tax: Tax) {
    if (!canManage || busy || tax.defaultTax) return;
    setBusy(true);
    setError("");
    try {
      const saved = await request<Tax>(`/taxes/${tax.id}/default`, {
        token: session.accessToken,
        method: "PATCH",
      });
      setTaxes((current) => current.map((row) => row.id === saved.id
        ? saved
        : { ...row, defaultTax: false }));
      setFeedback(t("gestion.taxes.saved"));
    } catch (cause) {
      setError(errorText(cause, "gestion.taxes.saveError", t));
    } finally {
      setBusy(false);
    }
  }

  async function remove(tax: Tax) {
    if (!canManage || busy || tax.defaultTax) return;
    if (!window.confirm(t("gestion.taxes.confirmDelete"))) return;
    setBusy(true);
    setError("");
    try {
      await request<void>(`/taxes/${tax.id}`, { token: session.accessToken, method: "DELETE" });
      setTaxes((current) => current.filter((row) => row.id !== tax.id));
      setFeedback(t("gestion.taxes.deleted"));
    } catch (cause) {
      setError(errorText(cause, "gestion.taxes.deleteError", t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="gestion-workspace gestion-taxes-workspace">
      <header className="gestion-taxes-header">
        <div>
          <span>{t("gestion.configuration.navigation")}</span>
          <h2>{t("gestion.taxes.title")}</h2>
          <p>{t("gestion.taxes.description")}</p>
        </div>
        {canManage && <button type="button" className="primary" disabled={busy || Boolean(draft)} onClick={openNew}>{t("gestion.taxes.new")}</button>}
      </header>

      {error && <p className="gestion-taxes-message error" role="alert">{error}</p>}
      {feedback && !draft && <p className="gestion-taxes-message success" role="status">{feedback}</p>}

      {draft && (
        <form className="gestion-taxes-editor" onSubmit={(event) => void save(event)}>
          <h3>{t(draft.id ? "gestion.taxes.editTitle" : "gestion.taxes.newTitle")}</h3>
          <label>
            <span>{t("gestion.taxes.percentage")}</span>
            <input autoFocus type="number" min="0" max="100" step="0.01" value={draft.percentage} disabled={busy || !canManage} onChange={(event) => setDraft({ ...draft, percentage: event.currentTarget.value })} />
          </label>
          <div className="gestion-taxes-editor-actions">
            <button type="submit" className="primary" disabled={busy || !canManage}>{t("common.save")}</button>
            <button type="button" disabled={busy} onClick={() => setDraft(null)}>{t("common.cancel")}</button>
          </div>
        </form>
      )}

      <section className="gestion-taxes-table" aria-label={t("gestion.taxes.title")}>
        <header>
          <span>{t("gestion.taxes.percentage")}</span>
          <span>{t("gestion.taxes.status")}</span>
          <span>{t("common.actions")}</span>
        </header>
        {loading ? <p className="gestion-taxes-state">{t("common.loading")}</p> : taxes.length === 0 ? <p className="gestion-taxes-state">{t("gestion.taxes.empty")}</p> : taxes.map((tax) => (
          <article key={tax.id}>
            <strong>{tax.percentage} %{tax.defaultTax && <small>{t("gestion.taxes.default")}</small>}</strong>
            <label className="gestion-taxes-switch">
              <input type="checkbox" role="switch" checked={tax.active} disabled={!canManage || busy || tax.defaultTax} onChange={(event) => void setActive(tax, event.currentTarget.checked)} />
              <span>{t(tax.active ? "gestion.taxes.active" : "gestion.taxes.inactive")}</span>
            </label>
            <div className="gestion-taxes-actions">
              <button type="button" disabled={!canManage || busy} onClick={() => openEdit(tax)}>{t("gestion.taxes.edit")}</button>
              {!tax.defaultTax && <button type="button" disabled={!canManage || busy || !tax.active} onClick={() => void markDefault(tax)}>{t("gestion.taxes.makeDefault")}</button>}
              {!tax.defaultTax && <button type="button" className="danger" disabled={!canManage || busy} onClick={() => void remove(tax)}>{t("common.delete")}</button>}
            </div>
          </article>
        ))}
      </section>
    </section>
  );
}

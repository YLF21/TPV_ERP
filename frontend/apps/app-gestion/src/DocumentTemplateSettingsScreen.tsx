import { useEffect, useState, type FormEvent } from "react";
import { ApiError, apiRequest, type UserSession } from "@tpverp/app-common";
import {
  activateDocumentTemplate,
  createDocumentTemplateDraft,
  downloadDocumentTemplateSource,
  loadDocumentTemplateCatalog,
  uploadDocumentTemplateArtifact,
  type DocumentTemplateCatalog,
  type DocumentTemplateFormat,
  type DocumentTemplateType,
  type DocumentTemplateView,
} from "./documentTemplatesApi";

type Translator = (key: string) => string;

type Props = {
  session: UserSession;
  t: Translator;
  request?: typeof apiRequest;
};

const documentTypes: DocumentTemplateType[] = ["FACTURA_VENTA", "ALBARAN_VENTA", "TICKET"];

function failureMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) return error.message || fallback;
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatDate(value: string | null) {
  if (!value) return "-";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "short" }).format(new Date(value));
}

function originLabel(scope: DocumentTemplateView["scope"] | undefined, t: Translator) {
  if (scope === "STORE") return t("gestion.documentTemplates.store");
  if (scope === "COMPANY") return t("gestion.documentTemplates.company");
  if (scope === "SYSTEM") return t("gestion.documentTemplates.system");
  return "-";
}

export function DocumentTemplateSettingsScreen({ session, t, request = apiRequest }: Props) {
  const [selectedType, setSelectedType] = useState<DocumentTemplateType>("FACTURA_VENTA");
  const [selectedFormat, setSelectedFormat] = useState<DocumentTemplateFormat>("A4");
  const [catalog, setCatalog] = useState<DocumentTemplateCatalog | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [code, setCode] = useState("FACTURA_A4");
  const [name, setName] = useState("");
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const canManage = session.permissions.includes("ADMIN")
    || session.permissions.includes("DOCUMENT_TEMPLATES_MANAGE");
  const effectiveFormat: DocumentTemplateFormat = selectedType === "TICKET"
    ? "TICKET_80"
    : selectedType === "ALBARAN_VENTA" ? "A4" : selectedFormat;

  function suggestedCode(type: DocumentTemplateType, format: DocumentTemplateFormat) {
    if (type === "FACTURA_VENTA" && format === "TICKET_80") return "FACTURA_TICKET_80";
    if (type === "ALBARAN_VENTA") return "ALBARAN_A4";
    if (type === "TICKET") return "TICKET_80";
    return "FACTURA_A4";
  }

  async function refresh(type = selectedType, format = effectiveFormat) {
    setLoading(true);
    try {
      setCatalog(await loadDocumentTemplateCatalog(type, format, session.accessToken, request));
    } catch (error) {
      setCatalog(null);
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentTemplates.loadError")) });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    setCode(suggestedCode(selectedType, effectiveFormat));
    setName("");
    setMessage(null);
    void refresh(selectedType, effectiveFormat);
    // refresh is intentionally tied to the selected catalog.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedType, selectedFormat, request, session.accessToken]);

  async function createDraft(event: FormEvent) {
    event.preventDefault();
    if (!canManage || busyId || !code.trim() || !name.trim()) return;
    setBusyId("create");
    setMessage(null);
    try {
      await createDocumentTemplateDraft(
        { type: selectedType, format: effectiveFormat, code: code.trim(), name: name.trim() },
        session.accessToken,
        request,
      );
      setName("");
      setMessage({ kind: "success", text: t("gestion.documentTemplates.draftCreated") });
      await refresh();
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentTemplates.createError")) });
    } finally {
      setBusyId(null);
    }
  }

  async function upload(template: DocumentTemplateView, file: File | undefined) {
    if (!file || !canManage || busyId) return;
    setBusyId(template.id);
    setMessage(null);
    try {
      await uploadDocumentTemplateArtifact(template.id, file, session.accessToken, request);
      setMessage({ kind: "success", text: t("gestion.documentTemplates.validated") });
      await refresh();
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentTemplates.validationError")) });
    } finally {
      setBusyId(null);
    }
  }

  async function activate(template: DocumentTemplateView) {
    if (!canManage || busyId || template.status !== "VALIDATED") return;
    setBusyId(template.id);
    setMessage(null);
    try {
      await activateDocumentTemplate(template.id, session.accessToken, request);
      setMessage({ kind: "success", text: t("gestion.documentTemplates.activated") });
      await refresh();
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentTemplates.activateError")) });
    } finally {
      setBusyId(null);
    }
  }

  async function download(template: DocumentTemplateView) {
    setMessage(null);
    try {
      await downloadDocumentTemplateSource(template, session.accessToken);
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentTemplates.downloadError")) });
    }
  }

  return (
    <section className="gestion-workspace gestion-document-templates-workspace">
      <header className="gestion-document-templates-header">
        <div>
          <span>{t("gestion.configuration.navigation")}</span>
          <h2>{t("gestion.documentTemplates.title")}</h2>
          <p>{t("gestion.documentTemplates.description")}</p>
        </div>
        <aside>
          <strong>{t("gestion.documentTemplates.storeScope")}</strong>
          <small>{t("gestion.documentTemplates.fallback")}</small>
        </aside>
      </header>

      <div className="gestion-document-template-tabs" role="tablist" aria-label={t("gestion.documentTemplates.documentType")}>
        {documentTypes.map((type) => (
          <button
            type="button"
            role="tab"
            aria-selected={selectedType === type}
            className={selectedType === type ? "selected" : undefined}
            key={type}
            onClick={() => setSelectedType(type)}
          >
            {t(`gestion.documentTemplates.type.${type}`)}
          </button>
        ))}
      </div>

      {selectedType === "FACTURA_VENTA" && (
        <div className="gestion-document-template-tabs" role="tablist" aria-label={t("gestion.documentTemplates.format")}>
          {(["A4", "TICKET_80"] as DocumentTemplateFormat[]).map((format) => (
            <button
              type="button"
              role="tab"
              aria-selected={selectedFormat === format}
              className={selectedFormat === format ? "selected" : undefined}
              key={format}
              onClick={() => setSelectedFormat(format)}
            >
              {t(`gestion.documentTemplates.format.${format}`)}
            </button>
          ))}
        </div>
      )}

      {message && (
        <p className={`gestion-document-template-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>
          {message.text}
        </p>
      )}

      <section className="gestion-document-template-effective" aria-labelledby="document-template-effective-title">
        <div>
          <span>{t("gestion.documentTemplates.effective")}</span>
          <h3 id="document-template-effective-title">
            {loading ? t("common.loading") : catalog?.effective.code ?? "-"}
          </h3>
        </div>
        <dl>
          <div><dt>{t("gestion.documentTemplates.version")}</dt><dd>{catalog?.effective.version ?? "-"}</dd></div>
          <div><dt>{t("gestion.documentTemplates.origin")}</dt><dd>{originLabel(catalog?.effective.scope, t)}</dd></div>
          <div><dt>{t("gestion.documentTemplates.schema")}</dt><dd>{catalog?.effective.schemaVersion ?? "-"}</dd></div>
        </dl>
      </section>

      <form className="gestion-document-template-create" onSubmit={createDraft}>
        <div>
          <h3>{t("gestion.documentTemplates.newVersion")}</h3>
          <p>{t("gestion.documentTemplates.newVersionHelp")}</p>
        </div>
        <label>
          <span>{t("gestion.documentTemplates.code")}</span>
          <input value={code} pattern="[A-Za-z0-9][A-Za-z0-9_]{2,79}" maxLength={80} required onChange={(event) => setCode(event.target.value.toUpperCase())} />
        </label>
        <label>
          <span>{t("gestion.documentTemplates.name")}</span>
          <input value={name} maxLength={160} required onChange={(event) => setName(event.target.value)} />
        </label>
        <button type="submit" disabled={!canManage || busyId !== null || !name.trim()}>{t("gestion.documentTemplates.createDraft")}</button>
      </form>

      <section className="gestion-document-template-list" aria-label={t("gestion.documentTemplates.versions")}>
        <header>
          <span>{t("gestion.documentTemplates.version")}</span>
          <span>{t("gestion.documentTemplates.name")}</span>
          <span>{t("gestion.documentTemplates.status")}</span>
          <span>{t("gestion.documentTemplates.updated")}</span>
          <span>{t("gestion.documentTemplates.actions")}</span>
        </header>
        {!loading && (catalog?.storeTemplates.length ?? 0) === 0 && (
          <p className="gestion-document-template-empty">{t("gestion.documentTemplates.empty")}</p>
        )}
        {catalog?.storeTemplates.map((template) => (
          <article key={template.id}>
            <strong>v{template.version}</strong>
            <div><b>{template.name}</b><small>{template.code}</small></div>
            <span className={`status status-${template.status.toLowerCase()}`}>{t(`gestion.documentTemplates.status.${template.status}`)}</span>
            <time dateTime={template.validatedAt ?? template.createdAt}>{formatDate(template.validatedAt ?? template.createdAt)}</time>
            <div className="actions">
              <label className={template.status !== "DRAFT" ? "disabled" : undefined}>
                <input
                  type="file"
                  accept=".jrxml,application/xml,text/xml"
                  disabled={!canManage || busyId !== null || template.status !== "DRAFT"}
                  onChange={(event) => {
                    const file = event.currentTarget.files?.[0];
                    event.currentTarget.value = "";
                    void upload(template, file);
                  }}
                />
                {t("gestion.documentTemplates.upload")}
              </label>
              {template.sha256 && <button type="button" onClick={() => void download(template)}>{t("gestion.documentTemplates.download")}</button>}
              {template.status === "VALIDATED" && <button type="button" className="primary" disabled={busyId !== null} onClick={() => void activate(template)}>{t("gestion.documentTemplates.activate")}</button>}
            </div>
          </article>
        ))}
      </section>
    </section>
  );
}

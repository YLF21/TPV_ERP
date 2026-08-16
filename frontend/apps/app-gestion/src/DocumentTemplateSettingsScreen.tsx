import { useEffect, useState, type FormEvent } from "react";
import { ApiError, apiRequest, type UserSession } from "@tpverp/app-common";
import {
  activateDocumentTemplate,
  createDocumentTemplateDraft,
  downloadDocumentTemplateSource,
  loadDocumentTemplateCatalog,
  loadDocumentTemplatePresentation,
  saveDocumentTemplatePresentation,
  reactivateDocumentTemplate,
  useBuiltInDocumentTemplate,
  uploadDocumentTemplateArtifact,
  type DocumentTemplateCatalog,
  type DocumentTemplateFormat,
  type DocumentTemplateOrigin,
  type DocumentTemplateType,
  type DocumentTemplateView,
} from "./documentTemplatesApi";
import {
  loadStoreDocumentPrintConfiguration,
  saveStoreTicketPresentation,
  type TicketPrintStyle,
  type TicketTemplateOrigin,
} from "./storeDocumentPrintConfigurationApi";
import ticketStylePrincipal from "./assets/ticket-styles/principal.svg";
import ticketStyleCompacta from "./assets/ticket-styles/compacta.svg";
import ticketStyleMinimalista from "./assets/ticket-styles/minimalista.svg";
import facturaA4Preview from "./assets/document-templates/factura-a4.png";
import albaranA4Preview from "./assets/document-templates/albaran-a4.png";
import facturaTicket80Preview from "./assets/document-templates/factura-ticket-80.png";
import valeTicket80Preview from "./assets/document-templates/vale-ticket-80.png";

type Translator = (key: string) => string;

type Props = {
  session: UserSession;
  t: Translator;
  request?: typeof apiRequest;
};

const documentTypes: DocumentTemplateType[] = ["FACTURA_VENTA", "ALBARAN_VENTA", "TICKET", "VALE"];

const ticketStylePreviews: Record<TicketPrintStyle, string> = {
  PRINCIPAL: ticketStylePrincipal,
  COMPACTA: ticketStyleCompacta,
  MINIMALISTA: ticketStyleMinimalista,
};

const documentTemplatePreviews: Partial<Record<`${DocumentTemplateType}:${DocumentTemplateFormat}`, string>> = {
  "FACTURA_VENTA:A4": facturaA4Preview,
  "FACTURA_VENTA:TICKET_80": facturaTicket80Preview,
  "ALBARAN_VENTA:A4": albaranA4Preview,
  "VALE:TICKET_80": valeTicket80Preview,
};

const ticketBundleFilenames = new Set([
  "ticket.jrxml",
  ...["cabecera", "cliente", "contenido", "impuesto", "pago", "pie"].flatMap((section) => [
    `ticket_${section}.jrxml`,
    `ticket_${section}_compacta.jrxml`,
    `ticket_${section}_minimalista.jrxml`,
  ]),
]);

export function documentTemplateArtifactFiles(type: DocumentTemplateType, files: File[]): File[] {
  if (type !== "TICKET") return files;
  if (files.length === 1 && files[0].name.toLowerCase().endsWith(".jrxml")) {
    return files;
  }
  return files.filter((file) => ticketBundleFilenames.has(file.name.toLowerCase()));
}

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
  const [ticketStyle, setTicketStyle] = useState<TicketPrintStyle>("PRINCIPAL");
  const [ticketTemplateOrigin, setTicketTemplateOrigin] =
    useState<TicketTemplateOrigin>("INTEGRATED");
  const [documentTemplateOrigin, setDocumentTemplateOrigin] =
    useState<DocumentTemplateOrigin>("INTEGRATED");
  const [savedTicketStyle, setSavedTicketStyle] = useState<TicketPrintStyle>("PRINCIPAL");
  const canManage = session.permissions.includes("ADMIN")
    || session.permissions.includes("DOCUMENT_TEMPLATES_MANAGE");
  const effectiveFormat: DocumentTemplateFormat = selectedType === "TICKET" || selectedType === "VALE"
    ? "TICKET_80"
    : selectedType === "ALBARAN_VENTA" ? "A4" : selectedFormat;
  const documentTemplatePreview = documentTemplatePreviews[`${selectedType}:${effectiveFormat}`];
  const selectedTicketPresentationIsActive = selectedType === "TICKET"
    && (ticketTemplateOrigin === "INTEGRATED"
      ? catalog?.effective?.builtIn === true && ticketStyle === savedTicketStyle
      : catalog?.effective?.builtIn === false);

  function suggestedCode(type: DocumentTemplateType, format: DocumentTemplateFormat) {
    if (type === "FACTURA_VENTA" && format === "TICKET_80") return "FACTURA_TICKET_80";
    if (type === "ALBARAN_VENTA") return "ALBARAN_A4";
    if (type === "TICKET") return "TICKET_80";
    if (type === "VALE") return "VALE_TICKET_80";
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

  useEffect(() => {
    if (selectedType !== "TICKET") return;
    void loadStoreDocumentPrintConfiguration(session.accessToken, request)
      .then((value) => {
        const style = value.ticketStyle ?? "PRINCIPAL";
        setTicketStyle(style);
        setSavedTicketStyle(style);
        setTicketTemplateOrigin(value.ticketTemplateOrigin ?? "INTEGRATED");
      })
      .catch((error) => setMessage({
        kind: "error",
        text: failureMessage(error, t("gestion.documentTemplates.ticketStyleLoadError")),
      }));
  }, [selectedType, request, session.accessToken, t]);

  useEffect(() => {
    if (selectedType === "TICKET") return;
    let current = true;
    void loadDocumentTemplatePresentation(
      selectedType, effectiveFormat, session.accessToken, request,
    )
      .then((value) => {
        if (current) setDocumentTemplateOrigin(value.origin ?? "INTEGRATED");
      })
      .catch((error) => {
        if (current) {
          setMessage({
            kind: "error",
            text: failureMessage(error, t("gestion.documentTemplates.documentPresentationLoadError")),
          });
        }
      });
    return () => { current = false; };
  }, [selectedType, effectiveFormat, request, session.accessToken, t]);

  async function updateTicketPresentation() {
    if (!canManage || busyId) return;
    setBusyId("ticket-style");
    setMessage(null);
    try {
      const value = await saveStoreTicketPresentation(
        ticketTemplateOrigin, ticketStyle, session.accessToken, request,
      );
      setTicketStyle(value.ticketStyle);
      setSavedTicketStyle(value.ticketStyle);
      setTicketTemplateOrigin(value.ticketTemplateOrigin);
      setMessage({ kind: "success", text: t("gestion.documentTemplates.ticketStyleSaved") });
      await refresh("TICKET", "TICKET_80");
    } catch (error) {
      setMessage({
        kind: "error",
        text: failureMessage(error, t("gestion.documentTemplates.ticketStyleSaveError")),
      });
    } finally {
      setBusyId(null);
    }
  }

  async function updateDocumentPresentation() {
    if (!canManage || busyId || selectedType === "TICKET") return;
    setBusyId("document-presentation");
    setMessage(null);
    try {
      const value = await saveDocumentTemplatePresentation({
        type: selectedType,
        format: effectiveFormat,
        origin: documentTemplateOrigin,
      }, session.accessToken, request);
      setDocumentTemplateOrigin(value.origin);
      setMessage({
        kind: "success",
        text: t("gestion.documentTemplates.documentPresentationSaved"),
      });
    } catch (error) {
      setMessage({
        kind: "error",
        text: failureMessage(error, t("gestion.documentTemplates.documentPresentationSaveError")),
      });
    } finally {
      setBusyId(null);
    }
  }

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

  async function upload(template: DocumentTemplateView, files: File[]) {
    if (files.length === 0 || !canManage || busyId) return;
    setBusyId(template.id);
    setMessage(null);
    try {
      await uploadDocumentTemplateArtifact(template.id, files, session.accessToken, request);
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

  async function reactivate(template: DocumentTemplateView) {
    if (!canManage || busyId || !template.reactivatable) return;
    setBusyId(template.id);
    setMessage(null);
    try {
      await reactivateDocumentTemplate(template.id, session.accessToken, request);
      setMessage({ kind: "success", text: t("gestion.documentTemplates.reactivated") });
      await refresh();
    } catch (error) {
      setMessage({ kind: "error", text: failureMessage(error, t("gestion.documentTemplates.reactivateError")) });
    } finally {
      setBusyId(null);
    }
  }

  async function useBuiltInTemplate() {
    if (!canManage || busyId || selectedType === "TICKET") return;
    setBusyId("built-in");
    setMessage(null);
    try {
      const value = await useBuiltInDocumentTemplate(
        selectedType, effectiveFormat, session.accessToken, request,
      );
      setCatalog(value);
      setMessage({ kind: "success", text: t("gestion.documentTemplates.builtInSaved") });
    } catch (error) {
      setMessage({
        kind: "error",
        text: failureMessage(error, t("gestion.documentTemplates.builtInSaveError")),
      });
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
            {loading ? t("common.loading") : catalog?.effective?.code ?? t("gestion.documentTemplates.missing")}
          </h3>
        </div>
        <dl>
          <div>
            <dt>{t("gestion.documentTemplates.activeTemplate")}</dt>
            <dd>{loading || !catalog?.effective
              ? "-"
              : (() => {
                  const active = catalog.storeTemplates.find(
                    (template) => template.id === catalog.effective?.id,
                  );
                  return active
                    ? `v${catalog.effective.version} · ${active.name}`
                    : `${t("gestion.documentTemplates.defaultTemplate")} · v${catalog.effective.version}`;
                })()}</dd>
          </div>
          <div><dt>{t("gestion.documentTemplates.version")}</dt><dd>{catalog?.effective?.version ?? "-"}</dd></div>
          <div><dt>{t("gestion.documentTemplates.origin")}</dt><dd>{originLabel(catalog?.effective?.scope, t)}</dd></div>
          <div><dt>{t("gestion.documentTemplates.schema")}</dt><dd>{catalog?.effective?.schemaVersion ?? "-"}</dd></div>
          <div>
            <dt>{t("gestion.documentTemplates.activeTicketStyle")}</dt>
            <dd>{selectedType === "TICKET"
              ? (catalog?.effective?.builtIn
                  ? t(`gestion.documentTemplates.ticketStyle.${savedTicketStyle}`)
                  : t("gestion.documentTemplates.customTicketTemplate"))
              : (catalog?.effective?.builtIn
                  ? t("gestion.documentTemplates.builtInDesign")
                  : t("gestion.documentTemplates.customTicketTemplate"))}</dd>
          </div>
        </dl>
      </section>

      {selectedType !== "TICKET" && (
        <section className="gestion-ticket-style-selector" aria-labelledby="document-presentation-title">
          <div className="gestion-ticket-style-copy">
            <h3 id="document-presentation-title">
              {t("gestion.documentTemplates.documentPresentation")}
            </h3>
            <p>{t("gestion.documentTemplates.documentPresentationHelp")}</p>
          </div>
          <div className="gestion-ticket-imported-summary">
            <strong>
              {documentTemplateOrigin === "IMPORTED"
                ? catalog?.effective?.code ?? t("gestion.documentTemplates.missing")
                : t("gestion.documentTemplates.presentationOrigin.INTEGRATED")}
            </strong>
            {documentTemplateOrigin === "IMPORTED" && (
              <span>{t("gestion.documentTemplates.version")} {catalog?.effective?.version ?? "-"}</span>
            )}
            <small>{t(documentTemplateOrigin === "IMPORTED"
              ? "gestion.documentTemplates.presentationImportedHelp"
              : "gestion.documentTemplates.presentationIntegratedHelp")}</small>
          </div>
          <div className="gestion-ticket-style-actions">
            <label>
              <span>{t("gestion.documentTemplates.presentationOriginLabel")}</span>
              <select
                value={documentTemplateOrigin}
                disabled={!canManage || busyId !== null}
                onChange={(event) => setDocumentTemplateOrigin(
                  event.currentTarget.value as DocumentTemplateOrigin,
                )}
              >
                <option value="INTEGRATED">
                  {t("gestion.documentTemplates.presentationOrigin.INTEGRATED")}
                </option>
                <option value="IMPORTED" disabled={!catalog?.effective}>
                  {t("gestion.documentTemplates.presentationOrigin.IMPORTED")}
                </option>
              </select>
            </label>
            <button
              type="button"
              disabled={!canManage || busyId !== null}
              onClick={() => void updateDocumentPresentation()}
            >
              {t("gestion.documentTemplates.ticketStyleSave")}
            </button>
          </div>
        </section>
      )}

      {selectedType === "TICKET" && (
        <section className="gestion-ticket-style-selector" aria-labelledby="ticket-style-title">
          <div className="gestion-ticket-style-copy">
            <h3 id="ticket-style-title">{t("gestion.documentTemplates.ticketStyle")}</h3>
            <p>{catalog?.effective?.builtIn
              ? t("gestion.documentTemplates.ticketStyleHelp")
              : t("gestion.documentTemplates.ticketStyleReplacesCustomHelp")}</p>
          </div>
          {ticketTemplateOrigin === "INTEGRATED" ? (
            <figure className="gestion-ticket-style-preview">
              <img
                src={ticketStylePreviews[ticketStyle]}
                alt={`${t("gestion.documentTemplates.ticketStylePreview")}: ${t(`gestion.documentTemplates.ticketStyle.${ticketStyle}`)}`}
              />
              <figcaption>{t(`gestion.documentTemplates.ticketStyle.${ticketStyle}`)}</figcaption>
            </figure>
          ) : (
            <div className="gestion-ticket-imported-summary">
              <strong>{catalog?.effective?.code ?? t("gestion.documentTemplates.missing")}</strong>
              <span>{t("gestion.documentTemplates.version")} {catalog?.effective?.version ?? "-"}</span>
              <small>{t("gestion.documentTemplates.ticketOriginImportedHelp")}</small>
            </div>
          )}
          <div className="gestion-ticket-style-actions">
            <label>
              <span>{t("gestion.documentTemplates.ticketOriginLabel")}</span>
              <select
                value={ticketTemplateOrigin}
                disabled={!canManage || busyId !== null}
                onChange={(event) => setTicketTemplateOrigin(
                  event.currentTarget.value as TicketTemplateOrigin,
                )}
              >
                <option value="INTEGRATED">{t("gestion.documentTemplates.ticketOrigin.INTEGRATED")}</option>
                <option value="IMPORTED" disabled={!catalog?.effective}>
                  {t("gestion.documentTemplates.ticketOrigin.IMPORTED")}
                </option>
              </select>
            </label>
            {ticketTemplateOrigin === "INTEGRATED" && (
              <label>
                <span>{t("gestion.documentTemplates.ticketStyleLabel")}</span>
                <select
                  value={ticketStyle}
                  disabled={!canManage || busyId !== null}
                  onChange={(event) => setTicketStyle(event.currentTarget.value as TicketPrintStyle)}
                >
                  <option value="PRINCIPAL">{t("gestion.documentTemplates.ticketStyle.PRINCIPAL")}</option>
                  <option value="COMPACTA">{t("gestion.documentTemplates.ticketStyle.COMPACTA")}</option>
                  <option value="MINIMALISTA">{t("gestion.documentTemplates.ticketStyle.MINIMALISTA")}</option>
                </select>
              </label>
            )}
            <button
              type="button"
              disabled={!canManage || busyId !== null || selectedTicketPresentationIsActive}
              onClick={() => void updateTicketPresentation()}
            >
              {selectedTicketPresentationIsActive
                ? t("gestion.documentTemplates.ticketStyleActive")
                : ticketTemplateOrigin === "INTEGRATED" && catalog?.effective?.builtIn === false
                  ? t("gestion.documentTemplates.ticketStyleReplace")
                  : t("gestion.documentTemplates.ticketStyleSave")}
            </button>
          </div>
        </section>
      )}

      {selectedType !== "TICKET" && (
        <section className="gestion-ticket-style-selector" aria-labelledby="document-built-in-title">
          <div className="gestion-ticket-style-copy">
            <h3 id="document-built-in-title">{t("gestion.documentTemplates.builtInTitle")}</h3>
            <p>{catalog?.effective?.builtIn
              ? t("gestion.documentTemplates.builtInHelp")
              : t("gestion.documentTemplates.builtInReplacesCustomHelp")}</p>
          </div>
          {documentTemplatePreview ? (
            <figure className={`gestion-document-template-preview ${effectiveFormat === "A4" ? "is-a4" : "is-ticket"}`}>
              <img
                src={documentTemplatePreview}
                alt={`${t("gestion.documentTemplates.ticketStylePreview")}: ${t(`gestion.documentTemplates.type.${selectedType}`)} · ${t(`gestion.documentTemplates.format.${effectiveFormat}`)}`}
              />
              <figcaption>
                {t(`gestion.documentTemplates.type.${selectedType}`)} · {t(`gestion.documentTemplates.format.${effectiveFormat}`)}
              </figcaption>
            </figure>
          ) : (
            <div className="gestion-ticket-imported-summary">
              <strong>{t(`gestion.documentTemplates.type.${selectedType}`)}</strong>
              <span>{t(`gestion.documentTemplates.format.${effectiveFormat}`)}</span>
              <small>{catalog?.effective?.builtIn
                ? t("gestion.documentTemplates.builtInActiveHelp")
                : t("gestion.documentTemplates.customActiveHelp")}</small>
            </div>
          )}
          <div className="gestion-ticket-style-actions">
            <button
              type="button"
              disabled={!canManage || busyId !== null || catalog?.effective?.builtIn === true}
              onClick={() => void useBuiltInTemplate()}
            >
              {catalog?.effective?.builtIn
                ? t("gestion.documentTemplates.ticketStyleActive")
                : t("gestion.documentTemplates.useBuiltIn")}
            </button>
          </div>
        </section>
      )}

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
                  multiple={template.type === "TICKET"}
                  disabled={!canManage || busyId !== null || template.status !== "DRAFT"}
                  onChange={(event) => {
                    const files = documentTemplateArtifactFiles(
                      template.type,
                      Array.from(event.currentTarget.files ?? []),
                    );
                    event.currentTarget.value = "";
                    void upload(template, files);
                  }}
                />
                {t(template.type === "TICKET"
                  ? "gestion.documentTemplates.uploadBundle"
                  : "gestion.documentTemplates.upload")}
              </label>
              {template.sha256 && <button type="button" onClick={() => void download(template)}>{t("gestion.documentTemplates.download")}</button>}
              {template.status === "VALIDATED" && <button type="button" className="primary" disabled={busyId !== null} onClick={() => void activate(template)}>{t("gestion.documentTemplates.activate")}</button>}
              {template.reactivatable && <button type="button" className="primary" disabled={busyId !== null} onClick={() => void reactivate(template)}>{t("gestion.documentTemplates.reactivate")}</button>}
            </div>
          </article>
        ))}
      </section>
    </section>
  );
}

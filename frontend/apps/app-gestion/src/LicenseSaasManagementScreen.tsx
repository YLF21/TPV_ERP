import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTableRowArrowFocus } from "../../../packages/app-common/src/components/useTableRowArrowFocus";
import { ApiError, apiRequest, type LocaleCode, type UserSession } from "@tpverp/app-common";
import { GestionTableSearch, matchesGestionTableSearch } from "./GestionTableSearch";
import { GestionModuleHeading } from "./GestionModuleHeading";
import {
  linkSaasLicense,
  loadLicenseHistory,
  validateSaasLicense,
  type LicenseHistoryItem,
  type LicenseSaasStatus,
} from "./licenseSaasApi";

type Translator = (key: string) => string;

type Props = {
  locale: LocaleCode;
  session: UserSession;
  storeName: string;
  t: Translator;
  request?: typeof apiRequest;
};

const localeTags: Record<LocaleCode, string> = {
  es: "es-ES",
  en: "en-GB",
  zh: "zh-CN",
};

function statusKey(status?: LicenseSaasStatus) {
  return status ? `gestion.licenses.status.${status}` : "gestion.licenses.status.UNKNOWN";
}

function messageFor(error: unknown, fallback: string) {
  if (error instanceof ApiError && error.traceId) return `${fallback} (Ref: ${error.traceId})`;
  return fallback;
}

export function LicenseSaasManagementScreen({
  locale,
  session,
  storeName,
  t,
  request = apiRequest,
}: Props) {
  const [history, setHistory] = useState<LicenseHistoryItem[]>([]);
  const [query, setQuery] = useState("");
  const [pairingCode, setPairingCode] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<"link" | "validate" | "refresh" | null>(null);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const historyTableRef = useRef<HTMLDivElement>(null);
  const handleHistoryArrow = useTableRowArrowFocus(historyTableRef, '[role="row"]:not(.head)', !loading);
  const canManage = session.permissions.includes("ADMIN");
  const activeLicense = useMemo(() => history.find((item) => item.active) ?? null, [history]);

  const formatDate = useCallback((value?: string | null, includeTime = false) => {
    if (!value) return t("gestion.licenses.notAvailable");
    const parsed = includeTime ? new Date(value) : new Date(`${value.slice(0, 10)}T00:00:00`);
    if (Number.isNaN(parsed.getTime())) return value;
    return new Intl.DateTimeFormat(localeTags[locale], includeTime
      ? { dateStyle: "medium", timeStyle: "short" }
      : { dateStyle: "medium" }).format(parsed);
  }, [locale, t]);

  const visibleHistory = history.filter((item) => matchesGestionTableSearch(query, [
    item.reference, item.taxId, formatDate(item.validFrom, true), formatDate(item.validUntil, true),
    `${item.maxWindows} ${t("gestion.licenses.windows")}`, `${item.maxPda} ${t("gestion.licenses.pda")}`,
    t(item.active ? "gestion.licenses.active" : "gestion.licenses.inactive")
  ]));

  const refresh = useCallback(async (showBusy = false) => {
    if (showBusy) setBusy("refresh");
    try {
      const loaded = await loadLicenseHistory(session.accessToken, request);
      setHistory(Array.isArray(loaded) ? loaded : []);
    } catch (error) {
      setHistory([]);
      setMessage({
        kind: "error",
        text: messageFor(error, t("gestion.licenses.loadError")),
      });
    } finally {
      setLoading(false);
      if (showBusy) setBusy(null);
    }
  }, [request, session.accessToken, t]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void loadLicenseHistory(session.accessToken, request)
      .then((loaded) => {
        if (active) setHistory(Array.isArray(loaded) ? loaded : []);
      })
      .catch((error) => {
        if (active) {
          setHistory([]);
          setMessage({ kind: "error", text: messageFor(error, t("gestion.licenses.loadError")) });
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [request, session.accessToken, t]);

  async function submitPairing(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalized = pairingCode.trim();
    if (!canManage || busy || !normalized) return;
    setBusy("link");
    setMessage(null);
    try {
      await linkSaasLicense(normalized, session.accessToken, request);
      setPairingCode("");
      await refresh();
      setMessage({ kind: "success", text: t("gestion.licenses.linked") });
    } catch (error) {
      setMessage({ kind: "error", text: messageFor(error, t("gestion.licenses.linkError")) });
    } finally {
      setBusy(null);
    }
  }

  async function validate() {
    if (!canManage || busy || !activeLicense) return;
    setBusy("validate");
    setMessage(null);
    try {
      await validateSaasLicense(session.accessToken, request);
      await refresh();
      setMessage({ kind: "success", text: t("gestion.licenses.validated") });
    } catch (error) {
      setMessage({ kind: "error", text: messageFor(error, t("gestion.licenses.validationError")) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="gestion-workspace gestion-license-workspace gestion-module-screen">
      <GestionModuleHeading className="gestion-license-heading" group={t("gestion.navigation.group.configuration")} title={t("gestion.licenses.title")} description={t("gestion.licenses.description")}>
        <aside>
          <span>{t("gestion.licenses.store")}</span>
          <strong>{storeName}</strong>
          <small>{canManage ? t("gestion.licenses.adminScope") : t("gestion.licenses.readOnlyScope")}</small>
        </aside>
      </GestionModuleHeading>

      {message && (
        <p className={`gestion-license-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>
          {message.text}
        </p>
      )}

      <section className="gestion-license-summary" aria-label={t("gestion.licenses.current") }>
        <div className="gestion-license-current gestion-module-panel">
          <header>
            <div>
              <span>{t("gestion.licenses.current")}</span>
              <strong>{activeLicense?.reference ?? t("gestion.licenses.notLinked")}</strong>
            </div>
            <b className={`gestion-license-status status-${activeLicense?.saasStatus ?? "UNKNOWN"}`}>
              {t(statusKey(activeLicense?.saasStatus))}
            </b>
          </header>
          {loading ? (
            <p className="gestion-license-state" role="status">{t("common.loading")}</p>
          ) : activeLicense ? (
            <dl>
              <div><dt>{t("gestion.licenses.validUntil")}</dt><dd>{formatDate(activeLicense.validUntil, true)}</dd></div>
              <div><dt>{t("gestion.licenses.lastValidation")}</dt><dd>{formatDate(activeLicense.lastSaasValidationAt, true)}</dd></div>
              <div><dt>{t("gestion.licenses.windows")}</dt><dd>{activeLicense.maxWindows}</dd></div>
              <div><dt>{t("gestion.licenses.pda")}</dt><dd>{activeLicense.maxPda}</dd></div>
              <div><dt>{t("gestion.licenses.nif")}</dt><dd>{activeLicense.taxId}</dd></div>
              <div><dt>{t("gestion.licenses.fiscalRegime")}</dt><dd>{activeLicense.impuestos}</dd></div>
              <div><dt>{t("gestion.licenses.verifactuActivation")}</dt><dd>{formatDate(activeLicense.verifactuActivationDate)}</dd></div>
              <div><dt>{t("gestion.licenses.version")}</dt><dd>{activeLicense.licenseVersion ?? t("gestion.licenses.notAvailable")}</dd></div>
            </dl>
          ) : (
            <p className="gestion-license-state">{t("gestion.licenses.empty")}</p>
          )}
          <footer className="gestion-toolbar-actions">
            <button type="button" disabled={busy !== null} onClick={() => void refresh(true)}>
              {busy === "refresh" ? t("gestion.licenses.refreshing") : t("gestion.licenses.refresh")}
            </button>
            <button type="button" className="primary" disabled={!canManage || !activeLicense || busy !== null} onClick={() => void validate()}>
              {busy === "validate" ? t("gestion.licenses.validating") : t("gestion.licenses.validate")}
            </button>
          </footer>
        </div>

        <form className="gestion-license-pairing gestion-module-panel gestion-module-fields" onSubmit={submitPairing}>
          <header>
            <span>{t("gestion.licenses.pairingEyebrow")}</span>
            <h3>{t("gestion.licenses.pairingTitle")}</h3>
            <p>{t("gestion.licenses.pairingDescription")}</p>
          </header>
          <label>
            <span>{t("gestion.licenses.pairingCode")}</span>
            <input
              autoComplete="off"
              spellCheck={false}
              value={pairingCode}
              disabled={!canManage || busy !== null}
              onChange={(event) => setPairingCode(event.currentTarget.value)}
              placeholder={t("gestion.licenses.pairingPlaceholder")}
            />
          </label>
          <div className="gestion-toolbar-actions"><button type="submit" disabled={!canManage || busy !== null || !pairingCode.trim()}>
            {busy === "link" ? t("gestion.licenses.linking") : t("gestion.licenses.link")}
          </button></div>
          <small>{canManage ? t("gestion.licenses.pairingSecurity") : t("gestion.licenses.adminRequired")}</small>
        </form>
      </section>

      <section className="gestion-license-history gestion-module-panel">
        <header>
          <div>
            <h3>{t("gestion.licenses.history")}</h3>
            <p>{t("gestion.licenses.historyDescription")}</p>
          </div>
          <strong>{visibleHistory.length}</strong>
        </header>
        <GestionTableSearch value={query} onChange={setQuery} t={t} />
        <div ref={historyTableRef} className="gestion-license-table gestion-module-table" role="table" aria-label={t("gestion.licenses.history") }>
          <div className="head" role="row">
            <span role="columnheader">{t("gestion.licenses.reference")}</span>
            <span role="columnheader">{t("gestion.licenses.period")}</span>
            <span role="columnheader">{t("gestion.licenses.capacity")}</span>
            <span role="columnheader">{t("gestion.licenses.state")}</span>
          </div>
          {visibleHistory.length === 0 ? (
            <p>{loading ? t("common.loading") : t(query.trim() ? "stock.status.noResults" : "gestion.licenses.noHistory")}</p>
          ) : visibleHistory.map((item) => (
            <div role="row" key={item.reference} tabIndex={0} onKeyDown={handleHistoryArrow}>
              <span role="cell"><strong>{item.reference}</strong><small>{item.taxId}</small></span>
              <span role="cell"><time>{formatDate(item.validFrom, true)}</time><small>{formatDate(item.validUntil, true)}</small></span>
              <span role="cell">
                <strong>{item.maxWindows} {t("gestion.licenses.windows")}</strong>
                <small>{item.maxPda} {t("gestion.licenses.pda")}</small>
              </span>
              <span role="cell"><b className={item.active ? "active" : "inactive"}>{t(item.active ? "gestion.licenses.active" : "gestion.licenses.inactive")}</b></span>
            </div>
          ))}
        </div>
      </section>
    </section>
  );
}

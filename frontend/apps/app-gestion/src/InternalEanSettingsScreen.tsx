import { useEffect, useState } from "react";
import type { UserSession } from "@tpverp/app-common";
import {
  loadInternalEanConfiguration,
  saveInternalEanConfiguration,
  type InternalEanConfiguration,
} from "./internalEanConfigurationApi";

type Props = {
  session: UserSession;
  t: (key: string, values?: Record<string, string | number>) => string;
};

export function InternalEanSettingsScreen({ session, t }: Props) {
  const [configuration, setConfiguration] = useState<InternalEanConfiguration | null>(null);
  const [companyCode, setCompanyCode] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    void loadInternalEanConfiguration(session.accessToken)
      .then((value) => {
        if (!active) return;
        setConfiguration(value);
        setCompanyCode(value.companyCode ?? "");
        setError("");
      })
      .catch((caught) => {
        if (active) setError(caught instanceof Error ? caught.message : t("gestion.internalEan.loadError"));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [session.accessToken]);

  async function save() {
    if (!configuration || !/^[0-9]{2}$/.test(companyCode) || saving) return;
    setSaving(true); setError(""); setStatus("");
    try {
      const next = await saveInternalEanConfiguration(
        configuration.version,
        companyCode,
        session.accessToken,
      );
      setConfiguration(next);
      setCompanyCode(next.companyCode ?? "");
      setStatus(t("gestion.internalEan.saved"));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t("gestion.internalEan.saveError"));
    } finally { setSaving(false); }
  }

  return <section className="gestion-internal-ean-screen">
    <header><h1>{t("gestion.internalEan.title")}</h1><p>{t("gestion.internalEan.description")}</p></header>
    <div className="gestion-internal-ean-card">
      {loading ? <p>{t("common.loading")}</p> : <>
        <label><span>{t("gestion.internalEan.companyCode")}</span><input autoFocus inputMode="numeric" maxLength={2} value={companyCode} onChange={(event) => setCompanyCode(event.currentTarget.value.replace(/\D/g, "").slice(0, 2))} /></label>
        <p className="gestion-internal-ean-example">{t("gestion.internalEan.example", { code: companyCode.padStart(2, "0") })}</p>
        <div className="gestion-internal-ean-warning"><strong>{t("gestion.internalEan.restrictedTitle")}</strong><p>{t("gestion.internalEan.restricted")}</p></div>
        <button type="button" disabled={saving || !/^[0-9]{2}$/.test(companyCode)} onClick={() => void save()}>{saving ? t("common.saving") : t("common.save")}</button>
      </>}
      {status && <p className="gestion-success" role="status">{status}</p>}
      {error && <p className="gestion-error" role="alert">{error}</p>}
    </div>
  </section>;
}

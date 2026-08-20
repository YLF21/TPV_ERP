import { useEffect, useMemo, useState } from "react";
import { apiRequest, type UserSession } from "@tpverp/app-common";
import "./MemberLoyaltySettingsScreen.css";

type Translator = (key: string) => string;
type Request = typeof apiRequest;

type CommercialChannel = { id: string; code: string; name: string; active: boolean };

type MemberSettingsResponse = {
  balanceAccrualEnabled: boolean;
  balanceAccrualBaseAmount: number | string;
  balanceAccrualPercent: number | string;
  balanceExpirationPolicy: string;
  pointsAccrualEnabled: boolean;
  pointsAccrualBaseAmount: number | string;
  pointsPerEuro: number | string;
  categoryAutoEnabled: boolean;
  memberWelcomeEnabled: boolean;
  memberCardCodeFormat: "QR" | "BARCODE";
  welcomeSubjectTemplate: string | null;
  welcomeBodyTemplate: string | null;
};

type NumericSetting = "balanceAccrualBaseAmount" | "balanceAccrualPercent"
  | "pointsAccrualBaseAmount" | "pointsPerEuro";
type Draft = Omit<MemberSettingsResponse, NumericSetting> & Record<NumericSetting, string>;

type Props = {
  session: UserSession;
  t: Translator;
  request?: Request;
};

const expirationPolicies = ["NO_CADUCA", "UN_MES", "TRES_MESES", "SEIS_MESES", "UN_ANO"];

function toDraft(value: MemberSettingsResponse): Draft {
  return {
    balanceAccrualEnabled: value.balanceAccrualEnabled ?? false,
    balanceAccrualBaseAmount: String(value.balanceAccrualBaseAmount ?? 1),
    balanceAccrualPercent: String(value.balanceAccrualPercent ?? 0),
    balanceExpirationPolicy: value.balanceExpirationPolicy ?? "NO_CADUCA",
    pointsAccrualEnabled: value.pointsAccrualEnabled ?? true,
    pointsAccrualBaseAmount: String(value.pointsAccrualBaseAmount ?? 1),
    pointsPerEuro: String(value.pointsPerEuro ?? 1),
    categoryAutoEnabled: value.categoryAutoEnabled ?? true,
    memberWelcomeEnabled: value.memberWelcomeEnabled ?? false,
    memberCardCodeFormat: value.memberCardCodeFormat ?? "QR",
    welcomeSubjectTemplate: value.welcomeSubjectTemplate ?? null,
    welcomeBodyTemplate: value.welcomeBodyTemplate ?? null,
  };
}

export function MemberLoyaltySettingsScreen({ session, t, request = apiRequest }: Props) {
  const [baseline, setBaseline] = useState<Draft | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [exampleAmount, setExampleAmount] = useState("10");
  const [channels, setChannels] = useState<CommercialChannel[]>([]);
  const [channelDraft, setChannelDraft] = useState({ id: "", code: "", name: "", active: true });
  const [channelFeedback, setChannelFeedback] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<"saved" | "loadError" | "saveError" | "invalid" | null>(null);
  const canManage = session.permissions.includes("ADMIN");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      request<MemberSettingsResponse>("/member-settings", { token: session.accessToken }),
      request<CommercialChannel[]>("/commercial-contact-channels", { token: session.accessToken }),
    ]).then(([value, channelRows]) => {
        if (cancelled) return;
        const next = toDraft(value);
        setBaseline(next);
        setDraft(next);
        setChannels(channelRows);
        setFeedback(null);
      })
      .catch(() => {
        if (!cancelled) setFeedback("loadError");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [request, session.accessToken]);

  const pointsBase = Number(draft?.pointsAccrualBaseAmount ?? 0);
  const pointsRate = Number(draft?.pointsPerEuro ?? 0);
  const balanceBase = Number(draft?.balanceAccrualBaseAmount ?? 0);
  const balancePercent = Number(draft?.balanceAccrualPercent ?? 0);
  const exampleValue = Number(exampleAmount);
  const validExample = Number.isFinite(exampleValue)
    && Number.isInteger(exampleValue) && exampleValue >= 0;
  const valid = Number.isFinite(pointsBase) && Number.isInteger(pointsBase) && pointsBase > 0
    && Number.isFinite(pointsRate) && Number.isInteger(pointsRate)
    && pointsRate >= 0 && pointsRate <= 1000
    && Number.isFinite(balanceBase) && Number.isInteger(balanceBase) && balanceBase > 0
    && Number.isFinite(balancePercent) && Number.isInteger(balancePercent)
    && balancePercent >= 0 && balancePercent <= 100
    && (!draft?.pointsAccrualEnabled || pointsRate > 0)
    && (!draft?.balanceAccrualEnabled || balancePercent > 0);
  const dirty = useMemo(() => (
    baseline != null && draft != null && JSON.stringify(baseline) !== JSON.stringify(draft)
  ), [baseline, draft]);
  const examplePoints = draft?.pointsAccrualEnabled && valid
    && validExample ? Math.floor(exampleValue * pointsRate / pointsBase)
    : 0;
  const exampleBalance = draft?.balanceAccrualEnabled && valid
    && validExample
    ? (exampleValue * (balanceBase * balancePercent / 100) / balanceBase).toFixed(2)
    : "0.00";

  async function save() {
    if (!draft || !canManage || saving) return;
    if (!valid) {
      setFeedback("invalid");
      return;
    }
    setSaving(true);
    setFeedback(null);
    try {
      const saved = await request<MemberSettingsResponse>("/member-settings", {
        token: session.accessToken,
        method: "PUT",
        body: {
          ...draft,
          pointsAccrualBaseAmount: pointsBase,
          pointsPerEuro: pointsRate,
          balanceAccrualBaseAmount: balanceBase,
          balanceAccrualPercent: balancePercent,
        },
      });
      const next = toDraft(saved);
      setBaseline(next);
      setDraft(next);
      setFeedback("saved");
    } catch {
      setFeedback("saveError");
    } finally {
      setSaving(false);
    }
  }

  async function saveChannel(event: React.FormEvent) {
    event.preventDefault();
    if (!canManage || saving) return;
    const body = {
      code: channelDraft.code.trim().toUpperCase(),
      name: channelDraft.name.trim(),
      active: channelDraft.active,
    };
    if (!body.code || !body.name) {
      setChannelFeedback("party.members.channelFieldsRequired");
      return;
    }
    setSaving(true);
    setChannelFeedback("");
    try {
      const saved = await request<CommercialChannel>(channelDraft.id
        ? `/commercial-contact-channels/${channelDraft.id}`
        : "/commercial-contact-channels", {
        token: session.accessToken,
        method: channelDraft.id ? "PUT" : "POST",
        body,
      });
      setChannels((current) => channelDraft.id
        ? current.map((channel) => channel.id === saved.id ? saved : channel)
        : [...current, saved]);
      setChannelDraft({ id: "", code: "", name: "", active: true });
      setChannelFeedback("party.members.channelSaved");
    } catch (cause) {
      setChannelFeedback(cause instanceof Error ? cause.message : "gestion.memberSettings.saveError");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <div className="member-settings-state">{t("common.loading")}</div>;
  if (!draft) return <div className="member-settings-state is-error">{t("gestion.memberSettings.loadError")}</div>;

  const disabled = !canManage || saving;
  const stateLabel = (enabled: boolean) => t(enabled
    ? "gestion.memberSettings.enabled"
    : "gestion.memberSettings.disabled");

  return (
    <section className="member-settings-screen">
      <header className="member-settings-header">
        <div>
          <h1>{t("gestion.memberSettings.title")}</h1>
          <p>{t("gestion.memberSettings.subtitle")}</p>
        </div>
        <div className="member-settings-example" aria-label={t("gestion.memberSettings.exampleTitle")}>
          <span>{t("gestion.memberSettings.exampleTitle")}</span>
          <label className="member-settings-example-amount">
            <span className="sr-only">{t("gestion.memberSettings.exampleTitle")}</span>
            <input type="number" min="0" step="1" value={exampleAmount}
              aria-label={t("gestion.memberSettings.exampleTitle")}
              onChange={(event) => setExampleAmount(event.target.value)} />
            <strong>€</strong>
          </label>
          <output>{examplePoints} {t("gestion.memberSettings.pointsUnit")}</output>
          <output>{exampleBalance} € {t("gestion.memberSettings.balanceUnit")}</output>
        </div>
      </header>

      <div className="member-settings-grid">
        <section className="member-settings-panel">
          <header>
            <div><span className="member-settings-index">{t("gestion.memberSettings.sectionPoints")}</span><h2>{t("gestion.memberSettings.pointsTitle")}</h2></div>
            <label className="member-settings-toggle">
              <input type="checkbox" checked={draft.pointsAccrualEnabled} disabled={disabled}
                onChange={(event) => setDraft({ ...draft, pointsAccrualEnabled: event.target.checked })} />
              <span>{stateLabel(draft.pointsAccrualEnabled)}</span>
            </label>
          </header>
          <p>{t("gestion.memberSettings.pointsDescription")}</p>
          <div className="member-settings-rule">
            <span>{t("gestion.memberSettings.ruleForEvery")}</span>
            <label><span className="sr-only">{t("gestion.memberSettings.pointsBaseAmount")}</span><input type="number" min="1" step="1"
              value={draft.pointsAccrualBaseAmount} disabled={disabled || !draft.pointsAccrualEnabled}
              onChange={(event) => setDraft({ ...draft, pointsAccrualBaseAmount: event.target.value })} /></label>
            <span>{t("gestion.memberSettings.ruleEurosCollected")}</span>
            <span className="member-settings-rule-action">{t("gestion.memberSettings.ruleEarn")}</span>
            <label><span className="sr-only">{t("gestion.memberSettings.pointsPerEuro")}</span><input type="number" min="0" max="1000" step="1"
              value={draft.pointsPerEuro} disabled={disabled || !draft.pointsAccrualEnabled}
              onChange={(event) => setDraft({ ...draft, pointsPerEuro: event.target.value })} /></label>
            <span>{t("gestion.memberSettings.rulePoints")}</span>
          </div>
        </section>

        <section className="member-settings-panel">
          <header>
            <div><span className="member-settings-index">{t("gestion.memberSettings.sectionBalance")}</span><h2>{t("gestion.memberSettings.balanceTitle")}</h2></div>
            <label className="member-settings-toggle">
              <input type="checkbox" checked={draft.balanceAccrualEnabled} disabled={disabled}
                onChange={(event) => setDraft({ ...draft, balanceAccrualEnabled: event.target.checked })} />
              <span>{stateLabel(draft.balanceAccrualEnabled)}</span>
            </label>
          </header>
          <p>{t("gestion.memberSettings.balanceDescription")}</p>
          <div className="member-settings-rule">
            <span>{t("gestion.memberSettings.ruleForEvery")}</span>
            <label><span className="sr-only">{t("gestion.memberSettings.balanceBaseAmount")}</span><input type="number" min="1" step="1"
              value={draft.balanceAccrualBaseAmount} disabled={disabled || !draft.balanceAccrualEnabled}
              onChange={(event) => setDraft({ ...draft, balanceAccrualBaseAmount: event.target.value })} /></label>
            <span>{t("gestion.memberSettings.ruleEurosCollected")}</span>
            <span className="member-settings-rule-action">{t("gestion.memberSettings.ruleGenerate")}</span>
            <label><span className="sr-only">{t("gestion.memberSettings.balancePercent")}</span><input type="number" min="0" max="100" step="1"
              value={draft.balanceAccrualPercent} disabled={disabled || !draft.balanceAccrualEnabled}
              onChange={(event) => setDraft({ ...draft, balanceAccrualPercent: event.target.value })} /></label>
            <span>{t("gestion.memberSettings.rulePercentBalance")}</span>
          </div>
        </section>
      </div>

      <div className="member-settings-secondary">
        <section className="member-settings-panel">
          <header><div><span className="member-settings-index">{t("gestion.memberSettings.sectionExpiration")}</span><h2>{t("gestion.memberSettings.expirationTitle")}</h2></div></header>
          <label className="member-settings-field"><span>{t("gestion.memberSettings.expirationPolicy")}</span>
            <select value={draft.balanceExpirationPolicy} disabled={disabled || !draft.balanceAccrualEnabled}
              onChange={(event) => setDraft({ ...draft, balanceExpirationPolicy: event.target.value })}>
              {expirationPolicies.map((policy) => <option key={policy} value={policy}>{t(`party.members.expiration.${policy}`)}</option>)}
            </select>
          </label>
          <small>{t("gestion.memberSettings.expirationHint")}</small>
        </section>
        <section className="member-settings-panel">
          <header><div><span className="member-settings-index">{t("gestion.memberSettings.sectionCategories")}</span><h2>{t("gestion.memberSettings.categoryTitle")}</h2></div></header>
          <label className="member-settings-check"><input type="checkbox" checked={draft.categoryAutoEnabled} disabled={disabled}
            onChange={(event) => setDraft({ ...draft, categoryAutoEnabled: event.target.checked })} />
            <span><strong>{t("gestion.memberSettings.autoCategories")}</strong><small>{t("gestion.memberSettings.autoCategoriesHint")}</small></span>
          </label>
        </section>
      </div>

      <section className="member-settings-panel member-settings-channels">
        <header><div><span className="member-settings-index">05</span><h2>{t("party.members.channelsTitle")}</h2></div></header>
        <p>{t("party.members.channelsHint")}</p>
        <form onSubmit={(event) => void saveChannel(event)}>
          <label className="member-settings-field"><span>{t("party.code")}</span><input value={channelDraft.code} disabled={disabled} onChange={(event) => setChannelDraft({ ...channelDraft, code: event.target.value })} /></label>
          <label className="member-settings-field"><span>{t("party.name")}</span><input value={channelDraft.name} disabled={disabled} onChange={(event) => setChannelDraft({ ...channelDraft, name: event.target.value })} /></label>
          <label className="member-settings-check"><input type="checkbox" checked={channelDraft.active} disabled={disabled} onChange={(event) => setChannelDraft({ ...channelDraft, active: event.target.checked })} /><span><strong>{t("party.active")}</strong></span></label>
          <button type="submit" className="primary-button" disabled={disabled}>{t(channelDraft.id ? "party.members.updateChannel" : "party.members.createChannel")}</button>
        </form>
        {channelFeedback && <p className={channelFeedback === "party.members.channelSaved" ? "is-success" : "is-error"} role="status">{t(channelFeedback)}</p>}
        <div className="member-settings-channel-table" role="table" aria-label={t("party.members.channelsTitle")}>
          <div className="header" role="row"><span>{t("party.code")}</span><span>{t("party.name")}</span><span>{t("party.status")}</span><span>{t("common.actions")}</span></div>
          {channels.map((channel) => <div role="row" key={channel.id}><code>{channel.code}</code><strong>{channel.name}</strong><span>{t(channel.active ? "party.active" : "party.inactive")}</span><button type="button" disabled={disabled} onClick={() => { setChannelDraft({ ...channel }); setChannelFeedback(""); }}>{t("party.members.edit")}</button></div>)}
        </div>
      </section>

      <details className="member-settings-admin">
        <summary>{t("gestion.memberSettings.adminOptions")}</summary>
        <div>
          <label className="member-settings-check"><input type="checkbox" checked={draft.memberWelcomeEnabled} disabled={disabled}
            onChange={(event) => setDraft({ ...draft, memberWelcomeEnabled: event.target.checked })} /><span><strong>{t("party.members.welcomeEnabled")}</strong></span></label>
          <label className="member-settings-field"><span>{t("party.members.cardFormat")}</span><select value={draft.memberCardCodeFormat} disabled={disabled}
            onChange={(event) => setDraft({ ...draft, memberCardCodeFormat: event.target.value as "QR" | "BARCODE" })}><option value="QR">{t("gestion.memberSettings.cardFormat.QR")}</option><option value="BARCODE">{t("gestion.memberSettings.cardFormat.BARCODE")}</option></select></label>
          <label className="member-settings-field"><span>{t("party.members.welcomeSubject")}</span><input value={draft.welcomeSubjectTemplate ?? ""} disabled={disabled || !draft.memberWelcomeEnabled}
            onChange={(event) => setDraft({ ...draft, welcomeSubjectTemplate: event.target.value })} /></label>
          <label className="member-settings-field is-wide"><span>{t("party.members.welcomeBody")}</span><textarea value={draft.welcomeBodyTemplate ?? ""} disabled={disabled || !draft.memberWelcomeEnabled}
            onChange={(event) => setDraft({ ...draft, welcomeBodyTemplate: event.target.value })} /></label>
        </div>
      </details>

      <footer className="member-settings-actions">
        <div>{feedback && <span className={feedback === "saved" ? "is-success" : "is-error"}>{t(`gestion.memberSettings.${feedback}`)}</span>}
          {!canManage && <span className="is-error">{t("gestion.memberSettings.adminRequired")}</span>}</div>
        <button type="button" className="secondary-button" disabled={!dirty || saving} onClick={() => { setDraft(baseline); setFeedback(null); }}>{t("common.cancel")}</button>
        <button type="button" className="primary-button" disabled={!canManage || !dirty || saving} onClick={() => void save()}>{saving ? t("common.saving") : t("common.save")}</button>
      </footer>
    </section>
  );
}

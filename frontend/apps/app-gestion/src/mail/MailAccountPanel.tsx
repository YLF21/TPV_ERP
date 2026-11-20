import { useEffect, useId, useRef, useState } from "react";
import { ErpConfirmDialog } from "../../../../packages/app-common/src/components/ErpConfirmDialog";
import type { MailProps, OAuthStatus, SmtpSettings } from "./MailTypes";
import { MailFeedback, MailLoad, useMailAction, useMailResource } from "./MailShared";

type AccountDraft = { enabled: boolean; host: string; port: number; username: string; password: string; fromEmail: string; fromName: string; startTls: boolean; sslEnabled: boolean };
const smtpProviders = [
  { id: "gmail", label: "Gmail / Google Workspace", host: "smtp.gmail.com", port: 587, hint: "mail.gmailPasswordHint" },
  { id: "yahoo", label: "Yahoo Mail", host: "smtp.mail.yahoo.com", port: 465, hint: "mail.appPasswordHint" },
  { id: "qq", label: "QQ Mail", host: "smtp.qq.com", port: 465, hint: "mail.authorizationCodeHint" },
  { id: "icloud", label: "iCloud Mail", host: "smtp.mail.me.com", port: 587, hint: "mail.appPasswordHint" },
  { id: "netease163", label: "NetEase 163", host: "smtp.163.com", port: 465, hint: "mail.authorizationCodeHint" },
  { id: "exmail", label: "Tencent Exmail", host: "smtp.exmail.qq.com", port: 465, hint: "mail.smtpHint" },
] as const;
type SmtpProvider = typeof smtpProviders[number]["id"] | "custom";
const passwordPages: Partial<Record<SmtpProvider, { name: string; url: string }>> = {
  gmail: { name: "Google", url: "https://myaccount.google.com/apppasswords" },
  yahoo: { name: "Yahoo", url: "https://login.yahoo.com/account/security" },
  qq: { name: "QQ", url: "https://mail.qq.com/" },
  icloud: { name: "Apple", url: "https://account.apple.com/" },
  netease163: { name: "163", url: "https://mail.163.com/" },
  exmail: { name: "Exmail", url: "https://exmail.qq.com/login" },
};
const fromSettings = (value: SmtpSettings): AccountDraft => ({
  enabled: value.enabled, host: value.host ?? "", port: value.port || 587,
  username: value.username ?? "", password: "", fromEmail: value.fromEmail ?? "",
  fromName: value.fromName ?? "", startTls: !value.sslEnabled, sslEnabled: value.sslEnabled,
});

export function MailAccountPanel(props: MailProps) {
  const { t, session, request } = props;
  const resource = useMailResource<SmtpSettings>("/member-smtp-settings", props);
  const oauth = useMailResource<OAuthStatus>("/mail-oauth/status", props);
  const action = useMailAction(t);
  const testAction = useMailAction(t);
  const passwordHelpId = useId();
  const [draft, setDraft] = useState<AccountDraft | null>(null);
  const [smtpProvider, setSmtpProvider] = useState<SmtpProvider>("custom");
  const selectedProvider = smtpProviders.find((provider) => provider.id === smtpProvider);
  const passwordPage = passwordPages[smtpProvider];
  const [passwordStepsOpen, setPasswordStepsOpen] = useState(false);
  const [testEmail, setTestEmail] = useState("");
  const [ownAddress, setOwnAddress] = useState(false);
  const [authorizationUrl, setAuthorizationUrl] = useState("");
  const [pendingAction, setPendingAction] = useState<"refresh" | "disconnect" | null>(null);
  const edited = useRef(false);
  useEffect(() => {
    if (resource.value && !edited.current) {
      setDraft(fromSettings(resource.value));
      setSmtpProvider(smtpProviders.find((provider) => provider.host === resource.value?.host?.trim().toLowerCase())?.id ?? "custom");
    }
  }, [resource.value]);
  const dirty = draft !== null && resource.value !== null && JSON.stringify(draft) !== JSON.stringify(fromSettings(resource.value));
  const options = { token: session.accessToken };
  const patch = (change: Partial<AccountDraft>) => { edited.current = true; setDraft((value) => value && { ...value, ...change }); action.clearFeedback(); testAction.clearFeedback(); setAuthorizationUrl(""); };
  const oauthAvailable = oauth.value?.providers.some((provider) => provider.available);
  function refresh() { edited.current = false; oauth.reload(); resource.reload(); setAuthorizationUrl(""); }
  async function disconnect() {
    await action.run(async () => { await request("/mail-oauth/connection", { ...options, method: "DELETE" }); refresh(); }, "mail.disconnected");
  }

  async function connect(provider: "GOOGLE" | "MICROSOFT") {
    if (!draft?.fromEmail) return;
    await action.run(async () => {
      const result = await request<{ authorizationUrl: string }>("/mail-oauth/connect", { ...options, method: "POST", body: { provider, email: draft.fromEmail } });
      const url = new URL(result.authorizationUrl);
      const hosts = provider === "GOOGLE" ? ["accounts.google.com"] : ["login.microsoftonline.com", "login.live.com"];
      if (url.protocol !== "https:" || !hosts.includes(url.hostname) || url.port || url.username || url.password) throw new Error("Invalid authorization URL");
      setAuthorizationUrl(url.href);
    });
  }

  return <MailLoad {...resource} t={t}>
    {draft && <div className="mail-column mail-account-layout">
      <section className="mail-card">
        <h3>{t("mail.accountTitle")}</h3>
        <p>{t("mail.accountHint")}</p>
        <p className="mail-status" role="status">{t(oauth.value?.connection ? "mail.accountAuthorized" : resource.value?.configured ? "mail.accountSaved" : "mail.accountMissing")}</p>
        {!resource.value?.credentialsReady && <p className="mail-feedback is-warning">{t("mail.credentialsAttention")}</p>}
        <form onSubmit={(event) => { event.preventDefault(); void action.run(async () => {
          const saved = await request<SmtpSettings>("/member-smtp-settings", { ...options, method: "PUT", body: draft });
          edited.current = false; setDraft(fromSettings(saved));
          resource.setValue(saved);
        }, "mail.saved"); }}>
          <fieldset disabled={action.busy || testAction.busy}>
            <div className="mail-form-grid">
              <label>{t("mail.fromName")}<input value={draft.fromName} maxLength={160} onChange={(event) => patch({ fromName: event.target.value })} /></label>
              <label>{t("mail.fromEmail")}<input required type="email" value={draft.fromEmail} maxLength={320} onChange={(event) => patch({ fromEmail: event.target.value })} /></label>
            </div>
            <div className="mail-actions mail-provider-actions">
              {(["GOOGLE", "MICROSOFT"] as const).map((provider) => <button key={provider} type="button"
                disabled={!draft.fromEmail || !oauth.value?.providers.find((item) => item.provider === provider)?.available}
                onClick={() => void connect(provider)}>{t(`mail.connect.${provider}`)}</button>)}
              <button type="button" onClick={() => { if (dirty) setPendingAction("refresh"); else refresh(); }}>{t("mail.refreshConnection")}</button>
            </div>
            {!oauthAvailable && <p className="mail-muted">{t("mail.oauthUnavailable")}</p>}
            {oauth.error && <p role="alert">{t("mail.oauthLoadError")}</p>}
            {authorizationUrl && <p><a href={authorizationUrl} target="_blank" rel="noopener noreferrer">{t("mail.authorizeLink")}</a><br /><small>{t("mail.authorizeReturn")}</small></p>}
            {oauth.value?.connection && <div className="mail-actions"><span>{oauth.value.connection.email}</span><button type="button" onClick={() => { if (dirty) setPendingAction("disconnect"); else void disconnect(); }}>{t("mail.disconnect")}</button></div>}
            <details open={!resource.value?.configured && !oauth.value?.connection}>
              <summary>{t("mail.smtpOptions")}</summary>
              <p className="mail-muted">{t("mail.smtpHint")}</p>
              <div className={`mail-form-grid mail-connection-grid${smtpProvider === "custom" ? " is-custom" : ""}`}>
                <label>{t("mail.host")}<select value={smtpProvider} onChange={(event) => {
                  const providerId = event.target.value as SmtpProvider;
                  const provider = smtpProviders.find((item) => item.id === providerId);
                  setSmtpProvider(providerId);
                  if (provider) patch({ host: provider.host, port: provider.port, startTls: provider.port === 587, sslEnabled: provider.port === 465,
                    username: draft.fromEmail.trim() || draft.username });
                }}>
                  {smtpProviders.map((provider) => <option key={provider.id} value={provider.id}>{provider.label}</option>)}
                  <option value="custom">{t("mail.otherServer")}</option>
                </select></label>
                {smtpProvider === "custom" && <label>{t("mail.customHost")}<input required value={draft.host} maxLength={255} onChange={(event) => patch({ host: event.target.value })} autoComplete="off" /></label>}
                <label>{t("mail.port")}<input required type="number" min={1} max={65535} value={draft.port} onChange={(event) => patch({ port: Number(event.target.value) })} /></label>
                <label>{t("mail.security")}<select aria-label={t("mail.security")} value={draft.sslEnabled ? "ssl" : "starttls"} onChange={(event) => patch({ sslEnabled: event.target.value === "ssl", startTls: event.target.value === "starttls", port: event.target.value === "ssl" ? 465 : 587 })}>
                  <option value="starttls">STARTTLS · 587</option><option value="ssl">TLS · 465</option>
                </select></label>
              </div>
              <div className="mail-form-grid mail-credentials-grid">
                <label>{t("mail.username")}<input value={draft.username} maxLength={320} onChange={(event) => patch({ username: event.target.value })} autoComplete="off" /></label>
                <div className="mail-password-field">
                  <div className="mail-password-entry">
                  <label htmlFor={`${passwordHelpId}-input`}>{t("mail.password")}</label>
                  <input id={`${passwordHelpId}-input`} type="password" aria-describedby={passwordHelpId} value={draft.password} onChange={(event) => patch({ password: event.target.value })} autoComplete="new-password" />
                  {passwordPage && <a className="mail-provider-link" href={passwordPage.url} target="_blank" rel="noopener noreferrer"
                    title={t("mail.passwordBrowserHint")} onClick={() => setPasswordStepsOpen(true)}>
                    {t("mail.openPasswordProvider").replace("{provider}", passwordPage.name)} <span aria-hidden="true">↗</span>
                  </a>}
                  </div>
                  <div id={passwordHelpId} className="mail-password-help">
                    <p>{t(resource.value?.passwordConfigured ? "mail.passwordKeep" : "mail.passwordHint")}</p>
                    {selectedProvider && selectedProvider.hint !== "mail.smtpHint" && <p>{t(selectedProvider.hint)}</p>}
                    <details open={passwordStepsOpen} className="mail-password-steps">
                      <summary onClick={(event) => { event.preventDefault(); setPasswordStepsOpen((open) => !open); }}>{t("mail.passwordStepsTitle")}</summary>
                      <ol>
                        {[1, 2, 3].map((step) => <li key={step}>
                          {t(`mail.passwordSteps.${smtpProvider}.${step}`)}
                          {step === 1 && passwordPage && <a className="mail-password-url" href={passwordPage.url} target="_blank" rel="noopener noreferrer" title={t("mail.passwordBrowserHint")}>{passwordPage.url}</a>}
                        </li>)}
                        <li>{t("mail.passwordStepsFinish")}</li>
                      </ol>
                      <p>{t(smtpProvider === "gmail" ? "mail.googlePasswordUnavailable" : "mail.passwordOptionsUnavailable")}</p>
                    </details>
                  </div>
                </div>
              </div>
            </details>
            <label className="mail-check"><input type="checkbox" checked={draft.enabled} onChange={(event) => patch({ enabled: event.target.checked })} />{t("mail.accountEnabled")}</label>
            <div className="mail-actions"><button type="submit" className="primary-button" disabled={!dirty && resource.value?.credentialsReady}>{t("common.save")}</button></div>
          </fieldset>
        </form>
        <MailFeedback value={action.feedback} />
      </section>
      <section className="mail-card mail-test-card">
        <h3>{t("mail.testTitle")}</h3><p>{t("mail.testHint")}</p>
        {dirty && <p className="mail-muted">{t("mail.saveBeforeTest")}</p>}
        <form onSubmit={(event) => { event.preventDefault(); if (!ownAddress || dirty) return; void testAction.run(async () => {
          await request("/member-smtp-settings/test", { ...options, method: "POST", body: { toEmail: testEmail.trim(), subject: t("mail.testSubject"), body: t("mail.testBody") } });
        }, "mail.testAccepted"); }}>
          <fieldset disabled={action.busy || testAction.busy || dirty || !resource.value?.configured || !resource.value?.enabled}>
            <label>{t("mail.testEmail")}<input type="email" required value={testEmail} onChange={(event) => { setTestEmail(event.target.value); setOwnAddress(false); testAction.clearFeedback(); }} /></label>
            <label className="mail-check"><input type="checkbox" required checked={ownAddress} onChange={(event) => setOwnAddress(event.target.checked)} />{t("mail.ownAddress")}</label>
            <button type="submit" className="primary-button" disabled={!ownAddress}>{t(testAction.busy ? "mail.sendingTest" : "mail.sendTest")}</button>
          </fieldset>
        </form>
        <MailFeedback value={testAction.feedback} />
      </section>
      {pendingAction && <ErpConfirmDialog title={t("mail.discardAccountTitle")} message={t("mail.discardAccountChanges")}
        confirmLabel={t(pendingAction === "refresh" ? "mail.refresh" : "mail.disconnect")} cancelLabel={t("common.cancel")}
        onCancel={() => setPendingAction(null)} onConfirm={() => {
          const selected = pendingAction; setPendingAction(null);
          if (selected === "refresh") refresh(); else void disconnect();
        }} />}
    </div>}
  </MailLoad>;
}

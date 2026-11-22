import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { useI18n } from "../../i18n";
import { mailApi, mailErrorCode, type MailOAuthProvider, type MailOAuthStatus, type SmtpAccount, type SmtpSecurity } from "../../lib/mail-api";
import type { Credentials } from "../../lib/types";
import { RetryError } from "../../shared/ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { useMailCopy } from "./mail-copy";
import { recognizeSmtpProvider, SmtpProviderHelp, smtpProviders, type SmtpProviderId } from "./SmtpProviderHelp";
import { useSmtpProviderCopy } from "./smtp-provider-copy";

const oauthProviders = [
  { id: "GOOGLE", label: "connectGoogle", hosts: ["accounts.google.com"] },
  { id: "MICROSOFT", label: "connectMicrosoft", hosts: ["login.microsoftonline.com", "login.live.com"] },
] as const;

function draftFields(account: SmtpAccount | null) {
  return {
    host: account ? account.host ?? "" : smtpProviders[0].host,
    port: String(account?.port ?? 587),
    security: account?.security ?? "STARTTLS",
    username: account?.username ?? "",
    fromAddress: account?.fromAddress ?? "",
    fromName: account?.fromName ?? "",
    replyTo: account?.replyTo ?? "",
    password: "",
  };
}

function connectionIdentity(draft: ReturnType<typeof draftFields>, username = draft.username.trim()) {
  return JSON.stringify([draft.host.trim().toLowerCase(), Number(draft.port), draft.security, username]);
}

function authorizationLink(value: string, provider: MailOAuthProvider) {
  const url = new URL(value);
  const hosts: readonly string[] = oauthProviders.find(item => item.id === provider)?.hosts ?? [];
  if (url.protocol !== "https:" || !hosts.includes(url.hostname) || url.port || url.username || url.password) {
    throw new Error("Invalid mail authorization URL");
  }
  return url.href;
}

type CopyKey = keyof ReturnType<typeof useMailCopy>;
export type MailFeedback = { tone: "success" | "error"; key: CopyKey };
const errors: Record<string, CopyKey> = {
  SMTP_VERSION_CONFLICT: "conflict",
  SMTP_INVALID: "invalidSettings",
  SMTP_ENCRYPTION_UNAVAILABLE: "serverKey",
  SMTP_PASSWORD_REQUIRED: "passwordRequired",
  SMTP_ACCOUNT_DUPLICATE: "duplicateAccount",
  SMTP_ACCOUNT_NOT_FOUND: "accountMissing",
  SMTP_NOT_READY: "testNeedsConfiguration",
  SMTP_AUTH_FAILED: "authFailure",
  SMTP_TEST_RATE_LIMITED: "testRateLimited",
  SMTP_DELIVERY_UNCERTAIN: "testUncertain",
  MAIL_OAUTH_UNAVAILABLE: "oauthUnavailable",
  MAIL_OAUTH_DISCONNECT_REQUIRED: "oauthDisconnectRequired",
};

export function SmtpAccountForm({ credentials, initial, onSaved, onReload, onBusy, onDirty, onClose, restoreFocus }: {
  credentials: Credentials;
  initial: SmtpAccount | null;
  onSaved: (account: SmtpAccount) => void;
  onReload: (accounts: SmtpAccount[]) => void;
  onBusy: (busy: boolean) => void;
  onDirty: (dirty: boolean) => void;
  onClose: () => void;
  restoreFocus: () => HTMLElement | null;
}) {
  const { t } = useI18n();
  const copy = useMailCopy();
  const providerCopy = useSmtpProviderCopy();
  const [saved, setSaved] = useState(initial);
  const [testing, setTesting] = useState(Boolean(initial?.testReady));
  const [draft, setDraft] = useState(() => draftFields(initial));
  const [provider, setProvider] = useState<SmtpProviderId>(() => initial ? recognizeSmtpProvider(initial.host) : "gmail");
  const [oauth, setOAuth] = useState<MailOAuthStatus | null | undefined>();
  const [authorization, setAuthorization] = useState("");
  const [notice, setNotice] = useState<MailFeedback | null>(null);
  const [busy, setBusy] = useState(false);
  const [stale, setStale] = useState(false);
  const [recipient, setRecipient] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const pending = useRef(false);
  const sender = useRef<HTMLInputElement>(null);
  const details = useRef<HTMLDetailsElement>(null);
  const dirty = !testing && JSON.stringify(draft) !== JSON.stringify(draftFields(saved));

  useLayoutEffect(() => { onDirty(dirty); }, [dirty, onDirty]);

  useEffect(() => {
    if (testing) return;
    let active = true;
    void mailApi.oauthStatus(credentials, saved?.id).then(value => {
      if (active) setOAuth(value);
    }).catch(() => { if (active) setOAuth(null); });
    return () => { active = false; };
  }, [credentials, saved?.id, testing]);

  function smtpUsername(values: typeof draft, providerId = provider) {
    return values.username.trim() || (providerId === "custom" ? "" : values.fromAddress.trim());
  }

  function update(change: Partial<typeof draft>, providerId = provider) {
    setDraft(previous => {
      const next = { ...previous, ...change };
      if (change.password === undefined && connectionIdentity(previous, smtpUsername(previous)) !== connectionIdentity(next, smtpUsername(next, providerId))) {
        next.password = "";
      }
      return next;
    });
    setNotice(null);
  }

  function selectProvider(value: SmtpProviderId) {
    const option = smtpProviders.find(item => item.id === value);
    setProvider(value);
    update({ host: option?.host ?? "", port: option?.port ?? "587", security: option?.security ?? "STARTTLS" }, value);
  }

  function apply(account: SmtpAccount) {
    setSaved(account);
    setDraft(draftFields(account));
    setProvider(recognizeSmtpProvider(account.host));
    setTesting(account.testReady);
    setStale(false);
    onSaved(account);
  }

  async function act(action: () => Promise<void>, fallback: CopyKey) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    onBusy(true);
    setNotice(null);
    try {
      await action();
    } catch (error) {
      const code = mailErrorCode(error) ?? "";
      if (code === "SMTP_VERSION_CONFLICT" || code === "SMTP_ACCOUNT_NOT_FOUND") setStale(true);
      setNotice({ tone: "error", key: errors[code] ?? fallback });
    } finally {
      pending.current = false;
      setBusy(false);
      onBusy(false);
    }
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (stale || authorization) return;
    const values = draft;
    const username = smtpUsername(values);
    if (!values.host.trim()) {
      if (details.current) details.current.open = true;
      setNotice({ tone: "error", key: "invalidSettings" });
      return;
    }
    if (username && !values.password && (!saved?.hasPassword || connectionIdentity(values, username) !== connectionIdentity(draftFields(saved)))) {
      setNotice({ tone: "error", key: "passwordRequired" });
      return;
    }
    void act(async () => {
      const body = {
        enabled: false, portalUrl: "", version: saved?.version ?? 0,
        host: values.host.trim().toLowerCase(), port: Number(values.port), security: values.security,
        username,
        fromAddress: values.fromAddress.trim(), fromName: values.fromName.trim(), replyTo: values.replyTo.trim(),
        ...(values.password ? { password: values.password } : {}),
        ...(!username && saved?.hasPassword ? { clearPassword: true } : {}),
      };
      apply(saved ? await mailApi.updateAccount(credentials, saved.id, body) : await mailApi.addAccount(credentials, body));
      setNotice({ tone: "success", key: "accountSaved" });
    }, "saveError");
  }

  function connect(selected: MailOAuthProvider) {
    if (stale || !sender.current?.form?.reportValidity()) return;
    if (saved?.hasPassword && !window.confirm(copy.oauthReplacePassword)) return;
    void act(async () => {
      const email = draft.fromAddress.trim();
      const metadata = {
        fromName: draft.fromName.trim(), replyTo: draft.replyTo.trim(), portalUrl: "", enabled: false,
      };
      let target = saved;
      if (!target) {
        target = await mailApi.addAccount(credentials, {
          ...metadata, fromAddress: email, host: "", port: 587, security: "STARTTLS", username: "", version: 0,
        });
        setSaved(target);
        onSaved(target);
      }
      const response = await mailApi.connect(credentials, { ...metadata, accountId: target.id, email, version: target.version, provider: selected });
      setAuthorization(authorizationLink(response.authorizationUrl, selected));
    }, "oauthConnectError");
  }

  function refreshAuthorization() {
    if (!authorization && dirty && !window.confirm(copy.discard)) return;
    void act(async () => {
      if (!saved) {
        setOAuth(await mailApi.oauthStatus(credentials));
        return;
      }
      const accounts = await mailApi.accounts(credentials);
      onReload(accounts);
      const account = accounts.find(item => item.id === saved.id);
      if (!account) throw new Error("Account missing");
      if (account.testReady && (!authorization || account.version !== saved.version)) {
        apply(account);
        setAuthorization("");
        setNotice({ tone: "success", key: "accountSaved" });
      } else {
        setSaved(account);
        setOAuth(await mailApi.oauthStatus(credentials, account.id));
      }
    }, "loadError");
  }

  function reloadAccount() {
    if (dirty && !window.confirm(copy.discard)) return;
    void act(async () => {
      const accounts = await mailApi.accounts(credentials);
      onReload(accounts);
      const account = accounts.find(item => item.id === saved?.id);
      if (account) apply(account);
      else setNotice({ tone: "error", key: "accountMissing" });
    }, "loadError");
  }

  function disconnect() {
    if (!saved || stale || !window.confirm(copy[saved.defaultAccount ? "oauthDisconnectConfirm" : "oauthDisconnectAccount"])) return;
    void act(async () => {
      await mailApi.disconnect(credentials, saved.id, saved.version);
      const account = (await mailApi.accounts(credentials)).find(item => item.id === saved.id);
      if (!account) throw new Error("Account missing");
      apply(account);
      setAuthorization("");
      setNotice({ tone: "success", key: saved.defaultAccount ? "oauthDisconnected" : "oauthAccountDisconnected" });
    }, "oauthDisconnectError");
  }

  function test(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (stale || !saved?.testReady || !recipient.trim() || !confirmed) return;
    void act(async () => {
      await mailApi.test(credentials, saved.id, recipient.trim());
      setNotice({ tone: "success", key: "accepted" });
      setConfirmed(false);
    }, "testError");
  }

  const title = testing ? copy.test : !saved ? copy.addAccount
    : saved.testReady ? copy.editConnection : copy.continueConnection;

  return <WorkspaceDialog className="smtp-dialog" title={title} subtitle={t("smtpSettings")} closeLabel={t("close")}
    busy={busy} onClose={onClose} restoreFocus={restoreFocus}>
    {notice?.tone === "error" && stale
      ? <RetryError message={copy[notice.key]} onRetry={reloadAccount} />
      : notice && <p className={`notice ${notice.tone}`} role={notice.tone === "error" ? "alert" : "status"}>{copy[notice.key]}</p>}
    {busy && <p className="module-help" role="status">{t("loadingSaas")}</p>}
    {testing && saved ? <form onSubmit={test} className="smtp-test-form">
      <strong className="smtp-test-address">{saved.fromAddress}</strong>
      <label>{copy.recipient}<input type="email" className="control-input" value={recipient} maxLength={254}
        required disabled={busy} onChange={event => { setRecipient(event.target.value); setConfirmed(false); }} /></label>
      <label className="smtp-checkbox">
        <input type="checkbox" checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} />
        {copy.ownAddress}
      </label>
      <div className="smtp-actions">
        <button type="button" className="secondary-button smtp-edit-connection" disabled={busy || stale}
          onClick={() => { setTesting(false); setNotice(null); }}>{copy.editConnection}</button>
        <button type="submit" className="primary-button" disabled={busy || stale || !recipient.trim() || !confirmed}>{copy.test}</button>
      </div>
    </form> : <>
      <form onSubmit={save} className="smtp-form">
        <fieldset className="smtp-fields" disabled={busy || stale || Boolean(authorization)}>
          {!saved?.oauthProvider && <label className="smtp-wide">{providerCopy("provider")}
            <select className="control-input" aria-label={providerCopy("provider")} value={provider}
              onChange={event => selectProvider(event.target.value as SmtpProviderId)}>
              {smtpProviders.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
              <option value="custom">{providerCopy("otherServer")}</option>
            </select>
          </label>}
          <label>{copy.sender}<input ref={sender} type="email" className="control-input" value={draft.fromAddress} maxLength={254}
            autoComplete="off" required readOnly={Boolean(saved?.testReady || saved?.oauthProvider)}
            onChange={event => update({ fromAddress: event.target.value })} /></label>
          <label>{copy.senderName}<input className="control-input" value={draft.fromName} maxLength={120}
            onChange={event => update({ fromName: event.target.value })} /></label>
          {!saved?.oauthProvider && <><label className="smtp-wide">{copy.smtpPassword}<input type="password" className="control-input" value={draft.password}
            maxLength={2048} autoComplete="new-password" onChange={event => update({ password: event.target.value })} /></label>
          {saved?.hasPassword && <p className="module-help smtp-wide">{copy.passwordSet}</p>}
          <SmtpProviderHelp key={`help-${provider}`} providerId={provider} />
          <details key={`advanced-${provider}`} ref={details} className="smtp-wide smtp-advanced" open={provider === "custom" || undefined}>
            <summary>{copy.advanced}</summary>
            <div className="smtp-fields">
              <label>{copy.host}<input className="control-input" value={draft.host} maxLength={253}
                onChange={event => update({ host: event.target.value })} /></label>
              <label>{copy.port}<input className="control-input" type="number" min={1} max={65535} value={draft.port}
                required onChange={event => update({ port: event.target.value })} /></label>
              <label>{copy.security}<select className="control-input" aria-label={copy.security} value={draft.security}
                onChange={event => update({ security: event.target.value as SmtpSecurity })}>
                <option value="STARTTLS">STARTTLS</option><option value="SSL">SSL/TLS</option>
              </select></label>
              <label>{copy.smtpUsername}<input className="control-input" autoComplete="off" value={draft.username}
                placeholder={provider === "custom" ? undefined : draft.fromAddress}
                maxLength={254} onChange={event => update({ username: event.target.value })} /></label>
              <label className="smtp-wide">{copy.replyTo}<input className="control-input" type="email" value={draft.replyTo}
                maxLength={254} onChange={event => update({ replyTo: event.target.value })} /></label>
            </div>
          </details></>}
          {saved?.oauthProvider && <label className="smtp-wide">{copy.replyTo}<input className="control-input" type="email" value={draft.replyTo}
            maxLength={254} onChange={event => update({ replyTo: event.target.value })} /></label>}
        </fieldset>
        {!saved?.oauthProvider && <div className="smtp-actions">
          <button type="submit" className="primary-button" disabled={busy || stale || Boolean(authorization)}>{copy.saveAccount}</button>
        </div>}
      </form>
      <details className="smtp-oauth" open={saved?.oauthProvider ? true : undefined}>
        <summary>{copy.otherConnections}</summary>
        {authorization ? <>
          <p className="module-help">{copy.oauthAuthorizeReturn}</p>
          <a className="smtp-help-link" href={authorization} target="_blank" rel="noopener noreferrer">
            {copy.oauthAuthorizeLink} <span aria-hidden="true">↗</span>
          </a>
        </> : <>
          {oauth === null && <p className="module-help" role="alert">{copy.oauthStatusError}</p>}
          {oauth && !oauth.providers.some(item => item.available) && <p className="module-help">{copy.oauthUnavailable}</p>}
          <div className="smtp-actions">
            {oauthProviders.filter(option => (!saved?.oauthProvider || saved.oauthProvider === option.id)
              && oauth?.providers.some(item => item.provider === option.id && item.available))
              .map(option => <button type="button" key={option.id} className="secondary-button"
                disabled={busy || stale || !draft.fromAddress.trim()} onClick={() => connect(option.id)}>{copy[saved?.oauthProvider ? "reauthorize" : option.label]}</button>)}
            {saved?.oauthProvider && <button type="button" className="secondary-button" disabled={busy || stale} onClick={disconnect}>
              {copy.oauthDisconnect}</button>}
          </div>
        </>}
        {(authorization || oauth === null || saved) && <button type="button" className="secondary-button" disabled={busy || stale}
          onClick={refreshAuthorization}>{copy.oauthRefresh}</button>}
      </details>
    </>}
  </WorkspaceDialog>;
}

import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useI18n } from "../../i18n";
import { mailApi, mailErrorCode, type SmtpAccount } from "../../lib/mail-api";
import type { Credentials } from "../../lib/types";
import { RetryError, StatusPill } from "../../shared/ui";
import { SmtpAccountForm, type MailFeedback } from "./SmtpAccountForm";
import { useMailCopy } from "./mail-copy";
import "./mail.css";

export function SmtpSettingsView({ credentials, navigationGuard }: {
  credentials: Credentials;
  navigationGuard: RefObject<(() => boolean) | null>;
}) {
  const { t } = useI18n();
  const copy = useMailCopy();
  const [accounts, setAccounts] = useState<SmtpAccount[] | null>(null);
  const [editing, setEditing] = useState<{ account: SmtpAccount | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [notice, setNotice] = useState<MailFeedback | null>(null);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const pending = useRef(false);
  const opener = useRef<HTMLButtonElement | null>(null);
  const addButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setNotice(null);
    void mailApi.accounts(credentials).then(result => {
      if (active) setAccounts(result);
    }).catch(() => {
      if (active) setNotice({ tone: "error", key: "loadError" });
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [credentials, revision]);

  function canClose() {
    return !pending.current && (!dirty || window.confirm(copy.discard));
  }

  useLayoutEffect(() => {
    navigationGuard.current = canClose;
    return () => { navigationGuard.current = null; };
  }, [navigationGuard, dirty, copy.discard]);

  useEffect(() => {
    if (!dirty && !busy) return;
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, busy]);

  function open(account: SmtpAccount | null, button: HTMLButtonElement) {
    opener.current = button;
    setEditing({ account });
    setNotice(null);
  }

  function close() {
    if (!canClose()) return;
    setEditing(null);
    setDirty(false);
  }

  function updateAccount(next: SmtpAccount) {
    setAccounts(current => {
      const previous = current ?? [];
      const withNext = previous.some(account => account.id === next.id)
        ? previous.map(account => account.id === next.id ? next : account)
        : [...previous, next];
      return next.defaultAccount ? withNext.map(account => account.id === next.id ? account : { ...account, defaultAccount: false }) : withNext;
    });
  }

  async function remove(account: SmtpAccount) {
    if (pending.current || !window.confirm(`${account.fromAddress ?? ""}\n\n${copy[account.defaultAccount ? "removeDefaultConfirm" : "removeConfirm"]}`)) return;
    pending.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await mailApi.deleteAccount(credentials, account.id, account.version);
      setAccounts(current => current?.filter(item => item.id !== account.id) ?? []);
      setNotice({ tone: "success", key: "removed" });
    } catch (error) {
      const code = mailErrorCode(error);
      setNotice({ tone: "error", key: code === "SMTP_VERSION_CONFLICT" ? "conflict"
        : code === "SMTP_ACCOUNT_IN_USE" ? "accountInUse"
        : code === "SMTP_NOTICES_PENDING" ? "removeNoticesPending" : "removeError" });
    } finally {
      pending.current = false;
      setBusy(false);
      requestAnimationFrame(() => addButton.current?.focus());
    }
  }

  return <section className="content-section smtp-settings" aria-label={t("smtpSettings")} aria-busy={loading || busy}>
    <div className="smtp-list-header">
      <h2>{copy.accounts}</h2>
      <button type="button" className="primary-button" ref={addButton} disabled={!accounts || loading || busy}
        onClick={event => open(null, event.currentTarget)}>{copy.addAccount}</button>
    </div>
    {loading && <p role="status" className="module-help">{t("loadingSaas")}</p>}
    {notice?.tone === "error"
      ? <RetryError message={copy[notice.key]} onRetry={() => setRevision(value => value + 1)} />
      : notice && <p className="notice success" role="status">{copy[notice.key]}</p>}
    {accounts?.length === 0 && <p className="module-help smtp-empty">{copy.noAccounts}</p>}
    {accounts && accounts.length > 0 && <ul className="smtp-account-list">
      {accounts.map(account => <li key={account.id}>
        <div className="smtp-account-info">
          <button type="button" className="smtp-address" disabled={busy || loading}
            title={copy[account.testReady ? "test" : "continueConnection"]}
            onClick={event => open(account, event.currentTarget)}>{account.fromAddress || copy.continueConnection}</button>
          <StatusPill status={copy[account.testReady ? "connected" : "connectionIncomplete"]} tone={account.testReady ? "ok" : "warning"} />
        </div>
        <button type="button" className="secondary-button" disabled={busy || loading}
          aria-label={`${copy.removeAccount} ${account.fromAddress ?? ""}`}
          onClick={() => void remove(account)}>{copy.removeAccount}</button>
      </li>)}
    </ul>}
    {editing && <SmtpAccountForm credentials={credentials} initial={editing.account} onSaved={updateAccount}
      onReload={setAccounts} onDirty={setDirty} onClose={close}
      restoreFocus={() => opener.current?.isConnected ? opener.current : addButton.current} onBusy={value => {
        pending.current = value;
        setBusy(value);
      }} />}
  </section>;
}

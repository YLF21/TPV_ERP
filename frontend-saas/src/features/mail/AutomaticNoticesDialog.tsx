import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type RefObject } from "react";
import { useI18n } from "../../i18n";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { mailApi, mailErrorCode, type SmtpAccount } from "../../lib/mail-api";
import type { Credentials } from "../../lib/types";
import { RetryError, StatusPill } from "../../shared/ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { useMailCopy } from "./mail-copy";
import type { MailFeedback } from "./SmtpAccountForm";
import "./mail.css";

function validPortal(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password && !/[?#]/.test(value);
  } catch {
    return false;
  }
}

export function AutomaticNoticesDialog({ credentials, navigationGuard, onClose, restoreFocus, onOpenSmtp }: {
  credentials: Credentials;
  navigationGuard: RefObject<(() => boolean) | null>;
  onClose: () => void;
  restoreFocus: () => HTMLElement | null;
  onOpenSmtp: () => void;
}) {
  const { t } = useI18n();
  const labels = useWorkspaceLabels();
  const copy = useMailCopy();
  const [settings, setSettings] = useState<Awaited<ReturnType<typeof mailApi.settings>> | null>(null);
  const [accounts, setAccounts] = useState<SmtpAccount[]>([]);
  const [accountId, setAccountId] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [portalUrl, setPortalUrl] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<MailFeedback | null>(null);
  const [conflict, setConflict] = useState(false);
  const [revision, setRevision] = useState(0);
  const pending = useRef(false);
  const selected = accounts.find(account => account.id === accountId);
  const dirty = settings !== null && (enabled !== settings.enabled || portalUrl !== (settings.portalUrl ?? "")
    || accountId !== (accounts.some(account => account.id === settings.id) ? settings.id : ""));
  const canSave = dirty && !conflict && !loading && !busy && selected && (!enabled || selected.testReady);

  function canLeave() {
    return !pending.current && (!dirty || window.confirm(copy.noticeDiscard));
  }

  useLayoutEffect(() => {
    navigationGuard.current = canLeave;
    return () => { navigationGuard.current = null; };
  }, [navigationGuard, dirty, copy.noticeDiscard]);

  useEffect(() => {
    if (!dirty && !busy) return;
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, busy]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setNotice(null);
    void Promise.all([mailApi.settings(credentials), mailApi.accounts(credentials)]).then(([settings, accounts]) => {
      if (!active) return;
      setAccounts(accounts.filter(account => account.testReady || account.id === settings.id));
      setSettings(settings);
      setAccountId(accounts.some(account => account.id === settings.id) ? settings.id ?? "" : "");
      setEnabled(settings.enabled);
      setPortalUrl(settings.portalUrl ?? "");
      setConflict(false);
    }).catch(() => {
      if (active) setNotice({ tone: "error", key: "loadError" });
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [credentials, revision]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSave || !settings || !settings.id || pending.current) return;
    const portal = portalUrl.trim();
    if ((enabled || portal) && !validPortal(portal)) {
      setNotice({ tone: "error", key: "invalidPortalUrl" });
      return;
    }
    pending.current = true;
    setBusy(true);
    setNotice(null);
    const body = {
      accountId: selected.id, enabled, portalUrl: portal, version: selected.version,
      defaultVersion: settings.version, defaultAccountId: settings.id,
    };
    try {
      let result: SmtpAccount;
      try {
        result = await mailApi.saveNotices(credentials, body);
      } catch (error) {
        if (mailErrorCode(error) !== "SMTP_NOTICES_PENDING") throw error;
        if (!window.confirm(copy.noticePending)) return;
        result = await mailApi.saveNotices(credentials, { ...body, confirmPending: true });
      }
      setSettings(result);
      setAccounts(current => current.map(account => account.id === result.id ? result
        : account.id === settings.id ? { ...account, version: settings.version + 1, defaultAccount: false, enabled: false, portalUrl: "" }
        : account).filter(account => account.testReady || account.id === result.id));
      setEnabled(result.enabled);
      setPortalUrl(result.portalUrl ?? "");
      setNotice({ tone: "success", key: "noticeSaved" });
    } catch (error) {
      const code = mailErrorCode(error);
      if (code === "SMTP_VERSION_CONFLICT" || code === "SMTP_ACCOUNT_NOT_FOUND") setConflict(true);
      setNotice({ tone: "error", key: code === "SMTP_VERSION_CONFLICT" ? "conflict"
        : code === "SMTP_ACCOUNT_NOT_FOUND" ? "accountMissing"
        : code === "SMTP_NOT_READY" ? "testNeedsConfiguration" : "noticeSaveFailed" });
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  function reload() {
    if (canLeave()) setRevision(current => current + 1);
  }

  return <WorkspaceDialog className="smtp-dialog" title={copy.automatic} subtitle={t("communications")} closeLabel={t("close")}
    busy={busy} onClose={() => { if (canLeave()) onClose(); }} restoreFocus={restoreFocus}>
    {loading && <p role="status" className="module-help">{t("loadingSaas")}</p>}
    {notice?.tone === "error" && (notice.key === "loadError" || conflict)
      ? <RetryError message={copy[notice.key]} onRetry={reload} />
      : notice && <p role={notice.tone === "error" ? "alert" : "status"} className={`notice ${notice.tone}`}>{copy[notice.key]}</p>}
    {settings && <form className="smtp-notices-form" onSubmit={save}>
      <p className="module-help">{copy.requestHelp}</p>
      <div>
        <StatusPill status={copy[settings.enabled ? settings.configured ? "ready" : "incomplete" : "disabled"]}
          tone={settings.enabled ? settings.configured ? "ok" : "warning" : "muted"} />
      </div>
      {accounts.length === 0 ? <>
        <p className="module-help">{copy.noNoticeAccounts}</p>
        <div><button type="button" className="secondary-button" onClick={onOpenSmtp}>{t("smtpSettings")}</button></div>
      </> : <>
        <label>{copy.noticeSender}
          <select className="control-input" aria-label={copy.noticeSender} value={accountId} disabled={busy || loading || conflict} onChange={event => {
            setAccountId(event.target.value);
            setNotice(null);
          }}>
            <option value="" disabled>{copy.selectNoticeSender}</option>
            {accounts.map(account => <option key={account.id} value={account.id}>{account.fromAddress}</option>)}
          </select>
        </label>
        <label className="smtp-checkbox">
          <input type="checkbox" checked={enabled} disabled={busy || loading || conflict || (!enabled && !selected?.testReady)}
            onChange={event => { setEnabled(event.target.checked); setNotice(null); }} />{copy.enabled}
        </label>
        <label>{copy.portalUrl}<input className="control-input" type="url" value={portalUrl} maxLength={1024}
          required={enabled} disabled={busy || loading || conflict} onChange={event => { setPortalUrl(event.target.value); setNotice(null); }} /></label>
        <div className="smtp-actions"><button type="submit" className="primary-button" disabled={!canSave}>{labels("save")}</button></div>
      </>}
    </form>}
  </WorkspaceDialog>;
}

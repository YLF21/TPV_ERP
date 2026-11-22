import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type RefObject } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useI18n } from "../../i18n";
import { useWorkspaceLabels } from "../../i18n/workspace";
import {
  COMMUNICATION_HTML_LIMIT, COMMUNICATION_SUBJECT_LIMIT, communicationsApi,
  type CommunicationContent, type CommunicationDetail, type CommunicationDraft, type CommunicationRecipient,
  type CommunicationReview, type CommunicationSummary,
} from "../../lib/communications-api";
import { mailApi, mailErrorCode, type SmtpAccount } from "../../lib/mail-api";
import type { Credentials } from "../../lib/types";
import { formatDate } from "../../shared/lib";
import { Input, RetryError, StatusPill } from "../../shared/ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { AutomaticNoticesDialog } from "../mail/AutomaticNoticesDialog";
import { useMailCopy } from "../mail/mail-copy";
import { AudienceDirectory, CommunicationHistory, DeliveryCountsRow, RecipientTable } from "./CommunicationTables";
import { EmailEditor, EmailPreview } from "./EmailEditor";
import { useCommunicationCopy, type CommunicationCopyKey } from "./communication-copy";
import "./communications.css";

type Modal =
  | { type: "preview"; content: CommunicationContent }
  | { type: "recipients" }
  | { type: "review"; id: string; review: CommunicationReview }
  | { type: "test"; id: string; version: number };
type Message = { type: "error" | "success"; key: CommunicationCopyKey };

const communicationErrors: Record<string, CommunicationCopyKey> = {
  COMMUNICATION_INVALID: "invalidContent",
  COMMUNICATION_NOT_FOUND: "missing",
  COMMUNICATION_VERSION_CONFLICT: "versionConflict",
  COMMUNICATION_REVIEW_CHANGED: "reviewChanged",
  COMMUNICATION_EMPTY: "contentRequired",
  COMMUNICATION_RECIPIENT_UNAVAILABLE: "eligibilityChanged",
  COMMUNICATION_STATE_CONFLICT: "versionConflict",
  SMTP_NOT_READY: "smtpNotReady",
  SMTP_ACCOUNT_NOT_FOUND: "senderUnavailable",
  SMTP_TEST_RATE_LIMITED: "testRateLimited",
  SMTP_AUTH_FAILED: "authorizationFailed",
  SMTP_DELIVERY_FAILED: "deliveryFailed",
  SMTP_DELIVERY_UNCERTAIN: "testUncertain",
  SMTP_SENDER_CHANGED: "senderChanged",
};

function emptyDraft(): CommunicationDraft {
  return { subject: "", html: "", audienceMode: "SELECTED", recipientIds: [], senderAccountId: null };
}

function draftFields(source: CommunicationDraft): CommunicationDraft {
  const { subject, html, audienceMode, recipientIds, senderAccountId } = source;
  return { subject, html, audienceMode, recipientIds: audienceMode === "ALL" ? [] : recipientIds, senderAccountId: senderAccountId ?? null };
}

function isSameDraft(a: CommunicationDraft, b: CommunicationDraft) {
  const ids = new Set(b.recipientIds);
  return a.subject === b.subject && a.html === b.html && a.audienceMode === b.audienceMode && a.senderAccountId === b.senderAccountId
    && (a.audienceMode === "ALL" || (a.recipientIds.length === ids.size && a.recipientIds.every(id => ids.has(id))));
}

function Feedback({ message }: { message: Message | null }) {
  const copy = useCommunicationCopy();
  if (!message) return null;
  return <p className={`notice ${message.type}`} role={message.type === "error" ? "alert" : "status"}>{copy(message.key)}</p>;
}

export function CommunicationsView({ credentials, navigationGuard, onOpenSmtp }: {
  credentials: Credentials;
  navigationGuard: RefObject<(() => boolean) | null>;
  onOpenSmtp: () => void;
}) {
  const { t } = useI18n();
  const l = useWorkspaceLabels();
  const copy = useCommunicationCopy();
  const mailCopy = useMailCopy();
  const [detail, setDetail] = useState<CommunicationDetail | null>(null);
  const [draft, setDraft] = useState<CommunicationDraft | null>(null);
  const [revision, setRevision] = useState(0);
  const [editorRevision, setEditorRevision] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [failingOpen, setFailingOpen] = useState<CommunicationSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [modal, setModal] = useState<Modal | null>(null);
  const [noticesOpen, setNoticesOpen] = useState(false);
  const noticesGuard = useRef<(() => boolean) | null>(null);
  const operation = useRef(false);
  const opener = useRef<HTMLElement | null>(null);
  const historyTrigger = useRef<HTMLElement | null>(null);
  const historyFocus = useRef(false);
  const history = useRef<HTMLDivElement>(null);
  const newButton = useRef<HTMLButtonElement>(null);
  const subject = useRef<HTMLInputElement>(null);
  const sentTitle = useRef<HTMLHeadingElement>(null);
  const historyRow = useRef<string | null>(null);
  const composing = draft !== null;
  const reading = detail !== null && !composing;
  const accounts = useRemote(() => composing ? mailApi.accounts(credentials) : Promise.resolve<SmtpAccount[]>([]),
    [credentials.accessToken, composing]);
  const account = accounts.data?.find(item => item.id === draft?.senderAccountId);
  const readyAccounts = accounts.data?.filter(item => item.testReady) ?? [];
  const dirty = composing && !isSameDraft(draft, detail ?? emptyDraft());
  const discard = copy("discard");

  function canLeave() {
    return !operation.current && (noticesGuard.current?.() ?? true) && (!dirty || window.confirm(discard));
  }

  useLayoutEffect(() => {
    navigationGuard.current = canLeave;
    return () => { navigationGuard.current = null; };
  }, [navigationGuard, dirty, discard]);

  useEffect(() => {
    if (!dirty && !busy) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, busy]);

  useEffect(() => {
    if (composing) subject.current?.focus();
    else if (reading) sentTitle.current?.focus();
    else if (historyFocus.current) {
      const row = historyRow.current;
      const previous = historyTrigger.current;
      const target = previous?.isConnected ? previous
        : row ? history.current?.querySelector<HTMLElement>(`tr[data-row-id="${CSS.escape(row)}"] button`) : null;
      (target ?? newButton.current)?.focus();
      historyFocus.current = false;
    }
  }, [composing, reading]);

  function apply(next: CommunicationDetail, htmlBefore?: string) {
    setDetail(next);
    setConflict(false);
    if (next.status === "DRAFT") {
      if (next.html !== htmlBefore) setEditorRevision(value => value + 1);
      setDraft(draftFields(next));
    } else setDraft(null);
  }

  function update(change: Partial<CommunicationDraft>) {
    setDraft(previous => previous && { ...previous, ...change });
    setMessage(null);
  }

  function openNew(event: React.MouseEvent<HTMLButtonElement>) {
    if (operation.current) return;
    historyRow.current = null;
    historyTrigger.current = event.currentTarget;
    setFailingOpen(null);
    setMessage(null);
    setDraft(emptyDraft());
  }

  function back() {
    if (!canLeave()) return;
    historyFocus.current = true;
    setDraft(null);
    setDetail(null);
    setFailingOpen(null);
    setConflict(false);
    setMessage(null);
  }

  async function run(action: () => Promise<void>, fallback: CommunicationCopyKey) {
    if (operation.current) return;
    operation.current = true;
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (error) {
      const code = mailErrorCode(error) ?? "";
      if (code === "COMMUNICATION_VERSION_CONFLICT" || code === "COMMUNICATION_STATE_CONFLICT") {
        setConflict(true);
        setModal(null);
      } else if (code === "COMMUNICATION_REVIEW_CHANGED") {
        setModal(null);
      } else if (composing && (code === "SMTP_ACCOUNT_NOT_FOUND" || code === "SMTP_NOT_READY" || code === "SMTP_SENDER_CHANGED")) {
        setModal(null);
        accounts.reload();
      }
      setMessage({ type: "error", key: communicationErrors[code] ?? fallback });
    } finally {
      operation.current = false;
      setBusy(false);
    }
  }

  function open(row: CommunicationSummary) {
    if (operation.current) return;
    historyRow.current = row.id;
    historyTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setFailingOpen(row);
    void run(async () => {
      apply(await communicationsApi.detail(credentials, row.id));
      setFailingOpen(null);
    }, "loadError");
  }

  async function saveDraft() {
    if (!draft) throw new Error("Draft required");
    if (detail && !dirty) return detail;
    const fields = draftFields(draft);
    const saved = detail
      ? await communicationsApi.update(credentials, detail.id, { ...fields, version: detail.version })
      : await communicationsApi.create(credentials, fields);
    apply(saved, draft.html);
    setRevision(value => value + 1);
    return saved;
  }

  function refreshDetail() {
    if (!detail || !canLeave()) return;
    void run(async () => {
      apply(await communicationsApi.detail(credentials, detail.id), draft?.html);
      setRevision(value => value + 1);
    }, "loadError");
  }

  const canSave = Boolean(draft && account && draft.html.length <= COMMUNICATION_HTML_LIMIT && !conflict && !busy);
  const canReview = canSave && Boolean(account?.testReady && draft?.subject.trim() && draft?.html.trim());

  function openPreview(event: React.MouseEvent<HTMLButtonElement>) {
    if (!draft) return;
    const html = draft.html;
    opener.current = event.currentTarget;
    void run(async () => {
      setModal({ type: "preview", content: await communicationsApi.preview(credentials, html) });
    }, "requestError");
  }

  function openReview(event: React.MouseEvent<HTMLButtonElement>) {
    if (!canReview) return;
    opener.current = event.currentTarget;
    void run(async () => {
      const saved = await saveDraft();
      const review = await communicationsApi.review(credentials, saved.id, saved.version);
      setModal({ type: "review", id: saved.id, review });
    }, "requestError");
  }

  function openTest(event: React.MouseEvent<HTMLButtonElement>) {
    if (!canReview) return;
    opener.current = event.currentTarget;
    void run(async () => {
      const saved = await saveDraft();
      setModal({ type: "test", id: saved.id, version: saved.version });
    }, "saveError");
  }

  function send(id: string, review: CommunicationReview) {
    void run(async () => {
      const queued = await communicationsApi.send(credentials, id, {
        version: review.version, reviewId: review.reviewId, recipientCount: review.recipientCount,
      });
      apply(queued);
      setModal(null);
      setHistoryPage(0);
      setRevision(value => value + 1);
      setMessage({ type: "success", key: "queued" });
    }, "sendError");
  }

  function cancelPending() {
    if (!detail?.counts.pending || operation.current || !window.confirm(copy("cancelConfirm"))) return;
    void run(async () => {
      apply(await communicationsApi.cancel(credentials, detail.id, detail.version));
      setRevision(value => value + 1);
      setMessage({ type: "success", key: "canceled" });
    }, "requestError");
  }

  function retry(row: CommunicationRecipient) {
    if (!detail || operation.current) return;
    const uncertain = row.status === "UNCERTAIN";
    if (uncertain && !window.confirm(`${row.email}\n\n${copy("retryUncertain")}`)) return;
    void run(async () => {
      apply(await communicationsApi.retry(credentials, detail.id, row.id, row.attempts, uncertain));
      setRevision(value => value + 1);
      setMessage({ type: "success", key: "retried" });
    }, "requestError");
  }

  function test(id: string, version: number, recipient: string) {
    void run(async () => {
      await communicationsApi.test(credentials, id, version, recipient);
      setModal(null);
      setMessage({ type: "success", key: "testAccepted" });
    }, "requestError");
  }

  return <section className={`content-section communication-workspace table-workspace${composing ? " communication-workspace--compose" : reading ? "" : " communication-workspace--history"}`}
    aria-label={t("communications")}>
    {!modal && <Feedback message={message} />}
    {busy && !modal && <p role="status">{l("loading")}</p>}
    {!composing && !reading && <div className="communication-history" ref={history}>
      <div className="communication-actions communication-header">
        <h2>{copy("history")}</h2>
        <div className="communication-actions">
          <button type="button" className="secondary-button" disabled={busy} onClick={event => {
            opener.current = event.currentTarget;
            setNoticesOpen(true);
          }}>{mailCopy.automatic}</button>
          <button type="button" className="secondary-button" disabled={busy}
            onClick={() => setRevision(value => value + 1)}>{t("refresh")}</button>
          <button type="button" className="primary-button" ref={newButton} disabled={busy} onClick={openNew}>{copy("new")}</button>
        </div>
      </div>
      <CommunicationHistory credentials={credentials} onOpen={open} disabled={busy} revision={revision} page={historyPage} onPage={setHistoryPage} />
      {failingOpen && message && <button type="button" className="secondary-button" disabled={busy}
        onClick={() => open(failingOpen)}>{t("retry")}</button>}
    </div>}
    {(draft || reading) && <div className="communication-actions">
      <button type="button" className="secondary-button" disabled={busy} onClick={back}>{copy("back")}</button>
      {(conflict || reading) && <button type="button" className="secondary-button" disabled={busy} onClick={refreshDetail}>
        {conflict ? copy("reloadDraft") : t("refresh")}</button>}
    </div>}
    {draft && <>
      <div className="communication-actions communication-header">
        <h2>{copy(detail ? "draft" : "new")}</h2>
        {dirty && <StatusPill status={copy("unsaved")} tone="warning" />}
      </div>
      <div className="communication-envelope">
        <label className="communication-field">
          {copy("from")}
          <select className="control-input" aria-label={copy("from")} value={draft.senderAccountId ?? ""} disabled={busy || conflict || accounts.loading}
            onChange={event => update({ senderAccountId: event.target.value })}>
            <option value="" disabled>{copy("chooseSender")}</option>
            {draft.senderAccountId && !account?.testReady &&
              <option value={draft.senderAccountId} disabled>{account?.fromAddress || copy("senderUnavailableOption")}</option>}
            {readyAccounts.map(item => <option key={item.id} value={item.id}>
              {item.fromName ? `${item.fromName} <${item.fromAddress}>` : item.fromAddress}
            </option>)}
          </select>
        </label>
        <div className="communication-field">
          <span>{copy("to")}</span>
          <div className="communication-to">
            <span>{draft.audienceMode === "ALL" ? copy("all") : `${copy("selectionCount")} ${draft.recipientIds.length}`}</span>
            <button type="button" className="secondary-button" disabled={busy || conflict} onClick={event => {
              opener.current = event.currentTarget;
              setModal({ type: "recipients" });
            }}>{copy("editRecipients")}</button>
          </div>
        </div>
        {accounts.error && <div className="communication-field-wide"><RetryError message={copy("loadSenders")} onRetry={accounts.reload} /></div>}
        {accounts.loading && <p className="module-help communication-field-wide" role="status">{t("loadingSaas")}</p>}
        {!accounts.loading && !accounts.error && (readyAccounts.length === 0 || Boolean(draft.senderAccountId && !account?.testReady)) &&
          <div className="communication-sender-help communication-field-wide">
            <span>{copy(draft.senderAccountId ? "senderUnavailable" : "smtpNotReady")}</span>
            <button type="button" className="secondary-button" onClick={onOpenSmtp}>{t("smtpSettings")}</button>
          </div>}
        <label className="communication-field communication-field-wide">
          {copy("subject")}
          <input className="control-input" ref={subject} value={draft.subject} maxLength={COMMUNICATION_SUBJECT_LIMIT}
            disabled={busy || conflict} onChange={event => update({ subject: event.target.value })} />
        </label>
      </div>
      <EmailEditor key={editorRevision} html={draft.html} disabled={busy || conflict} onChange={html => update({ html })} />
      <div className="communication-actions communication-compose-footer">
        <button className="secondary-button" type="button" disabled={!canSave || (!dirty && detail !== null)} onClick={() => {
          void run(async () => {
            await saveDraft();
            setMessage({ type: "success", key: "saved" });
          }, "saveError");
        }}>{copy("save")}</button>
        <button className="secondary-button" type="button" disabled={busy || !draft.html || draft.html.length > COMMUNICATION_HTML_LIMIT}
          onClick={openPreview}>{copy("preview")}</button>
        <button className="secondary-button" type="button" disabled={!canReview} onClick={openTest}>{copy("test")}</button>
        <button className="primary-button" type="button" disabled={!canReview} onClick={openReview}>{copy("review")}</button>
      </div>
    </>}
    {reading && <>
      <div className="communication-actions communication-header">
        <h2 tabIndex={-1} ref={sentTitle}>{detail.subject || copy("untitled")}</h2>
        <StatusPill status={copy(detail.status)} tone="muted" />
      </div>
      <dl className="communication-summary">
        {detail.fromAddress && <div><dt>{copy("sender")}</dt><dd>
          {detail.fromName ? `${detail.fromName} <${detail.fromAddress}>` : detail.fromAddress}
        </dd></div>}
        <div><dt>{copy("author")}</dt><dd>{detail.createdBy}</dd></div>
        <div><dt>{copy("updatedAt")}</dt><dd><time dateTime={detail.updatedAt}>{formatDate(detail.updatedAt)}</time></dd></div>
      </dl>
      <details className="communication-detail-preview">
        <summary>{copy("preview")}</summary>
        <EmailPreview content={detail} />
      </details>
      <h3>{copy("recipients")}</h3>
      <DeliveryCountsRow counts={detail.counts} />
      <p className="module-help">{copy("acceptedHelp")}</p>
      {detail.counts.pending > 0 && <div className="communication-actions">
        <button type="button" className="secondary-button" disabled={busy || conflict} onClick={cancelPending}>{copy("cancel")}</button>
      </div>}
      <RecipientTable key={detail.id} credentials={credentials} id={detail.id} revision={revision}
        disabled={busy || conflict} onRetry={detail.status === "CANCELLED" ? undefined : retry} />
    </>}
    {noticesOpen && <AutomaticNoticesDialog credentials={credentials} navigationGuard={noticesGuard} onOpenSmtp={onOpenSmtp}
      onClose={() => setNoticesOpen(false)} restoreFocus={() => opener.current} />}
    {modal && <WorkspaceDialog className="communication-dialog" title={copy(modal.type)} subtitle={t("communications")}
      closeLabel={t("close")} busy={busy} onClose={() => { setModal(null); setMessage(null); }} restoreFocus={() => opener.current}>
      <Feedback message={message} />
      {busy && <p role="status">{l("loading")}</p>}
      {modal.type === "preview" && <EmailPreview content={modal.content} />}
      {modal.type === "recipients" && draft && <AudienceForm credentials={credentials} draft={draft} onApply={selection => {
        update(selection);
        setModal(null);
      }} />}
      {modal.type === "review" && <ReviewForm credentials={credentials} id={modal.id} review={modal.review}
        busy={busy} onSend={() => send(modal.id, modal.review)} />}
      {modal.type === "test" && <TestForm busy={busy} onTest={value => test(modal.id, modal.version, value)} />}
    </WorkspaceDialog>}
  </section>;
}

function AudienceForm({ credentials, draft, onApply }: {
  credentials: Credentials;
  draft: CommunicationDraft;
  onApply: (selection: Pick<CommunicationDraft, "audienceMode" | "recipientIds">) => void;
}) {
  const copy = useCommunicationCopy();
  const [audienceMode, setMode] = useState(draft.audienceMode);
  const [recipientIds, setRecipients] = useState(draft.recipientIds);
  return <div className="communication-audience-form">
    <div className="communication-audience-mode" role="group" aria-label={copy("recipients")}>
      <label><input type="radio" name="communication-audience" checked={audienceMode === "SELECTED"}
        onChange={() => setMode("SELECTED")} />{copy("selected")}</label>
      <label><input type="radio" name="communication-audience" checked={audienceMode === "ALL"}
        onChange={() => setMode("ALL")} />{copy("all")}</label>
    </div>
    <p className="module-help">{copy("audienceHelp")}</p>
    {audienceMode === "SELECTED" && <AudienceDirectory credentials={credentials} selected={recipientIds}
      onChange={setRecipients} disabled={false} />}
    <div className="communication-actions communication-compose-footer">
      <button type="button" className="primary-button" onClick={() => onApply({ audienceMode, recipientIds })}>{copy("applyRecipients")}</button>
    </div>
  </div>;
}

function ReviewForm({ credentials, id, review, busy, onSend }: {
  credentials: Credentials;
  id: string;
  review: CommunicationReview;
  busy: boolean;
  onSend: () => void;
}) {
  const copy = useCommunicationCopy();
  const [confirmed, setConfirmed] = useState(false);
  const [listedTotal, setListedTotal] = useState<number | null>(null);
  const canSend = review.smtpReady && review.recipientCount > 0 && listedTotal === review.recipientCount && !busy;
  return <div className="communication-review">
    <dl className="communication-summary communication-review-meta">
      <div><dt>{copy("subject")}</dt><dd>{review.subject}</dd></div>
      <div><dt>{copy("sender")}</dt><dd>
        {review.fromName && review.fromAddress ? `${review.fromName} <${review.fromAddress}>` : review.fromAddress || "—"}
      </dd></div>
      {review.replyTo && <div><dt>{copy("replyTo")}</dt><dd>{review.replyTo}</dd></div>}
    </dl>
    <EmailPreview content={review} />
    <h4>{copy("reviewAudience")}</h4>
    <dl className="communication-summary">
      <div><dt>{copy("recipients")}</dt><dd>{review.recipientCount}</dd></div>
      <div><dt>{copy("omitted")}</dt><dd>{review.omittedCount}</dd></div>
      <div><dt>{copy("duplicate")}</dt><dd>{review.duplicateCount}</dd></div>
    </dl>
    <RecipientTable credentials={credentials} id={id} reviewId={review.reviewId} revision={0} disabled={busy}
      onLoadReview={setListedTotal} />
    {review.smtpReady
      ? <StatusPill status={copy("smtpReady")} tone="ok" />
      : <p role="status" className="notice error">{copy("smtpNotReady")}</p>}
    <form onSubmit={event => {
      event.preventDefault();
      if (canSend && confirmed) onSend();
    }}>
      <label className="communication-check">
        <input type="checkbox" checked={confirmed} disabled={!canSend} onChange={event => setConfirmed(event.target.checked)} />
        {copy("confirmation")}
      </label>
      <button type="submit" className="primary-button" disabled={!canSend || !confirmed}>{copy("send")}</button>
    </form>
  </div>;
}

function TestForm({ busy, onTest }: { busy: boolean; onTest: (recipient: string) => void }) {
  const copy = useCommunicationCopy();
  const [recipient, setRecipient] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!busy && confirmed) onTest(recipient.trim());
  }
  return <form className="communication-test" onSubmit={submit}>
    <p>{copy("testHelp")}</p>
    <Input label={copy("testRecipient")} type="email" value={recipient} maxLength={254} disabled={busy} required
      onChange={value => { setRecipient(value); setConfirmed(false); }} />
    <label className="communication-check">
      <input type="checkbox" disabled={busy} checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />
      {copy("testConfirm")}
    </label>
    <button type="submit" className="primary-button" disabled={busy || !confirmed || !recipient.trim()}>{copy("test")}</button>
  </form>;
}

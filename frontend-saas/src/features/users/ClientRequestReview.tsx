import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useI18n } from "../../i18n";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { api, ApiError } from "../../lib/api";
import { clientAccessApi, type ClientAccessRequestDetail, type ClientAccessRequestStatus } from "../../lib/client-access-api";
import type { Credentials } from "../../lib/types";
import type { StoreRow } from "../../lib/workspace-api";
import { formatDate, userManagementErrorMessage } from "../../shared/lib";
import type { Notice } from "../../shared/types";
import { StatusPill } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { RequestEmailStatus } from "../mail/RequestEmailStatus";
import { useClientUserLabels } from "./client-user-labels";
import { StoreAccessPicker } from "./StoreAccessPicker";

export function ClientRequestStatus({ status }: { status: ClientAccessRequestStatus }) {
  const labels = useClientUserLabels();
  return <StatusPill status={labels(status)} tone={status === "APPROVED" ? "ok" : status === "PENDING" ? "warning" : "muted"} />;
}

function Detail({ label, children, wide = false }: { label: string; children: ReactNode; wide?: boolean }) {
  return <div className={wide ? "client-user-detail-wide" : undefined}><dt>{label}</dt><dd>{children || "—"}</dd></div>;
}

export function ClientRequestReview({ credentials, requestId, onClose, onChanged, onNotice, restoreFocus }: {
  credentials: Credentials;
  requestId: string;
  onClose: () => void;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
  restoreFocus: () => HTMLElement | null;
}) {
  const { t } = useI18n();
  const labels = useClientUserLabels();
  const pending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [reviewed, setReviewed] = useState<ClientAccessRequestDetail | null>(null);
  const [reviewError, setReviewError] = useState("");
  const detail = useRemote(() => clientAccessApi.request(credentials, requestId), [credentials.accessToken, requestId]);
  const [lastDetail, setLastDetail] = useState<ClientAccessRequestDetail | null>(null);
  useEffect(() => { if (detail.data) setLastDetail(detail.data); }, [detail.data]);
  // Keep the review draft while revalidating an interrupted write. Never allow
  // another decision until the current server status has been read successfully.
  const entry = reviewed ?? detail.data ?? lastDetail;
  const revalidating = detail.loading || Boolean(detail.error);

  async function review(decision: "approve" | "reject", reason: string, stores: StoreRow[]) {
    if (!entry || entry.status !== "PENDING" || pending.current || revalidating) return;
    const grants = new Map<string, string[]>();
    for (const store of stores) {
      const selected = grants.get(store.companyId) ?? [];
      selected.push(store.id);
      grants.set(store.companyId, selected);
    }
    pending.current = true;
    setBusy(true);
    setReviewError("");
    try {
      const next = decision === "approve"
        ? await clientAccessApi.approve(credentials, entry.id, {
            version: entry.version, grants: Array.from(grants, ([companyId, storeIds]) => ({ companyId, storeIds })),
          })
        : await clientAccessApi.reject(credentials, entry.id, { version: entry.version, reason });
      setReviewed(next);
      onNotice({ type: "success", text: labels(decision === "approve" ? "approvalSuccess" : "rejectionSuccess") });
      onChanged();
    } catch (error) {
      if (error instanceof ApiError && (error.status === 409 || error.status === 404)) {
        setReviewError(userManagementErrorMessage(error));
      } else {
        setReviewError(labels("reviewFailed"));
      }
      // A rejected or interrupted write may race a review by another operator.
      detail.reload();
      onChanged();
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  return <WorkspaceDialog title={entry?.fullName || labels("requestDetail")} subtitle={labels("requestDetail")}
    busy={busy} closeLabel={t("close")} onClose={onClose} restoreFocus={restoreFocus} className="client-user-dialog">
    {(!entry || detail.loading || detail.error) && <LoadState {...detail} />}
    {reviewError && <p className="notice error" role="alert">{entry && entry.status !== "PENDING" ? labels("alreadyReviewed") : reviewError}</p>}
    {entry && <>
      <dl className="client-user-detail">
        <Detail label={t("username")}>{entry.username}</Detail>
        <Detail label={labels("name")}>{entry.fullName}</Detail>
        <Detail label={t("email")}>{entry.email}</Detail>
        <Detail label={labels("taxId")}>{entry.taxId}</Detail>
        <Detail label={t("phone")}>{entry.phone}</Detail>
        <Detail label={t("status")}><ClientRequestStatus status={entry.status} /></Detail>
        <Detail label={t("address")} wide>{entry.address}</Detail>
        <Detail label={t("postalCode")}>{entry.postalCode}</Detail>
        <Detail label={labels("city")}>{entry.city}</Detail>
        <Detail label={t("province")}>{entry.province}</Detail>
        <Detail label={t("country")}>{entry.country}</Detail>
        <Detail label={labels("submittedAt")}><time dateTime={entry.createdAt}>{formatDate(entry.createdAt)}</time></Detail>
        <Detail label={labels("requestedStores")} wide>
          {entry.requestedStores.length > 0 && <>
            <p className="module-help">{labels("requestedStoresHelp")}</p>
            <ul>{entry.requestedStores.map((store, index) => <li key={index}>{store}</li>)}</ul>
          </>}
        </Detail>
        {entry.notes && <Detail label={t("notes")} wide>{entry.notes}</Detail>}
        {entry.reviewedAt && <Detail label={labels("reviewedAt")}><time dateTime={entry.reviewedAt}>{formatDate(entry.reviewedAt)}</time></Detail>}
        {entry.reviewedBy && <Detail label={labels("reviewedBy")}>{entry.reviewedBy}</Detail>}
        {entry.reviewReason && <Detail label={labels("reason")} wide>{entry.reviewReason}</Detail>}
      </dl>
      {entry.status === "PENDING" ? <ReviewActions key={entry.version} credentials={credentials} busy={busy || revalidating}
        onReview={review} /> : <RequestEmailStatus key={entry.id} credentials={credentials} id={entry.id} initialDelivery={entry.delivery} />}
    </>}
  </WorkspaceDialog>;
}

function ReviewActions({ credentials, busy, onReview }: {
  credentials: Credentials;
  busy: boolean;
  onReview: (decision: "approve" | "reject", reason: string, stores: StoreRow[]) => Promise<void>;
}) {
  const l = useWorkspaceLabels();
  const labels = useClientUserLabels();
  const [choosingStores, setChoosingStores] = useState(false);
  const [companyId, setCompanyId] = useState("");
  const [selected, setSelected] = useState<StoreRow[]>([]);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const companies = useRemote(() => choosingStores ? api.companies(credentials) : Promise.resolve([]),
    [credentials.accessToken, choosingStores]);
  const validCompany = companies.data?.some(company => company.companyId === companyId) ?? false;

  function toggleStore(store: StoreRow, included: boolean) {
    setSelected(current => included ? [...current, store] : current.filter(value => value.id !== store.id));
  }

  function reject(event: FormEvent) {
    event.preventDefault();
    if (reason.trim()) void onReview("reject", reason.trim(), []);
  }

  return <div className="client-user-panel" aria-busy={busy}>
    {rejecting ? <form onSubmit={reject}>
      <label className="client-user-reason">{labels("rejectReason")}
        <textarea className="control-input" aria-label={labels("rejectReason")} value={reason} onChange={event => setReason(event.target.value)}
          maxLength={500} required autoFocus disabled={busy} />
      </label>
      <div className="client-user-review-actions">
        <button className="small-button danger" type="submit" disabled={busy || !reason.trim()}>{labels("submitRejection")}</button>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => setRejecting(false)}>{l("cancel")}</button>
      </div>
    </form> : <>
      <h4>{labels("selectedStores")} ({selected.length})</h4>
      {selected.length > 0 && <ul className="client-user-grants">
        {selected.map(store => <li key={store.id}>
          {store.companyName} · {store.code} · {store.name}
          <button type="button" className="small-button" aria-label={`${labels("removeStore")} ${store.code} · ${store.name}`}
            disabled={busy} onClick={() => toggleStore(store, false)}>{labels("removeStore")}</button>
        </li>)}
      </ul>}
      <p className="module-help">{labels(selected.length ? "approveWithStores" : "approveWithoutStores")}</p>
      {choosingStores ? <>
        <LoadState {...companies} />
        <div className="toolbar">
          <label>{l("company")}
            <select className="control-input" aria-label={l("company")} value={companyId} onChange={event => setCompanyId(event.target.value)}
              disabled={busy || companies.loading || Boolean(companies.error)}>
              <option value="">{l("selectCompany")}</option>
              {companies.data?.map(company => <option key={company.companyId} value={company.companyId}>{company.companyName}</option>)}
            </select>
          </label>
        </div>
        {validCompany && <StoreAccessPicker key={companyId} credentials={credentials} companyId={companyId}
          selectedIds={new Set(selected.map(store => store.id))} onToggle={toggleStore} disabled={busy} activeOnly />}
      </> : <button type="button" className="secondary-button" disabled={busy} onClick={() => setChoosingStores(true)}>{labels("chooseStores")}</button>}
      <div className="client-user-review-actions">
        <button type="button" className="primary-button" disabled={busy} onClick={() => void onReview("approve", "", selected)}>{labels("approve")}</button>
        <button type="button" className="small-button danger" disabled={busy} onClick={() => setRejecting(true)}>{labels("reject")}</button>
      </div>
    </>}
  </div>;
}

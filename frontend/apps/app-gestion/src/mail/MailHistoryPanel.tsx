import { useState } from "react";
import { ErpConfirmDialog } from "../../../../packages/app-common/src/components/ErpConfirmDialog";
import type { MailDelivery, MailProps } from "./MailTypes";
import { formatMailDate, MailFeedback, MailLoad, useMailAction, useMailResource } from "./MailShared";

export function MailHistoryPanel(props: MailProps) {
  const { t, request, session } = props;
  const welcome = useMailResource<MailDelivery[]>("/member-card-deliveries", props);
  const campaigns = useMailResource<MailDelivery[]>("/communications/history", props);
  const action = useMailAction(t);
  const [filter, setFilter] = useState("all");
  const [query, setQuery] = useState("");
  const [resend, setResend] = useState<{ delivery: MailDelivery; requestId: string } | null>(null);
  const rows = [...(welcome.value ?? []).map((row) => ({ ...row, kind: "welcome" })), ...(campaigns.value ?? []).map((row) => ({ ...row, kind: "campaign" }))]
    .filter((row) => (filter === "all" || row.kind === filter) && `${row.email} ${row.campaignName ?? row.subject ?? ""}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt));

  return <div className="mail-column">
    <p>{t("mail.historyHint")}</p>
    <div className="mail-actions">
      <label>{t("mail.deliveryType")}<select aria-label={t("mail.deliveryType")} value={filter} onChange={(event) => setFilter(event.target.value)}><option value="all">{t("mail.all")}</option><option value="welcome">{t("mail.welcome")}</option><option value="campaign">{t("mail.campaigns")}</option></select></label>
      <label>{t("mail.searchHistory")}<input type="search" value={query} onChange={(event) => setQuery(event.target.value)} /></label>
      <button type="button" disabled={action.busy} onClick={() => { welcome.reload(); campaigns.reload(); }}>{t("mail.refresh")}</button>
    </div>
    <MailLoad loading={welcome.loading || campaigns.loading} error={welcome.error || campaigns.error} reload={() => { welcome.reload(); campaigns.reload(); }} t={t}>
      <div className="mail-table-scroll"><table className="mail-table"><thead><tr>
        <th>{t("mail.deliveryType")}</th><th>{t("mail.recipient")}</th><th>{t("mail.subject")}</th><th>{t("mail.status")}</th><th>{t("mail.createdAt")}</th><th>{t("mail.actions")}</th>
      </tr></thead><tbody>
        {rows.map((row) => <tr key={`${row.kind}-${row.id}`}>
          <td>{t(row.kind === "welcome" ? "mail.welcome" : "mail.campaigns")}</td><td>{row.email}</td><td>{row.campaignName ?? row.subject ?? "—"}</td>
          <td><strong>{t(`mail.status.${row.status}`)}</strong>{(row.status === "INCIERTO" || row.status === "UNCERTAIN") && <small>{t("mail.uncertainHint")}</small>}{row.sentAt && <small>{formatMailDate(row.sentAt)}</small>}</td>
          <td>{formatMailDate(row.createdAt)}</td><td>{row.kind === "welcome" && row.status === "ERROR" && <button type="button" disabled={action.busy} onClick={() => void action.run(async () => {
            await request(`/member-card-deliveries/${row.id}/retry`, { token: session.accessToken, method: "PATCH" }); welcome.reload();
          }, "mail.retryQueued")}>{t("mail.retry")}</button>}
            {row.kind === "campaign" && row.status === "FAILED" && <button type="button" disabled={action.busy} onClick={() => void action.run(async () => {
              await request(`/communications/history/${row.id}/retry`, { token: session.accessToken, method: "POST" }); campaigns.reload();
            }, "mail.retryQueued")}>{t("mail.retry")}</button>}
            {row.kind === "welcome" && row.status === "ENVIADO" && <button type="button" disabled={action.busy} onClick={() => { action.clearFeedback(); setResend({ delivery: row, requestId: crypto.randomUUID() }); }}>{t("mail.resend")}</button>}
          </td>
        </tr>)}
        {!rows.length && <tr><td colSpan={6}>{t("mail.emptyHistory")}</td></tr>}
      </tbody></table></div>
    </MailLoad>
    <MailFeedback value={action.feedback} />
    {resend && <ErpConfirmDialog title={t("mail.resend")} message={t("mail.resendConfirm").replace("{email}", resend.delivery.email)}
      confirmLabel={t("mail.resend")} cancelLabel={t("common.cancel")} busy={action.busy} errorMessage={action.feedback?.error ? action.feedback.text : undefined}
      onCancel={() => setResend(null)} onConfirm={() => void action.run(async () => {
        await request(`/member-card-deliveries/${resend.delivery.id}/resend`, { token: session.accessToken, method: "POST", body: { requestId: resend.requestId } });
        setResend(null); welcome.reload();
      }, "mail.retryQueued")} />}
  </div>;
}

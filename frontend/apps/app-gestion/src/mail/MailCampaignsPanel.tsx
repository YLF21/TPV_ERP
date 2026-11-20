import { useEffect, useState } from "react";
import { ErpConfirmDialog } from "../../../../packages/app-common/src/components/ErpConfirmDialog";
import type { AudiencePreview, Campaign, CommunicationsStatus, MailProps, MailTemplate } from "./MailTypes";
import { EmailPreview, formatMailDate, MailFeedback, MailLoad, useMailAction, useMailResource } from "./MailShared";

type Customer = { id: string; fiscalName: string; email: string | null; clientId: string };
const exclusions = ["excludedNoConsent", "excludedNoEmail", "excludedChannel", "excludedSuppressed", "excludedInactive", "excludedDuplicate"] as const;

export function MailCampaignsPanel(props: MailProps) {
  const { t, request, session } = props;
  const campaigns = useMailResource<Campaign[]>("/communications/campaigns", props);
  const templates = useMailResource<MailTemplate[]>("/communications/templates", props);
  const readiness = useMailResource<CommunicationsStatus>("/communications/status", props);
  const customers = useMailResource<Customer[]>("/customers", props);
  const action = useMailAction(t);
  const [name, setName] = useState("");
  const [templateId, setTemplateId] = useState("");
  const [audience, setAudience] = useState("all");
  const [customerIds, setCustomerIds] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Campaign | null>(null);
  const [preview, setPreview] = useState<AudiencePreview | null>(null);
  const [scheduledAt, setScheduledAt] = useState("");
  const [confirm, setConfirm] = useState<"schedule" | "cancel" | null>(null);
  const ready = readiness.value?.campaignsEnabled && readiness.value.publicUnsubscribeConfigured && readiness.value.workerEnabled;
  const dateMillis = new Date(scheduledAt).getTime();
  const scheduleValid = !scheduledAt || (Number.isFinite(dateMillis) && dateMillis > Date.now() && dateMillis <= Date.now() + 366 * 86400000);
  const reviewed = !!preview && /^[a-f0-9]{64}$/i.test(preview.previewToken ?? "");
  const options = { token: session.accessToken };
  useEffect(() => {
    if (!selected || !campaigns.value) return;
    const latest = campaigns.value.find((campaign) => campaign.id === selected.id);
    if (!latest || latest.version !== selected.version || latest.status !== selected.status) {
      setSelected(latest ?? null); setPreview(null); setConfirm(null);
    }
  }, [campaigns.value, selected]);
  const updateCampaign = (saved: Campaign) => {
    campaigns.setValue([saved, ...(campaigns.value ?? []).filter((item) => item.id !== saved.id)]);
    setSelected(saved);
  };
  async function selectCampaign(campaign: Campaign) {
    setSelected(campaign); setPreview(null);
    if (selected?.id !== campaign.id) setScheduledAt("");
    if (campaign.status === "DRAFT") await action.run(async () => {
      setPreview(await request<AudiencePreview>(`/communications/campaigns/${campaign.id}/preview`, { ...options, method: "POST" }));
    });
  }

  return <MailLoad {...campaigns} t={t}>
    <div className="mail-column">
      <p>{t("mail.campaignsHint")}</p>
      {!ready && <div className="mail-feedback is-warning" role="status"><p>{t("mail.campaignsNotReady")}</p>
        {!readiness.value?.publicUnsubscribeConfigured && <p>{t("mail.publicUnsubscribeMissing")}</p>}
        {!readiness.value?.workerEnabled && <p>{t("mail.campaignWorkerMissing")}</p>}
        <button type="button" disabled={action.busy} onClick={readiness.reload}>{t("mail.refresh")}</button>
      </div>}
      <div className="mail-two-columns">
        <section className="mail-card">
          <h3>{t("mail.newCampaign")}</h3>
          <form onSubmit={(event) => { event.preventDefault(); if (audience === "selected" && !customerIds.length) return; void action.run(async () => {
            const saved = await request<Campaign>("/communications/campaigns", { ...options, method: "POST", body: { name, templateId, customerIds: audience === "selected" ? customerIds : [] } });
            updateCampaign(saved); setPreview(null); setName(""); setScheduledAt("");
            setPreview(await request<AudiencePreview>(`/communications/campaigns/${saved.id}/preview`, { ...options, method: "POST" }));
          }, "mail.draftCreated"); }}>
            <fieldset disabled={action.busy}>
              <label>{t("mail.campaignName")}<input required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} /></label>
              <label>{t("mail.templateChoose")}<select aria-label={t("mail.templateChoose")} required value={templateId} onChange={(event) => setTemplateId(event.target.value)}>
                <option value="">{t("mail.selectTemplate")}</option>{templates.value?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select></label>
              {templates.error && <p role="alert">{t("mail.loadError")}</p>}
              <button type="button" onClick={templates.reload}>{t("mail.refreshTemplates")}</button>
              <label>{t("mail.audience")}<select aria-label={t("mail.audience")} value={audience} onChange={(event) => setAudience(event.target.value)}>
                <option value="all">{t("mail.audienceAll")}</option><option value="selected">{t("mail.audienceSelected")}</option>
              </select></label>
              {audience === "selected" && <div className="mail-customer-picker">
                <label>{t("mail.searchCustomers")}<input type="search" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
                <MailLoad {...customers} t={t}>
                  <div className="mail-customer-list">{customers.value?.filter((customer) => `${customer.fiscalName} ${customer.email ?? ""} ${customer.clientId}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())).map((customer) => <label className="mail-check" key={customer.id}>
                    <input type="checkbox" checked={customerIds.includes(customer.id)} onChange={(event) => setCustomerIds(event.target.checked ? [...customerIds, customer.id] : customerIds.filter((id) => id !== customer.id))} />
                    <span>{customer.fiscalName}<small>{customer.email ?? t("mail.noEmail")}</small></span>
                  </label>)}</div>
                  <p>{t("mail.selectedCount").replace("{count}", String(customerIds.length))}</p>
                </MailLoad>
              </div>}
              <div className="mail-actions"><button type="submit" className="primary-button" disabled={!templateId || (audience === "selected" && !customerIds.length)}>{t("mail.createDraft")}</button></div>
            </fieldset>
          </form>
        </section>
        <section className="mail-card">
          <div className="mail-actions"><h3>{t("mail.campaignList")}</h3><button type="button" disabled={action.busy} onClick={campaigns.reload}>{t("mail.refresh")}</button></div>
          {!campaigns.value?.length && <p>{t("mail.emptyCampaigns")}</p>}
          <div className="mail-campaign-list">{campaigns.value?.map((campaign) => <button className={selected?.id === campaign.id ? "is-selected" : ""} key={campaign.id} type="button" aria-label={`${campaign.name} ${t(`mail.status.${campaign.status}`)}`} disabled={action.busy} onClick={() => void selectCampaign(campaign)}>
            <strong>{campaign.name}</strong><span>{t(`mail.status.${campaign.status}`)}</span><small>{formatMailDate(campaign.scheduledAt ?? campaign.createdAt)}</small>
          </button>)}</div>
        </section>
      </div>
      {selected && <section className="mail-card">
        <h3>{selected.name}</h3><p>{t(`mail.status.${selected.status}`)}</p>
        {selected.status === "DRAFT" && <button type="button" disabled={action.busy} onClick={() => void selectCampaign(selected)}>{t("mail.refreshAudience")}</button>}
        {preview && selected.status === "DRAFT" && <>
          <div className="mail-audience-summary"><strong>{t("mail.recipientCount").replace("{count}", String(preview.eligible))}</strong>
            <span>{t("mail.totalConsidered").replace("{count}", String(preview.total))}</span>
            {exclusions.map((key) => <span key={key}>{t(`mail.${key}`)}: {preview[key]}</span>)}
          </div>
          <p className="mail-muted">{t("mail.audienceRecheck")}</p>
          <EmailPreview html={preview.html} t={t} />
          <label>{t("mail.scheduleAt")}<input type="datetime-local" value={scheduledAt} disabled={action.busy} onChange={(event) => setScheduledAt(event.target.value)} /></label>
          <small>{t("mail.scheduleHint")}</small>
          {!scheduleValid && <p role="alert">{t("mail.error.schedule")}</p>}
          {!reviewed && <p role="alert">{t("mail.error.conflict")}</p>}
          <div className="mail-actions"><button type="button" className="primary-button" disabled={action.busy || !ready || !preview.eligible || !scheduleValid || !reviewed} onClick={() => { action.clearFeedback(); setConfirm("schedule"); }}>{t(scheduledAt ? "mail.schedule" : "mail.sendNow")}</button></div>
        </>}
        {(selected.status === "DRAFT" || selected.status === "SCHEDULED") && <button type="button" disabled={action.busy} onClick={() => setConfirm("cancel")}>{t("mail.cancelCampaign")}</button>}
      </section>}
      <MailFeedback value={action.feedback} />
      {confirm && selected && <ErpConfirmDialog title={t(confirm === "schedule" ? "mail.confirmSendTitle" : "mail.cancelCampaign")}
        message={confirm === "schedule" ? t("mail.confirmSend").replace("{count}", String(preview?.eligible ?? 0)).replace("{when}", scheduledAt ? formatMailDate(new Date(scheduledAt).toISOString()) : t("mail.now")) : t("mail.confirmCancel")}
        confirmLabel={t(confirm === "schedule" ? (scheduledAt ? "mail.schedule" : "mail.sendNow") : "mail.cancelCampaign")} cancelLabel={t("common.cancel")}
        busy={action.busy} errorMessage={action.feedback?.error ? action.feedback.text : undefined} onCancel={() => setConfirm(null)}
        onConfirm={() => void action.run(async () => {
          const saved = await request<Campaign>(`/communications/campaigns/${selected.id}/${confirm}`, { ...options, method: "POST", ...(confirm === "schedule" ? { body: { scheduledAt: scheduledAt ? new Date(scheduledAt).toISOString() : null, expectedRecipients: preview?.eligible, previewToken: preview?.previewToken } } : {}) });
          updateCampaign(saved); setPreview(null); setConfirm(null);
        }, confirm === "schedule" ? "mail.campaignQueued" : "mail.campaignCancelled")} />}
    </div>
  </MailLoad>;
}

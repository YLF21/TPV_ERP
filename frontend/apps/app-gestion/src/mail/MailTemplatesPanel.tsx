import { useEffect, useState } from "react";
import type { MailPreview, MailProps, MailTemplate, TemplateDraft } from "./MailTypes";
import { EmailPreview, isMailHttpsUrl, MailFeedback, MailLoad, useMailAction, useMailResource } from "./MailShared";

function templateDraft(value: MailTemplate): TemplateDraft {
  return { name: value.name, subject: value.subject, heading: value.heading, body: value.body,
    buttonText: value.buttonText ?? "", buttonUrl: value.buttonUrl ?? "", logoUrl: value.logoUrl ?? "", accentColor: value.accentColor };
}

export function MailTemplatesPanel(props: MailProps) {
  const { t, request, session } = props;
  const templates = useMailResource<MailTemplate[]>("/communications/templates", props);
  const action = useMailAction(t);
  const initial = (): TemplateDraft => ({ name: "", subject: t("mail.templateDefaultSubject"), heading: t("mail.templateDefaultHeading"), body: t("mail.templateDefaultBody"), buttonText: "", buttonUrl: "", logoUrl: "", accentColor: "#173e67" });
  const [draft, setDraft] = useState<TemplateDraft>(initial);
  const [selected, setSelected] = useState<MailTemplate | null>(null);
  const [preview, setPreview] = useState<MailPreview | null>(null);
  const [changed, setChanged] = useState(false);
  const [testEmail, setTestEmail] = useState("");
  const [ownAddress, setOwnAddress] = useState(false);
  useEffect(() => {
    const latest = templates.value?.find((item) => item.id === selected?.id);
    if (latest && latest !== selected && !changed) { setSelected(latest); setDraft(templateDraft(latest)); setPreview(null); }
  }, [templates.value, selected, changed]);
  const update = (change: Partial<TemplateDraft>) => { setDraft({ ...draft, ...change }); setPreview(null); setChanged(true); action.clearFeedback(); };
  const select = (id: string) => {
    const found = templates.value?.find((item) => item.id === id) ?? null;
    setSelected(found); setDraft(found ? templateDraft(found) : initial()); setPreview(null); setChanged(false); action.clearFeedback();
  };
  const validButton = (!draft.buttonText.trim() && !draft.buttonUrl.trim()) || (Boolean(draft.buttonText.trim()) && isMailHttpsUrl(draft.buttonUrl));
  const validLogo = !draft.logoUrl.trim() || isMailHttpsUrl(draft.logoUrl);
  const validContent = validButton && validLogo && !!draft.name.trim() && !!draft.subject.trim() && !!draft.heading.trim() && !!draft.body.trim();

  return <MailLoad {...templates} t={t}>
    <div className="mail-two-columns">
      <section className="mail-card">
        <h3>{t("mail.templatesTitle")}</h3><p>{t("mail.templatesHint")}</p>
        <label>{t("mail.templateChoose")}<select aria-label={t("mail.templateChoose")} value={selected?.id ?? ""} disabled={action.busy || changed} onChange={(event) => select(event.target.value)}>
          <option value="">{t("mail.newTemplate")}</option>{templates.value?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select></label>
        <button type="button" disabled={action.busy || changed} onClick={templates.reload}>{t("mail.refreshTemplates")}</button>
        {changed && <p className="mail-muted">{t("mail.saveOrDiscard")}</p>}
        <form onSubmit={(event) => { event.preventDefault(); if (!validContent) return; void action.run(async () => {
          const saved = await request<MailTemplate>(`/communications/templates${selected ? `/${selected.id}` : ""}`, {
            token: session.accessToken, method: selected ? "PUT" : "POST", body: { ...draft, ...(selected ? { version: selected.version } : {}) },
          });
          setSelected(saved); setDraft(templateDraft(saved)); setChanged(false);
          templates.setValue([...(templates.value ?? []).filter((item) => item.id !== saved.id), saved]);
        }, "mail.saved"); }}>
          <fieldset disabled={action.busy}>
            <label>{t("mail.templateName")}<input required maxLength={120} value={draft.name} onChange={(event) => update({ name: event.target.value })} /></label>
            <label>{t("mail.subject")}<input required maxLength={250} value={draft.subject} onChange={(event) => update({ subject: event.target.value })} /></label>
            <label>{t("mail.heading")}<input required maxLength={250} value={draft.heading} onChange={(event) => update({ heading: event.target.value })} /></label>
            <label>{t("mail.body")}<textarea required rows={7} maxLength={20000} value={draft.body} onChange={(event) => update({ body: event.target.value })} /></label>
            <p className="mail-muted">{t("mail.templateVariables")}</p>
            <div className="mail-form-grid">
              <label>{t("mail.logoUrl")}<input type="url" maxLength={2048} value={draft.logoUrl} placeholder="https://" onChange={(event) => update({ logoUrl: event.target.value })} /></label>
              <label>{t("mail.buttonText")}<input maxLength={80} value={draft.buttonText} onChange={(event) => update({ buttonText: event.target.value })} /></label>
              <label>{t("mail.buttonUrl")}<input type="url" maxLength={2048} value={draft.buttonUrl} placeholder="https://" onChange={(event) => update({ buttonUrl: event.target.value })} /></label>
              <label>{t("mail.accentColor")}<input type="color" value={draft.accentColor} onChange={(event) => update({ accentColor: event.target.value })} /></label>
            </div>
            {!validButton && <p role="alert">{t("mail.invalidButton")}</p>}
            {!validLogo && <p role="alert">{t("mail.error.link")}</p>}
            <div className="mail-actions">
              <button type="button" disabled={!validContent} onClick={() => void action.run(async () => {
                setPreview(await request<MailPreview>("/communications/templates/preview", { token: session.accessToken, method: "POST", body: draft }));
              })}>{t("mail.showPreview")}</button>
              <button type="submit" className="primary-button" disabled={!validContent || (!changed && !!selected)}>{t("common.save")}</button>
              {changed && <button type="button" onClick={() => select(selected?.id ?? "")}>{t("mail.discard")}</button>}
            </div>
          </fieldset>
        </form>
        {preview && <form onSubmit={(event) => { event.preventDefault(); if (!ownAddress) return; void action.run(async () => {
          await request("/communications/templates/test", { token: session.accessToken, method: "POST", body: { template: draft, toEmail: testEmail.trim() } });
        }, "mail.testAccepted"); }}>
          <fieldset disabled={action.busy}>
            <label>{t("mail.testEmail")}<input type="email" required value={testEmail} onChange={(event) => { setTestEmail(event.target.value); setOwnAddress(false); }} /></label>
            <label className="mail-check"><input type="checkbox" required checked={ownAddress} onChange={(event) => setOwnAddress(event.target.checked)} />{t("mail.ownAddress")}</label>
            <button type="submit" disabled={!ownAddress}>{t("mail.sendTest")}</button>
          </fieldset>
        </form>}
        <MailFeedback value={action.feedback} />
      </section>
      {preview ? <EmailPreview html={preview.html} t={t} /> : <div className="mail-preview-empty"><p>{t("mail.previewHint")}</p></div>}
    </div>
  </MailLoad>;
}

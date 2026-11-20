import { useEffect, useRef, useState } from "react";
import type { MailProps, MailTemplate, MemberSettings, SmtpSettings } from "./MailTypes";
import { MailFeedback, MailLoad, useMailAction, useMailResource } from "./MailShared";

type WelcomeDraft = Pick<MemberSettings, "memberWelcomeEnabled" | "memberCardCodeFormat" | "welcomeSubjectTemplate" | "welcomeBodyTemplate">;
const welcomeFields = (value: MemberSettings): WelcomeDraft => ({ memberWelcomeEnabled: value.memberWelcomeEnabled, memberCardCodeFormat: value.memberCardCodeFormat, welcomeSubjectTemplate: value.welcomeSubjectTemplate ?? "", welcomeBodyTemplate: value.welcomeBodyTemplate ?? "" });

export function MailAutomaticPanel(props: MailProps) {
  const { t, request, session } = props;
  const resource = useMailResource<MemberSettings>("/member-settings", props);
  const account = useMailResource<SmtpSettings>("/member-smtp-settings", props);
  const templates = useMailResource<MailTemplate[]>("/communications/templates", props);
  const welcomeTemplate = useMailResource<{ templateId: string | null }>("/communications/welcome-template", props);
  const action = useMailAction(t);
  const [draft, setDraft] = useState<WelcomeDraft | null>(null);
  const [templateId, setTemplateId] = useState("");
  const edited = useRef(false);
  const baseline = useRef<WelcomeDraft | null>(null);
  const designEdited = useRef(false);
  const patch = (value: WelcomeDraft) => { edited.current = true; setDraft(value); action.clearFeedback(); };
  useEffect(() => { if (resource.value && !edited.current) { baseline.current = welcomeFields(resource.value); setDraft(baseline.current); } }, [resource.value]);
  useEffect(() => { if (welcomeTemplate.value && !designEdited.current) setTemplateId(welcomeTemplate.value.templateId ?? ""); }, [welcomeTemplate.value]);
  const dirty = draft && baseline.current && JSON.stringify(draft) !== JSON.stringify(baseline.current);
  return <MailLoad {...resource} t={t}>
    {draft && <section className="mail-card">
      <h3>{t("mail.automaticTitle")}</h3><p>{t("mail.automaticHint")}</p>
      <p className="mail-muted">{t("mail.automaticSeparate")}</p>
      {account.value && (!account.value.enabled || !account.value.deliveryEnabled) && <p className="mail-feedback is-warning">{t("mail.workerMissing")}</p>}
      <form onSubmit={(event) => { event.preventDefault(); void action.run(async () => {
        // Fetch current loyalty values before merging: this form does not own accrual rules.
        const current = await request<MemberSettings>("/member-settings", { token: session.accessToken });
        const changes = Object.fromEntries(Object.entries(draft).filter(([key, value]) => value !== baseline.current?.[key as keyof WelcomeDraft]));
        const saved = await request<MemberSettings>("/member-settings", { token: session.accessToken, method: "PUT", body: { ...current, ...changes } });
        edited.current = false; baseline.current = welcomeFields(saved); setDraft(baseline.current);
        resource.setValue(saved);
      }, "mail.saved"); }}>
        <fieldset disabled={action.busy}>
          <label className="mail-check"><input type="checkbox" checked={draft.memberWelcomeEnabled} onChange={(event) => patch({ ...draft, memberWelcomeEnabled: event.target.checked })} />{t("mail.welcomeEnabled")}</label>
          <label>{t("mail.cardFormat")}<select aria-label={t("mail.cardFormat")} value={draft.memberCardCodeFormat} onChange={(event) => patch({ ...draft, memberCardCodeFormat: event.target.value as "QR" | "BARCODE" })}>
            <option value="QR">QR</option><option value="BARCODE">{t("mail.barcode")}</option>
          </select></label>
          <label>{t("mail.subject")}<input maxLength={250} value={draft.welcomeSubjectTemplate ?? ""} onChange={(event) => patch({ ...draft, welcomeSubjectTemplate: event.target.value })} /></label>
          <label>{t("mail.body")}<textarea rows={7} maxLength={20000} value={draft.welcomeBodyTemplate ?? ""} onChange={(event) => patch({ ...draft, welcomeBodyTemplate: event.target.value })} /></label>
          <p className="mail-muted">{t("mail.welcomeVariables")}</p>
          <button type="submit" className="primary-button" disabled={!dirty}>{t("common.save")}</button>
        </fieldset>
      </form>
      <MailFeedback value={action.feedback} />
      <hr />
      <h3>{t("mail.welcomeDesign")}</h3>
      <p>{t("mail.welcomeDesignHint")}</p>
      <MailLoad loading={templates.loading || welcomeTemplate.loading} error={templates.error || welcomeTemplate.error} reload={() => { templates.reload(); welcomeTemplate.reload(); }} t={t}>
        <form onSubmit={(event) => { event.preventDefault(); void action.run(async () => {
          const saved = await request<{ templateId: string | null }>("/communications/welcome-template", { token: session.accessToken, method: "PUT", body: { templateId: templateId || null } });
          designEdited.current = false; setTemplateId(saved.templateId ?? ""); welcomeTemplate.setValue(saved);
        }, "mail.saved"); }}>
          <fieldset disabled={action.busy}>
            <label>{t("mail.templateChoose")}<select aria-label={t("mail.templateChoose")} value={templateId} onChange={(event) => { designEdited.current = true; setTemplateId(event.target.value); action.clearFeedback(); }}>
              <option value="">{t("mail.welcomeDefaultDesign")}</option>{templates.value?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select></label>
            <div className="mail-actions"><button type="button" onClick={templates.reload}>{t("mail.refreshTemplates")}</button><button type="submit" disabled={templateId === (welcomeTemplate.value?.templateId ?? "")}>{t("mail.saveDesign")}</button></div>
          </fieldset>
        </form>
      </MailLoad>
    </section>}
  </MailLoad>;
}

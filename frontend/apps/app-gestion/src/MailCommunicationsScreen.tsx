import { useState } from "react";
import { apiRequest, type UserSession } from "@tpverp/app-common";
import { GestionModuleHeading } from "./GestionModuleHeading";
import { MailAccountPanel } from "./mail/MailAccountPanel";
import { MailTemplatesPanel } from "./mail/MailTemplatesPanel";
import { MailAutomaticPanel } from "./mail/MailAutomaticPanel";
import { MailCampaignsPanel } from "./mail/MailCampaignsPanel";
import { MailHistoryPanel } from "./mail/MailHistoryPanel";
import type { MailProps } from "./mail/MailTypes";
import "./MailCommunicationsScreen.css";

const tabs = ["account", "templates", "automatic", "campaigns", "history"] as const;
type Tab = typeof tabs[number];
export function MailCommunicationsScreen({ session, t, request = apiRequest }: { session: UserSession; t: (key: string) => string; request?: typeof apiRequest }) {
  if (!session.permissions.includes("ADMIN")) return <p role="alert">{t("mail.adminRequired")}</p>;
  // A different administrator must never inherit unsaved drafts from the previous session.
  // Token refreshes for the same administrator retain their work.
  return <MailWorkspace key={session.userId ?? session.username} session={session} t={t} request={request} />;
}

function MailWorkspace({ session, t, request }: MailProps) {
  const [tab, setTab] = useState<Tab>("account");
  const [visited, setVisited] = useState<Tab[]>(["account"]);
  const props = { session, t, request };
  const select = (next: Tab) => { setTab(next); setVisited((current) => current.includes(next) ? current : [...current, next]); };
  const panels = { account: MailAccountPanel, templates: MailTemplatesPanel, automatic: MailAutomaticPanel, campaigns: MailCampaignsPanel, history: MailHistoryPanel };
  return <section className="mail-screen gestion-module-screen">
    <GestionModuleHeading group={t("gestion.navigation.group.configuration")} title={t("mail.title")} description={t("mail.subtitle")} />
    <div className="mail-tabs" role="tablist" aria-label={t("mail.title")}>
      {tabs.map((key, index) => <button key={key} id={`mail-tab-${key}`} role="tab" type="button" aria-selected={tab === key} aria-controls={`mail-panel-${key}`} tabIndex={tab === key ? 0 : -1}
        onClick={() => select(key)} onKeyDown={(event) => {
          const next = event.key === "ArrowRight" ? tabs[(index + 1) % tabs.length] : event.key === "ArrowLeft" ? tabs[(index + tabs.length - 1) % tabs.length] : event.key === "Home" ? tabs[0] : event.key === "End" ? tabs[tabs.length - 1] : null;
          if (next) { event.preventDefault(); select(next); document.getElementById(`mail-tab-${next}`)?.focus(); }
        }}>{t(`mail.${key}`)}</button>)}
    </div>
    {visited.map((key) => { const Panel = panels[key]; return <div key={key} id={`mail-panel-${key}`} role="tabpanel" aria-labelledby={`mail-tab-${key}`} hidden={tab !== key}><Panel {...props} /></div>; })}
  </section>;
}

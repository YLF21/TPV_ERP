import { useEffect, useRef, useState } from "react";
import type { LocaleCode, UserSession } from "@tpverp/app-common";
import { CashPolicySettingsCard } from "../../../packages/app-common/src/components/CashPolicySettingsCard";
import { CashOpeningAlertsScreen } from "./CashOpeningAlertsScreen";
import { CashActivityView } from "./CashActivityView";
import { loadCashAlerts } from "./cashOpeningAlertsApi";
import "./CashWorkspace.css";

type Translator = (key: string) => string;
type Props = { session: UserSession; t: Translator; locale?: LocaleCode };

export function canReadCashClosures(session: UserSession) {
  return session.permissions.some((permission) => (
    permission === "ADMIN" || permission === "GESTION_CUENTAS" || permission === "CASH_READ"
  ));
}

export function CashClosuresScreen({ session, t, locale = "es" }: Props) {
  const [tab, setTab] = useState<"activity" | "alerts" | "policy">("activity");
  const [pendingCount, setPendingCount] = useState(0);
  const [refreshSignal, setRefreshSignal] = useState(0);
  const [reviewNotice, setReviewNotice] = useState<string | null>(null);
  const reviewNoticeRef = useRef<HTMLParagraphElement>(null);
  const isAdmin = session.permissions.includes("ADMIN");

  useEffect(() => {
    let active = true;
    void loadCashAlerts({ from: "", to: "", terminalId: "", userId: "", status: "PENDING" }, null, session.accessToken, 1)
      .then(page => { if (active) setPendingCount(page.pendingCount); })
      .catch(() => { /* The alert pane shows its own loading error. */ });
    return () => { active = false; };
  }, [session.accessToken, refreshSignal]);

  return <section className="gestion-workspace gestion-cash-closures-workspace gestion-cash-workspace erp-classic-tables">
    <header className="gestion-dashboard-toolbar gestion-cash-closures-header"><div>
      <span className="gestion-eyebrow">{t("gestion.cashClosures.eyebrow")}</span>
      <h2>{t("gestion.cashClosures.title")}</h2><p>{t("gestion.cashClosures.subtitle")}</p>
    </div><button type="button" onClick={() => setRefreshSignal(value => value + 1)}>{t("common.refresh")}</button></header>
    <div className="gestion-cash-tabs" role="tablist" aria-label={t("gestion.cashClosures.title")}>
      <button type="button" role="tab" aria-selected={tab === "activity"} onClick={() => setTab("activity")}>{t("gestion.cashClosures.tab.closures")}</button>
      <button type="button" role="tab" aria-selected={tab === "alerts"} onClick={() => { if (tab !== "alerts") { setTab("alerts"); setRefreshSignal(value => value + 1); } }}>{t("gestion.cashClosures.tab.alerts").replace("{count}", String(pendingCount))}</button>
      {isAdmin && <button type="button" role="tab" aria-selected={tab === "policy"} onClick={() => setTab("policy")}>{t("gestion.cashClosures.tab.policy")}</button>}
    </div>
    {reviewNotice && <p className="gestion-cash-review-notice" role="status" tabIndex={-1} ref={reviewNoticeRef}>{reviewNotice}</p>}
    <div className="gestion-cash-tab-panel" role="tabpanel">
      {tab === "activity" && <CashActivityView key={session.accessToken} session={session} t={t} locale={locale} refreshSignal={refreshSignal} />}
      {tab === "alerts" && <CashOpeningAlertsScreen session={session} t={t} locale={locale} refreshSignal={refreshSignal} onReviewed={() => {
        setReviewNotice(t("gestion.cashOpeningAlerts.reviewSaved"));
        setRefreshSignal(value => value + 1);
        requestAnimationFrame(() => reviewNoticeRef.current?.focus());
      }} />}
      {tab === "policy" && isAdmin && <div className="gestion-cash-policy"><CashPolicySettingsCard locale={locale} token={session.accessToken} /></div>}
    </div>
  </section>;
}

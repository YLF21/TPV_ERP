import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { PromotionWizard } from "./PromotionWizard";
import type { PromotionView } from "./PromotionWizard";

type PromotionListScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};

type PromotionAction = "duplicate" | "activate" | "deactivate" | "delete";

export function PromotionListScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout
}: PromotionListScreenProps) {
  const t = createTranslator(locale);
  const [promotions, setPromotions] = useState<PromotionView[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const token = session.accessToken;

  const selectedPromotion = useMemo(
    () => promotions.find((promotion) => promotion.id === selectedId) ?? null,
    [promotions, selectedId]
  );

  async function loadPromotions() {
    try {
      setLoading(true);
      const rows = await apiRequest<PromotionView[]>("/promotions", { token });
      setPromotions(rows);
      setStatus(rows.length === 0 ? t("promotion.list.empty") : t("promotion.status.loaded"));
      setSelectedId((current) => current ?? rows[0]?.id ?? null);
    } catch {
      setStatus(t("promotion.status.loadError"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadPromotions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, locale]);

  async function runAction(action: PromotionAction, promotion: PromotionView) {
    try {
      if (action === "delete") {
        await apiRequest<void>(`/promotions/${encodeURIComponent(promotion.id)}`, { token, method: "DELETE" });
      } else {
        await apiRequest<PromotionView>(`/promotions/${encodeURIComponent(promotion.id)}/${action}`, { token, method: "POST" });
      }
      await loadPromotions();
    } catch {
      setStatus(t(`promotion.status.${action}Error`));
    }
  }

  return (
    <main className="promotion-screen work-screen">
      <SessionTopControls
        locale={locale}
        session={session}
        languageLabel={t("login.language")}
        shutdownLabel={t("login.shutdown")}
        changePasswordLabel={t("common.changePassword")}
        logoutLabel={t("common.logout")}
        shutdownConfirmTitle={t("login.shutdownConfirmTitle")}
        shutdownConfirmText={t("login.shutdownConfirmText")}
        noLabel={t("common.no")}
        yesLabel={t("common.yes")}
        onLocaleChange={onLocaleChange}
        onLogout={onLogout}
      />

      <section className="work-shell promotion-shell" aria-label={t("promotion.list.title")}>
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{t("promotion.list.title")}</h1>
        </header>

        <section className="work-panel promotion-list-panel">
          <header className="work-panel-heading stock-panel-heading">
            <div>
              <h2>{t("promotion.list.heading")}</h2>
              <span>{loading ? t("promotion.status.loading") : status}</span>
            </div>
            <button type="button" className="stock-filter-button" onClick={() => void loadPromotions()}>
              {t("promotion.action.refresh")}
            </button>
          </header>

          <div className="promotion-table">
            <div className="promotion-row promotion-row-head">
              <span>{t("promotion.column.name")}</span>
              <span>{t("promotion.column.status")}</span>
              <span>{t("promotion.column.type")}</span>
              <span>{t("promotion.column.date")}</span>
              <span>{t("promotion.column.segment")}</span>
              <span>{t("promotion.column.actions")}</span>
            </div>
            {promotions.map((promotion) => (
              <div className={`promotion-row ${selectedId === promotion.id ? "selected" : ""}`} key={promotion.id}>
                <button type="button" onClick={() => setSelectedId(promotion.id)}>{promotion.name}</button>
                <span>{t(`promotion.status.${promotion.status}`)}</span>
                <span>{t(`promotion.type.${promotion.type}`)}</span>
                <span>{promotionDateRange(promotion)}</span>
                <span>{t(`promotion.segment.${promotion.customerSegment ?? "ALL"}`)}</span>
                <span className="promotion-row-actions">
                  <button type="button" onClick={() => void runAction("duplicate", promotion)}>{t("promotion.action.duplicate")}</button>
                  <button type="button" onClick={() => void runAction("activate", promotion)}>{t("promotion.action.activate")}</button>
                  <button type="button" onClick={() => void runAction("deactivate", promotion)}>{t("promotion.action.deactivate")}</button>
                  <button type="button" onClick={() => void runAction("delete", promotion)}>{t("promotion.action.delete")}</button>
                </span>
              </div>
            ))}
            {!loading && promotions.length === 0 && (
              <p className="promotion-empty">{t("promotion.list.empty")}</p>
            )}
          </div>
        </section>

        <section className="work-panel promotion-detail-panel">
          <PromotionWizard
            locale={locale}
            session={session}
            onCreated={(promotion) => {
              setPromotions((current) => [promotion, ...current]);
              setSelectedId(promotion.id);
              setStatus(t("promotion.status.created"));
            }}
          />
          {selectedPromotion && (
            <aside className="promotion-selected">
              <strong>{t("promotion.list.selected")}</strong>
              <span>{selectedPromotion.name}</span>
              <span>{t(`promotion.status.${selectedPromotion.status}`)}</span>
            </aside>
          )}
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}

function promotionDateRange(promotion: PromotionView) {
  return promotion.endDate ? `${promotion.startDate} - ${promotion.endDate}` : promotion.startDate;
}

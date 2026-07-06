import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import settingsIcon from "../assets/home-configuracion.png";
import reportIcon from "../assets/home-informe.png";
import saleIcon from "../assets/home-venta.png";
import stockIcon from "../assets/home-stock.png";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";

type SessionHomeScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  canOpenSalesReport?: boolean;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  onOpenSales?: () => void;
  onOpenStock?: () => void;
  onOpenSalesReport?: () => void;
  onOpenSettings?: () => void;
};

export function SessionHomeScreen({
  app,
  locale,
  session,
  terminalContext,
  canOpenSalesReport = false,
  onLocaleChange,
  onLogout,
  onOpenSales,
  onOpenStock,
  onOpenSalesReport,
  onOpenSettings
}: SessionHomeScreenProps) {
  const t = createTranslator(locale);

  return (
    <main className="home-screen">
      <header className="entry-topbar">
        <strong className="app-brand-static">{t(app === "venta" ? "venta.title" : "gestion.title")}</strong>
      </header>
      <div className="login-store-heading">
        <strong>{terminalContext.storeName}</strong>
        <span>{t("login.terminalPrefix")}: {terminalContext.terminalCode}</span>
      </div>
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

      <section className="home-actions" aria-label={t("home.title")}>
        <button type="button" className="home-action home-action-sale" onClick={onOpenSales}>
          <img className="home-action-icon" alt="" src={saleIcon} />
          <span>{t("home.sale")}</span>
        </button>
        <div className="home-action-side">
          <button type="button" className="home-action" onClick={onOpenStock}>
            <img className="home-action-icon" alt="" src={stockIcon} />
            <span>{t("home.stock")}</span>
          </button>
          <button
            type="button"
            className="home-action"
            disabled={!canOpenSalesReport}
            onClick={canOpenSalesReport ? onOpenSalesReport : undefined}
          >
            <img className="home-action-icon" alt="" src={reportIcon} />
            <span>{t("home.salesReport")}</span>
          </button>
          <button type="button" className="home-action" onClick={onOpenSettings}>
            <img className="home-action-icon" alt="" src={settingsIcon} />
            <span>{t("home.settings")}</span>
          </button>
        </div>
      </section>

      <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    </main>
  );
}

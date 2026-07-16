import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import settingsIcon from "../assets/home-configuracion.png";
import reportIcon from "../assets/home-informe.png";
import saleIcon from "../assets/home-venta.png";
import stockIcon from "../assets/home-stock.png";
import warehouseIcon from "../assets/home-almacen.png";
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
  onOpenWarehouse?: () => void;
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
  onOpenWarehouse,
  onOpenSalesReport,
  onOpenSettings
}: SessionHomeScreenProps) {
  const t = createTranslator(locale);
  const canOpenSale = Boolean(onOpenSales) && hasPermission(session, "VENTA");
  const canOpenStock = Boolean(onOpenStock) && (
    hasPermission(session, "GESTION_PRODUCTO")
    || hasPermission(session, "GESTION_VENTAS")
    || hasPermission(session, "STOCK_READ")
  );
  const canOpenWarehouse = Boolean(onOpenWarehouse) && hasPermission(session, "GESTION_ALMACEN");
  const canOpenReport = Boolean(onOpenSalesReport) && canOpenSalesReport;
  const canOpenSettings = Boolean(onOpenSettings);

  return (
    <main className="home-screen">
      <header className="entry-topbar">
        <img className="home-brand-icon" alt="" src={saleIcon} />
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
        {canOpenSale && (
          <button type="button" className="home-action home-action-sale" onClick={onOpenSales}>
            <img className="home-action-icon" alt="" src={saleIcon} />
            <span>{t("home.sale")}</span>
          </button>
        )}
        <div className="home-action-side">
          {canOpenStock && (
            <button type="button" className="home-action" onClick={onOpenStock}>
              <img className="home-action-icon" alt="" src={stockIcon} />
              <span>{t("home.product")}</span>
            </button>
          )}
          {canOpenWarehouse && (
            <button type="button" className="home-action" onClick={onOpenWarehouse}>
              <img className="home-action-icon" alt="" src={warehouseIcon} />
              <span>{t("home.warehouse")}</span>
            </button>
          )}
          {canOpenReport && (
            <button
              type="button"
              className="home-action"
              onClick={onOpenSalesReport}
            >
              <img className="home-action-icon" alt="" src={reportIcon} />
              <span>{t("home.salesReport")}</span>
            </button>
          )}
          {canOpenSettings && (
            <button type="button" className="home-action" onClick={onOpenSettings}>
              <img className="home-action-icon" alt="" src={settingsIcon} />
              <span>{t("home.settings")}</span>
            </button>
          )}
        </div>
      </section>

      <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    </main>
  );
}

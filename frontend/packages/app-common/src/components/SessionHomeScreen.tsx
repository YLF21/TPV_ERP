import { useEffect } from "react";
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
  onOpenCustomerReceivables?: () => void;
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
  onOpenCustomerReceivables,
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
  const canOpenReceivables = Boolean(onOpenCustomerReceivables) && hasPermission(session, "CUSTOMER_RECEIVABLES_READ");
  const canOpenSettings = Boolean(onOpenSettings);
  const hasSecondaryActions = canOpenStock || canOpenWarehouse || canOpenReport || canOpenReceivables || canOpenSettings;
  const homeActionsLayoutClass = canOpenSale && !hasSecondaryActions
    ? " home-actions-sale-only"
    : !canOpenSale && hasSecondaryActions
      ? " home-actions-side-only"
      : "";

  useEffect(() => {
    const shortcuts: Record<string, (() => void) | undefined> = app === "venta" ? {
      F1: canOpenSale ? onOpenSales : undefined,
      F2: canOpenStock ? onOpenStock : undefined,
      F3: canOpenReport ? onOpenSalesReport : undefined,
      F4: canOpenSettings ? onOpenSettings : undefined,
      F5: canOpenReceivables ? onOpenCustomerReceivables : undefined,
    } : {};
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat || document.querySelector('[role="dialog"][aria-modal="true"]')) return;
      const action = shortcuts[event.key];
      if (!action) return;
      event.preventDefault();
      action();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [app, canOpenSale, canOpenStock, canOpenReport, canOpenSettings, canOpenReceivables,
    onOpenSales, onOpenStock, onOpenSalesReport, onOpenSettings, onOpenCustomerReceivables]);

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

      <section className={`home-actions${homeActionsLayoutClass}`} aria-label={t("home.title")}>
        {canOpenSale && (
          <button type="button" className="home-action home-action-sale" data-home-action="sale" onClick={onOpenSales}>
            <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={saleIcon} /></span>
            <span className="home-action-label">{t("home.sale")}</span>
            {app === "venta" && <kbd className="home-action-shortcut">F1</kbd>}
          </button>
        )}
        {hasSecondaryActions && (
          <div className="home-action-side">
            {canOpenStock && (
              <button type="button" className="home-action home-action-stock" data-home-action="stock" onClick={onOpenStock}>
                <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={stockIcon} /></span>
                <span className="home-action-label">{t("home.product")}</span>
                {app === "venta" && <kbd className="home-action-shortcut">F2</kbd>}
              </button>
            )}
            {canOpenReport && (
              <button
                type="button"
                className="home-action home-action-report"
                data-home-action="report"
                onClick={onOpenSalesReport}
              >
                <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={reportIcon} /></span>
                <span className="home-action-label">{t("home.salesReport")}</span>
                {app === "venta" && <kbd className="home-action-shortcut">F3</kbd>}
              </button>
            )}
            {canOpenSettings && (
              <button type="button" className="home-action home-action-settings" data-home-action="settings" onClick={onOpenSettings}>
                <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={settingsIcon} /></span>
                <span className="home-action-label">{t("home.settings")}</span>
                {app === "venta" && <kbd className="home-action-shortcut">F4</kbd>}
              </button>
            )}
            {canOpenReceivables && (
              <button type="button" className="home-action home-action-receivables" data-home-action="receivables" onClick={onOpenCustomerReceivables}>
                <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={reportIcon} /></span>
                <span className="home-action-label">DEUDAS CLIENTES</span>
                {app === "venta" && <kbd className="home-action-shortcut">F5</kbd>}
              </button>
            )}
            {canOpenWarehouse && (
              <button type="button" className="home-action home-action-warehouse" data-home-action="warehouse" onClick={onOpenWarehouse}>
                <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={warehouseIcon} /></span>
                <span className="home-action-label">{t("home.warehouse")}</span>
              </button>
            )}
          </div>
        )}
      </section>

      <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    </main>
  );
}

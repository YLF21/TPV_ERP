import { useEffect, useState } from "react";
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
import { SaleCashSessionDialog } from "./SaleCashSessionDialog";
import { loadCashSessionReadiness } from "../sale/cashSessions";

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

type HomeCashSessionState = "LOADING" | "OPEN" | "CLOSED" | "ERROR";

function blocksHomeShortcut(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false;
  if (target.closest("input, select, textarea, dialog, [role='dialog']")) return true;

  let element: HTMLElement | null = target;
  while (element) {
    const contentEditable = element.getAttribute("contenteditable");
    if (element.isContentEditable || (contentEditable !== null && contentEditable !== "false")) {
      return true;
    }
    element = element.parentElement;
  }
  return false;
}

function hasOpenDialog() {
  return Boolean(document.querySelector("dialog[open], [role='dialog'][aria-modal='true']"));
}

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
  const [cashSessionState, setCashSessionState] = useState<HomeCashSessionState>("LOADING");
  const [cashDialogOpen, setCashDialogOpen] = useState(false);
  const [cashRefreshKey, setCashRefreshKey] = useState(0);
  const saleUnlocked = canOpenSale && cashSessionState === "OPEN";

  useEffect(() => {
    let active = true;
    if (!canOpenSale) {
      setCashSessionState("OPEN");
      return () => { active = false; };
    }
    if (!terminalContext.terminalId || !session.accessToken) {
      setCashSessionState("ERROR");
      return () => { active = false; };
    }

    setCashSessionState("LOADING");
    void loadCashSessionReadiness(terminalContext.terminalId, session.accessToken)
      .then((readiness) => {
        if (active) setCashSessionState(readiness.open ? "OPEN" : "CLOSED");
      })
      .catch(() => {
        if (active) setCashSessionState("ERROR");
      });
    return () => { active = false; };
  }, [canOpenSale, cashRefreshKey, session.accessToken, terminalContext.terminalId]);

  useEffect(() => {
    const shortcuts: Record<string, (() => void) | undefined> = {
      F1: saleUnlocked ? onOpenSales : undefined,
      F2: canOpenStock ? onOpenStock : undefined,
      F3: canOpenWarehouse ? onOpenWarehouse : undefined,
      F4: canOpenReport ? onOpenSalesReport : undefined,
      F5: canOpenSettings ? onOpenSettings : undefined
    };

    function handleKeyDown(event: KeyboardEvent) {
      if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || event.repeat) return;
      if (blocksHomeShortcut(event.target) || hasOpenDialog()) return;

      const action = shortcuts[event.key.toUpperCase()];
      if (!action) return;

      event.preventDefault();
      action();
    }

    // Cash-session readiness is intentionally left for its dedicated Home integration.
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [
    canOpenReport,
    canOpenSettings,
    canOpenStock,
    canOpenWarehouse,
    onOpenSales,
    onOpenSalesReport,
    onOpenSettings,
    onOpenStock,
    onOpenWarehouse,
    saleUnlocked
  ]);

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
          <div className="home-sale-launcher">
            <button
              type="button"
              className="home-action home-action-sale"
              onClick={onOpenSales}
              disabled={!saleUnlocked}
              aria-describedby={saleUnlocked ? undefined : "home-sale-cash-state"}
            >
              <img className="home-action-icon" alt="" src={saleIcon} />
              <span>{t("home.sale")}</span>
              <kbd className="home-action-shortcut" aria-hidden="true">F1</kbd>
            </button>
            {cashSessionState !== "OPEN" && (
              <p
                id="home-sale-cash-state"
                className={`home-sale-cash-state ${cashSessionState === "ERROR" ? "error" : ""}`}
                role={cashSessionState === "ERROR" ? "alert" : "status"}
              >
                {t(cashSessionState === "LOADING"
                  ? "home.cash.loading"
                  : cashSessionState === "ERROR"
                    ? "home.cash.error"
                    : "home.cash.closed")}
              </p>
            )}
            {cashSessionState === "CLOSED" && terminalContext.terminalId && session.accessToken && (
              <button
                type="button"
                className="home-open-cash-button"
                onClick={() => setCashDialogOpen(true)}
              >
                {t("home.cash.openAction")}
              </button>
            )}
            {cashSessionState === "ERROR" && (
              <button
                type="button"
                className="home-cash-retry-button"
                onClick={() => setCashRefreshKey((current) => current + 1)}
              >
                {t("home.cash.retry")}
              </button>
            )}
          </div>
        )}
        <div className="home-action-side">
          {canOpenStock && (
            <button type="button" className="home-action" onClick={onOpenStock}>
              <img className="home-action-icon" alt="" src={stockIcon} />
              <span>{t("home.product")}</span>
              <kbd className="home-action-shortcut" aria-hidden="true">F2</kbd>
            </button>
          )}
          {canOpenWarehouse && (
            <button type="button" className="home-action" onClick={onOpenWarehouse}>
              <img className="home-action-icon" alt="" src={warehouseIcon} />
              <span>{t("home.warehouse")}</span>
              <kbd className="home-action-shortcut" aria-hidden="true">F3</kbd>
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
              <kbd className="home-action-shortcut" aria-hidden="true">F4</kbd>
            </button>
          )}
          {canOpenSettings && (
            <button type="button" className="home-action" onClick={onOpenSettings}>
              <img className="home-action-icon" alt="" src={settingsIcon} />
              <span>{t("home.settings")}</span>
              <kbd className="home-action-shortcut" aria-hidden="true">F5</kbd>
            </button>
          )}
        </div>
      </section>

      {cashDialogOpen && terminalContext.terminalId && session.accessToken && (
        <SaleCashSessionDialog
          locale={locale}
          currentUsername={session.username}
          mode="OPEN"
          terminalId={terminalContext.terminalId}
          token={session.accessToken}
          openContext="HOME"
          onExitSales={() => setCashDialogOpen(false)}
          onOpened={() => {
            setCashDialogOpen(false);
            setCashSessionState("OPEN");
          }}
        />
      )}

      <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    </main>
  );
}

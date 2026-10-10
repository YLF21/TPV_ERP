import { AppBrand } from "./AppBrand";
import { DesktopHeaderContext } from "./DesktopHeaderContext";
import { useEffect, type ReactNode } from "react";
import {
  Desktop,
  CashRegister,
  Eye,
  Printer,
  UserCircle,
  Wrench
} from "@phosphor-icons/react";
import type { Icon } from "@phosphor-icons/react";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { ModuleNavBackButton } from "./ModuleNavBackButton";
import { ModuleNavItem } from "./ModuleNavItem";
import { normalizeSaleSettingsDestination, type SaleSettingsDestination } from "./saleSettingsNavigation";
import "./SaleSettingsShell.css";

export {
  normalizeSaleSettingsDestination,
  requestSaleSettingsBack,
  type CanonicalSaleSettingsDestination,
  type SaleSettingsDestination
} from "./saleSettingsNavigation";

export type SaleSettingsShellProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  active: SaleSettingsDestination;
  onNavigate: (destination: SaleSettingsDestination) => void;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  heading: string;
  subtitle: string;
  scopeLabel?: string;
  headerNavigation?: ReactNode;
  navigationDisabled?: boolean;
  children: ReactNode;
};

type SaleSettingsNavigationItem = {
  destination: SaleSettingsDestination;
  labelKey: string;
  icon: Icon;
};

const personalDestinations: SaleSettingsNavigationItem[] = [
  { destination: "account", labelKey: "settings.accountSecurity", icon: UserCircle },
  { destination: "visualization", labelKey: "settings.visualization", icon: Eye }
];

const workstationDestinations: SaleSettingsNavigationItem[] = [
  { destination: "printers", labelKey: "settings.printers", icon: Printer },
  { destination: "devices", labelKey: "settings.devices", icon: Desktop },
  { destination: "cash", labelKey: "settings.cash", icon: CashRegister }
];

export function SaleSettingsShell({
  app,
  locale,
  session,
  terminalContext,
  active,
  onNavigate,
  onBack,
  onLocaleChange,
  onLogout,
  heading,
  subtitle,
  scopeLabel,
  headerNavigation,
  navigationDisabled = false,
  children
}: SaleSettingsShellProps) {
  const t = createTranslator(locale);
  const canConfigureTerminal = app === "venta" && hasPermission(session, "CONFIGURACION_TERMINAL");

  useEffect(() => {
    const handleBackRequest = (event: Event) => {
      event.preventDefault();
      if (!navigationDisabled) onBack();
    };
    window.addEventListener("tpv-sale-settings-back", handleBackRequest);
    return () => window.removeEventListener("tpv-sale-settings-back", handleBackRequest);
  }, [onBack, navigationDisabled]);

  function navigationButton({
    destination,
    labelKey,
    icon: Icon
  }: SaleSettingsNavigationItem) {
    const selected = normalizeSaleSettingsDestination(active) === destination;
    return (
      <ModuleNavItem
        className="sale-settings-nav-item"
        disabled={navigationDisabled}
        icon={<Icon size={22} weight={selected ? "fill" : "regular"} />}
        label={t(labelKey)}
        selected={selected}
        key={destination}
        onClick={() => onNavigate(destination)}
      />
    );
  }

  return (
    <main className="settings-screen sale-settings-screen">
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
        exitBlocked={navigationDisabled}
        onLocaleChange={(nextLocale) => { if (!navigationDisabled) onLocaleChange(nextLocale); }}
        onChangePassword={() => { if (!navigationDisabled) onNavigate("security"); }}
        onLogout={onLogout ? () => { if (!navigationDisabled) onLogout(); } : undefined}
      />

      <section className="settings-shell sale-settings-shell" aria-label={t("settings.title")}>
        <header className="settings-topbar sale-settings-topbar">
          <button type="button" className="report-brand-back" disabled={navigationDisabled} onClick={onBack}>
            <AppBrand app={app} label={t(app === "venta" ? "venta.title" : "gestion.title")} />
          </button>
          <DesktopHeaderContext terminalContext={terminalContext} />
          <h1 className="report-title">{t("settings.title")}</h1>
        </header>

        <aside className="settings-nav sale-settings-nav" aria-label={t("settings.sections")}>
          <div className="sale-settings-nav-group">
            <strong className="sale-settings-nav-heading">{t("settings.group.personal")}</strong>
            {personalDestinations.map(navigationButton)}
          </div>

          {canConfigureTerminal ? (
            <>
              <div className="sale-settings-nav-group">
                <strong className="sale-settings-nav-heading">{t("settings.group.workstation")}</strong>
                {workstationDestinations.map(navigationButton)}
              </div>
              <div className="sale-settings-nav-group">
                <strong className="sale-settings-nav-heading">{t("settings.group.support")}</strong>
                {navigationButton({ destination: "diagnostics", labelKey: "settings.diagnosticsMaintenance", icon: Wrench })}
              </div>
              <div className="sale-settings-nav-group">
                <strong className="sale-settings-nav-heading">{t("settings.group.connection")}</strong>
                {navigationButton({ destination: "connection", labelKey: "terminalLink.title", icon: Desktop })}
              </div>
            </>
          ) : null}

          <ModuleNavBackButton
            className="sale-settings-nav-item"
            label={t("common.back")}
            onBack={onBack}
            disabled={navigationDisabled}
          />
        </aside>

        <section className="settings-workspace sale-settings-workspace">
          <header className={"settings-heading sale-settings-heading" + (headerNavigation ? " sale-settings-heading--with-navigation" : "")}>
            <h2>{heading}</h2>
            <span>{subtitle}</span>
            {scopeLabel ? <strong className="sale-settings-scope">{scopeLabel}</strong> : null}
            {headerNavigation}
          </header>
          <div className="sale-settings-content">{children}</div>
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}

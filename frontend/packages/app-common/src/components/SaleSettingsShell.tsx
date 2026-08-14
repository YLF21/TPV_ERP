import type { ReactNode } from "react";
import {
  Desktop,
  FileText,
  LockKey,
  Printer,
  ShoppingCartSimple,
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
import "./SaleSettingsShell.css";

export type SaleSettingsDestination =
  | "account"
  | "language"
  | "security"
  | "reports"
  | "sale"
  | "devices"
  | "printing"
  | "diagnostics";

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
  children: ReactNode;
};

type SaleSettingsNavigationItem = {
  destination: SaleSettingsDestination;
  labelKey: string;
  icon: Icon;
};

const personalDestinations: SaleSettingsNavigationItem[] = [
  { destination: "account", labelKey: "settings.account", icon: UserCircle },
  { destination: "security", labelKey: "settings.security", icon: LockKey },
  { destination: "reports", labelKey: "settings.reports", icon: FileText }
];

const workstationDestinations: SaleSettingsNavigationItem[] = [
  { destination: "sale", labelKey: "settings.sale", icon: ShoppingCartSimple },
  { destination: "devices", labelKey: "settings.devices", icon: Desktop },
  { destination: "printing", labelKey: "settings.printing", icon: Printer }
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
  children
}: SaleSettingsShellProps) {
  const t = createTranslator(locale);
  const canConfigureTerminal = app === "venta" && hasPermission(session, "CONFIGURACION_TERMINAL");

  function navigationButton({
    destination,
    labelKey,
    icon: Icon
  }: SaleSettingsNavigationItem) {
    const selected = active === destination;
    return (
      <ModuleNavItem
        className="sale-settings-nav-item"
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
        onLocaleChange={onLocaleChange}
        onChangePassword={() => onNavigate("security")}
        onLogout={onLogout}
      />

      <section className="settings-shell sale-settings-shell" aria-label={t("settings.title")}>
        <header className="settings-topbar sale-settings-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{t("settings.title")}</h1>
          <span className="sale-settings-terminal-context">
            {terminalContext.storeName} · {t("login.terminalPrefix")} {terminalContext.terminalCode}
          </span>
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
                {navigationButton({ destination: "diagnostics", labelKey: "settings.diagnostics", icon: Wrench })}
              </div>
            </>
          ) : null}

          <ModuleNavBackButton
            className="sale-settings-nav-item"
            label={t("common.back")}
            onBack={onBack}
          />
        </aside>

        <section className="settings-workspace sale-settings-workspace">
          <header className="settings-heading sale-settings-heading">
            <h2>{heading}</h2>
            <span>{subtitle}</span>
            {scopeLabel ? <strong className="sale-settings-scope">{scopeLabel}</strong> : null}
          </header>
          <div className="sale-settings-content">{children}</div>
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}

import { useState } from "react";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import {
  readCashInputMode,
  persistCashInputModeSelection,
  type CashInputMode
} from "../sale/cashInputMode";
import { PaymentTerminalSettings } from "./PaymentTerminalSettings";

type SettingsSection = "terminal" | "user" | "reports" | "system";

type SettingsScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  onOpenHardware?: () => void;
};

const settingsSections: SettingsSection[] = ["terminal", "user", "reports", "system"];

export function SettingsScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout,
  onOpenHardware
}: SettingsScreenProps) {
  const t = createTranslator(locale);
  const [selectedSection, setSelectedSection] = useState<SettingsSection>("terminal");
  const [cashInputMode, setCashInputMode] = useState<CashInputMode>(() => readCashInputMode());

  const handleCashInputModeChange = (value: string) => {
    const mode = persistCashInputModeSelection(value);
    if (!mode) {
      return;
    }

    setCashInputMode(mode);
  };

  return (
    <main className="settings-screen">
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

      <section className="settings-shell" aria-label={t("settings.title")}>
        <header className="settings-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{t("settings.title")}</h1>
        </header>

        <aside className="settings-nav">
          <strong>{t("settings.sections")}</strong>
          {settingsSections.map((section) => (
            <button
              type="button"
              className={selectedSection === section ? "selected" : ""}
              key={section}
              onClick={() => setSelectedSection(section)}
            >
              {t(`settings.${section}`)}
            </button>
          ))}
          <button type="button" className="report-back" onClick={onBack}>
            {t("common.back")}
          </button>
        </aside>

        <section className="settings-workspace">
          <header className="settings-heading">
            <h2>{t(`settings.${selectedSection}`)}</h2>
            <span>{t(`settings.${selectedSection}.subtitle`)}</span>
          </header>
          {selectedSection === "terminal" && (
            <div className="settings-grid">
              <article className="settings-card">
                <h3>{t("settings.hardware")}</h3>
                <p>{t("settings.hardware.description")}</p>
                <button type="button" onClick={onOpenHardware}>
                  {t("settings.openHardware")}
                </button>
              </article>
              <article className="settings-card">
                <h3>{t("settings.terminalContext")}</h3>
                <dl>
                  <div>
                    <dt>{t("settings.store")}</dt>
                    <dd>{terminalContext.storeName}</dd>
                  </div>
                  <div>
                    <dt>{t("login.terminalPrefix")}</dt>
                    <dd>{terminalContext.terminalCode}</dd>
                  </div>
                </dl>
              </article>
              <article className="settings-card settings-cash-input-card">
                <h3>{t("settings.cashInput")}</h3>
                <p>{t("settings.cashInput.description")}</p>
                <label htmlFor="cash-input-mode">{t("settings.cashInput")}</label>
                <select
                  id="cash-input-mode"
                  value={cashInputMode}
                  onChange={(event) => handleCashInputModeChange(event.currentTarget.value)}
                >
                  <option value="touch">{t("settings.cashInput.touch")}</option>
                  <option value="keyboard">{t("settings.cashInput.keyboard")}</option>
                </select>
              </article>
              <PaymentTerminalSettings locale={locale} token={session.accessToken} />
            </div>
          )}
          {selectedSection !== "terminal" && (
            <div className="settings-grid">
              <article className="settings-card settings-card-wide">
                <h3>{t(`settings.${selectedSection}`)}</h3>
                <p>{t(`settings.${selectedSection}.placeholder`)}</p>
              </article>
            </div>
          )}
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}

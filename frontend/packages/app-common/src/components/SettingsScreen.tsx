import { useEffect, useState, type FormEvent } from "react";
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
import { readSaleInterfaceTouchMode, saveSaleInterfaceTouchMode } from "./saleInterfacePreferences";
import { SystemCompatibilityCard } from "./SystemCompatibilityCard";
import { apiRequest, ApiError } from "../api/client";
import {
  readSalesReportOutputPreferences,
  saveSalesReportOutputPreferences,
  type SalesReportDensity,
  type SalesReportPrimaryAction
} from "./salesReportOutputPreferences";

type SettingsSection = "terminal" | "saleInterface" | "user" | "reports" | "system";

type SettingsScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  onOpenHardware?: () => void;
  onOpenReports?: () => void;
  request?: typeof apiRequest;
};

const baseSettingsSections: SettingsSection[] = ["terminal", "user", "reports", "system"];

export function SettingsScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout,
  onOpenHardware,
  onOpenReports,
  request = apiRequest
}: SettingsScreenProps) {
  const t = createTranslator(locale);
  const settingsSections: SettingsSection[] =
    app === "venta" ? ["terminal", "saleInterface", "user", "reports", "system"] : baseSettingsSections;
  const [selectedSection, setSelectedSection] = useState<SettingsSection>("terminal");
  const [cashInputMode, setCashInputMode] = useState<CashInputMode>(() => readCashInputMode());
  const [touchModeEnabled, setTouchModeEnabled] = useState(() =>
    readSaleInterfaceTouchMode(app, terminalContext)
  );
  const [reportPreferences, setReportPreferences] = useState(() =>
    readSalesReportOutputPreferences(app, session.username, terminalContext)
  );
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordMessage, setPasswordMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  const handleCashInputModeChange = (value: string) => {
    const mode = persistCashInputModeSelection(value);
    if (!mode) {
      return;
    }

    setCashInputMode(mode);
  };

  useEffect(() => {
    setTouchModeEnabled(readSaleInterfaceTouchMode(app, terminalContext));
  }, [app, terminalContext.terminalCode, terminalContext.terminalId]);

  useEffect(() => {
    setReportPreferences(readSalesReportOutputPreferences(app, session.username, terminalContext));
  }, [app, session.username, terminalContext.terminalCode, terminalContext.terminalId]);

  function updateTouchMode(enabled: boolean) {
    setTouchModeEnabled(enabled);
    saveSaleInterfaceTouchMode(app, terminalContext, enabled);
  }

  function settingsSectionLabel(section: SettingsSection) {
    return t(`settings.${section}`);
  }

  function updateReportDensity(density: SalesReportDensity) {
    const next = { ...reportPreferences, density };
    setReportPreferences(next);
    saveSalesReportOutputPreferences(app, session.username, terminalContext, next);
  }

  function updateReportPrimaryAction(primaryAction: SalesReportPrimaryAction) {
    const next = { ...reportPreferences, primaryAction };
    setReportPreferences(next);
    saveSalesReportOutputPreferences(app, session.username, terminalContext, next);
  }

  async function handlePasswordChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPasswordMessage(null);
    if (!/^\d{4,12}$/.test(newPassword)) {
      setPasswordMessage({ kind: "error", text: t("settings.user.passwordFormat") });
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordMessage({ kind: "error", text: t("settings.user.passwordMismatch") });
      return;
    }
    if (!session.accessToken) {
      setPasswordMessage({ kind: "error", text: t("settings.user.passwordUnavailable") });
      return;
    }
    setPasswordSaving(true);
    try {
      await request<void>("/auth/password", {
        token: session.accessToken,
        method: "PUT",
        body: { currentPassword, newPassword }
      });
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setPasswordMessage({ kind: "success", text: t("settings.user.passwordSuccess") });
    } catch (failure) {
      setPasswordMessage({
        kind: "error",
        text: failure instanceof ApiError && (failure.status === 401 || failure.status === 403)
          ? t("settings.user.passwordInvalid")
          : t("settings.user.passwordError")
      });
    } finally {
      setPasswordSaving(false);
    }
  }

  function settingsSectionSubtitle(section: SettingsSection) {
    return t(`settings.${section}.subtitle`);
  }

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
        onChangePassword={() => setSelectedSection("user")}
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
              {settingsSectionLabel(section)}
            </button>
          ))}
          <button type="button" className="report-back" onClick={onBack}>
            {t("common.back")}
          </button>
        </aside>

        <section className="settings-workspace">
          <header className="settings-heading">
            <h2>{settingsSectionLabel(selectedSection)}</h2>
            <span>{settingsSectionSubtitle(selectedSection)}</span>
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
          {selectedSection === "saleInterface" && (
            <div className="settings-grid">
              <article className="settings-card settings-card-wide">
                <h3>{t("settings.saleInterface")}</h3>
                <p>{t("settings.saleInterface.description")}</p>
                <label className="settings-toggle-row">
                  <input
                    type="checkbox"
                    checked={touchModeEnabled}
                    onChange={(event) => updateTouchMode(event.currentTarget.checked)}
                  />
                  {t("settings.saleInterface.touchMode")}
                </label>
              </article>
            </div>
          )}
          {selectedSection === "user" && (
            <div className="settings-grid settings-user-grid">
              <article className="settings-card settings-user-profile">
                <h3>{t("settings.user.profile")}</h3>
                <dl>
                  <div><dt>{t("settings.user.name")}</dt><dd>{session.displayName}</dd></div>
                  <div><dt>{t("settings.user.username")}</dt><dd>{session.username}</dd></div>
                  <div><dt>{t("settings.user.role")}</dt><dd>{session.role ?? "-"}</dd></div>
                  <div><dt>{t("settings.user.maxDiscount")}</dt><dd>{session.maxDiscountPercent == null ? "-" : `${session.maxDiscountPercent}%`}</dd></div>
                </dl>
                <fieldset className="settings-language-options">
                  <legend>{t("settings.user.language")}</legend>
                  <div>
                    {([ ["es", "Español"], ["en", "English"], ["zh", "中文"] ] as const).map(([code, label]) => (
                      <button type="button" className={locale === code ? "selected" : ""} key={code} onClick={() => onLocaleChange(code)}>
                        {label}
                      </button>
                    ))}
                  </div>
                </fieldset>
              </article>
              <article className="settings-card settings-user-security">
                <h3>{t("settings.user.security")}</h3>
                <p>{t("settings.user.passwordHelp")}</p>
                <form onSubmit={(event) => void handlePasswordChange(event)}>
                  <label>{t("settings.user.currentPassword")}
                    <input type="password" inputMode="numeric" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.currentTarget.value)} />
                  </label>
                  <label>{t("settings.user.newPassword")}
                    <input type="password" inputMode="numeric" pattern="[0-9]*" minLength={4} maxLength={12} autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.currentTarget.value)} />
                  </label>
                  <label>{t("settings.user.confirmPassword")}
                    <input type="password" inputMode="numeric" pattern="[0-9]*" minLength={4} maxLength={12} autoComplete="new-password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.currentTarget.value)} />
                  </label>
                  {passwordMessage && <p className={`settings-user-message ${passwordMessage.kind}`} role={passwordMessage.kind === "error" ? "alert" : "status"}>{passwordMessage.text}</p>}
                  <button type="submit" disabled={passwordSaving || !currentPassword || !newPassword || !confirmPassword}>
                    {passwordSaving ? t("settings.user.passwordSaving") : t("settings.user.passwordAction")}
                  </button>
                </form>
              </article>
            </div>
          )}
          {selectedSection === "reports" && (
            <div className="settings-grid settings-reports-grid">
              <article className="settings-card settings-report-preferences">
                <h3>{t("settings.reports.visualization")}</h3>
                <p>{t("settings.reports.visualizationHelp")}</p>
                <label htmlFor="report-density">{t("settings.reports.density")}</label>
                <select
                  id="report-density"
                  value={reportPreferences.density}
                  onChange={(event) => updateReportDensity(event.currentTarget.value as SalesReportDensity)}
                >
                  <option value="comfortable">{t("settings.reports.densityComfortable")}</option>
                  <option value="compact">{t("settings.reports.densityCompact")}</option>
                </select>
                <div className={`settings-report-density-preview ${reportPreferences.density}`} aria-hidden="true">
                  <span /><span /><span />
                </div>
                <p className="settings-report-note">{t("settings.reports.columnsHelp")}</p>
                <button type="button" onClick={onOpenReports} disabled={!onOpenReports}>
                  {t("settings.reports.openReports")}
                </button>
              </article>
              <article className="settings-card settings-report-preferences">
                <h3>{t("settings.reports.output")}</h3>
                <p>{t("settings.reports.outputHelp")}</p>
                <label htmlFor="report-primary-action">{t("settings.reports.primaryAction")}</label>
                <select
                  id="report-primary-action"
                  value={reportPreferences.primaryAction}
                  onChange={(event) => updateReportPrimaryAction(event.currentTarget.value as SalesReportPrimaryAction)}
                >
                  <option value="menu">{t("settings.reports.actionMenu")}</option>
                  <option value="print">{t("settings.reports.actionPrint")}</option>
                  <option value="pdf">{t("settings.reports.actionPdf")}</option>
                  <option value="excel">{t("settings.reports.actionExcel")}</option>
                </select>
                <p className="settings-report-saved" role="status">
                  {t("settings.reports.savedLocally")}
                </p>
              </article>
            </div>
          )}
          {selectedSection === "system" && (
            <div className="settings-grid">
              <SystemCompatibilityCard locale={locale} token={session.accessToken} />
            </div>
          )}
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}

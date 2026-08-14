import { useEffect, useState, type FormEvent } from "react";
import { ArrowSquareOut, Key } from "@phosphor-icons/react";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  readCashInputMode,
  persistCashInputModeSelection,
  type CashInputMode
} from "../sale/cashInputMode";
import { PaymentTerminalSettings } from "./PaymentTerminalSettings";
import {
  defaultSaleInterfaceMode,
  loadSaleInterfaceConfiguration,
  saveSaleInterfaceConfiguration,
  type SaleInterfaceMode
} from "./saleInterfacePreferences";
import { SystemCompatibilityCard } from "./SystemCompatibilityCard";
import { CashOperationsCard } from "./CashOperationsCard";
import { OperationalStatusCard } from "./OperationalStatusCard";
import { apiRequest, ApiError } from "../api/client";
import { hasPermission } from "../auth/auth";
import {
  readSalesReportOutputPreferences,
  saveSalesReportOutputPreferences,
  type SalesReportDensity,
  type SalesReportPrimaryAction
} from "./salesReportOutputPreferences";
import { SaleSettingsShell, type SaleSettingsDestination } from "./SaleSettingsShell";

type SettingsScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  initialDestination?: SaleSettingsDestination;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  onOpenHardware?: () => void;
  onOpenDocumentPrinting?: () => void;
  onOpenDiagnostics?: () => void;
  onOpenReports?: () => void;
  onSaleInterfaceModeChange?: (mode: SaleInterfaceMode) => void;
  request?: typeof apiRequest;
};

const protectedDestinations = new Set<SaleSettingsDestination>([
  "sale",
  "devices",
  "printing",
  "diagnostics"
]);

function normalizeDestination(destination: SaleSettingsDestination) {
  return destination === "language" ? "account" : destination;
}

function languageLabel(code: LocaleCode) {
  if (code === "es") return "Español";
  if (code === "zh") return "中文";
  return "English";
}

export function SettingsScreen({
  app,
  locale,
  session,
  terminalContext,
  initialDestination,
  onBack,
  onLocaleChange,
  onLogout,
  onOpenHardware,
  onOpenDocumentPrinting,
  onOpenDiagnostics,
  onOpenReports,
  onSaleInterfaceModeChange,
  request = apiRequest
}: SettingsScreenProps) {
  const t = createTranslator(locale);
  const canConfigureTerminal = app === "venta" && hasPermission(session, "CONFIGURACION_TERMINAL");
  const [selectedSection, setSelectedSection] = useState<SaleSettingsDestination>(() => {
    if (initialDestination && (canConfigureTerminal || !protectedDestinations.has(initialDestination))) {
      return normalizeDestination(initialDestination);
    }
    return canConfigureTerminal ? "sale" : "account";
  });
  const [cashInputMode, setCashInputMode] = useState<CashInputMode>(() => readCashInputMode());
  const [saleInterfaceMode, setSaleInterfaceMode] = useState<SaleInterfaceMode>(defaultSaleInterfaceMode);
  const [savedSaleInterfaceMode, setSavedSaleInterfaceMode] =
    useState<SaleInterfaceMode>(defaultSaleInterfaceMode);
  const [saleInterfaceLoading, setSaleInterfaceLoading] = useState(canConfigureTerminal);
  const [saleInterfaceSaving, setSaleInterfaceSaving] = useState(false);
  const [saleInterfaceMessage, setSaleInterfaceMessage] =
    useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [reportPreferences, setReportPreferences] = useState(() =>
    readSalesReportOutputPreferences(app, session.username, terminalContext)
  );
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordMessage, setPasswordMessage] =
    useState<{ kind: "success" | "error"; text: string } | null>(null);

  useEffect(() => {
    if (!canConfigureTerminal && protectedDestinations.has(selectedSection)) {
      setSelectedSection("account");
    }
  }, [canConfigureTerminal, selectedSection]);

  useEffect(() => {
    if (initialDestination && (canConfigureTerminal || !protectedDestinations.has(initialDestination))) {
      setSelectedSection(normalizeDestination(initialDestination));
    }
  }, [canConfigureTerminal, initialDestination]);

  useEffect(() => {
    let active = true;
    if (app !== "venta" || !canConfigureTerminal || !session.accessToken) {
      setSaleInterfaceMode(defaultSaleInterfaceMode);
      setSavedSaleInterfaceMode(defaultSaleInterfaceMode);
      setSaleInterfaceLoading(false);
      return () => { active = false; };
    }
    setSaleInterfaceLoading(true);
    setSaleInterfaceMessage(null);
    void loadSaleInterfaceConfiguration(session.accessToken, request)
      .then((configuration) => {
        if (!active) return;
        setSaleInterfaceMode(configuration.saleMode);
        setSavedSaleInterfaceMode(configuration.saleMode);
      })
      .catch(() => {
        if (!active) return;
        setSaleInterfaceMode(defaultSaleInterfaceMode);
        setSavedSaleInterfaceMode(defaultSaleInterfaceMode);
        setSaleInterfaceMessage({ kind: "error", text: t("settings.saleInterface.loadError") });
      })
      .finally(() => {
        if (active) setSaleInterfaceLoading(false);
      });
    return () => { active = false; };
  }, [app, canConfigureTerminal, request, session.accessToken, terminalContext.terminalId]);

  useEffect(() => {
    setReportPreferences(readSalesReportOutputPreferences(app, session.username, terminalContext));
  }, [app, session.username, terminalContext.terminalCode, terminalContext.terminalId]);

  function handleNavigation(destination: SaleSettingsDestination) {
    if (protectedDestinations.has(destination) && !canConfigureTerminal) return;
    const normalizedDestination = normalizeDestination(destination);
    if (destination === "devices") {
      onOpenHardware?.();
      return;
    }
    if (destination === "printing") {
      onOpenDocumentPrinting?.();
      return;
    }
    if (destination === "diagnostics" && onOpenDiagnostics) {
      onOpenDiagnostics();
      return;
    }
    setSelectedSection(normalizedDestination);
  }

  const handleCashInputModeChange = (value: string) => {
    const mode = persistCashInputModeSelection(value);
    if (mode) setCashInputMode(mode);
  };

  async function saveSaleInterfaceMode() {
    if (!session.accessToken || !canConfigureTerminal) return;
    setSaleInterfaceSaving(true);
    setSaleInterfaceMessage(null);
    try {
      const configuration = await saveSaleInterfaceConfiguration(
        saleInterfaceMode,
        session.accessToken,
        request
      );
      setSaleInterfaceMode(configuration.saleMode);
      setSavedSaleInterfaceMode(configuration.saleMode);
      onSaleInterfaceModeChange?.(configuration.saleMode);
      setSaleInterfaceMessage({ kind: "success", text: t("settings.saleInterface.saved") });
    } catch (failure) {
      setSaleInterfaceMessage({
        kind: "error",
        text: failure instanceof ApiError
          ? `${t("settings.saleInterface.saveError")} ${failure.message}`
          : t("settings.saleInterface.saveError")
      });
    } finally {
      setSaleInterfaceSaving(false);
    }
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

  function sectionHeading() {
    if (selectedSection === "account") return t("settings.account");
    if (selectedSection === "security") return t("settings.security");
    if (selectedSection === "reports") return t("settings.reports");
    if (selectedSection === "diagnostics") return t("settings.diagnostics");
    return t("settings.sale");
  }

  function sectionSubtitle() {
    if (selectedSection === "account") return t("settings.account.subtitle");
    if (selectedSection === "security") return t("settings.security.subtitle");
    if (selectedSection === "reports") return t("settings.reports.subtitle");
    if (selectedSection === "diagnostics") return t("settings.system.subtitle");
    return t("settings.sale.subtitle").replace(
      "{terminal}",
      `${t("login.terminalPrefix")} ${terminalContext.terminalCode}`
    );
  }

  const personalSection = selectedSection === "account"
    || selectedSection === "security"
    || selectedSection === "reports";

  return (
    <SaleSettingsShell
      app={app}
      locale={locale}
      session={session}
      terminalContext={terminalContext}
      active={selectedSection}
      onNavigate={handleNavigation}
      onBack={onBack}
      onLocaleChange={onLocaleChange}
      onLogout={onLogout}
      heading={sectionHeading()}
      subtitle={sectionSubtitle()}
      scopeLabel={t(personalSection ? "settings.scope.user" : "settings.scope.terminal")}
    >
      {selectedSection === "account" ? (
        <section className="sale-settings-panel sale-settings-account">
          <h3>{t("settings.user.profile")}</h3>
          <dl className="sale-settings-readonly-list">
            <div className="sale-settings-readonly-row">
              <dt>{t("settings.user.name")}</dt><dd>{session.displayName}</dd>
            </div>
            <div className="sale-settings-readonly-row">
              <dt>{t("settings.user.username")}</dt><dd>{session.username}</dd>
            </div>
            <div className="sale-settings-readonly-row">
              <dt>{t("settings.user.role")}</dt><dd>{session.role ?? "-"}</dd>
            </div>
            <div className="sale-settings-readonly-row">
              <dt>{t("settings.user.maxDiscount")}</dt>
              <dd>{session.maxDiscountPercent == null ? "-" : `${session.maxDiscountPercent}%`}</dd>
            </div>
          </dl>
          <div className="sale-settings-section-divider" />
          <fieldset className="settings-language-options sale-settings-language-options">
            <legend>{t("settings.user.language")}</legend>
            <div>
              {(["es", "en", "zh"] as const).map((code) => (
                <button
                  type="button"
                  className={locale === code ? "selected" : ""}
                  aria-pressed={locale === code}
                  key={code}
                  onClick={() => onLocaleChange(code)}
                >
                  {languageLabel(code)}
                </button>
              ))}
            </div>
          </fieldset>
        </section>
      ) : null}

      {selectedSection === "security" ? (
        <section className="sale-settings-panel settings-user-security">
          <h3>{t("settings.user.security")}</h3>
          <p>{t("settings.user.passwordHelp")}</p>
          <form className="sale-settings-security-form" onSubmit={(event) => void handlePasswordChange(event)}>
            <label>{t("settings.user.currentPassword")}
              <input type="password" inputMode="numeric" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.currentTarget.value)} />
            </label>
            <label>{t("settings.user.newPassword")}
              <input type="password" inputMode="numeric" pattern="[0-9]*" minLength={4} maxLength={12} autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.currentTarget.value)} />
            </label>
            <label>{t("settings.user.confirmPassword")}
              <input type="password" inputMode="numeric" pattern="[0-9]*" minLength={4} maxLength={12} autoComplete="new-password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.currentTarget.value)} />
            </label>
            {passwordMessage ? (
              <p className={`settings-user-message ${passwordMessage.kind}`} role={passwordMessage.kind === "error" ? "alert" : "status"}>
                {passwordMessage.text}
              </p>
            ) : null}
            <button
              type="submit"
              className="sale-settings-action-button"
              disabled={passwordSaving || !currentPassword || !newPassword || !confirmPassword}
            >
              <Key size={18} weight="bold" aria-hidden="true" />
              {passwordSaving ? t("settings.user.passwordSaving") : t("settings.user.passwordAction")}
            </button>
          </form>
        </section>
      ) : null}

      {selectedSection === "reports" ? (
        <div className="sale-settings-reports-layout">
          <section className="sale-settings-panel settings-report-preferences">
            <h3>{t("settings.reports.visualization")}</h3>
            <p>{t("settings.reports.visualizationHelp")}</p>
            <label htmlFor="report-density">{t("settings.reports.density")}</label>
            <select id="report-density" value={reportPreferences.density} onChange={(event) => updateReportDensity(event.currentTarget.value as SalesReportDensity)}>
              <option value="comfortable">{t("settings.reports.densityComfortable")}</option>
              <option value="compact">{t("settings.reports.densityCompact")}</option>
            </select>
            <div className={`settings-report-density-preview ${reportPreferences.density}`} aria-hidden="true">
              <span /><span /><span />
            </div>
            <p className="settings-report-note">{t("settings.reports.columnsHelp")}</p>
            <button
              type="button"
              className="sale-settings-action-button"
              onClick={onOpenReports}
              disabled={!onOpenReports}
            >
              <ArrowSquareOut size={18} weight="bold" aria-hidden="true" />
              {t("settings.reports.openReports")}
            </button>
          </section>
          <section className="sale-settings-panel settings-report-preferences">
            <h3>{t("settings.reports.output")}</h3>
            <p>{t("settings.reports.outputHelp")}</p>
            <label htmlFor="report-primary-action">{t("settings.reports.primaryAction")}</label>
            <select id="report-primary-action" value={reportPreferences.primaryAction} onChange={(event) => updateReportPrimaryAction(event.currentTarget.value as SalesReportPrimaryAction)}>
              <option value="menu">{t("settings.reports.actionMenu")}</option>
              <option value="print">{t("settings.reports.actionPrint")}</option>
              <option value="pdf">{t("settings.reports.actionPdf")}</option>
              <option value="excel">{t("settings.reports.actionExcel")}</option>
            </select>
            <p className="settings-report-saved" role="status">{t("settings.reports.savedLocally")}</p>
          </section>
        </div>
      ) : null}

      {selectedSection === "sale" && canConfigureTerminal ? (
        <div className="sale-settings-sale-layout">
          <CashOperationsCard
            locale={locale}
            currentUsername={session.username}
            token={session.accessToken}
            terminalId={terminalContext.terminalId}
            request={request}
          />

          <div className="sale-settings-section-divider" />
          <section className="sale-settings-panel settings-sale-interface-card">
            <h3>{t("settings.saleInterface")}</h3>
            <p>{t("settings.saleInterface.description")}</p>
            {saleInterfaceLoading ? (
              <p role="status">{t("settings.saleInterface.loading")}</p>
            ) : (
              <>
                <fieldset className="settings-sale-interface-options" disabled={saleInterfaceSaving}>
                  <legend>{t("settings.saleInterface.mode")}</legend>
                  <label className={saleInterfaceMode === "KEYBOARD" ? "selected" : ""}>
                    <input type="radio" name="sale-interface-mode" value="KEYBOARD" checked={saleInterfaceMode === "KEYBOARD"} onChange={() => setSaleInterfaceMode("KEYBOARD")} />
                    <span><strong>{t("settings.saleInterface.keyboard")}</strong><small>{t("settings.saleInterface.keyboardHelp")}</small></span>
                  </label>
                  <label className={saleInterfaceMode === "TOUCH" ? "selected" : ""}>
                    <input type="radio" name="sale-interface-mode" value="TOUCH" checked={saleInterfaceMode === "TOUCH"} onChange={() => setSaleInterfaceMode("TOUCH")} />
                    <span><strong>{t("settings.saleInterface.touch")}</strong><small>{t("settings.saleInterface.touchHelp")}</small></span>
                  </label>
                </fieldset>
                <button type="button" disabled={saleInterfaceSaving || saleInterfaceMode === savedSaleInterfaceMode} onClick={() => void saveSaleInterfaceMode()}>
                  {saleInterfaceSaving ? t("settings.saleInterface.saving") : t("settings.saleInterface.save")}
                </button>
              </>
            )}
            {saleInterfaceMessage ? (
              <p className={`settings-user-message ${saleInterfaceMessage.kind}`} role={saleInterfaceMessage.kind === "error" ? "alert" : "status"}>
                {saleInterfaceMessage.text}
              </p>
            ) : null}
          </section>

          <section className="sale-settings-panel settings-cash-input-card">
            <h3>{t("settings.cashInput")}</h3>
            <p>{t("settings.cashInput.description")}</p>
            <label htmlFor="cash-input-mode">{t("settings.cashInput")}</label>
            <select id="cash-input-mode" value={cashInputMode} onChange={(event) => handleCashInputModeChange(event.currentTarget.value)}>
              <option value="touch">{t("settings.cashInput.touch")}</option>
              <option value="keyboard">{t("settings.cashInput.keyboard")}</option>
            </select>
          </section>

          <div className="sale-settings-section-divider" />
          <PaymentTerminalSettings locale={locale} token={session.accessToken} />
        </div>
      ) : null}

      {selectedSection === "diagnostics" && canConfigureTerminal ? (
        <div className="sale-settings-sale-layout">
          <SystemCompatibilityCard locale={locale} token={session.accessToken} />
          <OperationalStatusCard locale={locale} token={session.accessToken} request={request} />
        </div>
      ) : null}
    </SaleSettingsShell>
  );
}

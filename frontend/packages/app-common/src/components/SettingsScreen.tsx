import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowSquareOut, Key } from "@phosphor-icons/react";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  readCashInputMode,
  persistCashInputModeSelection,
  type CashInputMode
} from "../sale/cashInputMode";
import {
  defaultSaleInterfaceMode,
  loadSaleInterfaceConfiguration,
  saveSaleInterfaceConfiguration,
  type SaleInterfaceMode
} from "./saleInterfacePreferences";
import { SystemCompatibilityCard } from "./SystemCompatibilityCard";
import { CashOperationsCard } from "./CashOperationsCard";
import { ErpSelect } from "./ErpSelect";
import { OperationalStatusCard } from "./OperationalStatusCard";
import { apiRequest, ApiError } from "../api/client";
import { hasPermission } from "../auth/auth";
import {
  readSalesReportOutputPreferences,
  saveSalesReportOutputPreferences,
  type SalesReportDensity,
  type SalesReportPrimaryAction
} from "./salesReportOutputPreferences";
import {
  SaleSettingsShell,
  normalizeSaleSettingsDestination,
  type CanonicalSaleSettingsDestination,
  type SaleSettingsDestination
} from "./SaleSettingsShell";
import { useSettingsNavigationGuard, type SettingsSaveResult } from "./useSettingsNavigationGuard";

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

const protectedDestinations = new Set<CanonicalSaleSettingsDestination>([
  "devices",
  "printers",
  "cash",
  "diagnostics"
]);

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
  const [selectedSection, setSelectedSection] = useState<CanonicalSaleSettingsDestination>(() => {
    const destination = initialDestination && normalizeSaleSettingsDestination(initialDestination);
    if (destination && (canConfigureTerminal || !protectedDestinations.has(destination))) {
      return destination;
    }
    return canConfigureTerminal ? "visualization" : "account";
  });
  const passwordInputRef = useRef<HTMLInputElement>(null);
  const passwordFocusRequested = useRef(initialDestination === "security");
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
  const { requestNavigation, confirmationDialog } = useSettingsNavigationGuard({
    dirty: selectedSection === "visualization" && saleInterfaceMode !== savedSaleInterfaceMode,
    saving: saleInterfaceSaving,
    locale,
    save: saveSaleInterfaceMode,
    discard: () => {
      setSaleInterfaceMode(savedSaleInterfaceMode);
      setSaleInterfaceMessage(null);
    }
  });

  useEffect(() => {
    if (!canConfigureTerminal && protectedDestinations.has(selectedSection)) {
      setSelectedSection("account");
    }
  }, [canConfigureTerminal, selectedSection]);

  useEffect(() => {
    const destination = initialDestination && normalizeSaleSettingsDestination(initialDestination);
    if (destination && (canConfigureTerminal || !protectedDestinations.has(destination))) {
      if (initialDestination === "security") passwordFocusRequested.current = true;
      setSelectedSection(destination);
      if (destination === "account" && initialDestination === "security") {
        passwordInputRef.current?.focus();
        if (passwordInputRef.current) passwordFocusRequested.current = false;
      }
    }
  }, [canConfigureTerminal, initialDestination]);

  useEffect(() => {
    if (selectedSection === "account" && passwordFocusRequested.current) {
      passwordInputRef.current?.focus();
      passwordFocusRequested.current = false;
    }
  }, [selectedSection]);

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
    const normalizedDestination = normalizeSaleSettingsDestination(destination);
    if (protectedDestinations.has(normalizedDestination) && !canConfigureTerminal) return;
    const navigate = () => {
      if (destination === "devices") {
        onOpenHardware?.();
        return;
      }
      if (normalizedDestination === "printers") {
        onOpenDocumentPrinting?.();
        return;
      }
      if (destination === "diagnostics" && onOpenDiagnostics) {
        onOpenDiagnostics();
        return;
      }
      if (destination === "security") {
        passwordFocusRequested.current = true;
        passwordInputRef.current?.focus();
        if (passwordInputRef.current) passwordFocusRequested.current = false;
      }
      setSelectedSection(normalizedDestination);
    };
    if (normalizedDestination === selectedSection) navigate();
    else requestNavigation(navigate);
  }

  const handleCashInputModeChange = (value: string) => {
    const mode = persistCashInputModeSelection(value);
    if (mode) setCashInputMode(mode);
  };

  async function saveSaleInterfaceMode(): Promise<SettingsSaveResult> {
    if (!session.accessToken || !canConfigureTerminal) {
      return { ok: false, error: t("settings.saleInterface.saveError") };
    }
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
      return { ok: true };
    } catch (failure) {
      const message = failure instanceof ApiError
        ? `${t("settings.saleInterface.saveError")} ${failure.message}`
        : t("settings.saleInterface.saveError");
      setSaleInterfaceMessage({
        kind: "error",
        text: message
      });
      return { ok: false, error: message };
    } finally {
      setSaleInterfaceSaving(false);
    }
  }

  function updateReportDensity(density: SalesReportDensity) {
    const next = { ...reportPreferences, density };
    setReportPreferences(next);
    saveSalesReportOutputPreferences(app, session.username, terminalContext, next);
  }

  function updateGestionReportPrimaryAction(primaryAction: SalesReportPrimaryAction) {
    if (app !== "gestion") return;
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
    if (selectedSection === "account") return t("settings.accountSecurity");
    if (selectedSection === "visualization") return t("settings.visualization");
    if (selectedSection === "cash") return t("settings.cash");
    return t("settings.diagnosticsMaintenance");
  }

  function sectionSubtitle() {
    if (selectedSection === "account") return t("settings.accountSecurity.subtitle");
    if (selectedSection === "visualization") return t("settings.visualization.subtitle");
    if (selectedSection === "cash") return t("settings.cash.subtitle");
    if (selectedSection === "diagnostics") return t("settings.system.subtitle");
    return "";
  }

  return (
    <SaleSettingsShell
      app={app}
      locale={locale}
      session={session}
      terminalContext={terminalContext}
      active={selectedSection}
      onNavigate={handleNavigation}
      onBack={() => requestNavigation(onBack)}
      onLocaleChange={(nextLocale) => requestNavigation(() => onLocaleChange(nextLocale))}
      onLogout={onLogout ? () => requestNavigation(onLogout) : undefined}
      heading={sectionHeading()}
      subtitle={sectionSubtitle()}
      scopeLabel={selectedSection === "account" ? t("settings.scope.user")
        : selectedSection === "cash" ? t("settings.scope.terminal") : undefined}
    >
      {selectedSection === "account" ? (
        <div className="sale-settings-account-layout">
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
        </section>
        <section className="sale-settings-panel settings-user-language">
          <fieldset className="sale-settings-fieldset settings-language-options sale-settings-language-options">
            <legend>{t("settings.user.language")}</legend>
            <div>
              {(["es", "en", "zh"] as const).map((code) => (
                <button
                  type="button"
                  className={locale === code ? "selected" : ""}
                  aria-pressed={locale === code}
                  key={code}
                  onClick={() => requestNavigation(() => onLocaleChange(code))}
                >
                  {languageLabel(code)}
                </button>
              ))}
            </div>
          </fieldset>
        </section>
        <section className="sale-settings-panel settings-user-security">
          <h3>{t("settings.user.security")}</h3>
          <p>{t("settings.user.passwordHelp")}</p>
          <form className="sale-settings-security-form" onSubmit={(event) => void handlePasswordChange(event)}>
            <label>{t("settings.user.currentPassword")}
              <input ref={passwordInputRef} type="password" inputMode="numeric" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.currentTarget.value)} />
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
        </div>
      ) : null}

      {selectedSection === "visualization" ? (
        <div className="sale-settings-visualization-layout">
          <section className="sale-settings-panel settings-report-preferences">
            <h3>{t("settings.reports.visualization")}</h3>
            <p>{t("settings.reports.visualizationHelp")}</p>
            <div className="sale-settings-report-controls">
              <div>
                <label htmlFor="report-density">{t("settings.reports.density")}</label>
                <ErpSelect
                  id="report-density"
                  aria-label={t("settings.reports.density")}
                  value={reportPreferences.density}
                  options={([
                    { value: "comfortable", label: t("settings.reports.densityComfortable") },
                    { value: "compact", label: t("settings.reports.densityCompact") }
                  ])}
                  onChange={(value) => updateReportDensity(value as SalesReportDensity)}
                />
                <p className="settings-report-note">{t("settings.reports.columnsHelp")}</p>
                <p className="settings-report-saved" role="status">{t("settings.visualization.localScope")}</p>
              </div>
              <div className={`sale-settings-report-preview ${reportPreferences.density}`}>
                <span>{t("settings.reports.preview")}</span>
                <table aria-hidden="true">
                  <thead><tr>
                    <th>{t("salesReport.column.ticket")}</th>
                    <th>{t("salesReport.column.date")}</th>
                    <th>{t("salesReport.column.customer")}</th>
                    <th>{t("salesReport.column.total")}</th>
                  </tr></thead>
                  <tbody>
                    <tr><td>T-001</td><td>30/09/2026</td><td>—</td><td>25,00</td></tr>
                    <tr><td>T-002</td><td>29/09/2026</td><td>—</td><td>43,50</td></tr>
                    <tr><td>T-003</td><td>28/09/2026</td><td>—</td><td>18,75</td></tr>
                  </tbody>
                </table>
              </div>
            </div>
            <button
              type="button"
              className="sale-settings-action-button"
              onClick={() => requestNavigation(() => onOpenReports?.())}
              disabled={!onOpenReports}
            >
              <ArrowSquareOut size={18} weight="bold" aria-hidden="true" />
              {t("settings.reports.openReports")}
            </button>
          </section>
          {app === "gestion" ? (
            <section className="sale-settings-panel settings-report-preferences">
              <h3>{t("settings.reports.output")}</h3>
              <p>{t("settings.reports.outputHelp")}</p>
              <label htmlFor="report-primary-action">{t("settings.reports.primaryAction")}</label>
              <ErpSelect
                id="report-primary-action"
                aria-label={t("settings.reports.primaryAction")}
                value={reportPreferences.primaryAction}
                options={[
                  { value: "menu", label: t("settings.reports.actionMenu") },
                  { value: "print", label: t("settings.reports.actionPrint") },
                  { value: "pdf", label: t("settings.reports.actionPdf") },
                  { value: "excel", label: t("settings.reports.actionExcel") }
                ]}
                onChange={(value) => updateGestionReportPrimaryAction(value as SalesReportPrimaryAction)}
              />
            </section>
          ) : null}
          {canConfigureTerminal ? (
          <>
          <section className="sale-settings-panel settings-sale-interface-card">
            <h3>{t("settings.saleInterface")}</h3>
            <p>{t("settings.saleInterface.description")}</p>
            {saleInterfaceLoading ? (
              <p role="status">{t("settings.saleInterface.loading")}</p>
            ) : (
              <>
                <fieldset className="sale-settings-fieldset settings-sale-interface-options" disabled={saleInterfaceSaving}>
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
                <button type="button" className="sale-settings-action-button" disabled={saleInterfaceSaving || saleInterfaceMode === savedSaleInterfaceMode} onClick={() => void saveSaleInterfaceMode()}>
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
            <ErpSelect
              id="cash-input-mode"
              aria-label={t("settings.cashInput")}
              value={cashInputMode}
              options={[
                { value: "touch", label: t("settings.cashInput.touch") },
                { value: "keyboard", label: t("settings.cashInput.keyboard") }
              ]}
              onChange={handleCashInputModeChange}
            />
          </section>
          </>
          ) : null}
        </div>
      ) : null}

      {selectedSection === "cash" && canConfigureTerminal ? (
        <div className="sale-settings-cash-layout">
          <CashOperationsCard
            locale={locale}
            currentUsername={session.username}
            token={session.accessToken}
            terminalId={terminalContext.terminalId}
            request={request}
          />
        </div>
      ) : null}

      {selectedSection === "diagnostics" && canConfigureTerminal ? (
        <div className="sale-settings-sale-layout">
          <SystemCompatibilityCard locale={locale} token={session.accessToken} />
          <OperationalStatusCard locale={locale} token={session.accessToken} request={request} />
        </div>
      ) : null}
      {confirmationDialog}
    </SaleSettingsShell>
  );
}

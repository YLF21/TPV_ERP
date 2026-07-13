import { useEffect, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

export type SimulatorOutcome = "APPROVED" | "DECLINED" | "TIMEOUT" | "CONNECTION_ERROR";
type PaymentCardMode = "MANUAL" | "INTEGRATED";
type PaymentTerminalProvider = "NONE" | "REDSYS_TPV_PC" | "PAYTEF" | "PAYCOMET" | "GLOBAL_PAYMENTS";

export type PaymentTerminalConfigurationView = {
  terminalId: string;
  rules: {
    cardManualEnabled: boolean;
    cardManualReferenceRequired: boolean;
    integratedCardEnabled: boolean;
    manualFallbackEnabled: boolean;
    allowedPaymentTerminalProviders: string[];
  };
  configuration: {
    cardMode: PaymentCardMode;
    provider: PaymentTerminalProvider;
    displayName: string | null;
    enabled: boolean;
    testMode: boolean;
    providerParameters: Record<string, string>;
    lastConnectionTestAt: string | null;
    lastConnectionStatus: string | null;
    secretConfigured: boolean;
  };
};

export type PaymentTerminalSettingsForm = {
  cardMode: PaymentCardMode;
  provider: PaymentTerminalProvider;
  displayName: string;
  enabled: boolean;
  testMode: boolean;
  simulatorOutcome: SimulatorOutcome;
};

const paymentConfigurationPath = "/terminal-configuration/payment";
const simulatorOutcomes: SimulatorOutcome[] = ["APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"];

export function loadPaymentTerminalConfiguration(token?: string) {
  return apiRequest<PaymentTerminalConfigurationView>(paymentConfigurationPath, { method: "GET", token });
}

export function savePaymentTerminalConfiguration(token: string | undefined, form: PaymentTerminalSettingsForm) {
  return apiRequest<PaymentTerminalConfigurationView>(paymentConfigurationPath, {
    method: "PATCH",
    token,
    body: paymentTerminalUpdatePayload(form)
  });
}

export function paymentTerminalUpdatePayload(form: PaymentTerminalSettingsForm) {
  const integrated = form.cardMode === "INTEGRATED";
  return {
    cardMode: form.cardMode,
    provider: integrated ? form.provider : "NONE" as PaymentTerminalProvider,
    displayName: form.displayName.trim(),
    enabled: form.enabled,
    testMode: integrated && form.testMode,
    providerParameters: integrated && form.testMode ? { simulatorOutcome: form.simulatorOutcome } : {}
  };
}

export function changePaymentCardMode(
  form: PaymentTerminalSettingsForm,
  mode: PaymentCardMode,
  allowedProviders: string[]
): PaymentTerminalSettingsForm {
  if (mode === "MANUAL") {
    return { ...form, cardMode: mode, provider: "NONE", testMode: false };
  }
  const provider = allowedProviders.includes("REDSYS_TPV_PC") ? "REDSYS_TPV_PC" : "NONE";
  return { ...form, cardMode: mode, provider };
}

export function paymentTerminalRulesError(
  form: PaymentTerminalSettingsForm,
  rules: PaymentTerminalConfigurationView["rules"]
): "manualDisabled" | "integratedDisabled" | "providerNotAllowed" | null {
  if (form.cardMode === "MANUAL" && !rules.cardManualEnabled) return "manualDisabled";
  if (form.cardMode === "INTEGRATED" && !rules.integratedCardEnabled) return "integratedDisabled";
  if (form.cardMode === "INTEGRATED" && !rules.allowedPaymentTerminalProviders.includes(form.provider)) return "providerNotAllowed";
  return null;
}

export function connectionTestSucceeded(status: string | null) {
  return status === "OK";
}

export function testPaymentTerminalConnection(token?: string) {
  return apiRequest<PaymentTerminalConfigurationView>(`${paymentConfigurationPath}/connection-test`, {
    method: "POST",
    token
  });
}

function toForm(view: PaymentTerminalConfigurationView): PaymentTerminalSettingsForm {
  const outcome = view.configuration.providerParameters.simulatorOutcome;
  return {
    cardMode: view.configuration.cardMode,
    provider: view.configuration.provider,
    displayName: view.configuration.displayName ?? "",
    enabled: view.configuration.enabled,
    testMode: view.configuration.testMode,
    simulatorOutcome: simulatorOutcomes.includes(outcome as SimulatorOutcome)
      ? outcome as SimulatorOutcome
      : "APPROVED"
  };
}

type Props = {
  locale: LocaleCode;
  token?: string;
  initialConfiguration?: PaymentTerminalConfigurationView;
};

export function PaymentTerminalSettings({ locale, token, initialConfiguration }: Props) {
  const t = createTranslator(locale);
  const [configuration, setConfiguration] = useState(initialConfiguration);
  const [form, setForm] = useState<PaymentTerminalSettingsForm | null>(
    initialConfiguration ? toForm(initialConfiguration) : null
  );
  const [busy, setBusy] = useState(!initialConfiguration);
  const [message, setMessage] = useState<string | null>(null);
  const [isError, setIsError] = useState(false);

  useEffect(() => {
    if (initialConfiguration) return;
    let active = true;
    loadPaymentTerminalConfiguration(token)
      .then((view) => {
        if (!active) return;
        setConfiguration(view);
        setForm(toForm(view));
      })
      .catch(() => {
        if (!active) return;
        setIsError(true);
        setMessage(t("settings.paymentTerminal.loadError"));
      })
      .finally(() => active && setBusy(false));
    return () => { active = false; };
  }, [initialConfiguration, token, locale]);

  const update = <K extends keyof PaymentTerminalSettingsForm>(key: K, value: PaymentTerminalSettingsForm[K]) => {
    setForm((current) => current ? { ...current, [key]: value } : current);
    setMessage(null);
  };

  const save = async () => {
    if (!form || busy) return;
    setBusy(true);
    setMessage(null);
    try {
      const view = await savePaymentTerminalConfiguration(token, form);
      setConfiguration(view);
      setForm(toForm(view));
      setIsError(false);
      setMessage(t("settings.paymentTerminal.saved"));
    } catch {
      setIsError(true);
      setMessage(t("settings.paymentTerminal.saveError"));
    } finally {
      setBusy(false);
    }
  };

  const testConnection = async () => {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      const view = await testPaymentTerminalConnection(token);
      setConfiguration(view);
      const ok = connectionTestSucceeded(view.configuration.lastConnectionStatus);
      setIsError(!ok);
      setMessage(t(ok ? "settings.paymentTerminal.testSuccess" : "settings.paymentTerminal.testFailed"));
    } catch {
      setIsError(true);
      setMessage(t("settings.paymentTerminal.testError"));
    } finally {
      setBusy(false);
    }
  };

  if (!form) {
    return <article className="settings-card settings-card-wide payment-terminal-settings"><h3>{t("settings.paymentTerminal")}</h3><p role="status">{message ?? t("settings.paymentTerminal.loading")}</p></article>;
  }

  const rulesError = paymentTerminalRulesError(form, configuration!.rules);
  const integrated = form.cardMode === "INTEGRATED";
  const changeMode = (mode: PaymentCardMode) => {
    setForm((current) => current ? changePaymentCardMode(current, mode, configuration!.rules.allowedPaymentTerminalProviders) : current);
    setMessage(null);
  };

  return (
    <article className="settings-card settings-card-wide payment-terminal-settings">
      <h3>{t("settings.paymentTerminal")}</h3>
      <p>{t("settings.paymentTerminal.description")}</p>
      <div className="payment-terminal-form">
        <label>{t("settings.paymentTerminal.mode")}<select value={form.cardMode} disabled={busy} onChange={(e) => changeMode(e.currentTarget.value as PaymentCardMode)}><option value="MANUAL" disabled={!configuration!.rules.cardManualEnabled}>{t("settings.paymentTerminal.manual")}</option><option value="INTEGRATED" disabled={!configuration!.rules.integratedCardEnabled}>{t("settings.paymentTerminal.integrated")}</option></select></label>
        <label>{t("settings.paymentTerminal.provider")}<select value={form.provider} disabled={busy || !integrated} onChange={(e) => update("provider", e.currentTarget.value as PaymentTerminalProvider)}><option value="NONE">{t("settings.paymentTerminal.none")}</option>{configuration!.rules.allowedPaymentTerminalProviders.includes("REDSYS_TPV_PC") && <option value="REDSYS_TPV_PC">Redsys TPV-PC</option>}</select></label>
        <label>{t("settings.paymentTerminal.displayName")}<input value={form.displayName} disabled={busy} onChange={(e) => update("displayName", e.currentTarget.value)} /></label>
        {integrated && <label>{t("settings.paymentTerminal.outcome")}<select value={form.simulatorOutcome} disabled={busy || !form.testMode} onChange={(e) => update("simulatorOutcome", e.currentTarget.value as SimulatorOutcome)}>{simulatorOutcomes.map((outcome) => <option value={outcome} key={outcome}>{t(`settings.paymentTerminal.outcome.${outcome}`)}</option>)}</select></label>}
        <label className="payment-terminal-check"><input type="checkbox" checked={form.enabled} disabled={busy} onChange={(e) => update("enabled", e.currentTarget.checked)} />{t("settings.paymentTerminal.enabled")}</label>
        {integrated && <label className="payment-terminal-check"><input type="checkbox" checked={form.testMode} disabled={busy} onChange={(e) => update("testMode", e.currentTarget.checked)} />{t("settings.paymentTerminal.testMode")}</label>}
      </div>
      {rulesError && <p role="alert" className="payment-terminal-error">{t(`settings.paymentTerminal.rules.${rulesError}`)}</p>}
      {configuration?.configuration.lastConnectionStatus && <p className="payment-terminal-last-test">{t("settings.paymentTerminal.lastTest")}: {configuration.configuration.lastConnectionStatus}</p>}
      {message && <p role={isError ? "alert" : "status"} className={isError ? "payment-terminal-error" : "payment-terminal-success"}>{message}</p>}
      <div className="payment-terminal-actions"><button type="button" disabled={busy || !!rulesError} onClick={save}>{busy ? t("settings.paymentTerminal.working") : t("settings.paymentTerminal.save")}</button><button type="button" className="secondary" disabled={busy || !!rulesError || !integrated || !form.enabled || !form.testMode} onClick={testConnection}>{t("settings.paymentTerminal.test")}</button></div>
    </article>
  );
}

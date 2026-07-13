import { useEffect, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

export type SimulatorOutcome = "APPROVED" | "DECLINED" | "TIMEOUT" | "CONNECTION_ERROR";
type PaymentCardMode = "MANUAL" | "INTEGRATED";
type PaymentTerminalProvider = "NONE" | "REDSYS_TPV_PC" | "PAYTEF" | "PAYCOMET" | "GLOBAL_PAYMENTS";
type PaymentTerminalMode = "MANUAL" | "SIMULATED" | "LIVE";
export type ProviderDescriptor = {
  provider: Exclude<PaymentTerminalProvider, "NONE">;
  displayName: string;
  supportedModes: Exclude<PaymentTerminalMode, "MANUAL">[];
  liveAvailable: boolean;
  unavailableReason: "SDK_NOT_INSTALLED" | null;
  capabilities: string[];
  fieldSchemas: Array<{ key: string; label: string; type: "TEXT" | "SELECT"; required: boolean;
    modes: Exclude<PaymentTerminalMode, "MANUAL">[]; options: string[] }>;
};

export type PaymentTerminalConfigurationView = {
  terminalId: string;
  providerDescriptors?: ProviderDescriptor[];
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
    pairingStatus?: "NOT_PAIRED" | "PAIRED" | "NOT_REQUIRED";
  };
};

export type PaymentTerminalSettingsForm = {
  cardMode: PaymentCardMode;
  provider: PaymentTerminalProvider;
  displayName: string;
  enabled: boolean;
  testMode?: boolean;
  terminalMode?: PaymentTerminalMode;
  simulatorOutcome: SimulatorOutcome;
  providerParameters?: Record<string, string>;
  secretInput?: string;
};

const paymentConfigurationPath = "/terminal-configuration/payment";
const simulatorOutcomes: SimulatorOutcome[] = ["APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"];
const providerNames: Record<Exclude<PaymentTerminalProvider, "NONE">, string> = {
  REDSYS_TPV_PC: "Redsys TPV-PC", PAYTEF: "PAYTEF", PAYCOMET: "PAYCOMET", GLOBAL_PAYMENTS: "Global Payments"
};
type PairingView = { status: string; code: string; reference: string | null; message: string };

function descriptors(view: PaymentTerminalConfigurationView): ProviderDescriptor[] {
  if (view.providerDescriptors?.length) {
    return view.providerDescriptors.filter((item) => view.rules.allowedPaymentTerminalProviders.includes(item.provider));
  }
  return view.rules.allowedPaymentTerminalProviders.filter((provider): provider is Exclude<PaymentTerminalProvider, "NONE"> => provider in providerNames)
    .map((provider) => ({ provider, displayName: providerNames[provider], supportedModes: ["SIMULATED", "LIVE"],
      liveAvailable: false, unavailableReason: "SDK_NOT_INSTALLED", capabilities: ["CONNECTION_TEST"],
      fieldSchemas: [{ key: "simulatorOutcome", label: "simulatorOutcome", type: "SELECT", required: false,
        modes: ["SIMULATED"], options: simulatorOutcomes }] }));
}

export function normalizeTerminalMode(mode: PaymentTerminalMode, descriptor?: ProviderDescriptor): PaymentTerminalMode {
  if (!descriptor || descriptor.supportedModes.includes(mode as "SIMULATED" | "LIVE")) return mode;
  return descriptor.supportedModes[0] ?? mode;
}

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
  const simulated = integrated && (form.terminalMode ? form.terminalMode === "SIMULATED" : !!form.testMode);
  const providerParameters = Object.fromEntries(Object.entries(form.providerParameters ?? {}).filter(([key]) => {
    const normalized = key.toLowerCase();
    return !["secret", "password", "credential", "token", "apikey", "api_key"].some((part) => normalized.includes(part))
      && (simulated || key !== "simulatorOutcome");
  }));
  return {
    cardMode: form.cardMode,
    provider: integrated ? form.provider : "NONE" as PaymentTerminalProvider,
    displayName: form.displayName.trim(),
    enabled: form.enabled,
    testMode: simulated,
    providerParameters: simulated ? { ...providerParameters, simulatorOutcome: providerParameters.simulatorOutcome ?? form.simulatorOutcome } : providerParameters
  };
}

export function changePaymentCardMode(
  form: PaymentTerminalSettingsForm,
  mode: PaymentCardMode,
  allowedProviders: string[]
): PaymentTerminalSettingsForm {
  if (mode === "MANUAL") {
    return { ...form, cardMode: mode, provider: "NONE", testMode: false, terminalMode: "MANUAL" };
  }
  const provider = allowedProviders.includes("REDSYS_TPV_PC") ? "REDSYS_TPV_PC" : "NONE";
  return { ...form, cardMode: mode, provider, terminalMode: "SIMULATED", testMode: true };
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

export function startPaymentTerminalPairing(token: string | undefined, pairingId: string) {
  return apiRequest<PairingView>(`${paymentConfigurationPath}/pairing`, {
    method: "POST", token, body: { pairingId }
  });
}

export function loadPaymentTerminalPairingStatus(token: string | undefined, pairingId: string) {
  return apiRequest<PairingView>(`${paymentConfigurationPath}/pairing/${pairingId}`, { method: "GET", token });
}

function toForm(view: PaymentTerminalConfigurationView): PaymentTerminalSettingsForm {
  const outcome = view.configuration.providerParameters.simulatorOutcome;
  const descriptor = descriptors(view).find((item) => item.provider === view.configuration.provider);
  const requestedMode = view.configuration.cardMode === "MANUAL" ? "MANUAL" : view.configuration.testMode ? "SIMULATED" : "LIVE";
  const terminalMode = normalizeTerminalMode(requestedMode, descriptor);
  return {
    cardMode: view.configuration.cardMode,
    provider: view.configuration.provider,
    displayName: view.configuration.displayName ?? "",
    enabled: view.configuration.enabled,
    testMode: terminalMode === "SIMULATED",
    terminalMode,
    simulatorOutcome: simulatorOutcomes.includes(outcome as SimulatorOutcome)
      ? outcome as SimulatorOutcome
      : "APPROVED",
    providerParameters: { ...view.configuration.providerParameters }
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
  const [pairingId, setPairingId] = useState<string | null>(null);
  const [pairingStatus, setPairingStatus] = useState<string | null>(initialConfiguration?.configuration.pairingStatus ?? null);

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

  const pair = async () => {
    if (busy) return;
    setBusy(true);
    const id = pairingId ?? crypto.randomUUID();
    try {
      const result = await startPaymentTerminalPairing(token, id);
      setPairingId(id);
      setPairingStatus(result.code);
      setIsError(result.code !== "PAIRED");
      setMessage(result.message);
    } catch {
      setIsError(true);
      setMessage(t("settings.paymentTerminal.pairingError"));
    } finally { setBusy(false); }
  };

  const refreshPairing = async () => {
    if (busy || !pairingId) return;
    setBusy(true);
    try {
      const result = await loadPaymentTerminalPairingStatus(token, pairingId);
      setPairingStatus(result.code);
      setIsError(result.code !== "PAIRED");
      setMessage(result.message);
    } catch {
      setIsError(true);
      setMessage(t("settings.paymentTerminal.pairingError"));
    } finally { setBusy(false); }
  };

  if (!form) {
    return <article className="settings-card settings-card-wide payment-terminal-settings"><h3>{t("settings.paymentTerminal")}</h3><p role="status">{message ?? t("settings.paymentTerminal.loading")}</p></article>;
  }

  const rulesError = paymentTerminalRulesError(form, configuration!.rules);
  const integrated = form.cardMode === "INTEGRATED";
  const availableDescriptors = descriptors(configuration!);
  const descriptor = availableDescriptors.find((item) => item.provider === form.provider);
  const terminalMode = normalizeTerminalMode(form.terminalMode ?? (form.testMode ? "SIMULATED" : "LIVE"), descriptor);
  const connectionTestAvailable = !!descriptor?.capabilities.includes("CONNECTION_TEST");
  const fieldLabel = (label: string) => label.startsWith("settings.") ? t(label) : label;
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
        <label>{t("settings.paymentTerminal.provider")}<select value={form.provider} disabled={busy || !integrated} onChange={(e) => { const provider = e.currentTarget.value as PaymentTerminalProvider; const selected = availableDescriptors.find((item) => item.provider === provider); setForm((current) => current ? { ...current, provider, terminalMode: normalizeTerminalMode(current.terminalMode ?? "SIMULATED", selected), testMode: normalizeTerminalMode(current.terminalMode ?? "SIMULATED", selected) === "SIMULATED", providerParameters: {} } : current); }}><option value="NONE">{t("settings.paymentTerminal.none")}</option>{availableDescriptors.map((item) => <option value={item.provider} key={item.provider}>{item.displayName}</option>)}</select></label>
        {integrated && <label>{t("settings.paymentTerminal.terminalMode")}<select value={terminalMode} disabled={busy} onChange={(e) => { const mode = e.currentTarget.value as PaymentTerminalMode; update("terminalMode", mode); update("testMode", mode === "SIMULATED"); }}>{descriptor?.supportedModes.map((mode) => <option key={mode} value={mode} disabled={mode === "LIVE" && !descriptor.liveAvailable}>{t(mode === "SIMULATED" ? "settings.paymentTerminal.simulated" : "settings.paymentTerminal.live")}</option>)}</select></label>}
        {integrated && descriptor?.unavailableReason && <p className="payment-terminal-hint">{t(`settings.paymentTerminal.unavailable.${descriptor.unavailableReason}`)}</p>}
        <label>{t("settings.paymentTerminal.displayName")}<input value={form.displayName} disabled={busy} onChange={(e) => update("displayName", e.currentTarget.value)} /></label>
        {integrated && descriptor?.fieldSchemas.filter((field) => field.modes.includes(terminalMode as "SIMULATED" | "LIVE")).map((field) => <label key={field.key}>{fieldLabel(field.label)}{field.type === "SELECT" ? <select value={form.providerParameters?.[field.key] ?? ""} required={field.required} disabled={busy} onChange={(e) => update("providerParameters", { ...form.providerParameters, [field.key]: e.currentTarget.value })}>{field.options.map((option) => <option value={option} key={option}>{t(`settings.paymentTerminal.outcome.${option}`) === `settings.paymentTerminal.outcome.${option}` ? option : t(`settings.paymentTerminal.outcome.${option}`)}</option>)}</select> : <input value={form.providerParameters?.[field.key] ?? ""} required={field.required} disabled={busy} onChange={(e) => update("providerParameters", { ...form.providerParameters, [field.key]: e.currentTarget.value })} />}</label>)}
        <label className="payment-terminal-check"><input type="checkbox" checked={form.enabled} disabled={busy} onChange={(e) => update("enabled", e.currentTarget.checked)} />{t("settings.paymentTerminal.enabled")}</label>
      </div>
      {integrated && descriptor?.capabilities.includes("PAIRING") && <div className="payment-terminal-pairing"><p>{t("settings.paymentTerminal.pairingState")}: {pairingStatus === "PAIRED" ? t("settings.paymentTerminal.paired") : pairingStatus ?? t("settings.paymentTerminal.pairingUnknown")}</p><button type="button" disabled={busy} onClick={pair}>{t("settings.paymentTerminal.pair")}</button><button type="button" disabled={busy || !pairingId} onClick={refreshPairing}>{t("settings.paymentTerminal.refreshPairing")}</button></div>}
      {rulesError && <p role="alert" className="payment-terminal-error">{t(`settings.paymentTerminal.rules.${rulesError}`)}</p>}
      {configuration?.configuration.lastConnectionStatus && <p className="payment-terminal-last-test">{t("settings.paymentTerminal.lastTest")}: {configuration.configuration.lastConnectionStatus}</p>}
      {message && <p role={isError ? "alert" : "status"} className={isError ? "payment-terminal-error" : "payment-terminal-success"}>{message}</p>}
      <div className="payment-terminal-actions"><button type="button" disabled={busy || !!rulesError} onClick={save}>{busy ? t("settings.paymentTerminal.working") : t("settings.paymentTerminal.save")}</button><button type="button" className="secondary" disabled={busy || !!rulesError || !integrated || !form.enabled || !connectionTestAvailable} onClick={testConnection}>{t("settings.paymentTerminal.test")}</button></div>
    </article>
  );
}

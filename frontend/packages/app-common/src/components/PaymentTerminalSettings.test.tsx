import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  PaymentTerminalSettings,
  changePaymentCardMode,
  connectionTestSucceeded,
  loadPaymentTerminalConfiguration,
  paymentTerminalRulesError,
  paymentTerminalUpdatePayload,
  savePaymentTerminalConfiguration,
  testPaymentTerminalConnection,
  type PaymentTerminalConfigurationView,
  type SimulatorOutcome
} from "./PaymentTerminalSettings";

const configuration: PaymentTerminalConfigurationView = {
  terminalId: "terminal-1",
  providerDescriptors: ["REDSYS_TPV_PC", "PAYTEF", "PAYCOMET", "GLOBAL_PAYMENTS"].map((provider) => ({
    provider: provider as Exclude<PaymentTerminalConfigurationView["configuration"]["provider"], "NONE">,
    displayName: provider === "REDSYS_TPV_PC" ? "Redsys TPV-PC" : provider,
    supportedModes: ["SIMULATED", "LIVE"],
    liveAvailable: false,
    unavailableReason: "SDK_NOT_INSTALLED",
    capabilities: ["PAIRING", "CONNECTION_TEST", "CHARGE"],
    fields: [{ key: "simulatorOutcome", label: "Simulator outcome", type: "SELECT", required: false,
      modes: ["SIMULATED"], options: ["APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"] }]
  })),
  rules: {
    cardManualEnabled: true,
    cardManualReferenceRequired: false,
    integratedCardEnabled: true,
    manualFallbackEnabled: true,
    allowedPaymentTerminalProviders: ["REDSYS_TPV_PC"]
  },
  configuration: {
    cardMode: "INTEGRATED",
    provider: "REDSYS_TPV_PC",
    displayName: "Redsys caja",
    enabled: true,
    testMode: true,
    providerParameters: { simulatorOutcome: "DECLINED" },
    lastConnectionTestAt: null,
    lastConnectionStatus: "OK",
    secretConfigured: false
  }
};

describe("PaymentTerminalSettings", () => {
  it("renders all terminal fields and simulator outcomes", () => {
    const html = renderToStaticMarkup(
      <PaymentTerminalSettings locale="es" token="token" initialConfiguration={configuration} />
    );

    expect(html).toContain("Datáfono");
    expect(html).toContain('value="INTEGRATED" selected=""');
    expect(html).toContain("Redsys TPV-PC");
    expect(html).toContain("Redsys caja");
    for (const outcome of ["APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"]) {
      expect(html).toContain(`value="${outcome}"`);
    }
    expect(html).toContain("Probar conexión");
    expect(html).toContain("Guardar configuración");
  });

  it("renders every allowed provider dynamically and disables LIVE with SDK no instalado", () => {
    const html = renderToStaticMarkup(
      <PaymentTerminalSettings locale="es" initialConfiguration={configuration} />
    );
    for (const label of ["Redsys TPV-PC", "PAYTEF", "PAYCOMET", "GLOBAL_PAYMENTS"]) {
      expect(html).toContain(label);
    }
    expect(html).toContain('value="LIVE" disabled=""');
    expect(html).toContain("SDK no instalado");
  });

  it("shows simulator fields for every provider and keeps pairing and connection testing independent", () => {
    const paytef = { ...configuration,
      rules: { ...configuration.rules, allowedPaymentTerminalProviders: ["REDSYS_TPV_PC", "PAYTEF", "PAYCOMET", "GLOBAL_PAYMENTS"] },
      configuration: { ...configuration.configuration, provider: "PAYTEF" as const } };
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={paytef} />);
    expect(html).toContain("Próximo resultado del simulador");
    expect(html).toContain("Estado de emparejamiento");
    expect(html).toContain("Probar conexión");
    expect(html).not.toContain('disabled="">Probar conexión');
  });

  it("never includes rehydrated secret values in an update payload", () => {
    const payload = paymentTerminalUpdatePayload({
      cardMode: "INTEGRATED", provider: "PAYTEF", displayName: "Caja", enabled: true,
      terminalMode: "SIMULATED", simulatorOutcome: "APPROVED", providerParameters: {}, secretInput: "do-not-send"
    });
    expect(JSON.stringify(payload)).not.toContain("do-not-send");
    expect(payload).not.toHaveProperty("secretReference");
  });

  it("keeps the legacy rules-only response usable", () => {
    const { providerDescriptors: _descriptors, ...legacy } = configuration;
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={legacy} />);
    expect(html).toContain("Redsys TPV-PC");
    expect(html).not.toContain('disabled="">Probar conexión');
  });

  it("loads configuration with the bearer token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(configuration), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadPaymentTerminalConfiguration("token")).resolves.toEqual(configuration);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "GET" });
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toMatchObject({ Authorization: "Bearer token" });
    vi.unstubAllGlobals();
  });

  it.each<SimulatorOutcome>(["APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"])(
    "saves simulator outcome %s without secrets",
    async (outcome) => {
      const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(configuration), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      }));
      vi.stubGlobal("fetch", fetchMock);

      await savePaymentTerminalConfiguration("token", {
        cardMode: "INTEGRATED",
        provider: "REDSYS_TPV_PC",
        displayName: "Redsys caja",
        enabled: true,
        testMode: true,
        simulatorOutcome: outcome
      });

      const request = fetchMock.mock.calls[0][1] as RequestInit;
      expect(request.method).toBe("PATCH");
      expect(JSON.parse(String(request.body))).toEqual({
        cardMode: "INTEGRATED",
        provider: "REDSYS_TPV_PC",
        displayName: "Redsys caja",
        enabled: true,
        testMode: true,
        providerParameters: { simulatorOutcome: outcome }
      });
      vi.unstubAllGlobals();
    }
  );

  it("runs connection test without a client supplied result", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(configuration), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await testPaymentTerminalConnection("token");
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(request.method).toBe("POST");
    expect(request.body).toBeUndefined();
    vi.unstubAllGlobals();
  });

  it("normalizes MANUAL mode to provider NONE without simulator parameters or secret fields", () => {
    const integrated = {
      cardMode: "INTEGRATED",
      provider: "REDSYS_TPV_PC",
      displayName: " Caja ",
      enabled: true,
      testMode: true,
      simulatorOutcome: "APPROVED"
    } as const;
    const manual = changePaymentCardMode(integrated, "MANUAL", ["REDSYS_TPV_PC"]);

    expect(manual).toMatchObject({ cardMode: "MANUAL", provider: "NONE", testMode: false });
    expect(paymentTerminalUpdatePayload(manual)).toEqual({
      cardMode: "MANUAL",
      provider: "NONE",
      displayName: "Caja",
      enabled: true,
      testMode: false,
      providerParameters: {}
    });
    expect(paymentTerminalUpdatePayload(manual)).not.toHaveProperty("secretReference");
  });

  it("selects Redsys on INTEGRATED only when store rules allow it", () => {
    const manual = {
      cardMode: "MANUAL",
      provider: "NONE",
      displayName: "Caja",
      enabled: true,
      testMode: false,
      simulatorOutcome: "APPROVED"
    } as const;
    expect(changePaymentCardMode(manual, "INTEGRATED", ["REDSYS_TPV_PC"]).provider).toBe("REDSYS_TPV_PC");
    expect(changePaymentCardMode(manual, "INTEGRATED", []).provider).toBe("NONE");
  });

  it("enforces mode and provider rules and understands backend OK/ERROR statuses", () => {
    const form = {
      cardMode: "INTEGRATED",
      provider: "REDSYS_TPV_PC",
      displayName: "Caja",
      enabled: true,
      testMode: true,
      simulatorOutcome: "APPROVED"
    } as const;
    expect(paymentTerminalRulesError(form, { ...configuration.rules, integratedCardEnabled: false })).toBe("integratedDisabled");
    expect(paymentTerminalRulesError(form, { ...configuration.rules, allowedPaymentTerminalProviders: [] })).toBe("providerNotAllowed");
    expect(paymentTerminalRulesError({ ...form, cardMode: "MANUAL", provider: "NONE" }, { ...configuration.rules, cardManualEnabled: false })).toBe("manualDisabled");
    expect(connectionTestSucceeded("OK")).toBe(true);
    expect(connectionTestSucceeded("ERROR")).toBe(false);
  });

  it("renders a rules error and disables integrated mode when store rules prohibit it", () => {
    const html = renderToStaticMarkup(
      <PaymentTerminalSettings
        locale="es"
        initialConfiguration={{ ...configuration, rules: { ...configuration.rules, integratedCardEnabled: false } }}
      />
    );
    expect(html).toContain('value="INTEGRATED" disabled="" selected=""');
    expect(html).toContain("El cobro integrado está desactivado para esta tienda");
  });
});

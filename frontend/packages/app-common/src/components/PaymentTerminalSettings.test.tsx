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
  startPaymentTerminalPairing,
  loadPaymentTerminalPairingStatus,
  normalizeTerminalMode,
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
    fieldSchemas: [
      { key: "simulatorOutcome", label: "settings.paymentTerminal.outcome", type: "SELECT", required: false,
        modes: ["SIMULATED"], options: ["APPROVED", "DECLINED", "TIMEOUT", "CONNECTION_ERROR"] },
      { key: "simulatorQueryOutcome", label: "settings.paymentTerminal.queryOutcome", type: "SELECT", required: false,
        modes: ["SIMULATED"], options: ["APPROVED", "DECLINED", "TIMEOUT", "PENDING", "ERROR"] }
    ]
  })),
  rules: {
    cardManualEnabled: true,
    cardManualReferenceRequired: false,
    integratedCardEnabled: true,
    manualFallbackEnabled: true,
    allowedPaymentTerminalProviders: ["REDSYS_TPV_PC", "PAYTEF", "PAYCOMET", "GLOBAL_PAYMENTS"]
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
    expect(html).toContain("Resultado al consultar tras timeout");
    expect(html).not.toContain("settings.paymentTerminal.queryOutcome");
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

  it("restores the pairing identity from current configuration after reload", () => {
    const recovered = {
      ...configuration,
      configuration: {
        ...configuration.configuration,
        pairingId: "123e4567-e89b-12d3-a456-426614174000",
        pairingStatus: "PAIRED" as const
      }
    };
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={recovered} />);
    expect(html).toContain("Emparejado");
    expect(html).not.toContain('disabled="">Consultar emparejamiento');
  });

  it("never includes rehydrated secret values in an update payload", () => {
    const payload = paymentTerminalUpdatePayload({
      cardMode: "INTEGRATED", provider: "PAYTEF", displayName: "Caja", enabled: true,
      terminalMode: "SIMULATED", simulatorOutcome: "APPROVED", providerParameters: {}, secretInput: "do-not-send"
    });
    expect(JSON.stringify(payload)).not.toContain("do-not-send");
    expect(payload).not.toHaveProperty("secretReference");
  });

  it("sends only an opaque secret reference and its matching version", () => {
    const payload = paymentTerminalUpdatePayload({
      cardMode: "INTEGRATED", provider: "PAYTEF", displayName: "Caja", enabled: true,
      terminalMode: "LIVE", simulatorOutcome: "APPROVED", providerParameters: {},
      secretReference: "pts_0123456789abcdef0123456789abcdef", secretVersion: 3,
      secretInput: "must-never-leave-the-browser"
    });
    expect(payload).toMatchObject({ secretReference: "pts_0123456789abcdef0123456789abcdef", secretVersion: 3 });
    expect(JSON.stringify(payload)).not.toContain("must-never-leave-the-browser");
  });

  it("keeps the legacy rules-only response usable", () => {
    const { providerDescriptors: _descriptors, ...legacy } = configuration;
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={legacy} />);
    expect(html).toContain("Redsys TPV-PC");
    expect(html).not.toContain('disabled="">Probar conexión');
  });

  it("renders descriptor modes and generic non-sensitive fields without hardcoding", () => {
    const live = { ...configuration, providerDescriptors: [{
      provider: "REDSYS_TPV_PC" as const, displayName: "Redsys TPV-PC", supportedModes: ["LIVE" as const],
      liveAvailable: true, unavailableReason: null, capabilities: ["PAIRING", "CONNECTION_TEST"],
      fieldSchemas: [{ key: "ip", label: "IP del datáfono", type: "TEXT" as const, required: true,
        modes: ["LIVE" as const], options: [] }]
    }], configuration: { ...configuration.configuration, testMode: false, providerParameters: { ip: "10.0.0.2" } } };
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={live} />);
    expect(html).toContain("IP del datáfono");
    expect(html).toContain('required=""');
    expect(html).toContain('value="10.0.0.2"');
    expect(html).not.toContain('value="SIMULATED"');
  });

  it("intersects descriptors with allowed provider rules", () => {
    const inconsistent = { ...configuration, rules: { ...configuration.rules,
      allowedPaymentTerminalProviders: ["REDSYS_TPV_PC"] } };
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={inconsistent} />);
    expect(html).toContain("Redsys TPV-PC");
    expect(html).not.toContain('value="PAYTEF"');
  });

  it("starts and queries pairing with the pairing id", async () => {
    const result = { status: "APPROVED", code: "PAIRED", reference: "ref", message: "ok" };
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(result), {
      status: 200, headers: { "Content-Type": "application/json" }
    })));
    vi.stubGlobal("fetch", fetchMock);
    await startPaymentTerminalPairing("token", "123e4567-e89b-12d3-a456-426614174000");
    await loadPaymentTerminalPairingStatus("token", "123e4567-e89b-12d3-a456-426614174000");
    expect(fetchMock.mock.calls[0][0]).toContain("/pairing");
    expect(JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body))).toEqual({ pairingId: "123e4567-e89b-12d3-a456-426614174000" });
    expect(fetchMock.mock.calls[1][0]).toContain("/pairing/123e4567-e89b-12d3-a456-426614174000");
    vi.unstubAllGlobals();
  });

  it("sends only non-sensitive provider parameters in LIVE mode", () => {
    expect(paymentTerminalUpdatePayload({ cardMode: "INTEGRATED", provider: "REDSYS_TPV_PC",
      displayName: "Caja", enabled: true, terminalMode: "LIVE", simulatorOutcome: "DECLINED",
      providerParameters: { ip: "10.0.0.2", simulatorOutcome: "APPROVED", apiKey: "hidden" }, secretInput: "hidden" }))
      .toEqual({ cardMode: "INTEGRATED", provider: "REDSYS_TPV_PC", displayName: "Caja", enabled: true,
        testMode: false, providerParameters: { ip: "10.0.0.2" } });
  });

  it("renders simulatorOutcome through the generic SELECT field renderer", () => {
    const generic = { ...configuration, providerDescriptors: [{ ...configuration.providerDescriptors![0],
      fieldSchemas: [{ key: "simulatorOutcome", label: "Resultado configurable", type: "SELECT" as const,
        required: true, modes: ["SIMULATED" as const], options: ["APPROVED", "DECLINED"] }] }] };
    const html = renderToStaticMarkup(<PaymentTerminalSettings locale="es" initialConfiguration={generic} />);
    expect(html).toContain("Resultado configurable");
    expect(html).toContain('required=""');
    expect(html).toContain('value="DECLINED" selected=""');
  });

  it("normalizes an unsupported current mode to the descriptor first supported mode", () => {
    const descriptor = { ...configuration.providerDescriptors![0], supportedModes: ["LIVE" as const], liveAvailable: true };
    expect(normalizeTerminalMode("SIMULATED", descriptor)).toBe("LIVE");
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

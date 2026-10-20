import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import {
  getHardwareBridge,
  type CustomerDisplayState,
  type HardwareBridge,
  type HardwareConfig,
} from "../hardware/hardware";
import type { LocaleCode, TerminalContext } from "../types";
import {
  createCustomerDisplaySaleState,
  customerDisplayLastItemId,
  type CustomerDisplayCartLine,
  type CustomerDisplaySaleInput,
} from "./customerDisplaySale";

type Identity = NonNullable<CustomerDisplayState["identity"]>;
type DisplaySetup = { config: HardwareConfig; identity: Identity };

// A queue shared by bridge also orders cleanup against a newly mounted sale.
// Pending intermediate snapshots are replaced by the most recent snapshot.
export function createCustomerDisplayPublisher(update: HardwareBridge["updateCustomerDisplay"]) {
  let queued: { state: CustomerDisplayState; force: boolean } | null = null;
  let running = false;
  let lastKey = "";
  async function drain() {
    if (running) return;
    running = true;
    try {
      while (queued) {
        const { state, force } = queued;
        queued = null;
        const key = JSON.stringify(state);
        if (!force && key === lastKey) continue;
        try {
          const result = await update(state);
          if (result.ok) lastKey = key;
        } catch {
          // The customer display must never interrupt an operator's sale.
        }
      }
    } finally {
      running = false;
    }
  }
  return (state: CustomerDisplayState, force = false) => {
    queued = { state, force: force || Boolean(queued?.force) };
    void drain();
  };
}

const publishers = new WeakMap<HardwareBridge, ReturnType<typeof createCustomerDisplayPublisher>>();
function publisherFor(hardware: HardwareBridge) {
  let publisher = publishers.get(hardware);
  if (!publisher) {
    publisher = createCustomerDisplayPublisher(state => hardware.updateCustomerDisplay(state));
    publishers.set(hardware, publisher);
  }
  return publisher;
}

function fallbackIdentity(terminal: TerminalContext): Identity {
  return {
    storeName: terminal.storeName,
    companyName: terminal.companyName,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    currency: "EUR",
  };
}

function withPresentation(
  state: CustomerDisplayState, setup: DisplaySetup, locale: LocaleCode,
): CustomerDisplayState {
  let formatter: Intl.NumberFormat;
  try {
    formatter = new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale, {
      style: "currency", currency: setup.identity.currency || "EUR",
    });
  } catch {
    formatter = new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" });
  }
  const currentItem = state.sale?.items.find(item => item.id === state.sale?.lastItemId);
  const idle = state.phase === "IDLE";
  const total = state.payment?.total ?? state.sale?.total;
  return {
    ...state, locale, identity: setup.identity,
    line1: idle ? setup.config.customerDisplayIdleLine1
      : currentItem?.name ?? (locale === "es" ? "TOTAL" : locale === "zh" ? "合计" : "TOTAL"),
    line2: idle ? setup.config.customerDisplayIdleLine2 : total == null ? "" : formatter.format(total),
  };
}

export function useCustomerDisplaySale({
  enabled, token, terminal, locale, ...input
}: CustomerDisplaySaleInput & {
  enabled: boolean;
  token?: string;
  terminal: TerminalContext;
  locale: LocaleCode;
}) {
  const hardware = getHardwareBridge();
  const [setup, setSetup] = useState<DisplaySetup | null>(null);
  const [configurationRevision, setConfigurationRevision] = useState(0);
  const publishedSetup = useRef<DisplaySetup | null>(null);
  const previousLines = useRef<CustomerDisplayCartLine[]>([]);
  const lastItemId = useRef<string | undefined>(undefined);
  const cleanupState = useRef<CustomerDisplayState | null>(null);
  const terminalKey = JSON.stringify(terminal);
  const inputKey = JSON.stringify(input);

  useEffect(() => {
    if (!enabled) return;
    const reload = () => setConfigurationRevision(revision => revision + 1);
    window.addEventListener("tpv:hardware-config-changed", reload);
    return () => window.removeEventListener("tpv:hardware-config-changed", reload);
  }, [enabled]);

  useEffect(() => {
    if (!enabled || typeof hardware.getHardwareConfig !== "function"
      || typeof hardware.updateCustomerDisplay !== "function") return;
    let active = true;
    const abort = new AbortController();
    setSetup(null);
    void Promise.resolve().then(() => hardware.getHardwareConfig()).then(async config => {
      if (!active || !config.customerDisplayEnabled) return;
      const fallback = fallbackIdentity(terminal);
      // Identity enrichment must not hold up sale updates when the endpoint is slow.
      setSetup({ config, identity: fallback });
      let identity = fallback;
      try {
        const loaded = await apiRequest<Identity>("/hardware/customer-display-identity", {
          token, signal: abort.signal,
        });
        identity = { ...fallback, ...loaded };
      } catch { /* Keep terminal identity when the backend is temporarily unavailable. */ }
      if (active) setSetup({ config, identity });
    }).catch(() => { /* Hardware setup is optional for completing a sale. */ });
    return () => {
      active = false;
      abort.abort();
      if (cleanupState.current) publisherFor(hardware)(cleanupState.current);
      cleanupState.current = null;
    };
  }, [enabled, hardware, token, terminalKey, configurationRevision]);

  useEffect(() => {
    if (!enabled || !setup) return;
    const snapshot = JSON.parse(inputKey) as CustomerDisplaySaleInput;
    lastItemId.current = customerDisplayLastItemId(previousLines.current, snapshot.lines, lastItemId.current);
    previousLines.current = snapshot.lines;
    const state = createCustomerDisplaySaleState({ ...snapshot, lastItemId: lastItemId.current });
    cleanupState.current = withPresentation({ line1: "", line2: "", phase: "IDLE" }, setup, locale);
    // The controller may have reset the monitor while applying configuration.
    // Every new setup must restore the full identity and current snapshot even
    // when an identical payload was delivered before that reset.
    const force = publishedSetup.current !== setup;
    publishedSetup.current = setup;
    publisherFor(hardware)(withPresentation(state, setup, locale), force);
  }, [enabled, setup, inputKey, hardware, locale]);
}

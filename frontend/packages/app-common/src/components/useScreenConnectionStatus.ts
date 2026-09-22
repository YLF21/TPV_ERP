import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";

export const CONNECTION_REFRESH_MS = 30_000;
export const CONNECTION_TIMEOUT_MS = 8_000;

export function useScreenConnectionStatus({ includeBackendAddress = true } = {}) {
  const [saasConnected, setSaasConnected] = useState(false);
  const [backendLabel, setBackendLabel] = useState<string | null>(null);
  const [checkingSaas, setCheckingSaas] = useState(false);
  const checkSaasRef = useRef<(() => Promise<void>) | null>(null);
  const refreshSaas = useCallback(() => { void checkSaasRef.current?.(); }, []);

  useEffect(() => {
    let disposed = false;
    let saasRequest: AbortController | null = null;
    let addressRequest: AbortController | null = null;

    async function checkSaas() {
      if (saasRequest) return;
      const controller = new AbortController();
      saasRequest = controller;
      setCheckingSaas(true);
      const timeout = window.setTimeout(() => controller.abort(), CONNECTION_TIMEOUT_MS);
      try {
        const result = await apiRequest<{ saasConnected: boolean }>("/connectivity", {
          signal: controller.signal
        });
        if (!disposed && saasRequest === controller && !controller.signal.aborted) {
          setSaasConnected(result?.saasConnected === true);
        }
      } catch {
        if (!disposed && saasRequest === controller) setSaasConnected(false);
      } finally {
        window.clearTimeout(timeout);
        if (saasRequest === controller) {
          saasRequest = null;
          if (!disposed) setCheckingSaas(false);
        }
      }
    }

    async function checkAddress() {
      if (addressRequest) return;
      const controller = new AbortController();
      addressRequest = controller;
      const timeout = window.setTimeout(() => controller.abort(), CONNECTION_TIMEOUT_MS);
      try {
        // The proxy knows its actual backend, including Electron's runtime configuration.
        const response = await fetch("/__tpv/backend-address", { cache: "no-store", signal: controller.signal });
        if (!response.ok) throw new Error("backend_address_unavailable");
        const result = await response.json() as { backendLabel?: unknown };
        if (!disposed && !controller.signal.aborted) {
          setBackendLabel(typeof result?.backendLabel === "string" && result.backendLabel ? result.backendLabel : null);
        }
      } catch {
        if (!disposed) setBackendLabel(null);
      } finally {
        window.clearTimeout(timeout);
        if (addressRequest === controller) addressRequest = null;
      }
    }

    function refresh() {
      void checkSaas();
      if (includeBackendAddress) void checkAddress();
    }
    function offline() {
      saasRequest?.abort();
      saasRequest = null;
      setSaasConnected(false);
      setCheckingSaas(false);
    }
    function visible() {
      if (document.visibilityState === "visible") refresh();
    }

    checkSaasRef.current = checkSaas;
    refresh();
    const interval = window.setInterval(refresh, CONNECTION_REFRESH_MS);
    window.addEventListener("online", refresh);
    window.addEventListener("offline", offline);
    document.addEventListener("visibilitychange", visible);
    return () => {
      disposed = true;
      checkSaasRef.current = null;
      window.clearInterval(interval);
      saasRequest?.abort();
      addressRequest?.abort();
      window.removeEventListener("online", refresh);
      window.removeEventListener("offline", offline);
      document.removeEventListener("visibilitychange", visible);
    };
  }, [includeBackendAddress]);

  return { saasConnected, backendLabel, checkingSaas, refreshSaas };
}

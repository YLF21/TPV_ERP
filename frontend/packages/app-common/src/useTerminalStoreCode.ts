import { useEffect, useRef, type Dispatch, type SetStateAction } from "react";
import type { TerminalContext } from "./types";
import { validStoreInternalCode } from "./storeInternalCode";

export const STORE_CODE_REFRESH_MS = 60_000;

type TerminalContextSetter = Dispatch<SetStateAction<TerminalContext | null | undefined>>;
type Binding = Pick<TerminalContext, "installationId" | "terminalId" | "terminalCredential" | "bindingId">;

function sameBinding(left: Binding, right: Binding): boolean {
  return left.installationId === right.installationId
    && left.terminalId === right.terminalId
    && left.terminalCredential === right.terminalCredential
    && left.bindingId === right.bindingId;
}

export function useTerminalStoreCode(
  terminalContext: TerminalContext | null | undefined,
  setTerminalContext: TerminalContextSetter
): void {
  const inFlight = useRef(false);
  const installationId = terminalContext?.installationId;
  const terminalId = terminalContext?.terminalId;
  const terminalCredential = terminalContext?.terminalCredential;
  const bindingId = terminalContext?.bindingId;
  const storeInternalCode = terminalContext?.storeInternalCode;

  useEffect(() => {
    const bridge = window.tpvDesktop?.terminalIdentity;
    if (!bridge || !installationId || !terminalId || !terminalCredential
      || validStoreInternalCode(storeInternalCode)) return;

    const captured: Binding = { installationId, terminalId, terminalCredential, bindingId };
    let active = true;
    const timer = window.setInterval(async () => {
      if (!active || inFlight.current) return;
      inFlight.current = true;
      try {
        const result = await bridge.load();
        if (!active || !result.ok || !result.identity || !sameBinding(captured, result.identity)) return;
        const code = validStoreInternalCode(result.identity.storeInternalCode);
        if (!code) return;
        setTerminalContext((current) => {
          if (!active || !current || !sameBinding(captured, current)
            || validStoreInternalCode(current.storeInternalCode)) return current;
          return { ...current, storeInternalCode: code };
        });
      } catch {
        // Keep the current identity; a later interval can retry.
      } finally {
        inFlight.current = false;
      }
    }, STORE_CODE_REFRESH_MS);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [installationId, terminalId, terminalCredential, bindingId, storeInternalCode, setTerminalContext]);
}

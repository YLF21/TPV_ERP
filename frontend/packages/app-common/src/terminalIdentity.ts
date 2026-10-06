import type { TerminalContext } from "./types";

export type TerminalIdentityLoadResult = {
  ok: boolean;
  identity?: TerminalContext | null;
  connectionUnavailable?: boolean;
  displayContext?: Pick<TerminalContext, "companyName" | "storeName" | "terminalCode" | "terminalName"> | null;
};

// Cached names are presentation data. They never become a verified identity.
export async function loadTerminalLoginContext(
  bridge: TerminalIdentityBridge | undefined,
  browserDevelopmentFallback: TerminalContext | null
): Promise<{ identity: TerminalContext | null; offlineContext: TerminalContext | null }> {
  try {
    const result = bridge
      ? await bridge.load()
      : { ok: true, identity: browserDevelopmentFallback };
    const identity = resolveTerminalIdentity(result);
    const display = result.displayContext;
    const offlineContext = !identity && result.ok && result.connectionUnavailable
      && display && hasText(display.storeName) && hasText(display.terminalCode)
      ? {
          storeName: display.storeName,
          terminalCode: display.terminalCode,
          companyName: display.companyName,
          terminalName: display.terminalName
        }
      : null;
    return { identity, offlineContext };
  } catch {
    return { identity: null, offlineContext: null };
  }
}

export type TerminalIdentityBridge = {
  load: () => Promise<TerminalIdentityLoadResult>;
};

function hasText(value: string | undefined): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

export function resolveTerminalIdentity(
  result: TerminalIdentityLoadResult
): TerminalContext | null {
  const identity = result.ok ? result.identity : null;
  if (
    !identity
    || !hasText(identity.storeName)
    || !hasText(identity.terminalCode)
    || !hasText(identity.terminalId)
    || !hasText(identity.terminalCredential)
  ) {
    return null;
  }

  return identity;
}

export async function loadTerminalIdentity(
  bridge: TerminalIdentityBridge | undefined,
  browserDevelopmentFallback: TerminalContext | null
): Promise<TerminalContext | null> {
  if (!bridge) {
    return resolveTerminalIdentity({ ok: true, identity: browserDevelopmentFallback });
  }

  try {
    return resolveTerminalIdentity(await bridge.load());
  } catch {
    return null;
  }
}

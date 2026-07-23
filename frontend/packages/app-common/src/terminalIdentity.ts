import type { TerminalContext } from "./types";

export type TerminalIdentityLoadResult = {
  ok: boolean;
  identity?: TerminalContext | null;
};

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

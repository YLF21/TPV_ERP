import type { TerminalContext } from "@tpverp/app-common";

type TerminalIdentityLoadResult = {
  ok: boolean;
  identity?: TerminalContext | null;
};

export function resolveGestionTerminalIdentity(
  result: TerminalIdentityLoadResult
): TerminalContext | null {
  if (result.ok && result.identity) return result.identity;
  return null;
}

import { devTerminalContext, type TerminalContext } from "@tpverp/app-common";

type TerminalIdentityLoadResult = {
  ok: boolean;
  identity?: TerminalContext | null;
};

export function resolveGestionTerminalIdentity(
  result: TerminalIdentityLoadResult,
  development: boolean
): TerminalContext | null {
  if (result.ok && result.identity) return result.identity;
  return development ? devTerminalContext : null;
}

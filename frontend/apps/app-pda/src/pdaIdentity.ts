import type { TerminalContext } from "../../../packages/app-common/src/types";
import { clearPdaDeviceData } from "./PdaWorkQueue";

export const PDA_IDENTITY_STORAGE_KEY = "tpverp.pda.identity.v1";
export const PDA_DISABLED_IDENTITY_STORAGE_KEY = "tpverp.pda.disabledIdentity.v1";

export type PdaIdentity = TerminalContext & {
  pendingApproval: boolean;
};

export type QuarantinedPdaIdentity = Omit<PdaIdentity, "terminalCredential"> & {
  quarantinedAt: string;
  reason: "terminal-disabled";
};
export type RotatedPdaCredential = { terminalId: string; terminalCredential: string; rotatedAt: string };

type IdentityStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function validText(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

export function readPdaIdentity(storage: IdentityStorage): PdaIdentity | null {
  try {
    const value = JSON.parse(storage.getItem(PDA_IDENTITY_STORAGE_KEY) ?? "null") as Partial<PdaIdentity> | null;
    if (!value
      || !validText(value.storeName)
      || !validText(value.terminalCode)
      || !validText(value.terminalId)
      || !validText(value.terminalCredential)) {
      return null;
    }
    return {
      storeName: value.storeName.trim(),
      terminalCode: value.terminalCode.trim(),
      terminalId: value.terminalId.trim(),
      terminalCredential: value.terminalCredential.trim(),
      pendingApproval: value.pendingApproval !== false
    };
  } catch {
    return null;
  }
}

export function writePdaIdentity(storage: IdentityStorage, identity: PdaIdentity) {
  // A browser-only PDA must retain this credential across restarts. Encrypting it
  // with a key stored by the same origin would not protect it from same-origin
  // script execution; CSP in index.html is therefore the primary browser
  // mitigation. Native deployments should replace this store with an OS-backed
  // secret bridge, and the server remains responsible for rotation/revocation.
  storage.setItem(PDA_IDENTITY_STORAGE_KEY, JSON.stringify(identity));
}

export function applyRotatedPdaCredential(storage: IdentityStorage, current: PdaIdentity, result: RotatedPdaCredential) {
  if (!validText(result.terminalId) || result.terminalId !== current.terminalId || !validText(result.terminalCredential)) {
    throw new Error("invalid_pda_credential_rotation");
  }
  const rotated = { ...current, terminalCredential: result.terminalCredential.trim(), pendingApproval: false };
  writePdaIdentity(storage, rotated);
  return rotated;
}

export function clearPdaIdentity(storage: IdentityStorage) {
  if (storage.getItem(PDA_IDENTITY_STORAGE_KEY)) storage.setItem(PDA_IDENTITY_STORAGE_KEY, "");
  storage.removeItem(PDA_IDENTITY_STORAGE_KEY);
  if (typeof window !== "undefined" && storage === window.localStorage) clearPdaDeviceData();
}

export function quarantineDisabledPdaIdentity(storage: IdentityStorage) {
  const current = readPdaIdentity(storage);
  if (current) {
    const { terminalCredential: _discardedCredential, ...metadata } = current;
    const quarantined: QuarantinedPdaIdentity = {
      ...metadata,
      quarantinedAt: new Date().toISOString(),
      reason: "terminal-disabled"
    };
    storage.setItem(PDA_DISABLED_IDENTITY_STORAGE_KEY, JSON.stringify(quarantined));
  }
  storage.removeItem(PDA_IDENTITY_STORAGE_KEY);
}

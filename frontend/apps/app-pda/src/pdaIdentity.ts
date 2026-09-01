import type { TerminalContext } from "@tpverp/app-common";
import { clearPdaDeviceData } from "./PdaWorkQueue";

export const PDA_IDENTITY_STORAGE_KEY = "tpverp.pda.identity.v1";
export const PDA_DISABLED_IDENTITY_STORAGE_KEY = "tpverp.pda.disabledIdentity.v1";

export type PdaIdentity = TerminalContext & {
  pendingApproval: boolean;
};

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
  storage.setItem(PDA_IDENTITY_STORAGE_KEY, JSON.stringify(identity));
}

export function clearPdaIdentity(storage: IdentityStorage) {
  if (storage.getItem(PDA_IDENTITY_STORAGE_KEY)) storage.setItem(PDA_IDENTITY_STORAGE_KEY, "");
  storage.removeItem(PDA_IDENTITY_STORAGE_KEY);
  if (typeof window !== "undefined" && storage === window.localStorage) clearPdaDeviceData();
}

export function quarantineDisabledPdaIdentity(storage: IdentityStorage) {
  const current = storage.getItem(PDA_IDENTITY_STORAGE_KEY);
  if (current) storage.setItem(PDA_DISABLED_IDENTITY_STORAGE_KEY, current);
  storage.removeItem(PDA_IDENTITY_STORAGE_KEY);
}

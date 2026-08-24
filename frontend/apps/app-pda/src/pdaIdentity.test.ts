import { describe, expect, it } from "vitest";
import { clearPdaIdentity, PDA_IDENTITY_STORAGE_KEY, readPdaIdentity, writePdaIdentity } from "./pdaIdentity";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); }
  };
}

describe("pdaIdentity", () => {
  it("persists and restores a complete pending identity", () => {
    const storage = memoryStorage();
    writePdaIdentity(storage, {
      storeName: "Tienda",
      terminalCode: "PDA 1",
      terminalId: "terminal-1",
      terminalCredential: "secret",
      pendingApproval: true
    });

    expect(readPdaIdentity(storage)).toEqual({
      storeName: "Tienda",
      terminalCode: "PDA 1",
      terminalId: "terminal-1",
      terminalCredential: "secret",
      pendingApproval: true
    });
    expect(storage.getItem(PDA_IDENTITY_STORAGE_KEY)).toContain("terminal-1");
  });

  it("rejects incomplete data and can forget a device", () => {
    const storage = memoryStorage();
    storage.setItem(PDA_IDENTITY_STORAGE_KEY, JSON.stringify({ terminalId: "terminal-1" }));
    expect(readPdaIdentity(storage)).toBeNull();
    clearPdaIdentity(storage);
    expect(storage.getItem(PDA_IDENTITY_STORAGE_KEY)).toBeNull();
  });
});

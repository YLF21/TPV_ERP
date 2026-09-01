// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { flushPdaDurableWrites, setPdaDurableStorageForTests, type PdaDurableStorage } from "./PdaDurableStorage";
import { PDA_LAST_SYNC_KEY, PDA_WORK_DRAFT_KEY, writePdaWorkQueue } from "./PdaWorkQueue";
import {
  clearPdaIdentity,
  PDA_DISABLED_IDENTITY_STORAGE_KEY,
  PDA_IDENTITY_STORAGE_KEY,
  quarantineDisabledPdaIdentity,
  readPdaIdentity,
  writePdaIdentity
} from "./pdaIdentity";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); }
  };
}

describe("pdaIdentity", () => {
  const durableValues = new Map<string, unknown>();
  beforeEach(() => {
    localStorage.clear(); durableValues.clear();
    const storage: PdaDurableStorage = {
      async read<T>(key: string) { return durableValues.get(key) as T | undefined; },
      async write<T>(key: string, value: T) { durableValues.set(key, value); },
      async remove(key: string) { durableValues.delete(key); },
      async clear() { durableValues.clear(); }
    };
    setPdaDurableStorageForTests(storage);
  });
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

  it("removes offline data and credentials when unlinking the browser device", async () => {
    writePdaIdentity(localStorage, { storeName: "Tienda", terminalCode: "PDA 1", terminalId: "t1", terminalCredential: "secret", pendingApproval: false });
    writePdaWorkQueue([{ id: "queued", createdAt: "x" }]);
    localStorage.setItem(PDA_WORK_DRAFT_KEY, JSON.stringify({ title: "draft" }));
    localStorage.setItem(PDA_LAST_SYNC_KEY, "2026-08-31T10:00:00Z");
    await flushPdaDurableWrites();
    clearPdaIdentity(localStorage);
    await flushPdaDurableWrites();
    expect(localStorage.getItem(PDA_IDENTITY_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(PDA_WORK_DRAFT_KEY)).toBeNull();
    expect(localStorage.getItem(PDA_LAST_SYNC_KEY)).toBeNull();
    expect(durableValues.size).toBe(0);
  });

  it("quarantines a disabled identity without deleting unsynchronized PDA work", async () => {
    writePdaIdentity(localStorage, { storeName: "Tienda", terminalCode: "PDA 1", terminalId: "t1", terminalCredential: "secret", pendingApproval: false });
    writePdaWorkQueue([{ id: "queued", createdAt: "x" }]);
    localStorage.setItem(PDA_WORK_DRAFT_KEY, JSON.stringify({ title: "draft" }));
    await flushPdaDurableWrites();

    quarantineDisabledPdaIdentity(localStorage);

    expect(localStorage.getItem(PDA_IDENTITY_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(PDA_DISABLED_IDENTITY_STORAGE_KEY)).toContain("t1");
    expect(localStorage.getItem(PDA_WORK_DRAFT_KEY)).toContain("draft");
    expect(durableValues.size).toBeGreaterThan(0);
  });
});

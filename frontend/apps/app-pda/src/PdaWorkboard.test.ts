// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { setPdaDurableStorageForTests, type PdaDurableStorage } from "./PdaDurableStorage";
import { resetPdaWorkQueueMemoryForTests } from "./PdaWorkQueue";
import { readPdaWorkQueue, writePdaWorkQueue } from "./PdaWorkboard";

describe("PdaWorkboard offline queue", () => {
  beforeEach(() => {
    const values = new Map<string, unknown>();
    const storage: PdaDurableStorage = {
      async read<T>(key: string) { return values.get(key) as T | undefined; },
      async write<T>(key: string, value: T) { values.set(key, value); },
      async remove(key: string) { values.delete(key); },
      async clear() { values.clear(); }
    };
    localStorage.clear();
    resetPdaWorkQueueMemoryForTests();
    setPdaDurableStorageForTests(storage);
  });
  it("persists and restores pending operations", () => {
    const values = [{ id: "queue-1", createdAt: "2026-08-25T10:00:00Z", payload: {
      type: "TASK" as const, title: "Reponer lineal", reference: "", productCode: "",
      warehouseId: null, quantity: null, lotNumber: "", expiryDate: null, location: "",
      priority: "HIGH", notes: "Pasillo 2", evidenceName: null, evidenceType: null, evidenceData: null
    }}];
    writePdaWorkQueue(values);
    expect(readPdaWorkQueue()).toEqual([expect.objectContaining(values[0])]);
  });
  it("returns an empty queue when storage is invalid", () => {
    localStorage.setItem("tpverp.pda.work.queue.v1", "invalid");
    expect(readPdaWorkQueue()).toEqual([]);
  });
});

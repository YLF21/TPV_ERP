// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import {
  flushPdaDurableWrites, queuePdaDurableWrite, readPdaDurableValue,
  setPdaDurableStorageForTests, type PdaDurableStorage
} from "./PdaDurableStorage";

function memoryDurableStorage(onWrite?: () => void) {
  const values = new Map<string, unknown>();
  const storage: PdaDurableStorage = {
    async read<T>(key: string) { return values.get(key) as T | undefined; },
    async write<T>(key: string, value: T) { onWrite?.(); values.set(key, value); },
    async remove(key: string) { values.delete(key); },
    async clear() { values.clear(); }
  };
  return { storage, values };
}

describe("PdaDurableStorage", () => {
  beforeEach(() => setPdaDurableStorageForTests(memoryDurableStorage().storage));

  it("serializes writes so the latest value wins", async () => {
    const memory = memoryDurableStorage();
    setPdaDurableStorageForTests(memory.storage);
    void queuePdaDurableWrite("queue", [{ id: "first" }]);
    void queuePdaDurableWrite("queue", [{ id: "latest" }]);
    await flushPdaDurableWrites();
    expect(await readPdaDurableValue("queue")).toEqual([{ id: "latest" }]);
  });

  it("keeps later writes working after a storage failure", async () => {
    let writes = 0;
    const memory = memoryDurableStorage(() => {
      writes += 1;
      if (writes === 1) throw new Error("quota");
    });
    setPdaDurableStorageForTests(memory.storage);
    await queuePdaDurableWrite("queue", ["failed"]);
    await queuePdaDurableWrite("queue", ["recovered"]);
    expect(await readPdaDurableValue("queue")).toEqual(["recovered"]);
  });
});

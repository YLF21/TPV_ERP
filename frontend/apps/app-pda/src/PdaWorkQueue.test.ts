// @vitest-environment jsdom
import { ApiConnectionError, ApiError } from "@tpverp/app-common";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPdaDurableWrites, setPdaDurableStorageForTests, type PdaDurableStorage } from "./PdaDurableStorage";
import { classifyQueueError, hydratePdaWorkQueue, isAssignedToCurrentUser, nextQueueWakeup, PDA_DURABLE_QUEUE_KEY, PDA_WORK_QUEUE_KEY, queueRetryDelay, queuedActionPath, readPdaWorkQueue, resetPdaWorkQueueMemoryForTests, sendQueuedWork, writePdaWorkQueue } from "./PdaWorkQueue";

function memoryDurableStorage() {
  const values = new Map<string, unknown>();
  const storage: PdaDurableStorage = {
    async read<T>(key: string) { return values.get(key) as T | undefined; },
    async write<T>(key: string, value: T) { values.set(key, value); },
    async remove(key: string) { values.delete(key); },
    async clear() { values.clear(); }
  };
  return { storage, values };
}

describe("PdaWorkQueue", () => {
  beforeEach(() => { localStorage.clear(); resetPdaWorkQueueMemoryForTests(); setPdaDurableStorageForTests(memoryDurableStorage().storage); });
  it("migrates legacy create entries", () => {
    localStorage.setItem("tpverp.pda.work.queue.v1", JSON.stringify([{ id: "1", createdAt: "2026-08-28T10:00:00Z", payload: { title: "Task" } }]));
    expect(readPdaWorkQueue()[0]).toMatchObject({ action: "create", attempts: 0, state: "pending" });
  });
  it("builds encoded action paths", () => {
    expect(queuedActionPath({ id: "1", createdAt: "x", action: "finish", workId: "a/b", attempts: 0, state: "pending" })).toBe("/pda-work/a%2Fb/finish");
  });
  it("uses bounded exponential backoff", () => {
    expect(queueRetryDelay(1)).toBe(2_000); expect(queueRetryDelay(4)).toBe(16_000); expect(queueRetryDelay(20)).toBe(60_000);
  });
  it("ignores conflicts when calculating the next wakeup", () => {
    const now = Date.parse("2026-08-28T10:00:00Z");
    expect(nextQueueWakeup([{ id: "1", createdAt: "x", action: "create", attempts: 1, state: "conflict" }, { id: "2", createdAt: "x", action: "cancel", workId: "w", attempts: 1, state: "pending", nextRetryAt: "2026-08-28T10:00:05Z" }], now)).toBe(5_000);
  });
  it("filters assigned tasks with the authenticated user id and never decodes the opaque token", () => {
    expect(isAssignedToCurrentUser({ assignedTo: "user-1" }, "USER-1")).toBe(true);
    expect(isAssignedToCurrentUser({ assignedUserId: "user-2" }, "user-1")).toBe(false);
    expect(isAssignedToCurrentUser({ assignedUserId: "user-2" }, undefined)).toBe(true);
    expect(isAssignedToCurrentUser({}, "user-1")).toBe(true);
  });
  it("persists normalized queue data", () => {
    writePdaWorkQueue([{ id: "1", createdAt: "x", payload: { title: "Task" } }]); expect(readPdaWorkQueue()[0]).toMatchObject({ action: "create", state: "pending" });
  });
  it("restores the queue from durable storage after local storage is lost", async () => {
    const memory = memoryDurableStorage();
    setPdaDurableStorageForTests(memory.storage);
    writePdaWorkQueue([{ id: "durable", createdAt: "x", action: "cancel", workId: "w" }]);
    await flushPdaDurableWrites();
    localStorage.clear();
    expect(await hydratePdaWorkQueue()).toEqual([expect.objectContaining({ id: "durable", action: "cancel" })]);
    expect(readPdaWorkQueue()[0]?.id).toBe("durable");
  });
  it("migrates a local queue into durable storage", async () => {
    const memory = memoryDurableStorage();
    setPdaDurableStorageForTests(memory.storage);
    localStorage.setItem("tpverp.pda.work.queue.v1", JSON.stringify([{ id: "legacy", createdAt: "x" }]));
    await hydratePdaWorkQueue();
    localStorage.clear();
    expect((await hydratePdaWorkQueue())[0]).toMatchObject({ id: "legacy", action: "create" });
  });

  it("keeps the newer local revision instead of restoring stale IndexedDB data", async () => {
    const memory = memoryDurableStorage();
    memory.values.set(PDA_DURABLE_QUEUE_KEY, { revision: 1, updatedAt: "2026-08-28T10:00:00Z", entries: [{ id: "old", createdAt: "2026-08-28T10:00:00Z" }] });
    localStorage.setItem(PDA_WORK_QUEUE_KEY, JSON.stringify({ revision: 2, updatedAt: "2026-08-28T11:00:00Z", entries: [{ id: "new", createdAt: "2026-08-28T11:00:00Z" }] }));
    setPdaDurableStorageForTests(memory.storage);

    expect(await hydratePdaWorkQueue()).toEqual([expect.objectContaining({ id: "new" })]);
    expect(memory.values.get(PDA_DURABLE_QUEUE_KEY)).toMatchObject({ revision: 2, entries: [expect.objectContaining({ id: "new" })] });
  });

  it("merges equal revisions defensively so an interrupted copy does not lose actions", async () => {
    const memory = memoryDurableStorage();
    memory.values.set(PDA_DURABLE_QUEUE_KEY, { revision: 4, updatedAt: "2026-08-28T10:00:00Z", entries: [{ id: "durable", createdAt: "2026-08-28T10:00:00Z" }] });
    localStorage.setItem(PDA_WORK_QUEUE_KEY, JSON.stringify({ revision: 4, updatedAt: "2026-08-28T10:00:00Z", entries: [{ id: "local", createdAt: "2026-08-28T10:00:00Z" }] }));
    setPdaDurableStorageForTests(memory.storage);
    expect((await hydratePdaWorkQueue()).map((entry) => entry.id).sort()).toEqual(["durable", "local"]);
  });

  it("persists a stable idempotency key for create actions", () => {
    writePdaWorkQueue([{ id: "request-1", createdAt: "2026-08-28T10:00:00Z", payload: { title: "Task" } }]);
    expect(readPdaWorkQueue()[0]).toMatchObject({ clientRequestId: "request-1" });
  });

  it("sends the persisted idempotency key on every replay", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ id: "work-1" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const entry = { id: "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", clientRequestId: "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", createdAt: "2026-08-28T10:00:00Z", action: "create" as const, payload: { title: "Task" }, attempts: 1, state: "retrying" as const };
    await sendQueuedWork(entry, "opaque-token");
    await sendQueuedWork(entry, "opaque-token");
    const bodies = fetchMock.mock.calls.map((call) => JSON.parse(String(call[1]?.body)));
    expect(bodies).toEqual([
      expect.objectContaining({ clientRequestId: entry.clientRequestId }),
      expect.objectContaining({ clientRequestId: entry.clientRequestId })
    ]);
    vi.unstubAllGlobals();
  });

  it("classifies retryable, conflict and permanent HTTP failures", () => {
    expect(classifyQueueError(new ApiConnectionError())).toBe("offline");
    expect(classifyQueueError(new ApiError("bad", 400))).toBe("validation");
    expect(classifyQueueError(new ApiError("auth", 401))).toBe("authentication");
    expect(classifyQueueError(new ApiError("forbidden", 403))).toBe("forbidden");
    expect(classifyQueueError(new ApiError("missing", 404))).toBe("not-found");
    expect(classifyQueueError(new ApiError("conflict", 409))).toBe("conflict");
    expect(classifyQueueError(new ApiError("precondition", 412))).toBe("conflict");
    expect(classifyQueueError(new ApiError("server", 503))).toBe("server");
  });

  it("continues writing the durable queue and emits an error when localStorage quota is exceeded", async () => {
    const memory = memoryDurableStorage();
    setPdaDurableStorageForTests(memory.storage);
    const errors: unknown[] = [];
    window.addEventListener("pda-durable-storage-error", (event) => errors.push((event as CustomEvent).detail));
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("full", "QuotaExceededError"); });
    writePdaWorkQueue([{ id: "durable-only", createdAt: "2026-08-28T10:00:00Z" }]);
    await flushPdaDurableWrites();
    setItem.mockRestore();
    expect(memory.values.get(PDA_DURABLE_QUEUE_KEY)).toMatchObject({ entries: [expect.objectContaining({ id: "durable-only" })] });
    expect(errors).toContainEqual(expect.objectContaining({ operation: "local-write", quotaExceeded: true }));
  });
});

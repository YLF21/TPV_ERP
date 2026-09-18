import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { SaleControlDelivery, type SaleControlDraft } from "./saleControlDelivery";
import { sameSaleControlContext, type SaleControlContext, type SaleControlEvent, type SaleControlStorage } from "./saleControlOutboxStorage";

const uuid = (suffix: number) => `00000000-0000-4000-8000-${String(suffix).padStart(12, "0")}`;
const context: SaleControlContext = { storeId: uuid(1), terminalId: uuid(2), userId: uuid(3) };
const serverTime = "2026-09-17T10:00:00.000Z";
const draft = (id = 5): SaleControlDraft => ({
  saleOperationId: uuid(4), deletionOperationId: uuid(id), fullTicketClear: false,
  lines: [{ productId: uuid(10), code: "ITEM-1", name: "Item", quantity: 1, unitPrice: 10 }],
});
function memoryStorage() {
  const events = new Map<string, SaleControlEvent>();
  const storage: SaleControlStorage = {
    list: vi.fn(async current => structuredClone([...events.values()].filter(event => sameSaleControlContext(event.context, current)))),
    put: vi.fn(async event => { events.set(event.deletionOperationId, structuredClone(event)); }),
    remove: vi.fn(async (_context, id) => { events.delete(id); }),
  };
  return { storage, events };
}
const controllers: SaleControlDelivery[] = [];
function fixture(options: { store?: ReturnType<typeof memoryStorage>; token?: string; identity?: SaleControlContext; elapsed?: () => number } = {}) {
  const store = options.store ?? memoryStorage();
  const identity = options.identity ?? context;
  const send = vi.fn().mockResolvedValue([]);
  const request = vi.fn(async (path: string, args?: { body?: unknown }) => path.endsWith("/context")
    ? { ...identity, serverTime } : send(args?.body));
  const delivery = new SaleControlDelivery({ token: options.token ?? "ephemeral-token", userId: identity.userId,
    terminalId: identity.terminalId, storage: store.storage, request: request as never, monotonicNow: options.elapsed ?? (() => 0) });
  controllers.push(delivery);
  return { ...store, send, request, delivery };
}
async function settled() { for (let i = 0; i < 30; i++) await Promise.resolve(); }

describe("durable sale control delivery", () => {
  afterEach(() => { controllers.splice(0).forEach(delivery => delivery.stop()); vi.useRealTimers(); vi.restoreAllMocks(); });

  it("persists a frozen event before delivery, with server time instead of the workstation clock", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2040-01-01"));
    let elapsed = 100;
    const value = fixture({ elapsed: () => elapsed });
    await value.delivery.start(); await settled();
    elapsed = 350;
    const input = draft();
    const saved = await value.delivery.persist(input);
    input.lines[0].unitPrice = 99;
    expect(saved.occurredAt).toBe("2026-09-17T10:00:00.250Z");
    expect([...value.events.values()][0].lines[0].unitPrice).toBe(10);
    expect(JSON.stringify([...value.events.values()])).not.toContain("ephemeral-token");
    expect(value.send).not.toHaveBeenCalled();
    value.delivery.deliver(); await settled();
    expect(value.events.size).toBe(0);
    expect(value.send).toHaveBeenCalledWith({ ...input, lines: saved.lines, context, occurredAt: saved.occurredAt });
  });

  it("rejects local write failure without posting or silently dropping it", async () => {
    const value = fixture(); await value.delivery.start(); await settled();
    vi.mocked(value.storage.put).mockRejectedValueOnce(new Error("disk full"));
    await expect(value.delivery.persist(draft())).rejects.toThrow("disk full");
    expect(value.send).not.toHaveBeenCalled();
    expect(value.delivery.getSnapshot().error).toBe("storage");
  });

  it("retries network failure with the identical ID, evidence, identity and original occurrence time", async () => {
    vi.useFakeTimers();
    const value = fixture(); await value.delivery.start(); await settled();
    value.send.mockRejectedValueOnce(new TypeError("offline"));
    await value.delivery.persist(draft()); value.delivery.deliver(); await settled();
    expect(value.events.size).toBe(1);
    expect(value.delivery.getSnapshot().error).toBe("network");
    await vi.advanceTimersByTimeAsync(2_000); await settled();
    expect(value.send).toHaveBeenCalledTimes(2);
    expect(value.send.mock.calls[0][0]).toEqual(value.send.mock.calls[1][0]);
    expect(value.events.size).toBe(0);
  });

  it.each([400, 401, 403, 409, 423])("retains HTTP %s events visibly without a retry loop", async status => {
    vi.useFakeTimers();
    const value = fixture(); await value.delivery.start(); await settled();
    value.send.mockRejectedValue(new ApiError("rejected", status));
    await value.delivery.persist(draft()); value.delivery.deliver(); await settled();
    await vi.advanceTimersByTimeAsync(60_000); value.delivery.online(); await settled();
    expect(value.events.size).toBe(1);
    expect(value.send).toHaveBeenCalledTimes(1);
    expect(value.delivery.getSnapshot().error).toBe(status === 401 ? "session" : "rejected");
  });

  it("recovers after restart only for the original identity and uses the new session token", async () => {
    const old = fixture(); await old.delivery.start(); await settled();
    const saved = await old.delivery.persist(draft()); old.delivery.stop();
    const other = fixture({ store: old, identity: { ...context, userId: uuid(8) }, token: "other-user-token" });
    await other.delivery.start(); await settled();
    expect(other.send).not.toHaveBeenCalled(); expect(old.events.size).toBe(1);
    other.delivery.stop();
    const next = fixture({ store: old, token: "new-original-token" });
    await next.delivery.start(); await settled();
    expect(next.send).toHaveBeenCalledWith(expect.objectContaining({ deletionOperationId: saved.deletionOperationId, occurredAt: saved.occurredAt }));
    expect(next.request).toHaveBeenCalledWith("/sale-line-deletions", expect.objectContaining({ token: "new-original-token" }));
    expect(old.events.size).toBe(0);
  });

  it("keeps an accepted event when local acknowledgement removal fails and replays the same UUID", async () => {
    const value = fixture(); await value.delivery.start(); await settled();
    vi.mocked(value.storage.remove).mockRejectedValueOnce(new Error("disk unavailable"));
    await value.delivery.persist(draft()); value.delivery.deliver(); await settled();
    expect(value.events.size).toBe(1); expect(value.delivery.getSnapshot().error).toBe("storage");
    value.delivery.retry(); await settled();
    expect(value.send).toHaveBeenCalledTimes(2);
    expect(value.send.mock.calls[0][0]).toEqual(value.send.mock.calls[1][0]);
    expect(value.events.size).toBe(0);
  });

  it("does not treat corrupt storage or a mismatched context as an empty queue", async () => {
    const value = fixture();
    vi.mocked(value.storage.list).mockResolvedValue([{ ...draft(), version: 1, occurredAt: serverTime, context: { ...context, userId: uuid(8) } }]);
    await value.delivery.start(); await settled();
    expect(value.delivery.getSnapshot()).toMatchObject({ ready: false, error: "storage" });
    await expect(value.delivery.persist(draft())).rejects.toThrow("CONTROL_NOT_READY");
    expect(value.send).not.toHaveBeenCalled();
  });

  it("serializes rapid removals in occurrence order even within the same millisecond", async () => {
    const value = fixture(); await value.delivery.start(); await settled();
    const first = await value.delivery.persist(draft(6));
    const second = await value.delivery.persist(draft(5));
    expect(first.occurredAt < second.occurredAt).toBe(true);
    value.delivery.deliver(); await settled();
    expect(value.send.mock.calls.map(([event]) => event.deletionOperationId)).toEqual([uuid(6), uuid(5)]);
  });
  it.each([0.125, -0.125])("preserves signed fractional quantity %s and an empty product code", async quantity => {
    const value = fixture(); await value.delivery.start(); await settled();
    const input = draft(); input.lines[0].quantity = quantity; input.lines[0].code = "";
    await value.delivery.persist(input); value.delivery.deliver(); await settled();
    expect(value.send).toHaveBeenCalledWith(expect.objectContaining({ lines: [expect.objectContaining({ quantity, code: "" })] }));
  });
  it("rejects quantities with excess precision instead of rounding evidence", async () => {
    const value = fixture(); await value.delivery.start(); await settled();
    const input = draft(); input.lines[0].quantity = 0.1251;
    await expect(value.delivery.persist(input)).rejects.toThrow("CONTROL_EVENT_INVALID");
    expect(value.events.size).toBe(0);
  });
  it("retains an in-flight event when its original session stops, even if its response arrives later", async () => {
    const value = fixture(); await value.delivery.start(); await settled();
    let finish!: (result: unknown[]) => void;
    value.send.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    await value.delivery.persist(draft()); value.delivery.deliver(); await settled();
    value.delivery.stop(); finish([]); await settled();
    expect(value.events.size).toBe(1);
    expect(value.storage.remove).not.toHaveBeenCalled();
  });
  it("aborts stalled transport, retains the event and schedules a retry", async () => {
    vi.useFakeTimers();
    const value = fixture();
    await value.delivery.start(); await settled();
    value.request.mockImplementationOnce((_path, options) => new Promise((_resolve, reject) => {
      (options as { signal: AbortSignal }).signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
    }));
    await value.delivery.persist(draft()); value.delivery.deliver(); await settled();
    await vi.advanceTimersByTimeAsync(30_000); await settled();
    expect(value.events.size).toBe(1); expect(value.delivery.getSnapshot().error).toBe("network");
    await vi.advanceTimersByTimeAsync(2_000); await settled();
    expect(value.events.size).toBe(0);
  });
  it("drains a stored batch without rereading every pending payload for each acknowledgement", async () => {
    const value = fixture(); await value.delivery.start(); await settled();
    for (let index = 20; index < 40; index++) await value.delivery.persist(draft(index));
    vi.mocked(value.storage.list).mockClear();
    value.delivery.deliver();
    for (let i = 0; i < 10; i++) await settled();
    expect(value.send).toHaveBeenCalledTimes(20);
    expect(value.storage.list).toHaveBeenCalledTimes(2);
    expect(value.events.size).toBe(0);
  });
  it.each([401, 403])("does not automatically repeat a rejected HTTP %s context request", async status => {
    vi.useFakeTimers();
    const value = fixture(); value.request.mockRejectedValueOnce(new ApiError("context rejected", status));
    await value.delivery.start(); await settled();
    expect(value.delivery.getSnapshot()).toMatchObject({ ready: false, error: status === 401 ? "session" : "rejected" });
    await vi.advanceTimersByTimeAsync(60_000); value.delivery.online(); await settled();
    expect(value.request).toHaveBeenCalledTimes(1);
    value.delivery.retry(); await settled();
    expect(value.request).toHaveBeenCalledTimes(2);
    expect(value.delivery.getSnapshot().ready).toBe(true);
  });
  it("rejects deletion promptly while initial identity is unavailable instead of waiting for the network", async () => {
    const value = fixture(); let finish!: (result: unknown) => void;
    value.request.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const initialization = value.delivery.start();
    await expect(value.delivery.persist(draft())).rejects.toThrow("CONTROL_NOT_READY");
    expect(value.storage.put).not.toHaveBeenCalled();
    finish({ ...context, serverTime }); await initialization;
  });
});

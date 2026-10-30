// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPdaObservations, observePdaEvent } from "./PdaObservability";
import { setPdaDurableStorageForTests, type PdaDurableStorage } from "./PdaDurableStorage";

describe("PdaObservability", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "event-1" }), {
      status: 201,
      headers: { "Content-Type": "application/json" },
    })));
    const values=new Map<string,unknown>(); const storage:PdaDurableStorage={async read<T>(key:string){return values.get(key) as T|undefined},async write<T>(key:string,value:T){values.set(key,value)},async remove(key:string){values.delete(key)},async clear(){values.clear()}};
    setPdaDurableStorageForTests(storage); localStorage.clear(); Object.defineProperty(navigator,"onLine",{configurable:true,value:true});
  });

  it("sends only the bounded operational schema", async () => {
    expect(await observePdaEvent("opaque-token", {
      type: "SYNC_FAILURE",
      code: "PDA_QUEUE_SYNC_SERVER",
      workId: "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
      durationMs: 999_999,
      traceId: "trace-safe-1",
    })).toBe(true);
    const init = vi.mocked(fetch).mock.calls[0]?.[1];
    const body = JSON.parse(String(init?.body));
    expect(body).toMatchObject({
      type: "SYNC_FAILURE",
      code: "PDA_QUEUE_SYNC_SERVER",
      workId: "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
      durationMs: 120_000,
      traceId: "trace-safe-1",
    });
    expect(body.clientEventId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(body).not.toHaveProperty("message");
    expect(String(init?.headers)).not.toContain("opaque-token");
  });

  it("drops invalid identifiers instead of leaking arbitrary text", async () => {
    await observePdaEvent("token", {
      type: "TASK_BLOCKED",
      code: "PDA_QUEUE_BLOCKED",
      workId: "not-an-id secret",
      traceId: "line\nbreak",
    });
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0]?.[1]?.body));
    expect(body).not.toHaveProperty("workId");
    expect(body).not.toHaveProperty("traceId");
  });

  it("never interrupts PDA work when telemetry is unavailable", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new Error("network down"));
    await expect(observePdaEvent("token", { type: "SYNC_FAILURE", code: "PDA_QUEUE_SYNC_SERVER" })).resolves.toBe(false);
  });

  it("rejects non-catalog codes before making a request", async () => {
    expect(await observePdaEvent("token", { type: "SYNC_FAILURE", code: "password=1234" })).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("persists a failure offline and flushes it after reconnection", async () => {
    Object.defineProperty(navigator,"onLine",{configurable:true,value:false});
    expect(await observePdaEvent("token",{type:"SYNC_FAILURE",code:"PDA_QUEUE_SYNC_OFFLINE"})).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
    Object.defineProperty(navigator,"onLine",{configurable:true,value:true});
    expect(await flushPdaObservations("token")).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

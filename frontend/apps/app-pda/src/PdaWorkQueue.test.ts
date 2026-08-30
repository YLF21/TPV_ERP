// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { isAssignedToCurrentUser, nextQueueWakeup, queueRetryDelay, queuedActionPath, readPdaWorkQueue, writePdaWorkQueue } from "./PdaWorkQueue";

describe("PdaWorkQueue", () => {
  beforeEach(() => localStorage.clear());
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
  it("filters future assigned tasks using JWT identity", () => {
    const payload = btoa(JSON.stringify({ sub: "user-1", username: "ADMIN" })).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_"); const token = `x.${payload}.x`;
    expect(isAssignedToCurrentUser({ assignedTo: "admin" }, token)).toBe(true); expect(isAssignedToCurrentUser({ assignedUserId: "user-2" }, token)).toBe(false); expect(isAssignedToCurrentUser({}, token)).toBe(true);
  });
  it("persists normalized queue data", () => {
    writePdaWorkQueue([{ id: "1", createdAt: "x", payload: { title: "Task" } }]); expect(readPdaWorkQueue()[0]).toMatchObject({ action: "create", state: "pending" });
  });
});
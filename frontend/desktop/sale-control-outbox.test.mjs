import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createSaleControlOutbox } from "./sale-control-outbox.cjs";

const uuid = n => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const context = { storeId: uuid(1), terminalId: uuid(2), userId: uuid(3) };
const event = () => ({ version: 1, context, occurredAt: "2026-09-17T10:00:00.000Z",
  saleOperationId: uuid(4), deletionOperationId: uuid(5), fullTicketClear: false,
  lines: [{ productId: uuid(6), code: "P-1", name: "Product", quantity: 1, unitPrice: 12.345 }] });
const directories = [];
function fixture(backendScope = "http://backend-a:8080") {
  const userDataPath = fs.mkdtempSync(path.join(os.tmpdir(), "tpverp-control-outbox-"));
  directories.push(userDataPath);
  return { userDataPath, backendScope, store: createSaleControlOutbox({ userDataPath, backendScope }) };
}
afterEach(() => {
  for (const directory of directories.splice(0)) {
    const absolute = path.resolve(directory);
    if (path.dirname(absolute) !== path.resolve(os.tmpdir()) || !path.basename(absolute).startsWith("tpverp-control-outbox-")) {
      throw new Error("Unexpected temporary directory");
    }
    fs.rmSync(absolute, { recursive: true, force: true });
  }
});

describe("desktop durable sale control storage", () => {
  it("recovers committed events in a fresh instance, independent of the renderer origin", () => {
    const value = fixture(); value.store.put(event());
    const reopened = createSaleControlOutbox(value);
    expect(reopened.list(context)).toEqual([event()]);
    reopened.remove(context, uuid(5));
    expect(value.store.list(context)).toEqual([]);
  });
  it("isolates backend, store, terminal and user without storing credentials", () => {
    const value = fixture(); value.store.put(event());
    expect(createSaleControlOutbox({ ...value, backendScope: "http://backend-b:8080" }).list(context)).toEqual([]);
    for (const key of ["storeId", "terminalId", "userId"]) expect(value.store.list({ ...context, [key]: uuid(7) })).toEqual([]);
    expect(() => value.store.put({ ...event(), accessToken: "secret" })).toThrow("CONTROL_EVENT_INVALID");
  });
  it("keeps the original immutable payload when a UUID is submitted twice", () => {
    const value = fixture(); value.store.put(event()); value.store.put(event());
    expect(value.store.list(context)).toEqual([event()]);
    expect(() => value.store.put({ ...event(), fullTicketClear: true })).toThrow("CONTROL_EVENT_CONFLICT");
    expect(value.store.list(context)).toEqual([event()]);
  });
  it("rejects path traversal in both context and event IDs", () => {
    const value = fixture();
    expect(() => value.store.list({ ...context, userId: "../../other" })).toThrow("CONTROL_CONTEXT_INVALID");
    expect(() => value.store.remove(context, "../identity.dpapi")).toThrow("CONTROL_EVENT_ID_INVALID");
    expect(() => value.store.put({ ...event(), deletionOperationId: "../../other" })).toThrow("CONTROL_EVENT_INVALID");
  });
  it("propagates a failed durable write and never publishes a partial event", () => {
    const value = fixture();
    const fileSystem = { ...fs, fsyncSync: vi.fn(() => { throw new Error("disk full"); }) };
    const faulty = createSaleControlOutbox({ ...value, fileSystem });
    expect(() => faulty.put(event())).toThrow("disk full");
    expect(value.store.list(context)).toEqual([]);
  });
  it("reports a corrupt committed file rather than treating it as an empty queue", () => {
    const value = fixture(); value.store.put(event());
    const filename = fs.readdirSync(path.join(value.userDataPath, "control-events"), { recursive: true })
      .find(name => String(name).endsWith(`${uuid(5)}.json`));
    fs.writeFileSync(path.join(value.userDataPath, "control-events", filename), "{invalid", "utf8");
    expect(() => value.store.list(context)).toThrow();
  });
  it.each([0.125, -0.125])("stores signed decimal quantity %s without changing it or an empty code", quantity => {
    const value = fixture(); const input = event(); input.lines[0].quantity = quantity; input.lines[0].code = "";
    value.store.put(input);
    expect(value.store.list(context)).toEqual([input]);
  });
  it("rejects a linked scope directory instead of following it outside the queue", () => {
    const value = fixture(); value.store.put(event()); value.store.remove(context, uuid(5));
    const relative = fs.readdirSync(path.join(value.userDataPath, "control-events"), { recursive: true })
      .find(name => String(name).endsWith(uuid(3)));
    const folder = path.join(value.userDataPath, "control-events", relative);
    const outside = path.join(value.userDataPath, "outside"); fs.mkdirSync(outside);
    fs.rmdirSync(folder); fs.symlinkSync(outside, folder, "junction");
    expect(() => value.store.put(event())).toThrow("CONTROL_STORAGE_UNSAFE_PATH");
    expect(fs.readdirSync(outside)).toEqual([]);
  });
});

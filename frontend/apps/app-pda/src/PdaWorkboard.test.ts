// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { readPdaWorkQueue, writePdaWorkQueue } from "./PdaWorkboard";

describe("PdaWorkboard offline queue", () => {
  beforeEach(() => localStorage.clear());
  it("persists and restores pending operations", () => {
    const values = [{ id: "queue-1", createdAt: "2026-08-25T10:00:00Z", payload: {
      type: "TASK" as const, title: "Reponer lineal", reference: "", productCode: "",
      warehouseId: null, quantity: null, lotNumber: "", expiryDate: null, location: "",
      priority: "HIGH", notes: "Pasillo 2", evidenceName: null, evidenceType: null, evidenceData: null
    }}];
    writePdaWorkQueue(values);
    expect(readPdaWorkQueue()).toEqual(values);
  });
  it("returns an empty queue when storage is invalid", () => {
    localStorage.setItem("tpverp.pda.work.queue.v1", "invalid");
    expect(readPdaWorkQueue()).toEqual([]);
  });
});
import { describe, expect, it, vi } from "vitest";
import { sortProductTableRows } from "./productCodeSorting";

describe("product code sorting", () => {
  it("matches the server's numeric, prefix and punctuation order without numeric overflow", () => {
    const codes = ["P-2", "P02", "p2", "P", "P10B10", "P10B2", "_2", "12", "2", "0", "000",
      "1000000000000000000000000000000000000", "999999999999999999999999999999999999"];
    const original = [...codes];
    const value = vi.fn((code: string) => code);
    expect(sortProductTableRows(codes, { column: "code", direction: "asc" }, value)).toEqual([
      "0", "000", "2", "12", "999999999999999999999999999999999999",
      "1000000000000000000000000000000000000", "_2", "P", "P02", "p2", "P10B2", "P10B10", "P-2"
    ]);
    expect(value).toHaveBeenCalledTimes(codes.length);
    expect(codes).toEqual(original);
  });

  it.each(["asc", "desc"] as const)("keeps missing codes last and equal codes stable in %s", (direction) => {
    const rows = [null, "P2", undefined, "P02", "", "p2", "P-2"];
    expect(sortProductTableRows(rows, { column: "code", direction }, (code) => code))
      .toEqual(direction === "asc"
        ? ["P2", "P02", "p2", "P-2", null, undefined, ""]
        : ["P-2", "P2", "P02", "p2", null, undefined, ""]);
  });

  it("keeps locale-sensitive sorting for other columns", () => {
    const rows = [{ name: "P2" }, { name: "P-2" }, { name: "" }];
    expect(sortProductTableRows(rows, { column: "name", direction: "asc" }, (row) => row.name, "es"))
      .toEqual([rows[1], rows[0], rows[2]]);
  });

  it("returns the unsorted rows in a new array when no sort is selected", () => {
    const rows = ["10", "2"];
    const value = vi.fn((code: string) => code);
    const result = sortProductTableRows(rows, null, value);
    expect(result).toEqual(rows);
    expect(result).not.toBe(rows);
    expect(value).not.toHaveBeenCalled();
  });
});

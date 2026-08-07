import { describe, expect, it } from "vitest";
import {
  compareTableSortValues,
  nextTableSort,
  readStoredTableSort,
  sanitizeTableSort,
  sortTableRows,
  tableSortStorageKey
} from "./tableSorting";

describe("table sorting", () => {
  const columns = ["code", "name", "total"] as const;

  it("toggles a column from ascending to descending", () => {
    expect(nextTableSort(null, "name")).toEqual({ column: "name", direction: "asc" });
    expect(nextTableSort({ column: "name", direction: "asc" }, "name"))
      .toEqual({ column: "name", direction: "desc" });
    expect(nextTableSort({ column: "name", direction: "desc" }, "total"))
      .toEqual({ column: "total", direction: "asc" });
  });

  it("sorts values naturally and keeps empty values last", () => {
    expect(compareTableSortValues("A2", "A10", "es")).toBeLessThan(0);
    expect(compareTableSortValues(null, "A", "es")).toBeGreaterThan(0);
    expect(compareTableSortValues(20, 3, "es")).toBeGreaterThan(0);
  });

  it("keeps equal rows stable", () => {
    const rows = [
      { id: "first", total: 2 },
      { id: "second", total: 1 },
      { id: "third", total: 2 }
    ];
    expect(sortTableRows(rows, { column: "total", direction: "asc" }, (row) => row.total)
      .map((row) => row.id)).toEqual(["second", "first", "third"]);
  });

  it("keeps empty values last in descending order", () => {
    const rows = [
      { id: "empty", total: null },
      { id: "small", total: 1 },
      { id: "large", total: 3 }
    ];
    expect(sortTableRows(rows, { column: "total", direction: "desc" }, (row) => row.total)
      .map((row) => row.id)).toEqual(["large", "small", "empty"]);
  });

  it("sanitizes and stores sorting per app, user and table", () => {
    const fallback = { column: "name", direction: "asc" } as const;
    expect(sanitizeTableSort({ column: "missing", direction: "desc" }, columns, fallback)).toEqual(fallback);
    expect(tableSortStorageKey("venta", " ADMIN ", "customers"))
      .toBe("tpv-erp:venta:user:admin:table:customers:sort");

    const storage = new Map<string, string>();
    const fakeStorage: Storage = {
      get length() { return storage.size; },
      clear: () => storage.clear(),
      getItem: (key: string) => storage.get(key) ?? null,
      key: (index: number) => Array.from(storage.keys())[index] ?? null,
      removeItem: (key: string) => { storage.delete(key); },
      setItem: (key: string, value: string) => { storage.set(key, value); }
    };
    storage.set(tableSortStorageKey("gestion", "admin", "customers"), JSON.stringify({ column: "total", direction: "desc" }));
    expect(readStoredTableSort("gestion", "admin", "customers", columns, fallback, fakeStorage))
      .toEqual({ column: "total", direction: "desc" });
  });
});

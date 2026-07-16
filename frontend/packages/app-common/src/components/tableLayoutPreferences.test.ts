import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import {
  clampTableColumnWidth,
  createDefaultTableLayout,
  loadAllTablePreferences,
  loadTablePreference,
  moveTableColumnByKeyboard,
  reorderTableColumns,
  resizeTableColumn,
  sanitizeSavedTableLayout,
  saveTablePreference,
  tableLayoutGridTemplate,
  tableLayoutStorageKey,
  toggleTableColumnVisibility,
  visibleTableColumns
} from "./tableLayoutPreferences";

vi.mock("../api/client", () => ({
  apiRequest: vi.fn()
}));

const apiRequestMock = vi.mocked(apiRequest);

const definitions = [
  { key: "code", defaultWidth: 100 },
  { key: "name", defaultWidth: 240 },
  { key: "total", defaultWidth: 120 }
] as const;

describe("table layout preferences", () => {
  beforeEach(() => {
    apiRequestMock.mockReset();
  });

  it("creates concrete defaults from column definitions", () => {
    expect(createDefaultTableLayout(definitions)).toEqual([
      { key: "code", width: 100, visible: true },
      { key: "name", width: 240, visible: true },
      { key: "total", width: 120, visible: true }
    ]);
  });

  it("preserves saved order and appends newly added columns in definition order", () => {
    const layout = sanitizeSavedTableLayout([
      { key: "name", width: 310, visible: false },
      { key: "code", width: 90 }
    ], definitions);

    expect(layout).toEqual([
      { key: "name", width: 310, visible: false },
      { key: "code", width: 90, visible: true },
      { key: "total", width: 120, visible: true }
    ]);
  });

  it("ignores bad and duplicate keys while keeping the first valid occurrence", () => {
    const layout = sanitizeSavedTableLayout([
      null,
      { key: "missing", width: 180 },
      { key: "name", width: "bad", visible: "bad" },
      { key: "name", width: 330, visible: false },
      { key: "code", width: Number.NaN, visible: false },
      { key: 42, width: 200 }
    ], definitions);

    expect(layout).toEqual([
      { key: "name", width: 240, visible: true },
      { key: "code", width: 100, visible: false },
      { key: "total", width: 120, visible: true }
    ]);
  });

  it("clamps sanitized and resized widths to the backend range", () => {
    expect(clampTableColumnWidth(55.4)).toBe(56);
    expect(clampTableColumnWidth(800.6)).toBe(800);
    expect(clampTableColumnWidth(100.6)).toBe(101);
    expect(clampTableColumnWidth(Number.NaN, 123)).toBe(123);

    const sanitized = sanitizeSavedTableLayout([
      { key: "code", width: 1 },
      { key: "name", width: 1200 }
    ], definitions);
    expect(sanitized.find((column) => column.key === "code")?.width).toBe(56);
    expect(sanitized.find((column) => column.key === "name")?.width).toBe(800);
    expect(resizeTableColumn(sanitized, "code", 900).find((column) => column.key === "code")?.width).toBe(800);
  });

  it("reorders, moves and hides columns without allowing an empty visible layout", () => {
    const defaults = createDefaultTableLayout(definitions);
    const reordered = reorderTableColumns(defaults, "code", "total");
    const moved = moveTableColumnByKeyboard(reordered, "total", -1);

    expect(reordered.map((column) => column.key)).toEqual(["name", "total", "code"]);
    expect(moved.map((column) => column.key)).toEqual(["total", "name", "code"]);

    const withoutName = toggleTableColumnVisibility(moved, "name");
    const onlyTotal = toggleTableColumnVisibility(withoutName, "code");
    const cannotHideLast = toggleTableColumnVisibility(onlyTotal, "total");

    expect(cannotHideLast).toBe(onlyTotal);
    expect(visibleTableColumns(cannotHideLast).map((column) => column.key)).toEqual(["total"]);
    expect(tableLayoutGridTemplate(cannotHideLast)).toBe("120px");
  });

  it("builds a normalized storage key scoped by app, username and table key", () => {
    expect(tableLayoutStorageKey("venta", " ADMIN/One ", " stock/current ")).toBe(
      "tpv-erp:venta:user:admin%2Fone:table:stock%2Fcurrent:layout"
    );
    expect(tableLayoutStorageKey("gestion", "ADMIN/One", "stock/current")).not.toBe(
      tableLayoutStorageKey("venta", "ADMIN/One", "stock/current")
    );
  });

  it("uses the relative load-all and load-one API contracts", async () => {
    apiRequestMock
      .mockResolvedValueOnce({ app: "venta", preferences: [] })
      .mockResolvedValueOnce({ app: "venta", tableKey: "stock/current", columns: [] });

    await loadAllTablePreferences("venta", "token");
    await loadTablePreference("venta", "stock/current", "token");

    expect(apiRequestMock).toHaveBeenNthCalledWith(1, "/ui/table-preferences/venta", { token: "token" });
    expect(apiRequestMock).toHaveBeenNthCalledWith(
      2,
      "/ui/table-preferences/venta/stock%2Fcurrent",
      { token: "token" }
    );
  });

  it("saves the exact table preference body with PUT", async () => {
    const columns = createDefaultTableLayout(definitions);
    apiRequestMock.mockResolvedValueOnce({
      app: "gestion",
      tableKey: "sales.tickets",
      columns
    });

    await saveTablePreference("gestion", "sales.tickets", columns, "token");

    expect(apiRequestMock).toHaveBeenCalledWith(
      "/ui/table-preferences/gestion/sales.tickets",
      {
        method: "PUT",
        token: "token",
        body: { app: "gestion", tableKey: "sales.tickets", columns }
      }
    );
  });
});

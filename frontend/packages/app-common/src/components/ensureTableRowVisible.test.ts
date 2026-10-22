// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { ensureTableRowVisible } from "./ensureTableRowVisible";

function tableViewport(rowTop: number, rowBottom: number) {
  const container = document.createElement("div");
  const table = document.createElement("table");
  table.innerHTML = "<thead><tr><th>Header</th></tr></thead><tbody><tr><td>Row</td></tr></tbody>";
  container.append(table);
  document.body.append(container);
  const row = table.tBodies[0].rows[0];
  Object.defineProperties(container, {
    clientTop: { value: 2 },
    clientHeight: { value: 100 },
    scrollHeight: { value: 500 },
  });
  container.scrollTop = 200;
  container.scrollLeft = 80;
  vi.spyOn(container, "getBoundingClientRect").mockReturnValue({ top: 10, bottom: 129 } as DOMRect);
  vi.spyOn(table.tHead!, "getBoundingClientRect").mockReturnValue({ bottom: -100 } as DOMRect);
  vi.spyOn(table.tHead!.rows[0].cells[0], "getBoundingClientRect").mockReturnValue({ bottom: 42 } as DOMRect);
  vi.spyOn(row, "getBoundingClientRect").mockReturnValue({ top: rowTop, bottom: rowBottom } as DOMRect);
  return { container, row };
}

afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); });

describe("ensureTableRowVisible", () => {
  it("reveals the full row above the horizontal scrollbar and keeps horizontal position", () => {
    const { container, row } = tableViewport(90, 126);
    ensureTableRowVisible(container, row);
    expect(container.scrollTop).toBe(214);
    expect(container.scrollLeft).toBe(80);
  });

  it("accounts for sticky header cells even when the thead has scrolled away", () => {
    const { container, row } = tableViewport(30, 66);
    ensureTableRowVisible(container, row);
    expect(container.scrollTop).toBe(188);
  });

  it("does not move an already visible row", () => {
    const { container, row } = tableViewport(42, 78);
    ensureTableRowVisible(container, row);
    expect(container.scrollTop).toBe(200);
  });

  it("clamps at the final row without wrapping or scrolling horizontally", () => {
    const { container, row } = tableViewport(100, 140);
    container.scrollTop = 390;
    ensureTableRowVisible(container, row);
    expect(container.scrollTop).toBe(400);
    expect(container.scrollLeft).toBe(80);
  });
});

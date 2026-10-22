// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import { StockAdjustmentHistoryTable } from "./StockAdjustmentHistoryTable";
import { StockCountTable } from "./StockCountTable";

const session: UserSession = { username: "admin", displayName: "Admin", accessToken: "token", permissions: ["GESTION_ALMACEN"] };
afterEach(() => { cleanup(); vi.useRealTimers(); localStorage.clear(); });

it("moves across live stock count rows and leaves row actions untouched", () => {
  const select = vi.fn();
  const open = vi.fn();
  const rows = Array.from({ length: 5 }, (_, index) => ({ id: `row-${index}`, label: `Fila ${index}` }));
  render(<StockCountTable rows={rows} columns={[{ key: "label", label: "Nombre", defaultWidth: 140 }]}
    cell={(row) => row.label} session={session} tableKey="test.stock.count.repeat" empty="Vacío"
    selected="" onSelect={select} onOpen={open} />);
  const first = screen.getByRole("row", { name: "Fila 0" });
  const fourth = screen.getByRole("row", { name: "Fila 3" });
  expect(first).toHaveFocus();
  expect(select).toHaveBeenCalledWith("row-0");
  first.focus();
  vi.useFakeTimers();
  fireEvent.keyDown(first, { key: "ArrowDown" });
  act(() => { vi.advanceTimersByTime(250); });
  expect(fourth).toHaveFocus();
  expect(select).toHaveBeenLastCalledWith("row-3");
  fireEvent.keyUp(fourth, { key: "ArrowDown" });
  act(() => { vi.advanceTimersByTime(500); });
  expect(fourth).toHaveFocus();
  expect(open).not.toHaveBeenCalled();
});

it("reveals the selected count row above the scrollbar without moving columns", () => {
  const rows = [{ id: "a", label: "Primera" }, { id: "b", label: "Última" }];
  const view = render(<StockCountTable rows={rows} columns={[{ key: "label", label: "Nombre", defaultWidth: 140 }]}
    cell={(row) => row.label} session={session} tableKey="test.stock.count.visibility" empty="Vacío" onSelect={vi.fn()} />);
  const container = view.container.querySelector<HTMLElement>(".inventory-table")!;
  const first = screen.getByRole("row", { name: "Primera" });
  const last = screen.getByRole("row", { name: "Última" });
  Object.defineProperties(container, { clientTop: { value: 2 }, clientHeight: { value: 100 }, scrollHeight: { value: 300 } });
  container.scrollLeft = 75;
  vi.spyOn(container, "getBoundingClientRect").mockReturnValue({ top: 10, bottom: 130 } as DOMRect);
  vi.spyOn(container.querySelector("thead")!, "getBoundingClientRect").mockReturnValue({ bottom: 42 } as DOMRect);
  vi.spyOn(container.querySelector("th")!, "getBoundingClientRect").mockReturnValue({ bottom: 42 } as DOMRect);
  vi.spyOn(last, "getBoundingClientRect").mockReturnValue({ top: 90, bottom: 125 } as DOMRect);
  first.focus();
  fireEvent.keyDown(first, { key: "ArrowDown" });
  expect(last).toHaveFocus();
  expect(container.scrollTop).toBe(13);
  expect(container.scrollLeft).toBe(75);
  fireEvent.keyUp(last, { key: "ArrowDown" });
});

it("repeats adjustment history selection and stops at the last row", () => {
  const rows = Array.from({ length: 4 }, (_, index) => ({ movementId: `movement-${index}`, warehouseId: "w",
    productId: `p-${index}`, code: `P-${index}`, name: `Producto ${index}`,
    previousQuantity: 1, adjustmentQuantity: 1, nextQuantity: 2, reason: "Ajuste", createdAt: "2026-09-24T10:00:00Z" }));
  render(<div className="stock-history-table-scroll"><StockAdjustmentHistoryTable rows={rows} session={session} t={(key) => key} /></div>);
  const first = screen.getByRole("row", { name: /P-0/ });
  const last = screen.getByRole("row", { name: /P-3/ });
  expect(first).toHaveFocus();
  expect(first).toHaveAttribute("aria-selected", "true");
  first.focus();
  vi.useFakeTimers();
  fireEvent.keyDown(first, { key: "ArrowDown" });
  act(() => { vi.advanceTimersByTime(500); });
  expect(last).toHaveFocus();
  expect(last).toHaveAttribute("aria-selected", "true");
  fireEvent.keyUp(last, { key: "ArrowDown" });
  act(() => { vi.advanceTimersByTime(500); });
  expect(last).toHaveFocus();
});

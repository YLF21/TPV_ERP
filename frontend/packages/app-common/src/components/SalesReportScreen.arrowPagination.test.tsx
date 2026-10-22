// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { SalesReportScreen } from "./SalesReportScreen";

const date = new Date().toISOString().slice(0, 10);
const ticket = (n: number, total = n * 10) => ({
  id: `ticket-${n}`, numero: `T-${n}`, tipo: "TICKET", estado: "CONFIRMADO",
  fecha: date, total: String(total), paymentMethods: ["CASH"],
});

function setup(nextPage: () => Promise<unknown>, first = [ticket(1), ticket(2), ticket(3)]) {
  vi.spyOn(Element.prototype, "clientHeight", "get").mockImplementation(function (this: Element) {
    return this.classList.contains("report-table-scroll") ? 240 : 0;
  });
  vi.spyOn(Element.prototype, "scrollHeight", "get").mockImplementation(function (this: Element) {
    return this.classList.contains("report-table-scroll") ? 600 : 0;
  });
  const request = vi.fn((path: string) => {
    if (path.startsWith("/document-reports/date-options")) return Promise.resolve({ currentDate: date, earliestDate: "2020-01-01" });
    if (path.startsWith("/document-reports/tickets")) {
      return path.includes("cursor=next-page") ? nextPage()
        : Promise.resolve({ items: first, hasMore: true, nextCursor: "next-page" });
    }
    return Promise.resolve({ items: [], hasMore: false, nextCursor: null });
  });
  const view = render(<SalesReportScreen app="gestion" locale="es" initialReport="salesReport.tickets"
    session={{ username: "pagination-selection", displayName: "ADMIN", accessToken: "token", permissions: ["ADMIN"] }}
    terminalContext={{ storeName: "Prueba", terminalCode: "01" }}
    request={request as unknown as NonNullable<Parameters<typeof SalesReportScreen>[0]["request"]>}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  const rows = () => view.container.querySelectorAll<HTMLTableRowElement>('.report-table[data-report-key="salesReport.tickets"] tbody tr');
  const ticketCalls = () => request.mock.calls.filter(([path]) => path.startsWith("/document-reports/tickets"));
  return { ...view, rows, ticketCalls };
}

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); localStorage.clear(); });

it("selects and focuses the first ticket after loading, then moves down without a click", async () => {
  const { rows, ticketCalls } = setup(() => Promise.resolve({ items: [ticket(4)], hasMore: false, nextCursor: null }));
  await waitFor(() => expect(rows()).toHaveLength(3));
  await waitFor(() => expect(rows()[0]).toHaveClass("selected"));
  expect(rows()[0]).toHaveFocus();
  fireEvent.keyDown(rows()[0], { key: "ArrowDown" });
  expect(rows()[1]).toHaveClass("selected");
  expect(rows()[1]).toHaveFocus();
  expect(ticketCalls()).toHaveLength(1);
  fireEvent.keyUp(window, { key: "ArrowDown" });
});

it("uses the focused last row before its selection state commits", async () => {
  const first = Array.from({ length: 50 }, (_, index) => ticket(index + 1));
  const { container, rows, ticketCalls } = setup(() => new Promise(() => {}), first);
  await waitFor(() => expect(rows()).toHaveLength(50));
  fireEvent.click(container.querySelector<HTMLElement>('.report-table [data-column-key="total"] .table-layout-sort-button')!);
  const last = rows()[49];
  act(() => {
    last.focus();
    last.dispatchEvent(new FocusEvent("focusin", { bubbles: true }));
    last.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
  });
  expect(last).toHaveClass("selected");
  expect(last).toHaveFocus();
  expect(ticketCalls()).toHaveLength(2);
  expect(rows()).toHaveLength(50);
  fireEvent.keyUp(window, { key: "ArrowDown" });
});

it("holds at the last loaded ticket and continues after the next page resolves", async () => {
  let finish!: (value: unknown) => void;
  const { rows, ticketCalls } = setup(() => new Promise((resolve) => { finish = resolve; }));
  await waitFor(() => expect(rows()).toHaveLength(3));
  rows()[0].focus();
  fireEvent.focusIn(rows()[0]);
  expect(rows()[0]).toHaveClass("selected");
  expect(document.querySelector('[aria-modal="true"]')).toBeNull();
  vi.useFakeTimers();
  fireEvent.keyDown(rows()[0], { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(180); });
  expect(rows()[2]).toHaveClass("selected");
  expect(ticketCalls()).toHaveLength(2);
  await act(async () => { vi.advanceTimersByTime(210); });
  expect(ticketCalls()).toHaveLength(2);
  expect(rows()[2]).toHaveClass("selected");
  await act(async () => { finish({ items: [ticket(4), ticket(5)], hasMore: false, nextCursor: null }); });
  await act(async () => { vi.advanceTimersByTime(70); });
  expect(rows()).toHaveLength(5);
  expect(rows()[3]).toHaveClass("selected");
  fireEvent.keyUp(window, { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(210); });
  expect(rows()[3]).toHaveClass("selected");
  expect(ticketCalls()).toHaveLength(2);
});

it("keeps the same ticket selected when sorting inserts a later page before it", async () => {
  const { container, rows, ticketCalls } = setup(() => Promise.resolve({
    items: [ticket(4, 20), ticket(5, 40)], hasMore: false, nextCursor: null,
  }), [ticket(1, 10), ticket(2, 30), ticket(3, 50)]);
  await waitFor(() => expect(rows()).toHaveLength(3));
  fireEvent.click(container.querySelector<HTMLElement>('.report-table [data-column-key="total"] .table-layout-sort-button')!);
  const scroll = container.querySelector<HTMLElement>(".report-table-scroll")!;
  vi.spyOn(Element.prototype, "clientHeight", "get").mockImplementation(function (this: Element) {
    return this.classList.contains("report-table-scroll") ? 180 : 0;
  });
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
    const index = this.matches('.report-table[data-report-key="salesReport.tickets"] tbody tr')
      ? Array.from(this.parentElement!.children).indexOf(this) : -1;
    const top = index >= 0 ? 42 + index * 40 - scroll.scrollTop : 0;
    const bottom = index >= 0 ? top + 40 : this.classList.contains("report-table-scroll") ? 220
      : this.matches("thead, thead th") ? 42 : 0;
    return { top, bottom, left: 0, right: 100, width: 100, height: bottom - top,
      x: 0, y: top, toJSON: () => ({}) } as DOMRect;
  });
  rows()[2].focus();
  fireEvent.focusIn(rows()[2]);
  expect(rows()[2]).toHaveClass("selected");
  expect(scroll.scrollTop).toBe(0);
  vi.useFakeTimers();
  fireEvent.keyDown(rows()[2], { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(180); });
  expect(ticketCalls()).toHaveLength(2);
  await act(async () => { await Promise.resolve(); });
  expect(rows()).toHaveLength(5);
  expect(rows()[4]).toHaveClass("selected");
  expect(rows()[4]).toHaveFocus();
  expect(scroll.scrollTop).toBe(62);
  fireEvent.keyUp(window, { key: "ArrowDown" });
});

it("does not advance after keyup while the next report page is unresolved", async () => {
  let finish!: (value: unknown) => void;
  const { rows, ticketCalls } = setup(() => new Promise((resolve) => { finish = resolve; }));
  await waitFor(() => expect(rows()).toHaveLength(3));
  rows()[0].focus(); fireEvent.focusIn(rows()[0]);
  vi.useFakeTimers();
  fireEvent.keyDown(rows()[0], { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(180); });
  expect(rows()[2]).toHaveClass("selected");
  expect(ticketCalls()).toHaveLength(2);
  fireEvent.keyUp(window, { key: "ArrowDown" });
  await act(async () => { finish({ items: [ticket(4), ticket(5)], hasMore: false, nextCursor: null }); });
  await act(async () => { vi.advanceTimersByTime(210); });
  expect(rows()).toHaveLength(5);
  expect(rows()[2]).toHaveClass("selected");
  expect(rows()[3]).not.toHaveClass("selected");
  expect(ticketCalls()).toHaveLength(2);
});

it("does not reclaim focus from another control when a sorted page changes the selected index", async () => {
  let finish!: (value: unknown) => void;
  const { container, rows, ticketCalls } = setup(() => new Promise((resolve) => { finish = resolve; }),
    [ticket(1, 10), ticket(2, 30), ticket(3, 50)]);
  await waitFor(() => expect(rows()).toHaveLength(3));
  fireEvent.click(container.querySelector<HTMLElement>('.report-table [data-column-key="total"] .table-layout-sort-button')!);
  rows()[2].focus(); fireEvent.focusIn(rows()[2]);
  vi.useFakeTimers();
  fireEvent.keyDown(rows()[2], { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(180); });
  expect(ticketCalls()).toHaveLength(2);
  fireEvent.keyUp(window, { key: "ArrowDown" });
  const filter = screen.getByRole("button", { name: "Filtrar" });
  filter.focus();
  await act(async () => { finish({ items: [ticket(4, 20), ticket(5, 40)], hasMore: false, nextCursor: null }); });
  expect(rows()[4]).toHaveClass("selected");
  expect(filter).toHaveFocus();
});

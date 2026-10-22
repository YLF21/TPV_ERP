// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SaleCustomer } from "./SaleScreen";
import { SaleInvoiceCustomerListDialog } from "./SaleInvoiceCustomerListDialog";
import * as tableVisibility from "./ensureTableRowVisible";

const { apiRequest } = vi.hoisted(() => ({ apiRequest: vi.fn() }));
vi.mock("../api/client", () => ({ apiRequest }));

const customers: SaleCustomer[] = [
  { id: "one", clientId: "CLI-001", fiscalName: "Cliente Uno", active: true },
  { id: "two", clientId: "CLI-002", fiscalName: "Cliente Dos", active: true },
  { id: "three", clientId: "CLI-003", fiscalName: "Cliente Tres", active: true },
];

afterEach(() => {
  cleanup();
  apiRequest.mockReset();
  vi.restoreAllMocks();
});

describe("SaleInvoiceCustomerListDialog", () => {
  it("uses the focused row before selection state commits and keeps row focus while moving", async () => {
    apiRequest.mockResolvedValue(customers);
    const onSelect = vi.fn();
    render(<SaleInvoiceCustomerListDialog locale="es" selectedCustomerId="one" onSelect={onSelect} onClose={vi.fn()} />);
    const first = await screen.findByRole("button", { name: /CLI-001 · Cliente Uno/ });
    const second = screen.getByRole("button", { name: /CLI-002 · Cliente Dos/ });
    const last = screen.getByRole("button", { name: /CLI-003 · Cliente Tres/ });

    act(() => { last.focus(); fireEvent.keyDown(last, { key: "ArrowDown" }); });
    expect(last).toHaveAttribute("aria-current", "true");
    expect(last).toHaveFocus();
    fireEvent.keyUp(last, { key: "ArrowDown" });
    vi.useFakeTimers();
    try {
      fireEvent.keyDown(last, { key: "ArrowUp" });
      expect(second).toHaveAttribute("aria-current", "true");
      expect(second).toHaveFocus();
      act(() => vi.advanceTimersByTime(180));
      expect(first).toHaveAttribute("aria-current", "true");
      expect(first).toHaveFocus();
      fireEvent.keyUp(first, { key: "ArrowUp" });
    } finally {
      vi.useRealTimers();
    }
    act(() => { first.focus(); fireEvent.keyDown(first, { key: "ArrowUp" }); });
    expect(first).toHaveAttribute("aria-current", "true");
    expect(first).toHaveFocus();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("advances through repeated arrows without repeating customer actions", async () => {
    const ensureVisible = vi.spyOn(tableVisibility, "ensureTableRowVisible");
    apiRequest.mockResolvedValue(customers);
    const onSelect = vi.fn();
    const onClose = vi.fn();
    render(<SaleInvoiceCustomerListDialog locale="es" selectedCustomerId="one" onSelect={onSelect} onClose={onClose} />);
    const search = screen.getByRole("textbox", { name: "Buscar cliente" });
    const first = await screen.findByRole("button", { name: /CLI-001 · Cliente Uno/ });
    const second = screen.getByRole("button", { name: /CLI-002 · Cliente Dos/ });
    const last = screen.getByRole("button", { name: /CLI-003 · Cliente Tres/ });

    vi.useFakeTimers();
    try {
      fireEvent.keyDown(search, { key: "ArrowDown" });
      expect(second).toHaveAttribute("aria-current", "true");
      fireEvent.keyDown(search, { key: "ArrowDown", repeat: true });
      act(() => vi.advanceTimersByTime(179));
      expect(second).toHaveAttribute("aria-current", "true");
      act(() => vi.advanceTimersByTime(1));
      expect(last).toHaveAttribute("aria-current", "true");
      expect(ensureVisible.mock.lastCall?.[0]).toHaveClass("sale-customer-table");
      expect(ensureVisible.mock.lastCall?.[1]).toBe(last);
      act(() => vi.advanceTimersByTime(70));
      expect(last).toHaveAttribute("aria-current", "true");
      fireEvent.keyUp(search, { key: "ArrowDown" });
      act(() => vi.advanceTimersByTime(500));
      expect(last).toHaveAttribute("aria-current", "true");
      fireEvent.keyDown(search, { key: "ArrowUp", isComposing: true });
      expect(last).toHaveAttribute("aria-current", "true");
      fireEvent.keyDown(search, { key: "ArrowUp" });
      expect(second).toHaveAttribute("aria-current", "true");
      expect(search).toHaveFocus();
      screen.getAllByRole("button", { name: "Cerrar" })[0].focus();
      act(() => vi.advanceTimersByTime(500));
      expect(second).toHaveAttribute("aria-current", "true");
    } finally {
      vi.useRealTimers();
    }

    for (const key of ["Enter", "Insert", "Escape"]) {
      fireEvent.keyDown(search, { key, repeat: true });
    }
    expect(onSelect).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(first).toHaveAttribute("aria-current", "false");
  });
});

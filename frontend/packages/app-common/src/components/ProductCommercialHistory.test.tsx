// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { ProductCommercialHistory } from "./ProductCommercialHistory";

vi.mock("../api/client", async (original) => ({
  ...await original<typeof import("../api/client")>(), apiRequest: vi.fn(),
}));

afterEach(() => { cleanup(); vi.useRealTimers(); vi.mocked(apiRequest).mockReset(); });

it("repeats through the current history selection and stops on keyup", async () => {
  vi.mocked(apiRequest).mockResolvedValue(["one", "two", "three", "four"].map((id) => ({
    id, source: "OFFER", type: "OFFER_PRICE", name: id, dateFrom: null, dateTo: null,
    beforePrice: null, finalPrice: null, discountPercent: null,
    recordedAt: "2026-10-01T10:00:00Z", status: "ACTIVE", initialSnapshot: false,
  })));
  render(<ProductCommercialHistory productId="product-1" productName="Producto" locale="es" />);
  fireEvent.click(screen.getByRole("button", { name: "Historial" }));
  const first = await screen.findByRole("row", { name: /one/ });
  expect(first).toHaveFocus();
  expect(first).toHaveAttribute("aria-selected", "true");
  vi.useFakeTimers();
  fireEvent.keyDown(first, { key: "ArrowDown" });
  expect(screen.getByRole("row", { name: /two/ })).toHaveFocus();
  await act(async () => { vi.advanceTimersByTime(180); });
  expect(screen.getByRole("row", { name: /three/ })).toHaveFocus();
  fireEvent.keyUp(window, { key: "ArrowDown" });
  await act(async () => { vi.advanceTimersByTime(300); });
  expect(screen.getByRole("row", { name: /three/ })).toHaveFocus();
  const last = screen.getByRole("row", { name: /four/ });
  act(() => {
    last.focus();
    fireEvent.keyDown(last, { key: "ArrowDown" });
  });
  await act(async () => { vi.advanceTimersByTime(500); });
  expect(last).toHaveFocus();
  expect(last).toHaveAttribute("aria-selected", "true");
});

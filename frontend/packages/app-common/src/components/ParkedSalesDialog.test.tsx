// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { ParkedSalesDialog } from "./ParkedSalesDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));

const request = vi.mocked(apiRequest);
const summary = { id: "parked-1", createdAt: "2026-07-21T10:00:00Z", comment: "Mesa 1", total: "12.10" };
const opened = { document: { lineas: [{ productoId: "product-1", cantidad: 1, descuento: 0 }] }, comment: "Mesa 1" };

afterEach(cleanup);
beforeEach(() => request.mockReset());

describe("ParkedSalesDialog", () => {
  it("acknowledges deletion only after the sale was restored locally", async () => {
    request
      .mockResolvedValueOnce([summary])
      .mockResolvedValueOnce(opened)
      .mockResolvedValueOnce(undefined);
    const recovered = vi.fn().mockResolvedValue(undefined);
    render(<ParkedSalesDialog token="token" locale="es" currentSale={{}} canPark onClose={vi.fn()} onParked={vi.fn()} onRecovered={recovered} />);

    fireEvent.click(await screen.findByRole("button", { name: "Recuperar" }));

    await waitFor(() => expect(recovered).toHaveBeenCalledWith(opened));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/parked-sales/parked-1", { token: "token", method: "DELETE" }));
    expect(request.mock.invocationCallOrder[1]).toBeLessThan(recovered.mock.invocationCallOrder[0]);
    expect(recovered.mock.invocationCallOrder[0]).toBeLessThan(request.mock.invocationCallOrder[2]);
  });

  it("keeps the parked sale when local restoration fails", async () => {
    request
      .mockResolvedValueOnce([summary])
      .mockResolvedValueOnce(opened);
    const recovered = vi.fn().mockRejectedValue(new Error("No se pudo reconstruir"));
    render(<ParkedSalesDialog token="token" locale="es" currentSale={{}} canPark onClose={vi.fn()} onParked={vi.fn()} onRecovered={recovered} />);

    fireEvent.click(await screen.findByRole("button", { name: "Recuperar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo reconstruir");
    expect(request).toHaveBeenCalledTimes(2);
    expect(screen.getByText("Mesa 1")).toBeInTheDocument();
  });
});

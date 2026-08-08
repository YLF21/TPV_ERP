// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import { SaleProductLabelDialog } from "./SaleProductLabelDialog";

describe("SaleProductLabelDialog", () => {
  afterEach(() => {
    cleanup();
    Reflect.deleteProperty(window, "tpvDesktop");
  });

  it("keeps rendering when Electron returns a legacy hardware configuration", async () => {
    Reflect.set(window, "tpvDesktop", {
      hardware: {
        getHardwareConfig: async () => ({ ticketPrinterName: "Ticket" }),
        listPrinters: async () => ({ ok: true, printers: [] }),
      },
    });

    render(
      <SaleProductLabelDialog
        open
        locale="es"
        storeName="Tienda"
        products={[]}
        onClose={() => undefined}
        onPrinted={() => undefined}
      />,
    );

    await act(async () => undefined);

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Ticket 58 x 40 mm")).toBeInTheDocument();
  });

  it("stays open after printing and closes only when the user requests it", async () => {
    const onClose = vi.fn();
    const onPrinted = vi.fn();
    const printProductLabel = vi.fn().mockResolvedValue({ ok: true });
    Reflect.set(window, "tpvDesktop", {
      hardware: {
        getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
        listPrinters: vi.fn().mockResolvedValue({ ok: true, printers: [] }),
        saveHardwareConfig: vi.fn().mockResolvedValue({ ok: true }),
        printProductLabel,
      },
    });

    render(
      <SaleProductLabelDialog
        open
        locale="es"
        storeName="Tienda"
        products={[{
          id: "coffee",
          code: "CAF-001",
          barcode: "8435606744034",
          name: "Café molido",
          salePrice: 10,
        }]}
        initialProductId="coffee"
        onClose={onClose}
        onPrinted={onPrinted}
      />,
    );

    const printButton = await screen.findByRole("button", { name: "Imprimir" });
    await waitFor(() => expect(printButton).toBeEnabled());
    fireEvent.click(printButton);

    await waitFor(() => expect(onPrinted).toHaveBeenCalledWith(false));
    expect(printProductLabel).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Etiqueta enviada a la impresora");
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(printButton);
    await waitFor(() => expect(printProductLabel).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

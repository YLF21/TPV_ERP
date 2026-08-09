// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import { SaleProductLabelDialog } from "./SaleProductLabelDialog";

const issuer = {
  name: "TPV ERP SL",
  taxId: "B12345678",
  address: { line1: "Calle Mayor 1", postalCode: "35001", city: "Las Palmas", province: "Las Palmas", country: "ES" },
};

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
        issuer={issuer}
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
        issuer={issuer}
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
    expect(screen.getByRole("status")).toHaveTextContent("Etiquetas enviadas a la impresora");
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(printButton);
    await waitFor(() => expect(printProductLabel).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("prints several selected products with independent EAN and copies", async () => {
    const printProductLabel = vi.fn().mockResolvedValue({ ok: true });
    Reflect.set(window, "tpvDesktop", {
      hardware: {
        getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
        listPrinters: vi.fn().mockResolvedValue({ ok: true, printers: [] }),
        saveHardwareConfig: vi.fn().mockResolvedValue({ ok: true }),
        printProductLabel,
      },
    });

    render(<SaleProductLabelDialog
      open
      locale="es"
      storeName="Tienda"
      issuer={issuer}
      products={[
        { id: "coffee", code: "CAF-001", barcode: "8435606744034", name: "Café", salePrice: 10 },
        { id: "milk", code: "LEC-001", barcode: "4006381333931", name: "Leche", salePrice: 2 },
      ]}
      initialProductId="coffee"
      onClose={() => undefined}
      onPrinted={() => undefined}
    />);

    fireEvent.click(await screen.findByRole("checkbox", { name: "Seleccionados: LEC-001" }));
    fireEvent.change(screen.getByRole("spinbutton", { name: "Copias: LEC-001" }), { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: "Imprimir" }));

    await waitFor(() => expect(printProductLabel).toHaveBeenCalledTimes(1));
    expect(printProductLabel.mock.calls[0][0]).toEqual(expect.objectContaining({
      version: 2,
      kind: "SEQUENTIAL",
      issuer: expect.objectContaining({ taxId: "B12345678" }),
      items: [
        expect.objectContaining({ id: "coffee", copies: 1 }),
        expect.objectContaining({ id: "milk", copies: 3 }),
      ],
    }));
  });

  it("loads the current offer and promotions before printing", async () => {
    const printProductLabel = vi.fn().mockResolvedValue({ ok: true });
    const loadCommercialContexts = vi.fn().mockResolvedValue([{
      productId: "coffee",
      offer: { regularPrice: 10, offerPrice: 8, discountPercent: 20, endDate: "2026-08-31" },
      promotions: [{
        id: "promo-1",
        name: "3x2 Café",
        type: "BUY_X_PAY_Y",
        buyQuantity: 3,
        payQuantity: 2,
        buyXPayYMode: "SAME_PRODUCT",
        endDate: "2026-08-20",
      }],
    }]);
    Reflect.set(window, "tpvDesktop", {
      hardware: {
        getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
        listPrinters: vi.fn().mockResolvedValue({ ok: true, printers: [] }),
        saveHardwareConfig: vi.fn().mockResolvedValue({ ok: true }),
        printProductLabel,
      },
    });

    render(<SaleProductLabelDialog
      open
      locale="es"
      storeName="Tienda"
      issuer={issuer}
      products={[{ id: "coffee", code: "CAF-001", barcode: "8435606744034", name: "Café", salePrice: 10 }]}
      loadCommercialContexts={loadCommercialContexts}
      initialProductId="coffee"
      onClose={() => undefined}
      onPrinted={() => undefined}
    />);

    fireEvent.click(await screen.findByRole("button", { name: "Imprimir" }));

    await waitFor(() => expect(printProductLabel).toHaveBeenCalledTimes(1));
    expect(loadCommercialContexts).toHaveBeenCalledWith(["coffee"]);
    expect(printProductLabel.mock.calls[0][0].items[0].product.commercial).toEqual({
      badge: "OFERTA + PROMO",
      offer: {
        regularPrice: 10,
        offerPrice: 8,
        discountPercent: 20,
        validUntil: "hasta 31/08",
      },
      promotionLines: ["3x2 · hasta 20/08"],
    });
  });

  it("opens the A4 composer and quick-places every selected label", async () => {
    const printProductLabel = vi.fn().mockResolvedValue({ ok: true });
    const a4Config = {
      ...defaultHardwareConfig,
      a4PrinterName: "A4",
      productLabelProfiles: [{
        ...defaultHardwareConfig.productLabelProfiles[0],
        destination: "A4" as const,
        printerName: "A4",
      }],
    };
    Reflect.set(window, "tpvDesktop", {
      hardware: {
        getHardwareConfig: vi.fn().mockResolvedValue(a4Config),
        listPrinters: vi.fn().mockResolvedValue({ ok: true, printers: [] }),
        saveHardwareConfig: vi.fn().mockResolvedValue({ ok: true }),
        printProductLabel,
      },
    });

    render(<SaleProductLabelDialog
      open
      locale="es"
      storeName="Tienda"
      issuer={issuer}
      products={[
        { id: "coffee", code: "CAF-001", barcode: "8435606744034", name: "Café", salePrice: 10 },
        { id: "milk", code: "LEC-001", barcode: "4006381333931", name: "Leche", salePrice: 2 },
      ]}
      initialProductId="coffee"
      onClose={() => undefined}
      onPrinted={() => undefined}
    />);

    fireEvent.click(await screen.findByRole("checkbox", { name: "Seleccionados: LEC-001" }));
    fireEvent.click(screen.getByRole("button", { name: "Diseñar hoja A4" }));
    fireEvent.click(await screen.findByRole("button", { name: "Colocación rápida" }));
    const zoom = screen.getByRole("slider", { name: "Zoom" });
    expect(zoom).toHaveValue("100");
    fireEvent.wheel(screen.getByLabelText("Vista previa de la hoja A4"), { deltaY: -100 });
    expect(zoom).toHaveValue("110");
    fireEvent.click(screen.getByRole("button", { name: "Restablecer zoom al 100 %" }));
    expect(zoom).toHaveValue("100");
    const printButton = screen.getByRole("button", { name: "Imprimir" });
    await waitFor(() => expect(printButton).toBeEnabled());
    fireEvent.click(printButton);

    await waitFor(() => expect(printProductLabel).toHaveBeenCalledTimes(1));
    expect(printProductLabel.mock.calls[0][0]).toEqual(expect.objectContaining({
      version: 2,
      kind: "A4_LAYOUT",
      pages: [expect.objectContaining({ placements: expect.arrayContaining([
        expect.objectContaining({ itemId: "coffee" }),
        expect.objectContaining({ itemId: "milk" }),
      ]) })],
    }));
  });
});

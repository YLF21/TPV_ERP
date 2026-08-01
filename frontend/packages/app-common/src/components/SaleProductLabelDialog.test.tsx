// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { SaleProductLabelDialog } from "./SaleProductLabelDialog";

describe("SaleProductLabelDialog", () => {
  afterEach(() => {
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
});

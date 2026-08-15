// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type apiRequest, type UserSession } from "@tpverp/app-common";
import { StoreDocumentPrintSettingsScreen } from "./StoreDocumentPrintSettingsScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "token",
};

afterEach(cleanup);

describe("StoreDocumentPrintSettingsScreen", () => {
  it("shows the current store and saves four independent observations", async () => {
    const initial = {
      storeId: "store-1",
      logo: null,
      ticketObservations: "Gracias",
      invoiceObservations: "Factura",
      deliveryNoteObservations: "Albaran",
      voucherObservations: "Vale",
      ticketStyle: "PRINCIPAL",
      ticketTemplateOrigin: "INTEGRATED",
      showStoreName: true,
    };
    const request = vi.fn(async (_path: string, options?: { method?: string; body?: unknown }) =>
      options?.method === "PUT"
        ? { ...initial, ...(options.body as object) }
        : initial);

    render(<StoreDocumentPrintSettingsScreen
      session={session}
      storeName="Tienda Centro"
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    expect(await screen.findByText("Tienda Centro")).toBeTruthy();
    const textareas = screen.getAllByRole("textbox");
    expect(textareas).toHaveLength(4);
    const [ticket, invoice, deliveryNote, voucher] = textareas as HTMLTextAreaElement[];
    fireEvent.change(ticket, { target: { value: "Ticket actualizado" } });
    fireEvent.change(invoice, { target: { value: "Factura actualizada" } });
    fireEvent.change(deliveryNote, { target: { value: "Albaran actualizado" } });
    fireEvent.change(voucher, { target: { value: "Vale actualizado" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar observaciones" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/store-document-print-configuration/observations",
      {
        method: "PUT",
        token: "token",
        body: {
          ticket: "Ticket actualizado",
          invoice: "Factura actualizada",
          deliveryNote: "Albaran actualizado",
          voucher: "Vale actualizado",
        },
      },
    ));
  });

  it("hides the store name using the persisted store setting", async () => {
    const initial = {
      storeId: "store-1",
      logo: null,
      ticketObservations: null,
      invoiceObservations: null,
      deliveryNoteObservations: null,
      voucherObservations: null,
      ticketStyle: "PRINCIPAL",
      ticketTemplateOrigin: "INTEGRATED",
      showStoreName: true,
    };
    const request = vi.fn(async (_path: string, options?: { method?: string; body?: unknown }) =>
      options?.method === "PUT" ? { ...initial, showStoreName: false } : initial);

    render(<StoreDocumentPrintSettingsScreen
      session={session}
      storeName="Tienda Centro"
      t={createTranslator("es")}
      request={request as unknown as typeof apiRequest}
    />);

    fireEvent.click(await screen.findByRole("button", { name: "Ocultar nombre de tienda" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/store-document-print-configuration/store-name-visibility",
      { method: "PUT", token: "token", body: { showStoreName: false } },
    ));
    expect(await screen.findByText("Se muestra solo el nombre de empresa")).toBeTruthy();
  });
});

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
  it("shows the current store and saves three independent observations", async () => {
    const initial = {
      storeId: "store-1",
      logo: null,
      ticketObservations: "Gracias",
      invoiceObservations: "Factura",
      deliveryNoteObservations: "Albaran",
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
    expect(textareas).toHaveLength(3);
    const [ticket, invoice, deliveryNote] = textareas as HTMLTextAreaElement[];
    fireEvent.change(ticket, { target: { value: "Ticket actualizado" } });
    fireEvent.change(invoice, { target: { value: "Factura actualizada" } });
    fireEvent.change(deliveryNote, { target: { value: "Albaran actualizado" } });
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
        },
      },
    ));
  });
});

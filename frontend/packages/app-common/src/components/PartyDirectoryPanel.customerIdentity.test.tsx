// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { PartyDirectoryPanel, type CustomerView } from "./PartyDirectoryPanel";
import type { UserSession } from "../types";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const session: UserSession = { username: "test", displayName: "Test", accessToken: "test-token", permissions: ["CUSTOMERS_READ", "CUSTOMERS_WRITE", "VENTA", "SUPPLIERS_WRITE"] };
const customer: CustomerView = { id: "customer-1", clientId: "C-001", fiscalName: "Cliente existente", documentType: "CIF", documentNumber: "B12345674", isMember: false, active: true };

beforeEach(() => {
  request.mockReset().mockImplementation(async (path) => {
    if (path === "/customers") return [customer];
    if (path === "/suppliers") return [{ ...customer, supplierId: "P-001", legalName: "Proveedor existente" }];
    if (path.startsWith("/document-reports/")) return { items: [], hasMore: false };
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("customer identity forms", () => {
  it.each(["venta", "gestion"] as const)("preserves the complete form after a duplicate response in %s", async (app) => {
    request.mockImplementation(async (path, options) => {
      if (path === "/customers" && options?.method === "POST") throw new ApiError("Duplicate", 409, { code: "CUSTOMER_DOCUMENT_DUPLICATE" });
      if (path === "/customers") return [customer];
      return [];
    });
    render(<PartyDirectoryPanel app={app} kind="customers" locale="es" session={session} />);
    await screen.findByText("Cliente existente");
    fireEvent.click(screen.getByRole("button", { name: /Nuevo cliente/ }));
    const dialog = screen.getByRole("dialog");
    const name = within(dialog).getByLabelText("Nombre o razón social");
    const number = within(dialog).getByLabelText("Número de documento");
    fireEvent.change(name, { target: { value: "Nombre introducido" } });
    fireEvent.change(number, { target: { value: "B12345674" } });
    fireEvent.change(within(dialog).getByLabelText("Notas"), { target: { value: "Conservar estas notas" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(number).toHaveAttribute("aria-invalid", "true"));
    expect(within(dialog).getAllByText(createTranslator("es")("party.customerIdentity.duplicate"))).toHaveLength(2);
    expect(name).toHaveValue("Nombre introducido");
    expect(number).toHaveValue("B12345674");
    expect(within(dialog).getByLabelText("Notas")).toHaveValue("Conservar estas notas");
    expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled();

    fireEvent.click(within(dialog).getByRole("button", { name: "Tipo de documento" }));
    fireEvent.click(screen.getByRole("option", { name: "DNI" }));
    expect(number).toHaveAttribute("aria-invalid", "false");
  });

  it("edits legacy CIF customers as NIF without changing the document number", async () => {
    render(<PartyDirectoryPanel kind="customers" locale="es" session={session} />);
    fireEvent.doubleClick((await screen.findByText("Cliente existente")).closest("[role=row]")!);
    await screen.findByRole("dialog", { name: "Documentos del cliente" });
    fireEvent.keyDown(window, { key: "F7" });
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("button", { name: "Tipo de documento" })).toHaveTextContent("NIF");
    expect(within(dialog).getByLabelText("Número de documento")).toHaveValue("B12345674");
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/customer-1", expect.objectContaining({
      method: "PUT", body: expect.objectContaining({ documentType: "NIF", documentNumber: "B12345674" }),
    })));
  });

  it("retains the supplier options, legacy CIF payload and existing error behavior", async () => {
    request.mockImplementation(async (path, options) => {
      if (path === "/suppliers") return [{ ...customer, supplierId: "P-001", legalName: "Proveedor existente" }];
      if (options?.method === "PUT") throw new ApiError("Supplier-specific error", 400, { code: "CUSTOMER_DOCUMENT_INVALID" });
      return [];
    });
    render(<PartyDirectoryPanel kind="suppliers" locale="es" session={session} />);
    fireEvent.click((await screen.findByText("Proveedor existente")).closest("[role=row]")!);
    const dialog = screen.getByRole("dialog");
    const type = within(dialog).getByRole("button", { name: "Tipo de documento" });
    expect(type).toHaveTextContent("CIF");
    fireEvent.click(type);
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["NIF", "CIF", "NIE", "PASAPORTE", "OTRO"]);
    fireEvent.click(screen.getByRole("option", { name: "CIF" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await screen.findByText("Supplier-specific error");
    expect(request).toHaveBeenCalledWith("/suppliers/customer-1", expect.objectContaining({
      body: expect.objectContaining({ documentType: "CIF" }),
    }));
    expect(within(dialog).getByLabelText("Número de documento")).toHaveAttribute("aria-invalid", "false");
  });
});

// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { PartyDirectoryPanel, type SupplierView } from "./PartyDirectoryPanel";
import type { UserSession } from "../types";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const supplier: SupplierView = {
  id: "supplier-1", supplierId: "P-001", legalName: "Distribuciones existentes", tradeName: "Marca existente",
  documentType: "CIF", documentNumber: "B12345674", active: true,
  address: { address: "CALLE MAYOR 1", postalCode: "35001", city: "Las Palmas", province: "Las Palmas", country: "ES" },
  phone: "928111222", email: "compras@proveedor.es", notes: "Entrega por la mañana", version: 4,
};
const writableSession: UserSession = {
  username: "supplier-editor", displayName: "Editor", accessToken: "test",
  permissions: ["SUPPLIERS_READ", "SUPPLIERS_WRITE"],
};

beforeEach(() => {
  localStorage.clear();
  request.mockReset().mockImplementation(async (path, options) => {
    if (path === "/suppliers/supplier-1" && options?.method === "PUT") return { ...supplier, ...(options.body as object) };
    if (path === "/suppliers") return [supplier];
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

async function openDirectory(session: UserSession = writableSession) {
  render(<PartyDirectoryPanel app="venta" kind="suppliers" locale="es" session={session} />);
  await screen.findByText(supplier.legalName);
}

async function editSupplier(session: UserSession = writableSession) {
  await openDirectory(session);
  fireEvent.click(screen.getByText(supplier.legalName).closest("[role=row]")!, { detail: 1 });
  const dialog = screen.getByRole("dialog");
  await waitFor(() => expect(within(dialog).getByLabelText("Razón social")).toBeInTheDocument());
  return dialog;
}

describe("Venta supplier editor", () => {
  it("edits the existing CIF supplier and sends only the supplier payload", async () => {
    const dialog = await editSupplier();
    expect(dialog.querySelector(".party-supplier-editor--venta")).toBeInTheDocument();
    expect(dialog.querySelector(".party-editor--venta")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Tipo de documento" })).toHaveTextContent("CIF");
    expect(within(dialog).getByLabelText("Razón social")).toHaveValue(supplier.legalName);
    expect(within(dialog).getByLabelText("Nombre comercial")).toHaveValue(supplier.tradeName);
    expect(within(dialog).getByLabelText("Teléfono")).toHaveValue(supplier.phone);
    expect(within(dialog).getByLabelText("Email")).toHaveValue(supplier.email);
    expect(within(dialog).getByLabelText("Notas")).toHaveValue(supplier.notes);

    fireEvent.change(within(dialog).getByLabelText("Razón social"), { target: { value: "Distribuciones actualizadas" } });
    fireEvent.change(within(dialog).getByLabelText("Nombre comercial"), { target: { value: "Marca nueva" } });
    fireEvent.change(within(dialog).getByLabelText("Teléfono"), { target: { value: "928333444" } });
    fireEvent.change(within(dialog).getByLabelText("Notas"), { target: { value: "Llamar antes de entregar" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith("/suppliers/supplier-1", expect.objectContaining({
      method: "PUT",
      body: expect.objectContaining({
        legalName: "Distribuciones actualizadas", tradeName: "Marca nueva", documentType: "CIF",
        documentNumber: supplier.documentNumber, phone: "928333444", email: supplier.email,
        notes: "Llamar antes de entregar", address: { ...supplier.address, city: "LAS PALMAS", province: "LAS PALMAS" },
      }),
    })));
    const [, options] = request.mock.calls.find(([path, callOptions]) => path === "/suppliers/supplier-1" && callOptions?.method === "PUT")!;
    expect(options?.body).not.toHaveProperty("isMember");
    expect(options?.body).not.toHaveProperty("addresses");
  });

  it("keeps a read-only supplier form disabled and hides save", async () => {
    const dialog = await editSupplier({ ...writableSession, permissions: ["SUPPLIERS_READ"] });
    expect(within(dialog).getByLabelText("Razón social")).toBeDisabled();
    expect(within(dialog).getByLabelText("Nombre comercial")).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Tipo de documento" })).toBeDisabled();
    expect(within(dialog).getByLabelText("Teléfono")).toBeDisabled();
    expect(within(dialog).getByLabelText("Email")).toBeDisabled();
    expect(within(dialog).getByLabelText("Notas")).toBeDisabled();
    expect(within(dialog).queryByRole("button", { name: "Guardar" })).not.toBeInTheDocument();
    expect(request.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
  });

  it("asks for confirmation before discarding supplier edits", async () => {
    const dialog = await editSupplier();
    fireEvent.change(within(dialog).getByLabelText("Nombre comercial"), { target: { value: "Cambio sin guardar" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(dialog).toBeInTheDocument();
    expect(request.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
  });

  it("starts a new supplier with document fields before names and focuses the document number", async () => {
    await openDirectory();
    fireEvent.click(screen.getByRole("button", { name: "Nuevo proveedor F8" }));
    const dialog = screen.getByRole("dialog");
    const documentNumber = within(dialog).getByLabelText("Número de documento");
    const legalName = within(dialog).getByLabelText("Razón social");
    const tradeName = within(dialog).getByLabelText("Nombre comercial");
    expect(documentNumber).toHaveFocus();
    expect(documentNumber.compareDocumentPosition(legalName) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(documentNumber.compareDocumentPosition(tradeName) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Tipo de documento" })).toHaveTextContent("NIF");
    expect(request.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
  });
});

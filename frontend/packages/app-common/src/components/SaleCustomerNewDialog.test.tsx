// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { SaleCustomerCreateDialog } from "./SaleCustomerCreateDialog";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const session: UserSession = { username: "venta", displayName: "Venta", accessToken: "token", permissions: ["VENTA"] };

function openDialog() {
  const onCancel = vi.fn();
  const onCreated = vi.fn();
  render(<SaleCustomerCreateDialog locale="es" session={session} onCancel={onCancel} onCreated={onCreated} />);
  return { dialog: screen.getByRole("dialog", { name: "Nuevo cliente" }), onCancel, onCreated };
}

function fillIdentity(dialog: HTMLElement) {
  fireEvent.change(within(dialog).getByLabelText("Número de documento"), { target: { value: "87654321X" } });
  fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Cliente de venta" } });
}

function addAddress(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
  fireEvent.click(screen.getByRole("option", { name: "Añadir nueva dirección" }));
}

function save(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
}

function postCalls() {
  return request.mock.calls.filter(([path, options]) => path === "/customers" && options?.method === "POST");
}

beforeEach(() => {
  request.mockReset().mockImplementation(async (path, options) => {
    if (path === "/commercial-contact-channels") return [] as never;
    if (path === "/customers/central-lookup") throw new ApiError("not found", 404, { code: "CUSTOMER_CENTRAL_NOT_FOUND" });
    if (path === "/customers" && options?.method === "POST") return { id: "created", ...(options.body as object) } as never;
    if (path.startsWith("/party-addresses/")) return { available: false, items: [] } as never;
    throw new Error(`Unexpected request: ${path}`);
  });
});
afterEach(cleanup);

describe("Nuevo cliente desde Ventas", () => {
  it("starts with the document first and focused, credit and membership off, and no legacy reuse help", () => {
    const { dialog } = openDialog();
    const document = within(dialog).getByLabelText("Número de documento");
    const name = within(dialog).getByLabelText("Nombre o razón social");
    expect(document).toHaveFocus();
    expect(document.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(document.closest(".party-form-section--identity")).toBeInTheDocument();
    expect(within(dialog).getByRole("checkbox", { name: "Permitir ventas a crédito" })).not.toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "Crear miembro" })).not.toBeChecked();
    expect(within(dialog).queryByText(/¿Ya existe en otra tienda/)).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Validar" })).toBeDisabled();
  });

  it("posts both contacts and uses only the default for the legacy contact fields", async () => {
    const { dialog, onCreated } = openDialog(); fillIdentity(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE PRIMERA" } });
    fireEvent.change(within(dialog).getByLabelText("Teléfono"), { target: { value: "600111222" } });
    addAddress(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE SEGUNDA" } });
    fireEvent.change(within(dialog).getByLabelText("Teléfono"), { target: { value: "600333444" } });
    save(dialog);
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect(postCalls()[0][1]).toEqual(expect.objectContaining({ token: "token", body: expect.objectContaining({
      fiscalName: "Cliente de venta", documentNumber: "87654321X", isMember: false, creditEnabled: false,
      address: expect.objectContaining({ address: "CALLE PRIMERA" }), phone: "600111222",
      addresses: [
        expect.objectContaining({ address: expect.objectContaining({ address: "CALLE PRIMERA" }), phone: "600111222" }),
        expect.objectContaining({ address: expect.objectContaining({ address: "CALLE SEGUNDA" }), phone: "600333444" }),
      ],
    }) }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ id: "created" })));
  });

  it("sends direct membership only when Crear miembro is selected", async () => {
    const { dialog } = openDialog(); fillIdentity(dialog);
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "Crear miembro" }));
    save(dialog);
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect(postCalls()[0][1]?.body).toEqual(expect.objectContaining({ isMember: true, numMember: null, creditEnabled: false }));
    expect(request.mock.calls.some(([path]) => path.endsWith("/member/activate"))).toBe(false);
  });

  it("keeps the original default when editing the secondary, then promotes the secondary", async () => {
    const { dialog } = openDialog(); fillIdentity(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE PRIMERA" } });
    addAddress(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE SEGUNDA" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: /Predeterminada · CALLE PRIMERA/ }));
    expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE PRIMERA");
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: /CALLE SEGUNDA/ }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Convertir a predeterminado" }));
    save(dialog);
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    const body = postCalls()[0][1]?.body as { address: { address: string }; addresses: { address: { address: string } }[] };
    expect(body.address.address).toBe("CALLE SEGUNDA");
    expect(body.addresses.map(entry => entry.address.address)).toEqual(["CALLE SEGUNDA", "CALLE PRIMERA"]);
  });

  it("removes only a selected secondary address", async () => {
    const { dialog } = openDialog(); fillIdentity(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE PRIMERA" } });
    addAddress(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE SEGUNDA" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar dirección" }));
    expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE PRIMERA");
    save(dialog);
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect((postCalls()[0][1]?.body as { addresses: unknown[] }).addresses).toEqual([
      expect.objectContaining({ address: expect.objectContaining({ address: "CALLE PRIMERA" }) }),
    ]);
  });

  it("shows the existing customer and Aceptar closes the whole editor without adopting or posting", async () => {
    request.mockImplementation(async (path, options) => {
      if (path === "/commercial-contact-channels") return [] as never;
      if (path === "/customers/central-lookup") return { centralCode: "C-009999", documentNumber: "87654321X" } as never;
      throw new Error(`Unexpected request: ${path} ${options?.method ?? "GET"}`);
    });
    const { dialog, onCancel, onCreated } = openDialog(); fillIdentity(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "Validar" }));
    const notice = await screen.findByRole("alertdialog", { name: "Cliente existente" });
    expect(notice).toHaveTextContent("C-009999");
    fireEvent.click(within(notice).getByRole("button", { name: "Aceptar" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onCreated).not.toHaveBeenCalled();
    expect(postCalls()).toHaveLength(0);
    expect(request.mock.calls.some(([path]) => path === "/customers/adopt-central")).toBe(false);
  });

  it("permits saving after an optional lookup returns the known not-found code", async () => {
    const { dialog, onCreated } = openDialog(); fillIdentity(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "Validar" }));
    expect(await screen.findByRole("status")).toHaveTextContent(createTranslator("es")("party.customerEditor.available"));
    save(dialog);
    await waitFor(() => expect(postCalls()).toHaveLength(1));
    expect(onCreated).toHaveBeenCalled();
  });

  it("selects a secondary with invalid email and blocks POST", async () => {
    const { dialog } = openDialog(); fillIdentity(dialog);
    addAddress(dialog);
    fireEvent.change(within(dialog).getByLabelText("Email"), { target: { value: "no-es-email" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: /Predeterminada/ }));
    save(dialog);
    expect(within(dialog).getByLabelText(/^Email/)).toHaveValue("no-es-email");
    expect(within(dialog).getByLabelText(/^Email/)).toHaveAttribute("aria-invalid", "true");
    expect(postCalls()).toHaveLength(0);
  });

  it("rejects an invalid country supplied to the secondary by address lookup", async () => {
    request.mockImplementation(async (path, options) => {
      if (path === "/commercial-contact-channels") return [] as never;
      if (path.startsWith("/party-addresses/suggestions")) return { available: true, items: [{ id: "street-1", type: "street", label: "CALLE SEGUNDA" }] } as never;
      if (path.startsWith("/party-addresses/resolve")) return { available: true, address: {
        address: "CALLE SEGUNDA", postalCode: "35120", city: "MOGÁN", province: "LAS PALMAS", country: "ESP",
      } } as never;
      if (path === "/customers" && options?.method === "POST") throw new Error("Invalid address must not be posted");
      throw new Error(`Unexpected request: ${path}`);
    });
    const { dialog } = openDialog(); fillIdentity(dialog);
    addAddress(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE" } });
    fireEvent.click(await screen.findByRole("option", { name: "CALLE SEGUNDA" }));
    await waitFor(() => expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE SEGUNDA"));
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: /Predeterminada/ }));
    save(dialog);
    expect(within(dialog).getByRole("combobox", { name: "País (ISO)" })).toHaveAttribute("aria-invalid", "true");
    expect(postCalls()).toHaveLength(0);
  });
});

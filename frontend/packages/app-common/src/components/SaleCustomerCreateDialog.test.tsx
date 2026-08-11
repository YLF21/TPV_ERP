// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { SaleCustomerCreateDialog, canCreateSaleCustomer } from "./SaleCustomerCreateDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);

const session: UserSession = {
  username: "venta",
  displayName: "Venta",
  accessToken: "token",
  permissions: ["VENTA"],
};

describe("SaleCustomerCreateDialog", () => {
  beforeEach(() => {
    request.mockReset();
    request.mockImplementation(async (path) => {
      if (path === "/commercial-contact-channels") return [] as never;
      if (path === "/customers") return {
        id: "customer-created",
        clientId: "C-104",
        fiscalName: "Cliente creado",
        documentType: "NIF",
        documentNumber: "12345678Z",
        isMember: false,
        active: true,
      } as never;
      throw new Error(`Unexpected request: ${path}`);
    });
  });

  afterEach(cleanup);

  it("allows only customer-writing sale roles to create customers", () => {
    expect(canCreateSaleCustomer(["VENTA"])).toBe(true);
    expect(canCreateSaleCustomer(["CUSTOMERS_WRITE"])).toBe(true);
    expect(canCreateSaleCustomer(["GESTION_CLIENTE_PROVEEDOR"])).toBe(true);
    expect(canCreateSaleCustomer(["CUSTOMERS_READ"])).toBe(false);
  });

  it("validates and creates a complete customer through the real customer endpoint", async () => {
    const onCreated = vi.fn();
    render(<SaleCustomerCreateDialog
      locale="es"
      session={session}
      onCancel={vi.fn()}
      onCreated={onCreated}
    />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo cliente" });
    expect(dialog).toBeVisible();
    fireEvent.submit(dialog.querySelector("form")!);
    expect(screen.getByText(/Revisa los campos obligatorios/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Nombre o razón social/), {
      target: { value: "Cliente creado" },
    });
    fireEvent.change(screen.getByLabelText(/Número de documento/), {
      target: { value: "12345678Z" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers", expect.objectContaining({
      method: "POST",
      token: "token",
      body: expect.objectContaining({
        fiscalName: "Cliente creado",
        documentNumber: "12345678Z",
        creditEnabled: true,
        unlimitedCredit: true,
      }),
    })));
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ id: "customer-created" }));
  });

  it("loads and updates the selected customer through the real customer endpoint", async () => {
    const existing = {
      id: "customer-1",
      clientId: "C-001-000001",
      fiscalName: "Cliente original",
      documentType: "NIF",
      documentNumber: "X5806991C",
      address: null,
      phone: null,
      email: null,
      notes: null,
      discount: 0,
      isMember: true,
      active: true,
      creditEnabled: true,
      creditLimit: null,
      paymentTermDays: 30,
      creditBlocked: false,
      blockOnOverdue: false,
    };
    const onCreated = vi.fn();
    request.mockImplementation(async (path, options) => {
      if (path === "/commercial-contact-channels") return [] as never;
      if (path === "/customers/customer-1" && options?.method === "PUT") {
        return { ...existing, fiscalName: "Cliente actualizado" } as never;
      }
      if (path === "/customers/customer-1") return existing as never;
      throw new Error(`Unexpected request: ${path}`);
    });

    render(<SaleCustomerCreateDialog
      locale="es"
      session={{ ...session, permissions: ["CUSTOMERS_WRITE"] }}
      customerId="customer-1"
      onCancel={vi.fn()}
      onCreated={onCreated}
    />);

    expect(await screen.findByRole("dialog", { name: "Modificar cliente" })).toBeVisible();
    const name = await screen.findByLabelText(/Nombre o razón social/);
    await waitFor(() => expect(name).toHaveValue("Cliente original"));
    fireEvent.change(name, { target: { value: "Cliente actualizado" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/customer-1", expect.objectContaining({
      method: "PUT",
      token: "token",
      body: expect.objectContaining({
        fiscalName: "Cliente actualizado",
        documentNumber: "X5806991C",
        isMember: true,
      }),
    })));
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({
      id: "customer-1",
      fiscalName: "Cliente actualizado",
    }));
  });
});

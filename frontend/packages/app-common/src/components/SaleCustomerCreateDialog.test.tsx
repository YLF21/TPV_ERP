// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { SaleCustomerCreateDialog, canCreateSaleCustomer } from "./SaleCustomerCreateDialog";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
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

  it.each(["es", "en", "zh"] as const)("offers the agreed customer document types in %s and submits the passport alias without format checks", async (locale) => {
    const t = createTranslator(locale);
    const onCreated = vi.fn();
    render(<SaleCustomerCreateDialog locale={locale} session={session} onCancel={vi.fn()} onCreated={onCreated} />);
    fireEvent.click(screen.getByRole("button", { name: t("party.field.documentType") }));
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["NIE", "DNI", "NIF", t("party.documentType.passportOther")]);
    fireEvent.click(screen.getByRole("option", { name: t("party.documentType.passportOther") }));
    fireEvent.change(screen.getByLabelText(t("party.field.fiscalName")), { target: { value: "Cliente internacional" } });
    fireEvent.change(screen.getByLabelText(t("party.field.documentNumber")), { target: { value: "AA / 99-自由" } });
    fireEvent.click(screen.getByRole("button", { name: t("common.save") }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers", expect.objectContaining({
      body: expect.objectContaining({ documentType: "PASAPORTE", documentNumber: "AA / 99-自由" }),
    })));
    expect(onCreated).toHaveBeenCalledTimes(1);
  });

  it("selects DNI using the existing keyboard dropdown", async () => {
    render(<SaleCustomerCreateDialog locale="es" session={session} onCancel={vi.fn()} onCreated={vi.fn()} />);
    const select = screen.getByRole("button", { name: "Tipo de documento" });
    fireEvent.keyDown(select, { key: "ArrowDown" });
    fireEvent.keyDown(screen.getByRole("option", { name: "NIF" }), { key: "Home" });
    fireEvent.keyDown(screen.getByRole("option", { name: "NIE" }), { key: "ArrowDown" });
    fireEvent.keyDown(screen.getByRole("option", { name: "DNI" }), { key: "Enter" });
    expect(select).toHaveTextContent("DNI");
    expect(select).toHaveFocus();
  });

  it.each([
    ["CUSTOMER_DOCUMENT_INVALID", "invalid", true],
    ["CUSTOMER_DOCUMENT_DUPLICATE", "duplicate", true],
    ["CUSTOMER_IDENTITY_SAAS_UNAVAILABLE", "unavailable", false],
    ["CUSTOMER_IDENTITY_CONFLICT", "conflict", false],
  ] as const)("shows %s and preserves the form for retry", async (code, key, invalidField) => {
    request.mockImplementation(async (path, options) => {
      if (path === "/commercial-contact-channels") return [];
      if (options?.method === "POST") throw new ApiError("opaque backend message", 409, { code });
      throw new Error(`Unexpected request: ${path}`);
    });
    const onCreated = vi.fn();
    render(<SaleCustomerCreateDialog locale="es" session={session} onCancel={vi.fn()} onCreated={onCreated} />);
    const name = screen.getByLabelText(/Nombre o razón social/);
    const number = screen.getByLabelText(/Número de documento/);
    const phone = screen.getByLabelText("Teléfono");
    fireEvent.change(name, { target: { value: "Cliente pendiente de guardar" } });
    fireEvent.change(number, { target: { value: "12345678A" } });
    fireEvent.change(phone, { target: { value: "600000001" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    const message = createTranslator("es")(`party.customerIdentity.${key}`);
    await waitFor(() => expect(screen.getAllByText(message).length).toBeGreaterThan(0));
    expect(screen.queryByText("opaque backend message")).not.toBeInTheDocument();
    expect(number).toHaveAttribute("aria-invalid", String(invalidField));
    expect(number).toHaveValue("12345678A");
    expect(name).toHaveValue("Cliente pendiente de guardar");
    expect(phone).toHaveValue("600000001");
    expect(onCreated).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Guardar" })).toBeEnabled();

    fireEvent.change(number, { target: { value: "12345678Z" } });
    expect(number).toHaveAttribute("aria-invalid", "false");
    request.mockResolvedValueOnce({ id: "customer-retry" } as never);
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: "customer-retry" }));
  });
});

// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { PartyDirectoryPanel, type CustomerView } from "./PartyDirectoryPanel";
import type { UserSession } from "../types";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const session: UserSession = { username: "test", displayName: "Test", accessToken: "test-token", permissions: ["CUSTOMERS_READ", "CUSTOMERS_WRITE", "VENTA"] };
const customer: CustomerView = { id: "customer-1", clientId: "C-001", fiscalName: "Cliente de prueba", documentType: "NIF", documentNumber: "12345678Z", isMember: false, active: true };
beforeEach(() => {
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path === "/customers") return [customer];
    if (path.startsWith("/customers/management/page")) return { items: [customer], hasMore: false };
    if (path === "/suppliers") return [{ ...customer, supplierId: "P-001", legalName: "Proveedor de prueba" }];
    if (path.startsWith("/customer-document-reports/saas/customer-1/")) return { localCustomerId: customer.id, customer: { id: "central-customer" }, coverage: "RECEIVED_V2_ONLY", items: [], hasMore: false };
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Customer directory document entry", () => {
  it.each(["venta", "gestion"] as const)("opens history first in %s, edits with F7 and returns to the same tab", async (app) => {
    render(<PartyDirectoryPanel app={app} kind="customers" locale="es" session={session} allowSafeRetirement={app === "gestion"} />);
    const row = (await screen.findByText("Cliente de prueba")).closest("[role=row]") as HTMLElement;
    row.focus();
    fireEvent.click(row, { detail: 1 });
    expect(row).toHaveClass("selected"); expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    fireEvent.doubleClick(row);
    await screen.findByRole("dialog", { name: "Documentos del cliente" });
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(`/ui/table-preferences/${app}/customers.documents`, expect.objectContaining({ token: "test-token" })));
    expect(screen.queryByRole("textbox", { name: "Nombre fiscal" })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "F3" });
    await screen.findByText("No hay documentos de este tipo recibidos en SaaS para este cliente.");
    fireEvent.keyDown(window, { key: "F7" });
    const name = await screen.findByDisplayValue("Cliente de prueba");
    expect(name).toHaveFocus();
    expect(screen.queryByRole("dialog", { name: "Documentos del cliente" })).not.toBeInTheDocument();
    const editDialog = screen.getByRole("dialog");
    fireEvent.click(within(editDialog).getByRole("button", { name: "Cancelar" }));
    await screen.findByRole("dialog", { name: "Documentos del cliente" });
    expect(screen.getByRole("tab", { name: "Albaranes F3" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); expect(row).toHaveFocus();
    expect(vi.mocked(apiRequest).mock.calls.every(([, options]) => !options?.body && (!options?.method || options.method === "GET"))).toBe(true);
  });

  it("supports keyboard activation of a customer without editing", async () => {
    render(<PartyDirectoryPanel kind="customers" locale="es" session={session} />);
    const row = (await screen.findByText("Cliente de prueba")).closest("[role=row]")!;
    fireEvent.click(row, { detail: 0 });
    await screen.findByRole("dialog", { name: "Documentos del cliente" });
    expect(screen.queryByDisplayValue("Cliente de prueba")).not.toBeInTheDocument();
  });

  it("refreshes the customer name after saving F7 and restores focus to the refreshed directory", async () => {
    let saved = false;
    vi.mocked(apiRequest).mockImplementation(async (path, options) => {
      if (path === "/customers/customer-1" && options?.method === "PUT") { saved = true; return customer; }
      if (path === "/customers") return [{ ...customer, fiscalName: saved ? "Cliente actualizado" : customer.fiscalName }];
      if (path.startsWith("/customer-document-reports/saas/customer-1/")) return { localCustomerId: customer.id, customer: { id: "central-customer" }, coverage: "RECEIVED_V2_ONLY", items: [], hasMore: false };
      return [];
    });
    render(<PartyDirectoryPanel kind="customers" locale="es" session={session} />);
    fireEvent.doubleClick((await screen.findByText("Cliente de prueba")).closest("[role=row]")!);
    await screen.findByRole("dialog", { name: "Documentos del cliente" });
    fireEvent.keyDown(window, { key: "F7" });
    fireEvent.change(await screen.findByDisplayValue("Cliente de prueba"), { target: { value: "Cliente actualizado" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    const history = await screen.findByRole("dialog", { name: "Documentos del cliente" });
    await waitFor(() => expect(history).toHaveTextContent("Cliente actualizado"));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByText("Cliente actualizado").closest("[role=row]")).toHaveFocus();
    expect(vi.mocked(apiRequest).mock.calls.filter(([, options]) => options?.method === "PUT")).toHaveLength(1);
  });

  it("retains direct editing for suppliers", async () => {
    render(<PartyDirectoryPanel kind="suppliers" locale="es" session={{ ...session, permissions: ["SUPPLIERS_WRITE"] }} />);
    fireEvent.click((await screen.findByText("Proveedor de prueba")).closest("[role=row]")!, { detail: 1 });
    await screen.findByDisplayValue("Proveedor de prueba");
    expect(screen.queryByRole("dialog", { name: "Documentos del cliente" })).not.toBeInTheDocument();
    await waitFor(() => expect(apiRequest).not.toHaveBeenCalledWith(expect.stringContaining("/customer-document-reports/saas/customer-1/"), expect.anything()));
  });
});

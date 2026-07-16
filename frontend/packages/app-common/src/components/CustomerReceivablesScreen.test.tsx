// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CustomerReceivablesScreen } from "./CustomerReceivablesScreen";
import type { UserSession } from "../types";

const session: UserSession = { username: "cashier", displayName: "Caja", accessToken: "token", permissions: ["CUSTOMER_RECEIVABLES_READ", "CUSTOMER_RECEIVABLES_PAY"] };
const row = { documentId: "doc-1", documentType: "FACTURA_VENTA", documentNumber: "FV-1", customerId: "customer-1", customerName: "Cliente Uno", issueDate: "2026-07-01", dueDate: "2026-07-31", total: "100.00", paidTotal: "25.00", pendingTotal: "75.00", status: "PARCIAL", overdue: false } as const;

afterEach(cleanup);

describe("CustomerReceivablesScreen", () => {
  it("loads the tenant-scoped endpoint with the customer prefilter and renders the financial columns", async () => {
    const request = vi.fn().mockResolvedValue([row]);
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} initialCustomerId="customer-1" request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByText("FV-1")).toBeVisible();
    expect(request).toHaveBeenCalledWith("/customer-receivables?customerId=customer-1", { token: "token" });
    for (const heading of ["Documento", "Cliente", "Emision", "Vencimiento", "Total", "Pagado", "Pendiente", "Estado"]) expect(screen.getByRole("columnheader", { name: heading })).toBeVisible();
  });

  it("sends text, status, type, overdue and due-date filters without any company id", async () => {
    const request = vi.fn().mockResolvedValue([]);
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Buscar deuda"), { target: { value: "Ana" } });
    fireEvent.change(screen.getByLabelText("Estado"), { target: { value: "PARCIAL" } });
    fireEvent.change(screen.getByLabelText("Tipo de documento"), { target: { value: "FACTURA_VENTA" } });
    fireEvent.click(screen.getByLabelText("Solo vencidos"));
    fireEvent.change(screen.getByLabelText("Vencimiento desde"), { target: { value: "2026-07-01" } });
    fireEvent.change(screen.getByLabelText("Vencimiento hasta"), { target: { value: "2026-07-31" } });
    await waitFor(() => expect(String(request.mock.calls.at(-1)?.[0])).toContain("search=Ana"));
    const path = String(request.mock.calls.at(-1)?.[0]);
    expect(path).toContain("status=PARCIAL"); expect(path).toContain("documentType=FACTURA_VENTA"); expect(path).toContain("overdue=true"); expect(path).toContain("dueFrom=2026-07-01"); expect(path).toContain("dueTo=2026-07-31"); expect(path).not.toContain("companyId");
  });

  it("retries a failed load and refreshes the row after a payment", async () => {
    const request = vi.fn(async (path: string) => {
      if (request.mock.calls.length === 1) throw new Error("sin red");
      if (path === "/payment-methods") return [{ id: "transfer", name: "TRANSFERENCIA", active: true }];
      if (path.endsWith("/payments")) return { ...row, paidTotal: "50.00", pendingTotal: "50.00" };
      return request.mock.calls.some(([called]) => String(called).endsWith("/payments")) ? [{ ...row, paidTotal: "50.00", pendingTotal: "50.00" }] : [row];
    });
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("sin red");
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByText("FV-1")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Cobrar FV-1" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "25" } });
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.change(screen.getByLabelText("Referencia"), { target: { value: "TR-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
    await waitFor(() => expect(screen.getAllByText("50,00")).toHaveLength(2));
  });

  it("ignores an older filter response that arrives after the current request", async () => {
    let resolveOld!: (value: unknown) => void;
    const request = vi.fn((path: string) => path.includes("search=old") ? new Promise((resolve) => { resolveOld = resolve; }) : Promise.resolve(path.includes("search=new") ? [{ ...row, documentNumber: "NEW" }] : []));
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Buscar deuda"), { target: { value: "old" } }); await waitFor(() => expect(request.mock.calls.some(([path]) => String(path).includes("search=old"))).toBe(true));
    fireEvent.change(screen.getByLabelText("Buscar deuda"), { target: { value: "new" } }); expect(await screen.findByText("NEW")).toBeVisible();
    resolveOld([{ ...row, documentNumber: "OLD" }]); await Promise.resolve(); expect(screen.queryByText("OLD")).not.toBeInTheDocument();
  });

  it("uses semantic cells and ignores a response after unmount", async () => {
    let resolve!: (value: unknown) => void; const request = vi.fn(() => new Promise((done) => { resolve = done; }));
    const view = render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    view.unmount(); resolve([row]); await Promise.resolve();
    const immediate = vi.fn().mockResolvedValue([row]); render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={immediate as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findAllByRole("cell")).toHaveLength(9);
  });
});

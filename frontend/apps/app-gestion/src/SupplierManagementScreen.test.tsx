/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { SupplierManagementScreen } from "./SupplierManagementScreen";

const request = vi.hoisted(() => vi.fn());
vi.mock("@tpverp/app-common", async () => ({
  ...(await vi.importActual<typeof import("@tpverp/app-common")>("@tpverp/app-common")),
  apiRequest: request,
}));

const adminSession: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"],
};
const operatorSession: UserSession = {
  ...adminSession,
  username: "operator",
  displayName: "Operador",
  permissions: ["APP_GESTION_ACCESS"],
};
afterEach(() => {
  vi.useRealTimers();
  cleanup();
  request.mockReset();
});

describe("SupplierManagementScreen", () => {
  it("repeats representative selection without opening an editor or sending a mutation", async () => {
    request.mockImplementation(async (path: string) => path.startsWith("/sales-representatives/management/page") ? {
      items: [1, 2, 3, 4].map(index => ({ id: `rep-${index}`, version: 1, commercialId: `COM-00${index}`, name: `Comercial ${index}`, active: true, suppliers: [] })),
      nextCursor: null, hasMore: false,
    } : []);
    render(<SupplierManagementScreen locale="es" session={adminSession} />);
    fireEvent.click(screen.getByRole("tab", { name: "Comerciales de proveedor" }));
    await waitFor(() => expect(screen.getByRole("table", { name: "Comerciales de proveedor" }).querySelectorAll("button.representative-management-row")).toHaveLength(4));
    const rows = Array.from(screen.getByRole("table", { name: "Comerciales de proveedor" }).querySelectorAll<HTMLButtonElement>("button.representative-management-row"));
    expect(rows[0]).toHaveFocus();
    expect(rows[0]).toHaveAttribute("aria-selected", "true");
    rows[0].focus();
    vi.useFakeTimers();
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(document.activeElement).toBe(rows[1]);
    await act(async () => { vi.advanceTimersByTime(180); });
    expect(document.activeElement).toBe(rows[2]);
    await act(async () => { vi.advanceTimersByTime(70); });
    expect(document.activeElement).toBe(rows[3]);
    expect(rows[3]).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(request.mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
    fireEvent.keyUp(rows[3], { key: "ArrowDown" });
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(document.activeElement).toBe(rows[3]);
    fireEvent.keyDown(rows[3], { key: "ArrowUp" });
    expect(document.activeElement).toBe(rows[2]);
    fireEvent.click(screen.getByRole("button", { name: "Nuevo comercial F5" }));
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(screen.getByRole("dialog", { name: "Nuevo comercial" })).toBeInTheDocument();
    expect(rows[2]).toHaveAttribute("aria-selected", "true");
    expect(request.mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
  });
  it("rejects a non-ADMIN session visibly before mounting management controls", () => {
    render(<SupplierManagementScreen locale="es" session={operatorSession} />);

    expect(screen.getByRole("alert")).toHaveTextContent("Solo un administrador puede abrir estas ventanas de gestión.");
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();
  });

  it("loads commercial pages with the cursor contract and preserves linked supplier detail", async () => {
    const firstPage = {
      items: [{
        id: "rep-1",
        version: 4,
        commercialId: "COM-001",
        name: "Ana Comercial",
        phone: "600 111 222",
        email: "ana@example.test",
        otherContact: "Contacto interno",
        active: true,
        suppliers: [{ supplierId: "sup-1", supplierCode: "PROV-001", supplierName: "Proveedor Uno", primary: true }],
      }],
      nextCursor: "cursor-2",
      hasMore: true,
    };
    const secondPage = {
      items: [{
        id: "rep-2",
        version: 1,
        commercialId: "COM-002",
        name: "Bruno Comercial",
        phone: null,
        email: null,
        otherContact: null,
        active: false,
        suppliers: [],
      }],
      nextCursor: null,
      hasMore: false,
    };
    request.mockImplementation(async (path: string) => {
      if (path.startsWith("/sales-representatives/management/page")) {
        return path.includes("cursor=cursor-2") ? secondPage : firstPage;
      }
      if (path === "/suppliers") return [];
      return [];
    });

    render(<SupplierManagementScreen locale="es" session={adminSession} />);
    fireEvent.click(screen.getByRole("tab", { name: "Comerciales de proveedor" }));

    expect(await screen.findByText("COM-001")).toBeInTheDocument();
    expect(screen.getByText("600 111 222")).toBeInTheDocument();
    const more = await screen.findByRole("button", { name: "Cargar más" });
    fireEvent.click(more);
    expect(await screen.findByText("COM-002")).toBeInTheDocument();
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/sales-representatives/management/page?size=50&cursor=cursor-2",
      expect.objectContaining({ token: "token" }),
    ));

    fireEvent.click(screen.getByRole("button", { name: "Ordenar por Código" }));
    expect(document.querySelector(".representative-management-row:not(.header) [data-column-key='code']"))
      .toHaveTextContent("COM-002");

    const firstRow = screen.getByText("COM-001").closest("button");
    expect(firstRow).not.toBeNull();
    fireEvent.click(firstRow!);
    expect(await screen.findByDisplayValue("Contacto interno")).toBeInTheDocument();
    const linkedSupplierCode = screen.getByText("PROV-001");
    expect(linkedSupplierCode.parentElement).toHaveTextContent("PROV-001 · Proveedor Uno · Principal");
    fireEvent.click(screen.getByRole("button", { name: "Desvincular" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("¿Desvincular este proveedor del comercial?");
    expect(request).not.toHaveBeenCalledWith(
      "/suppliers/sup-1/sales-representatives/rep-1",
      expect.objectContaining({ method: "DELETE" }),
    );
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cancelar" }));
  });

  it("requires app confirmation before deactivating a representative", async () => {
    request.mockImplementation(async (path: string) => {
      if (path.startsWith("/sales-representatives/management/page")) return {
        items: [{ id: "rep-1", version: 1, commercialId: "COM-001", name: "Ana", active: true, suppliers: [] }],
        nextCursor: null,
        hasMore: false,
      };
      return {};
    });

    render(<SupplierManagementScreen locale="es" session={adminSession} />);
    fireEvent.click(screen.getByRole("tab", { name: "Comerciales de proveedor" }));
    fireEvent.click(await screen.findByText("COM-001"));
    fireEvent.click(screen.getByRole("button", { name: "Desactivar" }));

    const confirmation = screen.getByRole("alertdialog");
    expect(confirmation).toHaveTextContent("¿Desactivar este comercial?");
    expect(request).not.toHaveBeenCalledWith("/sales-representatives/rep-1/deactivate", expect.anything());
    fireEvent.click(within(confirmation).getByRole("button", { name: "Cancelar" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(request).not.toHaveBeenCalledWith("/sales-representatives/rep-1/deactivate", expect.anything());

    fireEvent.click(screen.getByRole("button", { name: "Desactivar" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Desactivar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/sales-representatives/rep-1/deactivate",
      expect.objectContaining({ method: "PATCH", token: "token" }),
    ));
  });
});

it("selects a representative and opens the editor with F7 while F5 creates a new record", async () => {
  request.mockImplementation(async (path: string) => path.startsWith("/sales-representatives/management/page") ? {
    items: [{ id: "rep-1", version: 1, commercialId: "COM-001", name: "Ana", active: true, suppliers: [] }],
    nextCursor: null,
    hasMore: false,
  } : []);
  const t = createTranslator("es");

  render(<SupplierManagementScreen locale="es" session={adminSession} />);
  fireEvent.click(screen.getByRole("tab", { name: t("safeManagement.suppliers.tab.representatives") }));
  expect(screen.getByRole("button", { name: "Modificar comercial F7" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Retirar de forma segura F9" })).toBeDisabled();
  fireEvent.click(await screen.findByText("COM-001"), { detail: 1 });
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Retirar de forma segura F9" })).toBeEnabled();
  fireEvent.keyDown(window, { key: "F7" });
  expect(await screen.findByDisplayValue("Ana")).toBeInTheDocument();
  fireEvent.keyDown(window, { key: "F5" });
  expect(screen.getByDisplayValue("Ana")).toBeInTheDocument();
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: t("common.close") }));
  fireEvent.keyDown(window, { key: "F5" });
  expect(screen.queryByDisplayValue("Ana")).not.toBeInTheDocument();
  expect(screen.getByRole("dialog", { name: t("safeManagement.representatives.new") })).toBeInTheDocument();
});

it.each(["es", "en", "zh"] as const)("opens new representative with F5 in %s and ignores F8", async (locale) => {
  const t = createTranslator(locale);
  request.mockImplementation(async (path: string) => path.startsWith("/sales-representatives/management/page")
    ? { items: [], nextCursor: null, hasMore: false }
    : []);

  render(<SupplierManagementScreen locale={locale} session={adminSession} />);
  fireEvent.click(screen.getByRole("tab", { name: t("safeManagement.suppliers.tab.representatives") }));
  expect(await screen.findByText(t("safeManagement.representatives.empty"))).toBeInTheDocument();

  const newButton = screen.getByRole("button", { name: new RegExp(`${t("safeManagement.representatives.new")}\\s*F5`) });
  expect(newButton).toHaveAttribute("aria-keyshortcuts", "F5");
  fireEvent.keyDown(window, { key: "F8" });
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

  fireEvent.keyDown(window, { key: "F5" });
  const dialog = screen.getByRole("dialog", { name: t("safeManagement.representatives.new") });
  expect(dialog).toBeInTheDocument();
  fireEvent.keyDown(window, { key: "F5" });
  expect(screen.getAllByRole("dialog")).toHaveLength(1);
  expect(screen.getByRole("dialog", { name: t("safeManagement.representatives.new") })).toBe(dialog);
});

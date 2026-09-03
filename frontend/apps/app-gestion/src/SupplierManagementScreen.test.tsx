/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { type UserSession } from "@tpverp/app-common";
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
  cleanup();
  request.mockReset();
});

describe("SupplierManagementScreen", () => {
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

    const firstRow = screen.getByText("COM-001").closest("button");
    expect(firstRow).not.toBeNull();
    fireEvent.click(firstRow!);
    expect(await screen.findByDisplayValue("Contacto interno")).toBeInTheDocument();
    const linkedSupplierCode = screen.getByText("PROV-001");
    expect(linkedSupplierCode.parentElement).toHaveTextContent("PROV-001 · Proveedor Uno · Principal");
  });
});

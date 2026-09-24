/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

it("selects a representative and opens the editor with F7 while F8 creates a new record", async () => {
 request.mockImplementation(async (path: string) => path.startsWith("/sales-representatives/management/page") ? {items:[{id:"rep-1",version:1,commercialId:"COM-001",name:"Ana",active:true,suppliers:[]}],hasMore:false} : []);
 render(<SupplierManagementScreen locale="es" session={adminSession}/>);
 fireEvent.click(screen.getByRole("tab",{name:"Comerciales de proveedor"}));
 expect(screen.getByRole("button",{name:"F7 Modificar comercial"})).toBeDisabled();
 expect(screen.getByRole("button",{name:"F9 Retirar de forma segura"})).toBeDisabled();
 fireEvent.click(await screen.findByText("COM-001"),{detail:1});
 expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
 expect(screen.getByRole("button",{name:"F9 Retirar de forma segura"})).toBeEnabled();
 fireEvent.keyDown(window,{key:"F7"});
 expect(await screen.findByDisplayValue("Ana")).toBeInTheDocument();
 fireEvent.keyDown(window,{key:"F8"});
 expect(screen.getByDisplayValue("Ana")).toBeInTheDocument();
 fireEvent.click(screen.getByRole("button",{name:"Cancelar"}));
 fireEvent.keyDown(window,{key:"F8"});
 expect(screen.queryByDisplayValue("Ana")).not.toBeInTheDocument();
 expect(screen.getByRole("dialog",{name:"Nuevo comercial"})).toBeInTheDocument();
});

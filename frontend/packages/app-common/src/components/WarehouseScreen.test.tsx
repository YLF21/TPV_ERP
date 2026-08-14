import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { TerminalContext, UserSession } from "../types";
import { WarehouseScreen } from "./WarehouseScreen";
import { userCanManageWarehouse, visibleWarehouseSectionsForSession } from "./warehouseAccess";

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

function warehouseSession(permissions: UserSession["permissions"]): UserSession {
  return {
    username: "warehouse-user",
    displayName: "ALMACÉN",
    permissions
  };
}

describe("WarehouseScreen", () => {
  it("only grants access to ADMIN and GESTION_ALMACEN", () => {
    expect(userCanManageWarehouse(warehouseSession(["ADMIN"]))).toBe(true);
    expect(userCanManageWarehouse(warehouseSession(["GESTION_ALMACEN"]))).toBe(true);
    expect(userCanManageWarehouse(warehouseSession(["GESTION_PRODUCTO"]))).toBe(false);
    expect(userCanManageWarehouse(warehouseSession(["GESTION_VENTAS"]))).toBe(false);
    expect(visibleWarehouseSectionsForSession(warehouseSession(["GESTION_ALMACEN"])))
      .toEqual(["input", "purchaseDeliveryNotes", "purchaseInvoices", "output", "goodsCheck"]);
    expect(visibleWarehouseSectionsForSession(warehouseSession(["WAREHOUSES_MANAGE"])))
      .toEqual([]);
  });

  it("renders a requested operation inside APP GESTION without a second sidebar", () => {
    const html = renderToStaticMarkup(
      <WarehouseScreen
        app="gestion"
        locale="es"
        session={warehouseSession(["GESTION_ALMACEN"])}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        embedded
        initialSection="output"
      />
    );

    expect(html).toContain("gestion-embedded-module");
    expect(html).toContain("Listado y gestión de salidas de almacén");
    expect(html).not.toContain("<aside");
    expect(html).not.toContain("report-brand-back");
    expect(html).not.toContain("session-top-controls");
  });

  it("renders warehouse movements and independent purchase document queries", () => {
    const html = renderToStaticMarkup(
      <WarehouseScreen
        app="venta"
        locale="es"
        session={warehouseSession(["GESTION_ALMACEN"])}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain("Entrada almacén");
    expect(html).toContain("Albaranes de compra");
    expect(html).toContain("Facturas de compra");
    expect(html).toContain("Salida almacén");
    expect(html).toContain("Comprobación de pedido");
    expect(html).not.toContain("Edición masiva");
    expect(html).toContain('class="stock-nav"');
    expect(html).toContain('class="module-nav-back-icon"');
    expect(html.match(/class="module-nav-item-icon"/g)).toHaveLength(5);
    expect(html.match(/class="module-nav-item-label"/g)).toHaveLength(5);
    expect(html).not.toContain(">Clientes<");
    expect(html).not.toContain(">Proveedores<");
  });

  it("shows a denied state when the screen is rendered without warehouse management", () => {
    const html = renderToStaticMarkup(
      <WarehouseScreen
        app="venta"
        locale="es"
        session={warehouseSession(["GESTION_PRODUCTO"])}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain("No tiene permiso para acceder a la gestión de almacén");
  });
});

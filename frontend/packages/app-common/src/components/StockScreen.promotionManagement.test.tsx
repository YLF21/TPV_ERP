// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import type { PromotionView } from "./PromotionForm";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async original => ({
  ...await original<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const warehouseId = "11111111-1111-4111-8111-111111111111";
const managedSession: UserSession = {
  username: "manager", displayName: "Manager", permissions: ["GESTION_PRODUCTO"], accessToken: "test"
};
const saleAdminSession: UserSession = { ...managedSession, permissions: ["ADMIN"] };
const readOnlySession: UserSession = { ...managedSession, permissions: ["STOCK_READ"] };
const promotion: PromotionView = {
  id: "promo-1", version: 7, name: "Promoción vigente", type: "PURCHASE_THRESHOLD_DISCOUNT",
  status: "ACTIVE", startDate: "2026-01-01", endDate: null, scope: "PRODUCT_LIST",
  customerSegment: "ALL", memberCategoryId: null, minimumAmount: 10, minimumQuantity: null,
  buyQuantity: null, payQuantity: null, buyXPayYMode: null, discountAmount: null,
  discountPercent: 10, maximumDiscount: null, packPrice: null, used: true, usageCount: 2,
  targets: [{ type: "PRODUCT", targetId: "p-1" }]
};
const props = {
  locale: "es" as const, terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(), onLocaleChange: vi.fn(), initialView: "stock.promotions" as const
};

let serverPromotions: PromotionView[];

function mutatingRequests() {
  return vi.mocked(apiRequest).mock.calls.filter(([, options]) =>
    options?.method === "POST" || options?.method === "PUT");
}

beforeEach(() => {
  localStorage.clear();
  serverPromotions = [{ ...promotion }];
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  vi.stubGlobal("fetch", vi.fn(async () => Response.json([])));
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path, options) => {
    if (path.startsWith("/stock/page")) return {
      items: [{ product: { id: "p-1", code: "p-1", name: "Té actual", active: true,
        productType: "UNIT", familyId: "drinks" }, stock: [{ productId: "p-1", warehouseId, quantity: 4 }] }],
      hasMore: false
    };
    if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
    if (path === "/promotions" && options?.method !== "POST") return serverPromotions.map(item => ({ ...item }));
    if (path === "/products") return [{ id: "p-1", code: "p-1", name: "Té actual", active: true }];
    if (path === "/families") return [{ id: "drinks", name: "Bebidas" }];
    if (path === "/families/drinks/subfamilies") return [];
    if (path === "/promotions" && options?.method === "POST") {
      const body = options.body as { name: string; startDate: string; endDate: string | null;
        minimumAmount: string; discountPercent: string };
      const saved: PromotionView = { ...promotion, id: "promo-new", version: 0,
        name: body.name, type: "PURCHASE_THRESHOLD_DISCOUNT", status: "DRAFT",
        startDate: body.startDate, endDate: body.endDate, scope: "SALE", customerSegment: "ALL",
        minimumAmount: body.minimumAmount, discountPercent: body.discountPercent,
        used: false, usageCount: 0, targets: [] };
      serverPromotions = [...serverPromotions, saved];
      return saved;
    }
    if (path === "/promotions/promo-1/deactivate" && options?.method === "POST") {
      const saved = { ...promotion, version: 8, status: "INACTIVE" as const };
      serverPromotions = [saved];
      return saved;
    }
    if (path === "/promotions/promo-1" && options?.method === "PUT") {
      const saved = { ...promotion, id: "promo-2", version: 0, status: "DRAFT" as const,
        name: "Promoción ajustada", versionOrigenId: promotion.id };
      serverPromotions = [promotion, saved];
      return saved;
    }
    return [];
  });
});

afterEach(() => {
  cleanup(); localStorage.clear(); vi.unstubAllGlobals();
  delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

describe("StockScreen promotion management", () => {
  it("opens new promotion with F5 in Gestión and never opens product creation", async () => {
    render(<StockScreen {...props} app="gestion" session={managedSession} />);
    await screen.findByRole("button", { name: /Promoción vigente/ });
    expect(screen.getByRole("button", { name: /Nueva promoción/ })).toHaveAttribute("aria-keyshortcuts", "F5");
    fireEvent.keyDown(window, { key: "F5" });
    const dialog = screen.getByRole("dialog", { name: "Crear promoción" });
    expect(within(dialog).getByRole("button", { name: "Confirmar" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Cancelar" })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Cerrar" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).not.toBeInTheDocument();
    expect(mutatingRequests()).toHaveLength(0);
  });

  it("selects and focuses a newly created promotion after confirming with F5 from an active row", async () => {
    render(<StockScreen {...props} app="gestion" session={managedSession} />);
    const previousRow = await screen.findByRole("button", { name: /Promoción vigente/ });
    previousRow.focus();
    fireEvent.keyDown(previousRow, { key: "F5" });
    const dialog = screen.getByRole("dialog", { name: "Crear promoción" });
    fireEvent.change(within(dialog).getByLabelText(/Nombre/), { target: { value: "Promoción nueva" } });
    fireEvent.change(within(dialog).getByLabelText(/Mínimo específico/), { target: { value: "10" } });
    fireEvent.change(within(dialog).getByLabelText(/% descuento/), { target: { value: "15" } });
    fireEvent.submit(within(dialog).getByRole("button", { name: "Confirmar" }).closest("form")!);

    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/promotions", expect.objectContaining({
      token: "test", method: "POST", body: expect.objectContaining({
        name: "Promoción nueva", minimumAmount: "10", discountPercent: "15"
      })
    })));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Crear promoción" })).not.toBeInTheDocument());
    const createdRow = await screen.findByRole("button", { name: /Promoción nueva/ });
    await waitFor(() => {
      expect(createdRow).toHaveAttribute("aria-pressed", "true");
      expect(createdRow).toHaveFocus();
    });
    expect(previousRow).toHaveAttribute("aria-pressed", "false");
  });

  it.each(["Escape", "X"] as const)("returns focus to the prior promotion row when create closes with %s", async closeMethod => {
    render(<StockScreen {...props} app="gestion" session={managedSession} />);
    const previousRow = await screen.findByRole("button", { name: /Promoción vigente/ });
    previousRow.focus();
    fireEvent.keyDown(previousRow, { key: "F5" });
    const dialog = screen.getByRole("dialog", { name: "Crear promoción" });
    if (closeMethod === "Escape") fireEvent.keyDown(dialog, { key: "Escape" });
    else fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Crear promoción" })).not.toBeInTheDocument());
    await waitFor(() => expect(previousRow).toHaveFocus());
  });

  it("edits prefetched conditions with version and selects the new draft without changing its used source", async () => {
    render(<StockScreen {...props} app="gestion" session={managedSession} />);
    fireEvent.click(await screen.findByRole("button", { name: /Promoción vigente/ }));
    const editButton = screen.getByRole("button", { name: "Modificar condiciones" });
    editButton.focus();
    fireEvent.click(editButton);
    const editor = screen.getByRole("dialog", { name: "Modificar promoción" });
    expect(within(editor).getByRole("button", { name: "Confirmar" })).toBeInTheDocument();
    expect(within(editor).queryByRole("button", { name: "Cancelar" })).not.toBeInTheDocument();
    expect(within(editor).getByRole("button", { name: "Cerrar" })).toBeInTheDocument();
    expect(within(editor).getByDisplayValue("Promoción vigente")).toBeInTheDocument();
    expect(within(editor).getByRole("note")).toBeInTheDocument();
    fireEvent.change(within(editor).getByDisplayValue("Promoción vigente"),
      { target: { value: "Promoción ajustada" } });
    fireEvent.submit(within(editor).getByRole("button", { name: "Confirmar" }).closest("form")!);
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/promotions/promo-1", {
      token: "test", method: "PUT", body: {
        expectedVersion: 7, promotion: expect.objectContaining({
          name: "Promoción ajustada", type: "PURCHASE_THRESHOLD_DISCOUNT",
          targets: [{ type: "PRODUCT", targetId: "p-1" }]
        })
      }
    }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Modificar promoción" })).not.toBeInTheDocument());
    expect(screen.getByRole("status")).toHaveTextContent(/borrador/i);
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /Promoción ajustada/ })).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByRole("button", { name: /Promoción ajustada/ })).toHaveFocus();
    });
    expect(screen.getByRole("button", { name: /Promoción vigente/ })).toBeInTheDocument();
  });

  it("opens product scope editing and sends only the deactivation action to the status endpoint", async () => {
    render(<StockScreen {...props} app="gestion" session={managedSession} />);
    fireEvent.click(await screen.findByRole("button", { name: /Promoción vigente/ }));
    fireEvent.click(screen.getByRole("button", { name: "Añadir o quitar productos" }));
    const editor = screen.getByRole("dialog", { name: "Modificar promoción" });
    const picker = await screen.findByRole("dialog", { name: "Seleccionar productos" });
    expect(within(picker).getByText("Stock disponible")).toBeInTheDocument();
    fireEvent.keyDown(picker, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Seleccionar productos" })).not.toBeInTheDocument();
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalled();
    fireEvent.click(within(editor).getByRole("button", { name: "Cerrar" }));
    fireEvent.click(screen.getByRole("button", { name: "Desactivar" }));
    await waitFor(() => expect(mutatingRequests()).toEqual([
      ["/promotions/promo-1/deactivate", { token: "test", method: "POST" }]
    ]));
    await waitFor(() => expect(screen.getByRole("button", { name: /Promoción vigente/ })).toHaveTextContent("Inactiva"));
  });

  it.each([
    { app: "venta" as const, session: saleAdminSession },
    { app: "gestion" as const, session: readOnlySession }
  ])("keeps promotion management unavailable for $app with $session.permissions", async ({ app, session }) => {
    render(<StockScreen {...props} app={app} session={session} />);
    fireEvent.click(await screen.findByRole("button", { name: /Promoción vigente/ }));
    expect(screen.queryByRole("button", { name: /Nueva promoción/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Modificar condiciones" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Añadir o quitar productos" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Desactivar" })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "F5" });
    expect(screen.queryByRole("dialog", { name: "Crear promoción" })).not.toBeInTheDocument();
    expect(mutatingRequests()).toHaveLength(0);
  });
});

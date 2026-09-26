// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { PromotionListScreen } from "./PromotionListScreen";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
vi.mock("./PromotionWizard", () => ({ PromotionWizard: () => null, promotionProductsPath: "/products" }));
vi.mock("./SessionTopControls", () => ({ SessionTopControls: () => null }));
vi.mock("./ScreenContextFooter", () => ({ ScreenContextFooter: () => null }));

const session: UserSession = { username: "promotion-filters", displayName: "Test", accessToken: "token", permissions: ["ADMIN"] };
const promotions = [
  {
    id: "coffee", name: "Pack café", status: "ACTIVE", type: "FIXED_PACK_PRICE", startDate: "2026-09-01", endDate: null,
    scope: "PRODUCT_LIST", customerSegment: "ALL", used: true, usageCount: 12, targets: [{ type: "PRODUCT", targetId: "coffee-product" }]
  },
  {
    id: "water", name: "Agua", status: "DRAFT", type: "FIXED_PACK_PRICE", startDate: "2026-09-02", endDate: null,
    scope: "PRODUCT_LIST", customerSegment: "ALL", used: false, targets: []
  },
  {
    id: "expired", name: "Promoción caducada", status: "INACTIVE", type: "QUANTITY_DISCOUNT", startDate: "2026-08-01", endDate: "2026-08-31",
    scope: "PRODUCT_LIST", customerSegment: "ALL", used: false, targets: []
  }
];
const products = [{ id: "coffee-product", code: "C-001", name: "Café molido", active: true, salePrice: 3.5 }];
const props = {
  session, terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(), onLocaleChange: vi.fn(), embedded: true
};

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async path => {
    if (path === "/promotions") return promotions as never;
    if (path === "/products") return products as never;
    return [] as never;
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Promotion list filters and detail", () => {
  it("filters without accents and clearing the query preserves the selected promotion", async () => {
    render(<PromotionListScreen {...props} app="gestion" locale="es" />);
    const water = await screen.findByRole("button", { name: "Agua" });
    fireEvent.click(water);
    expect(await screen.findByRole("heading", { name: "Agua" })).toBeInTheDocument();

    const search = screen.getByRole("searchbox", { name: "Buscar" });
    fireEvent.change(search, { target: { value: " cafe " } });
    expect(screen.getByRole("button", { name: "Pack café" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Agua" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Agua" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Buscar: cafe");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(screen.getByRole("button", { name: "Agua" })).toHaveAttribute("aria-pressed", "true");
    expect(search).toHaveValue("");
    expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path === "/promotions")).toHaveLength(1);
  });

  it.each(["es", "en", "zh"] as const)("searches by the localized status in %s", async locale => {
    const t = createTranslator(locale);
    render(<PromotionListScreen {...props} app="gestion" locale={locale} />);
    await screen.findByRole("button", { name: "Agua" });
    const search = screen.getByRole("searchbox", { name: t("salesReport.search") });
    fireEvent.change(search, { target: { value: t("promotion.status.DRAFT") } });
    expect(screen.getByRole("button", { name: "Agua" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Pack café" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: t("filters.clearAll") }));
    expect(screen.getByRole("button", { name: "Pack café" })).toBeVisible();
    expect(search).toHaveValue("");
  });

  it("checks detail actions after opening Más acciones", async () => {
    const t = createTranslator("es");
    render(<PromotionListScreen {...props} app="gestion" locale="es" />);
    await screen.findByRole("button", { name: "Pack café" });

    fireEvent.click(screen.getByRole("button", { name: t("promotion.action.more") }));
    expect(screen.getByRole("button", { name: t("promotion.action.duplicate") })).toBeEnabled();
    expect(screen.getByRole("button", { name: t("promotion.action.delete") })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "Agua" }));
    fireEvent.click(screen.getByRole("button", { name: t("promotion.action.more") }));
    expect(screen.getByRole("button", { name: t("promotion.action.delete") })).toBeEnabled();
  });

  it("marks expired promotions with the expired state class", async () => {
    render(<PromotionListScreen {...props} app="gestion" locale="es" />);
    const expired = await screen.findByRole("button", { name: "Promoción caducada" });
    expect(expired).toHaveClass("expired");
  });

  it("filters promotions through the Estado selector", async () => {
    const t = createTranslator("es");
    render(<PromotionListScreen {...props} app="gestion" locale="es" />);
    await screen.findByRole("button", { name: "Agua" });
    fireEvent.click(screen.getByRole("button", { name: t("promotion.column.status") }));
    fireEvent.click(screen.getByRole("option", { name: t("promotion.list.expired") }));
    expect(screen.getByRole("button", { name: "Promoción caducada" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Agua" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pack café" })).not.toBeInTheDocument();
  });

  it("shows matching products after the selected promotion conditions", async () => {
    const t = createTranslator("es");
    render(<PromotionListScreen {...props} app="gestion" locale="es" />);
    expect(await screen.findByText("Café molido")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: t("promotion.detail.conditions") })).toBeInTheDocument();
    const conditions = screen.getByRole("heading", { name: t("promotion.detail.conditions") });
    const productHeading = screen.getByRole("heading", { name: /Productos incluidos \(1\)/ });
    expect(productHeading).toBeInTheDocument();
    expect(conditions.compareDocumentPosition(productHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

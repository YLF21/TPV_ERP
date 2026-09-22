// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { PromotionListScreen } from "./PromotionListScreen";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
vi.mock("./PromotionWizard", () => ({ PromotionWizard: () => null }));
vi.mock("./SessionTopControls", () => ({ SessionTopControls: () => null }));
vi.mock("./ScreenContextFooter", () => ({ ScreenContextFooter: () => null }));

const session: UserSession = { username: "promotion-filters", displayName: "Test", accessToken: "token", permissions: ["ADMIN"] };
const promotions = [
  { id: "coffee", name: "Pack café", status: "ACTIVE", type: "FIXED_PACK_PRICE", startDate: "2026-09-01", endDate: null, customerSegment: "ALL", used: true },
  { id: "water", name: "Agua", status: "DRAFT", type: "FIXED_PACK_PRICE", startDate: "2026-09-02", endDate: null, customerSegment: "ALL", used: false }
];
const props = { session, terminalContext: { storeName: "Test", terminalCode: "TEST" }, onBack: vi.fn(), onLocaleChange: vi.fn(), embedded: true };

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async path => path === "/promotions" ? promotions : []);
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Promotion list applied filters", () => {
  it.each(["venta", "gestion"] as const)("filters the complete %s list and restores rows without changing the selected promotion or actions", async app => {
    const { container } = render(<PromotionListScreen {...props} app={app} locale="es" />);
    const water = await screen.findByRole("button", { name: "Agua" });
    fireEvent.click(water);
    const search = screen.getByRole("searchbox", { name: "Buscar" });
    fireEvent.change(search, { target: { value: " cafe " } });
    expect(container.querySelector(".promotion-screen")).toHaveClass("erp-classic-tables");
    expect(screen.queryByRole("button", { name: "Agua" })).not.toBeInTheDocument();
    const coffeeRow = screen.getByRole("button", { name: "Pack café" }).closest(".promotion-row")!;
    expect(within(coffeeRow as HTMLElement).getByRole("button", { name: "Eliminar" })).toBeDisabled();
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Buscar: cafe");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(screen.getByRole("button", { name: "Agua" }).closest(".promotion-row")).toHaveClass("selected");
    expect(search).toHaveValue("");
    expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path === "/promotions")).toHaveLength(1);
  });

  it.each(["es", "en", "zh"] as const)("matches visible localized status in %s and clears only the applied query", async locale => {
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
});

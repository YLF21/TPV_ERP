// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { InternalEanReservation } from "../../../packages/app-common/src/sale/internalEan";

vi.mock("react-dom/client", () => ({ createRoot: vi.fn(() => ({ render: vi.fn() })) }));
vi.mock("../../../packages/app-common/src/api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../../../packages/app-common/src/api/client")>(),
  apiRequest: vi.fn().mockResolvedValue([]),
}));
vi.mock("../../../packages/app-common/src/components/SaleInternalEanDialog", () => ({
  SaleInternalEanDialog: ({ onCreateProduct }: { onCreateProduct: (reservation: InternalEanReservation) => void }) => (
    <section role="dialog" aria-label="EAN utility">
      <input aria-label="EAN query" autoFocus />
      <button type="button" onClick={() => onCreateProduct({ reservationId: "reservation-1", code: "2000000000015", format: "EAN_13", expiresAt: "2026-09-13T00:00:00Z" })}>
        Create reserved product
      </button>
    </section>
  ),
}));
vi.mock("../../../packages/app-common/src/components/ProductCreateDialog", () => ({
  ProductCreateDialog: () => (
    <section role="dialog" aria-label="Create utility product">
      <input aria-label="Product name" autoFocus />
    </section>
  ),
}));

import { SalesUtilityWindowApp } from "./main";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("retains the touch keyboard when EAN creation transitions to the product form", async () => {
  vi.stubGlobal("tpvDesktop", {
    salesUtilities: {
      consumeBootstrap: vi.fn().mockResolvedValue({
        kind: "INTERNAL_EAN",
        locale: "es",
        session: { username: "seller", permissions: ["VENTA"], accessToken: "test-token" },
        terminalContext: { terminalCode: "01", storeName: "Test store" },
        interfaceMode: "TOUCH",
        authorization: { mode: "DIRECT", requireUsername: false, requirePassword: false },
      }),
    },
  });
  render(<SalesUtilityWindowApp />);
  const eanDialog = await screen.findByRole("dialog", { name: "EAN utility" });
  const eanInput = within(eanDialog).getByRole("textbox", { name: "EAN query" });
  fireEvent.focus(eanInput);
  const eanKeyboard = await within(eanDialog).findByRole("group", { name: "Teclado alfanumérico" });
  fireEvent.click(within(eanKeyboard).getByRole("button", { name: "2" }));
  expect(eanInput).toHaveValue("2");

  fireEvent.click(within(eanDialog).getByRole("button", { name: "Create reserved product" }));
  const productDialog = await screen.findByRole("dialog", { name: "Create utility product" });
  const productName = within(productDialog).getByRole("textbox", { name: "Product name" });
  fireEvent.focus(productName);
  const productKeyboard = await within(productDialog).findByRole("group", { name: "Teclado alfanumérico" });
  fireEvent.click(within(productKeyboard).getByRole("button", { name: "P" }));
  expect(productName).toHaveValue("P");
  expect(screen.getAllByRole("group", { name: "Teclado alfanumérico" })).toHaveLength(1);
});

// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import type { PromotionView } from "./PromotionForm";
import { StockPromotionGroups } from "./StockPromotionGroups";

afterEach(cleanup);

it("focuses the first promotion and moves through rows without activating an action", () => {
  const promotions: PromotionView[] = ["First", "Second", "Third"].map((name, index) => ({
    id: `promotion-${index}`, name, status: "ACTIVE", type: "PURCHASE_THRESHOLD_DISCOUNT",
    startDate: "2026-07-11", endDate: null, scope: "SALE", customerSegment: "ALL", memberCategoryId: null,
    minimumAmount: 20, minimumQuantity: null, buyQuantity: null, payQuantity: null, buyXPayYMode: null,
    discountAmount: null, discountPercent: 10, maximumDiscount: 5, packPrice: null, used: false, targets: [],
  }));
  render(<StockPromotionGroups locale="en" promotions={promotions} productRows={[]} t={(key) => key} />);
  const rows = screen.getAllByRole("button", { name: /First|Second|Third/ });
  expect(rows[0]).toHaveFocus();
  expect(rows[0]).toHaveAttribute("aria-pressed", "true");
  fireEvent.keyDown(rows[0], { key: "ArrowDown" });
  expect(rows[1]).toHaveFocus();
  expect(rows[1]).toHaveAttribute("aria-pressed", "true");
  fireEvent.keyUp(rows[1], { key: "ArrowDown" });
  act(() => { rows[2].focus(); fireEvent.keyDown(rows[2], { key: "ArrowDown" }); });
  expect(rows[2]).toHaveFocus();
  expect(rows[2]).toHaveAttribute("aria-pressed", "true");
});

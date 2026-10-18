// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { buildPromotionRequest, promotionDraftFromView, type PromotionView } from "./PromotionForm";
import { PromotionWizard, submitPromotionUpdateDraft } from "./PromotionWizard";

vi.mock("../api/client", async (original) => ({
  ...await original<typeof import("../api/client")>(),
  apiRequest: vi.fn()
}));

const session: UserSession = {
  username: "gestion", displayName: "Gestión", accessToken: "token-123", permissions: []
};

function editablePromotion(): PromotionView {
  return {
    id: "promo/1", version: 3, name: "Segunda unidad", type: "SECOND_UNIT_PERCENT", status: "ACTIVE",
    startDate: "2026-07-11", endDate: null, scope: "SALE", customerSegment: "ALL", memberCategoryId: null,
    minimumAmount: null, minimumQuantity: null, buyQuantity: null, payQuantity: null, buyXPayYMode: null,
    discountAmount: null, discountPercent: "20.00", maximumDiscount: null, packPrice: null,
    targets: [], used: true, usageCount: 1
  };
}

afterEach(() => {
  cleanup();
  vi.mocked(apiRequest).mockReset();
});

describe("PromotionWizard editing", () => {
  it("sends the complete request with the stored version and rejects missing version or type changes", async () => {
    const promotion = editablePromotion();
    const draft = promotionDraftFromView(promotion);
    const request = vi.fn().mockResolvedValue({ ...promotion, version: 4 });

    await submitPromotionUpdateDraft(draft, promotion, "token-123", request);
    expect(request).toHaveBeenCalledWith("/promotions/promo%2F1", {
      token: "token-123", method: "PUT",
      body: { expectedVersion: 3, promotion: buildPromotionRequest(draft) }
    });
    await expect(submitPromotionUpdateDraft(draft, { ...promotion, version: undefined }, "token-123", request))
      .rejects.toThrow("promotion version is required");
    await expect(submitPromotionUpdateDraft({ ...draft, type: "BUY_X_PAY_Y" }, promotion, "token-123", request))
      .rejects.toThrow("promotion type cannot be changed");
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("keeps edited values after a conflict and lets the user retry or cancel", async () => {
    const promotion = editablePromotion();
    const onCreated = vi.fn();
    const onClose = vi.fn();
    const request = vi.mocked(apiRequest);
    request.mockRejectedValueOnce(new ApiError("stale", 409));
    request.mockResolvedValueOnce({ ...promotion, name: "Oferta modificada", version: 4, status: "DRAFT" });
    const { container } = render(
      <PromotionWizard locale="es" session={session} editPromotion={promotion} onCreated={onCreated} onClose={onClose} />
    );

    expect(screen.getByRole("note")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: createTranslator("es")("promotion.field.type") })).toBeDisabled();
    fireEvent.change(screen.getByDisplayValue("Segunda unidad"), { target: { value: "Oferta modificada" } });
    fireEvent.submit(container.querySelector("form")!);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(createTranslator("es")("promotion.status.conflict")));
    expect(screen.getByDisplayValue("Oferta modificada")).toBeInTheDocument();
    expect(onCreated).not.toHaveBeenCalled();

    fireEvent.submit(container.querySelector("form")!);
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ version: 4 })));
    expect(screen.getByDisplayValue("Oferta modificada")).toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole("button", { name: createTranslator("es")("common.cancel") }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("blocks repeated submit and disables Cancel while the request is pending", async () => {
    const promotion = editablePromotion();
    const onCreated = vi.fn();
    let finish!: (value: PromotionView) => void;
    vi.mocked(apiRequest).mockReturnValue(new Promise<PromotionView>((resolve) => { finish = resolve; }));
    const { container } = render(<PromotionWizard locale="es" session={session} editPromotion={promotion} onCreated={onCreated} />);
    const form = container.querySelector("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);

    expect(apiRequest).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: createTranslator("es")("common.cancel") })).toBeDisabled();
    finish({ ...promotion, version: 4 });
    await waitFor(() => expect(onCreated).toHaveBeenCalledOnce());
  });

  it("closes only the target picker on Escape", () => {
    const promotion = editablePromotion();
    promotion.scope = "PRODUCT_LIST";
    promotion.targets = [{ type: "PRODUCT", targetId: "p-1" }];
    vi.mocked(apiRequest).mockResolvedValue([{ id: "p-1", name: "Producto" }]);
    const parentKeyDown = vi.fn();
    const onClose = vi.fn();
    const { container } = render(
      <div onKeyDown={parentKeyDown}>
        <PromotionWizard locale="es" session={session} editPromotion={promotion} onClose={onClose} />
      </div>
    );
    fireEvent.click(container.querySelector(".promotion-create-pick")!);
    const picker = screen.getByRole("dialog");
    fireEvent.keyDown(picker, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(parentKeyDown).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});

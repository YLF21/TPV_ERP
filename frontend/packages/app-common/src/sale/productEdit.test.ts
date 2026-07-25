import { describe, expect, it, vi } from "vitest";
import {
  authorizeProductEdit,
  productEditDialogValue,
  revokeProductEditAuthorization,
} from "./productEdit";

describe("product edit authorization", () => {
  it("sends delegated credentials only to the authorization endpoint", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        operationId: "operation-1",
        authorizedBy: "ENCARGADO",
        delegated: true,
        expiresAt: "2026-07-24T12:15:00Z",
        product: { id: "product-1" }
      }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await authorizeProductEdit("product-1", "token", "encargado", "1234");
    await revokeProductEditAuthorization("operation-1", "token");

    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      productId: "product-1",
      authorizerUsername: "encargado",
      authorizerPassword: "1234"
    });
    expect(fetchMock.mock.calls[1][1]?.method).toBe("DELETE");
  });

  it("maps the complete management product to the shared editor", () => {
    const result = productEditDialogValue({
      id: "product-1",
      familyId: "family-1",
      taxId: "tax-1",
      productType: "UNIT",
      discountType: "NORMAL",
      priceUseMode: "NORMAL",
      name: "Cafe",
      purchasePrice: 2,
      purchaseDiscountPercent: 10,
      packageQuantity: 6,
      active: true,
      taxesIncluded: true,
      offerActive: false,
      salePrice: 4
    });

    expect(result.form).toMatchObject({
      name: "Cafe",
      purchasePrice: "2",
      salePrice: "4"
    });
    expect(result.initialData).toMatchObject({
      purchaseDiscountPercent: 10,
      packageQuantity: 6
    });
  });
});

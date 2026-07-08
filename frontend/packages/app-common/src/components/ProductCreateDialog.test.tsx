import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  buildCreateProductRequest,
  canLeaveProductField,
  createDefaultProductForm,
  nextProductFieldIndex,
  productCreateErrorMessage,
  productDiscountTypeOptions,
  productCreateValidationErrors,
  productImageUploadPath,
  saveProductWithOptionalImage,
  ProductCreateDialog
} from "./ProductCreateDialog";
import type { ProductCreateFormState } from "./ProductCreateDialog";

describe("ProductCreateDialog", () => {
  it("builds the full product creation request from form state", () => {
    const form: ProductCreateFormState = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "UNIT",
      discountType: "NORMAL",
      name: " Cafe molido ",
      description: "Paquete 250g",
      comments: "Alta desde stock",
      purchasePrice: "2.40",
      taxesIncluded: true,
      code: " A001 ",
      barcode: "8430000000011",
      salePrice: "3.95",
      memberPrice: "3.70",
      wholesalePrice: "3.40",
      offerPrice: "3.20",
      offerDiscountPercent: "10",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    };

    expect(buildCreateProductRequest(form)).toEqual({
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "UNIT",
      discountType: "NORMAL",
      name: "Cafe molido",
      description: "Paquete 250g",
      comments: "Alta desde stock",
      purchasePrice: "2.40",
      taxesIncluded: true,
      code: "A001",
      barcode: "8430000000011",
      salePrice: "3.95",
      memberPrice: "3.70",
      wholesalePrice: "3.40",
      offerPrice: "3.20",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    });
  });

  it("moves product fields forwards and backwards", () => {
    expect(nextProductFieldIndex(0, 4, false)).toBe(1);
    expect(nextProductFieldIndex(2, 4, false)).toBe(3);
    expect(nextProductFieldIndex(0, 4, true)).toBe(3);
    expect(nextProductFieldIndex(2, 4, true)).toBe(1);
    expect(nextProductFieldIndex(0, 0, false)).toBe(-1);
  });

  it("requires the fields that the backend cannot store as null", () => {
    expect(productCreateValidationErrors(createDefaultProductForm())).toEqual([
      "familyId",
      "taxId",
      "name",
      "code"
    ]);

    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      purchasePrice: "",
      salePrice: ""
    })).toEqual(["purchasePrice", "salePrice"]);
  });

  it("does not allow advancing from an empty required product field", () => {
    const form = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "",
      code: "A001",
      purchasePrice: "2.40",
      salePrice: "3.95"
    };

    expect(canLeaveProductField(form, "name")).toBe(false);
    expect(canLeaveProductField({ ...form, name: "Cafe" }, "name")).toBe(true);
    expect(canLeaveProductField({ ...form, name: "Cafe", code: "" }, "code")).toBe(false);
  });

  it("does not allow advancing from offer fields when an active offer is incomplete", () => {
    const form = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      offerActive: true,
      offerPrice: "",
      offerFrom: ""
    };

    expect(canLeaveProductField(form, "offerPrice")).toBe(false);
    expect(canLeaveProductField({ ...form, offerPrice: "2.50" }, "offerRange")).toBe(false);
    expect(canLeaveProductField({ ...form, offerPrice: "2.50", offerFrom: "2026-07-01" }, "offerRange")).toBe(true);
  });

  it("keeps every persisted product dialog attribute bound to the create request", () => {
    const request = buildCreateProductRequest({
      ...createDefaultProductForm(),
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "WEIGHT",
      discountType: "DISCOUNT_PRICE",
      name: "Cafe",
      description: "Descripcion",
      comments: "Comentario",
      purchasePrice: "1.20",
      taxesIncluded: false,
      code: "A001",
      barcode: "843",
      salePrice: "2.40",
      memberPrice: "2.10",
      wholesalePrice: "1.90",
      offerPrice: "1.80",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: ""
    });

    expect(Object.keys(request)).toEqual([
      "familyId",
      "subfamilyId",
      "taxId",
      "productType",
      "discountType",
      "name",
      "description",
      "comments",
      "purchasePrice",
      "taxesIncluded",
      "code",
      "barcode",
      "salePrice",
      "memberPrice",
      "wholesalePrice",
      "offerPrice",
      "offerActive",
      "offerFrom",
      "offerUntil"
    ]);
    expect(request.offerUntil).toBeNull();
    expect(JSON.stringify(request)).not.toContain("offerDiscountPercent");
  });

  it("requires offer price and start date when the offer is active", () => {
    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      offerActive: true
    })).toEqual(["offerPrice", "offerFrom"]);
  });

  it("requires an active offer when discount type is offer price", () => {
    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      discountType: "DISCOUNT_PRICE",
      offerPrice: "2.50",
      offerFrom: "2026-07-01"
    })).toEqual(["offerActive"]);
  });

  it("renders the reorganized product form with image panel", () => {
    const html = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        onClose={() => undefined}
      />
    );

    expect(html).toContain('class="product-create-body"');
    expect(html).toContain('class="product-create-form"');
    expect(html).toContain('class="product-create-media"');
    expect(html).toContain("Codigo");
    expect(html).toContain("Tipo");
    expect(html).toContain("Familia");
    expect(html).toContain("Impuesto");
    expect(html).toContain("Oferta desde y hasta");
    expect(html).toContain("Descuento oferta%");
    expect(html).toContain("required");
    expect(html).toContain("Examinar archivo");
    expect(html).toContain("Impuestos incluidos en el precio");
    expect(html.indexOf("Oferta activa")).toBeLessThan(html.indexOf("Precio oferta"));
    expect(html.indexOf("Precio oferta")).toBeLessThan(html.indexOf("Descuento oferta%"));
    expect(html.indexOf("Descuento oferta%")).toBeLessThan(html.indexOf("Oferta desde y hasta"));
    expect(html).not.toContain("<select");
  });

  it("renders full English price and discount labels", () => {
    const html = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="en"
        token="token"
        onClose={() => undefined}
      />
    );

    expect(html).toContain("Purchase price");
    expect(html).toContain("Sale price");
    expect(html).toContain("Member price");
    expect(html).toContain("Wholesale price");
    expect(html).toContain("Offer price");
    expect(html).toContain("Discount");
    expect(html).toContain("Offer discount%");
  });

  it("does not expose the deprecated member discount option", () => {
    expect(productDiscountTypeOptions).toEqual(["NORMAL", "NONE", "MEMBER_PRICE", "DISCOUNT_PRICE"]);
    expect(productDiscountTypeOptions).not.toContain("MEMBER_DISCOUNT");
  });

  it("builds the product image upload path", () => {
    expect(productImageUploadPath("product-1")).toBe("/products/product-1/image");
  });

  it("does not expose low-level network write errors in product dialog status", () => {
    expect(productCreateErrorMessage(new TypeError("Failed to write request"), "No se pudo cargar")).toBe("No se pudo cargar");
    expect(productCreateErrorMessage(new Error("Failed to write request"), "No se pudo cargar")).toBe("No se pudo cargar");
    expect(productCreateErrorMessage(new Error("Codigo duplicado"), "No se pudo cargar")).toBe("Codigo duplicado");
  });

  it("keeps the product creation when the optional image upload fails", async () => {
    const form = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      purchasePrice: "1.20",
      salePrice: "2.40"
    };
    const createdProduct = { id: "product-1", code: "A001", name: "Cafe" };
    const createProduct = vi.fn().mockResolvedValue(createdProduct);
    const uploadImage = vi.fn().mockRejectedValue(new TypeError("Failed to write request"));

    await expect(saveProductWithOptionalImage({
      form,
      token: "token",
      imageFile: {} as File,
      createProduct,
      uploadImage
    })).resolves.toEqual({
      product: createdProduct,
      imageUploadFailed: true
    });
    expect(createProduct).toHaveBeenCalledWith(buildCreateProductRequest(form), "token");
    expect(uploadImage).toHaveBeenCalledWith("product-1", expect.anything(), "token");
  });
});

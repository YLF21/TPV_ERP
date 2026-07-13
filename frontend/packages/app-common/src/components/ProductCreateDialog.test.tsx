import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  buildCreateProductRequest,
  canLeaveProductField,
  canNavigateProductField,
  createDefaultProductForm,
  createProductFormFromEditProduct,
  nextProductFieldIndex,
  productCreateKeyAction,
  productCreateErrorMessage,
  productDiscountTypeOptions,
  productCreateValidationErrors,
  duplicatedProductIdentifierFields,
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
      priceUseMode: "NORMAL",
      discountType: "NORMAL",
      name: " Cafe molido ",
      description: "Paquete 250g",
      comments: "Alta desde stock",
      purchasePrice: "2.40",
      taxesIncluded: true,
      code: " A001 ",
      barcode: "8430000000011",
      barcode2: "8430000000012",
      salePrice: "3.95",
      memberPrice: "3.70",
      wholesalePrice: "3.40",
      offerPrice: "3.20",
      offerDiscountPercent: "10",
      offerActive: false,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    };

    expect(buildCreateProductRequest(form)).toEqual({
      familyId: "family-1",
      subfamilyId: "subfamily-1",
      taxId: "tax-1",
      productType: "UNIT",
      priceUseMode: "NORMAL",
      discountType: "NORMAL",
      name: "Cafe molido",
      description: "Paquete 250g",
      comments: "Alta desde stock",
      purchasePrice: "2.40",
      taxesIncluded: true,
      code: "A001",
      barcode: "8430000000011",
      barcode2: "8430000000012",
      salePrice: "3.95",
      memberPrice: "3.70",
      wholesalePrice: "3.40",
      offerPrice: "3.20",
      offerDiscountPercent: "10",
      purchaseDiscountPercent: null,
      offerActive: false,
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

  it("maps product dialog keyboard shortcuts to actions", () => {
    expect(productCreateKeyAction("Escape")).toBe("close");
    expect(productCreateKeyAction("F8")).toBe("saveContinue");
    expect(productCreateKeyAction("F9")).toBe("saveClose");
    expect(productCreateKeyAction("Enter")).toBeNull();
  });

  it("requires the fields that the backend cannot store as null", () => {
    expect(productCreateValidationErrors(createDefaultProductForm())).toEqual([
      "familyId",
      "taxId",
      "name",
      "code",
      "barcode"
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
    const form: ProductCreateFormState = {
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
    expect(canLeaveProductField({ ...form, name: "Cafe", code: "", barcode: "" }, "code")).toBe(false);
    expect(canLeaveProductField({ ...form, name: "Cafe", code: "", barcode: "843" }, "code")).toBe(true);
  });

  it("allows moving from the first identifier to the second before requiring one of them", () => {
    const form = createDefaultProductForm();

    expect(canNavigateProductField(form, "barcode", false)).toBe(true);
    expect(canNavigateProductField(form, "code", false)).toBe(false);
    expect(canNavigateProductField(form, "code", true)).toBe(true);
  });

  it("does not allow advancing from offer fields when an active offer is incomplete", () => {
    const form: ProductCreateFormState = {
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      priceUseMode: "OFFER_PRICE",
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
      priceUseMode: "OFFER_PRICE",
      discountType: "DISCOUNT_PRICE",
      name: "Cafe",
      description: "Descripcion",
      comments: "Comentario",
      purchasePrice: "1.20",
      taxesIncluded: false,
      code: "A001",
      barcode: "843",
      barcode2: "844",
      salePrice: "2.40",
      memberPrice: "2.10",
      wholesalePrice: "1.90",
      offerPrice: "1.80",
      offerFrom: "2026-07-01",
      offerUntil: ""
    });

    expect(Object.keys(request)).toEqual([
      "familyId",
      "subfamilyId",
      "taxId",
      "productType",
      "priceUseMode",
      "discountType",
      "name",
      "description",
      "comments",
      "purchasePrice",
      "taxesIncluded",
      "code",
      "barcode",
      "barcode2",
      "salePrice",
      "memberPrice",
      "wholesalePrice",
      "offerPrice",
      "offerDiscountPercent",
      "purchaseDiscountPercent",
      "offerActive",
      "offerFrom",
      "offerUntil"
    ]);
    expect(request.offerUntil).toBeNull();
    expect(request.priceUseMode).toBe("OFFER_PRICE");
    expect(request.offerDiscountPercent).toBeNull();
    expect(request.purchaseDiscountPercent).toBeNull();
  });

  it("detects duplicated product identifiers before saving", () => {
    const form = {
      ...createDefaultProductForm(),
      code: " A001 ",
      barcode: "843",
      barcode2: "844"
    };

    expect(duplicatedProductIdentifierFields(form, [{ id: "product-2", code: "A001", barcode: null, barcode2: null }])).toEqual(["code"]);
    expect(duplicatedProductIdentifierFields({ ...form, code: "843" }, [])).toEqual(["code", "barcode"]);
    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      barcode: "843",
      code: "A001"
    }, [{ id: "product-2", code: null, barcode: null, barcode2: "843" }])).toContain("identifierDuplicate");
  });

  it("requires offer price and start date when the offer is active", () => {
    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      priceUseMode: "OFFER_PRICE"
    })).toEqual(["offerPrice", "offerFrom"]);
  });

  it("derives active offer from offer price modes", () => {
    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      priceUseMode: "OFFER_PRICE",
      discountType: "DISCOUNT_PRICE",
      offerPrice: "2.50",
      offerFrom: "2026-07-01"
    })).toEqual([]);

    expect(buildCreateProductRequest({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      priceUseMode: "OFFER_DISCOUNT",
      discountType: "NORMAL",
      salePrice: "10.00",
      offerDiscountPercent: "15",
      offerFrom: "2026-07-01"
    })).toMatchObject({
      priceUseMode: "OFFER_DISCOUNT",
      discountType: "DISCOUNT_PRICE",
      offerPrice: "8.50",
      offerDiscountPercent: "15",
      offerActive: true
    });
  });

  it("sends no-discount lock as DiscountType none with sale price mode", () => {
    expect(buildCreateProductRequest({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Cafe",
      code: "A001",
      priceUseMode: "OFFER_PRICE",
      discountType: "NONE",
      offerPrice: "8.50",
      offerFrom: "2026-07-01"
    })).toMatchObject({
      priceUseMode: "NORMAL",
      discountType: "NONE",
      offerActive: false
    });
  });

  it("restores the persisted no-discount lock when editing a product", () => {
    const initialData = {
      discountType: "NONE" as const,
      purchaseDiscountPercent: "12.50"
    };
    const form = createProductFormFromEditProduct({
      id: "product-1",
      form: {
        priceUseMode: "OFFER_PRICE",
        discountType: "NORMAL",
        offerActive: true,
        offerPrice: "8.50",
        offerFrom: "2026-07-01"
      },
      initialData
    });

    expect(form).toMatchObject({
      priceUseMode: "NORMAL",
      discountType: "NONE",
      offerActive: false
    });
    expect(buildCreateProductRequest(form, initialData)).toMatchObject({
      priceUseMode: "NORMAL",
      discountType: "NONE",
      purchaseDiscountPercent: "12.50",
      offerActive: false,
      offerPrice: null,
      offerDiscountPercent: null
    });
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
    expect(html).toContain("Código");
    expect(html).toContain("Código de barras 2");
    expect(html).toContain("Tipo");
    expect(html).toContain("Familia");
    expect(html).toContain("Impuesto");
    expect(html).toContain("Oferta desde y hasta");
    expect(html).toContain("Descuento oferta%");
    expect(html).toContain("Usar precio");
    expect(html).toContain("Precio venta");
    expect(html).toContain("No aplicar descuento");
    expect(html).toContain("No aplicar descuento activado: no se aplicará ningún tipo de descuento y el vendedor tampoco podrá aplicarlo manualmente");
    expect(html).toContain("Precio socio");
    expect(html).toContain("Precio oferta");
    expect(html).toContain("Descuento oferta");
    expect(html).toContain("required");
    expect(html).toContain("Examinar archivo");
    expect(html).toContain("Eliminar imagen");
    expect(html.indexOf("data-product-field-name=\"barcode\"")).toBeLessThan(html.indexOf("data-product-field-name=\"code\""));
    expect(html).toContain("Registrar producto y continuar F8");
    expect(html).toContain("Registrar producto y cerrar F9");
    expect(html).toContain("Impuestos incluidos en el precio");
    expect(html.indexOf("Usar precio")).toBeLessThan(html.indexOf("Precio oferta"));
    expect(html.indexOf("Precio oferta")).toBeLessThan(html.indexOf("Descuento oferta%"));
    expect(html.indexOf("Descuento oferta%")).toBeLessThan(html.indexOf("Oferta desde y hasta"));
    expect(html.indexOf("Oferta desde y hasta")).toBeLessThan(html.indexOf("Oferta activa"));
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
    expect(html).toContain("Use price");
    expect(html).toContain("Sale price");
    expect(html).toContain("Do not apply discount");
    expect(html).toContain("Do not apply discount enabled, no discount of any kind will be applied, and the seller will not be able to apply it manually");
    expect(html).toContain("Offer discount%");
    expect(html).toContain("Register product and continue F8");
    expect(html).toContain("Register product and close F9");
  });

  it("does not expose the deprecated none or member discount options", () => {
    expect(productDiscountTypeOptions).toEqual(["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"]);
    expect(productDiscountTypeOptions).not.toContain("NONE");
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

  it("updates an existing product when a product id is provided", async () => {
    const initialData = {
      discountType: "NONE" as const,
      purchaseDiscountPercent: "7.25"
    };
    const form = createProductFormFromEditProduct({
      id: "product-1",
      form: {
        familyId: "family-1",
        taxId: "tax-1",
        name: "Cafe",
        code: "A001",
        purchasePrice: "1.20",
        salePrice: "2.40",
        priceUseMode: "NORMAL",
        discountType: "NORMAL"
      },
      initialData
    });
    const updatedProduct = { id: "product-1", code: "A001", name: "Cafe" };
    const createProduct = vi.fn();
    const updateProduct = vi.fn().mockResolvedValue(updatedProduct);

    await expect(saveProductWithOptionalImage({
      form,
      token: "token",
      imageFile: null,
      productId: "product-1",
      initialData,
      createProduct,
      updateProduct
    })).resolves.toEqual({
      product: updatedProduct,
      imageUploadFailed: false
    });
    expect(createProduct).not.toHaveBeenCalled();
    expect(updateProduct).toHaveBeenCalledWith("product-1", buildCreateProductRequest(form, initialData), "token");
    expect(updateProduct).toHaveBeenCalledWith("product-1", expect.objectContaining({
      discountType: "NONE",
      purchaseDiscountPercent: "7.25"
    }), "token");
  });
});

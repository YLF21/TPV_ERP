// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { fireEvent, render, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import {
  applyProductRequiredDefaults,
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
  productImageReadPath,
  productImageUploadPath,
  preferredProductSupplier,
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
      active: true,
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
      packageQuantity: "1",
      stockMin: null,
      stockMax: null,
      requiresSerialNumber: false,
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
    expect(productCreateKeyAction("F8")).toBeNull();
    expect(productCreateKeyAction("F9")).toBe("save");
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

  it("does not allow advancing from offer price when an active offer has no price", () => {
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
    expect(canLeaveProductField({ ...form, offerPrice: "2.50" }, "offerRange")).toBe(true);
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
      "active",
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
      "packageQuantity",
      "stockMin",
      "stockMax",
      "requiresSerialNumber",
      "offerActive",
      "offerFrom",
      "offerUntil"
    ]);
    expect(request.offerUntil).toBeNull();
    expect(request.priceUseMode).toBe("OFFER_PRICE");
    expect(request.offerDiscountPercent).toBeNull();
    expect(request.purchaseDiscountPercent).toBeNull();
    expect(request.packageQuantity).toBe("1");
    expect(request.stockMin).toBeNull();
    expect(request.stockMax).toBeNull();
  });

  it("sends serial tracking only for unit products and normalizes no discount", () => {
    const base = { ...createDefaultProductForm(), familyId: "f", taxId: "t", name: "A", code: "A", purchasePrice: "1", salePrice: "2" };
    expect(buildCreateProductRequest({ ...base, requiresSerialNumber: true })).toMatchObject({
      productType: "UNIT", requiresSerialNumber: true, priceUseMode: "NORMAL"
    });
    expect(buildCreateProductRequest({ ...base, productType: "WEIGHT", requiresSerialNumber: true })).toMatchObject({
      productType: "WEIGHT", requiresSerialNumber: false
    });
    expect(buildCreateProductRequest({ ...base, discountType: "NONE", priceUseMode: "OFFER_PRICE" })).toMatchObject({
      discountType: "NONE", priceUseMode: "NORMAL", offerPrice: null
    });
  });

  it("uses edited form values as authoritative when clearing persisted fields", () => {
    const form = {
      ...createDefaultProductForm(), familyId: "f", taxId: "t", name: "A", code: "A",
      purchasePrice: "1", salePrice: "2", purchaseDiscountPercent: "", packageQuantity: "",
      stockMin: "", stockMax: ""
    };
    expect(buildCreateProductRequest(form, {
      purchaseDiscountPercent: "12", packageQuantity: "6", stockMin: "2", stockMax: "10"
    })).toMatchObject({
      purchaseDiscountPercent: null, packageQuantity: "1", stockMin: null, stockMax: null
    });
  });

  it("validates stock bounds and non-negative package quantities", () => {
    const base = {
      ...createDefaultProductForm(), familyId: "f", taxId: "t", name: "A", code: "A",
      purchasePrice: "1", salePrice: "2",
    };
    expect(productCreateValidationErrors({ ...base, packageQuantity: "-1" })).toContain("packageQuantity");
    expect(productCreateValidationErrors({ ...base, packageQuantity: "abc" })).toContain("packageQuantity");
    expect(productCreateValidationErrors({ ...base, stockMin: "8", stockMax: "3" })).toContain("stockMax");
    expect(productCreateValidationErrors({ ...base, stockMin: "-1" })).toContain("stockMin");
    expect(productCreateValidationErrors({ ...base, packageQuantity: "0", stockMin: "0", stockMax: "0" }))
      .not.toEqual(expect.arrayContaining(["packageQuantity", "stockMin", "stockMax"]));
  });

  it("detects duplicated product identifiers before saving", () => {
    const form = {
      ...createDefaultProductForm(),
      code: " A001 ",
      barcode: "843",
      barcode2: "844"
    };

    expect(duplicatedProductIdentifierFields(form, [{ id: "product-2", code: "A001", barcode: null, barcode2: null }])).toEqual(["code"]);
    expect(duplicatedProductIdentifierFields({ ...form, code: "843" }, [])).toEqual([]);
    expect(duplicatedProductIdentifierFields({ ...form, code: "843", barcode2: "843" }, [])).toEqual(["code", "barcode", "barcode2"]);
    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Articulo",
      code: "0",
      barcode: "0",
      purchasePrice: "0",
      salePrice: "5"
    }, [{ id: "product-1", code: "0", barcode: "0", barcode2: null }], "product-1")).not.toContain("identifierDuplicate");
    expect(buildCreateProductRequest({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "Articulo",
      code: "2",
      barcode: "2",
      purchasePrice: "0",
      salePrice: "2"
    })).toMatchObject({
      code: "2",
      barcode: "2"
    });
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
    })).toEqual(["offerPrice"]);
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

    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "ARTICULO",
      code: "0",
      barcode: "",
      purchasePrice: "0",
      salePrice: "05",
      priceUseMode: "OFFER_PRICE",
      discountType: "DISCOUNT_PRICE",
      offerPrice: "3",
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    }, [{ id: "product-1", code: "0", barcode: "", barcode2: null }], "product-1")).toEqual([]);

    expect(productCreateValidationErrors({
      ...createDefaultProductForm(),
      familyId: "family-1",
      taxId: "tax-1",
      name: "ARTICULO",
      code: "0",
      barcode: "",
      purchasePrice: "0",
      salePrice: "05",
      priceUseMode: "OFFER_PRICE",
      discountType: "DISCOUNT_PRICE",
      offerPrice: "3",
      offerFrom: "",
      offerUntil: ""
    }, [{ id: "product-1", code: "0", barcode: "", barcode2: null }], "product-1")).toEqual([]);

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
      offerActive: false,
      offerPrice: "8.50"
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
      offerPrice: "8.50",
      offerDiscountPercent: null
    });
  });

  it("uses required catalog defaults before validating an edited product", () => {
    const form = createProductFormFromEditProduct({
      id: "product-1",
      form: {
        familyId: "",
        taxId: "",
        name: "ARTICULO",
        code: "0",
        barcode: "",
        purchasePrice: "0",
        salePrice: "5",
        priceUseMode: "OFFER_PRICE",
        offerPrice: "3",
        offerFrom: "2026-07-01",
        offerUntil: "2026-07-31"
      }
    });
    const resolved = applyProductRequiredDefaults(
      form,
      [{ id: "family-general", name: "GENERAL", defaultFamily: true }],
      [{ id: "tax-21", percentage: 21, defaultTax: true }]
    );

    expect(resolved).toMatchObject({
      familyId: "family-general",
      taxId: "tax-21"
    });
    expect(productCreateValidationErrors(resolved, [], "product-1")).toEqual([]);
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
    expect(html).toContain('class="filter-overlay product-create-overlay"');
    expect(html).toContain('class="filter-dialog product-create-dialog"');
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
    expect(html).toContain("bloquea precio socio");
    expect(html).toContain("el precio mayorista sí está permitido");
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('aria-describedby="product-no-discount-description"');
    expect(html).toContain('id="product-no-discount-description"');
    expect(html).toContain("Precio socio");
    expect(html).toContain("Precio oferta");
    expect(html).toContain("Descuento oferta");
    expect(html).toContain("required");
    expect(html).toContain("Examinar archivo");
    expect(html).toContain("Eliminar imagen");
    expect(html.indexOf("data-product-field-name=\"barcode\"")).toBeLessThan(html.indexOf("data-product-field-name=\"code\""));
    expect(html).toContain("Guardar F9");
    expect(html).not.toContain("Registrar producto y continuar F8");
    expect(html).not.toContain("Registrar producto y cerrar F9");
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
    expect(html).toContain("member prices, offers, category/member discounts, coupons, promotions, member balance and manual discounts are blocked; wholesale pricing is allowed");
    expect(html).toContain("Offer discount%");
    expect(html).toContain("Save F9");
    expect(html).not.toContain("Register product and continue F8");
    expect(html).not.toContain("Register product and close F9");
  });

  it("exposes the primary supplier selector when modifying an existing product", () => {
    const html = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{ id: "product-1", form: { name: "Cafe" } }}
        onClose={() => undefined}
      />
    );

    expect(html).toContain("Proveedor principal");
    expect(html).toContain("Sin proveedores vinculados");
  });

  it("creates products active by default and exposes the state only while editing", () => {
    const createHtml = renderToStaticMarkup(
      <ProductCreateDialog open locale="es" token="token" onClose={() => undefined} />
    );
    const editHtml = renderToStaticMarkup(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{ id: "product-1", form: { name: "Cafe", active: false } }}
        onClose={() => undefined}
      />
    );

    expect(createDefaultProductForm().active).toBe(true);
    expect(createHtml).not.toContain('data-product-field-name="active"');
    expect(editHtml).toContain('data-product-field-name="active"');
    expect(editHtml).toContain("Desactivado");
    expect(buildCreateProductRequest(createProductFormFromEditProduct({
      id: "product-1",
      form: { active: false }
    }))).toMatchObject({ active: false });
  });

  it("does not expose the deprecated none or member discount options", () => {
    expect(productDiscountTypeOptions).toEqual(["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"]);
    expect(productDiscountTypeOptions).not.toContain("NONE");
    expect(productDiscountTypeOptions).not.toContain("MEMBER_DISCOUNT");
  });

  it("locks every use-price option while no discount is active and unlocks them from the lock control", () => {
    const { container } = render(
      <ProductCreateDialog
        open
        locale="es"
        token="token"
        editProduct={{ id: "product-1", form: { discountType: "NONE" } }}
        onClose={() => undefined}
      />
    );

    const priceOptions = within(container).getAllByRole("radio");
    expect(priceOptions).not.toHaveLength(0);
    priceOptions.forEach((option) => expect(option).toBeDisabled());

    fireEvent.click(container.querySelector<HTMLButtonElement>(
      'button[data-product-field-name="discountType"]',
    )!);

    priceOptions.forEach((option) => expect(option).not.toBeDisabled());
  });

  it("builds the product image upload path", () => {
    expect(productImageUploadPath("product-1")).toBe("/products/product-1/image");
    expect(productImageReadPath("product-1")).toBe("/products/product-1/image");
  });

  it("shows a manually selected primary supplier before the latest supplier", () => {
    const suppliers = [
      {
        supplierId: "supplier-last",
        legalName: "Proveedor ultimo",
        active: true,
        principal: false,
        lastSupplier: true
      },
      {
        supplierId: "supplier-primary",
        legalName: "Proveedor principal",
        active: true,
        principal: true,
        lastSupplier: false
      }
    ];

    expect(preferredProductSupplier(suppliers)?.supplierId).toBe("supplier-primary");
    expect(preferredProductSupplier(suppliers.slice(0, 1))?.supplierId).toBe("supplier-last");
    expect(preferredProductSupplier([])).toBeNull();
  });

  it("does not expose low-level network write errors in product dialog status", () => {
    expect(productCreateErrorMessage(new TypeError("Failed to write request"), "No se pudo cargar")).toBe("No se pudo cargar");
    expect(productCreateErrorMessage(new Error("Failed to write request"), "No se pudo cargar")).toBe("No se pudo cargar");
    expect(productCreateErrorMessage(new Error("Codigo duplicado"), "No se pudo cargar")).toBe("Codigo duplicado");
    expect(productCreateErrorMessage(
      new ApiError("La operacion entra en conflicto con los datos existentes", 409, { code: "DATA_INTEGRITY_CONFLICT" }),
      "No se pudo registrar",
      "El codigo o codigo de barras ya existe"
    )).toBe("El codigo o codigo de barras ya existe");
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

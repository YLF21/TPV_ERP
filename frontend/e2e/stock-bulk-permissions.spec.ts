import { expect, test } from "@playwright/test";
import {
  apiGet,
  apiPost,
  apiPut,
  apiUrl,
  authorization,
  cleanupBulkDrafts,
  createProductFixture,
  deleteProductFixture,
  expectApiOk,
  loginApi,
  type BulkDraftView,
  uniqueMarker
} from "./support/testApi";
import {
  cleanupProductManagerFixtures,
  createProductManagerFixtures,
  type ProductManagerFixtures
} from "./support/securityFixtures";

test("GESTION_PRODUCTO comparte edición y aplicación, pero protege la eliminación", async ({ request }) => {
  const admin = await loginApi(request);
  const marker = uniqueMarker("PERM").replaceAll("-", "").slice(-12);
  const listPrefix = `E2E PERMISOS ${marker}`;
  let fixtures: ProductManagerFixtures | null = null;
  const product = await createProductFixture(request, admin.accessToken, `E2E-PERM-${marker}`);

  try {
    fixtures = await createProductManagerFixtures(request, admin.accessToken, marker);
    const creatorSession = await loginApi(request, fixtures.creator.name, fixtures.creator.password);
    const colleagueSession = await loginApi(request, fixtures.colleague.name, fixtures.colleague.password);

    const created = await apiPost<BulkDraftView>(
      request,
      creatorSession.accessToken,
      "/product-bulk-edits",
      { name: `${listPrefix} COMPARTIDA`, content: [] }
    );
    const visible = await apiGet<BulkDraftView[]>(request, colleagueSession.accessToken, "/product-bulk-edits");
    expect(visible.some((draft) => draft.id === created.id)).toBe(true);

    const content = [{
      id: `e2e-${product.id}`,
      selected: true,
      query: product.code ?? "",
      product: {
        productId: product.id,
        version: product.version,
        imageId: product.imageId,
        code: product.code,
        barcode: product.barcode,
        barcode2: product.barcode2,
        name: product.name,
        description: product.description,
        comments: product.comments,
        purchasePrice: String(product.purchasePrice),
        purchaseDiscountPercent: product.purchaseDiscountPercent === null ? null : String(product.purchaseDiscountPercent),
        salePrice: String(product.salePrice),
        memberPrice: product.memberPrice === null ? null : String(product.memberPrice),
        wholesalePrice: product.wholesalePrice === null ? null : String(product.wholesalePrice),
        offerPrice: product.offerPrice === null ? null : String(product.offerPrice),
        offerDiscountPercent: product.offerDiscountPercent === null ? null : String(product.offerDiscountPercent),
        productType: product.productType,
        discountType: product.priceUseMode,
        backendDiscountType: product.discountType,
        familyId: product.familyId,
        subfamilyId: product.subfamilyId,
        taxId: product.taxId,
        taxesIncluded: product.taxesIncluded ? "common.yes" : "common.no",
        offerActive: product.offerActive ? "common.yes" : "common.no",
        offerFrom: product.offerFrom,
        offerUntil: product.offerUntil
      },
      draft: { salePrice: "11" },
      suppliers: []
    }];

    const modified = await apiPut<BulkDraftView>(
      request,
      colleagueSession.accessToken,
      `/product-bulk-edits/${encodeURIComponent(created.id)}`,
      { version: created.version, name: `${listPrefix} MODIFICADA`, content }
    );
    const applied = await apiPost<BulkDraftView>(
      request,
      colleagueSession.accessToken,
      `/product-bulk-edits/${encodeURIComponent(created.id)}/apply`,
      {
        version: modified.version,
        updates: [{
          productId: product.id,
          expectedVersion: product.version,
          product: {
            familyId: product.familyId,
            subfamilyId: product.subfamilyId,
            taxId: product.taxId,
            productType: product.productType,
            discountType: product.discountType,
            priceUseMode: product.priceUseMode,
            name: product.name,
            description: product.description,
            comments: product.comments,
            purchasePrice: product.purchasePrice,
            purchaseDiscountPercent: product.purchaseDiscountPercent,
            taxesIncluded: product.taxesIncluded,
            code: product.code,
            barcode: product.barcode,
            barcode2: product.barcode2,
            salePrice: 11,
            memberPrice: product.memberPrice,
            wholesalePrice: product.wholesalePrice,
            offerPrice: product.offerPrice,
            offerDiscountPercent: product.offerDiscountPercent,
            offerActive: product.offerActive,
            offerFrom: product.offerFrom,
            offerUntil: product.offerUntil
          }
        }],
        supplierAssignments: [],
        content
      }
    );
    expect(applied.status).toBe("APPLIED");

    const forbiddenDelete = await request.delete(
      `${apiUrl}/product-bulk-edits/${encodeURIComponent(applied.id)}?version=${applied.version}`,
      { headers: authorization(colleagueSession.accessToken) }
    );
    expect(forbiddenDelete.status()).toBe(403);

    const creatorDraft = await apiPost<BulkDraftView>(
      request,
      creatorSession.accessToken,
      "/product-bulk-edits",
      { name: `${listPrefix} CREADOR`, content: [] }
    );
    const creatorDelete = await request.delete(
      `${apiUrl}/product-bulk-edits/${encodeURIComponent(creatorDraft.id)}?version=${creatorDraft.version}`,
      { headers: authorization(creatorSession.accessToken) }
    );
    await expectApiOk(creatorDelete, "El creador no pudo eliminar su lista");

    const adminDelete = await request.delete(
      `${apiUrl}/product-bulk-edits/${encodeURIComponent(applied.id)}?version=${applied.version}`,
      { headers: authorization(admin.accessToken) }
    );
    await expectApiOk(adminDelete, "ADMIN no pudo eliminar la lista aplicada");
  } finally {
    await cleanupBulkDrafts(request, admin.accessToken, listPrefix).catch(() => undefined);
    await deleteProductFixture(request, admin.accessToken, product.id).catch(() => undefined);
    if (fixtures) cleanupProductManagerFixtures(fixtures);
  }
});

import { expect, test, type APIRequestContext } from "@playwright/test";
import { apiGet, apiPost, apiPut, apiUrl, authorization, cleanupBulkDrafts, createProductFixture,
  deleteProductFixture, loginApi, productById, uniqueMarker, type ProductView } from "./support/testApi";
import { loginUi, openBulkEdit, openStock } from "./support/ui";

type Product = ProductView & Record<string, any>;
type Draft = { id: string; version: number; status: string; content: any[] };

function row(product: Product, changes: Record<string, unknown> = {}) {
  const snapshot: Record<string, unknown> = { productId: product.id, version: product.version };
  for (const field of ["code", "barcode", "barcode2", "name", "description", "comments", "familyId", "subfamilyId", "taxId",
    "productType", "purchasePrice", "purchaseDiscountPercent", "salePrice", "memberPrice", "wholesalePrice", "offerPrice",
    "offerDiscountPercent", "taxesIncluded", "offerActive", "offerFrom", "offerUntil", "stockMin", "stockMax", "packageQuantity", "active"]) {
    snapshot[field] = product[field] == null ? "" : String(product[field]);
  }
  snapshot.discountType = product.priceUseMode;
  snapshot.backendDiscountType = product.discountType;
  return { id: `row-${product.id}`, selected: false, query: product.code, product: snapshot,
    draft: Object.fromEntries(Object.entries(changes).map(([key, value]) => [key, value == null ? "" : String(value)])), suppliers: [] };
}

function application(draft: Draft, products: Product[], changes: Record<string, unknown>[]) {
  return { version: draft.version, content: products.map((p, i) => row(p, changes[i])),
    updates: products.map((p, i) => ({ productId: p.id, expectedVersion: p.version, product: { ...p, ...changes[i] } })),
    creates: [], supplierAssignments: [], supplierPrincipalAssignments: [] };
}

async function cleanup(request: APIRequestContext, token: string, prefix: string, products: Product[]) {
  await cleanupBulkDrafts(request, token, prefix);
  for (let offset = 0; offset < products.length; offset += 5) {
    await Promise.all(products.slice(offset, offset + 5).map(product => deleteProductFixture(request, token, product.id)));
  }
}

for (const field of ["code", "barcode", "barcode2"] as const) {
  test(`un cambio posterior de ${field} invalida una lista antigua`, async ({ request }) => {
    const { accessToken: token } = await loginApi(request);
    const marker = uniqueMarker(`EDGE-${field}`);
    let product: Product = await createProductFixture(request, token, marker);
    try {
      if (field !== "code") product = await apiPut(request, token, `/products/${product.id}`, { ...product, [field]: `001-${marker}` });
      const draft = await apiPost<Draft>(request, token, "/product-bulk-edits", { name: marker, content: [row(product, { salePrice: 17 })] });
      const newIdentifier = `002-${marker}`;
      await apiPut(request, token, `/products/${product.id}`, { ...product, [field]: newIdentifier });
      const response = await request.post(`${apiUrl}/product-bulk-edits/${draft.id}/apply`, {
        headers: authorization(token), data: application(draft, [product], [{ salePrice: 17 }])
      });
      expect(response.status(), await response.text()).toBe(409);
      expect(await productById(request, token, product.id)).toMatchObject({ [field]: newIdentifier, salePrice: 10 });
    } finally { await cleanup(request, token, marker, [product]); }
  });
}

test("un conflicto en la última fila revierte el lote entero", async ({ request }) => {
  const { accessToken: token } = await loginApi(request);
  const marker = uniqueMarker("EDGE-ATOMIC");
  const products: Product[] = [];
  try {
    for (let i = 0; i < 3; i++) products.push(await createProductFixture(request, token, `${marker}-${i}`));
    const changes = products.map(() => ({ salePrice: 19 }));
    const draft = await apiPost<Draft>(request, token, "/product-bulk-edits", { name: marker, content: products.map((p, i) => row(p, changes[i])) });
    await apiPut(request, token, `/products/${products[2].id}`, { ...products[2], name: `${marker} CAMBIADO` });
    const response = await request.post(`${apiUrl}/product-bulk-edits/${draft.id}/apply`, {
      headers: authorization(token), data: application(draft, products, changes)
    });
    expect(response.status(), await response.text()).toBe(409);
    for (const p of products) expect((await productById(request, token, p.id)).salePrice).toBe(10);
    expect(await apiGet(request, token, `/product-bulk-edits/${draft.id}`)).toMatchObject({ status: "PENDING" });
  } finally { await cleanup(request, token, marker, products); }
});

test("códigos duplicados rechazan todo el lote sin modificar los productos", async ({ request }) => {
  const { accessToken: token } = await loginApi(request);
  const marker = uniqueMarker("EDGE-DUPLICATE");
  const products: Product[] = [];
  try {
    for (let i = 0; i < 2; i++) products.push(await createProductFixture(request, token, `${marker}-${i}`));
    const changes = products.map(() => ({ code: `${marker}-DUP`, salePrice: 19 }));
    const draft = await apiPost<Draft>(request, token, "/product-bulk-edits", { name: marker, content: products.map((p, i) => row(p, changes[i])) });
    const response = await request.post(`${apiUrl}/product-bulk-edits/${draft.id}/apply`, {
      headers: authorization(token), data: application(draft, products, changes)
    });
    expect([400, 409], await response.text()).toContain(response.status());
    for (const p of products) expect(await productById(request, token, p.id)).toMatchObject({ code: p.code, salePrice: 10 });
    expect(await apiGet(request, token, `/product-bulk-edits/${draft.id}`)).toMatchObject({ status: "PENDING" });
  } finally { await cleanup(request, token, marker, products); }
});

test("guardar y aplicar conserva ceros iniciales, Unicode, tres decimales y borrados explícitos", async ({ request }) => {
  const { accessToken: token } = await loginApi(request);
  const marker = uniqueMarker("EDGE-VALUES");
  let product: Product = await createProductFixture(request, token, marker);
  try {
    product = await apiPut(request, token, `/products/${product.id}`, { ...product, barcode: `000-${marker}`, memberPrice: 9,
      wholesalePrice: 8, comments: "Comentario anterior", stockMin: 2, stockMax: 20 });
    const changes = { name: "Café Ñandú 中文 🛒", description: "Línea 1\nLínea 2", salePrice: "0.001",
      comments: null, memberPrice: null, wholesalePrice: null, barcode2: `0000-${marker}`, stockMin: 0, stockMax: null };
    const draft = await apiPost<Draft>(request, token, "/product-bulk-edits", { name: marker, content: [row(product, changes)] });
    expect(await productById(request, token, product.id)).toMatchObject({ salePrice: 10, memberPrice: 9 });
    const reopened = await apiGet<Draft>(request, token, `/product-bulk-edits/${draft.id}`);
    expect(reopened.content[0].draft).toMatchObject({ comments: "", memberPrice: "", stockMin: "0" });
    await apiPost(request, token, `/product-bulk-edits/${draft.id}/apply`, application(reopened, [product], [changes]));
    const after = await apiGet<Product>(request, token, `/products/management/${product.id}`);
    expect(after).toMatchObject({ name: changes.name.toUpperCase(), description: changes.description, salePrice: 0.001,
      barcode: product.barcode, barcode2: changes.barcode2, stockMin: 0 });
    for (const field of ["comments", "memberPrice", "wholesalePrice", "stockMax"]) expect(after[field] ?? null).toBeNull();
    const repeated = await request.post(`${apiUrl}/product-bulk-edits/${draft.id}/apply`, {
      headers: authorization(token), data: application(reopened, [product], [changes])
    });
    expect(repeated.status(), await repeated.text()).toBe(409);
  } finally { await cleanup(request, token, marker, [product]); }
});

test("dos guardados con la misma versión no pierden cambios", async ({ request }) => {
  const { accessToken: token } = await loginApi(request);
  const marker = uniqueMarker("EDGE-LIST-VERSION");
  const product = await createProductFixture(request, token, marker);
  try {
    const draft = await apiPost<Draft>(request, token, "/product-bulk-edits", { name: marker, content: [row(product)] });
    await apiPut(request, token, `/product-bulk-edits/${draft.id}`, { version: draft.version, name: marker, content: [row(product, { salePrice: 12 })] });
    const response = await request.put(`${apiUrl}/product-bulk-edits/${draft.id}`, {
      headers: authorization(token), data: { version: draft.version, name: marker, content: [row(product, { salePrice: 15 })] }
    });
    expect(response.status(), await response.text()).toBe(409);
    expect((await apiGet<Draft>(request, token, `/product-bulk-edits/${draft.id}`)).content[0].draft.salePrice).toBe("12");
    expect((await productById(request, token, product.id)).salePrice).toBe(10);
  } finally { await cleanup(request, token, marker, [product]); }
});

test("una lista de 100 productos se reabre y aplica sin perder filas", async ({ page, request }) => {
  test.setTimeout(180_000);
  const { accessToken: token } = await loginApi(request);
  const marker = uniqueMarker("EDGE-100");
  const products: Product[] = [];
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => {
    if (message.type() === "error" && message.text().includes("same key")) errors.push(message.text());
  });
  try {
    for (let offset = 0; offset < 100; offset += 5) {
      products.push(...await Promise.all(Array.from({ length: 5 }, (_, index) =>
        createProductFixture(request, token, `${marker}-${String(offset + index).padStart(3, "0")}`))));
    }
    const content = products.map((p, i) => row(p, { salePrice: (11 + i / 1000).toFixed(3) }));
    const draft = await apiPost<Draft>(request, token, "/product-bulk-edits", { name: marker, content });
    expect((await apiGet<Draft>(request, token, `/product-bulk-edits/${draft.id}`)).content).toHaveLength(100);
    await page.setViewportSize({ width: 1920, height: 1080 });
    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("row").filter({ hasText: marker }).dblclick();
    await page.getByRole("button", { name: "Aplicar cambios", exact: true }).click();
    const response = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith(`/${draft.id}/apply`));
    await page.getByRole("dialog", { name: "Aplicar cambios" }).getByRole("button", { name: "Aplicar cambios", exact: true }).click();
    expect((await response).ok()).toBeTruthy();
    await expect(page.locator(".bulk-edit-status")).toContainText("Cambios aplicados");
    for (let offset = 0; offset < products.length; offset += 5) {
      const saved = await Promise.all(products.slice(offset, offset + 5).map(product => productById(request, token, product.id)));
      saved.forEach((product, index) => expect(product.salePrice).toBe(Number((11 + (offset + index) / 1000).toFixed(3))));
    }
    expect(errors).toEqual([]);
  } finally { await cleanup(request, token, marker, products); }
});

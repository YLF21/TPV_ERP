import { expect, type APIRequestContext, type APIResponse } from "@playwright/test";

export const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
export const apiUrl = `${backendUrl}/api/v1`;
export const ventaUrl = process.env.E2E_VENTA_URL ?? "http://127.0.0.1:4173";
export const gestionUrl = process.env.E2E_GESTION_URL ?? "http://127.0.0.1:4174";
export const terminalId = process.env.E2E_TERMINAL_ID ?? "06d2ce45-8ead-349d-b844-4ecdead5e1ec";
export const terminalCredential = process.env.E2E_TERMINAL_CREDENTIAL ?? "DEV-SERVER";

export type LoginSession = {
  accessToken: string;
  userId: string;
  userName: string;
  permissions: string[];
};

export type ProductView = {
  id: string;
  version: number;
  familyId: string;
  subfamilyId: string | null;
  taxId: string;
  productType: string;
  discountType: string;
  priceUseMode: string;
  code: string | null;
  barcode: string | null;
  barcode2: string | null;
  name: string;
  description: string | null;
  comments: string | null;
  purchasePrice: number;
  purchaseDiscountPercent: number | null;
  taxesIncluded: boolean;
  salePrice: number;
  memberPrice: number | null;
  wholesalePrice: number | null;
  offerPrice: number | null;
  offerDiscountPercent: number | null;
  offerActive: boolean;
  offerFrom: string | null;
  offerUntil: string | null;
  imageId: string | null;
};

export type BulkDraftView = {
  id: string;
  version: number;
  versionNumber: number;
  name: string;
  status: "PENDING" | "APPLIED";
  comments: Array<{ text: string }>;
};

export async function loginApi(
  request: APIRequestContext,
  userName = process.env.E2E_ADMIN_USERNAME ?? "ADMIN",
  password = process.env.E2E_ADMIN_PASSWORD ?? "0000"
) {
  const response = await request.post(`${apiUrl}/auth/login`, {
    data: { terminalId, terminalCredential, userName, password }
  });
  await expectApiOk(response, `No se pudo iniciar sesion como ${userName}`);
  return response.json() as Promise<LoginSession>;
}

export async function apiGet<T>(request: APIRequestContext, token: string, path: string) {
  const response = await request.get(`${apiUrl}${path}`, { headers: authorization(token) });
  await expectApiOk(response, `GET ${path}`);
  return response.json() as Promise<T>;
}

export async function apiPost<T>(request: APIRequestContext, token: string, path: string, data: unknown) {
  const response = await request.post(`${apiUrl}${path}`, { headers: authorization(token), data });
  await expectApiOk(response, `POST ${path}`);
  return response.json() as Promise<T>;
}

export async function apiPut<T>(request: APIRequestContext, token: string, path: string, data: unknown) {
  const response = await request.put(`${apiUrl}${path}`, { headers: authorization(token), data });
  await expectApiOk(response, `PUT ${path}`);
  return response.json() as Promise<T>;
}

export async function createProductFixture(request: APIRequestContext, token: string, marker: string) {
  const [families, taxes] = await Promise.all([
    apiGet<Array<{ id: string }>>(request, token, "/families"),
    apiGet<Array<{ id: string }>>(request, token, "/taxes/selectable")
  ]);
  expect(families.length, "La prueba necesita una familia").toBeGreaterThan(0);
  expect(taxes.length, "La prueba necesita un impuesto").toBeGreaterThan(0);
  return apiPost<ProductView>(request, token, "/products", {
    familyId: families[0].id,
    subfamilyId: null,
    taxId: taxes[0].id,
    productType: "UNIT",
    discountType: "NORMAL",
    priceUseMode: "NORMAL",
    name: `PRODUCTO ${marker}`,
    description: "Producto temporal para pruebas E2E",
    comments: null,
    purchasePrice: 5,
    purchaseDiscountPercent: null,
    taxesIncluded: true,
    code: marker,
    barcode: null,
    barcode2: null,
    salePrice: 10,
    memberPrice: null,
    wholesalePrice: null,
    offerPrice: null,
    offerDiscountPercent: null,
    offerActive: false,
    offerFrom: null,
    offerUntil: null
  });
}

export async function deleteProductFixture(request: APIRequestContext, token: string, productId: string) {
  const response = await request.delete(`${apiUrl}/products/${encodeURIComponent(productId)}`, {
    headers: authorization(token)
  });
  if (response.status() !== 404) await expectApiOk(response, "No se pudo borrar el producto E2E");
}

export async function cleanupBulkDrafts(
  request: APIRequestContext,
  token: string,
  namePrefix: string
) {
  const drafts = await apiGet<BulkDraftView[]>(request, token, "/product-bulk-edits");
  for (const draft of drafts.filter((candidate) => candidate.name.startsWith(namePrefix))) {
    const response = await request.delete(
      `${apiUrl}/product-bulk-edits/${encodeURIComponent(draft.id)}?version=${draft.version}`,
      { headers: authorization(token) }
    );
    if (response.status() !== 404) await expectApiOk(response, `No se pudo borrar ${draft.name}`);
  }
}

export async function productById(request: APIRequestContext, token: string, productId: string) {
  return apiGet<ProductView>(request, token, `/products/${encodeURIComponent(productId)}`);
}

export function uniqueMarker(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`.toUpperCase();
}

export function authorization(token: string) {
  return { Authorization: `Bearer ${token}` };
}

export async function expectApiOk(response: APIResponse, operation: string) {
  if (response.ok()) return;
  const body = await response.text();
  throw new Error(`${operation}: HTTP ${response.status()} ${body}`);
}

import { expect, test } from "@playwright/test";
import { strToU8, zipSync } from "fflate";
import { apiGet, createProductFixture, loginApi, uniqueMarker } from "./support/testApi";
import { loginUi } from "./support/ui";

function fixture(marker: string) {
  const esc = (v: string) => v.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const rows = [["Código", "Nombre", "Cantidad", "Precio de compra"], [marker, `UI ${marker}`, "1", "10"]];
  const body = rows.map((row, r) => `<row r="${r + 1}">${row.map((v, c) => `<c r="${String.fromCharCode(65 + c)}${r + 1}" t="inlineStr"><is><t>${esc(v)}</t></is></c>`).join("")}</row>`).join("");
  const files: Record<string, Uint8Array> = {
    "[Content_Types].xml": strToU8('<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'),
    "_rels/.rels": strToU8('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'),
    "xl/workbook.xml": strToU8('<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Productos" sheetId="1" r:id="rId1"/></sheets></workbook>'),
    "xl/_rels/workbook.xml.rels": strToU8('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'),
    "xl/worksheets/sheet1.xml": strToU8(`<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${body}</sheetData></worksheet>`)
  };
  return Buffer.from(zipSync(files));
}

test("importa Excel real a Entrada almacén y deja el documento sin confirmar", async ({ page, request }) => {
  const session = await loginApi(request);
  const marker = uniqueMarker("E2E-UI-WH");
  const product = await createProductFixture(request, session.accessToken, marker);
  const beforeProduct = await apiGet<Record<string, unknown>>(request, session.accessToken, `/products/management/${product.id}`);
  const warehouses = await apiGet<Array<{ id: string }>>(request, session.accessToken, "/warehouses");
  const stockPath = `/stock?productId=${product.id}&warehouseId=${warehouses[0].id}`;
  const beforeStock = await apiGet<unknown[]>(request, session.accessToken, stockPath);
  const mutatingRequests: string[] = [];
  const applyRequests: Array<{ method: string; postData: string | null }> = [];
  const applyResponses: Array<{ appliedCount?: number; warehouseMetadata?: unknown }> = [];
  page.on("request", (requestEvent) => {
    if (["POST", "PUT", "PATCH", "DELETE"].includes(requestEvent.method())) mutatingRequests.push(requestEvent.url());
    if (requestEvent.url().includes("/product-excel-imports/apply")) {
      applyRequests.push({ method: requestEvent.method(), postData: requestEvent.postDataBuffer()?.toString("utf8") ?? requestEvent.postData() });
    }
  });
  page.on("response", async (response) => {
    if (response.url().includes("/product-excel-imports/apply")) {
      const body = await response.json().catch(() => null) as { appliedCount?: number; warehouseMetadata?: unknown } | null;
      if (body) applyResponses.push(body);
    }
  });
  await loginUi(page, "venta");
  await page.getByRole("button", { name: /Almacén/i }).first().click();
  await expect(page.locator(".warehouse-screen")).toBeVisible();
  await page.getByRole("button", { name: "Entrada almacén", exact: true }).click();
  await page.getByRole("button", { name: "Crear documento", exact: true }).click();
  const document = page.locator(".warehouse-document-dialog");
  await expect(document).toBeVisible();
  await document.getByRole("button", { name: "Archivo", exact: true }).click();
  await page.getByRole("menu").getByRole("button", { name: "Importar Excel", exact: true }).click();
  const importDialog = page.getByRole("dialog", { name: /Importar Excel/i });
  await importDialog.locator("input.shared-excel-file-input").setInputFiles({ name: "warehouse-ui.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: fixture(marker) });
  await expect(importDialog.getByRole("button", { name: "Aplicar", exact: true })).toBeVisible();
  await importDialog.getByRole("button", { name: "Aplicar", exact: true }).click();
  await importDialog.getByRole("button", { name: /Productos importables \(1\)/ }).click();
  await expect(importDialog.getByRole("button", { name: /Importar Excel al documento|Importar al documento/, exact: false })).toBeVisible();
  await importDialog.getByRole("button", { name: /Importar Excel al documento|Importar al documento/, exact: false }).click();
  await expect(importDialog).toBeHidden();
  const importedLine = document.locator(".warehouse-document-table tbody tr").filter({ hasText: marker });
  await expect(importedLine).toHaveCount(1);
  await expect(importedLine.locator("td").nth(0)).toHaveText(marker);
  await expect(importedLine.locator("td").nth(2)).toHaveText(`UI ${marker}`);
  await expect(importedLine.locator("td").nth(4)).toHaveText(/10(?:[,.]00)?/);
  await expect(importedLine.locator("td").nth(5)).toHaveText("1");
  await expect(document.getByRole("button", { name: /Confirmar/i })).toBeVisible();
  expect(mutatingRequests.some((url) => /\/confirm(?:\/|\?|$)/i.test(url))).toBe(false);
  expect(mutatingRequests.some((url) => /\/products(?:\/|\?|$)/i.test(url))).toBe(false);
  expect(mutatingRequests.some((url) => /\/warehouse-inputs(?:\/|\?|$)/i.test(url))).toBe(false);
  expect(applyRequests).toHaveLength(1);
  expect(applyRequests[0].method).toBe("POST");
  expect(applyResponses).toHaveLength(1);
  expect(applyResponses[0].warehouseMetadata).toBeTruthy();
  expect(applyResponses[0].appliedCount ?? 0).toBe(0);
  expect(await apiGet<Record<string, unknown>>(request, session.accessToken, `/products/management/${product.id}`)).toEqual(beforeProduct);
  expect(await apiGet<unknown[]>(request, session.accessToken, stockPath)).toEqual(beforeStock);

  const saveResponse = page.waitForResponse((response) => response.url().match(/\/warehouse-inputs(?:\/[^/]+)?$/) !== null && response.request().method() === "POST");
  await document.getByRole("button", { name: /Guardar/i }).first().click();
  const saved = await (await saveResponse).json() as { status?: string; estado?: string; lines?: Array<{ productId?: string; quantity?: number; purchaseUnitPrice?: number }> };
  expect(saved.status ?? saved.estado).toBe("BORRADOR");
  expect(saved.lines).toEqual(expect.arrayContaining([expect.objectContaining({ productId: product.id, quantity: 1, purchaseUnitPrice: 10 })]));
  expect(mutatingRequests.some((url) => /\/confirm(?:\/|\?|$)/i.test(url))).toBe(false);
  await page.screenshot({ path: "../output/playwright/warehouse-ui-results/final-draft.png", fullPage: true });
});

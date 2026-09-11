import { expect, test, type Page } from "@playwright/test";
import { strToU8, zipSync } from "fflate";
import {
  apiGet,
  apiUrl,
  authorization,
  createProductFixture,
  loginApi,
  productById,
  uniqueMarker
} from "./support/testApi";
import { loginUi, openBulkEdit } from "./support/ui";
import { generateExcelImportFixture } from "./support/excelImportFixture";

/** A small, valid OOXML workbook; no fake file or mocked import endpoint is used. */
function productWorkbook(rows: string[][]): Buffer {
  const esc = (value: string) => value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  const xmlRows = rows.map((row, rowIndex) => `<row r="${rowIndex + 1}">${row.map((value, columnIndex) => {
    const ref = `${String.fromCharCode(65 + columnIndex)}${rowIndex + 1}`;
    return `<c r="${ref}" t="inlineStr"><is><t>${esc(value)}</t></is></c>`;
  }).join("")}</row>`).join("");
  const files: Record<string, Uint8Array> = {
    "[Content_Types].xml": strToU8(`<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`),
    "_rels/.rels": strToU8(`<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`),
    "xl/workbook.xml": strToU8(`<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Productos" sheetId="1" r:id="rId1"/></sheets></workbook>`),
    "xl/_rels/workbook.xml.rels": strToU8(`<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>`),
    "xl/worksheets/sheet1.xml": strToU8(`<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${xmlRows}</sheetData></worksheet>`)
  };
  return Buffer.from(zipSync(files));
}

async function openExcelImport(page: Page, file: { name: string; mimeType: string; buffer: Buffer }) {
  await page.getByRole("button", { name: "Archivo", exact: true }).click();
  await page.getByRole("menu").getByRole("button", { name: "Importar", exact: true }).click();
  await page.locator(".bulk-import-menu").getByRole("button", { name: "Importar Excel", exact: true }).click();
  await page.locator("input.shared-excel-file-input").setInputFiles(file);
  await expect(page.getByRole("dialog", { name: "Importar Excel" })).toBeVisible();
}

async function configureAndApply(page: Page) {
  const dialog = page.getByRole("dialog", { name: "Importar Excel" });
  await expect(dialog.getByLabel("Código Columna Excel")).toHaveValue("A");
  await expect(dialog.getByLabel("Nombre Columna Excel")).toHaveValue("B");
  await expect(dialog.getByLabel("Precio de compra Columna Excel")).toHaveValue("C");
  await dialog.getByLabel("Actualizar Nombre").check();
  await dialog.getByLabel("Actualizar Precio de compra").check();
  await dialog.getByLabel("Generar documento resumen").check();
  await page.setViewportSize({ width: 1366, height: 768 });
  const ordinaryMappingInput = dialog.locator('.shared-excel-mapping input[type="text"]').first();
  const ordinaryMappingBox = await ordinaryMappingInput.boundingBox();
  expect(ordinaryMappingBox?.width).toBe(52);
  await page.screenshot({ path: "../output/playwright/excel-import-mapping-1366.png", fullPage: true });
  for (const label of ["Usar precio", "Prohibido descuento", "Impuestos", "Impuestos incluidos"]) {
    const trigger = dialog.getByRole("button", { name: label, exact: true });
    const input = dialog.getByRole("combobox", { name: `${label} Columna o valor` });
    await input.fill("aa");
    await expect(input).toHaveValue("AA");
    await input.press("ArrowDown");
    const options = dialog.getByRole("listbox").getByRole("option");
    await expect(options.first()).toBeFocused();
    await options.first().press("Enter");
    await expect(input).toBeFocused();
    await expect(input).toHaveValue(label === "Impuestos" ? /[\d.,]+\s*%/ : label === "Usar precio" ? "1" : "0");
    await input.fill("zz");
    await expect(input).toHaveValue("ZZ");
    await trigger.click();
    await dialog.getByRole("listbox").getByRole("option").first().click();
    await expect(input).not.toHaveValue("ZZ");
    await input.fill("");
    await expect(input).toHaveValue("");
  }
  const priceCombo = dialog.getByRole("button", { name: "Usar precio", exact: true });
  const priceArrowBox = await priceCombo.locator(".erp-select__arrow").boundingBox();
  expect(priceArrowBox?.width).toBe(28);
  const priceColumn = dialog.getByRole("combobox", { name: "Usar precio Columna o valor" });
  const priceColumnBox = await priceColumn.boundingBox();
  expect(priceColumnBox?.width).toBe(52);
  await priceColumn.fill("A");
  await priceColumn.fill("");
  await dialog.getByRole("heading", { name: "Importar Excel" }).click();
  await expect(dialog.locator('input[aria-label="Usar precio Columna o valor"]')).toHaveValue("");
  const fixedCombo = dialog.getByRole("button", { name: "Prohibido descuento", exact: true });
  await fixedCombo.click();
  await expect(dialog.getByRole("listbox")).toBeVisible();
  await dialog.getByRole("listbox").getByRole("option").first().click();
  await dialog.getByRole("button", { name: "Aplicar", exact: true }).click();
  await expect(dialog.getByRole("button", { name: /Productos importables/ })).toBeVisible();
  return dialog;
}

test.describe("rediseño importación Excel contra backend aislado", () => {
  test("lee, previsualiza en readonly, exporta y aplica operaciones explícitas", async ({ page, request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("E2E-XLSX");
    const existing = await createProductFixture(request, session.accessToken, marker);
    const before = await apiGet<{ name: string; purchasePrice: number; salePrice: number }>(request, session.accessToken, `/products/management/${existing.id}`);
    const missing = `${marker}-AUTO`;
    const xlsx = productWorkbook([
      ["Código", "Nombre", "Precio de compra", "Precio de venta"],
      [marker, `Producto actualizado ${marker}`, "7.50", "10"],
      [missing, `Producto nuevo ${marker}`, "3.20", "8"]
    ]);
    const applyCalls: string[] = [];
    let createdProductId: string | null = null;
    page.on("request", (requestEvent) => {
      if (requestEvent.url().endsWith("/product-excel-imports/apply")) applyCalls.push(requestEvent.method());
    });

    page.on("response", async (event) => {
      if (event.url().includes("/product-excel-imports/apply")) {
        const body = await event.json().catch(() => null) as { rows?: Array<{ productId?: string | null }> } | null;
        createdProductId = body?.rows?.find((row) => row.productId)?.productId ?? createdProductId;
      }
    });
    await loginUi(page, "venta");
    await page.getByRole("button", { name: "GESTIÓN", exact: true }).click();
    await expect(page.locator(".stock-screen")).toBeVisible();
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();
    await openExcelImport(page, { name: "productos-e2e.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: xlsx });
    const dialog = await configureAndApply(page);

    // Applying the mapping is readonly: it may call /read and /preview, never /apply.
    expect(applyCalls).toEqual([]);
    await expect(dialog.getByRole("status")).toContainText("Aplicado: 1 aceptadas");
    await expect(dialog.getByRole("button", { name: /Productos no importables \(1\)/ })).toBeVisible();
    await expect(dialog.getByRole("button", { name: /Precio de compra distinto \(1\)/ })).toBeVisible();
    await expect(dialog.getByRole("button", { name: /Documento resumen/ })).toBeVisible();
    await expect(dialog.getByRole("button", { name: /Errores \(0\)/ })).toBeVisible();
    const preview = dialog.locator(".shared-excel-review-viewport");
    await expect(preview).toBeVisible();
    await preview.evaluate((element) => { element.scrollLeft = 80; element.scrollTop = 20; });
    const resizer = dialog.locator(".shared-excel-results .table-layout-column-resizer").first();
    await expect(resizer).toBeVisible();
    const header = resizer.locator("xpath=ancestor::th");
    const beforeWidth = await header.evaluate((element) => Math.round(element.getBoundingClientRect().width));
    await resizer.focus();
    await resizer.press("ArrowRight");
    const resizerBox = await resizer.boundingBox();
    if (!resizerBox) throw new Error("La manija de redimensionado no tiene bounding box");
    await page.mouse.move(resizerBox.x + 5, resizerBox.y + resizerBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(resizerBox.x + 25, resizerBox.y + resizerBox.height / 2);
    await page.mouse.up();
    await expect.poll(() => header.evaluate((element) => Math.round(element.getBoundingClientRect().width))).not.toBe(beforeWidth);
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.screenshot({ path: "../output/playwright/excel-import-1920.png", fullPage: true });
    await page.setViewportSize({ width: 1366, height: 768 });
    await page.screenshot({ path: "../output/playwright/excel-import-1366.png", fullPage: true });

    // Every result tab stays server-backed and can be exported as XLSX.
    for (const [tab, onlyNew] of [["Documento resumen", false], ["Documento resumen", true],
      ["Productos no importables", false], ["Precio de compra distinto", false], ["Productos importables", false], ["Errores", false]] as const) {
      if (tab === "Documento resumen") {
        await dialog.getByRole("button", { name: "Configuración del archivo", exact: true }).click();
        await dialog.getByLabel("Mostrar solo valores nuevos (Excel)").setChecked(onlyNew);
      }
      const tabButton = dialog.getByRole("button", { name: new RegExp(`^${tab}`) });
      await tabButton.click();
      const exportButton = dialog.getByRole("button", { name: "Exportar XLSX", exact: true });
      if (await exportButton.isEnabled()) {
        const visibleHeaders = (await dialog.locator(".shared-excel-results table thead th").allTextContents()).map((value) => value.trim()).filter(Boolean);
        if (tab === "Documento resumen") {
          expect(visibleHeaders.some(header => header.includes("Valores actuales (BD)"))).toBe(!onlyNew);
          expect(visibleHeaders.some(header => header.includes("Valores nuevos (Excel)"))).toBe(true);
        }
        const download = page.waitForEvent("download");
        await exportButton.click();
        const exported = await download;
        expect(exported.suggestedFilename()).toMatch(/\.xlsx$/i);
        const stream = await exported.createReadStream();
        const chunks: Buffer[] = [];
        for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
        const readResponse = await request.post(`${apiUrl}/product-excel-imports/read`, {
          headers: authorization(session.accessToken),
          multipart: { file: { name: exported.suggestedFilename(), mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: Buffer.concat(chunks) } }
        });
        expect(readResponse.ok(), await readResponse.text()).toBeTruthy();
        const read = await readResponse.json() as { rows: Array<Array<unknown>> };
        const normalizeRow = (row: string[]) => {
          const normalized = [...row];
          while (normalized.at(-1) === "") normalized.pop();
          return normalized;
        };
        const exportedRows = read.rows.map((row) => normalizeRow(row.map((value) => {
          if (value && typeof value === "object" && "value" in value) return String((value as { value?: unknown }).value ?? "").trim();
          return String(value ?? "").trim();
        })));
        expect(exportedRows.length, `${tab} export must contain a header row`).toBeGreaterThan(0);
        expect(exportedRows[0]).toEqual(visibleHeaders);
        const visibleRows = await dialog.locator(".shared-excel-results table tbody tr").evaluateAll((rows) => rows
          .map((row) => Array.from(row.querySelectorAll("th,td")).map((cell) => (cell.textContent ?? "").trim()))
          .filter((row) => row.length > 0 && !row.every((cell) => cell === "Sin filas")));
        expect(exportedRows.slice(1)).toEqual(visibleRows.map(normalizeRow));
      }
    }

    expect(applyCalls).toEqual([]);
    expect(await apiGet(request, session.accessToken, `/products/management/${existing.id}`)).toEqual(before);

    // The explicit auto-create action is the first write and is confirmed by the API and DB state.
    await dialog.getByRole("button", { name: /Productos no importables/ }).click();
    await dialog.getByRole("button", { name: "Configuración del archivo", exact: true }).click();
    await dialog.getByLabel("Añadir automáticamente los productos no existentes").check();
    await dialog.getByRole("button", { name: "Aplicar", exact: true }).click();
    await expect(dialog.getByRole("button", { name: /Productos no importables/ })).toBeVisible();
    await dialog.getByRole("button", { name: /Productos no importables/ }).click();
    await dialog.getByRole("button", { name: "Añadir productos", exact: true }).click();
    await expect(dialog.getByRole("alertdialog")).toBeVisible();
    const autoApplyResponse = page.waitForResponse((response) => response.url().includes("/product-excel-imports/apply") && response.request().method() === "POST");
    await dialog.getByRole("alertdialog").getByRole("button", { name: "Añadir productos", exact: true }).click();
    const autoApply = await autoApplyResponse;
    await autoApply.finished();
    expect(autoApply.ok()).toBeTruthy();
    expect((await autoApply.json()).appliedCount).toBe(1);
    expect(applyCalls).toEqual(["POST"]);

    await expect.poll(() => createdProductId).toBeTruthy();
    const created = await productById(request, session.accessToken, createdProductId!);
    expect(created.code).toBe(missing);

    // Purchase-price and selected-attribute operations are separate, explicit writes.
    await dialog.getByRole("button", { name: /Precio de compra distinto/ }).click();
    await dialog.getByRole("button", { name: "Actualizar precio de compra", exact: true }).click();
    const purchaseApplyResponse = page.waitForResponse((response) => response.url().includes("/product-excel-imports/apply") && response.request().method() === "POST");
    await dialog.getByRole("alertdialog").getByRole("button", { name: "Actualizar precio de compra", exact: true }).click();
    const purchaseApply = await purchaseApplyResponse;
    await purchaseApply.finished();
    expect(purchaseApply.ok()).toBeTruthy();
    expect((await purchaseApply.json()).appliedCount).toBe(1);
    const afterPurchase = await apiGet<{ name: string; purchasePrice: number; salePrice: number }>(request, session.accessToken, `/products/management/${existing.id}`);
    expect(afterPurchase.purchasePrice).toBe(7.5);
    expect(afterPurchase.salePrice).toBe(before.salePrice);

    await dialog.getByRole("button", { name: /Productos importables/ }).click();
    await dialog.getByRole("button", { name: "Actualizar atributos marcados", exact: true }).click();
    const selectedApplyResponse = page.waitForResponse((response) => response.url().includes("/product-excel-imports/apply") && response.request().method() === "POST");
    await dialog.getByRole("alertdialog").getByRole("button", { name: "Actualizar atributos marcados", exact: true }).click();
    const selectedApply = await selectedApplyResponse;
    await selectedApply.finished();
    expect(selectedApply.ok()).toBeTruthy();
    expect((await selectedApply.json()).appliedCount).toBeGreaterThan(0);
    const afterSelected = await apiGet<{ name: string; purchasePrice: number; salePrice: number }>(request, session.accessToken, `/products/management/${existing.id}`);
    expect(afterSelected.name).toBe(`Producto actualizado ${marker}`.toUpperCase());
    expect(afterSelected.salePrice).toBe(before.salePrice);
  });

  test("permite importar edición masiva sin columna Cantidad ni escritura implícita", async ({ page, request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("E2E-BULK");
    const product = await createProductFixture(request, session.accessToken, marker);
    const xlsx = productWorkbook([["Código", "Nombre", "Precio de compra"], [marker, `Edición ${marker}`, "5"]]);
    const writes: string[] = [];
    page.on("request", (event) => { if (event.url().includes("/product-excel-imports/apply")) writes.push(event.method()); });
    await loginUi(page, "venta");
    await page.getByRole("button", { name: "GESTIÓN", exact: true }).click();
    await expect(page.locator(".stock-screen")).toBeVisible();
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();
    await openExcelImport(page, { name: "edicion-e2e.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: xlsx });
    const dialog = await configureAndApply(page);
    expect(await dialog.getByLabel("Cantidad Columna Excel").count()).toBe(0);
    expect(writes).toEqual([]);
    await dialog.getByRole("button", { name: "Productos importables", exact: false }).click();
    await dialog.getByRole("button", { name: "Importar a edición masiva", exact: true }).click();
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();
    // PREPARE_DESTINATION calls the apply contract to resolve rows, but must not mutate the catalog.
    expect(writes).toEqual(["POST"]);
    const after = await productById(request, session.accessToken, product.id);
    expect(after.name).toBe(`PRODUCTO ${marker}`);
    expect((await apiGet<{ purchasePrice: number }>(request, session.accessToken, `/products/management/${product.id}`)).purchasePrice).toBe(product.purchasePrice);
  });

  test("permite resolver un faltante por la ruta manual", async ({ page, request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("E2E-MANUAL");
    const secondMarker = `${marker}-SECOND`;
    const xlsx = productWorkbook([["Código", "Nombre", "Precio de compra", "Precio de venta"], [marker, `Manual ${marker}`, "4", "9"], [secondMarker, `Manual ${secondMarker}`, "4", "9"]]);
    await loginUi(page, "venta");
    await page.getByRole("button", { name: "GESTIÓN", exact: true }).click();
    await expect(page.locator(".stock-screen")).toBeVisible();
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();
    await openExcelImport(page, { name: "manual-e2e.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: xlsx });
    const dialog = await configureAndApply(page);
    await dialog.getByRole("button", { name: /Productos no importables/ }).click();
    await dialog.getByRole("button", { name: "Configuración del archivo", exact: true }).click();
    await dialog.getByLabel("Añadir automáticamente los productos no existentes").uncheck();
    await dialog.getByRole("button", { name: "Aplicar", exact: true }).click();
    await dialog.getByRole("button", { name: /Productos no importables/ }).click();
    await dialog.getByRole("button", { name: "Añadir productos", exact: true }).click();
    const create = page.locator(".product-create-dialog");
    const editedCode = `${marker}-EDITED`;
    await create.locator('[data-product-field-name="code"]').fill(editedCode);
    await create.locator('[data-product-field-name="name"]').fill(`Manual ${editedCode}`);
    const family = create.locator('[data-product-field-name="familyBusinessCode"]').first();
    await family.fill("001");
    await family.press("Enter");
    await expect(family).toHaveAttribute("aria-busy", "false");
    await expect(create.getByRole("button", { name: /Guardar|Crear/ })).toBeEnabled();
    const saveResponse = page.waitForResponse((response) => response.url().endsWith("/products/management") && response.request().method() === "POST");
    await create.getByRole("button", { name: /Guardar|Crear/ }).click();
    const savedResponse = await saveResponse;
    expect(savedResponse.ok(), await savedResponse.text()).toBeTruthy();
    const savedProduct = await savedResponse.json() as { id?: string; code?: string };
    expect(savedProduct.code).toBe(editedCode);
    expect(savedProduct.id).toBeTruthy();
    expect((await productById(request, session.accessToken, savedProduct.id!)).code).toBe(editedCode);
    const secondCreate = page.locator(".product-create-dialog");
    await expect(secondCreate).toBeVisible();
    await expect(secondCreate.locator('[data-product-field-name="code"]')).toHaveValue(secondMarker);
    await secondCreate.getByRole("button", { name: /Cerrar/ }).last().click();
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("button", { name: /Productos importables \(1\)/ })).toBeVisible();
    await expect(dialog.getByRole("button", { name: /Productos no importables \(1\)/ })).toBeVisible();
    await expect(dialog.locator(".shared-excel-preview tbody tr").nth(1).locator("td").first()).toHaveText(marker);
  });

  test("abre un XLS real generado con POI y lo importa a Stock sin usar Cantidad", async ({ page, request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("E2E-XLS-POI");
    await createProductFixture(request, session.accessToken, marker);
    const xls = await generateExcelImportFixture("xls", marker);
    await loginUi(page, "venta");
    await page.getByRole("button", { name: "GESTIÓN", exact: true }).click();
    await expect(page.locator(".stock-screen")).toBeVisible();
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();
    await openExcelImport(page, { name: "poi-e2e.xls", mimeType: "application/vnd.ms-excel", buffer: xls });
    const dialog = page.getByRole("dialog", { name: "Importar Excel" });
    await expect(dialog.getByLabel("Código Columna Excel")).toHaveValue("A");
    await expect(dialog.getByLabel("Cantidad Columna Excel")).toHaveCount(0);
    await dialog.getByRole("button", { name: "Aplicar", exact: true }).click();
    await expect(dialog.getByRole("button", { name: /Productos importables/ })).toBeVisible();
    await dialog.getByRole("button", { name: /Productos importables/ }).click();
    await dialog.getByRole("button", { name: "Importar a edición masiva", exact: true }).click();
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();
    expect(await apiGet<unknown[]>(request, session.accessToken, "/products")).toBeTruthy();
  });
});

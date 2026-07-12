import { expect, type Page } from "@playwright/test";
import { gestionUrl, ventaUrl } from "./testApi";

export async function loginUi(
  page: Page,
  app: "venta" | "gestion",
  userName = process.env.E2E_ADMIN_USERNAME ?? "ADMIN",
  password = process.env.E2E_ADMIN_PASSWORD ?? "0000"
) {
  await page.goto(app === "venta" ? ventaUrl : gestionUrl);
  await page.getByLabel("Usuario").fill(userName);
  await page.getByLabel("Contraseña").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.locator(app === "venta" ? ".home-screen" : ".gestion-screen")).toBeVisible();
}

export async function openStock(page: Page, app: "venta" | "gestion") {
  if (app === "venta") {
    await page.getByRole("button", { name: /^stock$/i }).click();
  } else {
    await page.locator(".gestion-nav").getByRole("button", { name: "Stock", exact: true }).click();
  }
  await expect(page.getByRole("heading", { level: 1, name: "STOCK" })).toBeVisible();
}

export async function openBulkEdit(page: Page) {
  await page.getByRole("button", { name: "Edición masiva de productos", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Edición masiva de productos" })).toBeVisible();
}

export async function chooseFileAction(page: Page, action: string) {
  await page.getByRole("button", { name: "Archivo", exact: true }).click();
  await page.getByRole("menu").getByRole("button", { name: action, exact: true }).click();
}

export async function chooseImportAction(page: Page, action: string) {
  await page.getByRole("button", { name: "Archivo", exact: true }).click();
  const fileMenu = page.locator(".bulk-file-menu");
  await fileMenu.getByRole("button", { name: "Importar", exact: true }).click();
  await page.locator(".bulk-import-menu").getByRole("button", { name: action, exact: true }).click();
}

export async function clearBulkRows(page: Page) {
  await chooseFileAction(page, "Limpiar lista de productos");
  await page.getByRole("dialog", { name: "Limpiar lista de productos" })
    .getByRole("button", { name: "Confirmar" })
    .press("Enter");
  await expect(page.getByPlaceholder("Código o código de barras")).toBeVisible();
}

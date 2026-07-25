import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiGet, loginApi } from "./support/testApi";
import { loginUi } from "./support/ui";

type SaleProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name: string;
};

const viewports = [
  { name: "full-hd", width: 1920, height: 1080 },
  { name: "laptop", width: 1366, height: 768 },
  { name: "minimum-desktop", width: 1024, height: 768 }
] as const;

const locales = ["es", "en", "zh"] as const;

const localeLabels = {
  es: {
    quantityDialog: "Cambiar cantidad",
    quantityInput: "Nueva cantidad",
    customerDialog: "Seleccionar cliente",
    searchProduct: "Buscar producto"
  },
  en: {
    quantityDialog: "Change quantity",
    quantityInput: "New quantity",
    customerDialog: "Select customer",
    searchProduct: "Search product"
  },
  zh: {
    quantityDialog: "\u66f4\u6539\u6570\u91cf",
    quantityInput: "\u65b0\u6570\u91cf",
    customerDialog: "\u9009\u62e9\u5ba2\u6237",
    searchProduct: "\u641c\u7d22\u5546\u54c1"
  }
} as const;

const languageOptionIndex = { es: 0, en: 1, zh: 2 } as const;

for (const viewport of viewports) {
  for (const locale of locales) {
    test(`${viewport.name} keeps APP VENTA reachable in ${locale}`, async ({ page, request }) => {
      await page.setViewportSize(viewport);
      const session = await loginApi(request);
      const products = await apiGet<SaleProduct[]>(request, session.accessToken, "/products/sale");
      expect(products.length, "The responsive check needs a sale product").toBeGreaterThan(0);

      await loginUi(page, "venta");
      await page.locator(".home-action-sale").click();
      await expect(page.locator(".sale-screen")).toBeVisible();
      await chooseLocale(page, locale);

      await expectWithinViewport(page.locator(".sale-screen"), viewport);
      await expectWithinViewport(page.locator(".sale-tools"), viewport);
      await expectWithinViewport(page.locator(".sale-ticket"), viewport);
      await expectWithinViewport(page.locator(".promotion-preview-panel"), viewport);
      await expectWithinViewport(page.locator(".report-footer-context"), viewport);
      expect(await page.evaluate(() => document.documentElement.scrollWidth))
        .toBeLessThanOrEqual(viewport.width);

      await addProduct(page, products[0], locale);

      await page.keyboard.press("F2");
      const quantityDialog = page.getByRole("dialog", { name: localeLabels[locale].quantityDialog });
      await expectWithinViewport(quantityDialog, viewport);
      const quantityInput = quantityDialog.getByRole("spinbutton", {
        name: localeLabels[locale].quantityInput
      });
      await expect(quantityInput).toBeFocused();
      await page.keyboard.press("Tab");
      await expect(quantityDialog.getByRole("button").nth(1)).toBeFocused();
      await page.keyboard.press("Tab");
      await expect(quantityDialog.getByRole("button").nth(2)).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(quantityDialog).toBeHidden();

      await page.keyboard.press("F2");
      await expect(quantityDialog).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(quantityDialog).toBeHidden();

      await page.keyboard.press("F6");
      const customerDialog = page.getByRole("dialog", { name: localeLabels[locale].customerDialog });
      await expectWithinViewport(customerDialog, viewport);
      await page.keyboard.press("Escape");
      await expect(customerDialog).toBeHidden();

      expect(await unnamedInteractiveControls(page)).toEqual([]);
    });
  }
}

async function chooseLocale(page: Page, locale: typeof locales[number]) {
  await page.locator(".language-button").click();
  await page.locator(".language-picker button").nth(languageOptionIndex[locale]).click();
  await expect(page.locator(".work-shell")).toHaveAttribute("aria-label", /./);
}

async function addProduct(page: Page, product: SaleProduct, locale: typeof locales[number]) {
  const search = page.getByRole("combobox", { name: localeLabels[locale].searchProduct });
  await search.fill(product.code || product.barcode || product.name);
  const firstResult = page.getByRole("option").first();
  await expect(firstResult).toBeVisible();
  await firstResult.press("Enter");
  await expect(page.locator(".sale-ticket-line")).toBeVisible();
}

async function expectWithinViewport(locator: Locator, viewport: { width: number; height: number }) {
  await expect(locator).toBeVisible();
  const bounds = await locator.boundingBox();
  expect(bounds, "The element must have visible bounds").not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.y).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewport.width);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(viewport.height);
}

async function unnamedInteractiveControls(page: Page) {
  return page.locator("button, input:not([type='hidden']), select, textarea, [role='button'], [role='link']")
    .evaluateAll((controls) => controls.flatMap((control) => {
      const element = control as HTMLInputElement;
      const style = window.getComputedStyle(element);
      if (element.hidden || style.display === "none" || style.visibility === "hidden") return [];

      const labelledBy = (element.getAttribute("aria-labelledby") ?? "")
        .split(/\s+/)
        .map((id) => document.getElementById(id)?.textContent?.trim() ?? "")
        .join(" ");
      const labelText = Array.from(element.labels ?? [])
        .map((label) => label.textContent?.trim() ?? "")
        .join(" ");
      const name = [
        element.getAttribute("aria-label"),
        labelledBy,
        labelText,
        element.getAttribute("alt"),
        element.getAttribute("title"),
        element.value,
        element.textContent
      ].find((candidate) => candidate?.trim());
      return name ? [] : [`${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ""}`];
    }));
}

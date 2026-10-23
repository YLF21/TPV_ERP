import { expect, test } from "@playwright/test";
import { loginUi, openBulkEdit, openStock } from "./support/ui";

for (const app of ["venta", "gestion"] as const) {
  test(`APP ${app.toUpperCase()} abre el Stock compartido autenticado`, async ({ page }) => {
    await loginUi(page, app);
    await openStock(page, app);
    await expect(page.getByRole("button", { name: "Top ventas", exact: true })).toBeVisible();
    await openBulkEdit(page);
    const newList = page.getByRole("button", { name: "Nuevo F5", exact: true });
    await expect(newList).toBeVisible();
    await expect(newList).toHaveAttribute("aria-keyshortcuts", "F5");
    await page.keyboard.press("F5");
    await expect(page.getByText("Lista nueva sin guardar", { exact: true })).toBeVisible();
  });
}

import { expect, test } from "@playwright/test";
import { loginUi, openBulkEdit, openStock } from "./support/ui";

for (const app of ["venta", "gestion"] as const) {
  test(`APP ${app.toUpperCase()} abre el Stock compartido autenticado`, async ({ page }) => {
    await loginUi(page, app);
    await openStock(page, app);
    await expect(page.getByRole("button", { name: "Top ventas", exact: true })).toBeVisible();
    await openBulkEdit(page);
    await expect(page.getByRole("button", { name: "Nuevo", exact: true })).toBeVisible();
  });
}

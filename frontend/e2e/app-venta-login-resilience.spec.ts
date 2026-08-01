import { expect, test } from "@playwright/test";

const backendProbe = "**/api/v1/auth/login";

test("keeps login blocked while the backend is offline and recovers after retry", async ({ page }) => {
  let backendAvailable = false;
  await page.route(backendProbe, async (route) => {
    if (backendAvailable) {
      await route.fulfill({ status: 405, body: "" });
      return;
    }
    await route.abort("connectionfailed");
  });

  await page.goto("/");

  const submit = page.getByRole("button", { name: "Entrar" });
  await expect(page.getByRole("alert")).toContainText("Sin conexion con backend");
  await expect(submit).toBeDisabled();
  const retry = page.getByRole("button", { name: /Reintentar conexi[oó]n/ });
  await expect(retry).toBeVisible();

  backendAvailable = true;
  await retry.click();

  await expect(page.getByText("Backend conectado")).toBeVisible();
  await expect(submit).toBeEnabled();
});

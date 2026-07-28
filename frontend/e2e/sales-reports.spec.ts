import { expect, test } from "@playwright/test";
import { authorization, backendUrl, loginApi } from "./support/testApi";

const apiUrl = `${backendUrl}/api/v1`;

const exportRequest = {
  reportKey: "salesReport.inputWarehouse",
  filters: {
    dateFrom: "2026-07-01",
    dateTo: "2026-07-31",
    user: "",
    customer: "",
    supplier: "",
    payment: "",
    terminal: "",
    status: "",
    warehouse: ""
  },
  search: "",
  columns: [
    { key: "input", label: "Entrada" },
    { key: "warehouse", label: "Almacén" },
    { key: "productCount", label: "Productos" },
    { key: "total", label: "Total de compra" }
  ]
};

test("exporta el mismo informe de almacén a Excel y PDF desde el servidor", async ({ request }) => {
  const session = await loginApi(request);
  const headers = {
    ...authorization(session.accessToken),
    "Accept-Language": "es"
  };

  const excel = await request.post(`${apiUrl}/sales-reports/export`, {
    headers,
    data: exportRequest
  });
  expect(excel.ok(), await excel.text()).toBeTruthy();
  expect(excel.headers()["content-type"]).toContain("spreadsheetml");
  expect((await excel.body()).subarray(0, 2).toString()).toBe("PK");

  const pdf = await request.post(`${apiUrl}/sales-reports/export-pdf`, {
    headers,
    data: exportRequest
  });
  expect(pdf.ok(), await pdf.text()).toBeTruthy();
  expect(pdf.headers()["content-type"]).toContain("application/pdf");
  expect((await pdf.body()).subarray(0, 4).toString()).toBe("%PDF");
});

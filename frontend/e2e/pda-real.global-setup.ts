import { request } from "@playwright/test";
import {
  adminLogin, apiJson, bearer, realPdaApiUrl, requireOk, writePdaRealFixture,
  type PdaRealFixture
} from "./support/pdaRealFixture";

const readinessTimeoutMs = 120_000;

export default async function prepareRealPdaFixture() {
  if (process.env.E2E_PDA_REAL_DISPOSABLE !== "true") {
    throw new Error("E2E_PDA_REAL_DISPOSABLE=true es obligatorio para preparar fixtures PDA reales");
  }
  const context = await request.newContext();
  try {
    const admin = await waitForSeededBackend(context);
    await apiJson(context, admin.accessToken, "post", "/auth/gestion-groups/SEGURIDAD/unlock", { password: "0000" });

    const marker = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`.toUpperCase();
    const role = await apiJson<{ id: string }>(context, admin.accessToken, "post", "/roles", {
      name: `E2E PDA ${marker}`
    });
    await apiJson(context, admin.accessToken, "put", `/roles/${encodeURIComponent(role.id)}/permissions`, {
      codes: ["GESTION_ALMACEN"]
    });
    const userPassword = "2468";
    const userName = `E2EPDA_${marker}`;
    const user = await apiJson<{ id: string }>(context, admin.accessToken, "post", "/users", {
      name: `OPERADOR PDA E2E ${marker}`,
      userName,
      password: userPassword,
      roleId: role.id
    });

    const registration = await apiJson<{ terminalId: string }>(context, null, "post", "/terminals/pda/request", {
      name: `PDA E2E ${marker}`
    });
    await apiJson(context, admin.accessToken, "post", `/terminals/${encodeURIComponent(registration.terminalId)}/approve`);
    const pairing = await apiJson<{ code: string }>(context, admin.accessToken, "post", `/terminals/${encodeURIComponent(registration.terminalId)}/pairing-code`);

    const warehouseName = `ALMACÉN PDA E2E ${marker}`;
    const warehouse = await apiJson<{ id: string }>(context, admin.accessToken, "post", "/warehouses", {
      name: warehouseName,
      address: "Zona aislada E2E",
      notes: "Se elimina al terminar la prueba PDA real"
    });
    const [families, taxes] = await Promise.all([
      apiJson<Array<{ id: string }>>(context, admin.accessToken, "get", "/families"),
      apiJson<Array<{ id: string }>>(context, admin.accessToken, "get", "/taxes/selectable")
    ]);
    if (!families[0] || !taxes[0]) throw new Error("Los datos demo E2E no contienen familia e impuesto");
    const productCode = `E2ESCAN${marker}`;
    const productName = `PRODUCTO ESCÁNER E2E ${marker}`;
    const product = await apiJson<{ id: string }>(context, admin.accessToken, "post", "/products", {
      familyId: families[0].id,
      subfamilyId: null,
      taxId: taxes[0].id,
      productType: "UNIT",
      discountType: "NORMAL",
      priceUseMode: "NORMAL",
      name: productName,
      description: "Producto aislado para recorrido PDA E2E real",
      comments: null,
      purchasePrice: 4,
      purchaseDiscountPercent: null,
      taxesIncluded: true,
      code: productCode,
      barcode: null,
      barcode2: null,
      salePrice: 9.95,
      memberPrice: null,
      wholesalePrice: null,
      offerPrice: null,
      offerDiscountPercent: null,
      offerActive: false,
      offerFrom: null,
      offerUntil: null
    });
    const stockQuantity = 37;
    await apiJson(context, admin.accessToken, "post", "/stock/adjustments", {
      productId: product.id,
      warehouseId: warehouse.id,
      quantity: stockQuantity,
      reason: `CARGA AISLADA PDA E2E ${marker}`
    });

    const workTitle = `TAREA PDA E2E ${marker}`;
    const work = await apiJson<{ id: string }>(context, admin.accessToken, "post", "/pda-work", {
      type: "TASK",
      title: workTitle,
      priority: "HIGH",
      notes: "Debe sobrevivir al corte de conexión y finalizar al reconectar",
      assignedTo: user.id,
      clientRequestId: crypto.randomUUID()
    });

    const fixture: PdaRealFixture = {
      marker,
      roleId: role.id,
      userId: user.id,
      userName,
      userPassword,
      pdaTerminalId: registration.terminalId,
      pairingCode: pairing.code,
      warehouseId: warehouse.id,
      warehouseName,
      stockQuantity,
      productId: product.id,
      productCode,
      productName,
      workId: work.id,
      workTitle
    };
    writePdaRealFixture(fixture);
  } finally {
    await context.dispose();
  }
}

async function waitForSeededBackend(context: Awaited<ReturnType<typeof request.newContext>>) {
  const deadline = Date.now() + readinessTimeoutMs;
  let lastFailure = "backend no disponible";
  while (Date.now() < deadline) {
    try {
      return await adminLogin(context);
    } catch (error) {
      lastFailure = error instanceof Error ? error.message : String(error);
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  const health = await context.get(`${realPdaApiUrl.replace(/\/api\/v1$/, "")}/actuator/health/readiness`, {
    headers: bearer("")
  }).catch(() => null);
  throw new Error(`El backend E2E no quedó preparado: ${lastFailure}${health ? `; health ${health.status()} ${await health.text()}` : ""}`);
}

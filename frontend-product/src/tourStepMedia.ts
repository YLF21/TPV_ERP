import type { AppId } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";

export type TourStep = 0 | 1 | 2;
type ThreeSteps<T> = readonly [T, T, T];

export type TourMediaAsset = {
  src: string;
  width: number;
  height: number;
  /** Repository evidence for this unchanged screenshot, not a marketing claim. */
  source: string;
};

// These are existing product screenshots, not twelve newly captured operations.
export const tourApplicationOverviewAssets: Record<AppId, TourMediaAsset> = {
  venta: { src: "/marketing/tour-venta.png", width: 1600, height: 1000, source: "frontend/e2e/touch-review/main.tsx; frontend-product/README.md" },
  gestion: { src: "/marketing/tour-gestion.png", width: 1600, height: 968, source: "frontend/e2e/dashboard-review/main.tsx; frontend-product/README.md" },
  pda: { src: "/marketing/tour-pda.png", width: 920, height: 1700, source: "frontend-product/README.md: interfaz PDA con respuestas de API simuladas" },
  saas: { src: "/marketing/tour-saas.png", width: 1600, height: 1000, source: "frontend-product/README.md: portal cliente SaaS con respuestas de API simuladas" }
};

// A step receives a capture only after its actual screen and source have been
// reviewed. Missing evidence stays null: no renamed copies or invented states.
export const tourStepCaptureAssets: Record<AppId, ThreeSteps<TourMediaAsset | null>> = {
  venta: [null, null, null],
  gestion: [null, null, null],
  pda: [null, null, null],
  saas: [null, null, null]
};

type TourStepMediaContent = {
  overviewAlts: Record<AppId, string>;
  steps: Record<AppId, ThreeSteps<string>>;
  overviewNote: string;
  captureNote: string;
  expandLabel: string;
};

const content: Record<MarketingLanguage, TourStepMediaContent> = {
  es: {
    overviewAlts: {
      venta: "Vista general de esPOS Venta en español: ticket de demostración con dos productos, antes del cobro",
      gestion: "Vista general de esPOS Gestión en español: resumen de ventas con indicadores de demostración",
      pda: "Vista general de esPOS PDA en español: consulta de precio y existencias por almacén",
      saas: "Vista general del portal cliente esPOS SaaS en español: documentos recibidos de tiendas de demostración"
    },
    steps: {
      venta: ["Cobros y saldos pendientes", "Atajos en el mostrador", "Servicio local de tienda"],
      gestion: ["Existencias e informes", "Intercambio de productos con Excel", "Usuarios, roles y permisos"],
      pda: ["Conteo de inventario", "Comprobación de documentos confirmados", "Consulta y reposición de productos"],
      saas: ["Documentos y stock recibidos", "Estado de sincronización", "Licencias, facturas del servicio y soporte"]
    },
    overviewNote: "Interfaz real en español · Datos de demostración · Vista general de referencia, no de este paso.",
    captureNote: "Interfaz real en español · Datos de demostración · Ejemplo ilustrativo, no una sesión interactiva.",
    expandLabel: "Ampliar captura original (nueva pestaña)"
  },
  en: {
    overviewAlts: {
      venta: "esPOS Sales overview in Spanish: demonstration ticket with two products, before payment",
      gestion: "esPOS Management overview in Spanish: sales summary with demonstration indicators",
      pda: "esPOS PDA overview in Spanish: price and stock lookup by warehouse",
      saas: "esPOS SaaS customer portal overview in Spanish: documents received from demonstration stores"
    },
    steps: {
      venta: ["Payments and outstanding balances", "Counter shortcuts", "Local store service"],
      gestion: ["Stock and reports", "Exchanging product data with Excel", "Users, roles and permissions"],
      pda: ["Inventory counting", "Checking confirmed documents", "Product lookup and replenishment"],
      saas: ["Received documents and stock data", "Synchronisation status", "Licences, service invoices and support"]
    },
    overviewNote: "Real interface in Spanish · Demonstration data · General reference screen, not this step.",
    captureNote: "Real interface in Spanish · Demonstration data · Illustrative example, not an interactive session.",
    expandLabel: "Enlarge original screenshot (new tab)"
  },
  zh: {
    overviewAlts: {
      venta: "esPOS 销售西班牙语界面总览：收款前的小票，包含两件演示商品",
      gestion: "esPOS 管理西班牙语界面总览：销售汇总及演示指标",
      pda: "esPOS PDA 西班牙语界面总览：按仓库查询价格与库存",
      saas: "esPOS SaaS 客户门户西班牙语界面总览：从演示门店接收的单据"
    },
    steps: {
      venta: ["收款与未结余额", "收银台快捷操作", "门店本地服务"],
      gestion: ["库存与报表", "通过 Excel 交换商品数据", "用户、角色与权限"],
      pda: ["库存盘点", "核对已确认的单据", "商品查询与补货"],
      saas: ["已接收的单据与库存数据", "同步状态", "许可证、服务发票与支持"]
    },
    overviewNote: "真实界面（西班牙语）· 演示数据 · 此图为参考总览，并非当前步骤截图。",
    captureNote: "真实界面（西班牙语）· 演示数据 · 示例仅供说明，并非可交互的操作会话。",
    expandLabel: "放大原始截图（在新标签页打开）"
  }
};

export function getTourStepMediaContent(language: MarketingLanguage) {
  return content[language];
}

export function getTourStepMedia(application: AppId, step: number, language: MarketingLanguage) {
  if (!Number.isInteger(step) || step < 0 || step > 2) {
    throw new RangeError("A product tour step must be 0, 1 or 2");
  }
  const index = step as TourStep;
  const copy = getTourStepMediaContent(language);
  const capture = tourStepCaptureAssets[application][index];
  const asset = capture ?? tourApplicationOverviewAssets[application];
  const context = copy.steps[application][index];
  return {
    ...asset,
    kind: capture ? "step-capture" as const : "application-overview" as const,
    application,
    step: index,
    context,
    alt: capture ? `${context} · ${copy.captureNote}` : `${copy.overviewAlts[application]} · ${context}`,
    caption: `${context} · ${capture ? copy.captureNote : copy.overviewNote}`,
    expandLabel: copy.expandLabel
  };
}

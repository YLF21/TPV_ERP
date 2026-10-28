import type { AppId } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";

export type TourStep = 0 | 1 | 2;
type ThreeSteps<T> = readonly [T, T, T];

// Exact dimensions of the twelve reviewed native captures. The private source
// evidence and hashes live in assets/marketing-sources/tour-step-captures.json;
// that manifest is checked by tests and is never imported by the client.
const captureDimensions: Record<AppId, ThreeSteps<readonly [number, number]>> = {
  venta: [[994, 560], [620, 482], [1884, 658]],
  gestion: [[1480, 1040], [1920, 1080], [1614, 1052]],
  pda: [[445, 822], [445, 822], [445, 822]],
  saas: [[1280, 720], [1280, 720], [1280, 720]]
};

type TourStepMediaContent = {
  steps: Record<AppId, ThreeSteps<string>>;
  captureNote: string;
  expandLabel: string;
};

const content: Record<MarketingLanguage, TourStepMediaContent> = {
  es: {
    steps: {
      venta: ["Cobro parcial antes de finalizar", "Atajos en el mostrador", "Servicio local de tienda"],
      gestion: ["Existencias e informes", "Intercambio de productos con Excel", "Usuarios, roles y permisos"],
      pda: ["Conteo de inventario", "Comprobación de documentos confirmados", "Consulta y reposición de productos"],
      saas: ["Documentos y stock recibidos", "Estado de sincronización", "Licencias, facturas del servicio y soporte"]
    },
    captureNote: "Interfaz real en español · Datos de demostración · Ejemplo ilustrativo, no una sesión interactiva.",
    expandLabel: "Ampliar captura original (nueva pestaña)"
  },
  en: {
    steps: {
      venta: ["Partial payment before completing the sale", "Counter shortcuts", "Local store service"],
      gestion: ["Stock and reports", "Exchanging product data with Excel", "Users, roles and permissions"],
      pda: ["Inventory counting", "Checking confirmed documents", "Product lookup and replenishment"],
      saas: ["Received documents and stock data", "Synchronisation status", "Licences, service invoices and support"]
    },
    captureNote: "Real interface in Spanish · Demonstration data · Illustrative example, not an interactive session.",
    expandLabel: "Enlarge original screenshot (new tab)"
  },
  zh: {
    steps: {
      venta: ["完成销售前的部分收款", "收银台快捷操作", "门店本地服务"],
      gestion: ["库存与报表", "通过 Excel 交换商品数据", "用户、角色与权限"],
      pda: ["库存盘点", "核对已确认的单据", "商品查询与补货"],
      saas: ["已接收的单据与库存数据", "同步状态", "许可证、服务发票与支持"]
    },
    captureNote: "真实界面（西班牙语）· 演示数据 · 示例仅供说明，并非可交互的操作会话。",
    expandLabel: "放大原始截图（在新标签页打开）"
  }
};

export function getTourStepMediaContent(language: MarketingLanguage): TourStepMediaContent {
  return content[language];
}

export function getTourStepMedia(application: AppId, step: number, language: MarketingLanguage) {
  if (!Number.isInteger(step) || step < 0 || step > 2) {
    throw new RangeError("A product tour step must be 0, 1 or 2");
  }
  const index = step as TourStep;
  const copy = getTourStepMediaContent(language);
  const [width, height] = captureDimensions[application][index];
  const context = copy.steps[application][index];
  const caption = `${context} · ${copy.captureNote}`;
  return {
    src: `/marketing/steps/${application}-${step + 1}.webp`, width, height,
    kind: "step-capture" as const,
    step: index,
    context,
    alt: caption,
    caption,
    expandLabel: copy.expandLabel
  };
}

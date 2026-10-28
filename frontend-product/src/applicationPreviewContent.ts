import type { AppId } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";

const content: Record<MarketingLanguage, Record<AppId, string>> = {
  es: {
    venta: "Vista general de esPOS Venta en español: ticket de demostración con dos productos, antes del cobro",
    gestion: "Vista general de esPOS Gestión en español: resumen de ventas con indicadores de demostración",
    pda: "Vista general de esPOS PDA en español: consulta de precio y existencias por almacén",
    saas: "Vista general del portal cliente esPOS SaaS en español: documentos recibidos de tiendas de demostración"
  },
  en: {
    venta: "esPOS Sales overview in Spanish: demonstration ticket with two products, before payment",
    gestion: "esPOS Management overview in Spanish: sales summary with demonstration indicators",
    pda: "esPOS PDA overview in Spanish: price and stock lookup by warehouse",
    saas: "esPOS SaaS customer portal overview in Spanish: documents received from demonstration stores"
  },
  zh: {
    venta: "esPOS 销售西班牙语界面总览：收款前的小票，包含两件演示商品",
    gestion: "esPOS 管理西班牙语界面总览：销售汇总及演示指标",
    pda: "esPOS PDA 西班牙语界面总览：按仓库查询价格与库存",
    saas: "esPOS SaaS 客户门户西班牙语界面总览：从演示门店接收的单据"
  }
};

export function getApplicationPreviewAlts(language: MarketingLanguage) {
  return content[language];
}

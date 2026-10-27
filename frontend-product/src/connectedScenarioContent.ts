import type { MarketingLanguage } from "./marketingI18n";

export const connectedScenarioApps = ["venta", "gestion", "pda", "saas"] as const;
type Scenario = { title: string; intro: string; steps: Record<typeof connectedScenarioApps[number], string> };

const content: Record<MarketingLanguage, Scenario> = {
  es: {
    title: "Un producto, cuatro equipos conectados",
    intro: "Ejemplo ficticio: producto con control de stock, 10 unidades en un almacén y sin otros movimientos.",
    steps: {
      venta: "Caja vende 2 unidades y cobra el total con acceso al servicio de tienda: queda una venta confirmada.",
      gestion: "Administración consulta el movimiento y las 8 unidades registradas en ese almacén, con servicio y permisos.",
      pda: "Almacén cuenta 8 y revisa la diferencia cero antes de confirmar; necesita red y dispositivo autorizado.",
      saas: "Dirección consulta las 8 cuando ese stock se recibe y procesa por sincronización. Requiere internet y permisos, sin edición remota ni actualización inmediata garantizada."
    }
  },
  en: {
    title: "One product, four connected teams",
    intro: "Fictional example: one stock-controlled product with 10 units in one warehouse and no other movements.",
    steps: {
      venta: "Checkout sells 2 units and collects the full payment through the store service: the sale is confirmed.",
      gestion: "Administration checks the movement and the 8 recorded units in that warehouse, with service access and permissions.",
      pda: "Warehouse staff count 8 and review the zero difference before confirming; network access and an authorised device are needed.",
      saas: "Management views 8 once that stock is received and processed through synchronisation. Internet and permissions are required, without remote editing or guaranteed instant updates."
    }
  },
  zh: {
    title: "同一商品，连接四个团队",
    intro: "虚构示例：同一仓库中，一种受库存管理的商品原有 10 件，无其他库存变动。",
    steps: {
      venta: "收银人员通过门店服务售出 2 件并收取全款，销售单据得到确认。",
      gestion: "行政人员在具备门店服务访问权限时，查询该仓库的库存变动和记录的 8 件库存。",
      pda: "仓库人员清点出 8 件，确认前检查差异为零；需要网络和已授权设备。",
      saas: "管理层在库存同步收到并处理后查询这 8 件；需要互联网及权限，不提供远程编辑，也不保证即时更新。"
    }
  }
};

export function getConnectedScenario(language: MarketingLanguage) {
  return content[language];
}

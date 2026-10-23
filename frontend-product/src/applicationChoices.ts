import type { AppId } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";

/** Demo-interest suggestions only; these are not commercial bundles or licence rules. */
export const applicationChoiceScenarios = [
  { id: "checkout", applications: ["venta", "gestion"] },
  { id: "warehouse", applications: ["gestion", "pda"] },
  { id: "central", applications: ["saas"] },
  { id: "whole-team", applications: ["venta", "gestion", "pda", "saas"] }
] as const satisfies readonly { id: string; applications: readonly AppId[] }[];

export type ApplicationChoiceId = typeof applicationChoiceScenarios[number]["id"];

type ApplicationChoicesContent = {
  title: string;
  subtitle: string;
  dependenciesLabel: string;
  requestAction: string;
  note: string;
  scenarios: Record<ApplicationChoiceId, { title: string; body: string; dependency: string }>;
};

const content: Record<MarketingLanguage, ApplicationChoicesContent> = {
  es: {
    title: "¿Qué aplicaciones necesito?",
    subtitle: "Empieza por las tareas de tu equipo. Estas combinaciones son orientativas.",
    dependenciesLabel: "Qué debes tener en cuenta",
    requestAction: "Consultar esta opción",
    note: "La selección expresa interés en una demo, no una contratación. El alcance, las licencias, los equipos y la conexión se confirman para cada instalación.",
    scenarios: {
      checkout: {
        title: "Vender y administrar la tienda",
        body: "Venta cubre el mostrador y la caja; Gestión organiza el catálogo y permite revisar existencias y actividad.",
        dependency: "Ambas necesitan acceso al servicio de tienda configurado. Los pagos integrados y periféricos se validan para cada puesto."
      },
      warehouse: {
        title: "Contar mercancía y preparar reposición",
        body: "Gestión organiza almacenes y movimientos; la PDA acerca el recuento, la comprobación de documentos y la consulta al lugar de la mercancía.",
        dependency: "Gestión debe autorizar la PDA. El dispositivo necesita red y acceso al servicio de tienda; no sustituye a la caja."
      },
      central: {
        title: "Consultar tiendas desde un portal central",
        body: "SaaS permite consultar documentos y datos de existencias recibidos de las tiendas, su sincronización y la información del servicio: licencias, facturas y soporte según permisos.",
        dependency: "Requiere internet, una cuenta habilitada e instalaciones de tienda ya vinculadas. Complementa la operativa local; no implica edición remota completa."
      },
      "whole-team": {
        title: "Conectar mostrador, administración y almacén",
        body: "Consulta las cuatro aplicaciones si necesitas caja, administración, trabajo de almacén y una visión central para dirección.",
        dependency: "Se revisan los puestos, los dispositivos PDA y las tiendas que se vincularán. Cada aplicación conserva sus requisitos de acceso y conexión."
      }
    }
  },
  en: {
    title: "Which applications do I need?",
    subtitle: "Start with your team's tasks. These combinations are suggestions.",
    dependenciesLabel: "What to keep in mind",
    requestAction: "Discuss this option",
    note: "Your selection expresses interest in a demo, not a purchase. Scope, licences, equipment and connectivity are confirmed for each installation.",
    scenarios: {
      checkout: {
        title: "Sell and administer your store",
        body: "Sales covers the counter and register; Management organises the catalogue and lets you review stock and activity.",
        dependency: "Both need access to a configured store service. Integrated payments and peripherals are checked for each workstation."
      },
      warehouse: {
        title: "Count goods and prepare replenishment",
        body: "Management organises warehouses and movements; PDA brings counting, document checks and product lookup to where the goods are.",
        dependency: "Management must authorise the PDA. The device needs network access to the store service; it does not replace checkout."
      },
      central: {
        title: "Consult stores from a central portal",
        body: "SaaS lets you view documents and stock data received from stores, their synchronisation status and service information: licences, invoices and support within your permissions.",
        dependency: "Requires internet, an enabled account and existing linked store installations. It complements local operation, not complete remote editing."
      },
      "whole-team": {
        title: "Connect counter, administration and warehouse",
        body: "Discuss all four applications if you need checkout, administration, warehouse work and a central view for management.",
        dependency: "Workstations, PDA devices and the stores to link are reviewed. Each application keeps its own access and connectivity requirements."
      }
    }
  },
  zh: {
    title: "如何选择适合您团队的应用？",
    subtitle: "从团队的实际任务出发。以下组合仅供参考。",
    dependenciesLabel: "需要注意",
    requestAction: "咨询此方案",
    note: "所选应用仅表示演示咨询意向，不代表购买。功能范围、许可证、设备及连接条件需按具体安装环境确认。",
    scenarios: {
      checkout: {
        title: "门店销售与日常管理",
        body: "销售应用负责柜台与收银；管理应用整理商品资料，并用于查看库存与业务活动。",
        dependency: "两款应用都需要连接已配置的门店服务。集成支付与外设兼容性需按终端确认。"
      },
      warehouse: {
        title: "盘点商品与准备补货",
        body: "管理应用维护仓库与库存变动；PDA 让团队在货物所在位置完成盘点、单据核查与商品查询。",
        dependency: "PDA 必须由管理应用授权。设备需要通过网络访问门店服务，不能代替收银终端。"
      },
      central: {
        title: "通过中央门户查看门店",
        body: "SaaS 可按权限查看从门店接收到的单据和库存数据、同步状态，以及许可证、服务账单和支持。",
        dependency: "需要互联网、已开通的账户以及已关联的现有门店系统。它补充本地运营，不代表可完整远程编辑。"
      },
      "whole-team": {
        title: "连接柜台、管理与仓库团队",
        body: "如需收银、日常管理、仓库作业与管理层的集中查看，可咨询全部四款应用。",
        dependency: "需确认终端、PDA 设备及要关联的门店。每款应用仍需满足各自的访问权限与连接要求。"
      }
    }
  }
};

export function getApplicationChoices(language: MarketingLanguage): ApplicationChoicesContent {
  return content[language];
}

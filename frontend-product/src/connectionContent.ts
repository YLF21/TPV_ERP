import type { ApplicationId } from "./applicationGuideTypes";
import type { MarketingLanguage } from "./marketingI18n";

type ConnectionContent = {
  title: string;
  body: string;
  items: readonly { title: string; body: string }[];
  appLabel: string;
  apps: Record<ApplicationId, string>;
};

const content: Record<MarketingLanguage, ConnectionContent> = {
  es: {
    title: "Qué necesita conexión",
    body: "Servicio de tienda, internet y sincronización cumplen funciones distintas.",
    items: [
      { title: "Servicio de tienda", body: "Venta y Gestión necesitan acceder al servicio configurado en el equipo o en la red de la tienda. Si no responde, Venta no puede continuar la operativa." },
      { title: "Internet y servicios externos", body: "La sincronización central y los servicios externos necesitan internet. Las funciones disponibles ante un corte de internet se validan con la configuración de tu tienda." },
      { title: "Consulta desde SaaS", body: "Con internet, consulta documentos y datos de existencias recibidos de las tiendas, además de licencias, facturas del servicio y soporte según permisos. Si una tienda deja de sincronizar, el portal muestra la última información recibida; no ofrece edición remota completa." }
    ],
    appLabel: "Conexión de esta aplicación",
    apps: {
      venta: "Necesita acceso al servicio de tienda, en el propio equipo o por red local. Sin ese servicio no puede vender. La sincronización central y los servicios externos requieren internet.",
      gestion: "Necesita acceso al servicio de tienda. La información central depende de internet y de la sincronización configurada.",
      pda: "Necesita red y acceso al servicio de tienda desde un dispositivo autorizado. No es una aplicación de almacén completamente offline.",
      saas: "Necesita internet y tiendas vinculadas. Muestra los datos recibidos por sincronización, no necesariamente el estado actual de una tienda desconectada. No ofrece edición remota completa."
    }
  },
  en: {
    title: "What needs a connection",
    body: "The store service, internet and synchronisation have different roles.",
    items: [
      { title: "Store service", body: "Sales and Management need access to the service configured on the computer or store network. If it is unavailable, Sales cannot continue operating." },
      { title: "Internet and external services", body: "Central synchronisation and external services need an internet connection. The features available during an internet outage are checked against your store configuration." },
      { title: "Viewing through SaaS", body: "With internet access, view documents and stock data received from stores, plus licences, service invoices and support within your permissions. If a store stops synchronising, the portal shows its last received information; it does not offer full remote editing." }
    ],
    appLabel: "This application's connection",
    apps: {
      venta: "Needs access to the store service, on the same computer or over the local network. Sales are blocked without it. Central synchronisation and external services need internet access.",
      gestion: "Needs access to the store service. Central information depends on internet access and configured synchronisation.",
      pda: "Needs network access to the store service from an authorised device. It is not a fully offline warehouse app.",
      saas: "Needs internet access and linked stores. Shows data received through synchronisation, not necessarily a disconnected store's current state. It does not offer full remote editing."
    }
  },
  zh: {
    title: "哪些功能需要连接",
    body: "门店服务、互联网和数据同步各有不同作用。",
    items: [
      { title: "门店服务", body: "销售和管理应用需要访问电脑本机或门店网络中配置的服务。该服务不可用时，销售应用无法继续作业。" },
      { title: "互联网与外部服务", body: "中央同步和外部服务需要互联网连接。互联网中断时哪些功能可用，需按门店的具体配置确认。" },
      { title: "通过 SaaS 查询", body: "通过互联网按权限查询从门店接收到的单据和库存数据，以及许可证、服务账单和支持信息。门店停止同步时，门户显示最近收到的信息，不提供完整的远程编辑功能。" }
    ],
    appLabel: "此应用的连接要求",
    apps: {
      venta: "需要访问本机或局域网中的门店服务。该服务不可用时无法销售。中央同步和外部服务需要互联网连接。",
      gestion: "需要访问门店服务。中央数据取决于互联网连接及已配置的同步机制。",
      pda: "需要授权设备通过网络访问门店服务，并非完全离线的仓库应用。",
      saas: "需要互联网连接及已关联的门店。展示实际同步收到的数据，不一定反映断网门店的当前状态，不提供完整的远程编辑功能。"
    }
  }
};

export function getConnectionContent(language: MarketingLanguage): ConnectionContent {
  return content[language];
}

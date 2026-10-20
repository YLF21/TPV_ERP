import { appGuideOrder, getApplicationGuides } from "./applicationGuides";
import type { ApplicationId } from "./applicationGuideTypes";
import { getMarketingContent, type MarketingLanguage } from "./marketingI18n";

export type CompatibilityApplication = {
  name: string;
  requirements: readonly string[];
  toConfirm: readonly string[];
};

export type CompatibilityContent = {
  title: string;
  subtitle: string;
  documentedLabel: string;
  toConfirmLabel: string;
  verificationNote: string;
  prepareTitle: string;
  prepareItems: readonly string[];
  privacyNote: string;
  apps: Record<ApplicationId, CompatibilityApplication>;
};

type CompatibilityCopy = Omit<CompatibilityContent, "apps"> & {
  apps: Record<ApplicationId, { toConfirm: readonly string[] }>;
};

const content: Record<MarketingLanguage, CompatibilityCopy> = {
  es: {
    title: "¿Encaja con tus equipos?",
    subtitle: "Distingue los requisitos de cada aplicación de lo que hay que comprobar en tu instalación.",
    documentedLabel: "Requisitos documentados",
    toConfirmLabel: "Por comprobar en tu instalación",
    verificationNote: "Estos requisitos describen condiciones de acceso, no una lista de modelos certificados ni una matriz completa de versiones. Pendiente de validación: versiones, recursos y periféricos de tu instalación.",
    prepareTitle: "Qué información preparar para revisarlo",
    prepareItems: [
      "Modelo del equipo y versión del sistema operativo; navegador y versión si utilizas PDA o SaaS.",
      "Modelos de impresora, lector, cajón y terminal de pago, su forma de conexión y proveedor de la integración, si procede.",
      "Número de tiendas y puestos, red disponible y tareas que necesita realizar cada equipo.",
      "Si quieres importar desde Excel, estructura de columnas y una pequeña muestra anonimizada o ficticia."
    ],
    privacyNote: "Comparte solo los detalles técnicos necesarios. No envíes contraseñas, datos de tarjeta ni tu base de datos de clientes.",
    apps: {
      venta: { toConfirm: [
        "Versión y recursos de Windows y acceso del puesto al servicio local de tienda.",
        "Modelo, conexión y configuración de impresora de tickets, lector y cajón que quieras utilizar.",
        "Proveedor y modelo del terminal de pago, integración disponible y configuración antes de operar con pagos reales."
      ] },
      gestion: { toConfirm: [
        "Versión y recursos de Windows y acceso al servicio de tienda desde los puestos previstos.",
        "Estructura del Excel, correspondencia de campos y resultado de una prueba con datos de muestra antes de importar.",
        "Tareas de cada usuario y permisos necesarios para administración, catálogo y almacén."
      ] },
      pda: { toConfirm: [
        "Modelo Android, versión del sistema y del navegador, y funcionamiento del lector de códigos que utilices.",
        "Cobertura de red y acceso al servicio de tienda en las zonas donde se recibe, cuenta o repone mercancía.",
        "Vinculación y autorización del dispositivo desde Gestión y permisos para las tareas previstas."
      ] },
      saas: { toConfirm: [
        "Versión del navegador y conexión a internet desde los equipos de consulta.",
        "Organización, tiendas vinculadas y alcance de consulta permitido a cada usuario.",
        "Qué información llega de cada tienda y cómo comprobar la última sincronización antes de interpretar los datos."
      ] }
    }
  },
  en: {
    title: "Will it fit your equipment?",
    subtitle: "Separate each application's requirements from what needs checking in your installation.",
    documentedLabel: "Documented requirements",
    toConfirmLabel: "To check in your installation",
    verificationNote: "These requirements describe access conditions, not a certified-model list or a complete version matrix. Pending validation: the versions, resources and peripherals in your installation.",
    prepareTitle: "What to prepare for the review",
    prepareItems: [
      "Device model and operating system version; browser and version if you use PDA or SaaS.",
      "Printer, scanner, cash drawer and payment terminal models, how they connect and the integration provider, where applicable.",
      "Number of stores and workstations, available network and the tasks each team needs to perform.",
      "For an Excel import, the column structure and a small anonymised or fictional sample."
    ],
    privacyNote: "Share only the technical details needed. Do not send passwords, card details or your customer database.",
    apps: {
      venta: { toConfirm: [
        "Windows version and resources, and the workstation's access to the local store service.",
        "The model, connection and configuration of the receipt printer, scanner and cash drawer you intend to use.",
        "Payment terminal provider and model, available integration and configuration before processing real payments."
      ] },
      gestion: { toConfirm: [
        "Windows version and resources, and access to the store service from the planned workstations.",
        "Excel structure, field mapping and the result of a trial with sample data before importing.",
        "Each user's tasks and the permissions needed for administration, catalogue and warehouse work."
      ] },
      pda: { toConfirm: [
        "Android model, operating system and browser versions, and how your barcode scanner works with them.",
        "Network coverage and access to the store service where goods are received, counted or replenished.",
        "Device pairing and approval from Management, and permissions for the intended tasks."
      ] },
      saas: { toConfirm: [
        "Browser version and internet access from the devices used for viewing information.",
        "Organisation, linked stores and each user's permitted viewing scope.",
        "What information arrives from each store and how to check the last synchronisation before interpreting the data."
      ] }
    }
  },
  zh: {
    title: "是否适合您现有的设备？",
    subtitle: "区分各应用已有的使用要求，以及需要结合您的安装环境核对的事项。",
    documentedLabel: "已说明的使用要求",
    toConfirmLabel: "需在您的安装环境中核对",
    verificationNote: "这些要求说明使用条件，不代表认证机型清单或完整版本兼容表。待验证事项包括您安装环境中的系统版本、设备资源和外设。",
    prepareTitle: "核对前可准备的信息",
    prepareItems: [
      "设备型号和操作系统版本；如使用 PDA 或 SaaS，还需浏览器名称及版本。",
      "打印机、扫码器、钱箱及支付终端的型号、连接方式，以及适用的集成服务商。",
      "门店和工作站数量、可用网络，以及各团队需要完成的工作。",
      "如需从 Excel 导入，可准备列结构及少量匿名化或虚构的样本数据。"
    ],
    privacyNote: "只提供必要的技术信息。请勿发送密码、银行卡信息或客户数据库。",
    apps: {
      venta: { toConfirm: [
        "Windows 版本、设备资源，以及收银工作站能否访问门店本地服务。",
        "计划使用的小票打印机、扫码器及钱箱的型号、连接方式和配置。",
        "支付终端的服务商、型号、可用集成及配置；处理真实支付前需核对。"
      ] },
      gestion: { toConfirm: [
        "Windows 版本、设备资源，以及各管理工作站能否访问门店服务。",
        "Excel 结构、字段对应关系，以及正式导入前的样本测试结果。",
        "各用户的工作内容，以及管理、商品资料和仓库操作所需权限。"
      ] },
      pda: { toConfirm: [
        "Android 设备型号、系统及浏览器版本，以及扫码器在该环境中的使用情况。",
        "收货、盘点和补货区域的网络覆盖，以及能否访问门店服务。",
        "设备是否已通过管理应用关联并授权，以及相关仓库操作权限。"
      ] },
      saas: { toConfirm: [
        "查询设备的浏览器版本及互联网连接。",
        "所属组织、关联门店，以及各用户获准查询的范围。",
        "各门店上传的信息范围，以及判断数据前如何查询最近同步时间。"
      ] }
    }
  }
};

export function getCompatibilityContent(language: MarketingLanguage): CompatibilityContent {
  const copy = content[language];
  const guides = getApplicationGuides(language);
  const applications = getMarketingContent(language).apps.items;
  const application = (id: ApplicationId): CompatibilityApplication => ({
    name: applications[appGuideOrder.indexOf(id)][0],
    requirements: guides.apps[id].requirements,
    toConfirm: copy.apps[id].toConfirm
  });

  return {
    ...copy,
    apps: {
      venta: application("venta"),
      gestion: application("gestion"),
      pda: application("pda"),
      saas: application("saas")
    }
  };
}

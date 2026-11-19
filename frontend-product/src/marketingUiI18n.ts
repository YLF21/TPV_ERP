import type { MarketingLanguage } from "./marketingI18n";

const uiContent = {
  es: {
    skipToContent: "Saltar al contenido",
    primaryNavigation: "Navegación principal",
    openMenu: "Abrir menú",
    closeMenu: "Cerrar menú",
    clientAccess: "Acceso clientes",
    clients: "Clientes",
    internalAccess: "Acceso interno",
    portalUnavailable: "Acceso no disponible por el momento",
    tourApplications: "Aplicaciones del recorrido",
    tourSteps: "Pasos del recorrido"
  },
  en: {
    skipToContent: "Skip to content",
    primaryNavigation: "Main navigation",
    openMenu: "Open menu",
    closeMenu: "Close menu",
    clientAccess: "Customer login",
    clients: "Customers",
    internalAccess: "Staff login",
    portalUnavailable: "Access is currently unavailable",
    tourApplications: "Tour applications",
    tourSteps: "Tour steps"
  },
  zh: {
    skipToContent: "跳转到主要内容",
    primaryNavigation: "主导航",
    openMenu: "打开菜单",
    closeMenu: "关闭菜单",
    clientAccess: "客户登录",
    clients: "客户登录",
    internalAccess: "内部登录",
    portalUnavailable: "暂时无法访问",
    tourApplications: "导览应用",
    tourSteps: "导览步骤"
  }
} satisfies Record<MarketingLanguage, Record<string, string>>;

export function getMarketingUiContent(language: MarketingLanguage) {
  return uiContent[language];
}

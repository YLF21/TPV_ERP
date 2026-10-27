import type { MarketingLanguage } from "./marketingI18n";

const uiContent = {
  es: {
    skipToContent: "Saltar al contenido",
    primaryNavigation: "Navegación principal",
    openMenu: "Abrir menú",
    closeMenu: "Cerrar menú",
    tourApplications: "Aplicaciones del recorrido",
    tourSteps: "Pasos del recorrido"
  },
  en: {
    skipToContent: "Skip to content",
    primaryNavigation: "Main navigation",
    openMenu: "Open menu",
    closeMenu: "Close menu",
    tourApplications: "Tour applications",
    tourSteps: "Tour steps"
  },
  zh: {
    skipToContent: "跳转到主要内容",
    primaryNavigation: "主导航",
    openMenu: "打开菜单",
    closeMenu: "关闭菜单",
    tourApplications: "导览应用",
    tourSteps: "导览步骤"
  }
} satisfies Record<MarketingLanguage, Record<string, string>>;

export function getMarketingUiContent(language: MarketingLanguage) {
  return uiContent[language];
}

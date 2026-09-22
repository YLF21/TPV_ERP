import { useI18n } from "../../i18n";
const labels = {
  localCodeColumn: ["Código local", "Local code", "本地编号"],
  tableHint: ["Doble clic o Intro para abrir la ficha de tienda.", "Double-click or press Enter to open store details.", "双击或按回车打开门店详情。"],
  storeDetail: ["Ficha de tienda", "Store details", "门店详情"],
  newStore: ["Alta nueva tienda", "New store", "新增门店"],
  storeData: ["Datos de la tienda", "Store information", "门店资料"],
  chooseProfile: ["Selecciona el perfil comercial", "Select a commercial profile", "选择商业模式"],
  wholesale: ["Mayorista", "Wholesale", "批发"],
  retail: ["Minorista", "Retail", "零售"],
  chooseTax: ["Selecciona el régimen fiscal", "Select the tax regime", "选择税制"],
  taxLocked: ["Régimen fiscal vinculado a la licencia de la tienda", "Tax regime linked to the store license", "税制已与门店许可证关联"],
  servicePrice: ["Precio SaaS (EUR)", "SaaS price (EUR)", "SaaS费用（欧元）"],
  billingPeriod: ["Periodicidad", "Billing period", "计费周期"],
  choosePeriod: ["Selecciona la periodicidad", "Select a billing period", "选择计费周期"],
  monthly: ["Mensual", "Monthly", "每月"],
  annual: ["Anual", "Annual", "每年"],
  dateRequired: ["Indica la caducidad de la tienda", "Enter the store expiry date", "请填写门店到期日期"],
  unconfigured: ["Sin configurar", "Not configured", "未设置"],
  renewalPermission: ["Necesitas permiso de renovación para cambiar caducidad o límites de una tienda con licencia", "Renewal permission is required to change the expiry or limits of a licensed store", "修改已授权门店的到期日期或终端限额需要续期权限"],
} as const;
export function useStoreLabels() {
  const { language } = useI18n();
  return (key: keyof typeof labels) => labels[key][language === "zh" ? 2 : language === "en" ? 1 : 0];
}

import { useI18n } from "../../i18n";
const labels = {
  configuration: ["Configuración de licencia", "License configuration", "许可证配置"],
  close: ["Cerrar", "Close", "关闭"],
  generate: ["Generar código de activación", "Generate activation code", "生成激活码"],
  windows: ["Terminales Windows", "Windows terminals", "Windows 终端"],
  pda: ["Terminales PDA", "PDA terminals", "PDA 终端"],
  dateRequired: ["Indica la caducidad de la licencia", "Enter the license expiry date", "请填写许可证到期日期"],
  chooseCompany: ["Selecciona una empresa para ver sus tiendas.", "Select a company to view its stores.", "选择企业以查看其门店。"],
  companyStores: ["Tiendas de la empresa", "Company stores", "企业门店"],
  activeCodes: ["Códigos de activación vigentes", "Active activation codes", "有效激活码"],
  activationCode: ["Código de activación", "Activation code", "激活码"],
  codeExpiry: ["Caduca el", "Expires at", "到期时间"],
  remaining: ["Tiempo restante", "Time remaining", "剩余时间"],
  expiresSoon: ["Los códigos son válidos durante 30 minutos y se consumen al activar la instalación. Cada tienda solo puede tener uno vigente; generar otro invalida el anterior.", "Codes are valid for 30 minutes and are consumed when the installation is activated. Each store can have only one active code; generating another invalidates the previous one.", "激活码有效期为30分钟，激活安装后即失效。每家门店只能有一个有效激活码；生成新码将使旧码失效。"],
  generated: ["Código de activación generado", "Activation code generated", "已生成激活码"],
  generating: ["Generando…", "Generating…", "正在生成…"],
  copyCode: ["Copiar", "Copy", "复制"],
  copiedCode: ["Código copiado", "Code copied", "激活码已复制"],
  codeExpired: ["El código ha caducado. Actualiza la lista.", "The code has expired. Refresh the list.", "激活码已过期。请刷新列表。"],
  copyCodeFailed: ["No se ha podido copiar el código. Selecciónalo y cópialo manualmente.", "The code could not be copied. Select it and copy it manually.", "无法复制激活码。请选择并手动复制。"],
  removeCode: ["Eliminar", "Delete", "删除"],
  removingCode: ["Eliminando…", "Deleting…", "正在删除…"],
  removedCode: ["Código de activación eliminado", "Activation code deleted", "激活码已删除"],
  inactiveStore: ["Tienda inactiva", "Inactive store", "门店已停用"],
  moreStores: ["Cargar más tiendas", "Load more stores", "加载更多门店"],
  refreshCodes: ["Actualizar códigos", "Refresh codes", "刷新激活码"],
} as const;
export function useLicenseLabels() {
  const { language } = useI18n();
  return (key: keyof typeof labels) => labels[key][language === "es" ? 0 : language === "en" ? 1 : 2];
}

import { useI18n } from "../../i18n";
const labels = {
  options: ["Opciones de columna", "Column options", "列选项"],
  sort: ["Ordenar por", "Sort by", "排序依据"],
  resize: ["Cambiar ancho de", "Resize", "调整列宽"],
  left: ["Mover a la izquierda", "Move left", "向左移动"],
  right: ["Mover a la derecha", "Move right", "向右移动"],
  columns: ["Columnas visibles", "Visible columns", "可见列"],
  reset: ["Restablecer columnas", "Reset columns", "重置列"],
  openHint: ["Doble clic o Intro para configurar la licencia.", "Double-click or press Enter to configure the license.", "双击或按回车键配置许可证。"],
} as const;
export function useTableLabels() {
  const { language } = useI18n();
  return (key: keyof typeof labels) => labels[key][language === "es" ? 0 : language === "en" ? 1 : 2];
}

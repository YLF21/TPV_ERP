import type { MarketingLanguage } from "./marketingI18n";

const content = {
  es: {
    title: "Ayuda para importar productos",
    steps: [
      ["Prepara el Excel", "Fila 1: encabezados; desde la 2: datos. Códigos como texto; precios como números."],
      ["Revisa y confirma", "Relaciona columnas y revisa coincidencias y errores. Para crear productos, comprueba familia e impuesto. La vista previa no modifica productos; aplicar cambios requiere permisos y confirmación."]
    ],
    download: "Descargar Excel de ejemplo",
    note: "Ejemplo ficticio con guía. Solo catálogo y precios; sin existencias, clientes ni historial. No es una migración completa: prueba antes de aplicar."
  },
  en: {
    title: "Product import help",
    steps: [
      ["Prepare the spreadsheet", "Row 1: headings; data from row 2. Codes as text; prices as numbers."],
      ["Review and confirm", "Map columns and review matches and errors. For new products, check category and tax. The preview does not change products; applying changes requires permission and confirmation."]
    ],
    download: "Download Excel sample",
    note: "Fictitious sample with a guide. Catalogue and prices only, without stock quantities, customers or history. Not a full migration: test before applying."
  },
  zh: {
    title: "商品导入帮助",
    steps: [
      ["准备 Excel", "第 1 行表头，第 2 行起填写数据。编码为文本，价格为数字。"],
      ["检查并确认", "对应各列，检查匹配结果及错误。新商品需核对分类和税项。预览不会修改商品，应用修改需要权限及明确确认。"]
    ],
    download: "下载 Excel 示例",
    note: "虚构示例及说明。仅含商品目录与价格，不含库存数量、客户或历史记录。并非完整迁移，请先测试再应用。"
  }
} as const;

export function getProductImportHelp(language: MarketingLanguage) { return content[language]; }

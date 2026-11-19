import { useI18n } from "../../i18n";

const labels = {
  search: ["Buscar artículo o código", "Search article or code", "搜索商品或编码"],
  ranking: ["Clasificación de artículos", "Article ranking", "商品排名"],
  rankingScope: ["Hasta 12 referencias por moneda, ordenadas por importe neto del período filtrado.", "Up to 12 items per currency, ranked by net amount for the selected period.", "按所选期间的净额排名，每种币种最多显示 12 个商品。"],
  rankingChartLimit: ["El gráfico admite hasta {count} referencias. Consulta la clasificación completa del período en la tabla.", "The chart supports up to {count} items. View the full ranking for the period in the table.", "图表最多支持 {count} 个商品。可在表格中查看期间的完整排名。"],
  rank: ["Posición", "Rank", "排名"],
  code: ["Código", "Code", "编码"],
  article: ["Artículo", "Article", "商品"],
  relatedReports: ["Informes relacionados", "Related reports", "相关报表"],
  viewSales: ["Ventas", "Sales", "销售"],
  viewComparisonSales: ["Ventas comparadas", "Comparison sales", "对比销售"],
  viewStock: ["Existencias", "Stock", "库存"],
  quantitySold: ["Cantidad vendida", "Quantity sold", "销售数量"],
  quantityReturned: ["Cantidad devuelta", "Quantity returned", "退货数量"],
  netQuantity: ["Cantidad neta", "Net quantity", "净数量"],
  salesAmount: ["Importe de venta", "Sales amount", "销售金额"],
  returnsAmount: ["Importe devuelto", "Return amount", "退货金额"],
  netAmount: ["Importe neto", "Net amount", "净金额"],
  saleDocuments: ["Ventas del artículo", "Article sales", "商品销售单据"],
  averageQuantityPerSale: ["Unidades por venta", "Units per sale", "每笔销售数量"],
  amountSharePercent: ["Participación en importe (%)", "Amount share (%)", "金额占比 (%)"],
  previousAverageQuantity: ["Unidades por venta comparadas", "Comparison units per sale", "对比每笔销售数量"],
  previousAmountShare: ["Participación comparada (%)", "Comparison amount share (%)", "对比金额占比 (%)"],
  metricScope: ["Las unidades por venta usan cantidades positivas y documentos de venta distintos, sin diferencias. La participación compara el importe neto con el de todas las líneas de producto computables de la misma moneda y período.", "Units per sale use positive quantities and distinct sale documents, excluding differences. Amount share compares net amount with all eligible product line amounts in the same currency and period.", "每笔销售数量按正数数量与不同销售单据计算，排除差额。金额占比按相同币种和期间的可计入商品行金额计算。"],
  previousQuantity: ["Cantidad neta comparada", "Comparison net quantity", "对比净数量"],
  previousAmount: ["Importe neto comparado", "Comparison net amount", "对比净金额"],
  deltaQuantity: ["Variación de cantidad", "Quantity change", "数量变化"],
  dataGaps: ["Clasificación pendiente por falta de líneas o relaciones documentales.", "Ranking pending due to missing lines or document relationships.", "由于缺少行项目或单据关联，暂无法排名。"],
  noArticles: ["No hay artículos para estos filtros.", "No articles match these filters.", "没有符合筛选条件的商品。"],
} as const;

export function useArticleLabels() {
  const { language } = useI18n();
  const index = language === "zh" ? 2 : language === "en" ? 1 : 0;
  return (key: keyof typeof labels) => labels[key][index];
}

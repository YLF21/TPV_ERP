const reportNames = {
  statistics: ["ventas", "sales", "销售"], articles: ["articulos", "articles", "商品"],
  documents: ["documentos", "documents", "单据"], movements: ["movimientos", "movements", "库存变动"],
  inventory: ["inventario", "inventory", "库存"], catalog: ["catalogo", "catalogue", "目录"],
  balances: ["existencias", "stock", "库存余额"], overview: ["tiendas", "stores", "门店"],
  history: ["capturas", "snapshots", "快照"], comparison: ["comparacion", "comparison", "比较"],
};
function safe(value) { return String(value ?? "").normalize("NFKC").replace(/[<>:"/\\|?*\x00-\x1F]/g, "-").replace(/\s+/g, "-").replace(/^[.-]+|[. -]+$/g, "").slice(0, 80); }
export function reportExportFilename(path, filters, format, language = "es", title, now = new Date()) {
  const url = new URL(path, "http://localhost");
  filters = { ...Object.fromEntries(url.searchParams), ...filters };
  const section = url.pathname.split("/").at(-1);
  const index = language === "zh" ? 2 : language === "en" ? 1 : 0;
  const kind = url.searchParams.get("kind");
  const name = title || (reportNames[section]?.[index] ?? section);
  const values = ["espos", name, kind && reportNames[kind]?.[index]];
  if (filters.storeName || filters.storeId) values.push(filters.storeName || filters.storeId);
  else if (filters.companyName || filters.companyId) values.push(filters.companyName || filters.companyId);
  if (filters.province) values.push(filters.province);
  if (filters.from && filters.to) values.push(filters.from, filters.to);
  else values.push(now.toISOString().slice(0, 19).replaceAll(":", "-"), "UTC");
  return values.filter(Boolean).map(safe).join("-") + "." + (format === "csv" ? "csv" : "xlsx");
}

import { useEffect, useMemo, useState } from "react";
import type { LocaleCode } from "../types";
import {
  defaultHardwareConfig,
  getHardwareBridge,
  normalizeHardwareConfigForUi,
  type HardwareConfig,
  type HardwarePrinter,
  type ProductLabelDestination,
  type ProductLabelPrintRequest,
  type ProductLabelProfile,
} from "../hardware/hardware";
import { isValidEan, type InternalEanProduct } from "../sale/internalEan";

type LabelProduct = InternalEanProduct & { salePrice?: number | string | null };

type Props = {
  open: boolean;
  locale: LocaleCode;
  storeName: string;
  products: LabelProduct[];
  initialProductId?: string;
  onClose: () => void;
  onPrinted: (pdf: boolean) => void;
};

const copy = {
  es: {
    title: "Imprimir etiqueta de artículo", product: "Producto", search: "Buscar producto", noProducts: "No hay productos coincidentes",
    barcode: "Código EAN", missingEan: "El producto no tiene un EAN-8 o EAN-13 válido. Usa Ctrl+F2 para asignarlo.",
    profile: "Perfil de etiqueta", newProfile: "Nuevo perfil", deleteProfile: "Eliminar perfil", profileName: "Nombre del perfil",
    destination: "Destino", labelPrinter: "Impresora de etiquetas", ticketPrinter: "Impresora de tickets", a4: "Hoja A4",
    printer: "Impresora", width: "Ancho (mm)", height: "Alto (mm)", orientation: "Orientación", portrait: "Vertical", landscape: "Horizontal",
    margins: "Márgenes A4 (mm)", top: "Superior", right: "Derecho", bottom: "Inferior", left: "Izquierdo",
    gaps: "Separación entre etiquetas (mm)", horizontal: "Horizontal", vertical: "Vertical", copies: "Copias",
    storeName: "Mostrar nombre de tienda", start: "Primera etiqueta disponible en la hoja", saveDefault: "Guardar como predeterminado",
    print: "Imprimir", pdf: "Guardar PDF", close: "Cerrar", error: "No se pudo imprimir la etiqueta.", saved: "Perfil guardado para este terminal",
    printed: "Etiqueta enviada a la impresora", pdfSaved: "PDF de etiquetas guardado",
  },
  en: {
    title: "Print product label", product: "Product", search: "Search product", noProducts: "No matching products", barcode: "EAN code",
    missingEan: "The product has no valid EAN-8 or EAN-13. Use Ctrl+F2 to assign one.", profile: "Label profile", newProfile: "New profile",
    deleteProfile: "Delete profile", profileName: "Profile name", destination: "Destination", labelPrinter: "Label printer",
    ticketPrinter: "Ticket printer", a4: "A4 sheet", printer: "Printer", width: "Width (mm)", height: "Height (mm)", orientation: "Orientation",
    portrait: "Portrait", landscape: "Landscape", margins: "A4 margins (mm)", top: "Top", right: "Right", bottom: "Bottom", left: "Left",
    gaps: "Label gaps (mm)", horizontal: "Horizontal", vertical: "Vertical", copies: "Copies", storeName: "Show store name",
    start: "First available label on the sheet", saveDefault: "Save as default", print: "Print", pdf: "Save PDF", close: "Close",
    error: "The label could not be printed.", saved: "Profile saved for this terminal",
    printed: "Label sent to the printer", pdfSaved: "Label PDF saved",
  },
  zh: {
    title: "打印商品标签", product: "商品", search: "搜索商品", noProducts: "没有匹配的商品", barcode: "EAN 代码",
    missingEan: "商品没有有效的 EAN-8 或 EAN-13。请使用 Ctrl+F2 分配。", profile: "标签配置", newProfile: "新建配置", deleteProfile: "删除配置",
    profileName: "配置名称", destination: "输出目标", labelPrinter: "标签打印机", ticketPrinter: "小票打印机", a4: "A4 标签纸",
    printer: "打印机", width: "宽度（毫米）", height: "高度（毫米）", orientation: "方向", portrait: "纵向", landscape: "横向",
    margins: "A4 边距（毫米）", top: "上", right: "右", bottom: "下", left: "左", gaps: "标签间距（毫米）", horizontal: "水平",
    vertical: "垂直", copies: "份数", storeName: "显示门店名称", start: "纸张上的第一个可用标签", saveDefault: "保存为默认",
    print: "打印", pdf: "保存 PDF", close: "关闭", error: "无法打印标签。", saved: "配置已保存到此终端",
    printed: "标签已发送到打印机", pdfSaved: "标签 PDF 已保存",
  },
} as const;

function freshProfile(): ProductLabelProfile {
  return {
    ...defaultHardwareConfig.productLabelProfiles[0],
    id: globalThis.crypto?.randomUUID?.() ?? `label-${Date.now()}`,
    name: "58 x 40 mm",
    destination: "LABEL_PRINTER",
    printerName: "",
  };
}

function numeric(value: string, fallback: number, min: number, max: number) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback;
}

export function SaleProductLabelDialog({ open, locale, storeName, products, initialProductId = "", onClose, onPrinted }: Props) {
  const t = copy[locale];
  const bridge = getHardwareBridge();
  const [config, setConfig] = useState<HardwareConfig>(() => normalizeHardwareConfigForUi());
  const [printers, setPrinters] = useState<HardwarePrinter[]>([]);
  const [profileId, setProfileId] = useState(defaultHardwareConfig.defaultProductLabelProfileId);
  const [productId, setProductId] = useState(initialProductId);
  const [query, setQuery] = useState("");
  const [barcode, setBarcode] = useState("");
  const [startPosition, setStartPosition] = useState(0);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    let active = true;
    setProductId(initialProductId);
    setQuery(""); setStartPosition(0); setStatus(""); setError("");
    void Promise.all([bridge.getHardwareConfig(), bridge.listPrinters()]).then(([loaded, detected]) => {
      if (!active) return;
      const normalized = normalizeHardwareConfigForUi(loaded);
      setConfig(normalized);
      setProfileId(normalized.defaultProductLabelProfileId);
      setPrinters(detected.ok ? detected.printers : []);
    }).catch(() => setError(t.error));
    return () => { active = false; };
  }, [initialProductId, open]);

  const profile = config.productLabelProfiles.find((candidate) => candidate.id === profileId)
    ?? config.productLabelProfiles[0];
  const filteredProducts = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return products.filter((product) => !normalized || [product.code, product.barcode, product.barcode2, product.name]
      .some((value) => value?.toLocaleLowerCase().includes(normalized))).slice(0, 80);
  }, [products, query]);
  const product = products.find((candidate) => candidate.id === productId);
  const barcodeOptions = [product?.barcode, product?.barcode2].filter((value): value is string => Boolean(value && isValidEan(value)));

  useEffect(() => {
    setBarcode((current) => barcodeOptions.includes(current) ? current : (barcodeOptions[0] ?? ""));
  }, [productId, product?.barcode, product?.barcode2]);

  useEffect(() => {
    if (!open) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || busy) return;
      event.preventDefault();
      onClose();
    };
    globalThis.addEventListener("keydown", closeOnEscape);
    return () => globalThis.removeEventListener("keydown", closeOnEscape);
  }, [busy, onClose, open]);

  if (!open || !profile) return null;

  const pageWidth = profile.orientation === "LANDSCAPE" ? 297 : 210;
  const pageHeight = profile.orientation === "LANDSCAPE" ? 210 : 297;
  const columns = Math.max(1, Math.floor((pageWidth - profile.marginLeftMm - profile.marginRightMm + profile.horizontalGapMm) / (profile.widthMm + profile.horizontalGapMm)));
  const rows = Math.max(1, Math.floor((pageHeight - profile.marginTopMm - profile.marginBottomMm + profile.verticalGapMm) / (profile.heightMm + profile.verticalGapMm)));
  const capacity = columns * rows;

  function updateProfile(patch: Partial<ProductLabelProfile>) {
    setConfig((current) => ({ ...current, productLabelProfiles: current.productLabelProfiles.map((candidate) => candidate.id === profile.id ? { ...candidate, ...patch } : candidate) }));
  }

  async function persistProfile() {
    const next = { ...config, defaultProductLabelProfileId: profile.id };
    const result = await bridge.saveHardwareConfig(next);
    if (!result.ok) throw new Error(result.message);
    setConfig(next);
    setStatus(t.saved);
    return next;
  }

  function request(): ProductLabelPrintRequest {
    if (!product || !barcode) throw new Error(t.missingEan);
    return {
      storeName,
      product: { name: product.name ?? "", code: product.code ?? "", barcode, price: Number(product.salePrice ?? 0) },
      profile,
      copies: profile.copies,
      startPosition: profile.destination === "A4" ? startPosition : undefined,
    };
  }

  async function output(pdf: boolean) {
    if (busy) return;
    setBusy(true); setError(""); setStatus("");
    try {
      const next = await persistProfile();
      const payload = request();
      if (pdf) {
        const result = await bridge.exportProductLabelPdf(
          payload,
          `etiqueta-${product?.code || barcode}.pdf`,
        );
        if (!result.ok) throw new Error(result.message);
        if (!result.canceled) {
          setStatus(t.pdfSaved);
          onPrinted(true);
        }
      } else {
        const result = await bridge.printProductLabel(payload, next);
        if (!result.ok) throw new Error(result.message);
        setStatus(t.printed);
        onPrinted(false);
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t.error);
    } finally { setBusy(false); }
  }

  return <div className="sale-utility-backdrop" role="presentation"><section className="sale-utility-dialog sale-product-label-dialog" role="dialog" aria-modal="true" aria-labelledby="product-label-title">
    <header><h2 id="product-label-title">{t.title}</h2></header>
    <div className="sale-product-label-body">
      <section className="sale-product-label-products"><h3>{t.product}</h3><label><span>{t.search}</span><input autoFocus value={query} onChange={(event) => setQuery(event.currentTarget.value)} /></label>
        <select size={8} value={productId} onChange={(event) => setProductId(event.currentTarget.value)}>{filteredProducts.length === 0 && <option value="">{t.noProducts}</option>}{filteredProducts.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.code ? `${candidate.code} · ` : ""}{candidate.name ?? candidate.barcode ?? candidate.id}</option>)}</select>
        <label><span>{t.barcode}</span><select value={barcode} disabled={barcodeOptions.length === 0} onChange={(event) => setBarcode(event.currentTarget.value)}>{barcodeOptions.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
        {product && barcodeOptions.length === 0 && <p className="sale-dialog-error">{t.missingEan}</p>}
      </section>
      <section className="sale-product-label-profile"><h3>{t.profile}</h3><div className="sale-product-label-profile-row"><select value={profileId} onChange={(event) => setProfileId(event.currentTarget.value)}>{config.productLabelProfiles.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.name}</option>)}</select><button type="button" onClick={() => { const next = freshProfile(); setConfig((current) => ({ ...current, productLabelProfiles: [...current.productLabelProfiles, next] })); setProfileId(next.id); }}>{t.newProfile}</button><button type="button" disabled={config.productLabelProfiles.length <= 1} onClick={() => { const remaining = config.productLabelProfiles.filter((candidate) => candidate.id !== profile.id); setConfig((current) => ({ ...current, productLabelProfiles: remaining })); setProfileId(remaining[0]?.id ?? ""); }}>{t.deleteProfile}</button></div>
        <label><span>{t.profileName}</span><input value={profile.name} onChange={(event) => updateProfile({ name: event.currentTarget.value })} /></label>
        <label><span>{t.destination}</span><select value={profile.destination} onChange={(event) => updateProfile({ destination: event.currentTarget.value as ProductLabelDestination, printerName: "" })}><option value="LABEL_PRINTER">{t.labelPrinter}</option><option value="TICKET_PRINTER">{t.ticketPrinter}</option><option value="A4">{t.a4}</option></select></label>
        <label><span>{t.printer}</span><select value={profile.printerName} onChange={(event) => updateProfile({ printerName: event.currentTarget.value })}><option value="">{profile.destination === "TICKET_PRINTER" ? config.ticketPrinterName || "—" : profile.destination === "A4" ? config.a4PrinterName || "—" : "—"}</option>{printers.map((printer) => <option key={printer.name} value={printer.name}>{printer.displayName}</option>)}</select></label>
        <div className="sale-product-label-measures"><label><span>{t.width}</span><input type="number" min="20" max="210" step="0.1" value={profile.widthMm} onChange={(event) => updateProfile({ widthMm: numeric(event.currentTarget.value, 58, 20, 210) })} /></label><label><span>{t.height}</span><input type="number" min="15" max="297" step="0.1" value={profile.heightMm} onChange={(event) => updateProfile({ heightMm: numeric(event.currentTarget.value, 40, 15, 297) })} /></label><label><span>{t.copies}</span><input type="number" min="1" max="999" value={profile.copies} onChange={(event) => updateProfile({ copies: Math.round(numeric(event.currentTarget.value, 1, 1, 999)) })} /></label></div>
        <label><span>{t.orientation}</span><select value={profile.orientation} onChange={(event) => updateProfile({ orientation: event.currentTarget.value as "PORTRAIT" | "LANDSCAPE" })}><option value="PORTRAIT">{t.portrait}</option><option value="LANDSCAPE">{t.landscape}</option></select></label>
        <label className="sale-product-label-checkbox"><input type="checkbox" checked={profile.showStoreName} onChange={(event) => updateProfile({ showStoreName: event.currentTarget.checked })} /><span>{t.storeName}</span></label>
        {profile.destination === "A4" && <><fieldset><legend>{t.margins}</legend><div className="sale-product-label-four"><label><span>{t.top}</span><input type="number" min="0" max="50" value={profile.marginTopMm} onChange={(event) => updateProfile({ marginTopMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label><label><span>{t.right}</span><input type="number" min="0" max="50" value={profile.marginRightMm} onChange={(event) => updateProfile({ marginRightMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label><label><span>{t.bottom}</span><input type="number" min="0" max="50" value={profile.marginBottomMm} onChange={(event) => updateProfile({ marginBottomMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label><label><span>{t.left}</span><input type="number" min="0" max="50" value={profile.marginLeftMm} onChange={(event) => updateProfile({ marginLeftMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label></div></fieldset><fieldset><legend>{t.gaps}</legend><div className="sale-product-label-measures"><label><span>{t.horizontal}</span><input type="number" min="0" max="25" value={profile.horizontalGapMm} onChange={(event) => updateProfile({ horizontalGapMm: numeric(event.currentTarget.value, 2, 0, 25) })} /></label><label><span>{t.vertical}</span><input type="number" min="0" max="25" value={profile.verticalGapMm} onChange={(event) => updateProfile({ verticalGapMm: numeric(event.currentTarget.value, 2, 0, 25) })} /></label></div></fieldset><div><strong>{t.start}</strong><div className="sale-product-label-grid" style={{ gridTemplateColumns: `repeat(${columns}, 1fr)` }}>{Array.from({ length: capacity }, (_, index) => <button type="button" key={index} className={startPosition === index ? "active" : ""} onClick={() => setStartPosition(index)}>{index + 1}</button>)}</div></div></>}
      </section>
      {(status || error) && <p className={error ? "sale-dialog-error" : "sale-dialog-success"} role={error ? "alert" : "status"}>{error || status}</p>}
    </div>
    <footer><button type="button" disabled={busy} onClick={onClose}>{t.close}</button><button type="button" disabled={busy} onClick={() => void persistProfile().catch((caught) => setError(caught instanceof Error ? caught.message : t.error))}>{t.saveDefault}</button><button type="button" disabled={busy || !product || !barcode} onClick={() => void output(true)}>{t.pdf}</button><button type="button" disabled={busy || !product || !barcode} onClick={() => void output(false)}>{t.print}</button></footer>
  </section></div>;
}

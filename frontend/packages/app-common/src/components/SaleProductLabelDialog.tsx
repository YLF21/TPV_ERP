import { useEffect, useMemo, useRef, useState, type DragEvent, type PointerEvent as ReactPointerEvent } from "react";
import "./SaleProductLabelDialog.css";
import type { LocaleCode } from "../types";
import {
  defaultHardwareConfig,
  getHardwareBridge,
  normalizeHardwareConfigForUi,
  type HardwareConfig,
  type HardwarePrinter,
  type ProductLabelDestination,
  type ProductLabelCommercial,
  type ProductLabelIssuer,
  type ProductLabelItem,
  type ProductLabelPage,
  type ProductLabelPlacement,
  type ProductLabelPrintRequest,
  type ProductLabelProfile,
} from "../hardware/hardware";
import { isValidEan, type InternalEanProduct } from "../sale/internalEan";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import {
  canPlaceProductLabel,
  clampProductLabelPlacement,
  productLabelMinimumSize,
  productLabelPageSize,
  productLabelPlacementCounts,
  productLabelSafeArea,
  quickPlaceProductLabels,
  validateProductLabelComposition,
} from "./productLabelLayout";
import { productLabelEanBits } from "./productLabelBarcode";

type LabelProduct = InternalEanProduct & { salePrice?: number | string | null };
export type ProductLabelCommercialContext = {
  productId: string;
  offer?: {
    regularPrice: number;
    offerPrice: number;
    discountPercent: number;
    endDate?: string | null;
  } | null;
  promotions: Array<{
    id: string;
    name: string;
    type: "PURCHASE_THRESHOLD_DISCOUNT" | "BUY_X_PAY_Y" | "SECOND_UNIT_PERCENT" | "FIXED_PACK_PRICE" | "QUANTITY_DISCOUNT";
    endDate?: string | null;
    minimumAmount?: number | null;
    minimumQuantity?: number | null;
    buyQuantity?: number | null;
    payQuantity?: number | null;
    buyXPayYMode?: "SAME_PRODUCT" | "MIXED_TARGETS" | null;
    discountAmount?: number | null;
    discountPercent?: number | null;
    maximumDiscount?: number | null;
    packPrice?: number | null;
  }>;
};
type SelectedProduct = { productId: string; barcode: string; copies: number };
type View = "SELECTION" | "A4_COMPOSER";
type PointerInteraction = {
  pointerId: number;
  pageIndex: number;
  instanceId: string;
  mode: "MOVE" | "RESIZE";
  startClientX: number;
  startClientY: number;
  pageWidthPx: number;
  pageHeightPx: number;
  placement: ProductLabelPlacement;
};

type Props = {
  open: boolean;
  locale: LocaleCode;
  storeName: string;
  issuer?: ProductLabelIssuer;
  issuerError?: string;
  products: LabelProduct[];
  loadCommercialContexts?: (productIds: string[]) => Promise<ProductLabelCommercialContext[]>;
  initialProductId?: string;
  onClose: () => void;
  onPrinted: (pdf: boolean) => void;
};

const copy = {
  es: {
    title: "Imprimir etiquetas de artículos", products: "Productos", search: "Buscar producto", noProducts: "No hay productos coincidentes",
    code: "Código", product: "Producto", barcode: "Código EAN", copies: "Copias", selected: "Seleccionados", missingEan: "Sin EAN válido",
    profile: "Perfil de etiqueta", newProfile: "Nuevo perfil", deleteProfile: "Eliminar perfil", profileName: "Nombre del perfil",
    destination: "Destino", labelPrinter: "Impresora de etiquetas", ticketPrinter: "Impresora de tickets", a4: "Hoja A4",
    printer: "Impresora", width: "Ancho (mm)", height: "Alto (mm)", orientation: "Orientación", portrait: "Vertical", landscape: "Horizontal",
    margins: "Márgenes A4 (mm)", top: "Superior", right: "Derecho", bottom: "Inferior", left: "Izquierdo",
    gaps: "Separación entre etiquetas (mm)", horizontal: "Horizontal", vertical: "Vertical", defaultCopies: "Copias iniciales",
    companyData: "Mostrar datos de empresa", saveDefault: "Guardar como predeterminado", print: "Imprimir", pdf: "Guardar PDF", close: "Cerrar",
    designA4: "Diseñar hoja A4", composer: "Vista previa y composición A4", back: "Volver a productos", quickPlace: "Colocación rápida",
    add: "Añadir a hoja", placed: "colocadas", pending: "pendientes", page: "Página", newPage: "Nueva página", remove: "Retirar de la hoja",
    a4Preview: "Vista previa de la hoja A4", zoom: "Zoom", zoomOut: "Reducir zoom", zoomIn: "Ampliar zoom", resetZoom: "Restablecer zoom al 100 %",
    offer: "OFERTA", promotion: "PROMO", offerPromotion: "OFERTA + PROMO", until: "hasta", from: "desde", units: "uds.", mixable: "combinable", secondUnit: "2.ª unidad", pack: "Pack", morePromotions: "promociones más",
    inspector: "Posición y tamaño", positionX: "X (mm)", positionY: "Y (mm)", noPlacement: "Selecciona una etiqueta colocada para ajustarla.",
    incomplete: "Coloca todas las copias en la hoja antes de imprimir.", occupied: "La posición está ocupada o fuera del área imprimible.",
    companyMissing: "No se pudieron obtener la razón social, el CIF y la dirección de la empresa.", sizeTooSmall: "El tamaño es demasiado pequeño para imprimir el EAN y los datos seleccionados.",
    limit: "La cantidad de etiquetas supera el límite seguro de impresión.", error: "No se pudieron preparar las etiquetas.", commercialError: "No se pudieron comprobar las ofertas y promociones vigentes.",
    saved: "Perfil guardado para este terminal", printed: "Etiquetas enviadas a la impresora", pdfSaved: "PDF de etiquetas guardado",
  },
  en: {
    title: "Print product labels", products: "Products", search: "Search product", noProducts: "No matching products",
    code: "Code", product: "Product", barcode: "EAN code", copies: "Copies", selected: "Selected", missingEan: "No valid EAN",
    profile: "Label profile", newProfile: "New profile", deleteProfile: "Delete profile", profileName: "Profile name",
    destination: "Destination", labelPrinter: "Label printer", ticketPrinter: "Ticket printer", a4: "A4 sheet",
    printer: "Printer", width: "Width (mm)", height: "Height (mm)", orientation: "Orientation", portrait: "Portrait", landscape: "Landscape",
    margins: "A4 margins (mm)", top: "Top", right: "Right", bottom: "Bottom", left: "Left", gaps: "Label gaps (mm)", horizontal: "Horizontal", vertical: "Vertical",
    defaultCopies: "Initial copies", companyData: "Show company details", saveDefault: "Save as default", print: "Print", pdf: "Save PDF", close: "Close",
    designA4: "Design A4 sheet", composer: "A4 preview and layout", back: "Back to products", quickPlace: "Quick placement",
    add: "Add to sheet", placed: "placed", pending: "pending", page: "Page", newPage: "New page", remove: "Remove from sheet",
    a4Preview: "A4 sheet preview", zoom: "Zoom", zoomOut: "Zoom out", zoomIn: "Zoom in", resetZoom: "Reset zoom to 100%",
    offer: "OFFER", promotion: "PROMO", offerPromotion: "OFFER + PROMO", until: "until", from: "from", units: "units", mixable: "mix & match", secondUnit: "2nd unit", pack: "Pack", morePromotions: "more promotions",
    inspector: "Position and size", positionX: "X (mm)", positionY: "Y (mm)", noPlacement: "Select a placed label to adjust it.",
    incomplete: "Place every copy on a sheet before printing.", occupied: "The position is occupied or outside the printable area.",
    companyMissing: "The company name, tax ID and address could not be loaded.", sizeTooSmall: "The label is too small for the EAN and selected details.",
    limit: "The label quantity exceeds the safe printing limit.", error: "The labels could not be prepared.", commercialError: "Current offers and promotions could not be verified.", saved: "Profile saved for this terminal",
    printed: "Labels sent to the printer", pdfSaved: "Label PDF saved",
  },
  zh: {
    title: "打印商品标签", products: "商品", search: "搜索商品", noProducts: "没有匹配的商品", code: "代码", product: "商品",
    barcode: "EAN 代码", copies: "份数", selected: "已选择", missingEan: "没有有效的 EAN", profile: "标签配置", newProfile: "新建配置",
    deleteProfile: "删除配置", profileName: "配置名称", destination: "输出目标", labelPrinter: "标签打印机", ticketPrinter: "小票打印机", a4: "A4 纸",
    printer: "打印机", width: "宽度（毫米）", height: "高度（毫米）", orientation: "方向", portrait: "纵向", landscape: "横向",
    margins: "A4 边距（毫米）", top: "上", right: "右", bottom: "下", left: "左", gaps: "标签间距（毫米）", horizontal: "水平", vertical: "垂直",
    defaultCopies: "初始份数", companyData: "显示公司信息", saveDefault: "保存为默认", print: "打印", pdf: "保存 PDF", close: "关闭",
    designA4: "设计 A4 页面", composer: "A4 预览和排版", back: "返回商品", quickPlace: "快速放置", add: "添加到页面",
    placed: "已放置", pending: "待放置", page: "页面", newPage: "新页面", remove: "从页面移除", inspector: "位置和尺寸",
    a4Preview: "A4 页面预览", zoom: "缩放", zoomOut: "缩小", zoomIn: "放大", resetZoom: "恢复为 100%",
    offer: "特价", promotion: "促销", offerPromotion: "特价 + 促销", until: "截至", from: "满", units: "件", mixable: "可混搭", secondUnit: "第 2 件", pack: "组合", morePromotions: "个其他促销",
    positionX: "X（毫米）", positionY: "Y（毫米）", noPlacement: "选择已放置的标签进行调整。", incomplete: "打印前请放置所有标签。",
    occupied: "该位置已被占用或超出可打印区域。", companyMissing: "无法获取公司名称、税号和地址。", sizeTooSmall: "标签尺寸太小，无法打印 EAN 和所选信息。",
    limit: "标签数量超过安全打印限制。", error: "无法准备标签。", commercialError: "无法验证当前特价和促销。", saved: "配置已保存到此终端", printed: "标签已发送到打印机", pdfSaved: "标签 PDF 已保存",
  },
} as const;

type ProductLabelCopy = { [Key in keyof typeof copy.es]: string };

const MIN_ZOOM_PERCENT = 50;
const MAX_ZOOM_PERCENT = 200;
const ZOOM_STEP_PERCENT = 10;

function clampZoomPercent(value: number) {
  return Math.min(MAX_ZOOM_PERCENT, Math.max(MIN_ZOOM_PERCENT, value));
}

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

function productBarcodes(product: LabelProduct | undefined) {
  return [product?.barcode, product?.barcode2].filter(
    (value): value is string => Boolean(value && isValidEan(value)),
  );
}

function printableAddress(issuer: ProductLabelIssuer | undefined) {
  if (!issuer) return "";
  return [
    issuer.address.line1,
    [issuer.address.postalCode, issuer.address.city].filter(Boolean).join(" "),
    issuer.address.province,
    issuer.address.country,
  ].filter((value, index, values) => Boolean(value) && values.indexOf(value) === index).join(", ");
}

function errorMessage(value: unknown, t: ProductLabelCopy) {
  const message = value instanceof Error ? value.message : "";
  if (message === "PRODUCT_LABEL_LIMIT_EXCEEDED") return t.limit;
  if (message === "PRODUCT_LABEL_SIZE_TOO_SMALL" || message === "PRODUCT_LABEL_SIZE_TOO_LARGE") return t.sizeTooSmall;
  return message || t.error;
}

function compactNumber(value: number | null | undefined, locale: LocaleCode) {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(Number(value ?? 0));
}

function commercialMoney(value: number | null | undefined, locale: LocaleCode) {
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(value ?? 0));
}

function shortCommercialDate(value: string | null | undefined, locale: LocaleCode) {
  if (!value) return "";
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) return "";
  return new Intl.DateTimeFormat(locale, { day: "2-digit", month: "2-digit" })
    .formatToParts(new Date(year, month - 1, day))
    .map((part) => part.type === "day" || part.type === "month"
      ? part.value.padStart(2, "0")
      : part.value)
    .join("");
}

function promotionBenefit(
  promotion: ProductLabelCommercialContext["promotions"][number],
  locale: LocaleCode,
) {
  if (promotion.discountPercent != null) return `-${compactNumber(promotion.discountPercent, locale)}%`;
  if (promotion.discountAmount != null) return `-${commercialMoney(promotion.discountAmount, locale)}`;
  return promotion.name;
}

function promotionLine(
  promotion: ProductLabelCommercialContext["promotions"][number],
  t: ProductLabelCopy,
  locale: LocaleCode,
) {
  let rule = promotion.name;
  if (promotion.type === "BUY_X_PAY_Y" && promotion.buyQuantity != null && promotion.payQuantity != null) {
    rule = `${compactNumber(promotion.buyQuantity, locale)}x${compactNumber(promotion.payQuantity, locale)}`;
    if (promotion.buyXPayYMode === "MIXED_TARGETS") rule += ` ${t.mixable}`;
  } else if (promotion.type === "SECOND_UNIT_PERCENT") {
    rule = `${t.secondUnit} ${promotionBenefit(promotion, locale)}`;
  } else if (promotion.type === "FIXED_PACK_PRICE" && promotion.buyQuantity != null && promotion.packPrice != null) {
    rule = `${t.pack} ${compactNumber(promotion.buyQuantity, locale)} · ${commercialMoney(promotion.packPrice, locale)}`;
  } else if (promotion.type === "QUANTITY_DISCOUNT" && promotion.minimumQuantity != null) {
    rule = `${t.from} ${compactNumber(promotion.minimumQuantity, locale)} ${t.units} · ${promotionBenefit(promotion, locale)}`;
  } else if (promotion.type === "PURCHASE_THRESHOLD_DISCOUNT" && promotion.minimumAmount != null) {
    rule = `${t.from} ${commercialMoney(promotion.minimumAmount, locale)} · ${promotionBenefit(promotion, locale)}`;
  }
  const endDate = shortCommercialDate(promotion.endDate, locale);
  return endDate ? `${rule} · ${t.until} ${endDate}` : rule;
}

function commercialDisplay(
  context: ProductLabelCommercialContext | undefined,
  t: ProductLabelCopy,
  locale: LocaleCode,
): ProductLabelCommercial | undefined {
  if (!context || (!context.offer && context.promotions.length === 0)) return undefined;
  const hasOffer = Boolean(context.offer);
  const hasPromotions = context.promotions.length > 0;
  const promotionLines = context.promotions.map((promotion) => promotionLine(promotion, t, locale));
  const printablePromotionLines = promotionLines.length <= 2
    ? promotionLines
    : [promotionLines[0], `+${promotionLines.length - 1} ${t.morePromotions}`];
  return {
    badge: hasOffer && hasPromotions ? t.offerPromotion : hasOffer ? t.offer : t.promotion,
    ...(context.offer ? {
      offer: {
        regularPrice: Number(context.offer.regularPrice),
        offerPrice: Number(context.offer.offerPrice),
        discountPercent: Number(context.offer.discountPercent),
        ...(context.offer.endDate ? {
          validUntil: `${t.until} ${shortCommercialDate(context.offer.endDate, locale)}`,
        } : {}),
      },
    } : {}),
    promotionLines: printablePromotionLines,
  };
}

function ProductLabelBarcodePreview({ code }: { code: string }) {
  const bits = productLabelEanBits(code);
  return <svg className="barcode-preview" viewBox={`-9 0 ${bits.length + 18} 38`} preserveAspectRatio="none" aria-label={code}>
    {bits.split("").map((value, index) => value === "1"
      ? <rect key={index} x={index} y="0" width="1" height="38" />
      : null)}
  </svg>;
}

export function SaleProductLabelDialog({
  open,
  locale,
  storeName,
  issuer,
  issuerError,
  products,
  loadCommercialContexts,
  initialProductId = "",
  onClose,
  onPrinted,
}: Props) {
  const t = copy[locale];
  const bridge = getHardwareBridge();
  const dialogRef = useRef<HTMLElement | null>(null);
  const pointerRef = useRef<PointerInteraction | null>(null);
  const [config, setConfig] = useState<HardwareConfig>(() => normalizeHardwareConfigForUi());
  const [printers, setPrinters] = useState<HardwarePrinter[]>([]);
  const [profileId, setProfileId] = useState(defaultHardwareConfig.defaultProductLabelProfileId);
  const [selected, setSelected] = useState<SelectedProduct[]>([]);
  const [query, setQuery] = useState("");
  const [view, setView] = useState<View>("SELECTION");
  const [pages, setPages] = useState<ProductLabelPage[]>([{ placements: [] }]);
  const [pageIndex, setPageIndex] = useState(0);
  const [selectedPlacementId, setSelectedPlacementId] = useState("");
  const [zoomPercent, setZoomPercent] = useState(100);
  const [commercialContexts, setCommercialContexts] = useState<Record<string, ProductLabelCommercialContext>>({});
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    let active = true;
    setQuery(""); setView("SELECTION"); setPages([{ placements: [] }]); setPageIndex(0); setZoomPercent(100); setCommercialContexts({});
    setSelectedPlacementId(""); setStatus(""); setError("");
    const initialProduct = products.find((candidate) => candidate.id === initialProductId);
    const initialBarcode = productBarcodes(initialProduct)[0];
    setSelected(initialProduct && initialBarcode ? [{ productId: initialProduct.id, barcode: initialBarcode, copies: 1 }] : []);
    void Promise.all([bridge.getHardwareConfig(), bridge.listPrinters()]).then(([loaded, detected]) => {
      if (!active) return;
      const normalized = normalizeHardwareConfigForUi(loaded);
      setConfig(normalized);
      setProfileId(normalized.defaultProductLabelProfileId);
      setPrinters(detected.ok ? detected.printers : []);
    }).catch(() => setError(t.error));
    return () => { active = false; };
  }, [initialProductId, open, products]);

  useEffect(() => {
    if (!open || !dialogRef.current) return;
    return activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document);
  }, [open, view]);

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

  const profile = config.productLabelProfiles.find((candidate) => candidate.id === profileId)
    ?? config.productLabelProfiles[0];
  const filteredProducts = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return products.filter((product) => !normalized || [product.code, product.barcode, product.barcode2, product.name]
      .some((value) => value?.toLocaleLowerCase().includes(normalized))).slice(0, 100);
  }, [products, query]);
  const selectedItems = useMemo((): ProductLabelItem[] => selected.flatMap((entry) => {
    const product = products.find((candidate) => candidate.id === entry.productId);
    if (!product || !isValidEan(entry.barcode)) return [];
    return [{
      id: product.id,
      product: {
        name: product.name ?? "",
        code: product.code ?? "",
        barcode: entry.barcode,
        price: Number(product.salePrice ?? 0),
        commercial: commercialDisplay(commercialContexts[product.id], t, locale),
      },
      copies: entry.copies,
    }];
  }), [commercialContexts, locale, products, selected, t]);
  const placementCounts = useMemo(() => productLabelPlacementCounts(pages), [pages]);
  const currentPage = pages[pageIndex] ?? pages[0];
  const selectedPlacement = currentPage?.placements.find((placement) => placement.instanceId === selectedPlacementId);

  if (!open || !profile) return null;

  const pageSize = productLabelPageSize(profile);
  const safeArea = productLabelSafeArea(profile);
  const minimumSize = productLabelMinimumSize(profile.showStoreName);
  const companyReady = !profile.showStoreName || Boolean(
    issuer?.name && issuer.taxId && printableAddress(issuer),
  );
  const selectionReady = selectedItems.length > 0 && selectedItems.length === selected.length && companyReady;
  const compositionReady = profile.destination === "A4"
    && validateProductLabelComposition(selectedItems, pages, profile);

  function resetComposition() {
    setPages([{ placements: [] }]);
    setPageIndex(0);
    setSelectedPlacementId("");
  }

  async function refreshCommercialContexts() {
    if (!loadCommercialContexts) return commercialContexts;
    const productIds = selectedItems.map((item) => item.id);
    const resolved = await loadCommercialContexts(productIds);
    const next = Object.fromEntries(resolved.map((context) => [context.productId, context]));
    if (productIds.some((productId) => !next[productId])) {
      throw new Error(t.commercialError);
    }
    setCommercialContexts(next);
    return next;
  }

  function itemsWithCommercial(contexts: Record<string, ProductLabelCommercialContext>) {
    return selectedItems.map((item) => ({
      ...item,
      product: {
        ...item.product,
        commercial: commercialDisplay(contexts[item.id], t, locale),
      },
    }));
  }

  function updateProfile(patch: Partial<ProductLabelProfile>) {
    setConfig((current) => ({
      ...current,
      productLabelProfiles: current.productLabelProfiles.map((candidate) =>
        candidate.id === profile.id ? { ...candidate, ...patch } : candidate),
    }));
    resetComposition();
  }

  function toggleProduct(product: LabelProduct, checked: boolean) {
    setError(""); setStatus(""); setCommercialContexts({}); resetComposition();
    setSelected((current) => {
      if (!checked) return current.filter((entry) => entry.productId !== product.id);
      if (current.some((entry) => entry.productId === product.id)) return current;
      const barcode = productBarcodes(product)[0];
      return barcode ? [...current, { productId: product.id, barcode, copies: profile.copies }] : current;
    });
  }

  function updateSelected(productId: string, patch: Partial<SelectedProduct>) {
    setCommercialContexts({}); resetComposition();
    setSelected((current) => current.map((entry) => entry.productId === productId
      ? { ...entry, ...patch }
      : entry));
  }

  async function persistProfile() {
    const next = { ...config, defaultProductLabelProfileId: profile.id };
    const result = await bridge.saveHardwareConfig(next);
    if (!result.ok) throw new Error(result.message);
    setConfig(next);
    setStatus(t.saved);
    return next;
  }

  function request(contexts = commercialContexts): ProductLabelPrintRequest {
    if (!selectionReady) throw new Error(companyReady ? t.error : t.companyMissing);
    if (profile.widthMm < minimumSize.widthMm || profile.heightMm < minimumSize.heightMm) {
      throw new Error(t.sizeTooSmall);
    }
    if (profile.destination === "A4" && !compositionReady) throw new Error(t.incomplete);
    return {
      version: 2,
      kind: profile.destination === "A4" ? "A4_LAYOUT" : "SEQUENTIAL",
      storeName,
      issuer,
      profile,
      items: itemsWithCommercial(contexts),
      ...(profile.destination === "A4" ? { pages } : {}),
    };
  }

  async function output(pdf: boolean) {
    if (busy) return;
    setBusy(true); setError(""); setStatus("");
    try {
      const contexts = await refreshCommercialContexts();
      const next = await persistProfile();
      const payload = request(contexts);
      if (pdf) {
        const result = await bridge.exportProductLabelPdf(payload, `etiquetas-${selectedItems.length}.pdf`);
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
      setError(errorMessage(caught, t));
    } finally {
      setBusy(false);
    }
  }

  async function enterComposer() {
    if (busy) return;
    setError(""); setStatus("");
    if (!selectionReady) {
      setError(companyReady ? t.error : (issuerError || t.companyMissing));
      return;
    }
    if (profile.widthMm < minimumSize.widthMm || profile.heightMm < minimumSize.heightMm) {
      setError(t.sizeTooSmall);
      return;
    }
    setBusy(true);
    try {
      await refreshCommercialContexts();
      setView("A4_COMPOSER");
    } catch {
      setError(t.commercialError);
    } finally {
      setBusy(false);
    }
  }

  function quickPlace(items = selectedItems) {
    try {
      const next = quickPlaceProductLabels(items, pages, profile);
      setPages(next);
      setPageIndex(Math.min(pageIndex, next.length - 1));
      setError("");
    } catch (caught) {
      setError(errorMessage(caught, t));
    }
  }

  function addOne(itemId: string) {
    const placed = placementCounts.get(itemId) ?? 0;
    const item = selectedItems.find((candidate) => candidate.id === itemId);
    if (!item || placed >= item.copies) return;
    const partial = selectedItems.map((candidate) => ({
      ...candidate,
      copies: candidate.id === itemId ? placed + 1 : (placementCounts.get(candidate.id) ?? 0),
    }));
    quickPlace(partial);
  }

  function removePlacement(targetPageIndex: number, instanceId: string) {
    setPages((current) => current.map((page, index) => index === targetPageIndex
      ? { placements: page.placements.filter((placement) => placement.instanceId !== instanceId) }
      : page));
    setSelectedPlacementId("");
  }

  function updatePlacement(targetPageIndex: number, instanceId: string, patch: Partial<ProductLabelPlacement>) {
    setPages((current) => {
      const page = current[targetPageIndex];
      const existing = page?.placements.find((placement) => placement.instanceId === instanceId);
      if (!page || !existing) return current;
      const candidate = clampProductLabelPlacement({ ...existing, ...patch }, profile);
      if (!canPlaceProductLabel(candidate, page, profile, instanceId)) {
        setError(t.occupied);
        return current;
      }
      setError("");
      return current.map((value, index) => index === targetPageIndex ? {
        placements: value.placements.map((placement) => placement.instanceId === instanceId ? candidate : placement),
      } : value);
    });
  }

  function dragPayload(event: DragEvent, itemId: string) {
    event.dataTransfer.setData("application/x-tpverp-product-label", JSON.stringify({ itemId }));
    event.dataTransfer.effectAllowed = "copy";
  }

  function dropOnPage(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    let payload: { itemId?: string } = {};
    try {
      payload = JSON.parse(event.dataTransfer.getData("application/x-tpverp-product-label"));
    } catch {
      return;
    }
    const item = selectedItems.find((candidate) => candidate.id === payload.itemId);
    if (!item || (placementCounts.get(item.id) ?? 0) >= item.copies) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const instanceId = `${item.id}::${globalThis.crypto?.randomUUID?.() ?? Date.now()}`;
    const placement = clampProductLabelPlacement({
      instanceId,
      itemId: item.id,
      xMm: ((event.clientX - rect.left) / rect.width) * pageSize.widthMm - profile.widthMm / 2,
      yMm: ((event.clientY - rect.top) / rect.height) * pageSize.heightMm - profile.heightMm / 2,
      widthMm: profile.widthMm,
      heightMm: profile.heightMm,
    }, profile);
    if (!canPlaceProductLabel(placement, currentPage, profile)) {
      setError(t.occupied);
      return;
    }
    setPages((current) => current.map((page, index) => index === pageIndex
      ? { placements: [...page.placements, placement] }
      : page));
    setSelectedPlacementId(instanceId);
    setError("");
  }

  function startPointer(
    event: ReactPointerEvent<HTMLElement>,
    placement: ProductLabelPlacement,
    mode: PointerInteraction["mode"],
  ) {
    const page = event.currentTarget.closest(".sale-product-label-a4-page");
    if (!(page instanceof HTMLElement)) return;
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    const rect = page.getBoundingClientRect();
    pointerRef.current = {
      pointerId: event.pointerId,
      pageIndex,
      instanceId: placement.instanceId,
      mode,
      startClientX: event.clientX,
      startClientY: event.clientY,
      pageWidthPx: rect.width,
      pageHeightPx: rect.height,
      placement,
    };
    setSelectedPlacementId(placement.instanceId);
  }

  function movePointer(event: ReactPointerEvent<HTMLElement>) {
    const interaction = pointerRef.current;
    if (!interaction || interaction.pointerId !== event.pointerId) return;
    const deltaX = ((event.clientX - interaction.startClientX) / interaction.pageWidthPx) * pageSize.widthMm;
    const deltaY = ((event.clientY - interaction.startClientY) / interaction.pageHeightPx) * pageSize.heightMm;
    updatePlacement(interaction.pageIndex, interaction.instanceId, interaction.mode === "MOVE"
      ? { xMm: interaction.placement.xMm + deltaX, yMm: interaction.placement.yMm + deltaY }
      : { widthMm: interaction.placement.widthMm + deltaX, heightMm: interaction.placement.heightMm + deltaY });
  }

  function finishPointer(event: ReactPointerEvent<HTMLElement>) {
    if (pointerRef.current?.pointerId === event.pointerId) pointerRef.current = null;
  }

  function placementKeyDown(event: React.KeyboardEvent, placement: ProductLabelPlacement) {
    const delta = event.shiftKey ? 5 : 1;
    const movement: Record<string, Partial<ProductLabelPlacement>> = {
      ArrowLeft: { xMm: placement.xMm - delta }, ArrowRight: { xMm: placement.xMm + delta },
      ArrowUp: { yMm: placement.yMm - delta }, ArrowDown: { yMm: placement.yMm + delta },
    };
    if (movement[event.key]) {
      event.preventDefault();
      updatePlacement(pageIndex, placement.instanceId, movement[event.key]);
    } else if (event.key === "Delete") {
      event.preventDefault();
      removePlacement(pageIndex, placement.instanceId);
    }
  }

  function zoomWithWheel(event: React.WheelEvent<HTMLDivElement>) {
    if (event.deltaY === 0) return;
    event.preventDefault();
    setZoomPercent((current) => clampZoomPercent(
      current + (event.deltaY < 0 ? ZOOM_STEP_PERCENT : -ZOOM_STEP_PERCENT),
    ));
  }

  const footerMessage = error || status || (!companyReady && profile.showStoreName ? (issuerError || t.companyMissing) : "");

  return <div className="sale-utility-backdrop" role="presentation">
    <section ref={dialogRef} className={`sale-utility-dialog sale-product-label-dialog ${view === "A4_COMPOSER" ? "composer" : ""}`} role="dialog" aria-modal="true" aria-labelledby="product-label-title">
      <header><h2 id="product-label-title">{view === "A4_COMPOSER" ? t.composer : t.title}</h2></header>
      {view === "SELECTION" ? <div className="sale-product-label-body">
        <section className="sale-product-label-products">
          <div className="sale-product-label-section-heading"><h3>{t.products}</h3><strong>{t.selected}: {selected.length}</strong></div>
          <label><span>{t.search}</span><input autoFocus value={query} onChange={(event) => setQuery(event.currentTarget.value)} /></label>
          <div className="sale-product-label-table-wrap">
            <table className="sale-product-label-table">
              <thead><tr><th aria-label={t.selected}></th><th>{t.code}</th><th>{t.product}</th><th>{t.barcode}</th><th>{t.copies}</th></tr></thead>
              <tbody>{filteredProducts.length === 0 && <tr><td colSpan={5}>{t.noProducts}</td></tr>}{filteredProducts.map((product) => {
                const entry = selected.find((candidate) => candidate.productId === product.id);
                const barcodes = productBarcodes(product);
                return <tr key={product.id} className={entry ? "selected" : ""}>
                  <td><input type="checkbox" aria-label={`${t.selected}: ${product.code || product.name || product.id}`} checked={Boolean(entry)} disabled={barcodes.length === 0} onChange={(event) => toggleProduct(product, event.currentTarget.checked)} /></td>
                  <td>{product.code || "—"}</td><td>{product.name || "—"}</td>
                  <td>{entry ? <select aria-label={`${t.barcode}: ${product.code || product.id}`} value={entry.barcode} onChange={(event) => updateSelected(product.id, { barcode: event.currentTarget.value })}>{barcodes.map((barcode) => <option key={barcode}>{barcode}</option>)}</select> : (barcodes[0] ?? <span className="sale-dialog-error">{t.missingEan}</span>)}</td>
                  <td>{entry ? <input aria-label={`${t.copies}: ${product.code || product.id}`} type="number" min="1" max="999" value={entry.copies} onChange={(event) => updateSelected(product.id, { copies: Math.round(numeric(event.currentTarget.value, 1, 1, 999)) })} /> : "—"}</td>
                </tr>;
              })}</tbody>
            </table>
          </div>
        </section>
        <section className="sale-product-label-profile">
          <h3>{t.profile}</h3>
          <div className="sale-product-label-profile-row"><select value={profileId} onChange={(event) => { setProfileId(event.currentTarget.value); resetComposition(); }}>{config.productLabelProfiles.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.name}</option>)}</select><button type="button" onClick={() => { const next = freshProfile(); setConfig((current) => ({ ...current, productLabelProfiles: [...current.productLabelProfiles, next] })); setProfileId(next.id); resetComposition(); }}>{t.newProfile}</button><button type="button" disabled={config.productLabelProfiles.length <= 1} onClick={() => { const remaining = config.productLabelProfiles.filter((candidate) => candidate.id !== profile.id); setConfig((current) => ({ ...current, productLabelProfiles: remaining })); setProfileId(remaining[0]?.id ?? ""); resetComposition(); }}>{t.deleteProfile}</button></div>
          <label><span>{t.profileName}</span><input value={profile.name} onChange={(event) => updateProfile({ name: event.currentTarget.value })} /></label>
          <label><span>{t.destination}</span><select value={profile.destination} onChange={(event) => updateProfile({ destination: event.currentTarget.value as ProductLabelDestination, printerName: "" })}><option value="LABEL_PRINTER">{t.labelPrinter}</option><option value="TICKET_PRINTER">{t.ticketPrinter}</option><option value="A4">{t.a4}</option></select></label>
          <label><span>{t.printer}</span><select value={profile.printerName} onChange={(event) => updateProfile({ printerName: event.currentTarget.value })}><option value="">{profile.destination === "TICKET_PRINTER" ? config.ticketPrinterName || "—" : profile.destination === "A4" ? config.a4PrinterName || "—" : "—"}</option>{printers.map((printer) => <option key={printer.name} value={printer.name}>{printer.displayName}</option>)}</select></label>
          <div className="sale-product-label-measures"><label><span>{t.width}</span><input type="number" min={minimumSize.widthMm} max="210" step="1" value={profile.widthMm} onChange={(event) => updateProfile({ widthMm: numeric(event.currentTarget.value, 58, minimumSize.widthMm, 210) })} /></label><label><span>{t.height}</span><input type="number" min={minimumSize.heightMm} max="297" step="1" value={profile.heightMm} onChange={(event) => updateProfile({ heightMm: numeric(event.currentTarget.value, 40, minimumSize.heightMm, 297) })} /></label><label><span>{t.defaultCopies}</span><input type="number" min="1" max="999" value={profile.copies} onChange={(event) => updateProfile({ copies: Math.round(numeric(event.currentTarget.value, 1, 1, 999)) })} /></label></div>
          <label><span>{t.orientation}</span><select value={profile.orientation} onChange={(event) => updateProfile({ orientation: event.currentTarget.value as "PORTRAIT" | "LANDSCAPE" })}><option value="PORTRAIT">{t.portrait}</option><option value="LANDSCAPE">{t.landscape}</option></select></label>
          <label className="sale-product-label-checkbox"><input type="checkbox" checked={profile.showStoreName} onChange={(event) => updateProfile({ showStoreName: event.currentTarget.checked })} /><span>{t.companyData}</span></label>
          {profile.destination === "A4" && <><fieldset><legend>{t.margins}</legend><div className="sale-product-label-four"><label><span>{t.top}</span><input type="number" min="0" max="50" value={profile.marginTopMm} onChange={(event) => updateProfile({ marginTopMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label><label><span>{t.right}</span><input type="number" min="0" max="50" value={profile.marginRightMm} onChange={(event) => updateProfile({ marginRightMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label><label><span>{t.bottom}</span><input type="number" min="0" max="50" value={profile.marginBottomMm} onChange={(event) => updateProfile({ marginBottomMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label><label><span>{t.left}</span><input type="number" min="0" max="50" value={profile.marginLeftMm} onChange={(event) => updateProfile({ marginLeftMm: numeric(event.currentTarget.value, 5, 0, 50) })} /></label></div></fieldset><fieldset><legend>{t.gaps}</legend><div className="sale-product-label-measures"><label><span>{t.horizontal}</span><input type="number" min="0" max="25" value={profile.horizontalGapMm} onChange={(event) => updateProfile({ horizontalGapMm: numeric(event.currentTarget.value, 2, 0, 25) })} /></label><label><span>{t.vertical}</span><input type="number" min="0" max="25" value={profile.verticalGapMm} onChange={(event) => updateProfile({ verticalGapMm: numeric(event.currentTarget.value, 2, 0, 25) })} /></label></div></fieldset></>}
        </section>
      </div> : <div className="sale-product-label-composer-body">
        <aside className="sale-product-label-composer-list"><h3>{t.selected}: {selectedItems.length}</h3>{selectedItems.map((item) => {
          const placed = placementCounts.get(item.id) ?? 0;
          const pending = Math.max(0, item.copies - placed);
          return <article key={item.id} draggable={pending > 0} onDragStart={(event) => dragPayload(event, item.id)}>
            <strong>{item.product.code || item.product.name}</strong>{item.product.commercial && <em className="sale-product-label-commercial-chip">{item.product.commercial.badge}</em>}<span>{item.product.barcode}</span><small>{placed} {t.placed} · {pending} {t.pending}</small>
            <button type="button" disabled={pending === 0} onClick={() => addOne(item.id)}>{t.add}</button>
          </article>;
        })}</aside>
        <main className="sale-product-label-a4-workspace">
          <div className="sale-product-label-a4-toolbar"><button type="button" onClick={() => quickPlace()}>{t.quickPlace}</button><span>{t.page} {pageIndex + 1} / {pages.length}</span></div>
          <div className="sale-product-label-page-tabs">{pages.map((_, index) => <button type="button" key={index} className={index === pageIndex ? "active" : ""} onClick={() => { setPageIndex(index); setSelectedPlacementId(""); }}>{index + 1}</button>)}</div>
          <div className="sale-product-label-a4-stage" aria-label={t.a4Preview} onWheel={zoomWithWheel}>
            <div className="sale-product-label-a4-zoom-surface" style={{ aspectRatio: `${pageSize.widthMm} / ${pageSize.heightMm}`, zoom: zoomPercent / 100 }}>
              <div className="sale-product-label-a4-page" onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = "copy"; }} onDrop={dropOnPage}>
                <div className="sale-product-label-safe-area" style={{ left: `${safeArea.leftMm / pageSize.widthMm * 100}%`, top: `${safeArea.topMm / pageSize.heightMm * 100}%`, right: `${(pageSize.widthMm - safeArea.rightMm) / pageSize.widthMm * 100}%`, bottom: `${(pageSize.heightMm - safeArea.bottomMm) / pageSize.heightMm * 100}%` }} />
                {currentPage.placements.map((placement) => {
                  const item = selectedItems.find((candidate) => candidate.id === placement.itemId);
                  if (!item) return null;
                  const commercial = item.product.commercial;
                  return <article key={placement.instanceId} role="button" tabIndex={0} aria-label={`${item.product.code} ${item.product.name}`} className={`sale-product-label-preview ${profile.showStoreName && issuer ? "with-company" : ""} ${commercial?.promotionLines.length ? "with-promotions" : ""} ${selectedPlacementId === placement.instanceId ? "active" : ""}`} style={{ left: `${placement.xMm / pageSize.widthMm * 100}%`, top: `${placement.yMm / pageSize.heightMm * 100}%`, width: `${placement.widthMm / pageSize.widthMm * 100}%`, height: `${placement.heightMm / pageSize.heightMm * 100}%` }} onClick={() => setSelectedPlacementId(placement.instanceId)} onPointerDown={(event) => startPointer(event, placement, "MOVE")} onPointerMove={movePointer} onPointerUp={finishPointer} onPointerCancel={finishPointer} onKeyDown={(event) => placementKeyDown(event, placement)}>
                    {profile.showStoreName && issuer && <div className="company"><b>{issuer.name}</b><span>CIF: {issuer.taxId}</span><span>{printableAddress(issuer)}</span></div>}
                    <b className="name">{item.product.name}</b>
                    <div className="label-content"><span className="code">{item.product.code}</span><div className="barcode-block"><ProductLabelBarcodePreview code={item.product.barcode} /><span className="ean">{item.product.barcode}</span></div></div>
                    <div className={`price ${commercial?.offer ? "offer-price" : ""}`}>{commercial && <span className="commercial-badge">{commercial.badge}</span>}{commercial?.offer ? <><del>{commercialMoney(commercial.offer.regularPrice, locale)}</del><strong>{commercialMoney(commercial.offer.offerPrice, locale)}</strong><small>-{compactNumber(commercial.offer.discountPercent, locale)}%{commercial.offer.validUntil ? ` · ${commercial.offer.validUntil}` : ""}</small></> : <strong>{commercialMoney(item.product.price, locale)}</strong>}</div>
                    {commercial?.promotionLines.length ? <div className="promotion-summary">{commercial.promotionLines.map((line, index) => <span key={`${line}-${index}`}>{line}</span>)}</div> : null}
                    <button type="button" className="resize-handle" aria-label={`${t.width} / ${t.height}`} onPointerDown={(event) => startPointer(event, placement, "RESIZE")} onPointerMove={movePointer} onPointerUp={finishPointer} onPointerCancel={finishPointer}>↘</button>
                  </article>;
                })}
              </div>
            </div>
          </div>
          <div className="sale-product-label-zoom-controls" role="group" aria-label={t.zoom}>
            <button type="button" aria-label={t.zoomOut} disabled={zoomPercent <= MIN_ZOOM_PERCENT} onClick={() => setZoomPercent((current) => clampZoomPercent(current - ZOOM_STEP_PERCENT))}>−</button>
            <input aria-label={t.zoom} type="range" min={MIN_ZOOM_PERCENT} max={MAX_ZOOM_PERCENT} step={ZOOM_STEP_PERCENT} value={zoomPercent} onChange={(event) => setZoomPercent(clampZoomPercent(Number(event.currentTarget.value)))} />
            <output>{zoomPercent}%</output>
            <button type="button" aria-label={t.zoomIn} disabled={zoomPercent >= MAX_ZOOM_PERCENT} onClick={() => setZoomPercent((current) => clampZoomPercent(current + ZOOM_STEP_PERCENT))}>+</button>
            <button type="button" aria-label={t.resetZoom} disabled={zoomPercent === 100} onClick={() => setZoomPercent(100)}>100%</button>
          </div>
        </main>
        <aside className="sale-product-label-inspector"><h3>{t.inspector}</h3>{selectedPlacement ? <>
          <label><span>{t.positionX}</span><input type="number" step="1" value={selectedPlacement.xMm} onChange={(event) => updatePlacement(pageIndex, selectedPlacement.instanceId, { xMm: numeric(event.currentTarget.value, selectedPlacement.xMm, safeArea.leftMm, safeArea.rightMm - selectedPlacement.widthMm) })} /></label>
          <label><span>{t.positionY}</span><input type="number" step="1" value={selectedPlacement.yMm} onChange={(event) => updatePlacement(pageIndex, selectedPlacement.instanceId, { yMm: numeric(event.currentTarget.value, selectedPlacement.yMm, safeArea.topMm, safeArea.bottomMm - selectedPlacement.heightMm) })} /></label>
          <label><span>{t.width}</span><input type="number" min={minimumSize.widthMm} step="1" value={selectedPlacement.widthMm} onChange={(event) => updatePlacement(pageIndex, selectedPlacement.instanceId, { widthMm: numeric(event.currentTarget.value, selectedPlacement.widthMm, minimumSize.widthMm, safeArea.rightMm - selectedPlacement.xMm) })} /></label>
          <label><span>{t.height}</span><input type="number" min={minimumSize.heightMm} step="1" value={selectedPlacement.heightMm} onChange={(event) => updatePlacement(pageIndex, selectedPlacement.instanceId, { heightMm: numeric(event.currentTarget.value, selectedPlacement.heightMm, minimumSize.heightMm, safeArea.bottomMm - selectedPlacement.yMm) })} /></label>
          <button type="button" onClick={() => removePlacement(pageIndex, selectedPlacement.instanceId)}>{t.remove}</button>
        </> : <p>{t.noPlacement}</p>}</aside>
      </div>}
      {footerMessage && <p className={error || (!companyReady && profile.showStoreName) ? "sale-dialog-error sale-product-label-message" : "sale-dialog-success sale-product-label-message"} role={error ? "alert" : "status"}>{footerMessage}</p>}
      <footer>{view === "A4_COMPOSER" ? <>
        <button type="button" disabled={busy} onClick={() => { setView("SELECTION"); setError(""); setStatus(""); }}>{t.back}</button>
        <button type="button" disabled={busy} onClick={onClose}>{t.close}</button>
        <button type="button" disabled={busy || !compositionReady} onClick={() => void output(true)}>{t.pdf}</button>
        <button type="button" disabled={busy || !compositionReady} onClick={() => void output(false)}>{t.print}</button>
      </> : <>
        <button type="button" disabled={busy} onClick={onClose}>{t.close}</button>
        <button type="button" disabled={busy} onClick={() => void persistProfile().catch((caught) => setError(errorMessage(caught, t)))}>{t.saveDefault}</button>
        {profile.destination === "A4" ? <button type="button" disabled={busy || !selectionReady} onClick={() => void enterComposer()}>{t.designA4}</button> : <><button type="button" disabled={busy || !selectionReady} onClick={() => void output(true)}>{t.pdf}</button><button type="button" disabled={busy || !selectionReady} onClick={() => void output(false)}>{t.print}</button></>}
      </>}</footer>
    </section>
  </div>;
}

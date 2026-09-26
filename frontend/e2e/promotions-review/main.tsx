import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AppFrame,
  PromotionListScreen,
  createTranslator,
  type LocaleCode,
  type UserSession
} from "@tpverp/app-common";
import type { GestionNavigationItem } from "../../apps/app-gestion/src/GestionShell";
import { GestionShell } from "../../apps/app-gestion/src/GestionShell";
import { gestionNavigationGroups } from "../../apps/app-gestion/src/gestionNavigation";
import { StockScreen } from "../../packages/app-common/src/components/StockScreen";
import type { PromotionView } from "../../packages/app-common/src/components/PromotionWizard";
import "../../packages/app-common/src/styles/tpv.css";
import "../../apps/app-gestion/src/gestion.css";

const parameters = new URLSearchParams(location.search);
const requestedApp = parameters.get("app");
const stockReviewApp = requestedApp === "gestion" || requestedApp === "venta" ? requestedApp : null;
const stockReview = parameters.get("view") === "stock" && stockReviewApp !== null;
const supportedLocales: LocaleCode[] = ["es", "en", "zh"];
const requestedLocale = parameters.get("locale") as LocaleCode | null;
const initialLocale: LocaleCode = requestedLocale && supportedLocales.includes(requestedLocale) ? requestedLocale : "es";
const session: UserSession = {
  username: stockReview ? "PROMOTIONS-REVIEW" : "ADMIN",
  displayName: "ADMIN · revisión aislada",
  accessToken: "fixture-only",
  permissions: ["ADMIN", "APP_GESTION_ACCESS", "GESTION_PRODUCTO"]
};
const terminalContext = { storeName: "DATOS FICTICIOS · SIN CONEXIÓN A BD", terminalCode: "DEMO", terminalId: "demo-promotions" };

const products = [
  { id: "yogur-natural-125", code: "10023", barcode: "8410000010023", name: "Yogur natural 125 g", salePrice: 0.85, active: true, familyId: "yogures" },
  { id: "yogur-natural-500", code: "10024", barcode: "8410000010024", name: "Yogur natural 500 g", salePrice: 1.6, active: true, familyId: "yogures" },
  { id: "yogur-desnatado-125", code: "10025", barcode: "8410000010025", name: "Yogur natural desnatado 125 g", salePrice: 0.85, active: true, familyId: "yogures" },
  { id: "yogur-desnatado-500", code: "10026", barcode: "8410000010026", name: "Yogur natural desnatado 500 g", salePrice: 1.55, active: true, familyId: "yogures" },
  { id: "pan-molde", code: "20010", barcode: "8410000020010", name: "Pan de molde", salePrice: 1.85, active: true, familyId: "panaderia" },
  { id: "fruta-temporada", code: "30015", barcode: "8410000030015", name: "Fruta de temporada", salePrice: 2.4, active: true, familyId: "fruta" },
  { id: "helado-chocolate", code: "40021", barcode: "8410000040021", name: "Helado de chocolate 500 ml", salePrice: 3.25, active: true, familyId: "helados" }
];

const warehouseId = "6b2a4c81-8e97-4a43-9d5f-0c7f35a12e64";
const stockWarehouses = [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
const stockPageOneProducts = products.slice(0, 6);

function promo(id: string, name: string, type: PromotionView["type"], startDate: string, endDate: string | null, extra: Partial<PromotionView> = {}): PromotionView {
  return {
    id, name, type, status: "ACTIVE", startDate, endDate, scope: "PRODUCT_LIST", customerSegment: "ALL",
    memberCategoryId: null, minimumAmount: null, minimumQuantity: null, buyQuantity: null, payQuantity: null,
    buyXPayYMode: null, discountAmount: null, discountPercent: null, maximumDiscount: null, packPrice: null,
    used: false, usageCount: 0, targets: [], ...extra
  };
}

const yogurtTargets = products.slice(0, 4).map(product => ({ type: "PRODUCT" as const, targetId: product.id }));
let promotions: PromotionView[] = [
  promo("promo-yogures-2x1", "2×1 yogures naturales", "BUY_X_PAY_Y", "2026-09-01", "2026-10-31", {
    usageCount: 327, used: true, minimumQuantity: 2, buyQuantity: 2, payQuantity: 1, buyXPayYMode: "SAME_PRODUCT", targets: yogurtTargets
  }),
  promo("promo-frutas", "-20% en frutas de temporada", "QUANTITY_DISCOUNT", "2026-09-15", "2026-11-15", {
    minimumQuantity: 1, discountPercent: 20, targets: [{ type: "PRODUCT", targetId: "fruta-temporada" }]
  }),
  promo("promo-pan-3x2", "3×2 en pan de molde", "BUY_X_PAY_Y", "2026-09-01", "2026-09-30", {
    minimumQuantity: 3, buyQuantity: 3, payQuantity: 2, buyXPayYMode: "SAME_PRODUCT", targets: [{ type: "PRODUCT", targetId: "pan-molde" }]
  }),
  promo("promo-segunda-unidad", "Descuento 10% en segunda unidad", "SECOND_UNIT_PERCENT", "2026-09-20", "2026-10-31", {
    discountPercent: 10, targets: yogurtTargets
  }),
  promo("promo-helados", "-15% en helados", "QUANTITY_DISCOUNT", "2026-08-01", "2026-08-31", {
    status: "INACTIVE", discountPercent: 15
  }),
  promo("promo-isotonicas", "2×1 bebidas isotónicas", "BUY_X_PAY_Y", "2026-08-01", "2026-08-31", {
    status: "INACTIVE", buyQuantity: 2, payQuantity: 1, buyXPayYMode: "SAME_PRODUCT"
  }),
  promo("promo-solares", "-25% en productos solares", "QUANTITY_DISCOUNT", "2026-08-10", "2026-08-25", {
    status: "INACTIVE", discountPercent: 25
  })
];
const expiredIceCreamPromotion = promo("promo-helados", "-15% en helados", "QUANTITY_DISCOUNT", "2026-08-01", "2026-08-31", {
  status: "INACTIVE", discountPercent: 15, targets: [{ type: "PRODUCT", targetId: "helado-chocolate" }]
});
const stockPromotions = promotions.map(item => item.id === "promo-helados" ? expiredIceCreamPromotion : item);

const families = [
  { id: "yogures", name: "Yogures" }, { id: "panaderia", name: "Panadería" }, { id: "fruta", name: "Fruta" },
  { id: "helados", name: "Helados" }
];
const subfamilies = [
  { id: "yogur-natural", familyId: "yogures", name: "Yogur natural" },
  { id: "pan-molde-subfamily", familyId: "panaderia", name: "Pan de molde" }
];
const memberCategories = [{ id: "oro", code: "ORO", name: "Socio Oro", active: true }];

// Intercept every fetch. Unknown routes and any external host reject locally.
window.fetch = async (input, init) => {
  const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href);
  if (url.origin !== location.origin) throw new TypeError(`Fetch externo bloqueado en fixture: ${url.origin}`);
  const path = url.pathname.replace(/^\/api\/v1/, "").replace(/\/$/, "") || "/";
  const method = (init?.method || (input instanceof Request ? input.method : "GET")).toUpperCase();
  const body = init?.body ? JSON.parse(String(init.body)) : {};
  let result: unknown;
  let status = 200;

  if (path === "/promotions" && method === "GET") result = stockReview ? stockPromotions : promotions;
  else if (path === "/promotions" && method === "POST") {
    const created = promo(`fixture-${Date.now()}`, String(body.name || "Promoción de ejemplo"), body.type || "QUANTITY_DISCOUNT",
      body.startDate || "2026-09-26", body.endDate ?? null, {
        status: "DRAFT", scope: body.scope || "SALE", customerSegment: body.customerSegment || "ALL",
        memberCategoryId: body.memberCategoryId ?? null, minimumAmount: body.minimumAmount ?? null, minimumQuantity: body.minimumQuantity ?? null,
        buyQuantity: body.buyQuantity ?? null, payQuantity: body.payQuantity ?? null, buyXPayYMode: body.buyXPayYMode ?? null,
        discountAmount: body.discountAmount ?? null, discountPercent: body.discountPercent ?? null, maximumDiscount: body.maximumDiscount ?? null,
        packPrice: body.packPrice ?? null, targets: body.targets ?? []
      });
    promotions = [created, ...promotions];
    result = created;
    status = 201;
  } else if (path === "/products" && method === "GET") result = stockReview ? products : products.slice(0, 6);
  else if (path === "/stock/page" && method === "GET") {
    const cursor = url.searchParams.get("cursor");
    const pageProducts = cursor === "fixture-page-2" ? products.slice(6) : stockPageOneProducts;
    result = {
      items: pageProducts.map(product => ({
        product: { ...product, productType: "UNIT", version: 1, taxesIncluded: true },
        stock: [{ productId: product.id, warehouseId, quantity: product.id === "helado-chocolate" ? 4 : 18 }]
      })),
      nextCursor: cursor === "fixture-page-2" ? null : "fixture-page-2",
      hasMore: cursor !== "fixture-page-2"
    };
  } else if (path === "/warehouses" && method === "GET") result = stockWarehouses;
  else if (path === "/taxes/selectable" && method === "GET") result = [];
  else if (/^\/ui\/table-preferences\/(gestion|venta)\/[^/]+$/.test(path) && method === "GET") {
    const [, app, tableKey] = path.match(/^\/ui\/table-preferences\/(gestion|venta)\/([^/]+)$/)!;
    result = { app, tableKey: decodeURIComponent(tableKey), columns: [], updatedAt: null };
  } else if (/^\/ui\/table-preferences\/(gestion|venta)\/[^/]+$/.test(path) && method === "PUT") {
    result = { ...body, updatedAt: "2026-09-26T00:00:00.000Z" };
  } else if ((path === "/families" || path === "/product-families") && method === "GET") result = stockReview ? families : families.slice(0, 3);
  else if (/^\/(families|product-families)\/[^/]+\/subfamilies$/.test(path) && method === "GET") {
    const familyId = decodeURIComponent(path.split("/")[2]);
    result = subfamilies.filter(item => item.familyId === familyId);
  } else if (path === "/member-categories" && method === "GET") result = memberCategories;
  else {
    const match = path.match(/^\/promotions\/([^/]+)(?:\/(duplicate|activate|deactivate))?$/);
    if (!match) throw new TypeError(`Fetch no simulado bloqueado: ${method} ${path}`);
    const id = decodeURIComponent(match[1]);
    const action = match[2];
    const current = promotions.find(item => item.id === id);
    if (!current) { status = 404; result = { message: "Promoción ficticia no encontrada" }; }
    else if (method === "DELETE" && !action) { promotions = promotions.filter(item => item.id !== id); result = null; }
    else if (method === "POST" && action === "duplicate") {
      const duplicate = { ...current, id: `fixture-${Date.now()}`, name: `${current.name} (copia)`, status: "DRAFT" as const, used: false, usageCount: 0 };
      promotions = [duplicate, ...promotions]; result = duplicate;
    } else if (method === "POST" && (action === "activate" || action === "deactivate")) {
      const updated = { ...current, status: action === "activate" ? "ACTIVE" as const : "INACTIVE" as const };
      promotions = promotions.map(item => item.id === id ? updated : item); result = updated;
    } else { status = 405; result = { message: "Operación no simulada" }; }
  }

  return new Response(result === null ? null : JSON.stringify(result), {
    status,
    headers: { "Content-Type": "application/json" }
  });
};

function Review() {
  const [locale, setLocale] = useState<LocaleCode>(initialLocale);
  const t = createTranslator(locale);
  const navigation: GestionNavigationItem[] = gestionNavigationGroups.map(group => {
    const destinations = group.destinations.map(destination => ({
      key: destination.key,
      label: t(destination.labelKey),
      icon: destination.icon,
      lock: destination.lock,
      onOpen: () => undefined
    }));
    return group.direct && destinations.length === 1
      ? destinations[0]
      : { key: group.key, label: t(group.labelKey), icon: group.icon, lock: group.lock, children: destinations };
  });

  return <AppFrame titleKey={stockReviewApp === "venta" ? "home.product" : "gestion.title"} locale={locale} session={session}
    onLocaleChange={setLocale} onLogout={() => undefined}>
    {stockReview ? stockReviewApp === "gestion" ? (
      <GestionShell session={session} t={t} activeKey="stock.promotions" navigation={navigation}>
        <section className="gestion-module-stage">
          <StockScreen app="gestion" locale={locale} session={session} terminalContext={terminalContext}
            onBack={() => undefined} onLocaleChange={setLocale} onLogout={() => undefined}
            embedded initialView="stock.promotions" />
        </section>
      </GestionShell>
    ) : (
      <StockScreen app="venta" locale={locale} session={session} terminalContext={terminalContext}
        onBack={() => undefined} onLocaleChange={setLocale} onLogout={() => undefined}
        initialView="stock.promotions" />
    ) : (
      <GestionShell session={session} t={t} activeKey="promotions" navigation={navigation}>
        <section className="gestion-module-stage">
          <PromotionListScreen app="gestion" locale={locale} session={session} terminalContext={terminalContext}
            onBack={() => undefined} onLocaleChange={setLocale} onLogout={() => undefined} embedded />
        </section>
      </GestionShell>
    )}
  </AppFrame>;
}

const root = import.meta.hot?.data.root ?? createRoot(document.getElementById("root")!);
root.render(<StrictMode><Review /></StrictMode>);
if (import.meta.hot) {
  import.meta.hot.data.root = root;
  import.meta.hot.accept();
}

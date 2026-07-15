# Sale Main Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traducir la vista principal de Venta a español, inglés y chino, y conservar el idioma elegido de forma independiente para cada usuario autenticado.

**Architecture:** La vista seguirá usando `createTranslator(locale)` y trasladará sus textos fijos a claves `sale.main.*` de los tres catálogos existentes. APP VENTA incorporará un controlador pequeño de preferencia por usuario, probado de forma aislada, que gestiona el estado React y encapsula la lectura/escritura segura de `localStorage`.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library y el catálogo i18n propio de app-common.

## Global Constraints

- Traducir únicamente la vista principal de Venta y sus textos accesibles visibles.
- No traducir las ventanas de cantidad, descuento, cliente, anulación ni cobro.
- No traducir datos de productos o clientes procedentes del servidor.
- No cambiar formatos numéricos, monetarios, de fecha ni de hora; conservar `es-ES`.
- Admitir exactamente `es`, `en` y `zh`.
- Antes del login y después del logout usar español.
- Después del login cargar la preferencia guardada del usuario.
- Guardar la selección por `userId` o, si falta, `username`, normalizado y codificado.
- Tratar valores ausentes o inválidos como español.
- Un fallo o ausencia de `localStorage` no debe impedir usar la aplicación.
- No añadir dependencias ni introducir condicionales de traducción por locale en `SaleScreen`.

---

## File Structure

- Create `frontend/apps/app-venta/src/saleUserLocale.ts`: almacenamiento validado y controlador React de locale por usuario.
- Create `frontend/apps/app-venta/src/saleUserLocale.test.ts`: persistencia, aislamiento, valores inválidos y ciclo login/logout.
- Modify `frontend/apps/app-venta/src/main.tsx`: cableado del controlador con login, selector y logout.
- Modify `frontend/packages/app-common/src/i18n/MessagesEs.ts`: mensajes españoles de `sale.main.*`.
- Modify `frontend/packages/app-common/src/i18n/MessagesEn.ts`: mensajes ingleses de `sale.main.*`.
- Modify `frontend/packages/app-common/src/i18n/MessagesZh.ts`: mensajes chinos de `sale.main.*`.
- Modify `frontend/packages/app-common/src/components/SaleScreen.tsx`: consumo de mensajes para la vista principal.
- Modify `frontend/packages/app-common/src/components/SaleScreen.test.tsx`: render por locale, ausencia de mezcla e interpolación.

### Task 1: Preferencia de idioma independiente por usuario

**Files:**
- Create: `frontend/apps/app-venta/src/saleUserLocale.ts`
- Create: `frontend/apps/app-venta/src/saleUserLocale.test.ts`
- Modify: `frontend/apps/app-venta/src/main.tsx`

**Interfaces:**
- Produces: `saleUserLocaleStorageKey(session: UserSession): string`.
- Produces: `readSaleUserLocale(session: UserSession, storage?: Storage): LocaleCode`.
- Produces: `saveSaleUserLocale(session: UserSession, locale: LocaleCode, storage?: Storage): void`.
- Produces: `useSaleUserLocalePreference(storage?: Storage)` con `{ locale, applyUserLocale, changeLocale, resetLocale }`.

- [ ] **Step 1: Escribir las pruebas fallidas del almacenamiento y del ciclo de sesión**

Crear `saleUserLocale.test.ts`:

```tsx
// @vitest-environment jsdom

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import {
  readSaleUserLocale,
  saleUserLocaleStorageKey,
  saveSaleUserLocale,
  useSaleUserLocalePreference,
} from "./saleUserLocale";

const userA: UserSession = { userId: " USER/A ", username: "ADMIN", displayName: "Admin", permissions: [] };
const userB: UserSession = { username: " VENTA.B ", displayName: "Venta B", permissions: [] };

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("sale user locale", () => {
  it("builds a normalized APP VENTA key from userId or username", () => {
    expect(saleUserLocaleStorageKey(userA)).toBe("tpv-erp:venta:user:user%2Fa:locale");
    expect(saleUserLocaleStorageKey(userB)).toBe("tpv-erp:venta:user:venta.b:locale");
  });

  it("stores valid locales independently and rejects invalid stored values", () => {
    saveSaleUserLocale(userA, "en");
    saveSaleUserLocale(userB, "zh");
    expect(readSaleUserLocale(userA)).toBe("en");
    expect(readSaleUserLocale(userB)).toBe("zh");
    localStorage.setItem(saleUserLocaleStorageKey(userA), "fr");
    expect(readSaleUserLocale(userA)).toBe("es");
  });

  it("starts and resets in Spanish, loads on login and persists active changes", () => {
    saveSaleUserLocale(userA, "en");
    const { result } = renderHook(() => useSaleUserLocalePreference());
    expect(result.current.locale).toBe("es");

    act(() => result.current.applyUserLocale(userA));
    expect(result.current.locale).toBe("en");
    act(() => result.current.changeLocale(userA, "zh"));
    expect(result.current.locale).toBe("zh");
    expect(readSaleUserLocale(userA)).toBe("zh");

    act(() => result.current.resetLocale());
    expect(result.current.locale).toBe("es");
  });

  it("keeps the in-memory locale usable when storage throws", () => {
    const unavailable = {
      getItem: () => { throw new Error("blocked"); },
      setItem: () => { throw new Error("blocked"); },
    } as unknown as Storage;
    const { result } = renderHook(() => useSaleUserLocalePreference(unavailable));

    act(() => result.current.applyUserLocale(userA));
    expect(result.current.locale).toBe("es");
    act(() => result.current.changeLocale(userA, "en"));
    expect(result.current.locale).toBe("en");
  });
});
```

- [ ] **Step 2: Ejecutar la prueba y confirmar RED**

Run:

```powershell
cd frontend
npm.cmd test -- apps/app-venta/src/saleUserLocale.test.ts
```

Expected: FAIL porque `saleUserLocale.ts` no existe.

- [ ] **Step 3: Implementar el almacenamiento y controlador mínimos**

Crear `saleUserLocale.ts`:

```ts
import { useCallback, useState } from "react";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";

const defaultLocale: LocaleCode = "es";
const supportedLocales = new Set<LocaleCode>(["es", "en", "zh"]);

function browserStorage(storage?: Storage) {
  if (storage) return storage;
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

export function saleUserLocaleStorageKey(session: UserSession) {
  const identity = (session.userId?.trim() || session.username.trim()).toLocaleLowerCase();
  return `tpv-erp:venta:user:${encodeURIComponent(identity)}:locale`;
}

export function readSaleUserLocale(session: UserSession, storage?: Storage): LocaleCode {
  try {
    const value = browserStorage(storage)?.getItem(saleUserLocaleStorageKey(session));
    return value != null && supportedLocales.has(value as LocaleCode) ? value as LocaleCode : defaultLocale;
  } catch {
    return defaultLocale;
  }
}

export function saveSaleUserLocale(session: UserSession, locale: LocaleCode, storage?: Storage) {
  try {
    browserStorage(storage)?.setItem(saleUserLocaleStorageKey(session), locale);
  } catch {
    // The current session still keeps the selected locale in memory.
  }
}

export function useSaleUserLocalePreference(storage?: Storage) {
  const [locale, setLocale] = useState<LocaleCode>(defaultLocale);
  const applyUserLocale = useCallback((session: UserSession) => {
    setLocale(readSaleUserLocale(session, storage));
  }, [storage]);
  const changeLocale = useCallback((session: UserSession | null, next: LocaleCode) => {
    setLocale(next);
    if (session) saveSaleUserLocale(session, next, storage);
  }, [storage]);
  const resetLocale = useCallback(() => setLocale(defaultLocale), []);

  return { locale, applyUserLocale, changeLocale, resetLocale };
}
```

- [ ] **Step 4: Ejecutar las pruebas del controlador y confirmar GREEN**

Run:

```powershell
cd frontend
npm.cmd test -- apps/app-venta/src/saleUserLocale.test.ts
```

Expected: 4 tests PASS.

- [ ] **Step 5: Cablear el controlador en APP VENTA**

En `main.tsx`, sustituir el estado directo de locale por:

```tsx
const [session, setSession] = useState<UserSession | null>(null);
const [screen, setScreen] = useState<"home" | "sale" | "stock" | "salesReport" | "settings" | "hardwareSettings">("home");
const { locale, applyUserLocale, changeLocale, resetLocale } = useSaleUserLocalePreference();

const handleLocaleChange = (next: LocaleCode) => changeLocale(session, next);
const handleLogin = (nextSession: UserSession) => {
  setSession(nextSession);
  applyUserLocale(nextSession);
  setScreen("home");
};
const handleLogout = () => {
  setSession(null);
  resetLocale();
};
```

Importar `useSaleUserLocalePreference`, retirar las declaraciones antiguas de `locale`/`setLocale` y la declaración duplicada de `screen`, y conectar en todas las ramas de APP VENTA:

```tsx
onLocaleChange={handleLocaleChange}
onLogin={handleLogin}
onLogout={handleLogout}
```

El `LoginScreen` recibirá `onLocaleChange={handleLocaleChange}` con `session === null`, por lo que permitirá un cambio temporal sin persistirlo. Todos los componentes autenticados compartirán el mismo handler persistente.

- [ ] **Step 6: Verificar la aplicación de Venta**

Run:

```powershell
cd frontend
npm.cmd test -- apps/app-venta/src/saleUserLocale.test.ts
npm.cmd run build --workspace @tpverp/app-venta
```

Expected: 4 tests PASS y build de APP VENTA con exit code 0.

- [ ] **Step 7: Commit**

```powershell
git add frontend/apps/app-venta/src/saleUserLocale.ts frontend/apps/app-venta/src/saleUserLocale.test.ts frontend/apps/app-venta/src/main.tsx
git commit -m "feat(venta): persist locale per user"
```

### Task 2: Traducción completa de la vista principal

**Files:**
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Consumes: `createTranslator(locale)` existente.
- Produces: claves `sale.main.*` idénticas en los tres catálogos.
- Produces: `saleMainMessage(t, key, values?)` y `saleMainProductCount(t, count)` para interpolación y plural.

- [ ] **Step 1: Escribir las pruebas fallidas de los tres idiomas**

En `SaleScreen.test.tsx`, generalizar el helper de render para aceptar `locale` y añadir:

```tsx
it.each([
  ["es", ["Venta", "Líneas de venta", "Sin venta iniciada", "Buscar producto", "Cobro"]],
  ["en", ["Sale", "Sale lines", "No sale started", "Search product", "Payment"]],
  ["zh", ["销售", "销售明细", "尚未开始销售", "搜索商品", "收款"]],
] as const)("localizes the main sale view in %s", (locale, labels) => {
  const html = renderToStaticMarkup(
    <SaleScreen
      app="venta"
      locale={locale}
      session={session}
      terminalContext={terminalContext}
      onBack={vi.fn()}
      onLocaleChange={vi.fn()}
      onLogout={vi.fn()}
    />,
  );

  labels.forEach((label) => expect(html).toContain(label));
  expect(html).toContain("0,00");
});

it.each(["en", "zh"] as const)("does not leak fixed Spanish main-view labels in %s", (locale) => {
  const html = renderToStaticMarkup(
    <SaleScreen app="venta" locale={locale} session={session} terminalContext={terminalContext} onBack={vi.fn()} onLocaleChange={vi.fn()} onLogout={vi.fn()} />,
  );

  ["Líneas de venta", "Sin venta iniciada", "Buscar producto", "Cantidad", "Descuento", "Anular línea", "Cobro"].forEach((label) => {
    expect(html).not.toContain(label);
  });
});

it("interpolates localized customer and product counters", () => {
  const tEn = createTranslator("en");
  const tZh = createTranslator("zh");
  expect(saleMainMessage(tEn, "sale.main.selectedCustomer", { name: "ACME" })).toBe("Customer: ACME");
  expect(saleMainProductCount(tEn, 1)).toBe("1 product");
  expect(saleMainProductCount(tEn, 2)).toBe("2 products");
  expect(saleMainProductCount(tZh, 2)).toBe("2 件商品");
});
```

Importar `createTranslator`, `saleMainMessage` y `saleMainProductCount` en el archivo de prueba. Actualizar la prueba española existente para esperar las grafías acentuadas `Líneas`, `Búsqueda`, `Código`, `rápida` y `Anular línea` donde correspondan.

- [ ] **Step 2: Ejecutar las pruebas y confirmar RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/SaleScreen.test.tsx
```

Expected: FAIL para inglés/chino porque la vista aún contiene textos fijos españoles y los helpers no existen.

- [ ] **Step 3: Añadir las claves exactas a los tres catálogos**

Añadir el mismo conjunto de claves en `MessagesEs`, `MessagesEn` y `MessagesZh` con estos valores:

```ts
// MessagesEs
"sale.main.screen": "Venta",
"sale.main.ticket": "Ticket actual",
"sale.main.lines": "Líneas de venta",
"sale.main.selectedCustomer": "Cliente: {name}",
"sale.main.noSale": "Sin venta iniciada",
"sale.main.productCount.one": "{count} producto",
"sale.main.productCount.many": "{count} productos",
"sale.main.ticketLines": "Líneas del ticket",
"sale.main.unnamedProduct": "Producto sin nombre",
"sale.main.missingCode": "Sin código",
"sale.main.member": "Socio",
"sale.main.searchAndPayment": "Búsqueda y cobro",
"sale.main.total": "Total",
"sale.main.product": "Producto",
"sale.main.quickEntry": "Entrada rápida por código, nombre o referencia",
"sale.main.searchProduct": "Buscar producto",
"sale.main.searchPlaceholder": "Código o nombre",
"sale.main.loadingProducts": "Cargando productos...",
"sale.main.catalogError": "No se pudo cargar el catálogo",
"sale.main.retry": "Reintentar",
"sale.main.noProducts": "No se encontraron productos",
"sale.main.quantity": "Cantidad",
"sale.main.discount": "Descuento",
"sale.main.customer": "Cliente",
"sale.main.removeLine": "Anular línea",
"sale.main.payment": "Cobro",
"sale.main.shortcuts": "Atajos de venta",
"sale.main.search": "Buscar",
"sale.main.cash": "Efectivo",
"sale.main.card": "Tarjeta",
"sale.main.pending": "Pendiente",
"sale.main.deleteKey": "Supr",
"sale.main.quoteError": "No se pudo calcular el total de la venta",
```

```ts
// MessagesEn
"sale.main.screen": "Sale",
"sale.main.ticket": "Current ticket",
"sale.main.lines": "Sale lines",
"sale.main.selectedCustomer": "Customer: {name}",
"sale.main.noSale": "No sale started",
"sale.main.productCount.one": "{count} product",
"sale.main.productCount.many": "{count} products",
"sale.main.ticketLines": "Ticket lines",
"sale.main.unnamedProduct": "Unnamed product",
"sale.main.missingCode": "No code",
"sale.main.member": "Member",
"sale.main.searchAndPayment": "Search and payment",
"sale.main.total": "Total",
"sale.main.product": "Product",
"sale.main.quickEntry": "Quick entry by code, name or reference",
"sale.main.searchProduct": "Search product",
"sale.main.searchPlaceholder": "Code or name",
"sale.main.loadingProducts": "Loading products...",
"sale.main.catalogError": "Could not load the catalog",
"sale.main.retry": "Retry",
"sale.main.noProducts": "No products found",
"sale.main.quantity": "Quantity",
"sale.main.discount": "Discount",
"sale.main.customer": "Customer",
"sale.main.removeLine": "Remove line",
"sale.main.payment": "Payment",
"sale.main.shortcuts": "Sale shortcuts",
"sale.main.search": "Search",
"sale.main.cash": "Cash",
"sale.main.card": "Card",
"sale.main.pending": "Pending",
"sale.main.deleteKey": "Del",
"sale.main.quoteError": "Could not calculate the sale total",
```

```ts
// MessagesZh
"sale.main.screen": "销售",
"sale.main.ticket": "当前小票",
"sale.main.lines": "销售明细",
"sale.main.selectedCustomer": "客户：{name}",
"sale.main.noSale": "尚未开始销售",
"sale.main.productCount.one": "{count} 件商品",
"sale.main.productCount.many": "{count} 件商品",
"sale.main.ticketLines": "小票明细",
"sale.main.unnamedProduct": "未命名商品",
"sale.main.missingCode": "无编码",
"sale.main.member": "会员",
"sale.main.searchAndPayment": "商品搜索与收款",
"sale.main.total": "合计",
"sale.main.product": "商品",
"sale.main.quickEntry": "可通过编码、名称或参考号快速录入",
"sale.main.searchProduct": "搜索商品",
"sale.main.searchPlaceholder": "编码或名称",
"sale.main.loadingProducts": "正在加载商品...",
"sale.main.catalogError": "无法加载商品目录",
"sale.main.retry": "重试",
"sale.main.noProducts": "未找到商品",
"sale.main.quantity": "数量",
"sale.main.discount": "折扣",
"sale.main.customer": "客户",
"sale.main.removeLine": "删除行",
"sale.main.payment": "收款",
"sale.main.shortcuts": "销售快捷键",
"sale.main.search": "搜索",
"sale.main.cash": "现金",
"sale.main.card": "银行卡",
"sale.main.pending": "待处理",
"sale.main.deleteKey": "Del",
"sale.main.quoteError": "无法计算销售合计",
```

- [ ] **Step 4: Implementar interpolación y sustituir textos fijos de la vista**

Añadir helpers exportados junto a las funciones puras de `SaleScreen.tsx`:

```ts
type SaleTranslator = (key: string) => string;

export function saleMainMessage(
  t: SaleTranslator,
  key: string,
  values: Record<string, string | number> = {},
) {
  return Object.entries(values).reduce(
    (message, [name, value]) => message.replaceAll(`{${name}}`, String(value)),
    t(key),
  );
}

export function saleMainProductCount(t: SaleTranslator, count: number) {
  return saleMainMessage(t, count === 1 ? "sale.main.productCount.one" : "sale.main.productCount.many", { count });
}
```

En el JSX comprendido entre `<section className="work-shell">` y `</ScreenContextFooter>`, sustituir cada texto y `aria-label` enumerado en Step 3 por `t("sale.main.<key>")`. Usar:

```tsx
selectedCustomer
  ? saleMainMessage(t, "sale.main.selectedCustomer", { name: selectedCustomer.fiscalName })
  : lines.length === 0
    ? paymentLocked ? t("payment.split.reservedTicket") : t("sale.main.noSale")
    : saleMainProductCount(t, lines.length)
```

Para producto/código ausentes y etiqueta de socio usar `t("sale.main.unnamedProduct")`, `t("sale.main.missingCode")` y `t("sale.main.member")`. Para los dos fallbacks de error de cotización de las aperturas de efectivo/tarjeta usar `t("sale.main.quoteError")`.

No modificar el JSX de los `SaleActionDialog`, `CashPaymentDialog`, `CashPaymentResultDialog` ni `CardPaymentDialog`. Mantener `formatSaleAmount` y cualquier `toLocaleString("es-ES")` sin cambios.

- [ ] **Step 5: Verificar catálogos y vista localizada**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/i18n/messages.test.ts packages/app-common/src/components/SaleScreen.test.tsx
```

Expected: ambos archivos PASS; los tres catálogos tienen las mismas claves y la vista no mezcla las etiquetas principales españolas en `en`/`zh`.

- [ ] **Step 6: Ejecutar regresiones y build completo**

Run:

```powershell
cd frontend
npm.cmd test
npm.cmd run build
```

Expected: suite completa PASS y builds de APP GESTIÓN y APP VENTA con exit code 0.

- [ ] **Step 7: Commit**

```powershell
git add frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx
git commit -m "feat(venta): localize main sale view"
```

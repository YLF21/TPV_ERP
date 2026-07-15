# Sale Dialog, Search and Member Pricing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completar los diálogos y la búsqueda con teclado y aplicar de forma autoritativa el precio de socio más el porcentaje del nivel.

**Architecture:** `SaleScreen` gestiona selección/foco y calcula una vista previa con helpers puros. `MemberLoyaltyService` transforma cada `DocumentLineCommand` usando el socio y nivel actuales, y `PosCashService.authoritativeCommand` invoca esa regla para que efectivo, tarjeta, sesiones de pago y ticket compartan el mismo total.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library, Spring Boot, Java 25, JUnit 5, Mockito, Maven.

## Global Constraints

- Precio base: `memberPrice` válido para socio activo y producto `MEMBER_PRICE`; en otro caso precio normal efectivo.
- Descuento efectivo: máximo entre descuento manual y porcentaje del nivel; nunca se suman.
- Nivel inactivo, descuento deshabilitado o cliente no socio equivale a 0%.
- Backend autoritativo; no confiar en precios calculados por el navegador.
- Conservar restricciones de descuento manual, promociones, clics y atajos existentes.
- No modificar datos ni crear niveles en PostgreSQL.

---

### Task 1: Edición rápida y cancelación de diálogos

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `quantityInputRef`, `discountInputRef`, `SaleActionDialog`.
- Produces: `select()` al abrir F2/F7 y Escape contextual para cantidad, descuento y cliente.

- [ ] **Step 1: Escribir pruebas fallidas**

Usar `userEvent.keyboard` para abrir F2/F7, escribir sin borrar y comprobar sustitución; volver a abrir, pulsar Escape y comprobar que línea/cliente no cambian:

```tsx
fireEvent.keyDown(window, { key: "F2" });
expect(screen.getByRole("spinbutton", { name: "Nueva cantidad" })).toHaveFocus();
await user.keyboard("2{Enter}");
expect(screen.getByText(/2 x/)).toBeVisible();

fireEvent.keyDown(window, { key: "F7" });
await user.keyboard("10{Escape}");
expect(screen.queryByRole("dialog", { name: "Aplicar descuento" })).not.toBeInTheDocument();
```

- [ ] **Step 2: Confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL por falta de selección completa y Escape en esos diálogos.

- [ ] **Step 3: Implementar selección y Escape**

Después de enfocar, llamar `ref.current?.select()`. Extender el manejo contextual de `SaleActionDialog` para que Escape ejecute `onClose` en cantidad, descuento y cliente sin enviar formularios.

Agregar una clase de formulario:

```tsx
<form className="sale-action-form" onSubmit={...}>...</form>
```

Y espaciado:

```css
.sale-action-form { display: grid; gap: 18px; }
.sale-action-form .sale-action-buttons { margin-top: 4px; gap: 14px; }
```

- [ ] **Step 4: Confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: PASS.

### Task 2: Primer resultado seleccionado y Enter

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `filterSaleProducts`, `selectSaleProduct`, `results`, `addProduct`.
- Produces: `selectedSearchProductId` y `sale-search-result selected` accesible.

- [ ] **Step 1: Escribir pruebas fallidas**

Cubrir consulta ambigua con dos resultados: el primero tiene `aria-selected="true"` y Enter lo añade. Cubrir coincidencia exacta para demostrar que sigue teniendo prioridad.

- [ ] **Step 2: Confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL porque `selectSaleProduct` actualmente devuelve `undefined` con varias coincidencias.

- [ ] **Step 3: Implementar selección predeterminada**

Mantener coincidencia exacta y usar el primer resultado como fallback:

```tsx
export function selectSaleProduct(products: SaleProduct[], query: string) {
  const exact = /* búsqueda exacta existente */;
  return exact ?? filterSaleProducts(products, query)[0];
}
```

Derivar/resetear `selectedSearchProductId` al primer resultado cuando cambia la consulta, añadir `aria-selected`, clase `selected` y estilo equivalente al foco.

- [ ] **Step 4: Confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: PASS.

### Task 3: Vista previa frontend de beneficios de socio

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`

**Interfaces:**
- Produces: `effectiveSaleProductPrice(product, activeMember?)`, `effectiveSaleLineDiscount(line)` y `applyMemberDiscounts` coherentes.

- [ ] **Step 1: Escribir pruebas fallidas de la matriz de precios**

Casos obligatorios:

```tsx
// socio + MEMBER_PRICE => memberPrice
// socio + producto normal => salePrice
// no socio + MEMBER_PRICE => salePrice
// manual 8%, nivel 5% => 8%
// manual 3%, nivel 5% => 5%
// cliente seleccionado antes/después de añadir producto
```

- [ ] **Step 2: Confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL porque el nivel solo se aplica hoy a `MEMBER_DISCOUNT`.

- [ ] **Step 3: Implementar helpers mínimos**

La línea conserva `memberDiscountPercent`; el precio efectivo recibe el estado de socio:

```tsx
export function effectiveSaleProductPrice(product: SaleProduct, activeMember = false) {
  if (activeMember && product.discountType === "MEMBER_PRICE" && Number(product.memberPrice) > 0) {
    return Number(product.memberPrice);
  }
  return /* precio normal/oferta existente */;
}

export function effectiveSaleLineDiscount(line: SaleLine) {
  return Math.max(line.discountPercent, line.memberDiscountPercent ?? 0);
}
```

`applyMemberDiscounts` aplica el porcentaje del socio activo a todas las líneas; no lo limita a `MEMBER_DISCOUNT`. Subtotal, total y render usan el precio efectivo con el socio seleccionado.

- [ ] **Step 4: Confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: PASS.

### Task 4: Precio y descuento autoritativos en backend

**Files:**
- Modify: `backend/src/test/java/com/tpverp/backend/party/MemberLoyaltyServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/MemberLoyaltyService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/PosCashServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`

**Interfaces:**
- Consumes: `DocumentLineCommand.withPrice`, `DocumentLineCommand.withDiscount`, `MemberCategory`.
- Produces: `applyLineBenefit(UUID customerId, DocumentLineCommand line, Product product)` con base y máximo de descuentos; `authoritativeCommand` lo invoca.

- [ ] **Step 1: Escribir pruebas fallidas del servicio de fidelización**

Cubrir socio activo con categoría activa/descuento habilitado:

```java
assertThat(priced.precioUnitario()).isEqualByComparingTo("80.00");
assertThat(priced.descuento()).isEqualByComparingTo("5.00");
```

Añadir producto normal (precio 100 + 5%), manual 8% frente a nivel 5%, categoría deshabilitada y miembro inactivo.

- [ ] **Step 2: Confirmar RED del servicio**

Run: `mvn.cmd "-Dtest=MemberLoyaltyServiceTest" test`

Expected: FAIL porque la categoría no altera la línea.

- [ ] **Step 3: Implementar regla en `applyLineBenefit`**

Resolver una vez el socio activo. Elegir `memberPrice` solo para `MEMBER_PRICE`; obtener porcentaje únicamente de categoría activa y `discountEnabled`; aplicar `max(line.descuento(), categoryDiscount)` mediante `withDiscount`.

- [ ] **Step 4: Escribir prueba fallida de integración en `PosCashServiceTest`**

Verificar que `authoritativeCommand` pasa cada línea catalogada por `memberLoyalty.applyLineBenefit(customerId, line, product)` y usa el comando devuelto.

- [ ] **Step 5: Confirmar RED de POS**

Run: `mvn.cmd "-Dtest=PosCashServiceTest" test`

Expected: FAIL porque `PosCashService` construye hoy líneas con precio normal directamente.

- [ ] **Step 6: Inyectar y aplicar el servicio**

Añadir `MemberLoyaltyService` al constructor y transformar:

```java
var catalogLine = new DocumentLineCommand(...);
return memberLoyalty.applyLineBenefit(request.customerId(), catalogLine, product);
```

Actualizar fixtures de constructor sin cambios de esquema.

- [ ] **Step 7: Confirmar GREEN backend**

Run: `mvn.cmd "-Dtest=MemberLoyaltyServiceTest,PosCashServiceTest,PosCardServiceTest" test`

Expected: PASS.

### Task 5: Coherencia de petición y verificación integral

**Files:**
- Modify if required: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify if required: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Verify all files from Tasks 1-4.

**Interfaces:**
- Consumes: cálculo frontend y comando backend autoritativo.
- Produces: una venta que envía el descuento manual original, mientras el backend deriva el nivel y precio de socio de datos confiables.

- [ ] **Step 1: Verificar contrato de petición**

Asegurar mediante prueba que `cashSaleRequest()` no suplanta el porcentaje de nivel como entrada del usuario: envía `discountPercent`; el backend añade el beneficio autoritativo.

- [ ] **Step 2: Ejecutar frontend completo**

Run: `npm.cmd test`

Expected: PASS.

- [ ] **Step 3: Compilar APP VENTA**

Run: `npm.cmd run build --workspace @tpverp/app-venta`

Expected: exit 0.

- [ ] **Step 4: Ejecutar backend relevante**

Run: `mvn.cmd "-Dtest=MemberLoyaltyServiceTest,PosCashServiceTest,PosCardServiceTest,SalePaymentSessionServiceTest" test`

Expected: PASS.

- [ ] **Step 5: Revisar alcance**

Run: `git diff --check` y `git status --short`.

Expected: sin cambios de migraciones ni datos; solo frontend, servicios/pruebas backend y documentación de esta funcionalidad.

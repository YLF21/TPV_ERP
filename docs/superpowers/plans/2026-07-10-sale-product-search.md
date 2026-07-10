# Sale Product Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hacer funcional la busqueda de productos por codigo, codigo de barras o nombre y añadir los resultados al ticket local de Venta.

**Architecture:** `SaleScreen` cargara `GET /products` una vez y mantendra catalogo, consulta, estados de carga y lineas del ticket. Funciones puras exportadas resolveran filtrado, seleccion por Enter, acumulacion y total para pruebas rapidas y deterministas.

**Tech Stack:** React 19, TypeScript, Vitest, API REST existente.

## Global Constraints

- No modificar el backend.
- Maximo diez resultados visibles.
- Comparacion parcial sin distinguir mayusculas, con coincidencia exacta prioritaria por codigo o codigo de barras.
- Los cambios permanecen locales y no se realizan commits.

---

### Task 1: Logica pura de busqueda y ticket

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`

**Interfaces:**
- Produces: `filterSaleProducts(products, query, limit)`, `selectSaleProduct(products, query)`, `addSaleLine(lines, product)`, `saleTotal(lines)`.

- [ ] **Step 1: Escribir pruebas fallidas**

Añadir casos que busquen `CAFE` por nombre, `CAF-001` por codigo, `8410000000011` por codigo de barras, limiten a diez resultados, prioricen codigo exacto, incrementen cantidad y calculen el total.

- [ ] **Step 2: Verificar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`
Expected: FAIL porque las funciones exportadas todavia no existen.

- [ ] **Step 3: Implementar las funciones minimas**

Definir tipos `SaleProduct` y `SaleLine`; normalizar con `trim().toLocaleLowerCase()`; filtrar `code`, `barcode` y `name`; acumular por `id`; calcular `quantity * unitPrice`.

- [ ] **Step 4: Verificar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`
Expected: PASS.

### Task 2: Integracion React y estados visibles

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: funciones puras de Task 1 y `apiRequest<SaleProduct[]>("/products", { token })`.
- Produces: buscador controlado, lista seleccionable, ticket y total renderizados.

- [ ] **Step 1: Escribir pruebas fallidas de renderizado**

Comprobar atributos accesibles del buscador, contenedor de resultados, mensajes de carga/error/sin resultados y marcado de lineas del ticket.

- [ ] **Step 2: Verificar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`
Expected: FAIL porque la interfaz funcional no esta renderizada.

- [ ] **Step 3: Implementar integracion minima**

Cargar productos con `useEffect`; controlar consulta; resolver Enter; añadir al pulsar; limpiar y reenfocar; renderizar hasta diez resultados, errores, reintento, lineas y total.

- [ ] **Step 4: Añadir estilos acotados**

Crear reglas `sale-search-results`, `sale-search-result`, `sale-search-status`, `sale-ticket-line` y columnas de cantidad/precio/subtotal coherentes con `tpv.css`.

- [ ] **Step 5: Verificar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`
Expected: PASS.

### Task 3: Regresion y prueba funcional

**Files:**
- Verify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Verify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] **Step 1: Ejecutar suite frontend**

Run: `npm.cmd test`
Expected: todas las pruebas PASS.

- [ ] **Step 2: Ejecutar build**

Run: `npm.cmd run build`
Expected: TypeScript y Vite terminan con codigo 0.

- [ ] **Step 3: Probar en navegador**

Iniciar sesion como `ADMIN / 0000`, abrir Venta, buscar por nombre y codigo, seleccionar productos y comprobar cantidad y total.

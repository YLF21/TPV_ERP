# Sale Line Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Activar Cantidad, Descuento, Cliente y Anular linea en el ticket local de Venta.

**Architecture:** Extender `SaleLine` con descuento y añadir funciones puras para actualizar/eliminar/recalcular. `SaleScreen` gestionara seleccion, dialogos y clientes cargados desde `/customers`.

**Tech Stack:** React 19, TypeScript, Vitest, API REST existente.

## Global Constraints

- Sin persistencia de ticket en backend.
- Cantidad entera 1..9999 y descuento decimal 0..100.
- Sin commits ni push.

### Task 1: Modelo de linea y clientes

- [ ] Escribir pruebas fallidas de cantidad, descuento, eliminacion, total y filtro de clientes.
- [ ] Ejecutar `npm.cmd test -- SaleScreen.test.tsx` y confirmar RED.
- [ ] Implementar `updateSaleLineQuantity`, `updateSaleLineDiscount`, `removeSaleLine`, `saleLineSubtotal` y `filterSaleCustomers`.
- [ ] Ejecutar la prueba y confirmar GREEN.

### Task 2: Interfaz de acciones

- [ ] Añadir seleccion visual de linea y deshabilitar acciones sin seleccion.
- [ ] Añadir dialogos integrados de Cantidad, Descuento, Cliente y confirmacion de anulacion.
- [ ] Cargar clientes al abrir el selector y permitir asignar/quitar.
- [ ] Añadir estilos de seleccion y dialogos.
- [ ] Ejecutar prueba especifica y build.

### Task 3: Prueba funcional

- [ ] Verificar en navegador cantidad, descuento, cliente y anulacion.
- [ ] Revisar diff local y reportar fallos preexistentes por separado.

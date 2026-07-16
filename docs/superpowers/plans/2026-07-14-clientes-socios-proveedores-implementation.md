# Plan

Completar los maestros compartidos de Clientes, Socios y Proveedores sobre los contratos existentes, cerrando primero el ciclo de vida backend, después las fichas y configuración compartidas y finalmente sus integraciones con Venta y Almacén.

## Scope

- In: tres apartados separados, alta, consulta, edición, activación/desactivación, fidelización dentro de Socios, permisos, integraciones y pruebas.
- Out: eliminación desde UI, deuda/cuenta corriente, campañas masivas y productos/precios/representantes dentro de Proveedores.

## Action items

[ ] **1. Fijar contratos y regresión inicial.** Añadir pruebas que documenten el comportamiento actual de `CustomerController`, `SupplierController` y `MemberLoyaltyController`; verificar códigos inmutables, aislamiento por empresa y campos ya expuestos antes de modificar servicios.

[ ] **2. Completar activación backend.** Añadir `PATCH /api/v1/customers/{id}/activate` y `PATCH /api/v1/suppliers/{id}/activate`, implementar métodos de dominio/servicio idempotentes y probar que reactivan el mismo registro sin cambiar `clientId`, `supplierId` ni historial.

[ ] **3. Cerrar validaciones de cliente y proveedor.** Verificar y completar normalización de documento, email, país, dirección, porcentajes, duplicados y consentimiento/canal preferido; mantener los endpoints `DELETE` fuera del flujo frontend.

[ ] **4. Refactorizar el directorio compartido.** Dividir `PartyDirectoryPanel.tsx` en tabla, toolbar, ficha y diálogo reutilizables; conservar `Clientes`, `Socios` y `Proveedores` como rutas internas separadas de `StockScreen.tsx` y mantener el UI business-classic.

[ ] **5. Terminar Clientes.** Implementar filtros, detalle, alta y edición de todos los campos personales/fiscales; añadir confirmación de activar/desactivar, indicador fiscal, persistencia de búsqueda/selección y modo consulta sin `CUSTOMERS_WRITE`.

[ ] **6. Terminar Proveedores.** Implementar filtros, detalle, alta, edición y activar/desactivar únicamente para datos fiscales/contacto; excluir explícitamente productos, precios, entradas y representantes de la ficha; aplicar `SUPPLIERS_READ/WRITE`.

[ ] **7. Terminar el listado y ficha de Socios.** Mostrar códigos, cliente, categoría, puntos, saldo, fecha y estado mediante `GET /api/v1/members`; crear o reactivar la relación únicamente seleccionando un cliente activo existente; activar/desactivar conservando histórico y acceder a los datos de identidad del cliente sin duplicarlos.

[ ] **8. Implementar fidelización dentro de Socios.** Integrar movimientos, ajustes de puntos/saldo con motivo, cambio y bloqueo de categoría, CRUD lógico de categorías, settings, canales comerciales y entregas/reintentos de tarjeta mediante los endpoints existentes.

[ ] **9. Aplicar permisos y estados operativos.** Ocultar apartados sin permiso de lectura, ocultar mutaciones sin permiso de escritura, reservar el cambio manual de categoría para `ADMIN` y cubrir sesiones de APP VENTA y APP GESTIÓN con combinaciones reales de permisos.

[ ] **10. Integrar consumidores.** Actualizar selectores de Venta, Salida de almacén y Entrada de almacén para usar datos del maestro compartido, excluir registros desactivados en nuevas operaciones y conservar referencias históricas en documentos existentes.

[ ] **11. Añadir cobertura frontend.** Crear pruebas de componentes para carga, búsqueda, filtros, alta, edición, permisos, activar/desactivar, errores, foco y teclado; ampliar `StockScreen.test.tsx` y mantener verdes las pruebas actuales.

[ ] **12. Verificar backend e integración.** Ejecutar pruebas dirigidas de party/member, `backend\mvnw.cmd test`, `npm test`, `npm run build` y `git diff --check`; realizar prueba manual completa en APP VENTA y APP GESTIÓN con persistencia tras recarga.

## Open questions

- Ninguna. Las decisiones funcionales necesarias para esta implementación están cerradas en la especificación asociada.

# Auditoría operativa de frontend y backend — APP VENTA

**Fecha de revisión:** 2026-07-25
**Ámbito:** estado verificable del código de esta revisión. Esta auditoría separa
la funcionalidad disponible en local de los simuladores y de lo que requiere
infraestructura, certificación o hardware ajenos al repositorio.

## Matriz de estado

| Área | Estado | Evidencia en código y pruebas | Límites operativos residuales |
| --- | --- | --- | --- |
| Búsqueda de productos y clientes, selección por teclado y edición del ticket | Implementado y comprobable localmente | `SaleScreen.tsx`; recorrido F5/F6 en `frontend/e2e/app-venta-operational-flows.spec.ts`; nueve casos de teclado/diálogos en `frontend/e2e/app-venta-responsive-accessibility.spec.ts`. | La prueba E2E requiere backend, frontend, usuario administrador y datos vendibles. |
| Cotización autoritativa, precio de socio, descuentos, cupones y promociones | Implementado y comprobable localmente | `AuthoritativeSaleQuoteController.java`, `PromotionService.java`, `PromotionalCouponService.java` y `SaleScreen.tsx`; el flujo operacional comprueba que la cotización autoritativa incorpora el socio y su descuento. | La evidencia E2E actual cubre cotización/socio; las reglas concretas dependen del catálogo y la configuración comercial cargados. |
| Cobro en efectivo | Implementado y comprobable localmente | `SalePaymentCheckout.tsx`, `CashPaymentResultDialog.tsx` y el caso «cobra en efectivo» de `app-venta-operational-flows.spec.ts`. | Requiere una sesión de caja válida en el entorno que ejecute el flujo. |
| Cobro con tarjeta | **Solo simulador en la prueba local** | `DeterministicPaymentTerminalSimulator.java` y los escenarios aprobado, denegado y timeout de `app-venta-operational-flows.spec.ts`. | Un resultado del simulador no acredita una comunicación con un datáfono ni con un adquirente. |
| Pendiente de cliente, vales y controles de crédito/cuenta corriente | Implementado y comprobable localmente | `CustomerPendingSaleService.java`, `CustomerCreditAccountService.java`, `CustomerReceivableController.java`, `CustomerPendingSaleDialog.tsx` y `CustomerReceivablesScreen.tsx`; el flujo operacional cubre crédito, gestión posterior y cierre de usuario. | Las opciones disponibles dependen de los permisos, crédito y datos del cliente del entorno. |
| Ventas aparcadas y gestión posterior de tickets | Implementado y comprobable localmente | `ParkedSaleController.java`, `ParkedSalesDialog.tsx`, `TicketManagementDialog.tsx`; el flujo operacional aparca y recupera una venta y verifica su retirada atómica del listado. | La comprobación E2E necesita datos y servicios en ejecución. |
| Movimientos de stock al confirmar documentos | Implementado; prueba unitaria local y prueba concurrente PostgreSQL explícita | `InventoryDocumentGateway.java` usa `findByProductIdAndWarehouseIdForUpdate`; `InventoryDocumentGatewayConcurrencyPostgreSqlTest.java` exige dos confirmaciones, dos movimientos de `-1` y stock final `8` en 20 rondas. | La prueba concurrente está etiquetada `integration`, queda excluida de `mvn test` normal y necesita una base PostgreSQL dedicada. |
| Auditoría, identificador de correlación y monitorización | Implementado en la aplicación | `CorrelationIdFilter.java`, `BusinessBacklogMonitor.java`, `TpvBusinessHealthIndicator.java`, Actuator y registro Prometheus declarados en `backend/pom.xml`. | La recepción de métricas, paneles y alertas de producción depende de la pila de despliegue y su enrutamiento de alertas. |
| Impresión de tickets | Implementado con dependencia operacional local | `ticketPrinting.ts`, `printRetry.ts`, `hardware.ts` y pruebas de impresión/hardware del paquete común. | Necesita impresora, drivers y, cuando aplique, el bridge/Electron configurado en el terminal. |

## Dependencias externas o validación pendiente fuera del repositorio

| Integración | Estado que esta revisión puede afirmar | Dependencia para cerrar la validación |
| --- | --- | --- |
| Redsys TPV-PC, PAYTEF, PAYCOMET y Global Payments físicos | Hay configuración, contratos de gateway y simulación; **no se declara implementada una comunicación física por ello**. | SDK o protocolo oficial aplicable, credenciales y comercio homologado, servicio/bridge local y datáfono real. Véanse `BridgeLivePaymentTerminalGateway.java` y `docs/payment-terminal-live-bridge.md`. |
| VERI*FACTU en producción | La aplicación contiene gestión y configuración del certificado; no constituye por sí sola una certificación de producción. | Certificado válido, secretos protegidos, endpoint/entorno productivo y validación/certificación fiscal requerida. Véase `docs/verifactu-certificate-windows-operations.md`. |
| Hardware de terminal | El frontend puede solicitar las operaciones locales previstas. | Impresora, cajón, escáner, pantalla cliente, drivers y validación física en el terminal de destino. |

## Alcance de interfaz respaldado por pruebas

- Escritorio: 1920×1080, 1366×768 y 1024×768.
- Idiomas: español (`es`), inglés (`en`) y chino (`zh`).
- `app-venta-responsive-accessibility.spec.ts` recorre las nueve combinaciones,
  comprueba ausencia de desbordamiento horizontal y usa Tab, Enter y Escape en
  los diálogos de cantidad y cliente; también exige nombre accesible en los
  controles interactivos visibles.
- No se reclama soporte móvil por debajo de 1024 px.

## Comandos de verificación

Ejecutar desde los directorios indicados. Los E2E no arrancan los servicios por
sí mismos: reutilizan las instancias locales declaradas en las variables de
entorno.

```powershell
cd frontend
npm.cmd test
npm.cmd run build

$env:E2E_REUSE_EXTERNAL_SERVERS = "true"
$env:E2E_BACKEND_URL = "http://127.0.0.1:18080"
$env:E2E_VENTA_URL = "http://127.0.0.1:4173"
$env:E2E_ADMIN_USERNAME = "ADMIN"
$env:E2E_ADMIN_PASSWORD = "0000"
npm.cmd run test:e2e -- app-venta-operational-flows.spec.ts app-venta-responsive-accessibility.spec.ts

cd ..\backend
mvn.cmd test

$env:TPV_ERP_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:5432/tpv_erp_test"
$env:TPV_ERP_TEST_DB_USER = "tpv_erp"
# TPV_ERP_TEST_DB_PASSWORD debe estar definida previamente en el entorno.
mvn.cmd -Dtest=InventoryDocumentGatewayConcurrencyPostgreSqlTest test
```

## Evidencia de esta ejecución (2026-07-25)

| Comando | Resultado observado | Interpretación |
| --- | --- | --- |
| `frontend/npm.cmd test` | Falló: 916 de 917 pruebas pasaron; falló `SaleScreen.paymentCleanup.integration.test.tsx` porque sigue buscando el diálogo `Seleccionar cliente`. | La prueba mantiene un nombre español hardcodeado tras la localización del diálogo. No se considera evidencia de suite verde. |
| `frontend/npm.cmd run build` | Correcto: build de `@tpverp/app-gestion` y `@tpverp/app-venta` finalizada. | TypeScript y Vite construyen ambos paquetes; Vite emitió avisos de tamaño de chunk, no errores. |
| `backend/mvn.cmd test` | Correcto: 1.638 pruebas, 0 fallos, 0 errores y 36 omitidas. | La suite normal excluye las pruebas etiquetadas `integration`, incluida la concurrencia PostgreSQL focal. |
| Listado Playwright | Correcto: 16 pruebas descubiertas en los dos ficheros (7 operacionales y 9 de escritorio/accesibilidad). | Es descubrimiento de pruebas; no sustituye su ejecución contra los servicios. |
| Playwright focal y operacional | No ejecutado: `127.0.0.1:18080` y `127.0.0.1:4173` estaban cerrados. | Requiere servicios reutilizables, credenciales y datos; no se infiere un resultado de prueba cuando faltan. |
| Concurrencia PostgreSQL focal | No ejecutado: faltaban `TPV_ERP_TEST_DB_URL`, `TPV_ERP_TEST_DB_USER` y `TPV_ERP_TEST_DB_PASSWORD`. | Requiere las tres variables y una base de pruebas exclusiva; no se infiere el invariante sin esa ejecución. |

## Límites explícitos de esta auditoría

- Una build correcta no sustituye una ejecución E2E ni una validación de
  hardware o certificación.
- La simulación de tarjeta cubre los resultados programados, no una transacción
  real con proveedor.
- La invariancia concurrente de stock se acredita únicamente al ejecutar la
  prueba PostgreSQL explícita contra una base dedicada; `mvn test` ordinario la
  excluye por su etiqueta de integración.
- Las alertas y la observabilidad productivas necesitan infraestructura y
  operación de despliegue, aunque los puntos de instrumentación existan en la
  aplicación.

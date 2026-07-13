# Redsys Card Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activar el botón Tarjeta mediante un simulador Redsys TPV-PC configurable que registre tickets reales solo para pagos aprobados y sea sustituible por el SDK oficial.

**Architecture:** El backend define un puerto `CardTerminalGateway` y un resultado normalizado. Un adaptador simulador interpreta la configuración persistida de la terminal; un servicio POS coordina cotización, idempotencia, gateway y creación del ticket. El frontend configura el simulador y presenta un flujo de espera/resultado sin conocer cómo se obtiene la autorización.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL, React 19, TypeScript, Vitest, Vite.

## Global Constraints

- El simulador solo opera con `testMode=true`, `INTEGRATED`, `REDSYS_TPV_PC` y configuración activa.
- El resultado se elige exclusivamente desde Configuración.
- Solo `APPROVED` crea ticket y pago real.
- Rechazo, timeout y error conservan la venta y no crean ticket.
- No se almacenan datos de tarjeta sensibles.
- Un `checkoutId` no puede crear más de un ticket.
- `testMode=false` falla explícitamente hasta integrar el SDK real.
- Sin commit ni push.

---

### Task 1: Contrato y simulador Redsys

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/terminal/CardTerminalGateway.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/CardTerminalRequest.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/CardTerminalResult.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/RedsysSimulatorGateway.java`
- Create: `backend/src/test/java/com/tpverp/backend/terminal/RedsysSimulatorGatewayTest.java`

**Interfaces:**
- `CardTerminalGateway.supports(provider, testMode)`.
- `testConnection(configuration)`.
- `charge(request, configuration)` returning normalized status/reference/authorization/message.

- [ ] Escribir pruebas rojas para aprobado, rechazado, timeout, error y prohibición fuera de pruebas.
- [ ] Implementar el contrato mínimo y el adaptador determinista leyendo `simulatorOutcome`.
- [ ] Verificar que autorizaciones/referencias no contienen datos sensibles y que las pruebas pasan.

### Task 2: Configuración y prueba de conexión backend

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfiguration.java`
- Modify: `backend/src/test/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationControllerContractTest.java`

**Interfaces:**
- `POST /connection-test` sin boolean de éxito aportado por el cliente.
- `providerParameters.simulatorOutcome` limitado a los cuatro estados documentados.

- [ ] Escribir pruebas rojas para validación de resultado y test calculado por gateway.
- [ ] Inyectar selección de gateway en el servicio y eliminar la confianza en `request.success`.
- [ ] Mantener compatibilidad de lectura y comprobar tests de configuración.

### Task 3: Idempotencia persistente y servicio POS de tarjeta

**Files:**
- Create: `backend/src/main/resources/db/migration/V45__pos_card_checkout.sql`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCardCheckout.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCardCheckoutRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCardService.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCardController.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/PosCardServiceTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/PosCardControllerContractTest.java`
- Modify as needed: `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`

**Interfaces:**
- `POST /api/v1/pos/card/quote` reuses authoritative sale construction.
- `POST /api/v1/pos/card/charge` accepts `checkoutId`, sale and `quotedTotal`.
- Structured response includes status, ticket data on approval, authorization/reference when present.

- [ ] Escribir pruebas rojas para quote, validación, cada resultado, metadatos y replay idempotente.
- [ ] Extraer construcción común de venta solo si evita duplicación real con efectivo.
- [ ] Implementar transacción: reserve checkout, invoke gateway, create ticket only on approval, persist result.
- [ ] Verificar dos requests with same checkout return same result and one document.

### Task 4: Configuración de datáfono frontend

**Files:**
- Create: `frontend/packages/app-common/src/components/PaymentTerminalSettings.tsx`
- Create: `frontend/packages/app-common/src/components/PaymentTerminalSettings.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SettingsScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SettingsScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] Escribir pruebas rojas para carga, edición, guardado, cuatro resultados y test de conexión.
- [ ] Implementar consumidor tipado de la API existente sin exponer secretos.
- [ ] Integrar tarjeta Datáfono en Terminal y verificar estados loading/error/success.

### Task 5: Flujo Tarjeta en APP VENTA

**Files:**
- Create: `frontend/packages/app-common/src/components/CardPaymentDialog.tsx`
- Create: `frontend/packages/app-common/src/components/CardPaymentDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentResultDialog.tsx` or create a generic payment result component.
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] Escribir pruebas rojas para click Tarjeta, espera, aprobado, rechazo, timeout, error, reintento y cancelación.
- [ ] Implementar cotización y envío protegido por guarda síncrona con `checkoutId` estable por intento lógico.
- [ ] Mostrar resultado aprobado y conservar líneas/cliente en cualquier resultado no aprobado.
- [ ] Reutilizar el focus trap y verificar accesibilidad/teclado.

### Task 6: Verificación integral y revisión

- [ ] Ejecutar suites backend de terminal, documento, licencias, fidelización y migración.
- [ ] Ejecutar suites frontend de Settings, Sale y diálogos de pago.
- [ ] Ejecutar `mvn.cmd -DskipTests compile` y `npm.cmd run build`.
- [ ] Ejecutar `git diff --check`.
- [ ] Revisar que no existe camino que apruebe en `testMode=false` ni doble ticket por checkout.
- [ ] Revisión final independiente con subagente y corrección de hallazgos importantes.

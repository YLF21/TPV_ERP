# Cierre de aplicación y limpieza de cobros simulados Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Limpiar de forma auditada las sesiones antiguas del simulador al cerrar o entrar en Venta, sin permitir jamás esa limpieza para un datáfono real.

**Architecture:** El backend incorpora una transición terminal exclusiva de simulación y valida el modo usando la configuración persistida del terminal. `SalePaymentCheckout` solicita esa transición al preparar el apagado o recuperar una sesión antigua, y `SessionTopControls` espera un callback asíncrono antes de cerrar Electron o el navegador.

**Tech Stack:** Java 25, Spring Boot 4, JPA, JUnit 5/Mockito, React 19, TypeScript, Vitest, Testing Library, Vite.

## Global Constraints

- La limpieza forzada solo se autoriza cuando `CardTerminalConfiguration.testMode()` es `true`.
- Nunca borrar operaciones, recibos, asignaciones ni eventos de auditoría.
- Nunca limpiar de forma forzada un datáfono real, aunque el navegador lo solicite.
- Un fallo de red mantiene la aplicación abierta y conserva toda la recuperación.
- No iniciar un segundo cobro durante preparación, limpieza o recuperación.
- Tras limpiar el simulador, `/pos/payment-sessions/active` debe devolver `null` para esa sesión.
- El flujo ordinario conserva **Efectivo**, **Tarjeta** y **Pendiente cliente**.

---

### Task 1: Transición backend exclusiva de simulación

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/document/SalePaymentSession.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionController.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/SalePaymentSessionTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/SalePaymentSessionServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/SalePaymentSessionControllerContractTest.java`

**Interfaces:**
- Produces: `SalePaymentSession.discardSimulation(String reason, UUID userId)`.
- Produces: `SalePaymentSessionService.discardSimulation(UUID id, String reason, Authentication auth)`.
- Produces: `POST /api/v1/pos/payment-sessions/{id}/simulator-discard` con body `{ "reason": "application_shutdown" | "sale_entry_cleanup" }`.
- Consumes: `CardTerminalConfigurationReader.required(session.getTerminalId())` y `CardTerminalConfiguration.testMode()`.

- [ ] **Step 1: Escribir pruebas de dominio fallidas**

```java
@ParameterizedTest
@EnumSource(value = PaymentTerminalOperationStatus.class, names = {"PENDING", "TIMEOUT", "APPROVED"})
void discardsSimulatedUnfinishedAllocationsWithoutDeletingHistory(PaymentTerminalOperationStatus status) {
    var session = sessionWithIntegratedAllocation(status);
    var allocationId = session.getAllocations().getFirst().getId();
    session.discardSimulation("application_shutdown", userId);
    assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
    assertThat(session.getAllocations()).singleElement().extracting(SalePaymentAllocation::getId).isEqualTo(allocationId);
}
```

Añadir casos `COVERED` y `COMPENSATION_REQUIRED`, y comprobar que `FINALIZED` no puede descartarse.

- [ ] **Step 2: Ejecutar RED**

Run: `mvn.cmd -Dtest=SalePaymentSessionTest test`

Expected: FAIL porque `discardSimulation` no existe.

- [ ] **Step 3: Implementar la transición mínima**

`discardSimulation` normaliza el motivo, exige usuario, rechaza `FINALIZED`, conserva `allocations`, registra `compensationNote`, `compensationResolvedBy`, `compensationResolvedAt`, cambia a `CANCELLED` y actualiza `updatedAt`.

- [ ] **Step 4: Escribir pruebas de servicio fallidas**

Comprobar que:

```java
when(configurations.required(terminalId)).thenReturn(simulatedConfiguration);
assertThat(service.discardSimulation(sessionId, "application_shutdown", auth).getStatus())
    .isEqualTo(SalePaymentSessionStatus.CANCELLED);
verify(repository).save(session);
```

Y que configuración `testMode=false`, terminal ajeno o sesión inexistente lanzan excepción sin guardar.

- [ ] **Step 5: Implementar validación del servicio**

Obtener y validar primero la sesión con `scoped(...)`; cargar configuración por el `terminalId` de esa sesión; exigir `configuration.testMode()`; ejecutar la transición y guardar dentro de transacción.

- [ ] **Step 6: Añadir contrato del controlador**

```java
@PostMapping("/{id}/simulator-discard")
public View discardSimulation(@PathVariable UUID id, @Valid @RequestBody SimulatorDiscard request, Authentication auth) {
    return View.from(service.discardSimulation(id, request.reason(), auth));
}

public record SimulatorDiscard(
    @NotBlank @Pattern(regexp = "application_shutdown|sale_entry_cleanup") String reason
) {}
```

Añadir reflexión contractual del mapping y validación del body.

- [ ] **Step 7: Verificar backend focalizado**

Run: `mvn.cmd -Dtest=SalePaymentSessionTest,SalePaymentSessionServiceTest,SalePaymentSessionControllerContractTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/tpverp/backend/document backend/src/test/java/com/tpverp/backend/document
git commit -m "feat(payment): discard unfinished simulator sessions safely"
```

---

### Task 2: Preparación frontend del apagado y limpieza al entrar

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Test: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

**Interfaces:**
- Extends: `SalePaymentCheckoutHandle` con `prepareApplicationClose(): Promise<"READY" | "BLOCKED">`.
- Consumes: `POST /pos/payment-sessions/{id}/simulator-discard`.
- Produces: limpieza común de `sessionStorage`, intento persistido, guards, diálogos, total reservado y estado `server` después de `CANCELLED`.

- [ ] **Step 1: Escribir pruebas fallidas de preparación del apagado**

Para una sesión `COMPENSATION_REQUIRED`, `PENDING`, `TIMEOUT`, `APPROVED` o `COVERED`:

```ts
const result = await ref.current!.prepareApplicationClose();
expect(discardRequest).toHaveBeenCalledWith(expect.stringContaining("/simulator-discard"), expect.anything());
expect(result).toBe("READY");
expect(sessionStorage.getItem(storageKey)).toBeNull();
expect(localStorage.getItem(attemptKey)).toBeNull();
expect(onLockedChange).toHaveBeenLastCalledWith(false, undefined);
```

El 403/409 de modo real y el error de red deben devolver `BLOCKED`, conservar storage/estado y mostrar `payment.pending.shutdownBlocked`.

- [ ] **Step 2: Ejecutar RED**

Run: `npm.cmd test -- SalePaymentCheckout.test.ts`

Expected: FAIL porque el handle no contiene `prepareApplicationClose`.

- [ ] **Step 3: Implementar una limpieza local única**

Extraer una función interna que solo se ejecute tras respuesta `CANCELLED` y limpie toda la recuperación local. Reutilizarla desde cancelación normal, logout seguro, compensación y descarte simulado para evitar diferencias de comportamiento.

- [ ] **Step 4: Implementar `prepareApplicationClose()`**

Si la hidratación no es autoritativa, devolver `BLOCKED`. Sin sesión, `READY`. Para sesión segura, reutilizar cancelación normal. Para sesión insegura, llamar a `/simulator-discard` con `application_shutdown`; cerrar solo tras `CANCELLED`.

- [ ] **Step 5: Escribir prueba fallida de limpieza automática al montar**

Simular `/active` devolviendo una sesión antigua incierta, seguido de `/simulator-discard` con `CANCELLED`. Verificar que desaparecen el total reservado y los controles de recuperación. Para rechazo 403/409, mantener recuperación sin reintentos automáticos infinitos.

- [ ] **Step 6: Implementar limpieza de entrada una sola vez por sesión**

Tras hidratación autoritativa de una sesión activa insegura, solicitar `/simulator-discard` con `sale_entry_cleanup`. Usar un `Set`/ref de IDs intentados para no repetir en cada render. Si el backend rechaza por modo real, conservar la sesión y no volver a intentarlo durante ese montaje.

- [ ] **Step 7: Añadir traducciones**

Añadir `payment.pending.shutdownBlocked` y `payment.pending.simulatorCleanupError` en ES/EN/ZH; no usar fallback español hard-coded.

- [ ] **Step 8: Verificar pruebas focalizadas y commit**

Run: `npm.cmd test -- SalePaymentCheckout.test.ts`

Expected: PASS.

```bash
git add frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts
git commit -m "feat(payment): clear stale simulator checkout safely"
```

---

### Task 3: Esperar la preparación antes de cerrar la aplicación

**Files:**
- Modify: `frontend/packages/app-common/src/components/SessionTopControls.tsx`
- Test: `frontend/packages/app-common/src/components/SessionTopControls.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Test: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Produces: prop `onPrepareShutdown?: () => Promise<boolean>` en `SessionTopControls`.
- Consumes: `SalePaymentCheckoutHandle.prepareApplicationClose()` desde `SaleScreen`.

- [ ] **Step 1: Escribir pruebas fallidas del diálogo de apagado**

```ts
await user.click(screen.getByRole("button", { name: "Sí" }));
expect(onPrepareShutdown).toHaveBeenCalledTimes(1);
expect(closeApplication).not.toHaveBeenCalled();
resolvePreparation(true);
await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(1));
```

Con `false` o rechazo, no cerrar. Dos clics durante la promesa producen una preparación y un cierre.

- [ ] **Step 2: Ejecutar RED**

Run: `npm.cmd test -- SessionTopControls.test.tsx`

Expected: FAIL porque la prop no existe y el cierre es inmediato.

- [ ] **Step 3: Implementar coordinación en `SessionTopControls`**

Añadir estado/ref `shutdownPreparing`; al confirmar, esperar `onPrepareShutdown?.() ?? true`; invocar Electron/`window.close()` solo con `true`; reactivar botones en `finally` cuando no se cierra.

- [ ] **Step 4: Conectar `SaleScreen`**

Crear `handleApplicationClose()` con guard síncrono que invoque `paymentCheckoutRef.current?.prepareApplicationClose()` y devuelva booleano. Pasarlo a `SessionTopControls`.

- [ ] **Step 5: Probar integración y commit**

Run: `npm.cmd test -- SessionTopControls.test.tsx SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Expected: PASS.

```bash
git add frontend/packages/app-common/src/components/SessionTopControls.tsx frontend/packages/app-common/src/components/SessionTopControls.test.tsx frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx
git commit -m "fix(venta): prepare payment state before shutdown"
```

---

### Task 4: Verificación integral y regresión de reapertura

**Files:**
- Modify if needed: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify if needed: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify if needed: `backend/src/test/java/com/tpverp/backend/document/SalePaymentSessionServiceTest.java`

**Interfaces:**
- Validates all interfaces produced by Tasks 1–3.

- [ ] **Step 1: Añadir prueba de reapertura completa**

Montar Venta con sesión simulada antigua, esperar descarte `CANCELLED`, desmontar y montar de nuevo con `/active = null`. Verificar total `0,00`, ticket vacío, ausencia de **Cobro pendiente**/**Venta reservada en cobro** y presencia de acciones ordinarias deshabilitadas hasta añadir producto.

- [ ] **Step 2: Añadir prueba de producción**

Simular rechazo del descarte por configuración real. Verificar que el total anterior y los controles de recuperación permanecen, que cerrar aplicación no llama a Electron y que no se crea ninguna asignación nueva.

- [ ] **Step 3: Ejecutar backend completo**

Run: `mvn.cmd test`

Expected: 0 failures/errors.

- [ ] **Step 4: Ejecutar frontend completo**

Run: `npm.cmd test`

Expected: todas las pruebas pasan.

Run: `npm.cmd run build`

Expected: APP GESTIÓN y APP VENTA compilan con código 0.

Run: `git diff --check`

Expected: sin salida.

- [ ] **Step 5: Commit de pruebas finales si existen cambios**

```bash
git add backend/src/test frontend/packages/app-common/src/components
git commit -m "test(payment): verify simulator cleanup across restart"
```


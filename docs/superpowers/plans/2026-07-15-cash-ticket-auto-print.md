# Automatic Cash Ticket Printing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Imprimir automaticamente el ticket autoritativo cuando un cobro con efectivo queda confirmado, manteniendo la venta completada y ofreciendo reintento si falla el hardware.

**Architecture:** El backend construira un `TicketPrintView` comun desde el `CommercialDocument` confirmado y lo incluira en las respuestas finales de cobro directo y sesion de pagos. El frontend transformara esa instantanea en `TicketPrintRequest`, mostrara inmediatamente el resultado del pago y ejecutara la impresion local de forma asincrona. Electron resolvera de forma centralizada la impresora y las copias de la ruta `TICKET`.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5, React 19, TypeScript 5, Vitest 4, Electron 41.

## Global Constraints

- La impresion comienza solo despues de una confirmacion economica correcta.
- Un fallo de impresion nunca revierte, repite ni bloquea el cobro confirmado.
- El ticket se construye desde el documento persistido, no desde el carrito local.
- La impresion automatica respeta `documentPrintRoutes[TICKET].printAutomatically`.
- `Finalizar` permanece disponible mientras se imprime y despues de cualquier fallo.
- `Reintentar impresion` solo vuelve a llamar al puente de hardware.
- Los textos nuevos se incorporan a ES, EN y ZH.
- No se introduce una cola persistente de impresion en esta fase.

---

### Task 1: Instantanea autoritativa de ticket en las respuestas de cobro

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/document/TicketPrintView.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/TicketPrintViewTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/CommercialDocument.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/SalePaymentSessionControllerContractTest.java`

**Interfaces:**
- Produces: `TicketPrintView.from(CommercialDocument)`.
- Produces: `PosCashService.Result(..., TicketPrintView printTicket)`.
- Produces: `SalePaymentSessionController.View(..., TicketPrintView printTicket)` solamente con valor en la respuesta de finalizacion.
- Consumes: `CommercialDocument.getConfirmadoEn()`, lineas ordenadas y pagos ordenados del documento confirmado.

- [ ] **Step 1: Write the failing snapshot test**

Crear `TicketPrintViewTest` con un documento confirmado que contenga una linea y un pago. La asercion debe demostrar que el DTO copia los valores calculados del documento, no los valores de una solicitud externa:

```java
@Test
void buildsAuthoritativePrintableSnapshotFromConfirmedTicket() {
    var companyId = UUID.randomUUID();
    var document = new CommercialDocument(
            UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
            LocalDate.of(2026, 7, 15), UUID.randomUUID(), BigDecimal.ZERO);
    document.addLine(new DocumentLine(
            document, UUID.randomUUID(), 1, BigDecimal.valueOf(2), "A-1", "Cafe",
            null, BigDecimal.valueOf(3.50), BigDecimal.ZERO, true,
            "IVA", BigDecimal.valueOf(21)));
    document.confirm("001-260715-000001", UUID.randomUUID(),
            Instant.parse("2026-07-15T10:15:30Z"), false);
    var cash = new PaymentMethod(companyId, "EFECTIVO", true);
    document.addPayment(new DocumentPayment(
            document, cash, 1, BigDecimal.valueOf(7), true,
            BigDecimal.TEN, BigDecimal.valueOf(3), Instant.parse("2026-07-15T10:15:30Z")));

    var view = TicketPrintView.from(document);

    assertThat(view.documentNumber()).isEqualTo("001-260715-000001");
    assertThat(view.issuedAt()).isEqualTo(Instant.parse("2026-07-15T10:15:30Z"));
    assertThat(view.lines()).singleElement().satisfies(line -> {
        assertThat(line.name()).isEqualTo("Cafe");
        assertThat(line.quantity()).isEqualByComparingTo("2");
        assertThat(line.price()).isEqualByComparingTo("3.50");
        assertThat(line.total()).isEqualByComparingTo("7.00");
    });
    assertThat(view.payments()).singleElement().satisfies(payment -> {
        assertThat(payment.method()).isEqualTo("EFECTIVO");
        assertThat(payment.amount()).isEqualByComparingTo("7.00");
    });
    assertThat(view.total()).isEqualByComparingTo("7.00");
}
```

- [ ] **Step 2: Run the backend test and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=TicketPrintViewTest test
```

Expected: compilation failure because `TicketPrintView` and `getConfirmadoEn()` do not exist.

- [ ] **Step 3: Implement the common backend DTO**

Añadir `getConfirmadoEn()` a `CommercialDocument` y crear el DTO:

```java
public record TicketPrintView(
        UUID documentId,
        String documentNumber,
        Instant issuedAt,
        List<Line> lines,
        List<Payment> payments,
        BigDecimal total) {

    public static TicketPrintView from(CommercialDocument document) {
        if (document.getEstado() != DocumentStatus.CONFIRMADO || document.getConfirmadoEn() == null) {
            throw new IllegalArgumentException("print_ticket_requires_confirmed_document");
        }
        return new TicketPrintView(
                document.getId(), document.getNumero(), document.getConfirmadoEn(),
                document.getLineas().stream()
                        .map(line -> new Line(line.getNombre(), line.getCantidad(),
                                line.getPrecioUnitario(), line.getTotal()))
                        .toList(),
                document.getPagos().stream()
                        .map(payment -> new Payment(payment.getMetodoPago().getNombre(), payment.getImporte()))
                        .toList(),
                document.getTotal());
    }

    public record Line(String name, BigDecimal quantity, BigDecimal price, BigDecimal total) {}
    public record Payment(String method, BigDecimal amount) {}
}
```

- [ ] **Step 4: Return the snapshot from direct cash**

Ampliar el resultado y construirlo desde la misma entidad confirmada:

```java
return new Result(ticket.getId(), ticket.getNumero(), total, received, change,
        TicketPrintView.from(ticket));

public record Result(
        UUID id,
        String number,
        BigDecimal total,
        BigDecimal received,
        BigDecimal change,
        TicketPrintView printTicket) {}
```

- [ ] **Step 5: Write the failing finalized-session contract test**

Añadir a `SalePaymentSessionControllerContractTest` una prueba que simule una sesion finalizada y compruebe que `finalizeSession` usa la instantanea devuelta por el servicio y que las vistas de reserva/recuperacion no la inventan:

```java
@Test
void finalizeResponseCarriesTheConfirmedTicketSnapshot() {
    var viewComponents = SalePaymentSessionController.View.class.getRecordComponents();
    assertThat(Arrays.stream(viewComponents).map(RecordComponent::getName))
            .contains("printTicket");
    assertThat(viewComponents[viewComponents.length - 1].getType())
            .isEqualTo(TicketPrintView.class);
}
```

- [ ] **Step 6: Run the contract test and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=SalePaymentSessionControllerContractTest#finalizeResponseCarriesTheConfirmedTicketSnapshot test
```

Expected: FAIL because `View` has no `printTicket` component.

- [ ] **Step 7: Add the snapshot to finalization only**

Crear en `SalePaymentSessionService` un resultado explicito que conserve la sesion y el documento retornado por la finalizacion:

```java
public record Finalization(SalePaymentSession session, TicketPrintView printTicket) {}
```

Cambiar `finalizeSession` para devolver `Finalization`. En el camino idempotente, cargar el documento por `ticketId` mediante un metodo transaccional de `DocumentService`; en el camino nuevo, construir la vista desde el `ticket` que acaba de crear. En el controlador, añadir una sobrecarga:

```java
@PostMapping("/{id}/finalize")
public View finalizeSession(@PathVariable UUID id, Authentication auth) {
    var finalized = service.finalizeSession(id, auth);
    return View.from(finalized.session(), finalized.printTicket());
}

public record View(
        UUID id, BigDecimal total, String currency, String status,
        UUID ticketId, String ticketNumber, List<AllocationView> allocations,
        TicketPrintView printTicket) {
    static View from(SalePaymentSession session) {
        return from(session, null);
    }

    static View from(SalePaymentSession session, TicketPrintView printTicket) {
        return new View(session.getId(), session.getTotal(), session.getCurrency(),
                session.getStatus().name(), session.getTicketId(), session.getTicketNumber(),
                session.getAllocations().stream().map(AllocationView::from).toList(), printTicket);
    }
}
```

- [ ] **Step 8: Run focused and package backend tests**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=TicketPrintViewTest,SalePaymentSessionControllerContractTest,SalePaymentSessionServiceTest,PosCashServiceTransactionContractTest test
```

Expected: all selected tests PASS.

- [ ] **Step 9: Commit task 1**

```powershell
git add backend/src/main/java/com/tpverp/backend/document backend/src/test/java/com/tpverp/backend/document
git commit -m "feat(payment): return confirmed ticket print snapshot"
```

---

### Task 2: Resolver impresora y copias de la ruta TICKET en Electron

**Files:**
- Create: `frontend/desktop/ticket-print-route.cjs`
- Create: `frontend/desktop/ticket-print-route.test.mjs`
- Modify: `frontend/desktop/main.cjs`

**Interfaces:**
- Produces: `resolveTicketPrintRoute(config): { printerName: string; copies: number; printAutomatically: boolean }`.
- Consumes: `HardwareConfig.documentPrintRoutes` y `ticketPrinterName` como fallback.

- [ ] **Step 1: Write failing pure route tests**

```javascript
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { resolveTicketPrintRoute } = require("./ticket-print-route.cjs");

test("uses the configured TICKET route printer and copies", () => {
  assert.deepEqual(resolveTicketPrintRoute({
    ticketPrinterName: "Legacy",
    documentPrintRoutes: [{
      documentType: "TICKET",
      printerName: "Caja 1",
      copies: 2,
      printAutomatically: true
    }]
  }), { printerName: "Caja 1", copies: 2, printAutomatically: true });
});

test("falls back to the legacy printer and normalizes copies", () => {
  assert.deepEqual(resolveTicketPrintRoute({
    ticketPrinterName: "Legacy",
    documentPrintRoutes: [{ documentType: "TICKET", printerName: "", copies: 0, printAutomatically: true }]
  }), { printerName: "Legacy", copies: 1, printAutomatically: true });
});
```

- [ ] **Step 2: Run the Electron unit test and verify RED**

Run:

```powershell
cd frontend
node --test desktop/ticket-print-route.test.mjs
```

Expected: FAIL because `ticket-print-route.cjs` does not exist.

- [ ] **Step 3: Implement the pure resolver**

```javascript
function resolveTicketPrintRoute(config = {}) {
  const route = (config.documentPrintRoutes || []).find((item) => item.documentType === "TICKET");
  return {
    printerName: String(route?.printerName || config.ticketPrinterName || ""),
    copies: Math.max(1, Number.isFinite(Number(route?.copies)) ? Math.trunc(Number(route.copies)) : 1),
    printAutomatically: route?.printAutomatically !== false
  };
}

module.exports = { resolveTicketPrintRoute };
```

- [ ] **Step 4: Apply the resolver in `printTicket`**

Importar el resolver en `main.cjs`. Para Windows, usar `deviceName` y `copies` en `webContents.print`. Para ESC/POS, enviar el buffer del ticket el numero indicado de veces, pero incluir la orden de apertura de cajon solo en el primer envio:

```javascript
const route = resolveTicketPrintRoute(nextConfig);
const printerName = route.printerName;

printWindow.webContents.print({
  silent: true,
  deviceName: printerName,
  copies: route.copies,
  printBackground: true
}, callback);
```

- [ ] **Step 5: Run desktop tests**

Run:

```powershell
cd frontend
node --test desktop/ticket-print-route.test.mjs desktop/escpos.test.mjs
```

Expected: all tests PASS.

- [ ] **Step 6: Commit task 2**

```powershell
git add frontend/desktop/main.cjs frontend/desktop/ticket-print-route.cjs frontend/desktop/ticket-print-route.test.mjs
git commit -m "feat(hardware): honor ticket printer route and copies"
```

---

### Task 3: Orquestador frontend de impresion segura

**Files:**
- Create: `frontend/packages/app-common/src/sale/ticketPrinting.ts`
- Create: `frontend/packages/app-common/src/sale/ticketPrinting.test.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

**Interfaces:**
- Produces: `ConfirmedTicketPrintSnapshot` equivalente al JSON de `TicketPrintView`.
- Produces: `printConfirmedTicketAutomatically(snapshot, terminal, hardware)`.
- Produces: `retryConfirmedTicketPrint(snapshot, terminal, hardware)`.
- Produces: `TicketPrintOutcome = { status: "PRINTED" | "FAILED" | "SKIPPED"; technicalMessage?: string }`.

- [ ] **Step 1: Write failing orchestration tests**

Probar tres comportamientos con un `HardwareBridge` inyectado: impresion habilitada, omision configurada y fallo estructurado. La prueba de exito debe comprobar el request completo:

```typescript
it("prints the authoritative snapshot when automatic ticket printing is enabled", async () => {
  const printTicket = vi.fn().mockResolvedValue({ ok: true });
  const hardware = {
    getHardwareConfig: vi.fn().mockResolvedValue({
      ...defaultHardwareConfig,
      documentPrintRoutes: defaultHardwareConfig.documentPrintRoutes.map(route =>
        route.documentType === "TICKET" ? { ...route, printAutomatically: true } : route)
    }),
    printTicket
  } as HardwareBridge;

  const result = await printConfirmedTicketAutomatically(snapshot, terminal, hardware);

  expect(result).toEqual({ status: "PRINTED" });
  expect(printTicket).toHaveBeenCalledWith({
    documentNumber: "T-1",
    storeName: "Tienda",
    terminalCode: "CAJA-1",
    issuedAt: "2026-07-15T10:15:30Z",
    lines: [{ name: "Cafe", quantity: 2, price: 3.5, total: 7 }],
    payments: [{ method: "EFECTIVO", amount: 7 }],
    total: 7
  }, expect.objectContaining({ documentPrintRoutes: expect.any(Array) }));
});
```

La prueba de reintento debe llamar a `printTicket` aunque la ruta haya cambiado a `printAutomatically: false`, porque el usuario ha solicitado la impresion manualmente.

- [ ] **Step 2: Run the frontend test and verify RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/sale/ticketPrinting.test.ts
```

Expected: FAIL because `ticketPrinting.ts` does not exist.

- [ ] **Step 3: Implement the printing boundary**

Crear tipos exactos y dos funciones. La automatica consulta `printAutomatically`; el reintento imprime siempre. Ambas convierten numeros recibidos como `number | string` mediante `Number(...)` y convierten cualquier rechazo o `HardwareResult.ok === false` en `FAILED`, sin lanzar al flujo de cobro:

```typescript
export async function printConfirmedTicketAutomatically(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<TicketPrintOutcome> {
  try {
    const config = await hardware.getHardwareConfig();
    const route = config.documentPrintRoutes.find(item => item.documentType === "TICKET");
    if (route?.printAutomatically === false) return { status: "SKIPPED" };
    return await sendConfirmedTicket(snapshot, terminal, hardware, config);
  } catch (error) {
    return { status: "FAILED", technicalMessage: error instanceof Error ? error.message : String(error) };
  }
}

export async function retryConfirmedTicketPrint(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<TicketPrintOutcome> {
  try {
    return await sendConfirmedTicket(snapshot, terminal, hardware, await hardware.getHardwareConfig());
  } catch (error) {
    return { status: "FAILED", technicalMessage: error instanceof Error ? error.message : String(error) };
  }
}
```

- [ ] **Step 4: Add translated result messages**

Añadir las mismas claves a los tres catalogos:

```typescript
"payment.result.printing": "Imprimiendo ticket...",
"payment.result.printed": "Ticket enviado a la impresora",
"payment.result.printFailed": "El cobro se ha completado, pero no ha sido posible imprimir el ticket.",
"payment.result.retryPrint": "Reintentar impresión",
```

Usar traducciones equivalentes naturales en EN y ZH, conservando exactamente las mismas claves.

- [ ] **Step 5: Run focused tests and type build**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/sale/ticketPrinting.test.ts packages/app-common/src/i18n/LocalizedMessages.test.ts
npm.cmd run build --workspace @tpverp/app-venta
```

Expected: tests and APP VENTA build PASS.

- [ ] **Step 6: Commit task 3**

```powershell
git add frontend/packages/app-common/src/sale frontend/packages/app-common/src/i18n
git commit -m "feat(venta): add safe confirmed ticket printing"
```

---

### Task 4: Integrar estados, reintento y ambos flujos de cobro

**Files:**
- Modify: `frontend/packages/app-common/src/components/CashPaymentResultDialog.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- `CashPaymentResultDialog` consume `locale`, `printStatus` y `onRetryPrint`.
- `SalePaymentCheckout.onFinalized` pasa la instantanea completa y el resumen del metodo.
- `SaleScreen` abre el resultado primero y actualiza el estado de impresion despues.

- [ ] **Step 1: Write failing result-dialog tests**

Añadir pruebas que rendericen `PRINTING`, `PRINTED` y `FAILED`. Para `FAILED`, exigir el aviso, el boton de reintento y `Finalizar` simultaneamente:

```typescript
it("keeps payment completed and offers print retry after hardware failure", () => {
  const html = renderToStaticMarkup(
    <CashPaymentResultDialog
      locale="es"
      ticketNumber="T-0042"
      totalCents={1543}
      printStatus="FAILED"
      onRetryPrint={vi.fn()}
      onFinish={vi.fn()}
    />,
  );

  expect(html).toContain("Pago completado");
  expect(html).toContain("El cobro se ha completado, pero no ha sido posible imprimir el ticket.");
  expect(html).toContain("Reintentar impresión");
  expect(html).toContain("Finalizar");
});
```

- [ ] **Step 2: Run dialog tests and verify RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/CashPaymentResultDialog.test.tsx
```

Expected: FAIL because the print props and retry UI do not exist.

- [ ] **Step 3: Implement result print states**

Añadir:

```typescript
type TicketPrintUiStatus = "PRINTING" | "PRINTED" | "FAILED" | "SKIPPED";
```

Renderizar un bloque `role="status"` para `PRINTING`/`PRINTED`, `role="alert"` para `FAILED` y un boton secundario que invoque `onRetryPrint`. No renderizar texto en `SKIPPED`. Mantener el boton `Finalizar` activo y con el foco inicial.

- [ ] **Step 4: Write failing checkout callback test**

Actualizar el contrato de `ServerSession` para incluir `printTicket?: ConfirmedTicketPrintSnapshot` y exigir que una respuesta finalizada invoque:

```typescript
expect(onFinalized).toHaveBeenCalledWith(finalized.printTicket, expectedSummary);
```

Si `ticketNumber` existe pero `printTicket` falta, el checkout debe mostrar el error de finalizacion y no limpiar silenciosamente la sesion recuperable.

- [ ] **Step 5: Run checkout test and verify RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.tsx
```

Expected: FAIL because `onFinalized` still emits only the ticket number.

- [ ] **Step 6: Pass the authoritative snapshot through checkout**

Cambiar el callback a:

```typescript
onFinalized: (
  printTicket: ConfirmedTicketPrintSnapshot,
  summary: PaymentFinalizationSummary,
) => void;
```

Validar que la respuesta `FINALIZED` tenga `ticketNumber` y `printTicket` antes de limpiar almacenamiento y estado de sesion.

- [ ] **Step 7: Write failing SaleScreen integration tests**

Cubrir por separado:

1. cobro directo: la respuesta `/pos/cash` abre el resultado con `PRINTING` antes de resolver `printTicket`;
2. flujo de sesion efectivo: `onFinalized(snapshot, CASH)` usa el mismo orquestador;
3. tarjeta pura: no inicia esta impresion automatica de ticket porque queda fuera del alcance aprobado;
4. fallo: conserva `Pago completado`, cambia a `FAILED` y no repite `/pos/cash`;
5. reintento: incrementa llamadas a `hardware.printTicket`, no llamadas de cobro.

Inyectar `window.tpvDesktop.hardware` con promesas controladas y restaurarlo en `afterEach`.

- [ ] **Step 8: Run SaleScreen tests and verify RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/SaleScreen.test.tsx
```

Expected: FAIL because the successful transitions do not invoke the ticket printer.

- [ ] **Step 9: Wire printing after both successful cash transitions**

Extender `CashPaymentResponse` con `printTicket`. Al confirmar:

```typescript
setCashResult({ ...confirmedResult, printTicket: result.printTicket, printStatus: "PRINTING" });
void printConfirmedTicketAutomatically(result.printTicket, terminalContext)
  .then(outcome => updateMatchingPrintOutcome(result.printTicket.documentId, outcome));
```

En `SalePaymentCheckout.onFinalized`, ejecutar la misma transicion solo para
`summary.kind === "CASH" || summary.kind === "MIXED"`. Implementar
`onRetryPrint` con `retryConfirmedTicketPrint` y actualizar primero el estado a
`PRINTING`. Comparar `documentId` al resolver promesas para que una respuesta
tardia no altere otro ticket ni reabra una ventana cerrada.

- [ ] **Step 10: Style the status and retry action**

Añadir reglas compactas al dialogo ERP existente: una fila de estado de altura
reducida, alerta roja solo en `FAILED` y boton secundario rectangular. No
cambiar ancho, cabecera, resumen economico ni boton principal.

- [ ] **Step 11: Run all focused frontend tests**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/CashPaymentResultDialog.test.tsx packages/app-common/src/components/SalePaymentCheckout.test.tsx packages/app-common/src/components/SaleScreen.test.tsx packages/app-common/src/sale/ticketPrinting.test.ts
```

Expected: all selected tests PASS.

- [ ] **Step 12: Commit task 4**

```powershell
git add frontend/packages/app-common/src/components frontend/packages/app-common/src/styles/tpv.css
git commit -m "feat(venta): print ticket after confirmed cash payment"
```

---

### Task 5: Verificacion completa y revision

**Files:**
- Verify only; modify a file only if a failing test exposes a defect in the approved scope.

**Interfaces:**
- Consumes all deliverables from Tasks 1-4.
- Produces a clean, reviewed working tree with evidence from backend, frontend and Electron.

- [ ] **Step 1: Run complete backend tests**

```powershell
cd backend
.\mvnw.cmd test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run complete frontend tests and builds**

```powershell
cd frontend
npm.cmd test
npm.cmd run build
```

Expected: every Vitest file PASS and both workspaces build successfully.

- [ ] **Step 3: Run Electron pure tests**

```powershell
cd frontend
node --test desktop/ticket-print-route.test.mjs desktop/escpos.test.mjs
```

Expected: all Node tests PASS.

- [ ] **Step 4: Review the end-to-end invariants**

Confirmar por lectura y pruebas que:

- ninguna funcion de reintento llama a `/pos/cash` ni `/finalize`;
- `Pago completado` se establece antes de esperar al hardware;
- `FAILED` conserva `Finalizar`;
- las respuestas tardias se ignoran si el resultado fue cerrado;
- tarjeta pura conserva el flujo previo;
- ruta, impresora, copias y configuracion automatica se respetan.

- [ ] **Step 5: Check repository cleanliness**

```powershell
git diff --check
git status --short
git log -8 --oneline
```

Expected: no whitespace errors and no uncommitted implementation files.

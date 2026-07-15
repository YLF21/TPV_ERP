# Tarea 1 — Instantánea autoritativa de ticket en respuestas de cobro

## Estado

Implementación completada en el worktree `cash-ticket-auto-print`, rama
`codex/cash-ticket-auto-print`, partiendo de `a72202b54afba95b53e03d162ef5788056729eac`.

## Implementación

- Se añadió `TicketPrintView`, un DTO inmutable que se construye exclusivamente desde un
  `CommercialDocument` confirmado. Copia identificador, número fiscal, instante de confirmación,
  líneas ordenadas, pagos ordenados y total calculado del documento.
- Se expuso `CommercialDocument.getConfirmadoEn()` para usar el instante fiscal ya persistido.
- El cobro directo en efectivo devuelve `printTicket` creado desde el mismo ticket confirmado que
  acaba de persistir `DocumentService`.
- La finalización de sesiones devuelve el resultado explícito
  `SalePaymentSessionService.Finalization(session, printTicket)`.
- En una finalización nueva, la instantánea se crea desde el ticket recién creado. En una
  repetición idempotente, `DocumentService.loadForPrint(UUID)` recarga transaccionalmente el ticket
  vinculado, respetando el ámbito de tienda, antes de crear la instantánea.
- `SalePaymentSessionController.View` incorpora `printTicket`; las vistas normales usan `null` y
  únicamente el endpoint de finalización emplea la sobrecarga que lo rellena.
- Se adaptó el test PostgreSQL de concurrencia al nuevo resultado explícito y a los campos que
  requiere la instantánea del ticket simulado.

## Archivos

### Producción

- `backend/src/main/java/com/tpverp/backend/document/TicketPrintView.java` (nuevo)
- `backend/src/main/java/com/tpverp/backend/document/CommercialDocument.java`
- `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`
- `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionController.java`
- `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionService.java`

### Pruebas

- `backend/src/test/java/com/tpverp/backend/document/TicketPrintViewTest.java` (nuevo)
- `backend/src/test/java/com/tpverp/backend/document/SalePaymentSessionControllerContractTest.java`
- `backend/src/test/java/com/tpverp/backend/document/SalePaymentFinalizeConcurrencyPostgreSqlTest.java`

## Evidencia TDD

### RED 1 — instantánea autoritativa

Comando equivalente al prescrito (se usó Maven instalado por la incidencia del wrapper descrita
abajo):

```powershell
E:\apache-maven-3.9.10\bin\mvn.cmd -Dtest=TicketPrintViewTest test
```

Salida relevante:

```text
[ERROR] TicketPrintViewTest.java:[31,20] cannot find symbol
  symbol: variable TicketPrintView
[INFO] BUILD FAILURE
```

Después de añadir el DTO y `getConfirmadoEn()`:

```text
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### RED 2 — resultado de caja directa

Se añadió una prueba de contrato mínima antes de ampliar `PosCashService.Result`:

```powershell
E:\apache-maven-3.9.10\bin\mvn.cmd "-Dtest=TicketPrintViewTest#directCashResultCarriesConfirmedTicketSnapshot" test
```

Salida relevante:

```text
[ERROR] constructor Result ... cannot be applied to given types
  required: UUID,String,BigDecimal,BigDecimal,BigDecimal
  found: UUID,String,BigDecimal,BigDecimal,BigDecimal,TicketPrintView
[INFO] BUILD FAILURE
```

Después de ampliar el resultado y construirlo desde el ticket confirmado:

```text
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### RED 3 — contrato de finalización

```powershell
E:\apache-maven-3.9.10\bin\mvn.cmd "-Dtest=SalePaymentSessionControllerContractTest#finalizeResponseCarriesTheConfirmedTicketSnapshot" test
```

Salida relevante:

```text
Expecting ["id", "total", "currency", "status", "ticketId", "ticketNumber", "allocations"]
to contain ["printTicket"]
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Después de añadir `Finalization`, el camino idempotente y la sobrecarga de `View`:

```text
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

La prueba de contrato también verifica que el controlador propaga la misma instantánea devuelta
por el servicio al finalizar y que una recuperación normal mantiene `printTicket` a `null`.

## Verificación GREEN

Conjunto enfocado del brief:

```powershell
E:\apache-maven-3.9.10\bin\mvn.cmd "-Dtest=TicketPrintViewTest,SalePaymentSessionControllerContractTest,SalePaymentSessionServiceTest,PosCashServiceTransactionContractTest" test
```

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Suite backend completa, ejecutada después del self-review:

```powershell
E:\apache-maven-3.9.10\bin\mvn.cmd test
```

```text
Tests run: 1183, Failures: 0, Errors: 0, Skipped: 27
BUILD SUCCESS
Total time: 01:18 min
```

Los 27 tests omitidos están condicionados por variables/perfiles de integración y no representan
fallos. La suite sí ejecutó las pruebas PostgreSQL habilitadas por el entorno local.

## Self-review

- Contrato revisado contra el brief: nombres y tipos de los records coinciden; `printTicket` es el
  último componente de `View`.
- Autoridad de datos revisada: ninguna solicitud externa aporta número, instante, líneas, pagos o
  total del DTO; todos proceden del documento confirmado.
- Orden revisado: `CommercialDocument` ya garantiza `@OrderBy("posicion")` para líneas y pagos y
  el DTO conserva el orden de las listas.
- Idempotencia revisada: una sesión ya finalizada no crea otro documento; recarga el ticket por
  `ticketId` dentro de la transacción y devuelve su instantánea.
- Alcance revisado: no se modificó el checkout principal, no se implementaron tareas frontend ni
  tareas posteriores, y `git diff --check` no detectó errores de whitespace.
- Compatibilidad de pruebas revisada: se adaptó el test de concurrencia que consumía directamente
  el antiguo retorno `SalePaymentSession`.

## Preocupaciones / incidencias

- `backend/mvnw.cmd` falla antes de iniciar Maven por un error del wrapper al indexar
  `(Get-Item $MAVEN_M2_PATH).Target[0]` cuando `Target` es nulo (`NullArray`, seguido de
  `Cannot start maven from wrapper`). No se modificó el wrapper por quedar fuera de esta tarea; se
  ejecutó el mismo ciclo con la instalación existente `E:\apache-maven-3.9.10\bin\mvn.cmd`.
- Maven/JDK emiten avisos preexistentes sobre APIs deprecadas, auto-attach de Mockito y acceso
  nativo futuro. No hubo warnings atribuibles al contrato implementado ni fallos de compilación.

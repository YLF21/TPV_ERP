# VERI*FACTU Subsanaciones Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear subsanaciones fiscales administrativas inmutables, encadenadas, enviables y trazables sin permitir cambios economicos.

**Architecture:** Un servicio de aplicacion cargara el registro defectuoso dentro del tenant, clonara su snapshot y aplicara unicamente destinatario y descripcion. `FiscalRecordService` encapsulara la insercion encadenada; el generador XML interpretara los indicadores de subsanacion y el coordinador de respuestas marcara el original como subsanado solo tras aceptacion AEAT.

**Tech Stack:** Java 25, Spring Boot 4, Spring Security, JPA/Hibernate, PostgreSQL 18, Flyway, JUnit 5, Mockito, AssertJ.

---

### Task 1: Contrato y validacion administrativa

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalCorrectionRequest.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalCorrectionSnapshot.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalCorrectionSnapshotTest.java`
- Modify: `backend/src/main/resources/i18n/messages_es.properties`
- Modify: `backend/src/main/resources/i18n/messages_en.properties`
- Modify: `backend/src/main/resources/i18n/messages_zh.properties`

- [ ] **Step 1: Escribir pruebas que exijan motivo, al menos una correccion, NIF valido, nombre y descripcion con limites del XSD**

```java
assertThatThrownBy(() -> corrections.apply(original, new FiscalCorrectionRequest(" ", null, null, null), userId))
        .isInstanceOf(IllegalArgumentException.class);
assertThat(corrected.get("baseTotal")).isEqualTo(original.get("baseTotal"));
assertThat(((Map<?, ?>) corrected.get("cliente")).get("numeroDocumento"))
        .isEqualTo("B12345674");
```

- [ ] **Step 2: Ejecutar la prueba y comprobar RED**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionSnapshotTest test`
Expected: FAIL porque los tipos de subsanacion aun no existen.

- [ ] **Step 3: Implementar DTO y constructor de snapshot corregido**

`FiscalCorrectionRequest` aceptara `reason`, `recipientTaxId`, `recipientName` y `operationDescription`. `FiscalCorrectionSnapshot.apply(...)` copiara el snapshot inmutable, cambiara solo `cliente.numeroDocumento`, `cliente.nombreFiscal` y `descripcionOperacion`, y anadira `subsanacion`, `rechazoPrevio`, `subsanacionMotivo`, `subsanacionUsuarioId`, `subsanacionRegistroId` y `subsanacionFecha`.

- [ ] **Step 4: Centralizar los nuevos errores en los tres idiomas y ejecutar GREEN**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionSnapshotTest test`
Expected: PASS.

### Task 2: Creacion fiscal encadenada

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecord.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordRelationRepository.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalRecordServiceTest.java`

- [ ] **Step 1: Escribir pruebas de registro duplicado permitido solo como subsanacion, relacion `SUBSANA` e invariabilidad economica**

```java
var correction = service.registerCorrection(original, correctedSnapshot);
assertThat(correction.getNumber()).isEqualTo(original.getNumber());
assertThat(correction.getTotalAmount()).isEqualByComparingTo(original.getTotalAmount());
verify(relations).save(argThat(value -> value.getType() == FiscalRelationType.SUBSANA));
```

- [ ] **Step 2: Ejecutar la prueba y comprobar RED**

Run: `mvnw.cmd -q -Dtest=FiscalRecordServiceTest test`
Expected: FAIL porque `registerCorrection` y los accesores de relacion no existen.

- [ ] **Step 3: Implementar insercion dentro de la cadena bloqueada**

`registerCorrection` validara que el original sea `ALTA`, conservara identidad, tipo, importes y versiones, recalculara huella y hash del snapshot, insertara estado `PENDIENTE`, relacion `SUBSANA` y avanzara la cadena. La huella usara `AltaHashInput` con el nuevo instante y la huella anterior activa.

- [ ] **Step 4: Ejecutar GREEN y refactorizar la insercion compartida sin cambiar comportamiento**

Run: `mvnw.cmd -q -Dtest=FiscalRecordServiceTest test`
Expected: PASS.

### Task 3: Servicio, tenant, permisos y API

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalCorrectionService.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalCorrectionView.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalCorrectionServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/DefectiveFiscalRecordController.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuControllerContractTest.java`

- [ ] **Step 1: Escribir pruebas de tenant, estados permitidos, motivo, usuario y evento posterior al commit**

```java
assertThat(service.correct(recordId, request, authentication).status())
        .isEqualTo(FiscalSubmissionStatus.PENDIENTE);
verify(events).publishEvent(new FiscalRecordQueuedEvent(createdId));
```

Tambien se comprobara el rechazo de `ACEPTADO`, `PENDIENTE`, anulaciones, registros de otra tienda y una segunda subsanacion tras aceptacion.

- [ ] **Step 2: Ejecutar las pruebas y comprobar RED**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionServiceTest,VerifactuControllerContractTest test`
Expected: FAIL porque no existe el servicio ni `POST /{recordId}/corrections`.

- [ ] **Step 3: Implementar servicio y endpoint**

```java
@PostMapping("/{recordId}/corrections")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public FiscalCorrectionView correct(@PathVariable UUID recordId,
        @Valid @RequestBody FiscalCorrectionRequest request,
        Authentication authentication) {
    return service.correct(recordId, request, authentication);
}
```

El servicio cargara mediante `findByIdAndCompanyIdAndStoreId`, comprobara el estado mutable, resolvera el usuario con `CurrentOrganization`, registrara la subsanacion y publicara `FiscalRecordQueuedEvent` dentro de la transaccion.

- [ ] **Step 4: Ejecutar GREEN**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionServiceTest,VerifactuControllerContractTest test`
Expected: PASS.

### Task 4: XML oficial de subsanacion

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuXmlService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuXmlServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuOfficialXsdValidatorTest.java`

- [ ] **Step 1: Escribir pruebas del orden XSD y contenido corregido**

```java
assertThat(xml).containsSubsequence(
        "<sf:NombreRazonEmisor>", "<sf:Subsanacion>S</sf:Subsanacion>",
        "<sf:RechazoPrevio>S</sf:RechazoPrevio>", "<sf:TipoFactura>");
assertThat(xml).contains("<sf:Destinatarios>")
        .contains("<sf:NIF>B12345674</sf:NIF>")
        .contains("<sf:DescripcionOperacion>Venta corregida</sf:DescripcionOperacion>");
validator.validate(xml);
```

- [ ] **Step 2: Ejecutar las pruebas y comprobar RED**

Run: `mvnw.cmd -q -Dtest=VerifactuXmlServiceTest,VerifactuOfficialXsdValidatorTest test`
Expected: FAIL por ausencia de indicadores y destinatario.

- [ ] **Step 3: Implementar serializacion oficial**

El alta escribira `Subsanacion` y `RechazoPrevio` despues de emisor, utilizara `descripcionOperacion` con reserva `Venta`, y serializara `Destinatarios/IDDestinatario` despues de la descripcion. El destinatario espanol usara `NombreRazon` y `NIF`.

- [ ] **Step 4: Ejecutar GREEN contra XSD oficial**

Run: `mvnw.cmd -q -Dtest=VerifactuXmlServiceTest,VerifactuOfficialXsdValidatorTest test`
Expected: PASS.

### Task 5: Cierre de estado del registro original

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__subsanaciones_verifactu.sql`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalCorrectionCompletionService.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalCorrectionCompletionServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalSubmissionStatus.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalSubmissionStateService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuSubmissionService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuSubmissionServiceTest.java`

- [ ] **Step 1: Escribir pruebas de transicion tras respuesta AEAT**

```java
completion.accepted(correction);
verify(states).markSubsanado(originalId);
completion.rejected(correction);
verify(states, never()).markSubsanado(any());
```

- [ ] **Step 2: Ejecutar las pruebas y comprobar RED**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionCompletionServiceTest,VerifactuSubmissionServiceTest test`
Expected: FAIL porque falta `SUBSANADO` y el coordinador.

- [ ] **Step 3: Implementar estado y migracion**

La migracion ampliara el `CHECK` de `estado_envio_fiscal` con `SUBSANADO`. El coordinador consultara la relacion `SUBSANA` y marcara el original solo para respuestas `ACEPTADO`; una subsanacion rechazada conservara ambos registros en defectuosos.

- [ ] **Step 4: Ejecutar GREEN**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionCompletionServiceTest,VerifactuSubmissionServiceTest test`
Expected: PASS.

### Task 6: Verificacion integrada reducida

**Files:**
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalChainPostgreSqlTest.java`

- [ ] **Step 1: Añadir una prueba PostgreSQL del flujo esencial**

La prueba insertara alta, rechazo, subsanacion y aceptacion; verificara dos registros inmutables, secuencias consecutivas, relacion `SUBSANA` y estado original `SUBSANADO`.

- [ ] **Step 2: Ejecutar el conjunto VERI*FACTU focalizado**

Run: `mvnw.cmd -q -Dtest=FiscalCorrectionSnapshotTest,FiscalRecordServiceTest,FiscalCorrectionServiceTest,VerifactuControllerContractTest,VerifactuXmlServiceTest,VerifactuOfficialXsdValidatorTest,FiscalCorrectionCompletionServiceTest,VerifactuSubmissionServiceTest,FiscalChainPostgreSqlTest test`
Expected: PASS.

- [ ] **Step 3: Ejecutar compilacion y suite completa una sola vez**

Run: `mvnw.cmd -q test`
Expected: PASS con PostgreSQL local de pruebas.

- [ ] **Step 4: Comprobar calidad del diff**

Run: `git diff --check`
Expected: exit code 0.

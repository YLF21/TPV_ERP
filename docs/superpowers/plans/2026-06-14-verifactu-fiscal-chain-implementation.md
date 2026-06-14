# VERI*FACTU Fiscal Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear la base persistente e inmutable de VERI*FACTU, con activacion, copia fiscal JSON, huella oficial SHA-256 y una cadena unica segura ante confirmaciones concurrentes.

**Architecture:** El nuevo paquete `verifactu` sera independiente del dominio comercial. Un servicio transaccional bloqueara una unica fila `cadena_fiscal` por empresa e instalacion, calculara la huella oficial sobre los campos definidos por AEAT, guardara el snapshot fiscal y actualizara la cabeza de cadena. La integracion con tickets, facturas, anulaciones y subsanaciones se realizara en un plan posterior.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Data JPA, Jackson, PostgreSQL 18, Flyway, JUnit 5, AssertJ.

---

## Limites De Este Plan

Incluye:

- Configuracion y consulta del estado de activacion VERI*FACTU.
- Tablas de cadena, registro, relaciones y estado de envio.
- Triggers que impiden modificar o borrar registros fiscales.
- Huellas oficiales de alta y anulacion segun AEAT.
- Hash interno estable de la copia fiscal JSON.
- Insercion serializada y concurrente de registros fiscales.
- Pruebas unitarias y pruebas reales sobre PostgreSQL.

No incluye:

- Cambios en `DocumentService`.
- Nuevas series o numeracion de documentos.
- Conversion ticket-factura.
- Anulaciones, subsanaciones o rectificativas comerciales.
- Vales, carritos o ventas aparcadas.
- XML, QR, certificados o envio a AEAT.

## Mapa De Archivos

Crear:

- `backend/src/main/resources/db/migration/V7__nucleo_fiscal_verifactu.sql`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordOperation.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalDocumentType.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRelationType.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalSubmissionStatus.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/AltaHashInput.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/CancellationHashInput.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/OfficialHashService.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalJsonHasher.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuConfiguration.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuConfigurationRepository.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuActivationService.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalChain.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalChainRepository.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecord.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordRepository.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordRelation.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordRelationRepository.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalSubmissionState.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalSubmissionStateRepository.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordCommand.java`
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordService.java`
- `backend/src/test/java/com/tpverp/backend/verifactu/OfficialHashServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/verifactu/FiscalJsonHasherTest.java`
- `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuActivationServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/verifactu/FiscalRecordServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/verifactu/FiscalChainPostgreSqlTest.java`

Modificar:

- `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`

## Task 1: Huella Oficial AEAT

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/AltaHashInput.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/CancellationHashInput.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/OfficialHashService.java`
- Test: `backend/src/test/java/com/tpverp/backend/verifactu/OfficialHashServiceTest.java`

- [ ] **Step 1: Escribir las pruebas con los ejemplos oficiales**

```java
package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OfficialHashServiceTest {

    private final OfficialHashService service = new OfficialHashService();

    @Test
    void calculaPrimerRegistroDeAltaDelEjemploAeat() {
        var input = new AltaHashInput(
                "89890001K", "12345678/G33", "01-01-2024", "F1",
                new BigDecimal("12.35"), new BigDecimal("123.45"), null,
                OffsetDateTime.parse("2024-01-01T19:20:30+01:00"));

        assertThat(service.hash(input)).isEqualTo(
                "3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60");
    }

    @Test
    void calculaAltaEncadenadaDelEjemploAeat() {
        var input = new AltaHashInput(
                "89890001K", "12345679/G34", "01-01-2024", "F1",
                new BigDecimal("12.35"), new BigDecimal("123.45"),
                "3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60",
                OffsetDateTime.parse("2024-01-01T19:20:35+01:00"));

        assertThat(service.hash(input)).isEqualTo(
                "F7B94CFD8924EDFF273501B01EE5153E4CE8F259766F88CF6ACB8935802A2B97");
    }

    @Test
    void calculaAnulacionDelEjemploAeat() {
        var input = new CancellationHashInput(
                "89890001K", "12345679/G34", "01-01-2024",
                "F7B94CFD8924EDFF273501B01EE5153E4CE8F259766F88CF6ACB8935802A2B97",
                OffsetDateTime.parse("2024-01-01T19:20:40+01:00"));

        assertThat(service.hash(input)).isEqualTo(
                "177547C0D57AC74748561D054A9CEC14B4C4EA23D1BEFD6F2E69E3A388F90C68");
    }
}
```

- [ ] **Step 2: Ejecutar la prueba y comprobar que falla**

Run:

```powershell
.\mvnw.cmd -Dtest=OfficialHashServiceTest test
```

Expected: `FAIL` porque las clases de huella todavia no existen.

- [ ] **Step 3: Crear los records de entrada**

```java
public record AltaHashInput(
        String issuerTaxId,
        String invoiceNumber,
        String issueDate,
        String invoiceType,
        BigDecimal totalTax,
        BigDecimal totalAmount,
        String previousHash,
        OffsetDateTime generatedAt) {
}
```

```java
public record CancellationHashInput(
        String issuerTaxId,
        String cancelledInvoiceNumber,
        String cancelledIssueDate,
        String previousHash,
        OffsetDateTime generatedAt) {
}
```

Ambos archivos deben declarar `package com.tpverp.backend.verifactu;` e importar
sus tipos de `java.math` y `java.time`.

- [ ] **Step 4: Implementar la concatenacion y SHA-256**

```java
package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class OfficialHashService {

    public String hash(AltaHashInput input) {
        return sha256(
                "IDEmisorFactura=" + text(input.issuerTaxId())
                + "&NumSerieFactura=" + text(input.invoiceNumber())
                + "&FechaExpedicionFactura=" + text(input.issueDate())
                + "&TipoFactura=" + text(input.invoiceType())
                + "&CuotaTotal=" + number(input.totalTax())
                + "&ImporteTotal=" + number(input.totalAmount())
                + "&Huella=" + text(input.previousHash())
                + "&FechaHoraHusoGenRegistro=" + input.generatedAt());
    }

    public String hash(CancellationHashInput input) {
        return sha256(
                "IDEmisorFacturaAnulada=" + text(input.issuerTaxId())
                + "&NumSerieFacturaAnulada=" + text(input.cancelledInvoiceNumber())
                + "&FechaExpedicionFacturaAnulada=" + text(input.cancelledIssueDate())
                + "&Huella=" + text(input.previousHash())
                + "&FechaHoraHusoGenRegistro=" + input.generatedAt());
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
```

- [ ] **Step 5: Ejecutar la prueba**

Run:

```powershell
.\mvnw.cmd -Dtest=OfficialHashServiceTest test
```

Expected: `BUILD SUCCESS`, 3 tests.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/verifactu backend/src/test/java/com/tpverp/backend/verifactu/OfficialHashServiceTest.java
git commit -m "feat: add official verifactu hash calculation"
```

## Task 2: Migracion Del Nucleo Fiscal

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__nucleo_fiscal_verifactu.sql`
- Modify: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`

- [ ] **Step 1: Ampliar el contrato de migracion**

Cambiar la lista de tablas esperadas para incluir:

```java
"configuracion_verifactu", "cadena_fiscal", "registro_fiscal",
"registro_fiscal_relacion", "estado_envio_fiscal"
```

Cambiar el total esperado de `36` a `41` y añadir una comprobacion que intente
actualizar y borrar un registro fiscal, esperando SQLState `P0001`.

- [ ] **Step 2: Ejecutar la prueba PostgreSQL**

Run:

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_ERP_TEST_DB_USER='postgres'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
.\mvnw.cmd -Dtest=PostgreSqlMigrationTest test
```

Expected: `FAIL`, porque faltan las cinco tablas.

- [ ] **Step 3: Crear la migracion V7**

La migracion debe crear:

```sql
create table configuracion_verifactu (
    id uuid primary key,
    empresa_id uuid not null unique references empresa(id),
    activacion_voluntaria boolean not null default false,
    activada_en timestamptz,
    primera_remision_en timestamptz,
    version bigint not null default 0,
    check (activacion_voluntaria or activada_en is null)
);

create table cadena_fiscal (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    ultimo_registro_id uuid,
    ultima_huella varchar(64),
    ultima_secuencia bigint not null default 0,
    actualizada_en timestamptz not null,
    version bigint not null default 0,
    unique (empresa_id, instalacion_id),
    check (ultima_secuencia >= 0),
    check (ultima_huella is null or ultima_huella ~ '^[0-9A-F]{64}$')
);

create table registro_fiscal (
    id uuid primary key,
    cadena_id uuid not null references cadena_fiscal(id),
    empresa_id uuid not null references empresa(id),
    instalacion_id uuid not null references instalacion(id),
    tienda_id uuid not null references tienda(id),
    documento_id uuid references documento(id),
    secuencia bigint not null,
    operacion varchar(16) not null,
    tipo_documento_fiscal varchar(4) not null,
    serie_numero varchar(64) not null,
    fecha_expedicion date not null,
    generado_en timestamptz not null,
    zona_horaria varchar(64) not null,
    nif_emisor varchar(9) not null,
    cuota_total numeric(19,2),
    importe_total numeric(19,2),
    huella_anterior varchar(64),
    huella varchar(64) not null,
    hash_snapshot varchar(64) not null,
    snapshot jsonb not null,
    version_formato varchar(16) not null,
    version_algoritmo varchar(16) not null,
    version_aplicacion varchar(32) not null,
    unique (cadena_id, secuencia),
    unique (cadena_id, huella),
    check (secuencia > 0),
    check (operacion in ('ALTA', 'ANULACION')),
    check (tipo_documento_fiscal in ('F1', 'F2', 'F3', 'R1', 'R2', 'R3', 'R4', 'R5')),
    check (huella ~ '^[0-9A-F]{64}$'),
    check (huella_anterior is null or huella_anterior ~ '^[0-9A-F]{64}$'),
    check (hash_snapshot ~ '^[0-9A-F]{64}$')
);

alter table cadena_fiscal
    add constraint fk_cadena_ultimo_registro
    foreign key (ultimo_registro_id) references registro_fiscal(id);

create table registro_fiscal_relacion (
    registro_id uuid not null references registro_fiscal(id),
    relacionado_id uuid not null references registro_fiscal(id),
    tipo varchar(16) not null,
    primary key (registro_id, relacionado_id, tipo),
    check (registro_id <> relacionado_id),
    check (tipo in ('SUBSANA', 'ANULA', 'RECTIFICA', 'SUSTITUYE'))
);

create table estado_envio_fiscal (
    registro_id uuid primary key references registro_fiscal(id),
    estado varchar(24) not null default 'PENDIENTE',
    ultimo_error_codigo varchar(64),
    ultimo_error text,
    actualizado_en timestamptz not null,
    version bigint not null default 0,
    check (estado in (
        'PENDIENTE', 'ENVIANDO', 'ACEPTADO',
        'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'DEFECTUOSO'))
);

create index ix_registro_fiscal_documento on registro_fiscal(documento_id);
create index ix_registro_fiscal_empresa_fecha
    on registro_fiscal(empresa_id, generado_en desc);
create index ix_estado_envio_fiscal_estado
    on estado_envio_fiscal(estado, actualizado_en);

create function impedir_mutacion_fiscal() returns trigger
language plpgsql as $$
begin
    raise exception 'Los registros fiscales son inmutables'
        using errcode = 'P0001';
end;
$$;

create trigger tr_registro_fiscal_inmutable
before update or delete on registro_fiscal
for each row execute function impedir_mutacion_fiscal();

create trigger tr_relacion_fiscal_inmutable
before update or delete on registro_fiscal_relacion
for each row execute function impedir_mutacion_fiscal();
```

- [ ] **Step 4: Ejecutar el contrato de migracion**

Run: el mismo comando PostgreSQL del Step 2.

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V7__nucleo_fiscal_verifactu.sql backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java
git commit -m "feat: add immutable verifactu persistence"
```

## Task 3: Modelo JPA Y Repositorio Con Bloqueo

**Files:**
- Create: todos los enums, entidades y repositorios indicados en el mapa de archivos.
- Test: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalRecordServiceTest.java`

- [ ] **Step 1: Escribir una prueba de dominio**

La prueba debe construir una cadena vacia, añadir un registro con secuencia `1`
y verificar que `advance(record, instant)` deja `ultimaSecuencia = 1`,
`ultimaHuella = record.getHash()` y `ultimoRegistro = record`.

- [ ] **Step 2: Ejecutar y comprobar el fallo**

```powershell
.\mvnw.cmd -Dtest=FiscalRecordServiceTest test
```

Expected: `FAIL`, porque el modelo JPA no existe.

- [ ] **Step 3: Crear enums**

```java
public enum FiscalRecordOperation { ALTA, ANULACION }
public enum FiscalDocumentType { F1, F2, F3, R1, R2, R3, R4, R5 }
public enum FiscalRelationType { SUBSANA, ANULA, RECTIFICA, SUSTITUYE }
public enum FiscalSubmissionStatus {
    PENDIENTE, ENVIANDO, ACEPTADO, ACEPTADO_CON_ERRORES, RECHAZADO, DEFECTUOSO
}
```

Cada enum ira en su archivo dentro de `com.tpverp.backend.verifactu`.

- [ ] **Step 4: Crear entidades enfocadas**

Las entidades deben mapear exactamente V7 y exponer solo:

```java
// FiscalChain
public long nextSequence()
public String previousHash()
public void advance(FiscalRecord record, Instant updatedAt)

// FiscalRecord
public UUID getId()
public long getSequence()
public String getHash()
public String getSnapshotHash()

// FiscalSubmissionState
public UUID getRecordId()
public FiscalSubmissionStatus getStatus()
```

`FiscalRecord` no debe tener setters ni metodos de modificacion. Su constructor
publico recibira todos los datos inmutables y copiara el snapshot con
`Map.copyOf(snapshot)`.

- [ ] **Step 5: Crear el bloqueo pesimista**

```java
public interface FiscalChainRepository extends JpaRepository<FiscalChain, UUID> {

    @Modifying
    @Query(value = """
            insert into cadena_fiscal (
                id, empresa_id, instalacion_id, ultima_secuencia, actualizada_en)
            values (:id, :companyId, :installationId, 0, :createdAt)
            on conflict (empresa_id, instalacion_id) do nothing
            """, nativeQuery = true)
    void insertIfMissing(
            UUID id, UUID companyId, UUID installationId, Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select chain from FiscalChain chain
            where chain.companyId = :companyId
              and chain.installationId = :installationId
            """)
    Optional<FiscalChain> findForUpdate(UUID companyId, UUID installationId);
}
```

Los demas repositorios extenderan `JpaRepository` y
`FiscalRecordRepository` añadira:

```java
List<FiscalRecord> findAllByChainIdOrderBySequence(UUID chainId);
```

`FiscalRecord` tambien expondra `getPreviousHash()` para verificar la
continuidad sin abrir mutabilidad.

- [ ] **Step 6: Ejecutar la prueba**

```powershell
.\mvnw.cmd -Dtest=FiscalRecordServiceTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/verifactu backend/src/test/java/com/tpverp/backend/verifactu/FiscalRecordServiceTest.java
git commit -m "feat: map verifactu fiscal chain"
```

## Task 4: Hash Estable Del Snapshot JSON

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalJsonHasher.java`
- Test: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalJsonHasherTest.java`

- [ ] **Step 1: Escribir pruebas de estabilidad**

```java
@Test
void ignoraElOrdenDeInsercionDeLasClaves() {
    var first = new LinkedHashMap<String, Object>();
    first.put("numero", "001");
    first.put("total", new BigDecimal("10.00"));
    var second = new LinkedHashMap<String, Object>();
    second.put("total", new BigDecimal("10.00"));
    second.put("numero", "001");

    assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
}

@Test
void cambiaCuandoCambiaUnDatoFiscal() {
    assertThat(hasher.hash(Map.of("total", "10.00")))
            .isNotEqualTo(hasher.hash(Map.of("total", "10.01")));
}
```

- [ ] **Step 2: Ejecutar y comprobar el fallo**

```powershell
.\mvnw.cmd -Dtest=FiscalJsonHasherTest test
```

Expected: `FAIL`.

- [ ] **Step 3: Implementar JSON canonico**

```java
package com.tpverp.backend.verifactu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class FiscalJsonHasher {

    private final ObjectMapper mapper;

    public FiscalJsonHasher(ObjectMapper source) {
        mapper = source.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }

    public String hash(Map<String, Object> snapshot) {
        try {
            var json = mapper.writeValueAsBytes(snapshot);
            var digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("No se pudo calcular el hash fiscal", exception);
        }
    }
}
```

- [ ] **Step 4: Ejecutar la prueba**

```powershell
.\mvnw.cmd -Dtest=FiscalJsonHasherTest test
```

Expected: `BUILD SUCCESS`, 2 tests.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/verifactu/FiscalJsonHasher.java backend/src/test/java/com/tpverp/backend/verifactu/FiscalJsonHasherTest.java
git commit -m "feat: add canonical fiscal snapshot hash"
```

## Task 5: Politica De Activacion

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuConfiguration.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuConfigurationRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuActivationService.java`
- Test: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuActivationServiceTest.java`

- [ ] **Step 1: Escribir pruebas parametrizadas**

Probar:

```java
@ParameterizedTest
@CsvSource({
        "SOCIEDAD,2026-12-31T23:59:59Z,false",
        "SOCIEDAD,2027-01-01T00:00:00Z,true",
        "AUTONOMO,2027-06-30T23:59:59Z,false",
        "AUTONOMO,2027-07-01T00:00:00Z,true"
})
void aplicaLaFechaLegal(
        TaxpayerType type, Instant now, boolean expected) {
    assertThat(service.isLegallyRequired(type, now)).isEqualTo(expected);
}
```

Añadir pruebas para activacion voluntaria reversible antes de la primera
remision e irreversible despues de `primeraRemisionEn`.

- [ ] **Step 2: Ejecutar y comprobar el fallo**

```powershell
.\mvnw.cmd -Dtest=VerifactuActivationServiceTest test
```

Expected: `FAIL`.

- [ ] **Step 3: Implementar la politica**

```java
public boolean isLegallyRequired(TaxpayerType type, Instant now) {
    var date = now.atZone(ZoneOffset.UTC).toLocalDate();
    return switch (type) {
        case SOCIEDAD -> !date.isBefore(LocalDate.of(2027, 1, 1));
        case AUTONOMO -> !date.isBefore(LocalDate.of(2027, 7, 1));
    };
}

public boolean isActive(
        VerifactuConfiguration configuration,
        TaxpayerType type,
        Instant now) {
    return configuration.isVoluntarilyActive() || isLegallyRequired(type, now);
}
```

`deactivateVoluntarily()` debe lanzar `IllegalStateException` cuando exista
`primeraRemisionEn` o ya haya llegado la fecha legal.

- [ ] **Step 4: Ejecutar la prueba**

```powershell
.\mvnw.cmd -Dtest=VerifactuActivationServiceTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/verifactu/VerifactuConfiguration.java backend/src/main/java/com/tpverp/backend/verifactu/VerifactuConfigurationRepository.java backend/src/main/java/com/tpverp/backend/verifactu/VerifactuActivationService.java backend/src/test/java/com/tpverp/backend/verifactu/VerifactuActivationServiceTest.java
git commit -m "feat: add verifactu activation policy"
```

## Task 6: Servicio Transaccional De Registro

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordCommand.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalRecordServiceTest.java`

- [ ] **Step 1: Definir el comando inmutable**

```java
public record FiscalRecordCommand(
        UUID companyId,
        UUID installationId,
        UUID storeId,
        UUID documentId,
        FiscalRecordOperation operation,
        FiscalDocumentType documentType,
        String number,
        LocalDate issueDate,
        OffsetDateTime generatedAt,
        String timezone,
        String issuerTaxId,
        BigDecimal totalTax,
        BigDecimal totalAmount,
        Map<String, Object> snapshot,
        String formatVersion,
        String algorithmVersion,
        String applicationVersion) {
}
```

- [ ] **Step 2: Escribir la prueba de alta**

La prueba mockeara repositorios, devolvera una cadena con secuencia `0` y
comprobara:

- Se guarda secuencia `1`.
- La huella anterior es `null`.
- El estado inicial es `PENDIENTE`.
- La cadena avanza a la huella guardada.

- [ ] **Step 3: Ejecutar y comprobar el fallo**

```powershell
.\mvnw.cmd -Dtest=FiscalRecordServiceTest test
```

Expected: `FAIL`.

- [ ] **Step 4: Implementar el servicio**

```java
@Service
public class FiscalRecordService {

    @Transactional
    public FiscalRecord register(FiscalRecordCommand command) {
        // El UPSERT evita que dos TPV creen simultaneamente la primera cadena.
        chains.insertIfMissing(
                UUID.randomUUID(), command.companyId(), command.installationId(),
                command.generatedAt().toInstant());
        var chain = chains.findForUpdate(
                        command.companyId(), command.installationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la cadena fiscal"));
        var previousHash = chain.previousHash();
        var officialHash = command.operation() == FiscalRecordOperation.ALTA
                ? officialHashes.hash(new AltaHashInput(
                        command.issuerTaxId(), command.number(),
                        DATE.format(command.issueDate()), command.documentType().name(),
                        command.totalTax(), command.totalAmount(), previousHash,
                        command.generatedAt()))
                : officialHashes.hash(new CancellationHashInput(
                        command.issuerTaxId(), command.number(),
                        DATE.format(command.issueDate()), previousHash,
                        command.generatedAt()));
        var record = FiscalRecord.from(
                chain, chain.nextSequence(), command, previousHash,
                officialHash, jsonHasher.hash(command.snapshot()));
        records.save(record);
        states.save(FiscalSubmissionState.pending(
                record, command.generatedAt().toInstant()));
        chain.advance(record, command.generatedAt().toInstant());
        return record;
    }
}
```

La constante `DATE` sera:

```java
private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd-MM-uuuu");
```

Todas las dependencias se recibiran por constructor. Añadir comentarios `//`
solo a `register` y a la logica de creacion concurrente de la primera cadena.

- [ ] **Step 5: Ejecutar pruebas unitarias**

```powershell
.\mvnw.cmd -Dtest=FiscalRecordServiceTest,OfficialHashServiceTest,FiscalJsonHasherTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/verifactu backend/src/test/java/com/tpverp/backend/verifactu
git commit -m "feat: register chained fiscal records"
```

## Task 7: Concurrencia E Inmutabilidad En PostgreSQL

**Files:**
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/FiscalChainPostgreSqlTest.java`

- [ ] **Step 1: Crear fixture PostgreSQL aislada**

Seguir el patron de `DocumentConfirmationRollbackPostgreSqlTest`: esquema UUID,
variables `TPV_ERP_TEST_DB_*`, `@DataJpaTest`,
`@AutoConfigureTestDatabase(replace = NONE)` y limpieza en `@AfterAll`.

- [ ] **Step 2: Escribir la prueba concurrente**

Crear una cadena inicial y lanzar 20 registros con `ExecutorService` de 8 hilos.
Cada tarea usara una transaccion `REQUIRES_NEW`.

Comprobar:

```java
assertThat(records.findAllByChainIdOrderBySequence(chainId))
        .extracting(FiscalRecord::getSequence)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());

for (int index = 1; index < saved.size(); index++) {
    assertThat(saved.get(index).getPreviousHash())
            .isEqualTo(saved.get(index - 1).getHash());
}
```

- [ ] **Step 3: Escribir la prueba de triggers**

Ejecutar con `JdbcTemplate`:

```java
assertThatThrownBy(() -> jdbc.update(
        "update registro_fiscal set serie_numero = 'ALTERADO' where id = ?",
        recordId))
        .hasRootCauseInstanceOf(SQLException.class);

assertThatThrownBy(() -> jdbc.update(
        "delete from registro_fiscal where id = ?", recordId))
        .hasRootCauseInstanceOf(SQLException.class);
```

- [ ] **Step 4: Ejecutar la integracion**

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_ERP_TEST_DB_USER='postgres'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
.\mvnw.cmd -Dtest=FiscalChainPostgreSqlTest test
```

Expected: `BUILD SUCCESS`, secuencias `1..20` y cadena continua.

- [ ] **Step 5: Ejecutar la regresion enfocada**

```powershell
.\mvnw.cmd -Dtest=OfficialHashServiceTest,FiscalJsonHasherTest,VerifactuActivationServiceTest,FiscalRecordServiceTest,FiscalChainPostgreSqlTest,PostgreSqlMigrationTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/test/java/com/tpverp/backend/verifactu/FiscalChainPostgreSqlTest.java
git commit -m "test: verify fiscal chain concurrency and immutability"
```

## Criterio De Finalizacion

Este plan termina cuando:

- V7 migra correctamente sobre PostgreSQL 18.
- Los ejemplos oficiales de huella AEAT producen exactamente los hashes
  publicados.
- El snapshot genera un hash estable.
- Veinte inserciones concurrentes producen una unica secuencia continua.
- PostgreSQL impide modificar o borrar registros fiscales.
- La politica distingue activacion voluntaria y obligatoria.
- No se ha modificado todavia el comportamiento de tickets o facturas.

## Siguientes Planes

1. `2A2 - Integracion documental fiscal`: series, `F2`, `F3`, `R5`, altas,
   anulaciones, subsanaciones, conversion ticket-factura e inmutabilidad de
   documentos confirmados.
2. `2B - Vales de devolucion`: emision, consumo, renovacion, trazabilidad,
   caducidad y permisos.
3. `2C - Carritos y ventas aparcadas`: carrito persistente por terminal,
   recuperacion, aparcado, limpieza y auditoria.

## Referencia Oficial

La implementacion de `OfficialHashService` se contrastara con la version
vigente del documento AEAT:

- `Veri-Factu_especificaciones_huella_hash_registros.pdf`, version 0.1.2,
  publicada por la Agencia Tributaria.
- La AEAT define SHA-256, concatenacion UTF-8, salida hexadecimal en mayusculas
  de 64 caracteres y campos diferentes para alta y anulacion.

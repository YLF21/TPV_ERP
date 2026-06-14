# VERI*FACTU License And Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Incorporar NIF y tipo de contribuyente a la licencia firmada, validarlos contra la empresa y preparar cada tienda con código fiscal y zona horaria válidos.

**Architecture:** La licencia versión 3 será la fuente inmutable de NIF y tipo `SOCIEDAD|AUTONOMO`. El backend persistirá esos datos en el historial de licencias y rechazará discrepancias con la empresa instalada. La organización incorporará un código fiscal automático por tienda y una zona horaria española validada, sin implementar todavía cadenas, XML ni activación VERI*FACTU.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven, JPA, Flyway, PostgreSQL 18, Swing, Jackson, Gson, JUnit 5, Mockito, AssertJ.

---

## File Map

- Create `backend/src/main/java/com/tpverp/backend/licensing/application/TaxpayerType.java`: tipos fiscales admitidos.
- Create `license-issuer/src/main/java/com/tpv/licenseissuer/model/TaxpayerType.java`: equivalente del emisor offline.
- Create `backend/src/main/java/com/tpverp/backend/organization/SpanishTaxId.java`: normalización y validación estructural del NIF.
- Create `backend/src/main/java/com/tpverp/backend/organization/StoreFiscalIdentity.java`: código fiscal y zona permitida.
- Create `backend/src/main/resources/db/migration/V6__identidad_fiscal_verifactu.sql`: columnas, restricciones y asignación inicial.
- Modify payloads, previsualización, entidad y servicio de licencias.
- Modify `license-issuer` para capturar y firmar NIF y tipo.
- Modify `Empresa`, `Tienda`, bootstrap y repositorios para exponer la identidad.
- Add only focused unit tests plus the existing real PostgreSQL migration test.

### Task 1: Fiscal Value Objects

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/licensing/application/TaxpayerType.java`
- Create: `license-issuer/src/main/java/com/tpv/licenseissuer/model/TaxpayerType.java`
- Create: `backend/src/main/java/com/tpverp/backend/organization/SpanishTaxId.java`
- Create: `backend/src/main/java/com/tpverp/backend/organization/StoreFiscalIdentity.java`
- Test: `backend/src/test/java/com/tpverp/backend/organization/SpanishTaxIdTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/organization/StoreFiscalIdentityTest.java`

- [ ] **Step 1: Write failing NIF normalization tests**

```java
@Test
void normalizesSpanishTaxIds() {
    assertThat(SpanishTaxId.normalize(" b-12345678 ")).isEqualTo("B12345678");
}

@Test
void rejectsStructurallyInvalidTaxIds() {
    assertThatThrownBy(() -> SpanishTaxId.normalize("DEMO-00000000"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NIF");
}
```

The accepted structure is one leading letter or digit followed by eight
alphanumeric characters. Checksum verification is deliberately excluded from
this block because AEAT validation belongs to the fiscal integration.

- [ ] **Step 2: Write failing store identity tests**

```java
@ParameterizedTest
@ValueSource(strings = {"001", "999"})
void acceptsThreeDigitFiscalCodes(String code) {
    assertThat(StoreFiscalIdentity.code(code)).isEqualTo(code);
}

@ParameterizedTest
@ValueSource(strings = {"Atlantic/Canary", "Europe/Madrid"})
void acceptsSupportedTimezones(String zone) {
    assertThat(StoreFiscalIdentity.timezone(zone)).isEqualTo(zone);
}
```

Also reject `000`, `1000`, `Europe/London` and blank values.

- [ ] **Step 3: Run tests to verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd '-Dtest=SpanishTaxIdTest,StoreFiscalIdentityTest' test
```

Expected: compilation fails because both value objects are missing.

- [ ] **Step 4: Implement the value objects and enums**

```java
public enum TaxpayerType {
    SOCIEDAD,
    AUTONOMO
}
```

Implement `SpanishTaxId.normalize` with uppercase, removal of spaces and
hyphens, and the pattern `[A-Z0-9][A-Z0-9]{8}`.

Implement:

```java
public final class StoreFiscalIdentity {
    public static String code(String value)
    public static String timezone(String value)
}
```

`code` accepts `001..999`; `timezone` accepts only `Atlantic/Canary` and
`Europe/Madrid`. Add `//` comments only to public methods whose validation is
not obvious.

- [ ] **Step 5: Run focused tests**

Run the command from Step 3.

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/licensing/application/TaxpayerType.java backend/src/main/java/com/tpverp/backend/organization/SpanishTaxId.java backend/src/main/java/com/tpverp/backend/organization/StoreFiscalIdentity.java backend/src/test/java/com/tpverp/backend/organization license-issuer/src/main/java/com/tpv/licenseissuer/model/TaxpayerType.java
git commit -m "feat: add fiscal identity value objects"
```

### Task 2: Version 3 Signed License

**Files:**
- Modify: `license-issuer/src/main/java/com/tpv/licenseissuer/model/LicenseDetails.java`
- Modify: `license-issuer/src/main/java/com/tpv/licenseissuer/crypto/LicensePayload.java`
- Modify: `license-issuer/src/main/java/com/tpv/licenseissuer/crypto/LicenseCryptography.java`
- Modify: `license-issuer/src/main/java/com/tpv/licenseissuer/ui/LicenseIssuerFrame.java`
- Modify: `license-issuer/src/test/java/com/tpv/licenseissuer/model/LicenseDetailsTest.java`
- Modify: `license-issuer/src/test/java/com/tpv/licenseissuer/crypto/LicenseCryptographyTest.java`
- Modify: `license-issuer/src/test/java/com/tpv/licenseissuer/service/LicenseIssuanceServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/application/LicensePayload.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/application/LicensePreview.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/application/LicenseEnvelopeDecoder.java`
- Modify: `backend/src/test/java/com/tpverp/backend/licensing/application/LicenseEnvelopeDecoderTest.java`

- [ ] **Step 1: Extend issuer tests first**

Create licenses with:

```java
new LicenseDetails(
        "B12345678",
        TaxpayerType.SOCIEDAD,
        "Example SL",
        "Main Store",
        validFrom,
        validUntil,
        3,
        10,
        TaxRegime.IVA)
```

Assert decrypted payload:

```java
assertEquals("B12345678", payload.taxId());
assertEquals(TaxpayerType.SOCIEDAD, payload.taxpayerType());
assertEquals(3, envelope.get("version").getAsInt());
```

Add tests rejecting blank NIF and missing taxpayer type.

- [ ] **Step 2: Extend backend decoder tests first**

The helper payload must include:

```json
{
  "taxId": "B12345678",
  "taxpayerType": "SOCIEDAD"
}
```

Assert:

```java
assertThat(preview.taxId()).isEqualTo("B12345678");
assertThat(preview.taxpayerType()).isEqualTo(TaxpayerType.SOCIEDAD);
```

Add tests that reject version 2, a missing `taxId`, an invalid NIF structure and
an unknown taxpayer type.

- [ ] **Step 3: Run both modules to verify RED**

```powershell
cd license-issuer
.\mvnw.cmd '-Dtest=LicenseDetailsTest,LicenseCryptographyTest,LicenseIssuanceServiceTest' test
cd ..\backend
.\mvnw.cmd -Dtest=LicenseEnvelopeDecoderTest test
```

Expected: compilation failures caused by the new fields and enum.

- [ ] **Step 4: Implement license version 3**

Use these record signatures in both modules:

```java
public record LicensePayload(
        String installationId,
        String installationReference,
        String taxId,
        TaxpayerType taxpayerType,
        String company,
        String store,
        String validFrom,
        String validUntil,
        int maxWindows,
        int maxPda,
        TaxRegime impuestos,
        String issuedAt) {}
```

`LicensePreview` will expose `taxId` and `taxpayerType`. Set
`LicenseCryptography.VERSION` to `3`; set decoder header validation and AAD to
version 3. Version 2 licenses must fail explicitly.

Update the Swing form with:

- `NIF` text field.
- `Tipo de contribuyente` combo containing `SOCIEDAD` and `AUTONOMO`.

- [ ] **Step 5: Run focused tests**

Run both commands from Step 3.

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```powershell
git add license-issuer/src backend/src/main/java/com/tpverp/backend/licensing/application backend/src/test/java/com/tpverp/backend/licensing/application
git commit -m "feat: sign taxpayer identity in licenses"
```

### Task 3: Persist And Validate Licensed Taxpayer

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__identidad_fiscal_verifactu.sql`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/Licencia.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/application/LicenseService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicensingConfiguration.java`
- Modify: `backend/src/main/java/com/tpverp/backend/organization/Empresa.java`
- Modify: `backend/src/main/java/com/tpverp/backend/organization/EmpresaRepository.java`
- Modify: `backend/src/test/java/com/tpverp/backend/licensing/LicenciaTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/licensing/application/LicenseServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`

- [ ] **Step 1: Write domain persistence tests**

Construct `Licencia` with:

```java
"B12345678",
TaxpayerType.SOCIEDAD,
TaxRegime.IVA
```

Assert getters preserve both signed values and format version `3`.

- [ ] **Step 2: Write service mismatch tests**

Mock `EmpresaRepository` and assert:

```java
assertThatThrownBy(() -> service.activate(envelope, hash))
        .isInstanceOf(LicenseValidationException.class)
        .hasMessageContaining("NIF");
```

when the installed company has `B87654321` and the preview has `B12345678`.
Also verify:

- A matching normalized NIF activates and persists the taxpayer type.
- The first real license replaces the protected demo value
  `DEMO-00000000` with the signed NIF.
- Once a non-demo NIF exists, no later license may change it.

- [ ] **Step 3: Write migration assertions**

Extend `PostgreSqlMigrationTest` to assert:

- `licencia.tax_id` is not nullable.
- `licencia.taxpayer_type` accepts only `SOCIEDAD|AUTONOMO`.
- `tienda.codigo_fiscal` is not nullable.
- `(empresa_id, codigo_fiscal)` is unique.
- Existing first stores receive `001`.

- [ ] **Step 4: Run tests to verify RED**

```powershell
cd backend
.\mvnw.cmd '-Dtest=LicenciaTest,LicenseServiceTest' test
```

Expected: failures because persistence and validation are missing.

- [ ] **Step 5: Implement migration and activation validation**

Migration:

```sql
alter table licencia add column tax_id varchar(9);
alter table licencia add column taxpayer_type varchar(16);
alter table tienda add column codigo_fiscal varchar(3);

with numbered as (
    select id,
           row_number() over (partition by empresa_id order by id)::integer as code
    from tienda
)
update tienda t
set codigo_fiscal = lpad(numbered.code::text, 3, '0')
from numbered
where numbered.id = t.id;

alter table tienda alter column codigo_fiscal set not null;
alter table tienda add constraint ck_tienda_codigo_fiscal
    check (codigo_fiscal ~ '^[0-9]{3}$' and codigo_fiscal <> '000');
alter table tienda add constraint ux_tienda_empresa_codigo_fiscal
    unique (empresa_id, codigo_fiscal);
```

Because no version 3 licenses exist before this migration, leave historical
license rows nullable but enforce non-null in the JPA entity for newly accepted
licenses. Add a check constraint allowing both fields null together for old
history or both populated for version 3.

Inject `EmpresaRepository` into `LicenseService`. Before deactivating the
current license, normalize `preview.taxId()` and compare it with the company
linked to the current store:

- If `empresa.tax_id` is `DEMO-00000000` and no accepted real license exists,
  replace it with the signed NIF through `Empresa.adoptLicensedTaxId`.
- Otherwise require exact normalized equality.
- Reject mismatch before deactivating any existing license.

`Empresa.adoptLicensedTaxId` only accepts the transition from the exact demo
sentinel to a structurally valid NIF. It cannot modify a real NIF.

Persist `taxId`, `taxpayerType` and format version `3`; include them in preview
and history responses.

- [ ] **Step 6: Run focused tests**

```powershell
cd backend
.\mvnw.cmd '-Dtest=LicenciaTest,LicenseServiceTest,LicenseEnvelopeDecoderTest' test
```

Expected: all selected tests pass.

- [ ] **Step 7: Run one real PostgreSQL migration test**

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_ERP_TEST_DB_USER='postgres'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
.\mvnw.cmd -Dtest=PostgreSqlMigrationTest test
```

Expected: migration V1-V6 succeeds and all new constraints pass.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/resources/db/migration/V6__identidad_fiscal_verifactu.sql backend/src/main/java/com/tpverp/backend/licensing backend/src/main/java/com/tpverp/backend/organization/Empresa.java backend/src/main/java/com/tpverp/backend/organization/EmpresaRepository.java backend/src/test/java/com/tpverp/backend/licensing backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java
git commit -m "feat: validate licensed taxpayer identity"
```

### Task 4: Store Fiscal Code And Timezone

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/organization/Empresa.java`
- Modify: `backend/src/main/java/com/tpverp/backend/organization/Tienda.java`
- Modify: `backend/src/main/java/com/tpverp/backend/organization/TiendaRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/installation/InstallationBootstrapService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/installation/InstallationBootstrapServiceTest.java`
- Modify constructor calls in focused tests that instantiate `Tienda`.

- [ ] **Step 1: Write failing store tests**

Capture the demo store and assert:

```java
assertThat(store.getCodigoFiscal()).isEqualTo("001");
assertThat(store.getTimezone()).isEqualTo("Atlantic/Canary");
```

Add a domain test asserting invalid codes and unsupported timezones are
rejected.

- [ ] **Step 2: Run to verify RED**

```powershell
cd backend
.\mvnw.cmd '-Dtest=InstallationBootstrapServiceTest,ModeloPersistenciaContractTest' test
```

Expected: compilation failure for the missing code getter/constructor value.

- [ ] **Step 3: Implement store identity**

Change the constructor to include fiscal code:

```java
public Tienda(
        Empresa empresa,
        String codigoFiscal,
        String nombre,
        Map<String, String> direccion,
        String addressNormalizedHash,
        String timezone,
        String moneda,
        String locale)
```

Validate with `StoreFiscalIdentity`. Expose:

```java
public String getCodigoFiscal()
public String getTimezone()
```

The demo bootstrap passes `001`. Add repository lookup:

```java
boolean existsByEmpresaIdAndCodigoFiscal(UUID empresaId, String codigoFiscal);
```

Future store creation will allocate the next code under a company lock; that
API is not part of this block because no store-administration service exists
yet.

- [ ] **Step 4: Update focused constructor call sites**

Pass `001` in tests that create a single store. Tests with multiple stores use
different codes. Do not refactor unrelated test fixtures.

- [ ] **Step 5: Run compact verification**

```powershell
cd backend
.\mvnw.cmd '-Dtest=InstallationBootstrapServiceTest,ModeloPersistenciaContractTest,LicenciaTest,LicenseServiceTest,LicenseEnvelopeDecoderTest,SpanishTaxIdTest,StoreFiscalIdentityTest' test
cd ..\license-issuer
.\mvnw.cmd '-Dtest=LicenseDetailsTest,LicenseCryptographyTest,LicenseIssuanceServiceTest' test
```

Expected: all selected tests pass.

- [ ] **Step 6: Check diff and commit**

```powershell
git diff --check
git add backend/src/main/java/com/tpverp/backend/organization backend/src/main/java/com/tpverp/backend/installation/InstallationBootstrapService.java backend/src/test license-issuer/src
git commit -m "feat: assign fiscal identity to stores"
```

## Block Completion

The block is complete when:

- New licenses are version 3 and require NIF plus taxpayer type.
- Activation rejects a license for another NIF before mutating data.
- Accepted license history preserves the signed taxpayer identity.
- Every store has a unique three-digit fiscal code within its company.
- Store timezone accepts only the two supported Spanish zones.
- Focused backend and issuer tests pass.
- Flyway V1-V6 passes once against PostgreSQL 18.

The block deliberately does not implement activation dates, fiscal chains,
document numbering, XML, QR, certificates or AEAT communication.

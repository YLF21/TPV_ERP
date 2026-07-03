# SaaS License Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SaaS the main license authority while keeping the local license as signed/offline cache.

**Architecture:** Installation bootstrap will create only technical identity and protected `ADMIN` when no SaaS link exists. SaaS link will create/update official company, store, tax configuration and local license snapshot. Manual file activation remains available only as support/emergency flow behind configuration.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven, JPA, Flyway, PostgreSQL, JUnit 5, Mockito.

---

## File Structure

- Modify: `backend/src/main/java/com/tpverp/backend/shared/access/OperationalMode.java`
  - Add explicit installation/license modes: `UNLINKED`, `LICENSED`, `OFFLINE`, `RESTRICTED`.
- Modify: `backend/src/main/java/com/tpverp/backend/installation/InstallationBootstrapService.java`
  - Stop creating demo company/store/server terminal by default.
  - Keep installation identity and `ADMIN` bootstrap.
- Modify: `backend/src/main/java/com/tpverp/backend/installation/InstallationStatusService.java`
  - Return SaaS-aware state.
  - Do not require active company/store for a new unlinked installation.
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkRequest.java`
  - Make company/store fields nullable for new installations.
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkResponse.java`
  - Add official company/store payload fields returned by SaaS.
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkService.java`
  - Allow linking without existing local company/store.
  - Create official company/store from SaaS response.
  - Keep existing-installation validation when local data exists.
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/api/LicenseController.java`
  - Add status/manual validation endpoint if missing.
  - Gate local file activation as support flow.
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasValidationResponse.java`
  - Prepare for SaaS status values beyond `VALIDA` and `BLOQUEADA_MANUAL`.
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasStatus.java`
  - Add `CADUCADA`, `REQUIERE_ACTUALIZACION`.
- Modify: `backend/src/main/resources/application*.yml`
  - Add flag for local file activation support mode.
- Test: `backend/src/test/java/com/tpverp/backend/installation/InstallationBootstrapServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/installation/InstallationStatusServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasLinkServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/api/LicenseControllerContractTest.java`

---

## Task 1: Add SaaS-Aware Operational Modes

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/shared/access/OperationalMode.java`
- Test: `backend/src/test/java/com/tpverp/backend/installation/InstallationStatusServiceTest.java`

- [ ] **Step 1: Write failing status tests**

Add tests that assert:

```java
@Test
void reportsUnlinkedWhenThereIsNoActiveLicense() {
    when(installations.findAll()).thenReturn(List.of(installation()));
    when(licenses.findAll()).thenReturn(List.of());

    var status = service.status();

    assertThat(status.mode()).isEqualTo(OperationalMode.UNLINKED);
    assertThat(status.activeLicenseReference()).isNull();
}

@Test
void reportsRestrictedWhenActiveLicenseIsSaasBlocked() {
    var license = activeLicense();
    license.markSaasBlocked(NOW);
    when(installations.findAll()).thenReturn(List.of(installation()));
    when(licenses.findAll()).thenReturn(List.of(license));

    var status = service.status();

    assertThat(status.mode()).isEqualTo(OperationalMode.RESTRICTED);
}
```

- [ ] **Step 2: Run focused test**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=InstallationStatusServiceTest" test
```

Expected: FAIL because `OperationalMode.UNLINKED` does not exist or status still reports demo/restricted.

- [ ] **Step 3: Implement enum change**

Update `OperationalMode` to include:

```java
public enum OperationalMode {
    UNLINKED,
    LICENSED,
    OFFLINE,
    RESTRICTED
}
```

- [ ] **Step 4: Update status logic**

In `InstallationStatusService.status()` use:

```java
if (activeLicense == null) {
    mode = OperationalMode.UNLINKED;
} else if (activeLicense.isOperationalAt(now)) {
    mode = activeLicense.requiresOfflineExpiredWarningAt(now)
            ? OperationalMode.OFFLINE
            : OperationalMode.LICENSED;
} else {
    mode = OperationalMode.RESTRICTED;
}
```

- [ ] **Step 5: Verify**

Run the same focused test. Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/shared/access/OperationalMode.java backend/src/main/java/com/tpverp/backend/installation/InstallationStatusService.java backend/src/test/java/com/tpverp/backend/installation/InstallationStatusServiceTest.java
git commit -m "feat: add saas-aware installation modes"
```

---

## Task 2: Bootstrap Only Technical Installation For New Installs

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/installation/InstallationBootstrapService.java`
- Test: `backend/src/test/java/com/tpverp/backend/installation/InstallationBootstrapServiceTest.java`

- [ ] **Step 1: Write failing bootstrap test**

Add a test that asserts first boot creates installation and admin, but no company/store/terminal:

```java
@Test
void firstBootCreatesOnlyInstallationIdentityAndAdminBeforeSaasLink() {
    when(installations.count()).thenReturn(0L);
    when(identityStore.loadOrCreate()).thenReturn(identity());

    service.initialize();

    verify(installations).save(any(Installation.class));
    verify(users).save(argThat(user -> "ADMIN".equals(user.getUsername())));
    verify(companies, never()).save(any());
    verify(stores, never()).save(any());
    verify(terminals, never()).save(any());
}
```

- [ ] **Step 2: Run focused test**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=InstallationBootstrapServiceTest" test
```

Expected: FAIL because bootstrap currently creates demo company/store/terminal.

- [ ] **Step 3: Change bootstrap implementation**

In `InstallationBootstrapService.initialize()` keep:

```java
var identity = identityStore.loadOrCreate();
var now = Instant.now(clock);
var installation = new Installation(
        reference(identity.keyId()),
        Base64.getEncoder().encodeToString(identity.publicKey().getEncoded()),
        now);
instalacionRepository.save(installation);

var adminRole = rolRepository.save(new Role(null, "ADMIN"));
usuarioRepository.save(new UserAccount(null, "ADMIN", passwordEncoder.encode("0000"), adminRole));
```

If `Role` or `UserAccount` currently require `Store`, do not force null into invalid entities. Instead create a protected global admin model in the smallest compatible way:

- add nullable `tienda_id` for protected `ADMIN`, or
- keep a technical store flagged as non-operational only if null is too invasive.

Preferred option: nullable store for `ADMIN`, because a new installation has no real store yet.

- [ ] **Step 4: Add migration if store becomes nullable**

Create `backend/src/main/resources/db/migration/V36__admin_global_sin_tienda.sql`:

```sql
alter table rol alter column tienda_id drop not null;
alter table usuario alter column tienda_id drop not null;
```

Use the next available Flyway number.

- [ ] **Step 5: Verify**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=InstallationBootstrapServiceTest,TpvErpBackendApplicationTests" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/installation/InstallationBootstrapService.java backend/src/test/java/com/tpverp/backend/installation/InstallationBootstrapServiceTest.java backend/src/main/resources/db/migration
git commit -m "feat: bootstrap unlinked installation"
```

---

## Task 3: Expand SaaS Link Contract

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkRequest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkResponse.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/HttpLicenseSaasLinkClientTest.java`

- [ ] **Step 1: Write request/response contract test**

Assert the request can omit local store/company fields and response contains official data:

```java
var request = new LicenseSaasLinkRequest(
        "ABC123",
        installationId,
        "INST-1",
        "public-key",
        null,
        null,
        null,
        null);

assertThat(request.storeId()).isNull();
assertThat(request.taxId()).isNull();
```

Add response assertion:

```java
var response = mapper.readValue(json, LicenseSaasLinkResponse.class);
assertThat(response.companyTaxId()).isEqualTo("B12345678");
assertThat(response.companyName()).isEqualTo("EMPRESA REAL");
assertThat(response.storeCode()).isEqualTo("001");
assertThat(response.storeName()).isEqualTo("TIENDA 001");
```

- [ ] **Step 2: Run focused test**

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=HttpLicenseSaasLinkClientTest" test
```

Expected: FAIL because fields do not exist.

- [ ] **Step 3: Update response record**

Change `LicenseSaasLinkResponse` to:

```java
public record LicenseSaasLinkResponse(
        String licenseReference,
        UUID companyId,
        UUID storeId,
        String companyTaxId,
        String companyName,
        Map<String, String> companyAddress,
        String storeCode,
        String storeName,
        Map<String, String> storeAddress,
        Instant validUntil,
        LicenseSaasStatus status,
        int maxWindows,
        int maxPda,
        String taxId,
        TaxpayerType taxpayerType,
        TaxRegime impuestos,
        String installationToken) {
}
```

Keep `taxId` temporarily for compatibility during migration, but prefer `companyTaxId` in new code.

- [ ] **Step 4: Verify**

Run the focused test. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkRequest.java backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkResponse.java backend/src/test/java/com/tpverp/backend/licensing/HttpLicenseSaasLinkClientTest.java
git commit -m "feat: expand saas license link contract"
```

---

## Task 4: Link New Installation Without Local Company Or Store

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/organization/CompanyRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/organization/StoreRepository.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasLinkServiceTest.java`

- [ ] **Step 1: Write failing new-install link test**

Add:

```java
@Test
void vinculaInstalacionNuevaCreandoEmpresaYTiendaDesdeSaas() {
    var installation = installation();
    when(installations.findAll()).thenReturn(List.of(installation));
    when(stores.findAll()).thenReturn(List.of());
    when(client.link(any())).thenReturn(saasResponse());

    service.link("ABC123");

    var companyCaptor = ArgumentCaptor.forClass(Company.class);
    verify(companies).save(companyCaptor.capture());
    assertThat(companyCaptor.getValue().getTaxId()).isEqualTo("B12345678");
    assertThat(companyCaptor.getValue().getRazonSocial()).isEqualTo("EMPRESA REAL");

    var storeCaptor = ArgumentCaptor.forClass(Store.class);
    verify(stores).save(storeCaptor.capture());
    assertThat(storeCaptor.getValue().getCodigoTienda()).isEqualTo("001");
}
```

This requires injecting `CompanyRepository` into `LicenseSaasLinkService`.

- [ ] **Step 2: Run focused test**

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=LicenseSaasLinkServiceTest" test
```

Expected: FAIL because service requires current store.

- [ ] **Step 3: Inject company repository**

Update constructor in `LicenseSaasLinkService` and `LicensingConfiguration`:

```java
private final CompanyRepository companies;
```

- [ ] **Step 4: Make current store optional**

Replace `currentStore()` usage with:

```java
Optional<Store> existingStore = stores.findAll().stream().findFirst();
LicenseSaasLinkRequest request = existingStore
        .map(store -> requestWithLocalStore(normalizedCode, installation, store))
        .orElseGet(() -> requestWithoutLocalStore(normalizedCode, installation));
```

- [ ] **Step 5: Create official organization from SaaS**

Add:

```java
private Store resolveStoreFromSaas(LicenseSaasLinkResponse response, Optional<Store> existingStore) {
    if (existingStore.isPresent()) {
        validateResponse(existingStore.get().getEmpresa(), response);
        return existingStore.get();
    }
    Company company = companies.save(new Company(
            SpanishTaxId.normalize(response.companyTaxId()),
            required(response.companyName(), "companyName"),
            response.companyAddress() == null ? Map.of() : response.companyAddress()));
    return stores.save(new Store(
            company,
            required(response.storeCode(), "storeCode"),
            required(response.storeName(), "storeName"),
            response.storeAddress() == null ? Map.of() : response.storeAddress(),
            hashAddress(response.storeAddress()),
            "Atlantic/Canary",
            "EUR",
            "es-ES"));
}
```

Adjust constructor arguments to the actual `Store` constructor in the repo.

- [ ] **Step 6: Verify**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=LicenseSaasLinkServiceTest" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasLinkService.java backend/src/main/java/com/tpverp/backend/licensing/LicensingConfiguration.java backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasLinkServiceTest.java
git commit -m "feat: create organization from saas license link"
```

---

## Task 5: Deprecate Manual File Activation As Main Flow

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/api/LicenseController.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/api/LicenseControllerContractTest.java`

- [ ] **Step 1: Write failing controller tests**

Add:

```java
@Test
void localFileActivationIsBlockedWhenSupportModeDisabled() throws Exception {
    mvc.perform(post("/api/v1/licenses/activate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"license":"abc","confirmationHash":"hash"}
                    """))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run focused test**

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=LicenseControllerContractTest" test
```

Expected: FAIL because activation is always available.

- [ ] **Step 3: Add configuration flag**

In `application.yml`:

```yaml
tpv:
  license:
    local-file-activation-enabled: false
```

- [ ] **Step 4: Gate preview and activate**

Inject:

```java
@Value("${tpv.license.local-file-activation-enabled:false}")
private boolean localFileActivationEnabled;
```

Before calling local `LicenseService`:

```java
if (!localFileActivationEnabled) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "message.license.local_file_activation_disabled");
}
```

- [ ] **Step 5: Verify**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=LicenseControllerContractTest" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/licensing/api/LicenseController.java backend/src/main/resources/application.yml backend/src/test/java/com/tpverp/backend/licensing/api/LicenseControllerContractTest.java
git commit -m "feat: gate local license file activation"
```

---

## Task 6: Add Manual SaaS Validation Endpoint

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/api/LicenseController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasValidationService.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/api/LicenseControllerContractTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasValidationServiceTest.java`

- [ ] **Step 1: Write failing endpoint test**

Add:

```java
@Test
void validatesSaasLicenseManually() throws Exception {
    mvc.perform(post("/api/v1/licenses/validate-saas"))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: Make service return response**

Change:

```java
public void validateActiveLicense()
```

to:

```java
public LicenseSaasValidationResponse validateActiveLicense()
```

Return current status after save.

- [ ] **Step 3: Add controller method**

```java
@PostMapping("/validate-saas")
public LicenseSaasValidationResponse validateSaas() {
    return saasValidation.validateActiveLicense();
}
```

Inject `LicenseSaasValidationService` into `LicenseController`.

- [ ] **Step 4: Verify**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=LicenseControllerContractTest,LicenseSaasValidationServiceTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/licensing/api/LicenseController.java backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasValidationService.java backend/src/test/java/com/tpverp/backend/licensing/api/LicenseControllerContractTest.java backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasValidationServiceTest.java
git commit -m "feat: add manual saas license validation"
```

---

## Task 7: Add Extended SaaS License States

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasStatus.java`
- Modify: `backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasValidationService.java`
- Test: `backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasValidationServiceTest.java`

- [ ] **Step 1: Write failing state tests**

Add tests:

```java
@Test
void expiredSaasStatusRestrictsLicense() {
    when(client.validate(any())).thenReturn(new LicenseSaasValidationResponse(
            LicenseSaasStatus.CADUCADA,
            NOW.minusSeconds(1)));

    service.validateActiveLicense();

    verify(licenses).save(argThat(license -> !license.isOperationalAt(NOW)));
}
```

- [ ] **Step 2: Add enum values**

```java
public enum LicenseSaasStatus {
    VALIDA,
    BLOQUEADA_MANUAL,
    CADUCADA,
    REQUIERE_ACTUALIZACION
}
```

- [ ] **Step 3: Handle states minimally**

In validation:

```java
if (response.status() == LicenseSaasStatus.VALIDA) {
    license.markSaasValidated(now, response.validUntil());
} else {
    license.markSaasBlocked(now);
}
```

This is intentionally strict for now: any non-valid SaaS status blocks writes.

- [ ] **Step 4: Add migration constraint**

Create `backend/src/main/resources/db/migration/V37__estado_saas_licencia_extendido.sql`:

```sql
alter table licencia drop constraint licencia_estado_saas_check;
alter table licencia add constraint licencia_estado_saas_check
    check (estado_saas in ('VALIDA', 'BLOQUEADA_MANUAL', 'CADUCADA', 'REQUIERE_ACTUALIZACION'));
```

- [ ] **Step 5: Verify**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=LicenseSaasValidationServiceTest,TpvErpBackendApplicationTests" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasStatus.java backend/src/main/java/com/tpverp/backend/licensing/LicenseSaasValidationService.java backend/src/main/resources/db/migration backend/src/test/java/com/tpverp/backend/licensing/LicenseSaasValidationServiceTest.java
git commit -m "feat: support extended saas license states"
```

---

## Task 8: Final Verification

**Files:**
- All files modified above.

- [ ] **Step 1: Run reduced license suite**

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=InstallationBootstrapServiceTest,InstallationStatusServiceTest,LicenseSaasLinkServiceTest,LicenseSaasValidationServiceTest,LicenseControllerContractTest,TpvErpBackendApplicationTests" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run migration check**

If reduced suite does not exercise Flyway, run:

```powershell
cd backend
$env:TPV_TEST_DB_USERNAME='postgres'; $env:TPV_TEST_DB_PASSWORD='admin'; .\mvnw.cmd "-Dtest=TpvErpBackendApplicationTests" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Check git status**

```powershell
git status --short --branch
```

Expected: only intended files changed.

- [ ] **Step 4: Commit any final fixes**

```powershell
git add backend/src/main/java backend/src/main/resources backend/src/test/java docs/superpowers/specs docs/superpowers/plans
git commit -m "feat: make saas primary license flow"
```

Do not create this final commit if each task already committed and no remaining changes exist.

---

## Implementation Notes

- Keep code identifiers in English.
- Keep existing database column names unless a migration already exists for a deliberate rename.
- Do not remove the local `LicenseService` in this slice; gate it.
- Do not implement the SaaS server in this backend slice.
- Use the existing DPAPI credential store for the SaaS token.
- Keep tests focused. Avoid broad full-suite runs unless migration or application context changes require it.

## Coverage Check

- SaaS as primary authority: Tasks 3, 4, 5.
- New installation without company/store: Tasks 1, 2, 4.
- Public/private key handling: Task 2 keeps identity generation; no manual key handling.
- Local license as offline cache: Tasks 1, 6, 7.
- Manual file license deprecated: Task 5.
- Statuses `SIN_VINCULAR`, `VINCULADA`, `OFFLINE`, `BLOQUEADA`: Tasks 1 and 7 map these to backend operational modes.
- Reduced tests: Task 8.

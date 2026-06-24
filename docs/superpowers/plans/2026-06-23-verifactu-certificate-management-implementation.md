# VERI*FACTU Certificate Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Importar, custodiar y utilizar certificados AEAT por empresa sin almacenar la contrasena original ni incluir claves privadas en PostgreSQL o backups.

**Architecture:** PostgreSQL conservara metadatos y cadena publica; un almacen local escribira PKCS#8 cifrado con DPAPI de maquina. Un servicio transaccional coordinara validacion, sustitucion y auditoria, mientras el transporte reconstruira un `KeyStore` efimero para mTLS.

**Tech Stack:** Java 25, Spring Boot 4, JPA/Hibernate, PostgreSQL 18, Flyway, JNA/Windows DPAPI, Java Security, JUnit 5, Mockito y AssertJ.

---

### Task 1: Persistencia publica del certificado

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__certificados_verifactu.sql`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/ManagedCertificateStatus.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/ManagedVerifactuCertificate.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/ManagedVerifactuCertificateRepository.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/ManagedVerifactuCertificateTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`

- [ ] **Step 1: Escribir pruebas de estados y retencion de metadatos**

```java
var certificate = ManagedVerifactuCertificate.active(metadata, publicChain, relativeSecret, now, userId);
certificate.replace(now, userId);
assertThat(certificate.getStatus()).isEqualTo(ManagedCertificateStatus.ANTERIOR);
certificate.removeSecret(now.plus(365, DAYS));
assertThat(certificate.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
assertThat(certificate.getFingerprint()).isEqualTo(metadata.fingerprint());
```

- [ ] **Step 2: Ejecutar RED**

Run: `mvnw.cmd -q -Dtest=ManagedVerifactuCertificateTest test`
Expected: FAIL porque el modelo no existe.

- [ ] **Step 3: Implementar entidad y repositorio**

La tabla `certificado_verifactu` incluira empresa, estado, sujeto, emisor,
serie, NIF, vigencia, huella SHA-256, cadena publica PKCS#7, ruta relativa del
secreto y marcas de importacion/sustitucion/eliminacion. Dos indices parciales
garantizaran como maximo un `ACTIVO` y un `ANTERIOR` por empresa.

El repositorio expondra:

```java
Optional<ManagedVerifactuCertificate> findByCompanyIdAndStatus(UUID companyId, ManagedCertificateStatus status);
List<ManagedVerifactuCertificate> findAllByStatusAndReplacedAtBefore(ManagedCertificateStatus status, Instant limit);
```

- [ ] **Step 4: Ejecutar GREEN y migracion PostgreSQL**

Run: `mvnw.cmd -q "-Dtest=ManagedVerifactuCertificateTest,PostgreSqlMigrationTest" test`
Expected: PASS y esquema en version 15.

### Task 2: Secreto local cifrado con DPAPI de maquina

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/shared/crypto/WindowsMachineDpapiSecretProtector.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuCertificateSecretStore.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuCertificateSecretStoreTest.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Escribir pruebas de escritura atomica, lectura y borrado confinado**

```java
var relative = store.write(companyId, certificateId, privatePkcs8);
assertThat(store.read(relative)).containsExactly(privatePkcs8);
assertThatThrownBy(() -> store.read(Path.of("..", "fuera")))
        .isInstanceOf(IllegalArgumentException.class);
store.delete(relative);
assertThat(secretRoot.resolve(relative)).doesNotExist();
```

- [ ] **Step 2: Ejecutar RED**

Run: `mvnw.cmd -q -Dtest=VerifactuCertificateSecretStoreTest test`
Expected: FAIL porque el almacen no existe.

- [ ] **Step 3: Implementar proteccion y almacenamiento**

`WindowsMachineDpapiSecretProtector` usara `CRYPTPROTECT_LOCAL_MACHINE`. El
almacen escribira primero un temporal dentro de `tpv.verifactu.secret-directory`,
verificara un descifrado de prueba y publicara con movimiento atomico. Toda ruta
se normalizara y comprobara bajo el directorio raiz.

- [ ] **Step 4: Ejecutar GREEN**

Run: `mvnw.cmd -q -Dtest=VerifactuCertificateSecretStoreTest test`
Expected: PASS.

### Task 3: Analisis y validacion del PKCS#12

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/CertificateTaxIdExtractor.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/ImportedCertificateMaterial.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuCertificateImporter.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/CertificateTaxIdExtractorTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuCertificateImporterTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuPkcs12KeyStoreLoader.java`

- [ ] **Step 1: Escribir pruebas de identidad y material criptografico**

```java
assertThat(extractor.extract(subject("SERIALNUMBER=IDCES-B12345674")))
        .contains("B12345674");
assertThatThrownBy(() -> importer.importPkcs12(bytes, wrongPassword, "B12345674"))
        .hasMessageContaining("PKCS#12");
assertThatThrownBy(() -> importer.importPkcs12(bytes, password, "A58818501"))
        .hasMessageContaining("NIF");
```

Tambien se probaran ausencia de clave, varias identidades, certificado fuera de
vigencia y correspondencia clave-publica mediante firma/verificacion de un reto.

- [ ] **Step 2: Ejecutar RED**

Run: `mvnw.cmd -q "-Dtest=CertificateTaxIdExtractorTest,VerifactuCertificateImporterTest" test`
Expected: FAIL porque importador y extractor no existen.

- [ ] **Step 3: Implementar importador**

El extractor leera `SERIALNUMBER` y `2.5.4.97`, eliminara prefijos habituales
(`IDCES-`, `VATES-`) y validara con `SpanishTaxId`. El importador devolvera
PKCS#8, cadena PKCS#7 y metadatos publicos; limpiara copias temporales de la
contrasena en un bloque `finally`.

- [ ] **Step 4: Ejecutar GREEN**

Run: `mvnw.cmd -q "-Dtest=CertificateTaxIdExtractorTest,VerifactuCertificateImporterTest" test`
Expected: PASS.

### Task 4: Importacion y sustitucion atomica

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/ManagedCertificateView.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuCertificateManagementService.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuCertificateManagementServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/audit/AuditService.java`
- Modify: `backend/src/main/resources/i18n/messages_es.properties`
- Modify: `backend/src/main/resources/i18n/messages_en.properties`
- Modify: `backend/src/main/resources/i18n/messages_zh.properties`

- [ ] **Step 1: Escribir pruebas de alta, sustitucion y compensacion**

```java
var imported = service.importCertificate(pkcs12, password, authentication);
assertThat(imported.status()).isEqualTo(ManagedCertificateStatus.ACTIVO);
verify(audit).record(eq("VERIFACTU_CERTIFICATE_IMPORTED"), eq(EXITO), safeMetadata());

doThrow(new IllegalStateException("disk")).when(secrets).write(any(), any(), any());
assertThatThrownBy(() -> service.importCertificate(pkcs12, password, authentication));
verify(active, never()).replace(any(), any());
```

La sustitucion comprobara que el activo pasa a `ANTERIOR`, el anterior previo
pasa a `ELIMINADO` y su secreto se borra solo despues de publicar el nuevo.
La eliminacion manual exigira la confirmacion exacta `ELIMINAR CERTIFICADO`,
marcara el activo como `ELIMINADO`, borrara su secreto y conservara metadatos.

- [ ] **Step 2: Ejecutar RED**

Run: `mvnw.cmd -q -Dtest=VerifactuCertificateManagementServiceTest test`
Expected: FAIL porque el servicio no existe.

- [ ] **Step 3: Implementar servicio transaccional y compensacion de archivos**

El servicio resolvera empresa/usuario con `CurrentOrganization`, validara el NIF
de empresa, escribira el secreto, persistira metadatos y registrara auditoria sin
secretos. Ante rollback eliminara el secreto nuevo mediante sincronizacion de
transaccion. La respuesta nunca incluira ruta ni material criptografico.

La eliminacion manual registrara `VERIFACTU_CERTIFICATE_DELETED`; importacion y
sustitucion usaran `VERIFACTU_CERTIFICATE_IMPORTED` y
`VERIFACTU_CERTIFICATE_REPLACED`.

- [ ] **Step 4: Centralizar mensajes y ejecutar GREEN**

Run: `mvnw.cmd -q -Dtest=VerifactuCertificateManagementServiceTest,LocalizedMessagesTest test`
Expected: PASS.

### Task 5: API ADMIN y transporte mTLS gestionado

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/ManagedCertificateKeyStoreFactory.java`
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuCertificateController.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/ManagedCertificateKeyStoreFactoryTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/ConfiguredVerifactuTransport.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuTransportConfiguration.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuSubmissionProperties.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuSubmissionPropertiesFactory.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuAdminService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuControllerContractTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/verifactu/ConfiguredVerifactuTransportTest.java`

- [ ] **Step 1: Escribir pruebas de KeyStore, endpoint y transporte**

```java
var keyStore = factory.create(managedCertificate);
assertThat(keyStore.isKeyEntry("verifactu")).isTrue();
verify(secrets).read(managedCertificate.getSecretPath());

assertAdminOnly("importCertificate", MultipartFile.class, char[].class, Authentication.class);
verify(managedKeyStores).activeForCurrentCompany();
```

- [ ] **Step 2: Ejecutar RED**

Run: `mvnw.cmd -q "-Dtest=ManagedCertificateKeyStoreFactoryTest,ConfiguredVerifactuTransportTest,VerifactuControllerContractTest" test`
Expected: FAIL por ausencia de fabrica y API.

- [ ] **Step 3: Implementar fabrica, API y adaptacion del transporte**

La API bajo `/api/v1/verifactu/admin/certificates` ofrecera `GET`, `POST`
multipart y `DELETE` con confirmacion, todos con `hasRole('ADMIN')`. El transporte
obtendra certificado activo por empresa y construira un `KeyStore` efimero con
contrasena aleatoria, limpiada tras crear el `HttpClient`.

La fabrica rechazara certificados fuera de vigencia; el trabajador mantendra el
registro en cola como pendiente y la venta ya confirmada no se revertira.

`VerifactuSubmissionProperties` conservara modo, nombre e id de sistema, pero
eliminara ruta y contrasena de certificado.

- [ ] **Step 4: Ejecutar GREEN**

Run: `mvnw.cmd -q "-Dtest=ManagedCertificateKeyStoreFactoryTest,ConfiguredVerifactuTransportTest,VerifactuControllerContractTest,VerifactuSubmissionPropertiesTest,VerifactuSubmissionPropertiesFactoryTest" test`
Expected: PASS.

### Task 6: Avisos diarios y purga mensual

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuCertificateMaintenance.java`
- Create: `backend/src/test/java/com/tpverp/backend/verifactu/VerifactuCertificateMaintenanceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/ManagedVerifactuCertificate.java`
- Modify: `backend/src/main/java/com/tpverp/backend/verifactu/VerifactuAdminStatusView.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Escribir pruebas de aviso y retencion**

```java
maintenance.checkExpiry();
verify(audit).record(eq("VERIFACTU_CERTIFICATE_EXPIRY_WARNING"), eq(EXITO), any());
maintenance.purgePrevious();
verify(secrets).delete(expiredPrevious.getSecretPath());
assertThat(expiredPrevious.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
```

La prueba verificara un unico aviso por dia desde 30 dias antes de caducar y
purga solo al alcanzar un ano desde `replacedAt`.

- [ ] **Step 2: Ejecutar RED**

Run: `mvnw.cmd -q -Dtest=VerifactuCertificateMaintenanceTest test`
Expected: FAIL porque el mantenimiento no existe.

- [ ] **Step 3: Implementar tareas programadas**

`checkExpiry` se ejecutara diariamente y `purgePrevious` el primer dia de cada
mes. El estado administrativo leera siempre PostgreSQL y mostrara dias restantes
sin intentar abrir la clave privada.

- [ ] **Step 4: Ejecutar GREEN**

Run: `mvnw.cmd -q -Dtest=VerifactuCertificateMaintenanceTest,VerifactuAdminServiceTest test`
Expected: PASS.

### Task 7: Verificacion integrada reducida

**Files:**
- Modify: `backend/src/test/java/com/tpverp/backend/TpvErpBackendApplicationTests.java`
- Modify: `backend/src/test/java/com/tpverp/backend/backup/application/BackupArchiveServiceTest.java`

- [ ] **Step 1: Probar contexto y exclusion de secretos en backup**

La prueba de contexto comprobara que el transporte usa el almacen gestionado.
La prueba de backup confirmara que solo incluye dump e imagenes, nunca el
directorio `tpv.verifactu.secret-directory`.

- [ ] **Step 2: Ejecutar pruebas focalizadas**

Run: `mvnw.cmd -q "-Dtest=ManagedVerifactuCertificateTest,VerifactuCertificateSecretStoreTest,CertificateTaxIdExtractorTest,VerifactuCertificateImporterTest,VerifactuCertificateManagementServiceTest,ManagedCertificateKeyStoreFactoryTest,ConfiguredVerifactuTransportTest,VerifactuControllerContractTest,VerifactuCertificateMaintenanceTest,PostgreSqlMigrationTest" test`
Expected: PASS.

- [ ] **Step 3: Ejecutar suite completa PostgreSQL una vez**

Run: `mvnw.cmd -q test`
Expected: PASS usando `tpv_erp_test` y password local configurada.

- [ ] **Step 4: Comprobar diff**

Run: `git diff --check`
Expected: exit code 0.

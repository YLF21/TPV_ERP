# TPV ERP Backend

Backend local modular construido con Java 25, Spring Boot 4.0.6 y PostgreSQL 18.

## Preparacion local

1. Crear las bases y usuarios descritos en `scripts/create-databases.sql`.
2. Definir las variables del perfil deseado tomando `.env.example` como referencia.
3. No guardar contrasenas reales en archivos versionados.

Para desarrollo:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:TPV_DB_PASSWORD = "<contrasena-local>"
.\mvnw.cmd spring-boot:run
```

En el primer arranque se crean:

- Una instalacion y su identidad RSA protegida con Windows DPAPI.
- Empresa y tienda de demostracion con vigencia de 30 dias.
- Terminal local `SERVIDOR`.
- Rol y usuario protegido `ADMIN`, con contrasena inicial `0000`.
- Configuracion de backup a las 12:00, con 30 copias diarias y 72 mensuales.

Cambiar la contrasena inicial de `ADMIN` cuando se implemente la pantalla de configuracion inicial.

## Licencias

La solicitud para el emisor se obtiene en:

```text
GET /api/v1/installation/license-request
```

El backend verifica las licencias con la clave publica del proveedor indicada por:

```text
TPV_LICENSE_ISSUER_PUBLIC_KEY_FILE
```

La clave privada correspondiente solo debe existir en el PKCS#12 de `license-issuer`.

## API inicial

- `/api/v1/installation`: estado y solicitud de licencia.
- `/api/v1/auth`: login, renovacion y logout.
- `/api/v1/licenses`: previsualizacion, activacion e historial.
- `/api/v1/users` y `/api/v1/roles`: administracion de seguridad.
- `/api/v1/terminals`: solicitud, aprobacion y desactivacion.
- `/api/v1/backups`: configuracion, ejecucion, historial y restauracion.
- `/api/v1/audit`: consulta y borrado administrativo confirmado.

OpenAPI y Swagger UI solo se habilitan en el perfil `dev`.

## Certificado VeriFactu en Windows

En produccion, la clave privada VeriFactu debe almacenarse en el directorio
protegido `C:\ProgramData\TPV ERP\secrets\verifactu` y el backend debe ejecutarse
como `NT SERVICE\TPVERPBackend`. El aprovisionamiento de ACL, las variables del
servicio y el procedimiento de backup, restauracion y reimportacion se describen
en [`../docs/verifactu-certificate-windows-operations.md`](../docs/verifactu-certificate-windows-operations.md).

## Pruebas

Las pruebas de integracion PostgreSQL aceptan cualquiera de estas familias de
variables. No es necesario definir ambas a la vez:

```powershell
$env:TPV_TEST_DB_URL = "jdbc:postgresql://localhost:5432/tpv_erp_test"
$env:TPV_TEST_DB_USERNAME = "tpv_erp_test"
$env:TPV_TEST_DB_PASSWORD = "<contrasena-local-de-pruebas>"
$env:TPV_ERP_TEST_DB_URL = $null
$env:TPV_ERP_TEST_DB_USER = $null
$env:TPV_ERP_TEST_DB_PASSWORD = $null
.\mvnw.cmd test
```

Compatibilidad con la familia historica:

```powershell
$env:TPV_ERP_TEST_DB_URL = "jdbc:postgresql://localhost:5432/tpv_erp_test"
$env:TPV_ERP_TEST_DB_USER = "tpv_erp_test"
$env:TPV_ERP_TEST_DB_PASSWORD = "<contrasena-local-de-pruebas>"
.\mvnw.cmd test
```

`PaymentPlatformMigrationPostgreSqlTest` valida tanto una instalacion vacia
como la actualizacion desde V45 hasta la version actual (V60). Cada prueba usa
un esquema temporal y lo elimina al finalizar.

## Datafonos y simuladores locales

La configuracion se gestiona desde APP GESTION y se consulta dinamicamente por
terminal. En modo `SIMULATED` estan disponibles Redsys TPV-PC, PAYTEF,
PAYCOMET y Global Payments. El parametro no sensible `simulatorOutcome`
permite probar `APPROVED`, `DECLINED`, `TIMEOUT` y `ERROR`; para resolver un
timeout mediante consulta se puede usar `simulatorQueryOutcome=APPROVED`.

Los simuladores admiten cobro, consulta, anulacion, devolucion, recibo y
conciliacion sin contactar ningun servicio externo. El modo `LIVE` permanece
bloqueado con `SDK_NOT_INSTALLED` hasta instalar y certificar el SDK oficial
del proveedor. No se deben introducir credenciales reales, PAN, PIN o CVV en
`provider_parameters` ni en archivos versionados.

## Ventas pendientes de cliente

APP VENTA puede confirmar albaranes y facturas de venta con cobro inicial
completo, parcial o inexistente. El documento comercial es la fuente de verdad:
el saldo se calcula como total menos pagos reales y `PENDIENTE` nunca es una
forma de pago ni crea una fila en `documento_pago`.

API principal:

- `POST /api/v1/pos/customer-pending-sales/quote`: total autoritativo antes de
  cobrar o confirmar.
- `POST /api/v1/pos/customer-pending-sales/card-charges`: inicia el cargo
  integrado de la venta pendiente.
- `POST /api/v1/pos/customer-pending-sales`: confirma el albaran o factura de
  forma idempotente mediante `checkoutId`.
- `GET /api/v1/customer-receivables`: lista deudas con filtros.
- `POST /api/v1/customer-receivables/{documentId}/payments`: registra un cobro
  posterior idempotente mediante `requestId`.
- `GET /api/v1/commercial-reports/daily?date=AAAA-MM-DD`: separa facturado,
  cobrado en ventas actuales, nuevo pendiente, cobros de deuda anterior y
  entrada real.

Los permisos son `CUSTOMER_RECEIVABLES_READ`,
`CUSTOMER_RECEIVABLES_CREATE` y `CUSTOMER_RECEIVABLES_PAY`; `ADMIN` incluye
los tres. La migracion de esta funcion es
`V90__customer_pending_sales.sql`. Consulte el procedimiento de operacion y
recuperacion en `../docs/customer-pending-sales-operations.md`.

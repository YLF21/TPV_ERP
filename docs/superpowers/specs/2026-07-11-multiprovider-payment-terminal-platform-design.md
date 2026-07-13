# Plataforma multiproveedor de datáfonos

## Objetivo

Completar una plataforma común de pagos presenciales para Redsys TPV-PC,
PAYTEF, PAYCOMET y Global Payments. En esta fase se implementan simuladores
contractuales, persistencia, recuperación, anulaciones, devoluciones, recibos,
pagos divididos, secretos y el contrato del puente local. Los conectores reales
permanecen desactivados hasta disponer del SDK oficial, credenciales, terminal de
pruebas y homologación de cada adquirente.

## Límites

- No se inventarán protocolos, códigos de respuesta ni formatos propietarios.
- No se enviarán operaciones financieras a servicios externos.
- No se almacenarán PAN completo, PIN, CVV ni datos sensibles del titular.
- Los cambios permanecerán locales, sin commit ni push.
- Los secretos no se almacenarán en `provider_parameters` ni se devolverán por API.

## Proveedores y modos

El catálogo común reconoce `REDSYS_TPV_PC`, `PAYTEF`, `PAYCOMET` y
`GLOBAL_PAYMENTS`. Cada proveedor admite:

- `SIMULATED`: adaptador funcional, determinista y configurable.
- `LIVE`: adaptador registrado pero no disponible mientras falte el SDK oficial.
- `MANUAL`: registro de tarjeta procesada externamente, sin comunicación integrada.

La configuración de tienda decide qué proveedores y modos se permiten. La
configuración de terminal selecciona un proveedor, habilitación, modo, nombre
visible, referencia opaca de secretos y parámetros no sensibles validados.

## Arquitectura

### Contrato del gateway

`PaymentTerminalGateway` expone capacidades y operaciones normalizadas:

- emparejamiento y estado del emparejamiento;
- prueba de conexión;
- cobro;
- consulta de operación;
- anulación de autorización;
- devolución total o parcial;
- recuperación de recibo;
- conciliación, solamente si el proveedor declara esa capacidad.

Cada operación recibe una clave idempotente, terminal, proveedor, moneda EUR,
importe y referencia de configuración. El resultado utiliza estados comunes:
`PENDING`, `APPROVED`, `DECLINED`, `CANCELLED`, `REFUNDED`, `PARTIALLY_REFUNDED`,
`TIMEOUT`, `ERROR` y `REVIEW_REQUIRED`.

Los simuladores de los cuatro proveedores implementan el mismo contrato. Los
adaptadores LIVE devuelven un error tipado `SDK_NOT_INSTALLED`; nunca simulan una
aprobación en producción.

### Persistencia

`payment_terminal_operation` es la fuente de verdad de cada operación. Guarda:

- identificador e idempotency key;
- proveedor, terminal, tienda y modo;
- tipo de operación y relación con la operación original;
- importe, moneda y suma devuelta;
- estado actual;
- referencia y autorización externas sanitizadas;
- hash y versión de configuración;
- ticket o pago asociado;
- leases de procesamiento, reintentos y fechas.

`payment_terminal_event` es append-only y conserva intentos y transiciones con
fecha, estado anterior/nuevo, código normalizado, diagnóstico seguro y metadatos
no sensibles. Nunca contiene secretos ni datos de tarjeta.

La tabla `pos_card_checkout` se mantiene como compatibilidad del flujo POS y se
enlaza con la nueva operación. La migración de datos existentes asigna proveedor
Redsys y conserva referencias, autorizaciones y estados actuales.

### Orquestación e idempotencia

El flujo de cobro es:

1. Cotizar el ticket con datos autoritativos.
2. Reservar la operación mediante idempotency key y hash de la venta.
3. Resolver proveedor, configuración y gateway.
4. Enviar el cobro una sola vez.
5. Persistir resultado y evento antes de crear el ticket.
6. Crear el ticket únicamente con resultado `APPROVED`.
7. Reanudar la creación del ticket al consultar o repetir la misma petición.

Una misma clave con parámetros diferentes devuelve conflicto. Un timeout nunca
inicia automáticamente otro cobro: pasa a consulta o revisión manual.

### Recuperación

Un worker con lease consulta operaciones `PENDING` y `TIMEOUT` usando backoff
acotado. Si el proveedor no ofrece consulta, la operación pasa a
`REVIEW_REQUIRED`. El worker puede reanudar la creación del ticket aprobado, pero
no repite el cargo.

Tras reiniciar el backend, las operaciones pendientes continúan desde PostgreSQL.
La interfaz permite consultar manualmente sin generar una nueva idempotency key.

### Anulaciones y devoluciones

Una anulación solo se permite para una autorización aprobada no liquidada cuando
el proveedor declara `VOID`. Una devolución requiere una operación aprobada y no
puede superar el importe pendiente de devolución.

Las devoluciones parciales se acumulan de forma transaccional. Cuando el terminal
aprueba la devolución se crea el documento comercial/fiscal correspondiente. Si
la operación financiera queda aprobada pero falla el documento, se conserva en
`REVIEW_REQUIRED` para reanudación; nunca se repite automáticamente la devolución.

### Pagos divididos

APP VENTA permite combinar efectivo, tarjeta manual y una o varias operaciones
integradas. El ticket se confirma cuando la suma aprobada cubre el total. Si un
cobro posterior falla, los anteriores permanecen visibles y el usuario puede
continuar, anular los autorizables o cancelar mediante devolución. No se ocultan
operaciones ya aprobadas.

### Recibos y conciliación

Los gateways pueden devolver un recibo sanitizado con texto permitido, comercio,
terminal, fecha, importe, referencia y autorización truncada. Se almacena para
reimpresión mediante `HardwareBridge`.

La conciliación registra lotes por terminal y jornada, totales ERP/proveedor y
discrepancias. En simulación es determinista. En LIVE queda no disponible cuando
el SDK no declare la capacidad.

### Secretos y puente local

`PaymentSecretStore` guarda material cifrado mediante protección de máquina y
devuelve referencias opacas versionadas. La API permite crear, rotar y eliminar
referencias con permiso administrativo, pero nunca leer el valor.

El contrato del puente local define health, versión, capacidades, pairing y
operaciones de pago. La comunicación futura será autenticada, limitada a
localhost o named pipes y con allow-list de comandos. En esta fase se implementan
contrato, cliente y stub `SDK_NOT_INSTALLED`; los drivers reales requieren los
binarios oficiales.

## API

- Configuración y capacidades por terminal.
- Crear cobro y consultar estado por operación/idempotency key.
- Anular y devolver una operación aprobada.
- Consultar y reimprimir recibo.
- Listar historial de operaciones y eventos con permisos.
- Iniciar y consultar conciliación.
- Gestionar referencias de secretos sin exponerlos.

Todos los errores usan Problem Details con código estable. Los errores de
transporte se distinguen de denegaciones, validaciones y resultados financieros.

## Frontend

Configuración muestra dinámicamente los cuatro proveedores, modo simulado/real,
capacidades, parámetros no sensibles, estado de emparejamiento y prueba de
conexión. LIVE deshabilitado muestra `SDK no instalado`.

Venta presenta estado real de cada operación, consulta segura, pagos divididos y
recibos. Las pantallas de historial permiten devolución/anulación según capacidad,
estado y permisos. Ningún error HTTP genérico se presenta como resultado incierto
si el backend confirma que el cargo no fue enviado.

## Seguridad y permisos

- Cobrar: permisos de venta existentes.
- Consultar operaciones y recibos: venta o gestión de ventas.
- Anular/devolver: permiso específico y autorización reforzada configurable.
- Conciliar y configurar: permisos administrativos.
- Secretos: permiso específico, auditoría obligatoria y valores nunca retornados.

Todos los logs y eventos aplican redacción. Referencias externas se validan y
limitan en longitud.

## Pruebas

- Contrato común ejecutado contra los cuatro simuladores.
- Casos aprobado, rechazado, timeout, error, consulta, anulación y devolución.
- Idempotencia, conflicto de hash, concurrencia y recuperación tras reinicio.
- Límites de devoluciones parciales y compensación documental.
- Pagos divididos con combinaciones y fallos intermedios.
- Persistencia PostgreSQL, migraciones y ledger append-only.
- Seguridad de secretos, redacción y permisos.
- Componentes frontend, integración API y builds de APP VENTA/GESTIÓN.
- Suite completa backend y frontend antes de declarar la fase terminada.

## Entrega por fases

1. Modelo común, capacidades, migración y ledger.
2. Simuladores multiproveedor y routing dinámico.
3. Recuperación y consulta de operaciones inciertas.
4. Anulaciones, devoluciones y documentos asociados.
5. Pagos divididos, recibos y conciliación simulada.
6. Secret store, contrato del bridge y stubs LIVE.
7. Configuración e interfaces frontend completas.
8. Revisión transversal, migraciones PostgreSQL y suites completas.

Los conectores LIVE se implementarán uno a uno después de recibir SDK,
documentación, credenciales sandbox, terminal físico y criterios de homologación.

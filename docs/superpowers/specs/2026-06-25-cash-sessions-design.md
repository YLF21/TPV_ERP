# Diseno De Caja Y Arqueos

## Objetivo

Incorporar sesiones de caja por terminal, movimientos fisicos de efectivo,
retiradas documentadas, arqueo ciego y consultas restringidas. Cada terminal
mantendra como maximo una sesion abierta y podra realizar varias sesiones
consecutivas durante el mismo dia.

## Alcance

- Una caja independiente por terminal.
- Todos los usuarios con permiso de venta pueden abrir y cerrar sesiones.
- Sin sesion abierta no se pueden crear, aparcar, recuperar, confirmar ni cobrar
  ventas en la terminal.
- Tickets y cobros de facturas pertenecen a la sesion que procesa el pago.
- Se admiten pagos mixtos; solo la parte en efectivo afecta al saldo fisico.
- No se implementan cierres automaticos ni cajas compartidas.

## Modelo De Datos

### Configuracion De Tienda

`configuracion_caja_tienda` almacenara:

- Tolerancia monetaria para considerar que existe descuadre, inicialmente
  `0,00 EUR`.
- Obligacion independiente de desglose por denominaciones en entradas,
  retiradas y cierres.
- Version para control de concurrencia.

Solo `ADMIN` y `GESTION_CUENTAS` podran modificar esta configuracion. No
existira fondo minimo.

### Sesion De Caja

`sesion_caja` almacenara terminal, tienda, usuario y fecha de apertura, fondo
inicial, usuario y fecha de cierre, importes esperados, fondo dejado, descuadre,
estado y version. Un indice parcial garantizara una sola sesion abierta por
terminal.

Una sesion podra cruzar medianoche y quedara identificada como cierre tardio.
La desactivacion de la terminal no cerrara su sesion. La terminal debera
reactivarse para que un usuario con permiso de venta pueda cerrarla.

### Movimientos

`movimiento_caja` sera inmutable y registrara:

- Cobro de ticket o factura por metodo de pago.
- Devolucion en efectivo.
- Entrada manual.
- Retirada ordinaria o retirada de cierre.
- Movimiento entre sesiones.

Guardara terminal, sesion opcional, importe, tipo, fecha, usuario, comentario,
documento, pago y usuario autorizador cuando corresponda. Una referencia unica
por pago impedira registrar dos veces el mismo movimiento.

`movimiento_caja_denominacion` guardara denominacion y cantidad entera. El
desglose podra omitirse cuando la configuracion lo permita y entonces se
registrara solamente el importe total.

### Intentos De Arqueo

`intento_arqueo_caja` conservara cada recuento enviado durante el cierre,
incluido su desglose opcional. El primer intento fuera de tolerancia no cerrara
la sesion. El segundo intento cerrara siempre y conservara el descuadre sin
exigir explicacion.

## Denominaciones

El sistema trabajara exclusivamente en EUR y ofrecera cantidades enteras para:

`100`, `50`, `20`, `10`, `5`, `2`, `1`, `0.50`, `0.20`, `0.10`, `0.05`,
`0.02` y `0.01`.

Los importes se almacenaran con dos decimales y redondeo `HALF_UP`.

## Flujo Operativo

### Apertura

La apertura requiere permiso de venta, terminal activa y ausencia de otra
sesion abierta. No solicita recuento. El fondo inicial sera el fondo conservado
en el ultimo cierre, ajustado por entradas y retiradas realizadas entre
sesiones.

La primera apertura de una terminal requerira que `ADMIN` o
`GESTION_CUENTAS` hayan registrado previamente una entrada entre sesiones. No
se permitira abrir sin ese movimiento inicial.

### Ventas Y Cobros

Crear o manipular una venta requerira sesion abierta. Al confirmar un ticket o
cobrar completamente una factura, cada pago quedara asociado a la sesion. Las
operaciones fiscales, stock y totales documentales conservaran su comportamiento
actual; la caja solo anadira trazabilidad financiera.

### Entradas

Durante una sesion, un usuario con permiso de venta podra iniciar una entrada.
La operacion exigira comentario y autenticacion inmediata mediante usuario y
contrasena de `ADMIN` o `GESTION_CUENTAS`. Se guardaran tanto el iniciador como
el autorizador.

Entre sesiones, `ADMIN` y `GESTION_CUENTAS` podran registrar entradas con
comentario opcional. Estas modificaran el fondo propuesto en la apertura
siguiente.

### Retiradas

Los usuarios con permiso de venta podran realizar varias retiradas durante una
sesion. Una retirada no podra superar el efectivo teorico disponible. Podra
introducirse mediante denominaciones o importe total segun la configuracion.

Entre sesiones, `ADMIN` y `GESTION_CUENTAS` podran retirar dinero con comentario
opcional. El movimiento reducira el fondo de la siguiente apertura.

Cada retirada imprimira automaticamente un justificante con importe, desglose
si existe, fecha, usuario, terminal y sesion. Incluira dos recuadros en blanco:
firma de quien entrega y firma de quien recibe. Un fallo de impresion no
revertira el movimiento y permitira reimpresion.

### Cierre

El cierre tendra dos fases:

1. Registrar la retirada final de tipo `CIERRE`.
2. Contar el fondo que permanece fisicamente en la terminal.

El efectivo teorico se calculara como:

`fondo inicial + cobros en efectivo - devoluciones en efectivo + entradas - retiradas`

El arqueo compara el fondo contado con ese efectivo teorico. No compara contra
el total general de tickets, ya que tarjeta, vale y otros metodos no representan
efectivo fisico.

Si el primer intento queda fuera de la tolerancia, se mostrara al vendedor el
importe del descuadre y volvera al recuento. Si el segundo intento sigue sin
cuadrar, la sesion se cerrara, guardara el descuadre y notificara a `ADMIN` y
`GESTION_CUENTAS`, sin solicitar explicacion.

Una sesion cerrada no se editara. Cualquier cambio fisico posterior sera una
nueva entrada o retirada entre sesiones.

## Permisos Y Privacidad

- Permiso de venta: abrir, cerrar, vender, ingresar con autorizacion y retirar.
- `ADMIN` y `GESTION_CUENTAS`: configurar caja, autorizar entradas, realizar
  movimientos entre sesiones y consultar importes teoricos y estadisticas.
- Los vendedores podran ver importes declarados y el descuadre de su cierre,
  pero no ventas esperadas, saldos teoricos ni totales historicos.

Las consultas autorizadas mostraran resultados por terminal y por tienda con
periodos diario, semanal, mensual y rango personalizado. No se implementara un
consolidado de empresa en este bloque.

## Impresion

Ademas del justificante de cada retirada, el cierre imprimira un comprobante:

- Para vendedores: recuentos declarados, descuadre, identificacion de sesion y
  espacios de firma, sin ventas esperadas ni saldo teorico.
- Para `ADMIN` y `GESTION_CUENTAS`: resumen completo de cobros, movimientos,
  efectivo esperado, fondo dejado y descuadre.

## Integridad Y Auditoria

- Aperturas, pagos, movimientos y cierres seran transaccionales.
- Se bloqueara la sesion al retirar o cerrar para evitar operaciones
  simultaneas incompatibles.
- La validacion de los dos intentos se realizara en backend.
- Una autorizacion incorrecta no creara ningun movimiento.
- Configuraciones, autorizaciones y operaciones sensibles quedaran auditadas.
- Los movimientos y los intentos de arqueo seran inmutables.

## API Inicial

La API `/api/v1/cash` incluira operaciones para:

- Consultar estado de caja de la terminal.
- Abrir y cerrar sesion.
- Registrar entradas y retiradas.
- Previsualizar el efectivo disponible sin exponerlo a vendedores.
- Reimprimir justificantes.
- Configurar tolerancia y reglas de denominaciones.
- Consultar sesiones, movimientos, descuadres y resumen por periodos.

Las respuestas se filtraran segun permisos para impedir que un vendedor reciba
totales restringidos aunque manipule directamente la API.

## Pruebas

Se cubriran de forma focalizada:

- Migracion PostgreSQL e indice de sesion unica.
- Apertura, continuidad del fondo y primera entrada obligatoria.
- Bloqueo completo de ventas sin sesion.
- Pagos mixtos y calculo de efectivo teorico.
- Entradas autorizadas, credenciales invalidas y retiradas limitadas.
- Movimientos entre sesiones.
- Primer y segundo intento de arqueo.
- Tolerancia, descuadres y sesiones que cruzan medianoche.
- Concurrencia basica de cierre y retirada.
- Restriccion de totales y permisos.
- Datos de impresion y reimpresion pendiente.

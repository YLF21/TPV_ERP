# Bloque 2 VERI*FACTU: Nucleo Fiscal, Vales Y Ventas Aparcadas

## Objetivo

Construir el nucleo fiscal interno que permita confirmar ventas de forma
atomica, conservar una copia fiscal inmutable y encadenar registros mediante
SHA-256. El bloque tambien incorporara los vales generados por devoluciones,
los carritos persistentes y las ventas aparcadas.

Este bloque no implementara todavia XML, QR, certificados, firma, comunicacion
con la AEAT ni reintentos. Dejara preparados sus estados y limites de dominio.

Las decisiones de este documento prevalecen sobre el diseno general anterior
cuando exista alguna diferencia.

## Arquitectura

El dominio `verifactu` permanecera separado del dominio comercial. Los
servicios comerciales le entregaran una copia fiscal normalizada dentro de la
misma transaccion que confirma el documento.

Componentes principales:

- `FiscalSnapshotFactory`: construye la copia fiscal normalizada.
- `FiscalValidator`: valida NIF y demas campos obligatorios.
- `FiscalDocumentPolicy`: determina automaticamente `F2`, `F3` o `R5`.
- `FiscalChainService`: bloquea la cabeza de cadena y calcula las huellas.
- `FiscalRecordService`: crea altas, anulaciones, subsanaciones y sustituciones.
- `VoucherService`: emite, consume, renueva, caduca, reactiva y anula vales.
- `VoucherTraceService`: conserva la procedencia completa de vales y tickets.
- `ActiveCartService`: mantiene un carrito persistente por terminal.
- `ParkedSaleService`: aparca, recupera y elimina ventas no confirmadas.

El nucleo fiscal no dependera de controladores ni de representaciones visuales.

## Persistencia Fiscal

### Cadena fiscal

`cadena_fiscal` tendra una fila por empresa e instalacion y guardara:

- Empresa e instalacion.
- Ultimo registro fiscal.
- Ultima huella oficial.
- Ultima secuencia asignada.
- Fecha de actualizacion tecnica.

PostgreSQL bloqueara esta fila durante la insercion de cada registro. Dos TPV
que confirmen simultaneamente quedaran serializados durante el breve calculo de
la cadena, sin bloquear el resto de la aplicacion.

### Registro fiscal

`registro_fiscal` sera inmutable y contendra:

- Identificador y secuencia de cadena.
- Empresa, instalacion, tienda y documento comercial.
- Tipo de registro: alta, anulacion, subsanacion o sustitucion.
- Tipo fiscal: `F2`, `F3`, `R5` u otro tipo incorporado posteriormente.
- Serie, numero y fecha de expedicion.
- Instante UTC y zona horaria aplicada.
- Huella oficial anterior y huella oficial propia.
- Hash SHA-256 del contenido fiscal completo.
- Columnas fiscales principales para consulta.
- Copia fiscal normalizada completa en `JSONB`.
- Versiones del formato fiscal, algoritmo y aplicacion.

La referencia al documento facilita la navegacion interna. El `JSONB` conserva
exactamente los datos con los que se calcularon las huellas, aunque cambien
posteriormente clientes, productos o configuracion.

### Relaciones y estados

`registro_fiscal_relacion` enlazara:

- Subsanacion con el registro corregido.
- Anulacion con el ticket anulado.
- Rectificativa con el documento rectificado cuando este se identifique.
- Sustitucion de ticket con la factura resultante.

`estado_envio_fiscal` sera mutable y estara separado del registro inmutable.
Preparara estados como pendiente, enviado, aceptado, rechazado y defectuoso.

`intento_envio_fiscal` conservara en el bloque de comunicaciones cada intento,
fecha, resultado, codigo, XML enviado y respuesta integra de la AEAT.

## Inmutabilidad

La aplicacion no expondra operaciones para actualizar o eliminar registros
fiscales ni sus relaciones. Triggers de PostgreSQL rechazaran tambien cualquier
`UPDATE` o `DELETE` directo.

Ningun usuario, incluido `ADMIN`, podra romper la cadena. Una necesidad legal
de privacidad se resolvera, cuando proceda, anonimizando entidades externas sin
alterar el contenido fiscal historico.

No existira purga administrativa de registros fiscales. Esta regla sustituye
la posibilidad de purga contemplada en el diseno general inicial.

## Activacion Y Flujo Transaccional

Solo se generaran registros encadenados cuando VERI*FACTU este activo. No se
incorporaran ventas anteriores ni se creara un registro tecnico inicial. El
primer documento confirmado iniciara la cadena sin huella anterior.

La confirmacion realizara en una transaccion:

1. Validar todos los datos comerciales y fiscales.
2. Asignar serie y numero.
3. Crear la copia fiscal normalizada.
4. Bloquear la fila de `cadena_fiscal`.
5. Calcular la huella oficial y el SHA-256 del `JSONB`.
6. Guardar documento, stock, pagos, vales y registro fiscal.
7. Crear el estado de envio inicial.
8. Actualizar la cabeza de la cadena.
9. Confirmar la transaccion.

Un fallo tecnico en numeracion, stock, pagos, vales, registro o huella revertira
toda la operacion. Nunca quedara un documento confirmado sin registro fiscal
cuando VERI*FACTU este activo.

## Validacion Fiscal

La validacion local del NIF comprobara formato y digito de control cada vez que
se confirme una factura. No se guardara un estado de comprobacion censal.

La indisponibilidad de una consulta censal externa no impedira confirmar cuando
la validacion local sea correcta.

Todos los errores se devolveran juntos:

- Una factura exigira cliente y datos fiscales localmente validos.
- Un ticket con cliente invalido podra continuar si se elimina el cliente y se
  convierte en anonimo.
- Un documento que siga conteniendo datos fiscales invalidos no se confirmara.
- El fallo de un documento no impedira iniciar ni confirmar otras ventas.

## Tickets, Devoluciones Y Facturas

Los tickets solo se confirmaran al completar el pago. Antes de ese momento no
tendran numero fiscal, no moveran stock y no generaran registro VERI*FACTU.

Clasificacion automatica:

- Total positivo o igual a `0,00 EUR`: ticket ordinario `F2`.
- Total negativo: factura simplificada rectificativa `R5`, serie `TR`.
- Un mismo ticket podra mezclar lineas positivas y negativas.
- Con saldo final positivo o cero sera `F2`; con saldo negativo sera `R5`.

La interfaz presentara la devolucion como una operacion normal de ticket. El
usuario no seleccionara claves fiscales. Una devolucion podra crearse sin
ticket original, con lineas manuales y sin motivo. Esta posibilidad sustituye
la exigencia anterior de referenciar siempre el documento original.

Cualquier usuario con permiso normal de venta podra realizar devoluciones.

Solo los tickets no convertidos en factura podran anularse. La anulacion:

- Requerira una categoria y un motivo textual.
- Categorias iniciales: error de operacion, duplicado, devolucion total y otros.
- Podra ejecutarla `ADMIN` o un usuario con `GESTION_VENTAS`.
- Conservara el ticket original y creara un registro fiscal de anulacion.
- Compensara stock y pagos mediante operaciones trazables.

Las facturas no se anularan. Sus correcciones se haran mediante facturas
rectificativas.

## Conversion De Ticket En Factura

Cada ticket podra generar como maximo una factura individual. No se agruparan
varios tickets.

La conversion:

- Exigira un cliente fiscalmente valido.
- Copiara exactamente lineas, precios, descuentos e impuestos del ticket.
- Solo incorporara la identidad fiscal necesaria del cliente.
- No repetira venta, cobro ni movimientos de stock.
- Marcara el ticket como sustituido.
- Creara la factura fiscal `F3`.
- Creara el registro de sustitucion del ticket y el alta de factura.
- Guardara ambos registros en la misma transaccion y cadena.

Si falla cualquiera de los registros, se revertira toda la conversion. Un
ticket sustituido no podra anularse; cualquier correccion posterior se hara
mediante factura rectificativa.

## Subsanaciones Y Documentos Defectuosos

Una subsanacion conservara el registro original y creara otro registro
encadenado con:

- Referencia al registro subsanado.
- Copia fiscal completa corregida.
- Fecha, usuario y contexto de la correccion.

Podran subsanar `ADMIN` y usuarios con `GESTION_VENTAS`.

Los rechazos futuros de AEAT y los errores fiscales detectados se mostraran en
`Facturas defectuosas`. No bloquearan nuevas ventas. El registro original no
se modificara ni eliminara.

## Vales De Devolucion

Los vales solo podran originarse al devolver mediante vale el saldo de un
ticket negativo. Se descarta expresamente la compra directa de vales.

Al confirmar un ticket negativo, el usuario elegira entre:

- Devolver por otro metodo de pago.
- Generar un vale por el importe absoluto pendiente.

El vale se creara en la misma transaccion que el ticket y su registro fiscal.
Si falla su creacion, se revertira toda la confirmacion.

### Identidad y uso

- Sera al portador y no estara asociado a un cliente.
- Tendra un codigo aleatorio largo, no deducible y con control de errores.
- Bastara introducir o escanear el codigo para utilizarlo.
- Funcionara como metodo de pago `VALE`.
- No reducira la base imponible ni los impuestos de la nueva venta.
- Podra combinarse con otros metodos de pago.
- Podran utilizarse varios vales en un mismo ticket.
- No podra canjearse directamente por efectivo.

### Consumo y renovacion

Cada uso cancelara los codigos consumidos. Si sobra saldo, se generara
automaticamente un unico vale nuevo por el total restante.

Ejemplo:

1. El ticket `001` genera un vale de `100 EUR`.
2. El ticket `002` consume `20 EUR` y genera otro vale de `80 EUR`.
3. El ticket `003` consume `30 EUR` y genera otro vale de `50 EUR`.
4. El ultimo vale conserva la procedencia de los tickets `001`, `002` y `003`.

Cuando se combinen varios vales, el nuevo vale reunira el saldo restante y la
trazabilidad completa de todas sus ramas de origen. El saldo no podra editarse
manualmente.

### Caducidad y alcance

ADMIN configurara para los nuevos vales:

- Sin caducidad o un plazo de caducidad.
- Uso solo en la tienda emisora o en cualquier tienda de la empresa.

Los cambios solo afectaran a vales creados posteriormente.

Un vale caducado quedara bloqueado pero conservara saldo e historial. Solo
ADMIN podra reactivarlo e indicara manualmente la nueva fecha de vencimiento.

Solo ADMIN podra anular manualmente un vale con saldo y debera indicar motivo.

### Relacion con anulaciones

Si se anula el ticket emisor y el vale nunca se utilizo, el vale se anulara en
la misma operacion. Si el vale ya se uso total o parcialmente, se bloqueara la
anulacion del ticket y se exigira una operacion correctora.

### Consulta e impresion

ADMIN y usuarios con `GESTION_VENTAS` podran localizar y reimprimir un vale
mediante cualquiera de sus tickets relacionados.

La reimpresion mantendra el mismo codigo, saldo y contenido. Cada impresion y
reimpresion registrara fecha, usuario y terminal.

## Carrito Activo

Cada terminal tendra un unico carrito activo persistente en el servidor.

- Se guardara tras cada cambio de linea, cliente o comentario.
- Podra continuarlo cualquier usuario con permiso de venta.
- Se recuperara despues de un cierre inesperado.
- Antes de abrir una venta aparcada, el carrito actual debera estar vacio,
  cobrarse o volver a aparcarse.

El carrito activo no reserva ni mueve stock.

## Ventas Aparcadas

Aparcar un carrito creara una venta no fiscal con:

- Identificador interno.
- Fecha y hora.
- Usuario que la aparco.
- Cliente opcional.
- Comentario opcional.
- Copia de lineas, precios, descuentos e impuestos.
- Total calculado.

Los precios e impuestos guardados se conservaran al recuperarla, aunque la
configuracion comercial haya cambiado. Podran modificarse libremente antes del
pago.

Cualquier usuario con permiso de venta podra recuperar, editar o eliminar una
venta aparcada.

Al recuperarla:

- Se eliminara atomicamente de la lista de aparcadas.
- Pasara a ser el carrito activo de una terminal cuyo carrito este vacio.
- No habra bloqueos de edicion entre terminales.
- Si vuelve a aparcarse se creara una venta aparcada nueva.

No se asignara numero, no se movera ni reservara stock y no se generara ningun
registro fiscal hasta cobrarla.

### Conservacion y limpieza

Por defecto permaneceran hasta cobrarse o eliminarse manualmente. ADMIN podra
activar una limpieza diaria y configurar los dias de conservacion.

Toda eliminacion se auditara con identificador, fecha, usuario original y
total. La eliminacion manual incluira al usuario ejecutor. La automatica
identificara al proceso del sistema.

## Permisos

- Permiso normal de venta: crear tickets, devoluciones, carritos y ventas
  aparcadas; recuperar, editar y eliminar ventas aparcadas.
- `GESTION_VENTAS`: anular tickets, subsanar, buscar y reimprimir vales.
- `ADMIN`: todas las operaciones anteriores, configuracion de vales,
  reactivacion y anulacion manual de vales.

No se creara un permiso especifico de VERI*FACTU para subsanar.

## Pruebas

Se cubriran:

- Migraciones y restricciones reales en PostgreSQL.
- Bloqueo concurrente de la cabeza de cadena desde varios TPV.
- Secuencia unica, huella anterior y ambos hashes.
- Rechazo de `UPDATE` y `DELETE` sobre datos fiscales inmutables.
- Inicio de cadena al activar VERI*FACTU, sin incorporar historicos.
- Validacion local de NIF y lista completa de errores.
- Clasificacion automatica `F2`, `F3` y `R5`.
- Tickets con lineas positivas y negativas y total positivo, cero o negativo.
- Anulacion de tickets y bloqueo de tickets ya facturados.
- Conversion ticket-factura sin duplicar stock ni pagos.
- Subsanacion sin modificar el original.
- Emision, consumo parcial, combinacion, renovacion y trazabilidad de vales.
- Caducidad, reactivacion, alcance y anulacion de vales.
- Bloqueo de anulacion cuando un vale descendiente ya fue utilizado.
- Persistencia y recuperacion de un carrito por terminal.
- Ciclo de ventas aparcadas y limpieza diaria auditada.
- Reversion transaccional ante fallos tecnicos.

Se usaran pruebas parametrizadas para reducir duplicacion. PostgreSQL real se
reservara para migraciones, triggers, restricciones y concurrencia; la logica
pura se comprobara con pruebas unitarias enfocadas.

## Fuera De Alcance

Este bloque no incluye:

- Generacion o validacion de XML contra XSD.
- QR y representacion impresa fiscal.
- Certificados y DPAPI.
- Firma o comunicacion con AEAT.
- Reintentos y procesamiento de respuestas.
- Consulta censal persistida.
- Compra directa de vales.
- Canje de vales por efectivo.
- Reserva de stock para carritos o ventas aparcadas.


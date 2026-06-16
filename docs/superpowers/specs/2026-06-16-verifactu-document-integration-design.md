# Bloque 2A2 VERI*FACTU: Integracion Documental Recortada

## Objetivo

Conectar el nucleo fiscal VERI*FACTU ya implementado con la operativa real de
tickets y facturas de venta. Este bloque debe crear registros fiscales en la
misma transaccion que confirma o convierte documentos, sin incorporar todavia
XML, QR, certificado, comunicacion con AEAT, vales, subsanaciones ni ventas
aparcadas.

El alcance se mantiene intencionadamente corto para reducir pruebas y riesgo.

## Alcance

Se implementara:

- Alta fiscal de tickets confirmados.
- Alta fiscal de facturas de venta directas.
- Alta fiscal de facturas rectificativas de venta.
- Conversion directa de ticket en factura.
- Anulacion fiscal de tickets no facturados.
- Numeracion fiscal con `codigoTienda`.
- Inmutabilidad de documentos confirmados.

Queda fuera:

- XML, QR, firma, certificado, cola AEAT y reintentos.
- Vales de devolucion.
- Ventas aparcadas y carritos persistentes.
- Subsanaciones.
- Seleccion detallada de motivos `R2`, `R3` y `R4`.
- Agrupacion de varios tickets en una factura.

## Numeracion Fiscal

Desde la integracion fiscal se usaran series con `codigoTienda`:

- Ticket positivo o cero: `001-YYMMDD-NNNNNN`.
- Ticket negativo rectificativo: `TR-001-YY-NNNNNN`.
- Factura de venta: `FV-001-YY-NNNNNN`.
- Factura rectificativa de venta: `FRV-001-YY-NNNNNN`.

`001` representa `tienda.codigoTienda`. Cada tienda mantiene sus contadores.

## Clasificacion Fiscal

La clasificacion sera automatica:

- Ticket con total mayor o igual que `0,00 EUR`: `F2`.
- Ticket con total negativo: `R5`.
- Factura de venta directa: `F1`.
- Factura creada desde ticket: `F3`.
- Factura rectificativa de venta: `R1` por defecto.

Los tipos `R2`, `R3` y `R4` se reservaran para un flujo posterior con motivos
fiscales explicitos.

## Confirmacion De Tickets

`createTicket` confirmara el ticket, registrara pagos, movera stock y, si
VERI*FACTU esta activo, creara el registro fiscal `F2` o `R5` en la misma
transaccion.

Si VERI*FACTU no esta activo, el ticket se confirmara sin registro fiscal.

Un fallo interno en numeracion, stock, pagos o registro fiscal revertira toda la
operacion.

## Confirmacion De Facturas De Venta

Al confirmar una factura de venta directa:

- Se exigira cliente con datos fiscales completos y NIF localmente valido.
- Se asignara numero fiscal `FV-...`.
- Se creara registro fiscal `F1` si VERI*FACTU esta activo.

Al confirmar una factura rectificativa de venta:

- Se asignara numero fiscal `FRV-...`.
- Se creara registro fiscal `R1` si VERI*FACTU esta activo.

Las facturas de compra y albaranes no generaran registros VERI*FACTU en este
bloque.

## Conversion Ticket A Factura

La conversion de ticket a factura sera directa y atomica:

- El ticket debe estar confirmado.
- El ticket no puede estar ya facturado.
- El cliente debe tener datos fiscales completos y NIF localmente valido.
- La factura queda confirmada inmediatamente.
- La factura recibe numero propio `FV-001-YY-NNNNNN`.
- La factura usa tipo fiscal `F3`.
- La factura copia exactamente lineas, precios, descuentos e impuestos.
- No se repiten movimientos de stock.
- No se repiten pagos ni cobros.
- Se guarda relacion documental `FACTURA_DE`.
- Se guarda en la factura el campo nullable `num_ticket` con el numero del
  ticket origen.

El ticket permanecera en estado `CONFIRMADO`; no se marcara como anulado ni
sustituido. El vinculo se resolvera por `FACTURA_DE` y por `num_ticket` en la
factura.

Con VERI*FACTU activo, la conversion creara en la misma transaccion:

- Registro fiscal de sustitucion del ticket.
- Alta fiscal `F3` de la factura.

Si falla cualquiera de los pasos internos, se revertira toda la conversion.

Si VERI*FACTU no esta activo, la conversion se permitira sin registros fiscales
encadenados.

## Campo `num_ticket`

Se anadira `documento.num_ticket` como campo nullable.

Reglas:

- Solo se rellenara en facturas creadas desde ticket.
- No sera obligatorio para facturas directas.
- Despues de confirmar la factura no podra modificarse.
- Formara parte del snapshot fiscal.

## Anulacion De Tickets

Solo se podran anular fiscalmente tickets no facturados.

Si un ticket ya tiene factura relacionada, la anulacion se bloqueara y la
correccion debera hacerse mediante factura rectificativa.

La anulacion mantendra el numero del ticket y, con VERI*FACTU activo, creara el
registro fiscal de anulacion enlazado al alta original.

## Inmutabilidad Documental

Se eliminara la edicion administrativa excepcional de tickets confirmados.

Una vez confirmado un documento fiscal:

- No se podran modificar fecha ni numero.
- No se podran modificar lineas, precios, impuestos ni `num_ticket`.
- Las correcciones se haran mediante anulacion de ticket o rectificativa de
  factura, segun corresponda.

## Errores

Revierten toda la operacion:

- Error interno de numeracion.
- Error al guardar documento o relacion.
- Error de stock.
- Error de pagos.
- Error al crear registro fiscal.
- Error al actualizar la cadena fiscal.

No revierten la venta en este bloque:

- Caida futura de red.
- AEAT no disponible.
- Rechazo posterior de AEAT.

La cola, envio y reintentos se implementaran en el bloque de comunicacion AEAT.

## Pruebas Recortadas

Se cubriran solo pruebas criticas:

- Unitarias de numeracion fiscal con `codigoTienda`.
- Unitarias de clasificacion `F2`, `R5`, `F1`, `F3` y `R1`.
- Unitarias de bloqueo de segunda conversion de ticket.
- Unitarias de bloqueo de anulacion de ticket facturado.
- Unitarias de inmutabilidad de documentos confirmados.
- Una prueba PostgreSQL critica que verifique conversion ticket-factura,
  relacion `FACTURA_DE`, `num_ticket`, registro fiscal y rollback ante fallo
  interno.

No se repetiran pruebas ya cubiertas en 2A1:

- Hash oficial AEAT.
- Hash JSON.
- Triggers de inmutabilidad fiscal.
- Concurrencia de cadena.
- Migraciones completas salvo que una nueva migracion las requiera.


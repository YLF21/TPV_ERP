# Operacion de ventas pendientes de cliente

## Alcance y regla contable

Esta funcion permite dejar saldo pendiente solamente en albaranes de venta
(`ALBARAN_VENTA`) y facturas de venta (`FACTURA_VENTA`). Los tickets siguen
requiriendo cobro completo.

El saldo es `total - pagos reales`: **no es una forma de pago**, no se debe
configurar un metodo llamado `PENDIENTE` y nunca genera una fila en
`documento_pago`. Caja y fidelizacion reciben solo importes realmente cobrados.

## Preparacion

1. Inicie PostgreSQL, backend y APP VENTA conforme a sus README.
2. Compruebe terminal y almacen. Para efectivo, abra una sesion de caja.
3. Configure formas activas llamadas `EFECTIVO`, `TARJETA` y `TRANSFERENCIA`.
4. Para tarjeta local, seleccione proveedor y modo `SIMULATED` en
   Configuracion. La pantalla permite `APPROVED`, `DECLINED`, `TIMEOUT` y
   `CONNECTION_ERROR`; `simulatorQueryOutcome` permite resolver la consulta
   simulada.
5. Inicie sesion con los permisos necesarios:

   - `CUSTOMER_RECEIVABLES_CREATE`: crear ventas pendientes;
   - `CUSTOMER_RECEIVABLES_READ`: consultar deudas;
   - `CUSTOMER_RECEIVABLES_PAY`: cobrar posteriormente;
   - permisos de terminal adicionales para consultar, anular o devolver.

`ADMIN` incluye los tres permisos de cuentas a cobrar.

## Crear una venta 100 % pendiente

1. Abra **Venta** y añada productos.
2. Seleccione cliente con `F6`, si aun no hay uno.
3. Pulse `F12` o **Pendiente cliente**. Si falta cliente, el selector se abre
   primero y el dialogo continua despues de elegirlo.
4. Elija **Albaran** o **Factura**. El vencimiento inicial es la fecha local de
   tienda mas 30 dias y se puede editar antes del primer efecto de tarjeta.
5. Revise el total autoritativo mostrado. No añada pagos iniciales.
6. Pulse **Confirmar venta pendiente** o `Enter` con el formulario valido.
7. Compruebe numero y estado `PENDIENTE`, e imprima el documento comercial.

El backend recalcula catalogo, precio de socio, descuento por nivel,
promociones, impuestos y total. Ante un cambio exige revisar la cotizacion.
La pantalla se limpia solo despues de una confirmacion correcta.

## Crear una venta mixta

El dialogo combina pagos reales y deja pendiente la diferencia. Revise siempre
el resumen **Total / Pagado / Pendiente**.

- **Transferencia:** pulse **Añadir transferencia**, escriba importe y
  referencia no vacia y guarde. Es la asignacion que permite elegir libremente
  un importe parcial.
- **Efectivo:** **Añadir efectivo** abre la calculadora tactil y, en la version
  actual, cubre todo el saldo restante.
- **Tarjeta:** **Añadir tarjeta** carga el saldo restante completo y espera el
  resultado del terminal.

Por tanto, la UI actual permite crear una venta inicialmente parcial indicando
un importe menor mediante **Transferencia**. Todavia no permite introducir una
asignacion inicial parcial de efectivo o tarjeta: esos botones cubren el saldo
restante. El cobro posterior si admite escribir un importe parcial para los
tres medios.

Solo una asignacion `APPROVED` cuenta como cobrada. Confirme el documento: sera
`PARCIAL` si queda saldo y `PAGADO` si los pagos cubren el total.

## Consultar y cobrar posteriormente

1. En la pantalla inicial abra **DEUDAS CLIENTES**. Desde la ficha de un
   cliente, **Ver deudas** abre la misma pantalla prefiltrada.
2. Filtre por texto, estado (`PENDIENTE`, `PARCIAL`, `PAGADO`), tipo, solo
   vencidos o rango de vencimiento.
3. Pulse **Cobrar**. El importe comienza con el saldo total; escriba una cifra
   menor para cobrar parcialmente.
4. Seleccione:

   - **Efectivo**: indique recibido y confirme; requiere caja abierta.
   - **Tarjeta**: espere aprobacion y registro.
   - **Transferencia**: indique referencia y confirme.

5. Verifique `PARCIAL` y el nuevo saldo, o `PAGADO` y saldo cero.
6. Imprima el justificante de cobro. No sustituye al documento fiscal original.

El backend bloquea el documento durante el cobro y rechaza importe cero,
negativo, superior al saldo, documento pagado o documento de otra tienda. Los
cobros posteriores no cambian lineas, cliente, precios, impuestos ni stock.

## Recuperacion e idempotencia

### Timeout o resultado incierto de tarjeta

1. Con `TIMEOUT`, `PENDING` o `SENT`, no cierre a la fuerza, no borre el
   almacenamiento local y no inicie otro cargo.
2. Pulse **Consultar estado de tarjeta**. Se reutiliza el mismo identificador
   con `POST /api/v1/payment-terminal/operations/{operationId}/query`.
3. Si resulta `APPROVED`, finalice con esa operacion.
4. Si resulta `DECLINED`, `ERROR` o `CANCELLED`, descarte explicitamente el
   intento y elija otro medio o un nuevo intento.
5. Una aprobacion no se elimina como si nunca existiera. Para retirarla use la
   anulacion del proveedor con los permisos correspondientes.

### Reinicio de APP VENTA o backend

En el cobro posterior de una deuda, la UI conserva en almacenamiento local los
identificadores estables de intentos de efectivo, transferencia o tarjeta que
no terminaron. Tras reiniciar, vuelva a la deuda, consulte la operacion de
tarjeta cuando corresponda y reintente con el mismo identificador.

El dialogo de creacion de una venta pendiente todavia no persiste su borrador
ni su `checkoutId` en almacenamiento local. No cierre ni reinicie APP VENTA si
su tarjeta inicial queda incierta: resuelva el intento en el mismo dialogo. La
recuperacion automatica de ese caso despues de reiniciar requiere una mejora
adicional. Nunca genere manualmente otro `checkoutId`, `requestId` u operacion
para el mismo efecto.

Repetir la creacion con igual `checkoutId` y contenido devuelve el mismo
documento. Repetir un cobro con igual `requestId` y contenido devuelve el mismo
resultado. La misma clave con datos distintos produce conflicto: investigue el
intento original en lugar de cambiarla a ciegas.

### Fallo de impresion

Una venta o cobro confirmado sigue siendo valido si falla la impresora. Pulse
**Reintentar impresion**; no repita la mutacion. La UI reutiliza el snapshot
autoritativo. Para una copia posterior existen:

- `GET /api/v1/customer-receivables/{documentId}/print-document`;
- `GET /api/v1/customer-receivables/{documentId}/payments/{paymentId}/receipt`.

## Stock y fiscalidad

- La confirmacion inicial numera el documento, ejecuta fiscalidad y aplica
  stock una sola vez.
- Un cobro posterior solo añade el pago y nunca vuelve a mover stock.
- Para comprobarlo, anote existencias antes de crear, despues de confirmar y
  despues de cobrar. Solo la confirmacion inicial debe modificarlas.
- No cree otra venta para cobrar una deuda existente.

## Informe diario: cinco magnitudes

**Informes de ventas** y
`GET /api/v1/commercial-reports/daily?date=AAAA-MM-DD` separan:

1. **Ventas facturadas** (`invoiced`): albaranes y facturas emitidos ese dia,
   sin duplicar el albaran posteriormente facturado.
2. **Cobrado en ventas actuales** (`collectedCurrent`): pagos reales sobre
   documentos emitidos ese dia.
3. **Nuevo pendiente** (`newPending`): facturado menos cobrado actual, nunca
   menor que cero.
4. **Cobros de deudas anteriores** (`priorDebtCollected`): pagos del dia sobre
   documentos de fechas previas.
5. **Entrada real de caja** (`cashInflow`): cobrado actual mas cobros de deuda
   anterior.

Ejemplo: factura de hoy por 100, cobro inicial 30 y cobro hoy de deuda antigua
por 20 produce `100 / 30 / 70 / 20 / 50` en esas cinco magnitudes.

## API de referencia

```text
POST /api/v1/pos/customer-pending-sales/quote
POST /api/v1/pos/customer-pending-sales/card-charges
POST /api/v1/pos/customer-pending-sales
GET  /api/v1/customer-receivables
GET  /api/v1/customer-receivables/{documentId}
POST /api/v1/customer-receivables/{documentId}/card-charges
POST /api/v1/customer-receivables/{documentId}/payments
POST /api/v1/payment-terminal/operations/{operationId}/query
GET  /api/v1/commercial-reports/daily?date=AAAA-MM-DD
```

Filtros de deudas: `customerId`, `search`, `status`, `documentType`, `overdue`,
`dueFrom` y `dueTo`.

Claves importantes:

- creacion POS: `checkoutId`;
- pago real inicial o posterior: `requestId`;
- tarjeta: `paymentTerminalOperationId` enlaza una operacion aprobada.

No envie `PENDIENTE` entre pagos. Una creacion sin pagos usa `payments: []`.

## Limitacion del datafono fisico

Flujo, persistencia, recuperacion y simuladores funcionan sin dispositivo real.
El modo `LIVE` requiere instalar en backend o servicio local el SDK/protocolo
oficial de Redsys TPV-PC, PAYTEF, PAYCOMET o Global Payments, completar
emparejamiento y certificacion y validar recibos con el adquirente.

Hasta entonces `LIVE` devuelve `SDK_NOT_INSTALLED`; el simulador no se convierte
en produccion. No guarde en Git, README, `provider_parameters`, variables
`VITE_*`, logs o capturas PAN completo, PIN, CVV, claves privadas, secretos de
comercio ni credenciales del SDK. Use referencias opacas gestionadas por el
backend y siga las reglas PCI y del proveedor antes de cobrar dinero real.

## Checklist de aceptacion manual

- [ ] Albaran 100 % pendiente: un documento, `PENDIENTE`, cero pagos.
- [ ] Factura 100 % pendiente con el mismo resultado financiero.
- [ ] Venta mixta inicial por transferencia: `PARCIAL` y saldo correcto.
- [ ] Si se exige efectivo o tarjeta inicial parcial, implementar primero la
      entrada de importe; hoy esos botones cubren todo el saldo restante.
- [ ] Cobro posterior parcial y total por cada medio permitido.
- [ ] Timeout consultado y resuelto sin segundo cargo.
- [ ] Reinicio y replay de un cobro posterior con las mismas claves devuelven
      el mismo resultado.
- [ ] No reiniciar una venta pendiente con tarjeta inicial incierta hasta
      implementar persistencia y recuperacion de su borrador.
- [ ] Documento y justificante son distintos; reimprimir no repite la mutacion.
- [ ] Stock cambia una vez, al confirmar el documento.
- [ ] El informe muestra las cinco magnitudes esperadas.
- [ ] Los permisos ocultan o bloquean cada accion no autorizada.
- [ ] No existe metodo `PENDIENTE` ni pago ficticio por el saldo.

Registre fecha, terminal, documentos y operaciones simuladas sin datos
sensibles. Marque solo lo ejecutado contra PostgreSQL y simulador reales.

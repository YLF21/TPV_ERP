# Ventas pendientes de cliente

## Objetivo

Permitir que APP VENTA cree albaranes y facturas de venta con cobro inicial
total, parcial o inexistente, mantenga el importe restante como deuda del
cliente y permita cobrar posteriormente esa deuda total o parcialmente.

Los tickets quedan fuera de esta fase y continuan exigiendo pago completo.

## Alcance de la primera version

- `F12` abre una ventana unica de venta pendiente.
- El operador elige `ALBARAN_VENTA` o `FACTURA_VENTA`.
- El cliente es obligatorio.
- La fecha de vencimiento es editable y comienza en fecha local de tienda mas
  30 dias.
- Se admiten pagos iniciales en efectivo, tarjeta y transferencia.
- El saldo restante queda pendiente en el propio documento.
- La deuda se puede consultar y cobrar desde APP VENTA y desde la ficha del
  cliente.
- Los cobros posteriores admiten efectivo, tarjeta y transferencia.
- No se implementan limites de credito, vales ni saldo de socio como medio de
  cobro de deuda en esta fase.

## Arquitectura elegida

El documento comercial es la fuente de verdad de la deuda. No se crea una
tabla de cuenta corriente independiente.

Cada albaran o factura conserva:

- cliente;
- fecha de emision y vencimiento;
- total;
- pagos reales registrados;
- saldo calculado como `total - pagos`;
- estado `PENDIENTE`, `PARCIAL` o `PAGADO`.

La deuda agregada de un cliente se obtiene sumando los saldos de sus documentos
de venta pendientes o parciales. Esta eleccion reutiliza el dominio existente
y evita mantener dos saldos que puedan divergir.

## Reglas financieras

- El saldo pendiente no es un metodo de pago y nunca crea una fila en
  `documento_pago`.
- Los pagos reales mas el saldo pendiente deben coincidir con el total
  autoritativo.
- Sin pago inicial, el documento queda `PENDIENTE`.
- Con pago inicial inferior al total, queda `PARCIAL`.
- Con pago completo, queda `PAGADO`.
- No se admite sobrepago ni importe de pago cero o negativo.
- Los cobros posteriores no modifican lineas, cliente, precios, impuestos ni
  stock.
- Caja e informes de cobro incluyen exclusivamente dinero realmente cobrado.
- La fidelizacion aplica precios y descuentos al crear el documento, pero solo
  acumula beneficios sobre importes efectivamente cobrados y nunca dos veces.

## Flujo de creacion en APP VENTA

El boton `Pendiente cliente` y `F12` se habilitan cuando existe una venta con
total positivo. Si no hay cliente, la accion abre primero el selector de
cliente y continua al dialogo cuando el operador elige uno.

El dialogo contiene:

1. Cliente seleccionado.
2. Tipo de documento: albaran o factura de venta.
3. Fecha de vencimiento editable.
4. Total recalculado por backend.
5. Pagos iniciales en efectivo, tarjeta o transferencia.
6. Resumen de total, pagado, cambio de efectivo y saldo pendiente.
7. Acciones `Cancelar` y `Confirmar venta pendiente`.

El backend recalcula catalogo, precio de socio, descuento por nivel,
promociones, impuestos y total. El navegador no define importes fiscales.

Una tarjeta aprobada no se elimina como si nunca hubiera existido. Para retirar
esa asignacion se exige la anulacion admitida por el proveedor. Un resultado
incierto bloquea la confirmacion y nuevos intentos hasta consultar su estado.

Tras confirmar correctamente se limpian lineas, cliente y asignaciones. Ante
un fallo se conserva el formulario y cualquier operacion recuperable.

## API de creacion

Se incorpora:

```http
POST /api/v1/pos/customer-pending-sales
```

Solicitud:

```json
{
  "checkoutId": "uuid",
  "documentType": "ALBARAN_VENTA",
  "customerId": "uuid",
  "dueDate": "2026-08-15",
  "lines": [
    { "productId": "uuid", "quantity": 2, "discount": 0 }
  ],
  "payments": [
    {
      "paymentId": "uuid",
      "kind": "CASH",
      "amount": "30.00",
      "delivered": "50.00",
      "change": "20.00"
    }
  ],
  "quotedTotal": "100.00"
}
```

Para tarjeta integrada el cliente envia unicamente `paymentId`, `kind`,
`amount` y `paymentTerminalOperationId`. El backend obtiene proveedor, estado,
referencia y autorizacion de la operacion persistida.

La transferencia exige `reference` no vacia. Efectivo exige una sesion de caja
abierta y valida entregado y cambio.

Respuesta:

```json
{
  "documentId": "uuid",
  "documentNumber": "AV-001-26-000123",
  "status": "PARCIAL",
  "total": "100.00",
  "paidTotal": "30.00",
  "pendingTotal": "70.00",
  "dueDate": "2026-08-15",
  "printDocument": {}
}
```

`checkoutId` identifica de forma idempotente la creacion. Repetir el mismo
payload devuelve el mismo documento; reutilizarlo con otro payload devuelve
conflicto.

## Vista financiera del documento

`DocumentView` expone adicionalmente:

- `customerId`;
- `customerName`;
- `dueDate`;
- `paidTotal`;
- `pendingTotal`;
- `overdue`.

`overdue` es verdadero cuando el saldo es positivo y el vencimiento es anterior
a la fecha local actual de la tienda.

## Consulta de deudas

Se incorporan:

```http
GET /api/v1/customer-receivables
GET /api/v1/customer-receivables/{documentId}
```

La coleccion admite filtros por `customerId`, texto de cliente/codigo/documento,
estado, vencido, tipo documental y rango de vencimiento. Solo devuelve
`ALBARAN_VENTA` y `FACTURA_VENTA`.

La pantalla es accesible desde APP VENTA y desde la ficha del cliente. La ficha
abre la misma pantalla con el cliente prefiltrado.

Columnas minimas:

- documento;
- cliente;
- emision;
- vencimiento;
- total;
- pagado;
- pendiente;
- estado.

Acciones: ver documento, cobrar, ver pagos, imprimir documento e imprimir el
justificante del ultimo cobro.

## Cobro posterior

Se incorpora:

```http
POST /api/v1/customer-receivables/{documentId}/payments
```

Cada peticion contiene un `paymentId` idempotente, importe y uno de estos
medios:

- `CASH`, con entregado y cambio;
- `INTEGRATED_CARD` o tarjeta manual si la tienda lo permite;
- `TRANSFER`, con referencia obligatoria.

El backend bloquea el documento durante la validacion y registro, comprueba el
saldo actual y rechaza un documento pagado, anulado, ajeno a la tienda o un
importe superior al pendiente. Repetir `paymentId` devuelve el mismo resultado;
reutilizarlo con otros datos devuelve conflicto.

La respuesta devuelve saldo y estado actualizados y un justificante imprimible.

## Integracion con tarjeta

La creacion y el cobro posterior reutilizan la plataforma de operaciones de
datáfono. Solo una operacion `APPROVED`, de la misma tienda/terminal/configuracion
y no consumida puede convertirse en pago.

- `DECLINED` permite cambiar de metodo o iniciar un nuevo intento explicito.
- `TIMEOUT`, `PENDING` o `SENT` bloquean confirmacion y otro cargo hasta consulta.
- Si el proveedor aprueba y falla la escritura del documento, la operacion se
  conserva para recuperacion; nunca se repite automaticamente el cargo.
- La vinculacion entre operacion y pago es unica.

## Stock y fiscalidad

Al confirmar la venta pendiente se numeran y confirman albaran o factura y se
aplica stock una sola vez conforme a las reglas actuales del tipo documental.
Los pagos posteriores no crean una nueva venta ni vuelven a mover stock.

La integracion fiscal se ejecuta en la confirmacion del documento, no en cada
cobro posterior. Los justificantes de cobro no sustituyen al documento fiscal.

## Permisos

- `CUSTOMER_RECEIVABLES_READ`: consultar deudas y pagos.
- `CUSTOMER_RECEIVABLES_CREATE`: crear albaranes/facturas pendientes desde POS.
- `CUSTOMER_RECEIVABLES_PAY`: registrar cobros posteriores.
- Los permisos existentes de tarjeta siguen siendo necesarios para consultar,
  anular o devolver operaciones.
- `ADMIN` incluye los tres permisos nuevos.

Todos los endpoints se limitan a empresa y tienda autenticadas. La ficha de
cliente no permite consultar documentos de otra organizacion.

## Errores y recuperacion

- Sin cliente: abre selector; si se intenta por API, devuelve validacion.
- Cliente inexistente o inactivo: no confirma.
- Tipo distinto de albaran/factura de venta: no confirma.
- Total cambiado: devuelve conflicto y exige revisar la nueva cotizacion.
- Referencia de transferencia ausente: validacion sin efectos.
- Caja cerrada: no registra efectivo.
- Tarjeta rechazada: no crea pago.
- Resultado de tarjeta incierto: conserva sesion y bloquea duplicados.
- Documento ya pagado o sobrepago: no modifica estado ni pagos.
- Fallo de servidor: conserva venta y claves idempotentes para recuperar.

## Informes

El informe diario separa:

- ventas facturadas;
- dinero cobrado en ventas actuales;
- nuevo pendiente de clientes;
- cobros de deudas anteriores;
- entrada real de caja.

La consulta por cliente ofrece deuda total y vencida. La primera version incluye
filtros de vencimiento, pero no limite de credito ni autorizaciones para
excederlo.

## Accesibilidad y teclado

- `F12` inicia el flujo.
- `Enter` confirma el formulario valido.
- `Escape` cierra antes de enviar efectos externos.
- Una operacion de tarjeta incierta impide cerrar ocultando el problema.
- Los dialogos atrapan foco, restauran el foco de origen y anuncian total,
  pagado, pendiente, cambio y estado de tarjeta mediante regiones vivas.
- La tabla y filtros se pueden operar sin raton.

## Pruebas de aceptacion

### Dominio y persistencia

- Sin pagos produce `PENDIENTE`.
- Pago parcial produce `PARCIAL`.
- Pago completo produce `PAGADO`.
- Sobrepago se rechaza sin efectos.
- Saldo y vencimiento persisten y se consultan correctamente.

### Creacion POS

- Albaran y factura completamente pendientes.
- Efectivo, tarjeta o transferencia mas saldo pendiente.
- Varios pagos iniciales.
- Cliente y vencimiento obligatorios.
- Tipo documental restringido.
- Idempotencia y conflicto de payload.
- Precios, descuentos e impuestos autoritativos.
- Stock aplicado una sola vez.

### Cobro posterior

- Efectivo, tarjeta y transferencia parcial y total.
- Referencia de transferencia obligatoria.
- Rechazo de sobrepago y documento pagado.
- Operacion de tarjeta reutilizada rechazada.
- Timeout consultado sin segundo cargo.
- Caja e informes incluyen solo dinero real.
- Fidelizacion no se duplica.

### Frontend e integracion

- `F12` selecciona cliente si falta y continua el flujo.
- Vencimiento inicial de 30 dias y editable.
- Resumen coherente de total, pagado y pendiente.
- Conserva formulario ante error y limpia solo tras exito.
- Listado, filtros y cobro posterior funcionan desde Venta y ficha de cliente.
- Suite backend, suite frontend y build de APP VENTA correctos.
- Migraciones PostgreSQL correctas desde base vacia y actualizada.

## Fuera de alcance

- Tickets pendientes.
- Limites de credito y bloqueo por riesgo.
- Cuenta corriente o tabla de deuda independiente.
- Vales y saldo de socio para cobros posteriores.
- Planes de pago, intereses, recargos y recordatorios automaticos.
- Modificar lineas o cliente durante un cobro posterior.

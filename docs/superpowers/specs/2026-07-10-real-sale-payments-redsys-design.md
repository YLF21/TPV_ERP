# Cobro real de venta y Redsys TPV-PC

## Objetivo

Convertir el ticket local de `SaleScreen` en una venta real persistida, permitiendo pagos simples o mixtos mediante efectivo, tarjeta y otros métodos activos. La tarjeta usará una integración Redsys TPV-PC preparada para producción y completamente operativa en modo de pruebas mientras no se disponga del contrato, endpoint y credenciales oficiales.

## Alcance

- Crear un ticket comercial real mediante un endpoint POS autoritativo e idempotente.
- Permitir varios pagos sobre el mismo ticket.
- Calcular entregado y cambio para efectivo.
- Registrar tarjeta únicamente después de una respuesta aprobada del datáfono.
- Mostrar y usar otros métodos de pago activos, incluida transferencia y vale.
- Permitir pago pendiente solo con un cliente seleccionado.
- Incorporar configuración, emparejamiento y prueba de conexión para Redsys TPV-PC.
- Mantener el ticket editable si el cobro o la creación del documento falla.

No se incluye la captura de datos de tarjeta en el TPV ERP, la integración con TPV Virtual por redirección ni el uso de un protocolo Redsys supuesto. El transporte productivo requerirá los parámetros oficiales entregados al comercio.

## Arquitectura

### Venta y cobro

`SaleScreen` abrirá un diálogo de cobro que mantiene una lista de asignaciones. Cada asignación contiene método, importe y metadatos específicos. La suma de cobros reales más el saldo pendiente debe coincidir con el total del ticket; en efectivo, `entregado` puede superar el importe y la diferencia se guarda como `cambio`.

El frontend cargará `GET /api/v1/pos/context`, limitado a la organización autenticada, con:

- almacén predeterminado activo de la tienda, o un error operativo si no existe;
- métodos de pago activos con código/tipo persistente y capacidades;
- configuración de pago del terminal;
- cliente seleccionado, si existe.

Antes de asignar o autorizar pagos, el frontend generará un `checkoutId` y enviará checkout, líneas y cliente a `POST /api/v1/pos/quotes`. El backend resolverá nombre, código, precio vigente, beneficios del cliente, promociones, impuestos, régimen y redondeo, y devolverá `quoteId`, hash, vencimiento, total y líneas calculadas. Cotización, autorización, polling y ticket validarán siempre la misma pareja terminal/checkout. Mientras no haya comenzado una autorización, todo cambio de línea, descuento o cliente invalida la cotización y requiere una nueva dentro del mismo checkout.

Al iniciar una autorización de tarjeta, el backend reservará la cotización vigente para ese checkout. Una cotización reservada seguirá siendo consumible por ese mismo checkout después de su vencimiento normal, pero no permitirá nuevas autorizaciones incompatibles. Al confirmar, el frontend enviará `POST /api/v1/pos/tickets` con la cotización reservada, una clave estable de checkout, pagos y saldo pendiente. El frontend no podrá definir snapshots fiscales ni precios autoritativos.

La clave de checkout será única por terminal y se almacenará junto con un hash canónico del request. Una repetición idéntica devolverá el ticket existente; reutilizar la clave con otro payload devolverá conflicto.

### Adaptador de datáfono

El backend expondrá una interfaz `PaymentTerminalGateway` independiente del proveedor y un `PaymentTerminalSecretResolver` que solo admita referencias protegidas allow-listed. Su primera implementación será `RedsysTpvPcGateway` y tendrá dos transportes:

- `TEST`: simulador determinista habilitado únicamente en entorno de desarrollo; solo ADMIN podrá escoger el siguiente resultado;
- `LIVE`: cliente HTTP configurable, deshabilitado mientras falten endpoint o referencia secreta oficial.

El frontend nunca enviará ni recibirá secretos. La configuración persistirá parámetros no sensibles y una referencia al secreto protegido, siguiendo el modelo existente de `TerminalPaymentConfiguration`.

### Operaciones de tarjeta

Una operación tendrá identificador único, importe, estado, referencia externa, código de autorización y marcas de tiempo. El flujo será:

1. Crear y confirmar una operación `PENDING` para el terminal autenticado.
2. Marcar y confirmar `SENT` antes de realizar I/O externo.
3. Invocar el gateway fuera de una transacción de base de datos y guardar el resultado en una segunda transacción.
4. Solo un resultado `APPROVED` produce un `PaymentRequest.Item` de tarjeta con modo `INTEGRATED`.
5. La creación del ticket enlaza `documento_pago.operacion_datafono_id` con la operación mediante FK única. `terminal_cobro_id` continúa identificando el terminal físico.

Las peticiones usarán una clave de idempotencia acotada por terminal y validarán cotización, importe, EUR, proveedor y hash de configuración. Desde `SENT`, la venta queda congelada: no se pueden cambiar líneas, cliente o descuentos ni iniciar autorizaciones sustitutorias. `SENT/UNKNOWN` bloquea también la creación del ticket hasta reconciliarse; `APPROVED` mantiene la venta congelada hasta que se cree el ticket. Un resultado final no aprobado libera la reserva y permite editar o iniciar un intento nuevo.

## Experiencia de usuario

### Diálogo de cobro

El diálogo mostrará total, asignado y restante. Dispondrá de:

- **Efectivo:** importe aplicado, cantidad entregada y cambio calculado.
- **Tarjeta:** importe y estado del datáfono. Si no está configurado, ofrece `Emparejar datáfono`.
- **Otros:** selector de métodos activos distintos de efectivo y tarjeta, con referencia obligatoria cuando lo exija el método.
- **Pendiente cliente:** disponible solo con cliente seleccionado; crea una cuenta por cobrar separada y nunca un `DocumentPayment`.

Se podrán añadir, editar o eliminar asignaciones hasta que el restante autoritativo sea cero. No se aceptan importes negativos, cero, con más de dos decimales ni superiores al restante, excepto el entregado en efectivo. Cada intento de autorización conserva un `authorizationAttemptId` para reintentos técnicos; solo una acción explícita tras un resultado final no aprobado crea otro identificador.

### Emparejamiento Redsys

El diálogo de emparejamiento, restringido a ADMIN o permiso de configuración de terminal, incluirá:

- proveedor fijo `REDSYS_TPV_PC` en esta primera versión;
- nombre visible;
- modo pruebas/producción;
- endpoint del servicio y parámetros no sensibles mostrados mediante una vista allow-listed;
- referencia al secreto, nunca el secreto en claro;
- botón `Probar conexión`, cuya llamada no acepta un resultado del navegador y ejecuta el gateway en backend;
- estado y fecha de la última prueba.

En modo de pruebas, solo ADMIN puede escoger el siguiente resultado simulado. En producción, no se permite activar la configuración sin endpoint, secreto resoluble y una prueba real satisfactoria, vigente y asociada al mismo hash de configuración. El hash de la última prueba se invalida al cambiar proveedor, endpoint, parámetros o referencia secreta. El modo TEST queda bloqueado fuera del perfil de desarrollo.

## Construcción del ticket

La solicitud de cotización enviará únicamente producto, cantidad y descuento. El backend construirá el snapshot de catálogo y fiscalidad, aplicará redondeo monetario por línea y promociones, y utilizará fecha local de tienda, almacén predeterminado, tipo `TICKET`, cliente seleccionado y descuento global cero. Un descuento mayor que cero exige ADMIN o `APLICAR_DESCUENTO` tanto al cotizar como al confirmar.

Los pagos mantendrán su orden; el navegador no enviará el flag `principal` y el backend marcará como principal el primer pago real. Cuando solo exista cuenta por cobrar no habrá pago principal. Para efectivo se enviarán `importe`, `entregado` y `cambio`. Para tarjeta integrada el cliente enviará únicamente método, importe y `paymentTerminalOperationId`; proveedor, estado y autorización se recuperarán de la operación persistida. Los demás métodos enviarán `reference` o `voucherCode` según sus capacidades. El saldo pendiente se enviará fuera de la lista de pagos.

El backend creará una cuenta por cobrar ligada al ticket y al cliente cuando exista saldo pendiente. La cuenta conservará importe original, saldo, estado y movimientos posteriores; caja e informes de cobros solo sumarán `DocumentPayment` reales.

Tras una respuesta correcta, se mostrará número de ticket y cambio, y se limpiarán líneas, cliente, selección y pagos. Ante error no se limpia ningún dato.

## Reglas y errores

- Un ticket vacío no puede cobrarse.
- El total debe ser positivo.
- Pago pendiente requiere cliente.
- Efectivo requiere sesión de caja abierta cuando así lo exige el backend.
- Tarjeta integrada requiere terminal habilitado, emparejado y operación aprobada.
- Una operación aprobada no puede aplicarse dos veces gracias a validación bloqueante y FK única.
- Una cotización reservada por una autorización iniciada en plazo puede consumirse después de su vencimiento únicamente con el mismo checkout y payload.
- Tras una autorización `SENT`, `UNKNOWN` o `APPROVED` no se puede editar la venta; esta primera versión no implementa void/refund para recotizar una venta ya cargada.
- Un rechazo final confirmado permite reintentar o elegir otro método. `SENT/UNKNOWN` bloquea otra autorización y el ticket hasta consulta, cancelación confirmada o reconciliación.
- Si Redsys aprueba pero se pierde la respuesta del ticket, el reintento debe reutilizar checkout y operación y devolver el mismo ticket, sin emitir un segundo cargo ni documento.
- Los mensajes mostrarán una acción concreta: abrir caja, seleccionar cliente, completar referencia, emparejar, reintentar o cambiar método.

## Seguridad

- No se capturan PAN, caducidad ni CVV.
- Los secretos se guardan fuera de `providerParameters`, se resuelven mediante una interfaz allow-listed y nunca se devuelven ni registran.
- Los logs omiten credenciales y datos sensibles.
- Emparejamiento y configuración exigen ADMIN o `CONFIGURACION_TERMINAL`.
- Cobro exige `VENTA` o permisos equivalentes ya utilizados por tickets.
- Toda consulta y operación queda limitada a empresa, tienda y terminal autenticados.

## Pruebas

### Frontend

- Abrir y bloquear cobro sin líneas.
- Añadir efectivo y calcular cambio.
- Construir pagos mixtos y validar restante.
- Exigir cliente para pendiente.
- Cargar y seleccionar otros métodos.
- Emparejar Redsys en pruebas.
- Gestionar aprobado, rechazo, timeout y error.
- Enviar el payload de ticket y limpiar solo tras éxito.
- Conservar el ticket tras fallo.

### Backend

- Configuración y permisos de emparejamiento.
- Validación de configuración Redsys LIVE y TEST.
- Transiciones válidas de operación.
- Idempotencia de cobro.
- Simulador de todos los resultados.
- Cotización autoritativa, caducidad, cambio de cliente/promoción y consumo exacto por ticket.
- Rechazo de tarjeta no aprobada o reutilizada.
- Creación POS autoritativa con precio/impuestos resueltos en servidor.
- Idempotencia de ticket antes y después del commit y conflicto de payload.
- Cuenta por cobrar para pendiente y combinación, sin contaminar cobros.
- Registro de movimiento de caja para efectivo.
- Concurrencia de autorización y consumo.

### Integración manual

- Cobro efectivo completo y con cambio.
- Cobro tarjeta aprobada en modo pruebas.
- Rechazo y reintento con efectivo.
- Timeout de transporte, polling y aprobación tardía sin segundo cobro.
- Pago mixto efectivo/tarjeta.
- Pago pendiente con y sin cliente.
- Confirmación de persistencia en PostgreSQL y aparición en informes.
- Repetición del mismo checkout tras respuesta perdida sin duplicar ticket.

## Criterios de aceptación

La funcionalidad se considera terminada cuando el operador puede crear un ticket real calculado por el backend con cualquiera de los métodos soportados, combinar cobros y cuenta por cobrar hasta cubrir el total, obtener cambio correcto, usar un cliente para saldo pendiente y completar una operación Redsys simulada mediante una configuración emparejada. Ningún fallo debe borrar el ticket, duplicar documento o cargo ni registrar deuda como cobro, y la integración productiva debe quedar bloqueada hasta aportar protocolo y parámetros oficiales válidos.

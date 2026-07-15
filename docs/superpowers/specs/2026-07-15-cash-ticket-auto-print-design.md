# Impresion automatica del ticket tras el cobro

## Objetivo

Conectar el ticket confirmado con la impresora local de APP VENTA. La impresion
se iniciara inmediatamente despues de que el backend confirme correctamente el
cobro y nunca formara parte de la transaccion economica: un fallo de hardware no
anulara, repetira ni dejara en duda una venta ya cobrada.

## Alcance

Se incluyen los dos caminos que pueden finalizar un cobro desde la pantalla de
venta:

- cobro en efectivo directo mediante `POST /api/v1/pos/cash`;
- finalizacion de una sesion de pagos mediante
  `POST /api/v1/pos/payment-sessions/{id}/finalize`, cuando el resultado incluye
  efectivo o una combinacion de efectivo y tarjeta.

La conexion se limita a APP VENTA y al ticket comercial de la venta. No cambia
el recibo propio del datafono, la impresion de facturas o albaranes ni el flujo
de informes.

## Datos autoritativos de impresion

El ticket no se construira a partir del carrito local. El backend generara una
instantanea imprimible desde el documento que acaba de confirmar y la incluira
en la respuesta final del cobro. Asi, numero, lineas, cantidades, precios,
promociones, formas de pago y total coincidiran con el documento persistido.

La instantanea tendra un contrato comun para ambos endpoints:

- identificador y numero del documento;
- fecha y hora de emision;
- lineas con descripcion, cantidad, precio unitario y total;
- pagos con nombre del metodo e importe;
- total confirmado.

El frontend completara los datos locales del terminal (`storeName` y
`terminalCode`) antes de crear el `TicketPrintRequest` del puente de hardware.
No se exponen drivers ni dispositivos fisicos al backend.

## Flujo de impresion

1. El backend confirma el pago y el ticket en su transaccion actual.
2. APP VENTA recibe el resultado y limpia la venta para impedir otro cobro.
3. APP VENTA abre inmediatamente la ventana `Pago completado`.
4. En paralelo, lee la configuracion local mediante `getHardwareConfig()`.
5. Busca la ruta de documento `TICKET`.
6. Si `printAutomatically` esta activo, envia la instantanea confirmada a
   `printTicket()` usando la impresora y el numero de copias configurados.
7. Actualiza el estado visible de impresion sin alterar el resultado del cobro.

Si la impresion automatica esta desactivada expresamente para `TICKET`, el
cobro se completa sin llamar a la impresora. La configuracion predeterminada la
mantiene activada.

## Ventana de pago completado

La ventana aparecera siempre que el backend haya confirmado el cobro, incluso
si la impresora no existe, esta desconectada o devuelve error.

Estados visibles:

- `PRINTING`: `Imprimiendo ticket...`;
- `PRINTED`: `Ticket enviado a la impresora`;
- `FAILED`: `El cobro se ha completado, pero no ha sido posible imprimir el
  ticket.`;
- `SKIPPED`: no muestra error, porque la impresion automatica fue desactivada
  por configuracion.

En `FAILED` se mostrara `Reintentar impresion`. Este boton reutilizara la misma
instantanea confirmada y solo repetira `printTicket()`; nunca llamara otra vez a
un endpoint de cobro. El boton `Finalizar` permanecera disponible durante todos
los estados para que el cajero pueda continuar.

## Configuracion de impresora y copias

El puente Electron resolvera la ruta `TICKET` de `documentPrintRoutes`:

- usara `route.printerName` cuando tenga valor y, como compatibilidad, recurrira
  a `ticketPrinterName`;
- aplicara `route.copies`, normalizado a un minimo de una copia;
- conservara el comportamiento actual para Windows y ESC/POS;
- mantendra la apertura de cajon ya asociada al ticket y a sus formas de pago.

La pantalla de configuracion y su ticket de prueba seguiran usando el mismo
puente; no se crean configuraciones paralelas.

## Errores e idempotencia

- Un error de impresion se trata como error de hardware, no de pago.
- La venta se limpia una sola vez tras la confirmacion del backend.
- Reintentar no vuelve a confirmar ni cobrar el ticket.
- Una respuesta duplicada/idempotente del backend puede volver a proporcionar
  la misma instantanea, pero una unica transicion exitosa de UI inicia una
  impresion automatica.
- Los mensajes tecnicos del puente se conservaran para diagnostico, mientras la
  UI mostrara el mensaje operativo definido para `FAILED`.

## Internacionalizacion

Los nuevos textos de estado y acciones se incorporaran a los catalogos ES, EN
y ZH. El contenido comercial del ticket (nombre de tienda, productos y metodos
de pago) se imprimira tal como lo devuelve el backend.

## Pruebas

Backend:

- el cobro efectivo devuelve la instantanea del ticket confirmado;
- la finalizacion de sesion devuelve la misma instantanea comun;
- lineas, pagos y total proceden del documento persistido.

Frontend:

- el resultado confirmado abre `Pago completado` antes de esperar al hardware;
- con impresion automatica activa se envia exactamente el ticket confirmado;
- configuracion desactivada no llama a `printTicket()`;
- exito cambia el estado a `PRINTED`;
- fallo mantiene el pago completado y cambia a `FAILED`;
- reintento vuelve a imprimir sin repetir ninguna llamada de cobro;
- el flujo directo y el flujo de sesion utilizan la misma funcion de impresion.

Electron:

- la ruta `TICKET` selecciona impresora y copias;
- el fallback a `ticketPrinterName` conserva configuraciones existentes;
- los errores estructurados actuales siguen llegando a APP VENTA.

## Fuera de alcance

- Cola persistente de reimpresiones entre reinicios de la aplicacion.
- Rediseño visual general de la ventana `Pago completado`.
- Nuevos formatos fiscales o cambios en VERI*FACTU.
- Impresion automatica de facturas, albaranes o informes.

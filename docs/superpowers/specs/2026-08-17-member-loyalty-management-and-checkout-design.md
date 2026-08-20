# Configuración de socios, categorías y consumo de saldo en cobro

**Estado:** especificación cerrada

**Fecha:** 2026-08-17

## Objetivo

Completar la fidelización de socios en APP GESTIÓN y APP VENTA. El administrador podrá configurar la acumulación de puntos y saldo, mantener las categorías de socios y definir sus descuentos. En el cobro, el operador podrá consumir saldo socio mediante el atajo `F10`, aplicar descuentos monetarios mediante `F11` y completar la venta con uno o varios medios de pago.

La lógica autoritativa permanecerá en backend. APP GESTIÓN y APP VENTA mostrarán y solicitarán operaciones, pero no calcularán por su cuenta los importes fiscales, los puntos, el saldo disponible ni la categoría efectiva.

## Alcance funcional

### APP GESTIÓN

Las configuraciones de uso poco frecuente estarán en `Configuración → Socios/Fidelización`:

- Activar o desactivar acumulación de puntos.
- Definir una regla proporcional con importe base en euros y puntos generados.
- Activar o desactivar generación de saldo.
- Definir una regla proporcional con importe base en euros y porcentaje de saldo generado.
- Configurar caducidad del saldo.
- Activar o desactivar asignación automática de categorías.
- Configurar las opciones administrativas del programa de socios.

La gestión de niveles estará en `Socios → Categoría`:

- Crear, editar, activar y desactivar categorías.
- Definir el nombre; el orden se calculará automáticamente por puntos mínimos.
- Definir puntos mínimos para categorías automáticas.
- Definir porcentaje de descuento socio.
- Definir categorías de asignación exclusivamente manual.
- Mostrar las categorías exclusivamente manuales después de las automáticas.
- Recalcular las categorías automáticas cuando cambien los puntos o la configuración.

La configuración y mantenimiento exigirán permiso administrativo en backend. El frontend no será la única barrera de autorización. Los cambios relevantes quedarán auditados.

### APP VENTA

La pantalla de cobro incorporará:

- `F10`: consumir saldo socio.
- `F11`: aplicar descuento monetario manual.

`F10` solo estará disponible cuando exista un socio activo seleccionado y saldo utilizable. Al pulsarlo, el operador podrá consumir todo el saldo disponible o introducir un importe parcial.

El saldo podrá dejar el total a pagar en cero. También se permitirán cobros mixtos con el importe restante mediante efectivo, tarjeta, transferencia u otros medios configurados.

## Modelo económico de la venta

La venta mostrará los siguientes conceptos:

1. `Subtotal`.
2. `Descuento socio`.
3. `Saldo socio`.
4. `Descuento F11`.
5. `Total a pagar`.

El descuento socio es porcentual y se calcula sobre el subtotal de las líneas permitidas.

El saldo socio y el descuento F11 son importes monetarios que se restan del importe restante. Al ser restas de importe, su orden matemático no modifica el total final. La interfaz mantendrá el orden visual anterior para facilitar la lectura del ticket.

Los productos marcados como no aplicables a descuentos quedan excluidos de los tres conceptos: descuento socio, saldo socio y F11. Las restas se distribuirán únicamente entre las líneas elegibles y nunca podrán generar un importe negativo.

## Impuestos

Los impuestos se calcularán después de aplicar el descuento socio, el saldo socio y el descuento F11.

El cálculo se realizará por línea y por tipo impositivo. Después se sumarán las bases y cuotas para mostrar el resumen fiscal de la venta.

Para una venta final de 10,00 € al 21%:

- Base imponible: `10 / 1,21 = 8,2644...`, mostrada como `8,26 €`.
- Cuota de IVA: `10,00 - 8,26 = 1,74 €`.
- Total: `10,00 €`.

No se calculará una única base global cuando existan líneas con tipos de IVA diferentes.

## Acumulación de puntos y saldo

Los puntos y el saldo se generarán únicamente sobre el importe efectivamente cobrado, después de todos los descuentos y del saldo socio utilizado.

Cada regla tendrá dos valores configurables. Para puntos se indicará `por cada X euros cobrados, acumular Y puntos`; para saldo se indicará `por cada X euros cobrados, generar Y % de saldo`. El cálculo será proporcional, no por tramos completos. Los puntos se redondearán hacia abajo al entero al finalizar el cálculo del cobro y el saldo se redondeará hacia abajo a céntimos.

La acumulación estará vinculada a cada cobro real, no únicamente a la creación del ticket:

- Si una venta se cobra completamente, se genera la recompensa correspondiente al importe cobrado.
- Si una venta se cobra parcialmente y queda deuda, solo se recompensa la parte cobrada.
- Cuando se cobre posteriormente la deuda, se generará la recompensa correspondiente a ese cobro posterior.
- Un cobro con importe cero no genera puntos ni saldo.
- Las anulaciones y devoluciones revertirán los movimientos de puntos y saldo que correspondan.

Los puntos no caducan.

El saldo sí caduca a las `23:59` del día de caducidad, usando la zona horaria de la tienda. El backend será la autoridad sobre la hora y la validez. Al seleccionar al cliente en una venta, APP VENTA reservará temporalmente su saldo utilizable y mantendrá la reserva mediante un latido cada `30 segundos`:

- La reserva bloquea temporalmente esos lotes frente a otras cajas y tiendas y mantiene su validez para la venta en curso aunque llegue su hora de caducidad.
- La reserva se conserva hasta confirmar el cobro y emitir el ticket.
- Si la venta se guarda con `Ctrl+G`, se cambia de cliente o se abandona la venta, la reserva se libera.
- Si la caja pierde conexión, la reserva central se libera automáticamente tras `2 minutos` sin latidos.
- Un fallo de reserva o de conexión nunca impedirá el cobro local; únicamente dejará indisponible el consumo de saldo socio.
- Al seleccionar de nuevo al cliente después de liberar o expirar la reserva, el saldo ya caducado no aparecerá.

El consumo de saldo seguirá FIFO por lote.

## Categorías

Las categorías automáticas se determinarán por puntos acumulados y puntos mínimos configurados.

Se permitirá asignar una categoría manualmente. También existirá una categoría o nivel de uso exclusivamente manual, que no se obtendrá por alcanzar puntos y que no será sustituido automáticamente por el recálculo de categorías.

El porcentaje de descuento de la categoría se aplicará a todas las líneas elegibles, excepto a los productos bloqueados para descuentos.

El descuento socio se mantendrá separado del descuento F11. El descuento F11 se aplicará como resta monetaria sobre el importe resultante, sin modificar el origen ni la trazabilidad del descuento de categoría.

## Devoluciones, anulaciones y trazabilidad

Cada operación de acumulación y consumo deberá conservar:

- Socio afectado.
- Ticket o documento relacionado.
- Cobro que originó el movimiento.
- Tipo de movimiento.
- Importe de saldo o puntos.
- Lote consumido cuando corresponda.
- Usuario, terminal y fecha.
- Motivo de reversión cuando exista.

Una devolución o anulación no editará directamente el saldo actual. Creará movimientos compensatorios trazables y devolverá el saldo consumido cuando corresponda.

## Integridad del cobro

El backend validará en la confirmación:

- Socio activo.
- Saldo disponible y no caducado.
- Importe máximo consumible.
- Productos elegibles.
- Importe final y desglose fiscal.
- Permisos del operador.
- Idempotencia del checkout.
- Estado de sincronización oficial requerido por el modelo existente.

La confirmación no podrá duplicar consumo de saldo, puntos, saldo generado ni documento fiscal si se reintenta la misma operación.

## Presentación y documentación

El ticket, la factura cuando corresponda, los informes de ventas y el historial del socio mostrarán separadamente:

- Subtotal.
- Descuento socio.
- Saldo socio utilizado.
- Descuento F11.
- Total a pagar.
- Bases imponibles por tipo de IVA.
- Cuotas de IVA por tipo.
- Medios de pago utilizados.

El saldo socio no se mostrará como un descuento F11 ni se mezclará con el importe cobrado por efectivo, tarjeta u otros medios.

## Criterios de aceptación

- El administrador puede configurar puntos, saldo, caducidad y categorías desde las pantallas definidas.
- Una categoría se aplica automáticamente según puntos cuando está configurada como automática.
- Una categoría manual no se sustituye por el cálculo automático.
- `F10` permite consumir saldo completo o parcial.
- El consumo FIFO respeta lotes y caducidades.
- El saldo reservado al seleccionar al cliente conserva su validez hasta finalizar o abandonar esa venta; fuera de esa reserva, un saldo caducado no puede utilizarse.
- `F11` aplica una resta monetaria independiente del descuento socio.
- Los productos bloqueados no reciben ninguno de los tres descuentos.
- El total puede pagarse con varios medios.
- El IVA se calcula a partir del total final por tipo impositivo.
- Los puntos y el saldo se generan por cada importe efectivamente cobrado.
- El cobro posterior de una deuda genera la recompensa del importe cobrado posteriormente.
- Las anulaciones y devoluciones generan movimientos compensatorios trazables.
- Los reintentos no duplican documentos ni movimientos de fidelización.
- Las operaciones están protegidas por permisos de backend y traducidas en `es`, `en` y `zh`.

## Fuera de alcance

- Campañas promocionales avanzadas.
- Reglas de acumulación diferentes por producto, familia o campaña.
- App móvil de socios.
- Programa de puntos con caducidad.
- Cambios retroactivos sobre tickets ya cerrados.

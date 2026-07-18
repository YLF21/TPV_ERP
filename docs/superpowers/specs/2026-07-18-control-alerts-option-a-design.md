# Alertas de control - opcion A

## Alcance aprobado

El primer incremento registra alertas locales de control para operaciones que el backend
puede observar de forma fiable y para el vaciado voluntario comunicado por APP VENTA.
El dominio es independiente de la auditoria general y no reutiliza sus politicas de purga.

Tipos iniciales:

- `MANUAL_DISCOUNT_OVER_PERCENT`, con `thresholdPercent` entre 0 y 100.
- `PRODUCT_DISCOUNT_APPLIED`, solo a partir del descuento manual de linea del comando
  original; nunca a partir del documento recalculado con promociones o fidelizacion.
- `INACTIVE_PRODUCT_SOLD`.
- `TICKET_CANCELLED`.
- `SALE_SCREEN_CLEARED`.
- `CONSECUTIVE_LINE_DELETIONS`, con `minimumCount` entero y valor inicial 3.

`MANUAL_PRICE_CHANGED` y `MANUAL_PRICE_CHANGE_OVER_PERCENT` forman parte del catalogo del
sistema, pero se publican como no soportadas y no pueden configurarse ni activarse: el backend
impone actualmente el precio autorizado del catalogo y no existe una operacion real de cambio
manual de precio.

## Seguridad

- La entrada a endpoints de APP GESTION exige `APP_GESTION_ACCESS`, salvo `ADMIN`.
- Consultar alertas exige `CONTROL_ALERTS_READ` o `CONTROL_ALERTS_MANAGE`.
- Cambiar su estado exige `CONTROL_ALERTS_MANAGE`.
- Gestionar reglas exige `CONTROL_RULES_MANAGE`.
- Abrir el documento relacionado exige ademas `GESTION_VENTAS`.
- La tienda se deriva siempre de `CurrentOrganization`; el cliente no envia `tiendaId`.

## Persistencia e invariantes

- Las reglas se crean, modifican, activan y desactivan. No se eliminan.
- El nombre de cada regla pertenece al sistema, no se acepta en los comandos de escritura.
- Solo puede existir una regla de cada tipo por tienda.
- Los tipos sin parametro usan configuracion vacia; los parametrizados solo admiten
  `minimumCount` o `thresholdPercent`, segun su catalogo.
- Cada cambio de regla genera una version append-only con su configuracion completa.
- Los eventos de control son append-only y deduplicados por regla y origen.
- Las alertas no admiten DELETE y usan bloqueo optimista.
- Cada cambio de estado genera historial append-only.
- Estados: `NEW`, `REVIEWED`, `CLOSED`, `DISMISSED`.
- No se siembran reglas activas. Un administrador debe definir la politica de la tienda.

La migracion V76 se detiene con un mensaje explicito si una instalacion que ya uso V75 contiene
dos reglas del mismo tipo en una tienda. No consolida ni elimina automaticamente esos registros:
los eventos guardan la regla y su version como evidencia inmutable, por lo que reasignarlos o
borrar una regla alteraria el rastro historico. Ese caso requiere una consolidacion administrada
antes de reintentar la migracion.

## API de bloques

- `GET /api/v1/control/rules/catalog` publica todos los tipos, su nombre fijo, parametro,
  configuracion inicial, disponibilidad y si ya estan configurados en la tienda.
- `GET /api/v1/control/alerts/groups?from=&to=` devuelve todas las reglas configuradas con
  sus contadores por estado para el rango semiabierto `[from, to)` calculado con `ocurrido_en`.
  Incluye la configuracion vigente para que un lector pueda interpretar el umbral del bloque.
- `GET /api/v1/control/alerts` admite `ruleId`, ademas de los filtros anteriores, para abrir
  exactamente el listado asociado al bloque sin confundir reglas del mismo tipo historico.

## Deteccion

La confirmacion de una venta evalua las reglas activas en la misma transaccion. El descuento
manual de linea se calcula a partir del comando original solo cuando la venta se confirma en la
misma peticion. En borradores confirmados posteriormente y en instantaneas de tarjeta ya
tarificadas se evalua solo el descuento global: una linea persistida puede incluir un beneficio
de miembro y no debe producir falsos positivos. La venta permitida de productos desactivados se
comprueba contra el catalogo actual.
La anulacion se registra despues de guardar el ticket anulado, dentro de su transaccion.

`SALE_SCREEN_CLEARED` se registra al aceptar `fullTicketClear=true` en el endpoint de lineas
eliminadas. `VENTA` y `GESTION_VENTAS` pueden comunicar esa accion.

Las eliminaciones se agrupan mediante una operacion de venta y una operacion de eliminacion
idempotente. La cabecera permite contar acciones, no filas, de modo que un vaciado con varias
lineas no infla el umbral de eliminaciones consecutivas.

## Limitacion conocida

El vaciado de pantalla es de mejor esfuerzo: un cierre forzado, un corte electrico o una
interrupcion deliberada de red puede impedir que APP VENTA lo comunique. Resolverlo requiere
una sesion de carrito durable en backend y queda fuera de esta fase.

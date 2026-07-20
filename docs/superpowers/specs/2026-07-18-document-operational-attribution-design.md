# Atribución operativa histórica de documentos

## Decisión aprobada

Se adopta la alternativa C: campo de terminal de origen para consultas rápidas y eventos operativos append-only para conservar el ciclo de vida completo.

## Modelo

- `documento.terminal_origen_id` identifica el primer terminal fiable del documento.
- Puede pasar de `NULL` a un terminal una sola vez y después es inmutable, tanto en dominio como en PostgreSQL.
- `documento_evento_operativo` registra `CREADO`, `CONFIRMADO`, `ANULADO`, `MODIFICADO`, `COBRADO`, `CONVERTIDO` y `RECTIFICADO`.
- Cada evento conserva documento, tienda, usuario, terminal opcional, instante y datos mínimos de contexto.
- PostgreSQL impide actualizar o eliminar eventos.

## Semántica

- El terminal de origen no cambia si el documento se confirma desde otro terminal.
- El evento de confirmación conserva el segundo terminal y el usuario que confirmó.
- Los informes muestran el usuario operativo que confirmó el documento o, si continúa en borrador, quien lo creó.
- Las anulaciones y modificaciones quedan como eventos separados; no sustituyen al actor original de la venta.

## Migración histórica

El terminal histórico solo se completa cuando todas las evidencias disponibles para el documento señalan a un único terminal. Se consultan checkout de efectivo, checkout de tarjeta, sesiones de cobro y movimientos de caja. Los casos inexistentes o ambiguos permanecen como `NULL` y se muestran como `No disponible`.

## Seguridad y normativa

- Las relaciones con documento y terminal están limitadas por tienda mediante claves foráneas compuestas.
- Los eventos operativos complementan la auditoría y los registros fiscales; no sustituyen VeriFactu ni permiten alterar registros fiscales.
- La futura cronología visible en APP GESTIÓN deberá exigir los permisos del documento y no exponer datos de otras tiendas.

## Consulta desde APP GESTIÓN

- Los informes de tickets, facturas y albaranes incorporan el identificador interno del documento sin mostrarlo como columna.
- Doble clic, Enter o el botón `Actividad` abre una cronología compacta y de solo lectura.
- La API exige `APP_GESTION_ACCESS` además de `GESTION_VENTAS` para ventas, o los permisos de lectura de documentos de compra.
- La consulta vuelve a validar el tipo de documento y la tienda en backend; no depende de la protección del frontend.

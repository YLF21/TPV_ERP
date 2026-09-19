# Historial de ventas de producto desde SaaS

## Alcance implementado

F6 en APP VENTA y Stock → información del producto → historial comparten `StockSalesHistoryPanel`: consulta central, filtros de fechas/estado/tienda, detalle con carga incremental al desplazarse, comparación por tienda y exportación Excel/PDF. APP GESTIÓN conserva los textos ES/EN/ZH.

El scroll carga páginas de hasta 200 líneas mediante el cursor del servidor y conserva las filas anteriores, sin botones Anterior/Siguiente. Un cambio de filtros u orden reinicia el recorrido. Un fallo al cargar más filas conserva lo ya mostrado y permite reintentar el mismo cursor. La exportación sigue incluyendo el resultado filtrado completo, no sólo las filas cargadas.

El código de producto histórico se compara como texto exacto dentro de la empresa. No se convierten códigos a números ni se eliminan ceros iniciales. Los UUID locales no identifican el mismo producto entre tiendas.

La venta, el cobro y la sincronización siguen funcionando localmente. Esta consulta pasa por el backend local, que conserva las credenciales de instalación y resuelve la identidad SaaS. Si SaaS no está disponible, la pantalla ofrece reintento y permite cerrar; no presenta datos locales como si fueran un consolidado central.

## Datos y métricas

- V60 añade `saas_commercial_document_line` e índices por empresa/código/tienda/documento. Las líneas se copian del último evento documental aceptado en la misma transacción que su cabecera. Una revisión antigua no puede sustituirlas.
- El detalle conserva cantidad, precio, descuento e importe históricos. No recalcula documentos con precios o impuestos actuales.
- Los totales y la comparación incluyen todo el resultado filtrado, independientemente de las filas cargadas. Las etiquetas visibles son «Cantidad total» e «Importe total»; mantienen los cálculos netos descritos a continuación. Separan monedas; no convierten divisas.
- Cantidad neta = cantidad vendida − cantidad devuelta. Anulados no contribuyen. Una rectificativa económica con tarifa `DIFERENCIA` modifica el importe pero aporta cero unidades; el detalle conserva su cantidad original.
- Una factura efectiva con relación `FACTURA_DE` sustituye su ticket/albarán de origen, incluso cuando la factura cae fuera del intervalo seleccionado. Las relaciones `COMPENSA` de cambios no eliminan una venta nueva ni la devolución asociada.
- Importe neto significa suma de importes históricos de las líneas efectivas del producto. No distribuye descuentos o ajustes globales sin asignación histórica a esas líneas. No representa margen ni beneficio.
- La cobertura indica únicamente datos recibidos en SaaS. La fecha mostrada como «Actualizado» es la última recepción de documentos en el ámbito temporal/tiendas, no una garantía de que todas las tiendas hayan sincronizado.
- Los documentos sin líneas utilizables o con relaciones incompletas generan un aviso de resultado parcial. Como no es posible conocer el producto de una línea ausente, el contador de incompletos abarca documentos del periodo/tiendas seleccionados.
- El historial y sus exportaciones no incluyen la columna Almacén. Los documentos centrales se consultan en modo de lectura: sus UUID no se abren como documentos locales.

## Contratos y seguridad

Backend local:

- `GET /api/v1/stock/products/{productId}/sales-history/saas`
- `POST /api/v1/stock/products/{productId}/sales-history/saas/export`
- `POST /api/v1/stock/products/{productId}/sales-history/saas/render`

Permisos existentes: ADMIN o STOCK_READ/GESTION_PRODUCTO/GESTION_VENTAS/VENTA. La selección del producto se comprueba contra la tienda local activa; empresa, tienda de origen y código enviado al central se obtienen en el servidor.

SaaS: `POST /api/v1/product-sales-history/page` y `/export`, autenticados con `X-TPV-Installation-Token`. La instalación vinculada debe estar activa. El ámbito de empresa se deriva de la instalación autenticada; cambiar identificadores o filtros no permite consultar otra empresa.

Páginas de hasta 200 líneas, con cursor vinculado a ámbito/filtros/orden y desempate estable. Cada respuesta y exportación usa una transacción de lectura REPEATABLE_READ. Los importes y cantidades viajan como cadenas decimales.

Límites explícitos: exportación de detalle de 50.000 líneas, 2.000 tiendas, 10.000 grupos tienda/moneda. Si se superan, se devuelve error; no se exporta una muestra truncada. La comparación se exporta sin cargar el detalle completo. El adaptador limita el cuerpo remoto a 4 MiB en consulta y 64 MiB en exportación y no expone mensajes remotos ni tokens al navegador.

Excel utiliza texto negro sin rellenos de colores, filtros y cabecera congelada. Los valores con más precisión que Excel admite se mantienen como texto. Los datos de usuario no se interpretan como fórmulas. El PDF usa una tabla Jasper adaptable a las columnas, cabecera repetida, texto ajustado, importes alineados a la derecha y moneda explícita.

## Recuperación del histórico recibido

`CommercialDocumentLineBackfill` procesa documentos `PENDING` desde su `source_event_id` vigente. Cada documento tiene su transacción y comparte el bloqueo usado por la recepción. Se reanuda tras un reinicio y no solicita a la tienda que vuelva a enviar todo su histórico.

Configuración SaaS:

| Propiedad | Valor predeterminado |
| --- | --- |
| `tpv.saas.document-lines.backfill-batch-size` | 100 documentos; admitido 1–1000 |
| `tpv.saas.document-lines.backfill-initial-delay` | 30000 ms |
| `tpv.saas.document-lines.backfill-delay` | 10000 ms entre lotes |

Estados: `PENDING`, `READY`, `MISSING`, `INVALID`. Los dos últimos no se reintentan indefinidamente y quedan visibles como cobertura incompleta; un nuevo evento válido los sustituye. La recuperación usa los eventos documentales ya almacenados. Un histórico antiguo que nunca llegó a la proyección documental necesita el procedimiento existente de recuperación de sincronización; este cambio no inventa sus líneas.

Consulta operativa de solo lectura, para un administrador SaaS autorizado:

```sql
select company_id, store_id, line_projection_status, count(*)
from saas_commercial_document
group by company_id, store_id, line_projection_status
order by company_id, store_id, line_projection_status;
```

## Validación y activación

Se han añadido pruebas de proyección, recuperación, concurrencia de revisiones, aislamiento entre empresas, autenticación, cursores/orden, exportación, monedas, precisión decimal, conversión a factura y rectificativas económicas. Las pruebas de componentes cubren F6 y la entrada real desde Stock en Venta/Gestión, incluido foco y Escape.

La revisión de navegador utiliza el componente real y respuestas ficticias aisladas, sin conexión a datos de tienda. Se comprobaron comparación, acceso al detalle de una tienda, filtro de estado, paginación, resultado vacío, error de conexión y aviso de cobertura incompleta en 1280×720. La ordenación real y los límites se comprueban en pruebas de servidor, no en la simulación visual.

Se generaron y revisaron las nueve páginas de cuatro PDF de prueba (detalle de 65 líneas, comparación, vacío y chino), además de comprobar estructura, precisión y estilos XLSX. No se ha hecho una impresión física.

Resultados de ejecución:

- SaaS: `mvnw.cmd --batch-mode verify`, 660 pruebas sin fallos ni omisiones, empaquetado y cobertura JaCoCo correctos, PostgreSQL efímero con V60 final.
- Backend local: 24 pruebas de adaptador/servicio/exportaciones y regresión del histórico anterior, más 12 pruebas HTTP de autorización y serialización. Todas pasan. Los GET verifican arrays y cadenas decimales reales; los POST comprueban PDF/XLSX. Se corrigió la serialización del árbol Jackson 2 bajo MVC Jackson 3 sin alterar conversores globales.
- Frontend: 28 pruebas del flujo; ejecución adicional de 80 pruebas de regresión de panel/Stock/tablas/traducciones (incluye pruebas repetidas, no son 108 casos distintos). TypeScript, builds de Gestión/PDA/Venta y presupuestos de bundle pasan.
- `git diff --check` correcto. El PostgreSQL efímero se detuvo al finalizar; no se modificó la base de tienda.

La implementación no implica despliegue. Para activarla: publicar primero backend SaaS con V60, comprobar avance del backfill y después actualizar backend local/frontend. Se reutilizan `tpv.sync.central-url`, `tpv.sync.worker-enabled` y las credenciales existentes de instalación. Hasta que se actualicen ambos servidores, el nuevo historial no debe considerarse operativo sobre datos reales.

Durante la implementación inicial no se realizaron commits, push ni cambios sobre la base de datos de producción. La revisión final del scroll, etiquetas y accesos F6/Stock verifica 36 pruebas de frontend, TypeScript, builds de Gestión/PDA/Venta y control de tamaño. APP VENTA queda con 161 bytes de margen en su presupuesto CSS, a tener en cuenta en el próximo cambio visual. La publicación en Git se realiza después, por petición expresa del usuario.

### Activación y corrección comprobadas en desarrollo

El 19/09/2026 se actualizó el SaaS de desarrollo, con copia previa de PostgreSQL, y se comprobó V60 aplicada: 211 documentos preparados y 745 líneas proyectadas. La respuesta directa de SaaS era correcta, pero la petición de F6 a través del backend local devolvía HTTP 500 antes de contactar con el central.

La causa era la lectura de `Product.getCode()` sobre un producto desconectado de Hibernate, con sus identificadores todavía sin cargar (`LazyInitializationException`). El adaptador usa ahora una consulta específica que carga los identificadores y conserva el filtro por tienda e ID. No mantiene una transacción abierta durante la llamada HTTP a SaaS.

La nueva regresión con PostgreSQL reprodujo el error antes de la corrección y pasó después. La ejecución focal del servicio, cliente y regresión PostgreSQL terminó con 7 pruebas correctas, sin omisiones; también comprueba aislamiento por tienda y ausencia de transacción durante la consulta remota.

Tras reiniciar el backend local conservando los perfiles `dev,fiscal-dev,saas-dev`, se verificó el recorrido real mediante una sesión temporal autenticada, revocada al terminar: consulta HTTP 200 con 9 líneas para el artículo comprobado, cero documentos incompletos y filtro `CONFIRMADO` correcto. Excel y PDF devolvieron HTTP 200 con firmas ZIP y PDF válidas, respectivamente. Esta comprobación HTTP no sustituye la revisión visual anterior de los documentos ni una prueba manual de la ventana nativa. No se reinició APP VENTA ni se publicó en Git.

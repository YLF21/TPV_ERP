# Diseno De Proveedores E Imagenes De Producto

## Objetivo

Completar el dominio de productos con:

- La gestion de los proveedores relacionados con cada producto.
- Una imagen principal y una miniatura por producto.
- La actualizacion automatica de proveedores desde compras confirmadas.
- La inclusion obligatoria de las imagenes en backups y restauraciones.

Estas funciones se implementaran en servicios separados para no aumentar la
responsabilidad de `CatalogService`.

## Alcance

Este bloque incluye migracion Flyway, persistencia JPA, servicios, API REST,
permisos, integracion documental, almacenamiento local y pruebas.

No incluye una galeria de imagenes, precios de compra por proveedor, proveedor
principal, historial independiente de entradas ni gestion de relaciones desde
la pantalla general de proveedores.

## Producto Y Proveedor

### Modelo

La tabla existente `producto_proveedor` se adaptara para contener:

- `id`: identificador UUID.
- `producto_id`: producto relacionado.
- `proveedor_id`: proveedor relacionado.
- `referencia_proveedor`: referencia opcional.
- `ultima_fecha_entrada`: fecha opcional de la compra confirmada mas reciente.
- `version`: control de concurrencia optimista.

Solo existira una relacion para cada pareja producto-proveedor.

La referencia:

- Sera opcional.
- Se normalizara eliminando espacios exteriores y convirtiendola a mayusculas.
- Podra repetirse en distintos productos, incluso para el mismo proveedor.

La nueva migracion eliminara el indice unico actual sobre proveedor y
referencia, y permitira que `referencia_proveedor` sea nula. Las migraciones
anteriores no se modificaran.

### Reglas

- No existira proveedor principal.
- El producto mantendra un unico coste general en `precio_compra`.
- Un proveedor inactivo conservara sus relaciones existentes.
- Un proveedor inactivo no podra vincularse manualmente ni seleccionarse en una
  compra nueva.
- La relacion se podra eliminar manualmente aunque existan compras historicas.
- Una compra posterior volvera a crear la relacion si fue eliminada.
- La anulacion o rectificacion posterior de una compra no eliminara la relacion
  ni modificara su ultima fecha de entrada.

Los proveedores relacionados con un producto se ordenaran siempre por
`numero_documento` ascendente. La fecha de ultima entrada sera solo informativa.
La lista general de proveedores usara el mismo orden por CIF/NIF, tengan o no
compras.

### Actualizacion Desde Compras

Al confirmar un albaran de compra:

1. Se comprobara que el proveedor este activo.
2. Por cada producto se creara la relacion si no existe.
3. Se actualizara `ultima_fecha_entrada` con la fecha del documento.
4. Si ya existe una fecha posterior, se conservara la posterior.

Una factura de compra directa aplicara la misma regla. Una factura creada desde
albaranes no volvera a actualizar las relaciones, porque la entrada ya quedo
registrada al confirmar los albaranes.

La relacion creada automaticamente tendra referencia nula. La referencia solo
se completara o modificara desde la ficha del producto.

### Servicios Y API

Se creara `ProductSupplierService` con operaciones para listar, vincular,
actualizar la referencia, desvincular y registrar una compra confirmada.

La API quedara bajo el producto:

- `GET /api/v1/products/{productId}/suppliers`
- `POST /api/v1/products/{productId}/suppliers`
- `PUT /api/v1/products/{productId}/suppliers/{supplierId}`
- `DELETE /api/v1/products/{productId}/suppliers/{supplierId}`

Las consultas requeriran `PRODUCTS_READ`. Las modificaciones requeriran
`PRODUCTS_WRITE`. Todas las operaciones validaran empresa y tienda actuales.

## Imagen De Producto

### Entrada Y Procesamiento

Cada producto tendra una sola imagen. La carga aceptara JPG, PNG y WebP con un
maximo de 5 MB.

El backend validara el contenido real decodificando la imagen. No confiara en
el nombre, la extension ni el `Content-Type` enviado por el cliente.

Durante la carga:

1. Se rechazaran archivos vacios, corruptos, mayores de 5 MB o con formato no
   admitido.
2. Se corregira la orientacion si el formato contiene metadatos aplicables.
3. Se reducira la imagen para que no supere 1600 x 1600, conservando proporcion
   y sin ampliar imagenes pequenas.
4. Se generara una miniatura de 300 x 300 con ajuste completo, sin recorte y con
   fondo transparente.
5. La imagen principal y la miniatura se guardaran internamente como WebP con
   calidad 85.

Se usara un codec Java mantenido y compatible con `ImageIO` para leer y escribir
WebP. La imagen original no se conservara.

### Almacenamiento

Los archivos se guardaran en una ruta interna administrada por la aplicacion,
configurable mediante `TPV_PRODUCT_IMAGE_DIRECTORY` y separada del directorio
de backups.

La estructura sera:

```text
<raiz>/<tienda UUID>/<producto UUID>/<imagen UUID>.webp
<raiz>/<tienda UUID>/<producto UUID>/<imagen UUID>-thumb.webp
```

No se usaran nombres proporcionados por el usuario. Todas las rutas se
resolveran y validaran dentro de la raiz configurada para impedir escapes de
directorio.

Se reutilizaran los campos existentes de `producto`:

- `imagen_id`: UUID usado por ambos archivos.
- `imagen_tipo`: `image/webp`.
- `imagen_tamano`: tamano de la imagen principal procesada.
- `imagen_hash`: SHA-256 de la imagen principal procesada.

### Reemplazo Y Borrado

`ProductImageService` coordinara el procesamiento, los archivos y los metadatos.

Para reemplazar una imagen:

1. Se procesaran y escribiran los archivos nuevos en ubicaciones temporales.
2. Se moveran atomicamente a sus nombres definitivos.
3. Se actualizaran los metadatos del producto.
4. Tras confirmar correctamente la operacion se eliminaran los archivos
   anteriores.

No se conservaran versiones anteriores. Si el procesamiento o la persistencia
fallan, la imagen anterior seguira disponible y se limpiaran los temporales.

Al borrar la imagen se limpiaran sus metadatos y archivos. Al borrar un producto
se eliminaran tambien sus archivos. La ausencia inesperada de un archivo se
tratara de forma idempotente para no impedir el borrado del producto.

### Lectura Y Exportacion

Las imagenes solo se serviran mediante endpoints autenticados:

- `PUT /api/v1/products/{productId}/image`
- `GET /api/v1/products/{productId}/image`
- `GET /api/v1/products/{productId}/image/thumbnail`
- `GET /api/v1/products/{productId}/image/export?format=jpg|png|webp`
- `DELETE /api/v1/products/{productId}/image`

La lectura requerira `PRODUCTS_READ`. La carga y el borrado requeriran
`PRODUCTS_WRITE`.

La exportacion WebP devolvera el archivo almacenado. PNG conservara la
transparencia. JPG se generara con fondo blanco porque el formato no admite
canal alfa.

Las respuestas incluiran un `ETag` derivado de `imagen_hash` para permitir cache
condicional sin exponer rutas locales.

## Backups

Las imagenes formaran parte de todas las copias, sin opcion para excluirlas.

Antes del cifrado se creara un paquete temporal versionado que contendra:

- Un manifiesto con version y sumas de comprobacion.
- El dump PostgreSQL en formato custom.
- El arbol de imagenes de producto.

El paquete completo se cifrara con el mecanismo AES-GCM ya existente. No se
modificara el contenedor criptografico `.tpvb` ni la gestion de su clave.

La restauracion:

1. Descifrara y validara el paquete en un directorio temporal.
2. Verificara el manifiesto y las sumas antes de modificar datos.
3. Restaurara PostgreSQL.
4. Sustituira el arbol de imagenes mediante un cambio atomico con copia de
   seguridad temporal.
5. Recuperara el arbol anterior si falla la sustitucion de archivos.

Los backups antiguos cuyo contenido descifrado sea directamente un dump de
PostgreSQL seguiran siendo restaurables. En ese caso solo se restaurara la base
de datos y no se modificara el directorio actual de imagenes.

## Errores Y Concurrencia

- Una relacion repetida devolvera conflicto.
- Un proveedor inactivo devolvera un error de validacion.
- Una referencia vacia se almacenara como nula.
- Una imagen inexistente devolvera `404`.
- Un archivo invalido, corrupto o demasiado grande devolvera `400`.
- La version del producto protegera reemplazos concurrentes.
- Los procesos temporales usaran identificadores UUID para evitar colisiones.
- La limpieza de temporales y archivos sustituidos sera idempotente.

## Comentarios De Codigo

Se mantendra el criterio acordado: comentarios `//` solo en metodos publicos con
una secuencia funcional relevante y en logica no evidente, especialmente
operaciones atomicas, integracion documental, validacion binaria y restauracion.

## Pruebas

### Persistencia

- Migracion real sobre PostgreSQL.
- Referencia opcional y repetible.
- Unicidad de producto-proveedor.
- Orden por CIF/NIF.
- Aislamiento por empresa y tienda.

### Proveedores De Producto

- Vinculacion, cambio de referencia y desvinculacion.
- Rechazo de proveedores inactivos.
- Creacion automatica al confirmar albaran de compra.
- Actualizacion desde factura de compra directa.
- Ausencia de doble actualizacion al facturar albaranes.
- Conservacion de la fecha mas reciente al confirmar documentos antiguos.
- Conservacion de relacion y fecha tras anulacion o rectificacion.
- Recreacion automatica de una relacion eliminada.

### Imagenes

- Aceptacion de JPG, PNG y WebP reales.
- Rechazo por contenido falso, corrupcion, formato y limite de 5 MB.
- Redimensionado proporcional a 1600 x 1600.
- Miniatura completa de 300 x 300 sin recorte.
- Conservacion de transparencia en WebP y PNG.
- Conversion JPG con fondo blanco.
- Reemplazo atomico y limpieza de la imagen anterior.
- Conservacion de la imagen anterior cuando falla un reemplazo.
- Borrado directo y borrado junto con el producto.
- Autenticacion, permisos, `ETag` y aislamiento de rutas.

### Backups

- Inclusion de originales procesados y miniaturas.
- Validacion de manifiesto y sumas.
- Restauracion completa de base de datos e imagenes.
- Recuperacion de archivos previos ante un fallo.
- Restauracion compatible de backups antiguos sin imagenes.

## Resultado Esperado

La ficha de producto podra mostrar y administrar sus proveedores ordenados por
CIF/NIF, mientras las compras mantendran automaticamente la informacion de
ultima entrada. Cada producto dispondra de una imagen eficiente para pantalla y
una miniatura, ambas protegidas por la API e incluidas en las copias de
seguridad.

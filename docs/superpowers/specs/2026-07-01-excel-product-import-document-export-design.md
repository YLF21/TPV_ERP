# Excel: importacion de productos y exportacion de documentos

## Objetivo

Anadir soporte backend para trabajar con Excel en dos flujos separados:

- Importar productos desde archivos `.xls` y `.xlsx`.
- Exportar tickets, facturas y albaranes existentes a `.xlsx` con formato visual tipo documento impreso.

No se importaran documentos existentes desde Excel. La importacion solo crea o actualiza productos y, opcionalmente, genera un albaran o factura de compra en borrador.

## Importacion de productos

El usuario subira un archivo `.xls` o `.xlsx` e indicara un mapeo por letra de columna. El backend no dependera de cabeceras ni nombres de columnas.

Campos mapeables:

- `codigo`
- `codigoBarras`
- `nombre`
- `descripcion`
- `precioCompra`
- `precioVenta`
- `precioMayorista`
- `precioMiembro`
- `impuesto`
- `cantidad`
- `referenciaProveedor`

La fila inicial sera configurable con `startRow`, por defecto `2`.

## Reglas de producto

Un producto existente se detectara por codigo o codigo de barras, usando la regla actual de unicidad cruzada.

Para crear un producto nuevo seran obligatorios:

- codigo o codigo de barras
- nombre
- precio compra

Si falta cantidad, la fila podra crear o actualizar solo el producto, pero no entrara como linea del documento.

Los productos nuevos usaran familia `GENERAL` y subfamilia vacia. El impuesto podra venir por columna como porcentaje (`21`, `21%`, `7`). Si esta vacio, se usara el impuesto predeterminado de la tienda.

## Actualizaciones y preview

La importacion siempre tendra dos pasos:

1. Vista previa.
2. Confirmacion.

La vista previa no guardara nada. Devolvera todas las filas con estado, errores y cambios detectados.

Cada cambio de producto se devolvera con:

- `campo`
- `valorActual`
- `valorExcel`

El usuario podra decidir en el mapeo si cada dato se actualiza o no. Si un campo esta desactivado para actualizacion, el backend lo leera solo cuando haga falta para crear un producto nuevo, pero no modificara productos existentes.

## Documento de compra generado

En la confirmacion, el usuario podra elegir crear:

- albaran de compra
- factura de compra

El documento siempre se creara como borrador. No movera stock hasta la confirmacion normal del documento.

El proveedor se seleccionara manualmente desde listado y aplicara a todo el documento. El numero externo del proveedor sera opcional y manual; no se leera del Excel.

Solo las filas con cantidad positiva se anadiran al documento. No se permitiran cantidades negativas en este importador.

## Relacion producto-proveedor

El usuario podra activar la opcion de actualizar proveedor de articulo.

Esa relacion se actualizara cuando se confirme el documento de compra, no al crear el borrador ni durante la preview.

La referencia proveedor-producto vendra de una columna opcional. Si esta vacia, se usara el codigo principal del producto.

## Exportacion de documentos

La exportacion sera solo de documentos ya existentes:

- tickets
- facturas
- albaranes

Formatos soportados:

- un documento por archivo `.xlsx`
- varios documentos en un `.xlsx`, con una hoja por documento

El contenido sera visual, similar a una factura/albaran/ticket impreso. No incluira hoja tecnica ni columnas ocultas con UUIDs internos.

## API prevista

Endpoints bajo `/api/v1/excel`:

- `POST /product-import/preview`
- `POST /product-import/confirm`
- `GET /documents/{documentId}/export`
- `POST /documents/export`

Los endpoints validaran sesion, terminal, licencia y permisos existentes de productos/documentos.

## Errores

La preview devolvera errores por fila sin guardar datos.

La confirmacion sera transaccional: si falla una fila durante guardado, no se creara parcialmente el documento ni se actualizaran parcialmente productos.

## Pruebas minimas

- Leer `.xlsx` y `.xls` con mapeo por letras.
- Detectar producto existente por codigo o codigo de barras.
- Crear producto nuevo con familia `GENERAL` e impuesto predeterminado si procede.
- Preview con cambios `campo`, `valorActual`, `valorExcel`.
- Confirmacion crea documento de compra en borrador.
- Fila sin cantidad crea/actualiza producto, pero no linea.
- Exportacion de un documento a `.xlsx`.

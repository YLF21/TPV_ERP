# APP VENTA: documentos de entrada y salida de almacen

## Objetivo

Anadir desde `SalesReportScreen` un flujo de creacion de documentos para los
informes `Entrada almacen` y `Salida almacen`, vinculado a backend y base de
datos.

## Alcance

La primera entrega cubre documentos de almacen desde la pantalla de informes:

- `Salida almacen`: seleccion de cliente o destino, almacen, fecha, importacion
  de Excel, revision de lineas y confirmacion.
- `Entrada almacen`: seleccion de proveedor u origen, almacen, fecha,
  importacion de Excel, revision de lineas y confirmacion.
- Confirmar un documento aplica movimientos de stock y refresca el informe.

No se incluyen en esta fase edicion avanzada de documentos confirmados,
impresion fiscal, facturacion automatica ni creacion masiva de productos desde
el Excel.

## Estado actual

`SalesReportScreen` ya contiene las opciones `Salida almacen` y `Entrada
almacen`.

`Salida almacen` ya tiene backend y BD:

- tabla `salida_almacen`;
- tabla `salida_almacen_linea`;
- endpoint `/api/v1/warehouse-outputs`;
- confirmacion con movimientos negativos de stock.

`Entrada almacen` no tiene documento persistido propio. El informe se alimenta
ahora de movimientos de stock positivos, por lo que no puede representar bien
cabecera, proveedor, estado, numeracion ni lineas de un documento.

## Enfoque elegido

Reutilizar el backend existente de `Salida almacen` y crear un documento
simetrico para `Entrada almacen`.

Este enfoque evita migrar el modelo de salidas ya existente y da a entradas una
fuente de verdad documental propia. Es mas estable que usar ajustes de stock
sueltos y menos invasivo que reemplazar ambos lados por una tabla generica.

## Experiencia de usuario

Cuando el informe seleccionado sea `Salida almacen` o `Entrada almacen`, la
barra de acciones mostrara el boton `Crear documento`.

Al pulsarlo se abrira una ventana modal sobre `SalesReportScreen`.

Para `Salida almacen`, la ventana mostrara:

- cliente o destino;
- almacen;
- fecha;
- boton `Importar Excel`;
- tabla de lineas importadas;
- boton `Confirmar`.

Para `Entrada almacen`, la ventana mostrara:

- proveedor u origen;
- almacen;
- fecha;
- boton `Importar Excel`;
- tabla de lineas importadas;
- boton `Confirmar`.

La tabla de lineas mostrara producto, codigo o referencia, cantidad y estado de
validacion. Si una fila no se puede asociar a un producto existente o tiene una
cantidad invalida, la ventana no permitira confirmar hasta corregir o retirar
esa linea.

## Importacion Excel

El frontend leera el Excel localmente y lo convertira a lineas de documento. La
primera version aceptara columnas reconocibles por nombre:

- producto, codigo, referencia, barcode o codigo de barras;
- cantidad.

Cada fila se cruzara contra productos cargados desde `/api/v1/products`.

La importacion no creara productos nuevos. Si un producto no existe, se marcara
la fila como no encontrada.

## Backend

### Salida almacen

Se reutilizaran:

- `WarehouseOutputController`;
- `WarehouseOutputService`;
- `WarehouseOutput`;
- `WarehouseOutputLine`;
- `/api/v1/warehouse-outputs`;
- `/api/v1/warehouse-outputs/{id}/confirm`.

La ventana creara un borrador y luego lo confirmara. La confirmacion mantendra
la regla actual: numerar el documento y crear movimientos negativos
`SALIDA_ALMACEN`.

### Entrada almacen

Se anadira un modulo equivalente:

- `WarehouseInputController`;
- `WarehouseInputService`;
- `WarehouseInput`;
- `WarehouseInputLine`;
- `WarehouseInputCommand`;
- `WarehouseInputLineCommand`;
- `WarehouseInputRepository`;
- `WarehouseInputLineRepository`;
- `WarehouseInputStatus`.

Endpoints:

- `GET /api/v1/warehouse-inputs`;
- `POST /api/v1/warehouse-inputs`;
- `PUT /api/v1/warehouse-inputs/{id}`;
- `DELETE /api/v1/warehouse-inputs/{id}`;
- `POST /api/v1/warehouse-inputs/{id}/confirm`.

La confirmacion numerara el documento y creara movimientos positivos de stock.
El tipo de movimiento sera `ENTRADA_ALMACEN`.

## Base de datos

Se anadira una migracion nueva con:

- tabla `entrada_almacen`;
- tabla `entrada_almacen_linea`;
- relacion opcional con proveedor;
- almacen obligatorio;
- tienda obligatoria;
- numero unico por tienda;
- estado `BORRADOR` o `CONFIRMADA`;
- usuario creador;
- usuario y fecha de confirmacion;
- lineas con producto y cantidad positiva.

`movimiento_stock` se extendera para poder enlazar movimientos a
`entrada_almacen_id` y aceptar el tipo `ENTRADA_ALMACEN`.

## Datos y refresco de informes

`SalesReportScreen` seguira cargando documentos remotos al abrirse. Tras
confirmar un documento:

1. se cierra la ventana;
2. se vuelve a cargar el informe remoto;
3. se mantiene seleccionado el informe actual;
4. se selecciona la primera fila si la lista cambia.

`Entrada almacen` pasara a leer `/warehouse-inputs` en lugar de reconstruirse
desde movimientos positivos sueltos.

## Permisos

La primera version reutilizara permisos de gestion de producto:

- lectura: `GESTION_PRODUCTO`, `STOCK_READ` o administrador;
- creacion/edicion/confirmacion: `GESTION_PRODUCTO` o administrador.

Si despues se necesitan permisos finos, se podran crear permisos equivalentes a
los de salidas: lectura, edicion, borrado y confirmacion.

## Validaciones y errores

Backend:

- el almacen debe existir, pertenecer a la tienda actual y estar activo;
- el cliente o proveedor debe pertenecer a la empresa actual cuando se informe;
- las lineas son obligatorias;
- las cantidades deben ser positivas;
- los productos deben pertenecer a la tienda actual;
- un documento confirmado es inmutable;
- un documento confirmado no se puede borrar;
- no se puede confirmar dos veces creando movimientos duplicados.

Frontend:

- no se permite confirmar sin almacen;
- no se permite confirmar sin cliente/destino en salida cuando el usuario no ha
  escrito destino manual;
- no se permite confirmar sin proveedor/origen en entrada cuando el usuario no
  ha escrito origen manual;
- no se permite confirmar con Excel vacio, productos no encontrados o cantidades
  invalidas;
- los errores de API se muestran dentro de la ventana.

## Pruebas

Backend:

- contrato de `WarehouseInputController`;
- pruebas de `WarehouseInputService` para crear, modificar, borrar, confirmar y
  evitar doble confirmacion;
- prueba de migracion/contrato para tablas de entrada y extension de
  `movimiento_stock`;
- pruebas de regresion de `WarehouseOutputService` si cambia la superficie de
  salida.

Frontend:

- `SalesReportScreen.test.tsx` verificara que `Crear documento` aparece solo en
  `Entrada almacen` y `Salida almacen`;
- test de ventana de salida con cliente/destino, Excel y confirmacion;
- test de ventana de entrada con proveedor/origen, Excel y confirmacion;
- test de filas invalidas de Excel bloqueando la confirmacion.

## Riesgos

El principal riesgo es tocar `SalesReportScreen`, que ya es grande y concentra
mucha logica. Para reducirlo, la ventana de documento debe vivir en un
componente nuevo y `SalesReportScreen` solo debe decidir cuando mostrar el boton,
abrir la ventana y refrescar datos.

Otro riesgo es la importacion Excel. La primera version debe limitarse a mapear
productos existentes y cantidades, sin crear catalogo ni deducir precios.

## Criterio de aceptacion

- En `Salida almacen`, el usuario puede crear documento, importar Excel,
  confirmar y ver el informe actualizado con el documento.
- En `Entrada almacen`, el usuario puede crear documento, importar Excel,
  confirmar y ver el informe actualizado con el documento.
- La confirmacion modifica stock en BD con movimientos trazables.
- Los documentos confirmados quedan numerados e inmutables.
- Los tests backend y frontend relevantes pasan.

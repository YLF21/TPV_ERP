# Diseño: edición rápida, búsqueda por teclado y precios por nivel de socio

## Objetivo

Completar el manejo por teclado de Venta y hacer que el precio de socio y el descuento del nivel del cliente se calculen de forma coherente en pantalla, cobro y ticket.

## Edición de cantidad y descuento

- `F2` abre Cantidad, enfoca el campo y selecciona todo su valor actual.
- La primera entrada numérica sustituye el valor seleccionado; por ejemplo, escribir `2` reemplaza `1`.
- `Enter` valida y guarda; `Escape` cierra sin modificar la línea.
- `F7` reproduce el mismo comportamiento para Descuento, incluido el valor inicial `0`.
- Los botones Cancelar y Guardar mantienen su comportamiento con ratón.
- Los formularios tendrán separación vertical entre campo, mensajes y acciones, y margen suficiente alrededor de los botones.

## Búsqueda y selección de productos

- `F5` enfoca el buscador.
- Al existir resultados, el primero queda seleccionado por defecto y se diferencia visualmente.
- `Enter` añade la coincidencia exacta por código, código de barras o código alternativo cuando exista.
- Sin coincidencia exacta, `Enter` añade el primer resultado filtrado.
- Al cambiar la consulta, la selección vuelve al primer resultado.
- Los clics sobre cualquier resultado siguen funcionando.

## Selección de clientes

- `F6` abre el selector de clientes.
- `Escape` lo cierra sin cambiar el cliente seleccionado ni los descuentos actuales.
- Elegir un cliente aplica inmediatamente sus beneficios a todas las líneas existentes.
- Los productos añadidos después reciben los mismos beneficios.

## Regla de precios y descuentos de socio

Para un cliente socio activo:

1. Si el producto está configurado con precio de socio y tiene un `memberPrice` válido, ese importe es el precio base.
2. En cualquier otro caso, el precio base es el precio normal efectivo del producto.
3. El porcentaje del nivel Bronce, Plata, Oro u otro nivel activo se aplica sobre ese precio base.
4. Si el vendedor introduce un descuento manual, se conserva la regla actual: el descuento efectivo es el mayor entre el descuento manual y el porcentaje del nivel; no se suman.

Para un cliente no socio o una venta sin cliente:

- No se usa el precio de socio.
- El porcentaje del nivel es cero.
- Solo se conserva el descuento manual permitido.

La aplicación frontend debe mostrar la base y el descuento efectivos. La petición de venta y el backend deben producir el mismo total. El backend será la autoridad final y aplicará la regla usando el cliente, su nivel activo y la configuración del producto; no confiará exclusivamente en valores calculados por el navegador.

## Compatibilidad y errores

- Se mantienen las restricciones existentes para productos que bloquean descuentos manuales.
- Un nivel inexistente, inactivo o con descuentos deshabilitados equivale a porcentaje cero.
- Un `memberPrice` nulo, no positivo o no aplicable hace que se use el precio normal.
- Los diálogos conservan cierre mediante botón, clic permitido y controles accesibles.
- Los atajos globales siguen sin actuar dentro de campos editables, salvo `Enter` y `Escape` gestionados expresamente por el diálogo activo.

## Pruebas

- Interacción real para selección completa y sustitución mediante teclado en F2/F7.
- `Escape` cancela Cantidad, Descuento y Cliente sin mutar el estado.
- F5 selecciona visualmente el primer resultado y `Enter` lo añade con y sin coincidencia exacta.
- Cálculo unitario de precio base normal/socio y prioridad del mayor descuento.
- Cliente seleccionado antes y después de añadir productos.
- Contratos backend para nivel activo, nivel inactivo, producto con precio de socio y producto normal.
- Integración de cobro/ticket con total coherente.
- Suite frontend, suite backend relevante y compilación de APP VENTA.

## Fuera de alcance

- Sumar descuento manual y descuento del nivel.
- Crear nuevos niveles o modificar porcentajes en base de datos.
- Cambiar promociones u ofertas no relacionadas.
- Navegación horizontal o paginación del catálogo.

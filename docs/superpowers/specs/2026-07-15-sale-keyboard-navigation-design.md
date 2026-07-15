# Diseño: navegación y acciones de teclado en Venta

## Objetivo

Permitir que las operaciones habituales de la pantalla de Venta se completen con teclado sin alterar el comportamiento existente mediante ratón.

## Alcance

- `F2` abre la edición de cantidad y coloca el foco en su campo numérico.
- `Enter` confirma la cantidad introducida usando la misma validación y acción que el botón de confirmación.
- `F7` abre la edición de descuento y coloca el foco en su campo numérico.
- `Enter` confirma el descuento usando la misma validación y acción que el botón de aplicación.
- `Supr` abre la confirmación de anulación de la línea seleccionada.
- En la confirmación de anulación, `Enter` confirma y `Escape` cancela.
- `AvPág` (`PageDown` en el evento del navegador) sustituye a `F10` como acceso rápido al cobro en efectivo.
- Las etiquetas del botón de efectivo y de la ayuda inferior mostrarán `AvPág`; `F10` dejará de activar el cobro.
- `Flecha arriba` selecciona la línea de producto anterior y `Flecha abajo` la siguiente.
- La navegación se detiene en la primera y última línea, sin selección circular.

## Diseño técnico

`SaleScreen` seguirá siendo el coordinador de los atajos globales. Las teclas invocarán las mismas funciones que los botones para evitar dos implementaciones distintas de cada operación.

Los diálogos de cantidad y descuento expondrán referencias a sus campos para enfocar el control al abrirse. Sus formularios procesarán `Enter` mediante el envío normal del formulario. La confirmación de anulación manejará `Enter` y `Escape` únicamente mientras esté abierta.

La selección con flechas calculará la posición de la línea actualmente seleccionada dentro de las líneas visibles del ticket. Si no existe selección, `Flecha abajo` seleccionará la primera línea y `Flecha arriba` la última. Una vez seleccionada una línea, las flechas avanzarán o retrocederán sin superar los extremos.

Los atajos globales no actuarán cuando:

- el evento sea una repetición automática;
- la acción esté deshabilitada;
- exista un diálogo incompatible abierto;
- el foco esté en un campo editable, salvo las teclas gestionadas expresamente por el diálogo activo.

## Pruebas

Se añadirán pruebas de interacción que demuestren:

1. `F2` abre Cantidad, enfoca el campo y `Enter` confirma.
2. `F7` abre Descuento, enfoca el campo y `Enter` confirma.
3. `Supr` abre la confirmación; `Enter` confirma y `Escape` cancela.
4. `PageDown` activa el cobro en efectivo y `F10` deja de hacerlo.
5. Las etiquetas visibles muestran `AvPág`.
6. `Flecha arriba` y `Flecha abajo` cambian la línea seleccionada respetando los extremos.
7. Los atajos se ignoran durante repeticiones o cuando un diálogo incompatible está abierto.

Después se ejecutarán las pruebas específicas de Venta, la suite frontend completa y la compilación de APP VENTA.

## Fuera de alcance

- Navegación con flechas izquierda y derecha.
- Paginación de productos.
- Cambios en backend o base de datos.
- Nuevos atajos distintos de los descritos.

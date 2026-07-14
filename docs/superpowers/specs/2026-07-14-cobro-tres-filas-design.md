# Diseño: acciones de cobro en tres filas

## Objetivo

Mejorar la legibilidad y el uso táctil del bloque **Cobro** de APP VENTA. Las acciones actuales aparecen comprimidas en tres columnas y el texto y los atajos se solapan en el ancho disponible.

## Diseño aprobado

- Mostrar `Efectivo`, `Tarjeta` y `Pendiente cliente` en tres filas verticales.
- Cada botón ocupa el ancho completo del contenedor de cobro.
- El nombre de la acción permanece centrado visualmente.
- El atajo `F10`, `F11` o `F12` queda alineado al extremo derecho sin superponerse al texto.
- Mantener una altura cómoda para pantallas táctiles y el lenguaje visual actual: borde, color, estados hover, foco y deshabilitado.
- No modificar la funcionalidad, permisos, atajos de teclado ni estados habilitados de las acciones.

## Implementación

El cambio se limita a los estilos de `.individual-payment-actions` y sus botones. La cuadrícula pasará de tres columnas a una sola columna. Los botones usarán una disposición interna que permita centrar la etiqueta y fijar el `kbd` a la derecha.

## Adaptación

La disposición será igual en modo normal y táctil. Las reglas existentes del modo táctil podrán seguir aumentando el tamaño mínimo del botón sin alterar las tres filas.

## Verificación

- Prueba del componente para confirmar que conserva las tres acciones y sus atajos.
- Compilación de APP VENTA.
- Inspección visual en el ancho lateral mostrado por el usuario.
- Comprobación de ausencia de desbordamiento o solapamiento.

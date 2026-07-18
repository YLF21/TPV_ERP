# Diseño de inicio de APP VENTA y acciones de venta pendiente

## Objetivo

Modernizar dos zonas de APP VENTA sin alterar sus reglas de negocio:

1. La pantalla inicial de módulos adoptará una composición de escritorio con una acción principal de Venta y accesos secundarios horizontales.
2. La ventana de venta pendiente presentará las formas de pago y la confirmación con la misma jerarquía visual que el cobro normal.

## Alcance funcional conservado

- Se mantienen las comprobaciones de permisos actuales para mostrar cada módulo.
- Se mantienen las rutas y callbacks existentes de Venta, Stock/Producto, Informe, Ajustes y Deudas clientes.
- Deudas clientes permanece disponible como cuarta acción secundaria cuando el usuario tenga `CUSTOMER_RECEIVABLES_READ`.
- No se modifican el cálculo, la validación, los estados ni las llamadas API de la venta pendiente.
- Los estados `disabled`, carga, error e incertidumbre de los botones de pago siguen gobernados por la lógica actual.

## Pantalla inicial

### Estructura

La zona central se divide en dos columnas:

- Columna izquierda: tarjeta principal de Venta, de altura equivalente al conjunto de acciones secundarias.
- Columna derecha: lista vertical de tarjetas para Stock, Informe, Ajustes y Deudas clientes.

Las acciones que el usuario no puede abrir no se renderizan. La lista se adapta al número real de acciones visibles sin dejar huecos reservados.

### Tarjeta de Venta

- Zona superior azul marino con el icono actual de venta en blanco.
- Zona inferior azul con el texto `VENTA` destacado.
- Indicador `F1` visible en la parte inferior.
- Toda la tarjeta es un botón y conserva el callback `onOpenSales`.

### Tarjetas secundarias

Cada tarjeta usa tres zonas visuales:

- Icono sobre fondo azul muy claro a la izquierda.
- Nombre del módulo sobre fondo blanco en el centro.
- Indicador de tecla rápida a la derecha.

Asignación prevista para APP VENTA:

- `F2`: Stock/Producto.
- `F3`: Informe.
- `F4`: Ajustes.
- `F5`: Deudas clientes.

Si el proyecto proporciona también Almacén en este inicio, conservará su acción y se integrará en la lista sin eliminar funcionalidad; las teclas visibles de APP VENTA priorizan la asignación anterior.

### Teclado y accesibilidad

- Los botones siguen siendo elementos `button`, utilizables con tabulador y Enter/Espacio.
- APP VENTA escuchará `F1` a `F5` mientras la pantalla inicial esté activa y ejecutará solo acciones visibles y permitidas.
- Se evitará interceptar una tecla si el evento ya fue gestionado o es una repetición mantenida.
- Los iconos decorativos mantienen texto alternativo vacío; el nombre visible identifica cada acción.
- El foco de teclado tendrá un contorno claramente visible.

### Adaptación de tamaño

- En escritorio, se usa la composición de dos columnas de la referencia aprobada.
- En ventanas estrechas, Venta pasa arriba y las acciones secundarias debajo, ocupando todo el ancho.
- No se introducen barras de desplazamiento horizontales.
- La cabecera, controles de usuario y pie de contexto actuales permanecen sin cambios funcionales.

## Ventana de venta pendiente

### Acciones de pago

Los botones `Añadir efectivo`, `Añadir tarjeta` y `Añadir transferencia`:

- Serán azules, con texto blanco y el mismo tamaño visual.
- Se distribuirán en tres columnas iguales con separación uniforme.
- Conservarán sus condiciones de habilitación actuales.
- Mostrarán un estado deshabilitado distinguible y no interactivo.

### Pie de confirmación

El pie se coloca en una fila independiente debajo de errores y formularios auxiliares:

- `Cancelar`: botón secundario de ancho ajustado al contenido.
- `Confirmar venta pendiente`: botón primario azul que ocupa el espacio restante.
- Separación suficiente entre ambos botones y respecto al contenido superior.

En pantallas estrechas, las acciones de pago y el pie pueden apilarse para mantener objetivos táctiles legibles.

## Estrategia de implementación

- Se añadirán clases específicas a `SessionHomeScreen` y `CustomerPendingSaleDialog`.
- Los estilos quedarán acotados a `.home-screen` y `.customer-pending-sale-dialog` para no cambiar botones de otras pantallas.
- Se reutilizarán los iconos existentes; no se incorporarán nuevas dependencias gráficas.
- No se modificará el backend ni la base de datos.

## Verificación

Se ampliarán las pruebas para comprobar:

- Clases y marcadores de tecla de las tarjetas.
- Ejecución de `F1` a `F5` solo cuando la acción correspondiente sea visible.
- Conservación de permisos para Deudas clientes y resto de módulos.
- Clases primarias de las tres formas de pago y del botón de confirmación.
- Conservación de estados deshabilitados y callbacks de la venta pendiente.
- Renderizado responsivo mediante las reglas CSS y compilación de APP VENTA.

La validación final incluirá pruebas unitarias del paquete afectado, compilación de APP VENTA e inspección visual en el navegador local.

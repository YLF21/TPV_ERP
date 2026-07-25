# APP VENTA: endurecimiento focalizado

## Objetivo

Cerrar los defectos de calidad más visibles de APP VENTA sin ampliar el alcance a integraciones físicas todavía no disponibles. El trabajo debe mejorar la consistencia de los idiomas, actualizar la documentación técnica, verificar las resoluciones de escritorio soportadas y cubrir una única carrera crítica de inventario que actualmente no tiene prueba PostgreSQL concurrente.

## Alcance

### 1. Localización completa de los diálogos de venta

Los textos visibles y accesibles de `SaleScreen` deben salir del catálogo de mensajes activo. Se eliminarán los literales en español de:

- edición de cantidad;
- aplicación de descuento;
- autorización del encargado;
- selección de cliente;
- anulación de línea;
- botones Guardar, Cancelar y Cerrar;
- etiquetas, descripciones y `aria-label` de esos diálogos.

Cada clave nueva existirá en español, inglés y chino. Los tres catálogos conservarán exactamente el mismo conjunto de claves. Los tests no dependerán de textos españoles cuando la intención real sea identificar un control por su función.

### 2. Retirada de mensajes provisionales

Las pantallas de ajustes no deben afirmar que una función futura “se conectará aquí” cuando esa función ya existe. Se sustituirán los mensajes provisionales de:

- preferencias del usuario;
- preferencias de informes;
- cupón promocional.

Los textos resultantes describirán la capacidad actual y estarán traducidos en los tres idiomas.

### 3. Auditoría técnica actualizada

`docs/frontend-backend-audit.md` se reescribirá para reflejar el estado actual del proyecto. Cada área se clasificará como:

- implementada;
- parcial;
- dependiente de un proveedor o hardware externo.

La auditoría deberá reconocer expresamente las funciones ya presentes en APP VENTA: flujo de venta, efectivo, simulación de tarjeta, ventas aparcadas, gestión posterior de tickets, promociones, socios, cuenta corriente, almacén, informes y ajustes.

También dejará fuera de la categoría “pendiente de código interno” aquello que requiere SDK oficial, dispositivo físico o certificación externa, como el datáfono real y la certificación fiscal de producción.

### 4. Compatibilidad de escritorio y accesibilidad

La aplicación seguirá siendo de escritorio; no se introduce un diseño móvil. El límite mínimo soportado es 1024 px de ancho.

Se comprobarán estas resoluciones:

| Resolución | Comprobación principal |
| --- | --- |
| 1920×1080 | carrito largo, venta aparcada y cobro sin recortes ni scroll horizontal del documento |
| 1366×768 | efectivo, tarjeta y resultado final con todas las acciones accesibles |
| 1024×768 | límite mínimo: búsqueda, carrito y diálogos operables sin desbordamiento horizontal |

En las tres resoluciones:

- los controles principales deben permanecer visibles o alcanzables mediante el scroll de su propio panel;
- ningún diálogo debe superar de forma inutilizable la altura visible;
- `Tab`, `Enter` y `Escape` deben conservar el comportamiento esperado;
- el foco debe permanecer dentro de los diálogos y regresar al control que los abrió;
- las etiquetas accesibles deben usar el idioma activo;
- se comprobarán español, inglés y chino para detectar desbordes causados por traducciones.

No se exige una comparación de píxeles exacta. Las pruebas validarán geometría, visibilidad, ausencia de scroll horizontal global y comportamiento del foco para evitar fragilidad visual.

### 5. Carrera crítica de existencias

La cobertura PostgreSQL existente ya protege:

- idempotencia del cobro en efectivo;
- finalización concurrente de una sesión de pago;
- cadena fiscal concurrente;
- ajustes, anulaciones y devoluciones de tarjeta.

No se duplicarán esas pruebas.

Se añadirá una prueba PostgreSQL para dos ventas diferentes iniciadas simultáneamente sobre el mismo producto y almacén. La prueba debe:

1. crear dos checkouts o sesiones distintas;
2. sincronizar el comienzo de las dos operaciones;
3. registrar de forma explícita el resultado de cada transacción;
4. comprobar que no existe una sobrescritura silenciosa;
5. verificar el invariante final entre `existencia.cantidad` y la suma de `movimiento_stock`.

Si la colisión optimista actual rechaza una de las operaciones, ese rechazo debe ser determinista y recuperable. Si el comportamiento funcional esperado es aceptar ambas, el servicio deberá bloquear o reintentar de forma controlada. La implementación no ocultará errores de concurrencia ni convertirá automáticamente una venta fallida en confirmada.

## Arquitectura de los cambios

### Frontend

- Ampliar los catálogos `MessagesEs`, `MessagesEn` y `MessagesZh`.
- Sustituir literales en `SaleScreen` por el acceso de traducción ya utilizado por el resto de APP VENTA.
- Mantener los componentes y estilos existentes; solo se harán ajustes de layout necesarios para las resoluciones soportadas.
- Añadir pruebas unitarias de los diálogos y pruebas Playwright parametrizadas por resolución e idioma.

### Backend

- Incorporar primero la prueba PostgreSQL concurrente.
- Cambiar el servicio de inventario únicamente si la prueba demuestra un resultado incoherente o no recuperable.
- Reutilizar el método de repositorio con bloqueo ya existente o el mecanismo de versión optimista, según el resultado funcional elegido; no se introduce un segundo modelo de concurrencia.

### Documentación

- Actualizar la auditoría con evidencia del código y de las pruebas.
- Separar claramente funcionalidad simulada de integración física real.

## Criterios de aceptación

- No quedan literales visibles en español dentro de los diálogos cubiertos de `SaleScreen`.
- Los catálogos español, inglés y chino tienen las mismas claves y pasan su prueba de integridad.
- Los mensajes provisionales de usuario, informes y cupón describen la función actual.
- La auditoría técnica ya no marca como ausentes funciones que están implementadas.
- Playwright pasa en 1920×1080, 1366×768 y 1024×768, incluyendo español, inglés y chino en los puntos con riesgo de desbordamiento.
- Los recorridos de teclado conservan foco, `Enter` y `Escape`.
- La nueva prueba PostgreSQL concurrente demuestra el invariante de existencias y movimientos.
- Las pruebas existentes de pagos, APP VENTA y backend continúan pasando.

## Fuera de alcance

- SDK y conexión física real con Redsys, Global Payments, PAYTEF o PAYCOMET.
- Instalación o configuración de un datáfono.
- Certificación fiscal o VERI*FACTU de producción.
- Rediseño móvil o soporte por debajo de 1024 px.
- Auditoría completa WCAG con una nueva dependencia automática.
- Nuevas promociones, métodos de pago o reglas comerciales.

## Riesgos y mitigaciones

- **Fragilidad de pruebas visuales:** se comprueba geometría y comportamiento, no capturas exactas.
- **Cambio accidental de reglas de stock:** la prueba concurrente fija primero el invariante y cualquier modificación se limita al punto de escritura.
- **Traducciones demasiado largas:** las tres variantes se ejecutan en el viewport mínimo.
- **Documentación que vuelve a quedar obsoleta:** cada afirmación de la auditoría enlazará con la capa o prueba que la respalda.

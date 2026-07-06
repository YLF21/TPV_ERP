# APP VENTA: pantalla cliente compacta

## Objetivo

APP VENTA debe poder abrir una pantalla cliente local para displays de dos lineas tipo 客显. Esta pantalla no consulta backend ni controla la venta; solo refleja el estado que le envia APP VENTA desde Electron.

La primera parte de Fase 2 implementa solo el modo compacto de dos lineas. El modo completo para segundo monitor grande queda para una extension posterior.

## Alcance

Incluido:

- Ventana secundaria Electron para pantalla cliente.
- Modo compacto de dos lineas.
- Configuracion local por terminal.
- Textos de reposo configurables.
- Botones de abrir, cerrar y enviar prueba.
- API local para actualizar la pantalla cliente.

Fuera de este paso:

- Pantalla secundaria completa con lista de lineas.
- Sincronizacion con una pantalla real de venta final si aun no existe.
- Comunicacion con hardware serial especifico de displays antiguos.

## Comportamiento compacto

La pantalla compacta siempre muestra dos lineas.

Estado reposo:

- Linea 1: texto configurable.
- Linea 2: texto configurable.

Estado venta:

- Linea 1: nombre del ultimo articulo.
- Linea 2: `cantidad x precio`.

Estado cobro:

- Linea 1: `TOTAL: importe`.
- Linea 2: `CAMBIO: importe` cuando haya cambio calculado.
- Si aun no hay cambio, la linea 2 puede mostrar el importe cobrado o quedar con texto de cobro.

## Configuracion

Campos nuevos dentro de la configuracion local de hardware:

- `customerDisplayEnabled`
- `customerDisplayMode`: `COMPACT`
- `customerDisplayIdleLine1`
- `customerDisplayIdleLine2`
- `customerDisplayScreenId`

La pantalla de configuracion hardware debe incluir:

- activar/desactivar pantalla cliente,
- seleccionar monitor destino cuando Electron detecte mas de una pantalla,
- texto reposo linea 1,
- texto reposo linea 2,
- boton abrir pantalla cliente,
- boton cerrar pantalla cliente,
- boton enviar prueba.

## Electron

Electron tendra IPC para:

- listar pantallas disponibles,
- abrir ventana cliente,
- cerrar ventana cliente,
- actualizar contenido de dos lineas,
- enviar estado de reposo,
- enviar prueba.

La ventana cliente sera sin menu, pantalla completa o maximizada en el monitor elegido. Debe tener fondo oscuro y texto grande de alto contraste.

## React

APP VENTA consumira la API local desde `app-common`. La primera implementacion solo envia datos de prueba desde la pantalla de hardware. Cuando la pantalla TPV real este conectada, enviara eventos reales:

- ultimo articulo,
- cantidad,
- precio,
- total,
- cambio.

## Errores

Errores traducibles:

- pantalla cliente no disponible fuera de Electron,
- no se pudo abrir pantalla cliente,
- pantalla destino no encontrada,
- pantalla cliente no esta abierta.

Ningun error de pantalla cliente debe bloquear ventas, cobros ni impresion.

## Pruebas

Pruebas automaticas:

- configuracion por defecto incluye pantalla cliente desactivada y textos de reposo.
- el estado de reposo genera dos lineas.
- el estado de venta genera nombre y cantidad por precio.
- el estado de cobro genera total y cambio.

Pruebas manuales:

- abrir pantalla cliente desde configuracion,
- cerrar pantalla cliente,
- enviar prueba,
- cambiar textos de reposo,
- probar seleccion de monitor si hay mas de una pantalla.


# APP VENTA Business Classic UI V2

## Objetivo

Aplicar a APP VENTA un estilo de escritorio ERP/TPV mas clasico y profesional, alineado con las pantallas actuales de la aplicacion. El cambio debe evitar apariencia de catalogo, tarjetas grandes redondeadas, fondos decorativos y estetica tipo mobile/app promocional.

## Alcance

Pantallas actuales de `frontend/apps/app-venta/src/main.tsx`:

- Login
- Home de sesion
- Venta
- Stock
- Informe de ventas
- Ajustes
- Ajustes de hardware

## Decisiones de UI

- Apariencia business/desktop: fondos gris-azulados suaves, paneles blancos, bordes visibles y sombras discretas.
- Radios pequenos: `4px` como valor base; dialogos y paneles pueden llegar a `6px`, sin esquinas tipo pill salvo chips muy concretos.
- Densidad alta: tablas, formularios, toolbars y botones compactos para uso de caja y gestion repetitiva.
- Tipografia: `Segoe UI` para espanol, con fallback `Microsoft YaHei UI` y `Noto Sans SC` para chino.
- Estado seleccionado: azul marino corporativo con acento lateral azul.
- Foco accesible: ring visible en botones, inputs, selects, buscadores y controles de fecha.
- Tablas: cabeceras sobrias, filas de 32-36px, separadores claros y seleccion evidente.
- Desplegables y fecha: popovers blancos, borde recto, cabecera compacta, seleccion marino y rango en azul claro.

## Fuera de Alcance

- No se cambian rutas, permisos, llamadas API ni modelos de datos.
- No se redisenan componentes React en esta fase.
- No se sustituyen textos ni flujos de teclado existentes.

## Implementacion

Se anade una capa final de CSS en `frontend/packages/app-common/src/styles/tpv.css` con tokens `--tpv-business-*` y `--tpv-v3-*` sobre las clases existentes. La aplicacion tambien incorpora pequenos ajustes estructurales en las pantallas de entrada:

- `LoginScreen`: cabecera interna del panel y estado local visible.
- `SessionHomeScreen`: panel superior de tienda y modulos con subtitulo operativo.
- `SaleScreen`: layout de TPV clasico con panel operativo izquierdo y lineas de venta a la derecha.

Esta base actua como referencia para futuras pantallas de APP VENTA.

## Criterios de Aceptacion

- Las pantallas de APP VENTA se ven mas clasicas, compactas y business.
- Login/home dejan de parecer catalogo o landing.
- Venta y stock mantienen lectura rapida de tabla, ticket, busqueda y acciones.
- Informe, ajustes y hardware comparten el mismo lenguaje visual.
- El foco de teclado es visible en controles interactivos.
- Los desplegables y selectores de fecha usan panel blanco, borde claro, radio bajo y seleccion marino.
- `npm run build` del frontend sigue pasando.

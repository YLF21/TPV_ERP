# Diálogo de cobro en efectivo con UI clásica

## Objetivo

Igualar el diálogo de entrada del cobro en efectivo con el lenguaje visual clásico y compacto de APP VENTA. El diálogo debe conservar el teclado numérico táctil completo y toda su funcionalidad actual, pero dejar de parecer un componente móvil con tarjetas redondeadas y controles sobredimensionados.

## Alcance

El cambio se limita al diálogo `CashPaymentDialog` que permite introducir el dinero recibido antes de confirmar un cobro en efectivo.

Quedan fuera de alcance:

- El diálogo de pago con tarjeta.
- El diálogo de resultado del pago.
- Los diálogos de cantidad, descuento y cliente.
- La lógica de cálculo, confirmación, foco, teclado físico y accesibilidad.
- Los flujos y contratos de pago.

## Enfoque

Se añadirá una clase modificadora exclusiva al contenedor de `CashPaymentDialog`. Los estilos nuevos se aplicarán mediante esa clase, sin cambiar la clase base `cash-payment-dialog`, que también utilizan otros diálogos.

La estructura React actual se conservará. No se reorganizarán los controles en columnas ni se cambiará el orden de tabulación.

## Diseño visual

### Contenedor y cabecera

- Contenedor blanco con borde visible, radio de `4px` y sombra discreta.
- Anchura de `420px` limitada por el viewport y altura máxima de `calc(100dvh - 32px)`, con desplazamiento vertical cuando sea necesario.
- Padding y separaciones internas reducidos para aumentar la densidad.
- Cabecera gris clara, separada del contenido por un borde.
- Título compacto y botón de cierre cuadrado, con foco visible.

### Resumen e importe

- Total, dinero recibido y cambio se mostrarán como filas ERP compactas con borde o separador claro.
- Las etiquetas quedarán a la izquierda y los importes a la derecha, usando números tabulares.
- La fila de cambio conservará una señal visual verde sobria.
- El campo de dinero recibido tendrá menor altura y radio bajo, sin perder el foco azul accesible ni la alineación numérica.

### Atajos y teclado numérico

- Se conservarán `Exacto`, `5 €`, `10 €`, `20 €` y `50 €`.
- Se conservará el teclado completo: dígitos, separador decimal, retroceso y limpieza.
- Se mantendrán las cuadrículas actuales de cinco columnas para atajos y tres columnas para el teclado.
- Los botones serán rectangulares, con radio bajo, separación reducida y altura suficiente para uso táctil.
- El selector entre teclado táctil y físico seguirá disponible y conservará su comportamiento.

### Acciones y estados

- `Cancelar` será una acción secundaria compacta.
- `Confirmar cobro` será la acción primaria azul y ocupará el espacio restante.
- Los estados deshabilitado, envío y error conservarán su comportamiento actual.
- El diálogo seguirá cerrándose con `Escape` y confirmándose con `Enter` cuando el importe recibido cubra el total.

## Implementación prevista

- Añadir la clase `cash-payment-entry-dialog` al elemento con `role="dialog"` de `CashPaymentDialog.tsx`.
- Añadir estilos específicos bajo `.cash-payment-entry-dialog` en `tpv.css`, reutilizando los tokens `--tpv-v3-*` existentes.
- Evitar selectores que cambien `CardPaymentDialog` o `CashPaymentResultDialog`.

## Pruebas y verificación

- Actualizar la prueba de render para comprobar la clase exclusiva del diálogo.
- Mantener las pruebas existentes de teclado táctil, teclado físico, foco, deshabilitado, `Escape` y `Enter`.
- Ejecutar las pruebas de `CashPaymentDialog`.
- Ejecutar la compilación del frontend.
- Revisar visualmente que el diálogo mantenga el teclado completo, quepa en el viewport y coincida con el estilo clásico de APP VENTA.

## Criterios de aceptación

- El diálogo tiene apariencia ERP compacta y rectangular.
- El teclado numérico táctil completo sigue visible en modo táctil.
- Todas las acciones y atajos actuales continúan funcionando.
- El foco de teclado es claramente visible.
- El contenido sigue siendo utilizable en viewports de poca altura mediante desplazamiento.
- Los demás diálogos de pago no cambian de aspecto.

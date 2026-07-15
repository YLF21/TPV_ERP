# Diálogo compacto de pago completado

## Objetivo

Uniformar el diálogo `Pago completado` de APP VENTA con el estilo ERP compacto y rectangular ya aplicado al diálogo de cobro en efectivo.

## Referencia aprobada

Se adopta la variante visual A: el diálogo de resultado reutiliza las mismas proporciones, geometría y jerarquía visual del diálogo `Cobro en efectivo`.

## Diseño visual

- Ancho máximo de `420px`, adaptable al viewport.
- Contenedor con borde de `1px`, radio de `4px`, fondo de superficie y sombra ERP discreta.
- Cabecera de `38px` con fondo alternativo, borde inferior y título de `16px`.
- Indicador de éxito verde compacto, cuadrado y alineado a la derecha de la cabecera.
- Ticket como línea secundaria compacta debajo de la cabecera.
- Total, dinero recibido y cambio en filas independientes de al menos `34px`, con borde y radio de `3px`.
- Importes de `16px` con cifras tabulares.
- Cambio, cuando exista, conserva el tratamiento verde suave del diálogo de efectivo.
- Botón `Finalizar` azul, rectangular, de al menos `34px` y con márgenes equivalentes al pie del diálogo de efectivo.
- Sin tarjetas grandes, radios redondeados amplios ni espacios verticales propios del estilo anterior.

## Comportamiento

- No se modifica el flujo de finalización de la venta.
- Se mantienen el overlay modal, el focus trap, `autoFocus`, la acción `onFinish` y la accesibilidad existente.
- Se siguen mostrando ticket, método, autorización y referencia cuando correspondan.
- Total, dinero recibido y cambio conservan sus condiciones actuales de visibilidad y formato.
- El cambio es exclusivamente de presentación.

## Implementación

- `CashPaymentResultDialog` conservará su estructura semántica y recibirá únicamente clases específicas si son necesarias para aplicar el patrón.
- Los estilos se añadirán como overrides específicos de `.cash-payment-result-dialog` junto al bloque ERP compacto de `.cash-payment-entry-dialog`.
- No se generalizarán estilos globales ni se refactorizarán otros diálogos.

## Pruebas

- Actualizar las pruebas del componente para exigir las clases estructurales del resultado compacto.
- Añadir una prueba de contrato CSS que confirme ancho, radio, cabecera, filas y botón compactos.
- Ejecutar las pruebas del componente, la suite frontend completa y los builds de APP GESTIÓN y APP VENTA.


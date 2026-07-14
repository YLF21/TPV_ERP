# Caja de prueba para APP VENTA

## Objetivo

Permitir que un operador del entorno de desarrollo abra manualmente una sesión de caja de prueba desde APP VENTA. Esto desbloquea la finalización de ventas en efectivo sin debilitar la obligación de disponer de una caja abierta ni introducir funciones de prueba en producción.

## Causa actual

El backend exige una sesión de caja abierta para registrar movimientos de efectivo y expone `POST /api/v1/cash/sessions/open`. APP VENTA no ofrece actualmente ninguna acción para abrirla. Cuando un pago en efectivo ya está cubierto, la finalización del documento falla con `No hay una sesion de caja abierta` y conserva la sesión de cobro para un reintento seguro.

Además, la primera apertura de una terminal exige una sesión anterior cerrada o una entrada entre sesiones. El conjunto de datos demo no proporciona ninguno de esos antecedentes.

## Alcance

- Añadir un botón contextual `Abrir caja de prueba` en el panel de cobro de APP VENTA.
- Mostrar la acción solo en una compilación Vite de desarrollo.
- Abrir la caja de la terminal actual mediante el endpoint real de sesiones de caja.
- Preparar los datos demo del perfil backend `dev` para que la primera apertura empiece con `0,00 €`.
- Conservar el cobro pendiente y dejar que el usuario pulse `Finalizar venta` después de abrir la caja.

Quedan fuera de alcance:

- La gestión completa de apertura, movimientos, arqueo y cierre de caja.
- La apertura automática al iniciar sesión.
- La apertura o finalización automática después de un error de pago.
- Cualquier bypass de permisos o validaciones del servicio de caja.
- La exposición del botón o de datos de prueba en producción.

## Arquitectura

### Datos demo del backend

`DevSampleDataSeeder`, activo únicamente con el perfil Spring `dev`, añadirá de forma idempotente una sesión histórica cerrada para la terminal demo. La sesión tendrá fondo inicial, efectivo teórico, fondo dejado y descuadre de `0,00 €`, y utilizará el usuario demo tanto para apertura como para cierre.

La inserción tendrá un UUID determinista y `on conflict do nothing`. No se creará una sesión abierta durante el arranque. El endpoint existente `POST /cash/sessions/open` seguirá siendo la única vía para abrir la caja; su cálculo de fondo inicial recuperará los `0,00 €` dejados por la sesión histórica.

### Acción de desarrollo en el frontend

`SalePaymentCheckout` recibirá la propiedad opcional `testCashEnabled`, cuyo valor predeterminado será `false`. `SaleScreen` pasará `testCashEnabled={import.meta.env.DEV && app === "venta"}`. Así las pruebas podrán controlar la señal explícitamente y cualquier consumidor que no la proporcione permanecerá protegido.

El error se reconocerá con una función pura `isMissingCashSessionError(message)`: normalizará el texto a minúsculas, eliminará diacríticos y comprobará la frase `sesion de caja abierta`. El botón solo se renderizará cuando se cumplan todas estas condiciones:

- la compilación es de desarrollo;
- existe una sesión de pago con estado `COVERED`;
- la finalización ha devuelto el error de sesión de caja no abierta;
- existe `terminal.terminalId`.

Al pulsar el botón se enviará:

```http
POST /api/v1/cash/sessions/open
Content-Type: application/json

{"terminalId":"<terminal actual>"}
```

Se reutilizarán `apiRequest`, el token de la sesión y el estado `busy` del checkout.

## Flujo de usuario

1. El usuario registra el efectivo.
2. La sesión de cobro queda `COVERED`.
3. La finalización falla porque no hay sesión de caja abierta.
4. El panel conserva `Finalizar venta`, muestra el error y ofrece `Abrir caja de prueba`.
5. El usuario pulsa el botón.
6. Si la apertura tiene éxito, el error se sustituye por `Caja de prueba abierta. Pulse Finalizar venta.`.
7. El usuario pulsa `Finalizar venta`; el flujo existente reintenta la finalización sin crear otra asignación de efectivo.

El botón no reintenta automáticamente la finalización y no vuelve a registrar el pago.

## Estados y errores

- Mientras se abre la caja, el botón estará deshabilitado y mostrará `Abriendo caja...`.
- Si la apertura falla, se conservará la sesión de pago y se mostrará el mensaje devuelto por `ApiError`.
- Si falta el identificador de terminal, el botón no se renderizará.
- Si la caja ya fue abierta por otro proceso antes de pulsar, el backend conservará su validación de sesión única y el error se mostrará sin modificar el cobro pendiente.
- Al completarse la venta se limpiarán los estados de pago mediante el flujo existente.

## Presentación

- El botón se ubicará junto al aviso dentro del estado `FINALIZE_RETRY` del panel `Cobro`.
- Usará el lenguaje visual ERP compacto existente: borde visible, radio bajo y altura equivalente a las acciones del panel.
- El mensaje de éxito usará `role="status"`; los errores conservarán `role="alert"`.

## Seguridad y aislamiento

- El frontend ocultará la acción cuando `import.meta.env.DEV` sea falso.
- La semilla solo existirá en `DevSampleDataSeeder`, cargado por `@Profile("dev")`.
- El endpoint real mantendrá sus permisos `ADMIN`, `GESTION_VENTAS` o `CASH_OPERATE`.
- No se añadirá un endpoint de bypass ni se relajará `CashSessionService`.
- No se insertarán sesiones abiertas automáticamente.

## Pruebas

### Frontend

- El botón aparece con entorno de desarrollo, sesión `COVERED`, error de caja cerrada y terminal identificada.
- No aparece cuando `isMissingCashSessionError` devuelve falso, sin terminal o con `testCashEnabled={false}`.
- Al pulsarlo se envía una sola petición con el `terminalId` correcto.
- El botón refleja carga, éxito y error sin reintentar automáticamente la venta.
- Las pruebas de recuperación y finalización de `SalePaymentCheckout` siguen pasando.

### Backend

- La semilla crea una sesión histórica cerrada con importes `0,00 €`.
- Ejecutar la semilla dos veces no duplica la sesión.
- La terminal demo puede abrir una caja mediante `CashSessionService` con fondo inicial `0,00 €` después del sembrado.
- Las pruebas existentes de sesiones de caja siguen pasando.

### Verificación

- Ejecutar las pruebas enfocadas de frontend y backend.
- Ejecutar la compilación de APP VENTA.
- Comprobar manualmente el flujo completo desde un pago en efectivo pendiente hasta la finalización de la venta.
- Confirmar con una compilación de producción que el botón no se incluye en la interfaz.

## Criterios de aceptación

- El operador puede abrir manualmente una caja de prueba desde el panel de cobro en desarrollo.
- La caja se abre con fondo inicial `0,00 €` usando el servicio real y sus permisos.
- El cobro cubierto no se duplica y solo se finaliza cuando el usuario vuelve a pulsar `Finalizar venta`.
- Los errores permanecen visibles y recuperables.
- El botón y la preparación de datos demo no afectan a producción.

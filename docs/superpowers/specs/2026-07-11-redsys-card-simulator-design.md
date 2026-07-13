# Simulador de cobro con tarjeta Redsys TPV-PC

## Objetivo

Implementar el botón **Tarjeta** de APP VENTA mediante un simulador backend de Redsys TPV-PC que cree tickets y pagos reales en la base de datos cuando el resultado sea aprobado. La arquitectura permitirá sustituir el simulador por el SDK oficial sin modificar el flujo de Venta.

## Alcance

- El simulador se configura por terminal en PostgreSQL mediante la configuración de datáfono existente.
- El resultado del simulador se elige desde Configuración, nunca desde Venta.
- APP VENTA muestra el mismo flujo que utilizará el datáfono real.
- No se almacenan PAN, PIN, CVV ni datos sensibles de tarjeta.
- No se implementa todavía comunicación con el SDK real de TPV-PC.
- Todo queda local, sin commit ni push.

## Contrato de proveedor

El backend expondrá un puerto `CardTerminalGateway` con operaciones de prueba de conexión y cobro. Recibirá identificador idempotente, terminal, proveedor, importe y modo de prueba. Devolverá un resultado normalizado con estado, referencia, autorización opcional y mensaje seguro.

Implementaciones:

- `RedsysSimulatorGateway`: disponible exclusivamente cuando la terminal tenga `testMode=true` y proveedor `REDSYS_TPV_PC`.
- `RedsysTpvPcGateway`: punto de extensión futuro. Mientras no exista el SDK, cualquier intento con `testMode=false` devolverá un error explícito de conector no disponible y nunca aprobará una venta.

## Estados

El simulador admite:

- `APPROVED`: autorización simulada y ticket confirmado.
- `DECLINED`: operación rechazada sin ticket.
- `TIMEOUT`: resultado incierto/sin ticket; se conserva la venta.
- `CONNECTION_ERROR`: fallo de conexión sin ticket.

Los estados se normalizan al vocabulario ya utilizado por los metadatos del pago. Si el enum persistente actual no incluye algún estado necesario, se ampliará mediante migración compatible.

## Configuración

En **Configuración → Terminal** se añadirá una tarjeta **Datáfono** que consumirá:

- `GET /api/v1/terminal-configuration/payment`
- `PATCH /api/v1/terminal-configuration/payment`
- `POST /api/v1/terminal-configuration/payment/connection-test`

Campos editables:

- Modo `MANUAL` o `INTEGRATED`.
- Proveedor `REDSYS_TPV_PC`.
- Nombre visible.
- Activado.
- Modo de pruebas.
- Próximo resultado del simulador.

El próximo resultado se almacenará como parámetro no sensible validado. El backend no aceptará claves, secretos ni credenciales dentro de `providerParameters`.

El botón **Probar conexión** realizará una prueba backend real contra el gateway seleccionado. El cliente no enviará un booleano indicando éxito; el backend calculará y registrará el resultado.

## Flujo de cobro

1. El usuario añade productos y opcionalmente selecciona cliente.
2. Pulsa **Tarjeta**.
3. El frontend solicita al backend una cotización autoritativa.
4. Aparece un modal **Pago con tarjeta** con total y estado **Conectando con el datáfono**.
5. El frontend envía una única solicitud con `checkoutId`, venta y total cotizado.
6. El backend valida configuración, proveedor, modo integrado, terminal actual y total.
7. El gateway devuelve el resultado normalizado.
8. Solo en `APPROVED` se crea el ticket con pago `TARJETA` y metadatos:
   - `cardMode=INTEGRATED`
   - `paymentTerminalProvider=REDSYS_TPV_PC`
   - `paymentTerminalStatus=APPROVED`
   - autorización simulada
   - referencia de operación
   - terminal actual
9. El frontend limpia la venta únicamente tras la confirmación y muestra **Pago completado** con ticket, total, método y autorización.
10. En rechazo, timeout o error no se crea ticket, se mantiene la venta y el modal permite reintentar o cancelar.

## Idempotencia y concurrencia

- El frontend genera un `checkoutId` único por intento lógico.
- La guarda síncrona existente evita dobles pulsaciones.
- El backend debe tratar reintentos del mismo `checkoutId` como la misma operación y no crear tickets duplicados.
- Un timeout no se convertirá automáticamente en rechazo ni aprobación.
- El futuro gateway real deberá ofrecer consulta/recuperación antes de permitir reintentos con resultado incierto.

## API de venta con tarjeta

Se añadirán endpoints equivalentes al cobro en efectivo:

- `POST /api/v1/pos/card/quote`
- `POST /api/v1/pos/card/charge`

Las respuestas no aprobadas usarán un cuerpo estructurado con estado y mensaje; no dependerán de analizar texto de excepciones. Los errores de validación/configuración seguirán el formato global de errores del backend.

## Componentes frontend

- Tarjeta de configuración de datáfono en `SettingsScreen` o componente específico pequeño.
- `CardPaymentDialog`: estado de conexión, resultado, reintento y cancelación, con contención/restauración de foco.
- Reutilización del resumen `CashPaymentResultDialog` mediante generalización a un diálogo de resultado de pago, o componente equivalente sin duplicar lógica modal.
- `SaleScreen`: cotización, envío único, conservación del ticket en error y transición al resultado.

## Pruebas

- Gateway: aprobado, rechazado, timeout, conexión fallida y prohibición fuera de test.
- Configuración: lectura, actualización, parámetros permitidos y prueba de conexión calculada por backend.
- Servicio de tarjeta: validación de terminal/configuración, ticket real aprobado y ausencia de ticket en fallos.
- Idempotencia: dos solicitudes con el mismo `checkoutId` generan como máximo un ticket.
- Metadatos: proveedor, estado, autorización, referencia y terminal.
- Frontend: configuración, espera, éxito, rechazo, timeout, reintento, cancelación y doble pulsación.
- Regresión: efectivo, descuentos de socio, licencias y compilación completa.

## Sustitución por TPV-PC real

Cuando se reciba el SDK oficial:

1. Implementar `RedsysTpvPcGateway` contra el proceso/DLL/API local proporcionado.
2. Mapear códigos Redsys al resultado normalizado.
3. Implementar consulta, cancelación y recuperación tras timeout.
4. Añadir secretos mediante un almacén seguro referenciado por `secretReference`.
5. Ejecutar certificación con Redsys/banco.
6. Habilitar `testMode=false` solo después de homologación.

APP VENTA, los endpoints y la creación del ticket no deberán cambiar.

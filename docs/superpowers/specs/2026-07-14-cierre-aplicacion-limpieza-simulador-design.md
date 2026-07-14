# Cierre de aplicación y limpieza de cobros simulados

## Objetivo

Conectar **Cerrar aplicación** con la seguridad del flujo de pago y garantizar que, en modo simulador, una sesión antigua no reaparezca con el total anterior al volver a entrar en Venta.

## Comportamiento por modo

### Simulador o pruebas

- Al confirmar **Cerrar aplicación**, la aplicación prepara el cierre antes de invocar Electron o `window.close()`.
- Una sesión vacía o con asignaciones `DECLINED`, `ERROR` o `CANCELLED` se cancela mediante el endpoint existente.
- Una sesión simulada con `PENDING`, `TIMEOUT`, `APPROVED`, `COVERED` o `COMPENSATION_REQUIRED` se limpia mediante una operación backend explícita, auditada y permitida únicamente cuando la configuración activa del terminal está en modo de pruebas.
- La limpieza elimina el bloqueo operativo de la sesión, pero conserva los registros históricos del simulador y la auditoría.
- Después de cerrar y volver a abrir, `/pos/payment-sessions/active` no devuelve la sesión anterior y Venta muestra total cero/ticket vacío.

### Datáfono real o producción

- Nunca se fuerza la limpieza de `PENDING`, `TIMEOUT`, `APPROVED`, `COVERED` o `COMPENSATION_REQUIRED`.
- El cierre de la aplicación queda bloqueado y se muestra un aviso accesible para resolver el cobro.
- Los fallos de red también bloquean el cierre y conservan todos los identificadores de recuperación.

## Arquitectura

- El backend expondrá una acción específica para descartar una sesión de pago simulada. Validará en servidor que la configuración del terminal sea simulada/test antes de modificar la sesión.
- La acción dejará la sesión en un estado terminal no activo y registrará el motivo de limpieza del simulador.
- `SalePaymentCheckout` ampliará su preparación de salida para distinguir entre logout y cierre de aplicación, consultar el modo activo y usar la limpieza simulada solo cuando corresponda.
- `SaleScreen` proporcionará a `SessionTopControls` un callback asíncrono de preparación de apagado.
- `SessionTopControls` cerrará la ventana únicamente cuando el callback confirme `READY`.
- Al montar Venta, una sesión simulada antigua seguirá el mismo proceso de limpieza automática para cubrir cierres forzados o caídas anteriores.

## Presentación

- Durante la preparación se deshabilita la confirmación para impedir dobles solicitudes.
- Si el cierre está bloqueado, el diálogo se cierra y aparece: **Debes resolver el cobro pendiente antes de cerrar la aplicación**.
- Tras limpiar una sesión simulada al entrar, se ocultan **Cobro pendiente**, **Venta reservada en cobro** y el total anterior.
- El flujo ordinario conserva **Efectivo**, **Tarjeta** y **Pendiente cliente**.

## Seguridad

- La decisión simulador/real se toma en el backend, no mediante un valor confiado enviado por el navegador.
- El endpoint rechaza la limpieza si el terminal no está configurado en modo de pruebas.
- No se eliminan operaciones, recibos ni eventos de auditoría.
- No se habilita un segundo cobro mientras la limpieza o recuperación está en curso.
- Las referencias manuales, contraseñas y secretos no se incluyen en la solicitud.

## Pruebas

- Backend: limpieza aceptada en modo test para cada estado no terminal y sesión deja de estar activa.
- Backend: limpieza rechazada en modo real, terminal ajeno o sesión inexistente.
- Frontend: **Sí** en Cerrar aplicación espera la preparación y cierra solo con `READY`.
- Frontend: doble clic genera una única preparación y un único cierre.
- Frontend: modo real/indeterminado bloquea cierre y conserva recuperación.
- Frontend: al entrar con sesión simulada antigua se limpia y el total vuelve a cero.
- Frontend: si falla la limpieza, se conserva el cobro pendiente y la aplicación permanece abierta.
- Suite completa de backend/frontend y compilación de APP VENTA/APP GESTIÓN.

## Fuera de alcance

- Eliminar historiales de pago o auditoría.
- Forzar la limpieza de operaciones de datáfono real.
- Modificar el protocolo físico de Redsys, PAYTEF, PAYCOMET o Global Payments.

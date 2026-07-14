# Cierre seguro de usuario con cobro pendiente

## Objetivo

Evitar que una venta cancelable reaparezca al volver a iniciar sesión, sin ocultar ni duplicar cobros que puedan haberse procesado en un datáfono.

## Comportamiento

- Si no existe una sesión de cobro activa, **Cerrar usuario** termina la sesión inmediatamente.
- Si la sesión de cobro no contiene cargos o solo contiene resultados seguros `DECLINED` o `ERROR`, el cierre cancela primero la sesión de cobro y después termina la sesión del usuario.
- Si existe cualquier asignación `PENDING`, `TIMEOUT` o `APPROVED`, o la sesión requiere finalización o compensación, el cierre se bloquea.
- Cuando se bloquea, la pantalla permanece en Venta y muestra un aviso accesible: **Debes resolver el cobro pendiente antes de cerrar usuario**.
- Después de consultar, cancelar, finalizar o resolver administrativamente el cobro, el usuario puede volver a cerrar sesión.
- Una sesión cancelada de forma segura no se recupera al volver a entrar en Venta.

## Presentación

- El flujo ordinario conserva los botones **Efectivo**, **Tarjeta** y **Pendiente cliente**.
- Las pantallas de recuperación no utilizarán el título ni la descripción **Cobro dividido**.
- El bloque excepcional se denominará **Cobro pendiente** y mostrará únicamente las acciones necesarias para consultar, gestionar, cancelar, finalizar o resolver el cobro.
- No se añadirán controles para iniciar cargos nuevos durante una recuperación incierta.

## Arquitectura

- `SalePaymentCheckout` expondrá al contenedor de Venta el estado mínimo necesario para decidir si el logout es inmediato, cancelable automáticamente o bloqueado.
- `SaleScreen` coordinará la solicitud de cierre con el checkout activo y solo propagará `onLogout` cuando la preparación termine correctamente.
- La cancelación seguirá usando el endpoint existente de sesión de cobro; no se borrarán registros ni estados directamente en el navegador.
- El cierre no almacenará referencias manuales, contraseñas ni datos sensibles.

## Reglas de seguridad

- Nunca se cancela automáticamente una operación incierta o aprobada.
- Nunca se habilita un segundo cargo mientras existe `PENDING` o `TIMEOUT`.
- Un fallo de red durante la cancelación bloquea el logout y conserva la sesión recuperable.
- El token de usuario solo se elimina después de completar una cancelación segura o cuando no existe sesión activa.

## Pruebas

- Logout inmediato sin sesión de cobro.
- Cancelación automática y logout con sesión vacía o únicamente rechazada/error.
- Logout bloqueado para `PENDING`, `TIMEOUT`, `APPROVED`, `COVERED` y `COMPENSATION_REQUIRED`.
- Error de cancelación: usuario permanece conectado y recibe un aviso.
- Reentrada después de cancelación segura: no se muestra la venta anterior.
- DOM sin el texto **Cobro dividido** en el flujo de Venta.
- Suite completa de frontend y compilación de APP VENTA/APP GESTIÓN.

## Fuera de alcance

- Borrar operaciones del proveedor o historiales de auditoría.
- Forzar la cancelación de cobros inciertos o aprobados.
- Modificar la integración física con el datáfono.

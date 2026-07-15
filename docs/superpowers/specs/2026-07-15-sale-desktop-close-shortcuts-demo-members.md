# Diseño: cierre de escritorio, atajos de venta y socios de demostración

## Objetivo

Corregir la pantalla de venta para que el cierre sea coherente con una aplicación de escritorio, los accesos rápidos ejecuten exactamente las mismas acciones que los botones y el perfil `dev` incluya clientes suficientes para validar los descuentos de socio.

## Cierre de la aplicación

- Al confirmar **Sí**, la pantalla de venta ejecutará primero la preparación segura existente: cancelar o resolver el estado transitorio de venta/cobro que pueda descartarse y cerrar la sesión.
- En Electron se invocará el puente `tpvDesktop.closeApplication()` y la aplicación terminará.
- En el navegador usado durante el desarrollo, donde una pestaña abierta manualmente no puede cerrarse de forma fiable, el mismo flujo dejará la aplicación en el login después de limpiar la sesión.
- Si la preparación indica que no es seguro cerrar, la aplicación permanecerá abierta y no perderá información.
- Las confirmaciones repetidas durante la preparación producirán una sola operación.

## Accesos rápidos de venta

- F2: cantidad de la línea seleccionada.
- F5: enfocar búsqueda de producto.
- F6: seleccionar cliente.
- F7: descuento de la línea seleccionada.
- Supr: anular la línea seleccionada.
- F10: abrir cobro en efectivo.
- F11: iniciar cobro con tarjeta.
- F12: registrar como pendiente de cliente.
- Cada tecla llamará al mismo callback que su botón y respetará los mismos estados deshabilitados.
- Los atajos no actuarán cuando haya un diálogo modal abierto, durante una operación de cobro ni ante repetición automática de tecla.
- Se evitarán las acciones predeterminadas del navegador para las teclas gestionadas.

## Datos de demostración

El perfil `dev` sembrará de forma idempotente:

- `CLIENTE BRONCE DEMO`, socio Bronce con 5 %.
- `CLIENTE PLATA DEMO`, socio Plata con 10 %.
- `CLIENTE ORO DEMO`, socio Oro con 15 %.
- El cliente de pruebas sin membresía se conservará para comparar el precio normal.

Las categorías y membresías se guardarán en `member_category` y `miembro`; el frontend continuará leyendo el descuento calculado por el backend.

## Verificación

- Pruebas unitarias de los controles de cierre en Electron y navegador.
- Pruebas de la pantalla de venta para todos los accesos rápidos y sus bloqueos.
- Prueba PostgreSQL del seeder para clientes, categorías, porcentajes y ejecución idempotente.
- Suite del frontend, compilación y pruebas relevantes del backend.

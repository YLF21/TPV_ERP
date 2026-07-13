# Modo de entrada y resultado del cobro en efectivo

## Objetivo

Permitir que cada navegador elija entre una interfaz de cobro táctil y otra optimizada para teclado físico, y mostrar un resumen explícito después de registrar correctamente el pago.

## Alcance

- La preferencia se guarda únicamente en `localStorage`; no se modifica PostgreSQL ni el backend para almacenarla.
- La preferencia pertenece al navegador donde se configura.
- El cambio de modo realizado dentro del diálogo de cobro es temporal y no sustituye la preferencia guardada.
- El backend continúa calculando el total definitivo y registrando el ticket real.

## Configuración

En `Configuración → Terminal` se añadirá una tarjeta denominada **Entrada de cobro**. Contendrá un desplegable con dos valores:

- **Táctil**: muestra el teclado numérico en pantalla.
- **Teclado normal**: prioriza la entrada desde un teclado físico.

El valor predeterminado será **Táctil** para conservar el comportamiento actual. La clave de almacenamiento tendrá un nombre versionado y específico de la aplicación, y cualquier valor ausente o inválido se interpretará como `touch`.

## Diálogo de cobro

Ambos modos mostrarán siempre:

- Total de la compra.
- Dinero recibido.
- Cambio calculado.
- Campo de importe recibido.
- Acciones de cancelar y confirmar.
- Mensajes de importe insuficiente y errores del servidor.

### Modo táctil

Mostrará el teclado numérico en pantalla, los importes rápidos `Exacto`, `5`, `10`, `20` y `50 €`, y un botón **Usar teclado físico**. El campo también aceptará un teclado físico.

### Modo teclado normal

El campo de importe recibirá el foco automáticamente. Se ocultarán el teclado numérico y los importes rápidos para reducir el espacio ocupado. Se mostrará un botón **Mostrar teclado táctil**.

### Atajos

- `Enter` confirma cuando el importe recibido es igual o superior al total y no hay una petición en curso.
- `Escape` cancela cuando no hay una petición en curso.
- Se aceptan coma y punto decimal, normalizándolos al formato interno existente.

El botón de alternancia afecta solamente al diálogo abierto. El próximo cobro volverá a comenzar en el modo guardado en Configuración.

## Confirmación posterior

Cuando el backend registra el ticket correctamente, el diálogo de entrada se sustituye por otro denominado **Pago completado**. Mostrará:

- Número del ticket.
- Total de la compra.
- Dinero recibido.
- Cambio.
- Botón **Finalizar**.

El estado de la venta se limpia solamente después de recibir la confirmación del backend. El diálogo posterior conserva los importes devueltos o validados por el servidor. Al pulsar **Finalizar**, se cierra y el foco vuelve al buscador de productos para comenzar otra venta.

Si el registro falla, no aparece el resumen, permanece abierto el diálogo de entrada y se conservan las líneas, el cliente y el importe introducido.

## Componentes y responsabilidades

- `cashInputMode.ts`: valida, lee y escribe la preferencia en `localStorage` sin depender de React.
- `SettingsScreen`: presenta y actualiza el modo predeterminado.
- `CashPaymentDialog`: gestiona el modo efectivo del diálogo, el teclado táctil y los atajos físicos.
- `CashPaymentResultDialog`: muestra únicamente el resultado confirmado y comunica la acción de finalizar.
- `SaleScreen`: coordina cotización, cobro, errores, limpieza de la venta y transición entre ambos diálogos.

## Pruebas

- Lectura predeterminada, persistencia y recuperación ante valores inválidos de `localStorage`.
- Renderizado del selector en Configuración y actualización de la preferencia.
- Diferencias visibles entre modo táctil y teclado normal.
- Alternancia temporal sin modificar la preferencia guardada.
- Confirmación con `Enter`, cancelación con `Escape` y bloqueo durante el envío.
- Resumen final con total, recibido, cambio y número de ticket.
- Conservación del cobro y de la venta cuando el backend devuelve un error.
- Prueba de regresión de la calculadora existente y compilación del frontend completo.

## Fuera de alcance

- Sincronizar la preferencia entre navegadores o terminales.
- Guardar la preferencia por usuario.
- Cambiar la API o el esquema de PostgreSQL para esta configuración.
- Impresión automática del ticket desde el nuevo diálogo.

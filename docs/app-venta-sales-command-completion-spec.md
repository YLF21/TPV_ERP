# Spec: completar comandos pendientes de SALESCREEN

## Identificador para el traspaso

- **Nombre de la tarea:** `SALES-COMMANDS-COMPLETION`
- **Título:** Completar comandos pendientes de SALESCREEN
- **Ámbito:** APP VENTA, pantalla principal de Ventas y diálogos que se abren desde ella.

## Objetivo

Completar los comandos confirmados para la pantalla de Ventas sin duplicar lógica entre:

- atajos de teclado;
- menús superiores;
- botones del modo táctil.

Las tres entradas deben invocar la misma operación y respetar las mismas validaciones, permisos, auditoría y alertas de control.

## Situación actual

La pantalla ya dispone de menús superiores y de un manejador global de teclado. Los comandos conectados se muestran en los menús y los que todavía no tienen funcionalidad real no deben mostrarse hasta quedar terminados.

### Comandos terminados o que solo necesitan regresión

| Grupo | Comando | Función | Estado |
|---|---|---|---|
| Sistema | `F2` | Calculadora básica y cálculo con/sin impuestos | Implementado |
| Sistema | `F3` | Abrir cajón con permiso o autorización delegada | Implementado |
| Sistema | `F4` | Cerrar sesión; bloqueado cuando la venta no está vacía | Implementado |
| Sistema | `F8` | Cerrar sesión de caja y abrir arqueo | Implementado |
| Factura/Ticket | `Ctrl+F` | Abrir Factura/Albarán | Implementado |
| Factura/Ticket | `F10` | Devolución por ticket, cantidades pendientes y control de S/N | Implementado |
| Documento | `AvPág` | Abrir cobro | Implementado |
| Documento | `Fin` | Abrir lista de clientes | Implementado |
| Documento | `Ctrl+G` | Guardar/apartar venta | Implementado |
| Documento | `Ctrl+/` | Descuento global | Implementado |
| Producto | `F1` | Consulta de precio | Implementado |
| Producto | `F5` | Stock de la línea seleccionada | Implementado |
| Producto | `F6` | Historial de ventas del producto | Implementado |
| Producto | `F7` | Modificar ficha completa del catálogo con autorización | Implementado |
| Producto | `Pausa` | Cambiar cantidad, `0` elimina y solo `-1 + Pausa` permite negativo | Implementado |
| Producto | `Ctrl++` | Sumar cantidad a la línea | Implementado |
| Producto | `Ctrl+-` | Restar cantidad sin permitir resultado negativo | Implementado |
| Producto | `+` | Cantidad de unidades del siguiente producto | Implementado |
| Producto | `*` | Cantidad de paquetes del siguiente producto | Implementado |
| Producto | `RePág` | Convertir precio final deseado en descuento de línea | Implementado |
| Producto | `Ctrl+N` | Número de serie de la línea | Implementado |
| Producto | `/` | Descuento de línea | Implementado |
| Buscadores | `Insert` | Añadir el producto seleccionado al carrito | Implementado |
| Edición | `Ctrl+C/V/X/Z/Y` | Comportamiento nativo de copiar, pegar, cortar, deshacer y rehacer | Implementado; no interceptar |

La ventana de cobro también tiene implementados sus propios atajos (`*`, `+`, `F7`, `F8`, `F9`, `F11`, `F12`, `Ctrl+O`, `Ctrl+N`, `Esc` y `Enter`). Queda fuera de este trabajo salvo pruebas de regresión.

## Trabajo pendiente

### 1. Comandos parcialmente implementados

#### `F11` — anular el último ticket del terminal

Estado actual: abre el diálogo genérico de gestión de tickets.

Debe:

1. Localizar y mostrar directamente el último ticket confirmado del terminal actual.
2. Si el usuario actual tiene `GESTION_VENTAS`, solicitar únicamente su contraseña.
3. Si no tiene el permiso, solicitar usuario y contraseña de una persona autorizada.
4. Exigir motivo de anulación.
5. Ejecutar la anulación mediante el backend; nunca autorizarla solo en frontend.
6. Registrar auditoría y alerta de control con ticket, terminal, operador y autorizador.
7. Actualizar los datos fiscales/VeriFactu y las listas de tickets tras completarse.

#### `Ctrl+F11` — anular otro ticket por código

Estado actual: abre el mismo diálogo genérico.

Debe:

1. Abrir un diálogo enfocado en el código de ticket.
2. Aplicar los mismos controles de autorización, motivo, auditoría y alerta que `F11`.
3. No asumir que el ticket pertenece al terminal actual.
4. Mostrar errores de ticket inexistente, ya anulado o no anulable.

#### `F12` — convertir ticket a factura

Estado actual: abre el diálogo genérico de tickets.

Debe:

1. Abrir el flujo específico de conversión.
2. Precargar el código del último ticket del terminal, permitiendo modificarlo.
3. Mostrar debajo el selector de cliente.
4. Exigir un cliente fiscal válido.
5. Reutilizar el endpoint existente de conversión de ticket a factura.
6. No duplicar stock ni pagos.
7. Actualizar los listados de ticket y factura y refrescar el estado fiscal.

### 2. Comandos no implementados

#### Sistema

| Comando | Función requerida |
|---|---|
| `Ctrl+F2` | Generador de EAN. Antes de implementarlo se debe confirmar el tipo de código a generar y la regla de asignación para evitar duplicados. |
| `Ctrl+I` | Imprimir etiqueta del artículo. Debe reutilizar la configuración de impresora del terminal y un diseño real de etiqueta; no crear un botón sin impresión funcional. |
| `F9` | Retirada de efectivo. Debe reutilizar el endpoint y la lógica de retirada ya existentes, abrir un diálogo operativo desde Ventas e imprimir el justificante correspondiente. |

#### Documento

| Comando | Función requerida |
|---|---|
| `Ctrl+O` | Añadir o editar un comentario interno del carrito. No se imprime en el documento del cliente. |
| `Ctrl+F4` | Vaciar el carrito completo. |
| `Ctrl+Shift+A` | Eliminar todos los artículos. Debe ser un alias de la misma operación de vaciado usada por `Ctrl+F4`. |
| `Ctrl+Shift+D` | Eliminar todos los descuentos manuales de línea y de compra sin retirar promociones automáticas válidas. |
| `Ctrl+P` | Elegir el método de impresión únicamente para la compra actual, sin modificar la configuración permanente del terminal. |
| `Insert` en clientes | Seleccionar el cliente activo en el diálogo de clientes. |

#### Producto

| Comando | Función requerida |
|---|---|
| `Inicio` | Cambiar el nombre de la línea solo para la compra actual, sin modificar el catálogo. |
| `Ctrl+RePág` | Cambiar el precio unitario de la línea solo para la compra actual, con permiso/autorización y alerta de control. |

## Reglas funcionales

### Vaciado del carrito

- `Ctrl+F4` y `Ctrl+Shift+A` deben invocar una única función compartida.
- No se permite vaciar mientras existe un cobro bloqueado o una operación de tarjeta incierta.
- Debe solicitar confirmación si hay líneas.
- Al confirmar, limpia líneas, cliente, cantidades preparadas, comentario interno y descuentos manuales.
- No debe borrar una venta aparcada ni un documento ya confirmado.

### Eliminación de descuentos

- Solo elimina descuentos introducidos manualmente.
- No elimina precios de socio, precios de oferta ni descuentos de oferta calculados por promociones activas.
- Debe recalcular inmediatamente todas las líneas y el total.
- Debe respetar la política de permisos de descuento existente.

### Cambio temporal de nombre

- Se guarda en la instantánea de la línea del documento.
- No modifica el producto del catálogo.
- Debe conservarse al aparcar y recuperar la venta.
- Debe aparecer en el ticket/factura de esa compra.
- Una devolución debe seguir relacionándose con la línea original y no depender del nombre modificado.

### Cambio temporal de precio

- Se aplica a una línea concreta y no al producto del catálogo.
- Debe distinguirse del comando `RePág`, que calcula un descuento para alcanzar un precio final.
- Requiere autorización validada por backend.
- Debe registrar precio anterior, precio nuevo, operador, autorizador, terminal y motivo.
- Debe generar una alerta de control.
- Debe conservarse al aparcar y recuperar la venta.

### Retirada de efectivo

- Reutilizar `POST /api/v1/cash/movements/withdrawal`.
- Respetar la sesión de caja y los límites ya validados por `CashSessionService`.
- Reutilizar el permiso vigente del backend; no crear otro permiso sin revisar el modelo existente.
- Imprimir el justificante mediante el endpoint de recibos de retirada.
- Si la impresión falla después de registrar la retirada, no repetir el movimiento; ofrecer reimpresión.

### Impresión para la compra actual

- `Ctrl+P` cambia una preferencia efímera de la venta actual.
- Al terminar, cancelar o aparcar la venta, se debe conservar o limpiar siguiendo una regla explícita:
  - al aparcar, guardar la selección con la venta;
  - al confirmar o cancelar, volver a la configuración del terminal.
- El ticket siempre se genera; esta opción solo decide su destino o modo de impresión.

## Diseño técnico recomendado

Antes de añadir más casos al `switch` de teclado, crear un registro reutilizable de comandos, por ejemplo:

```text
SaleCommandId
  -> etiqueta y atajo
  -> disponibilidad y motivo de bloqueo
  -> acción
  -> aparición en menú
  -> aparición como botón táctil
```

El teclado, el menú superior y la interfaz táctil deben resolver el mismo `SaleCommandId`. Esto evita que una acción funcione desde el teclado pero no desde el menú, o que tenga reglas distintas según el modo de pantalla.

No se debe cambiar la arquitectura general ni añadir una dependencia externa para esta capa.

## Seguridad, auditoría y alertas

- Toda autorización sensible debe validarse también en backend.
- Usar el permiso real `GESTION_VENTAS`; no crear variantes como `GESTION_VENTA`.
- Reutilizar el mecanismo de autorización delegada ya usado por apertura de cajón y modificación de producto.
- Mantener los eventos de control existentes para descuentos y cantidades negativas.
- Añadir un tipo explícito de alerta para el cambio temporal de precio si no existe uno reutilizable y semánticamente correcto.
- Las contraseñas nunca deben persistirse, registrarse en logs ni incluirse en la alerta.
- La anulación debe preservar la trazabilidad fiscal; nunca borrar físicamente el ticket.

## Orden de implementación recomendado

1. Crear el registro común de comandos y migrar los comandos ya conectados sin cambiar su comportamiento.
2. Implementar `Ctrl+O`, vaciado del carrito, eliminación de descuentos, `Ctrl+P` e `Insert` en clientes.
3. Integrar `F9` reutilizando retirada de efectivo y reimpresión existente.
4. Separar los flujos específicos de `F11`, `Ctrl+F11` y `F12`.
5. Implementar `Inicio` y `Ctrl+RePág` con persistencia de instantánea, backend, permisos y alertas.
6. Diseñar e implementar `Ctrl+F2` y `Ctrl+I` cuando estén confirmadas las reglas de EAN y etiqueta.
7. Añadir los comandos terminados a menús y modo táctil.

## Pruebas obligatorias

### Unitarias

- Resolver cada combinación de teclas al `SaleCommandId` correcto.
- No interceptar `Ctrl+C/V/X/Z/Y`.
- No ejecutar atajos repetidos por `event.repeat`.
- Respetar campos editables y diálogos modales.
- Comprobar disponibilidad y motivo de bloqueo.

### Frontend

- Paridad entre teclado, menú y botón táctil.
- `F11`, `Ctrl+F11` y `F12` abren flujos diferentes con sus valores precargados.
- `Ctrl+F4` y `Ctrl+Shift+A` producen exactamente el mismo resultado.
- `Ctrl+Shift+D` mantiene las promociones automáticas.
- `Inicio` y `Ctrl+RePág` sobreviven a aparcar/recuperar.
- `Insert` selecciona producto y cliente en sus respectivos diálogos.
- Un cobro o resultado de tarjeta incierto bloquea acciones incompatibles.

### Backend

- Permisos y autorización delegada.
- Auditoría y alertas de control.
- Anulación fiscal sin borrado físico.
- Conversión a factura sin duplicar stock ni pagos.
- Cambio temporal de precio sin modificar el catálogo.
- Retirada idempotente ante fallo de impresión.

### Integración/E2E

- Ejecutar cada comando con teclado y con su equivalente visual.
- Verificar funcionamiento en modo teclado y modo táctil.
- Comprobar impresión/reimpresión con el puente de hardware simulado.
- Confirmar que los totales, informes y devoluciones reflejan los cambios temporales.

## Decisiones pendientes antes de programar `Ctrl+F2` y `Ctrl+I`

1. Tipo o tipos de EAN permitidos: EAN-8, EAN-13 u otros.
2. Si el EAN se genera solo para consulta o también se asigna al producto.
3. Secuencia, ámbito de unicidad y dígito de control.
4. Diseño y tamaño de la etiqueta.
5. Datos impresos: nombre, precio, código interno, código de barras, lote u otros.
6. Impresora y número de copias por defecto.

Estas decisiones son bloqueantes: no se deben inventar ni sustituir con datos simulados.

## Definición de terminado

La tarea se considera terminada únicamente cuando:

- todos los comandos confirmados tienen una función real;
- teclado, menú y modo táctil comparten la misma operación;
- los permisos sensibles se validan en backend;
- auditorías y alertas requeridas quedan registradas;
- no hay botones o menús sin lógica;
- no quedan TODO, mocks operativos ni datos simulados;
- pasan las pruebas unitarias, de integración y E2E afectadas;
- el flujo se ha probado con backend real y una sesión de caja válida.

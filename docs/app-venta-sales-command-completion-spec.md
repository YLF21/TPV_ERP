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
| Sistema | `F8` | Cerrar sesión de caja y abrir arqueo | Implementado con operación durable, retirada final única, intentos idempotentes y recuperación tras recarga o pérdida de respuesta |
| Sistema | `F9` | Retirada autorizada de efectivo, desglose configurable e impresión recuperable | Implementado |
| Factura/Ticket | `Ctrl+F` | Abrir Factura/Albarán | Implementado |
| Factura/Ticket | `F10` | Devolución por ticket, cantidades pendientes y control de S/N | Implementado |
| Factura/Ticket | `F11` | Anular el último ticket del terminal con compensación y autorización | Implementado |
| Factura/Ticket | `Ctrl+F11` | Anular otro ticket por código con compensación y autorización | Implementado |
| Factura/Ticket | `F12` | Convertir un ticket a factura, precargando el último del terminal | Implementado |
| Documento | `AvPág` | Abrir cobro | Implementado |
| Documento | `Fin` | Abrir lista de clientes | Implementado |
| Documento | `Ctrl+G` | Guardar/apartar venta | Implementado |
| Documento | `Ctrl+/` | Descuento global | Implementado |
| Documento | `Ctrl+O` | Comentario interno del carrito | Implementado |
| Documento | `Ctrl+F4` | Vaciar completamente la venta actual | Implementado |
| Documento | `Ctrl+Shift+A` | Eliminar únicamente las líneas del carrito | Implementado |
| Documento | `Ctrl+Shift+D` | Eliminar descuentos manuales | Implementado |
| Documento | `Ctrl+P` | Método de impresión para la compra actual | Implementado |
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
| Producto | `Inicio` | Nombre temporal de la línea, sin modificar el catálogo | Implementado |
| Producto | `Ctrl+RePág` | Precio temporal de la línea con autorización configurable | Implementado |
| Producto | `/` | Descuento de línea | Implementado |
| Buscadores | `Insert` | Añadir el producto seleccionado al carrito | Implementado |
| Buscadores | `Insert` | Seleccionar el cliente activo | Implementado |
| Edición | `Ctrl+C/V/X/Z/Y` | Comportamiento nativo de copiar, pegar, cortar, deshacer y rehacer | Implementado; no interceptar |

La ventana de cobro también tiene implementados sus propios atajos (`*`, `+`, `F7`, `F8`, `F9`, `F11`, `F12`, `Ctrl+O`, `Ctrl+N`, `Esc` y `Enter`). Queda fuera de este trabajo salvo pruebas de regresión.

## Trabajo pendiente

### Comandos no implementados

#### Sistema

| Comando | Función requerida |
|---|---|
| `Ctrl+F2` | Implementado: genera y comprueba EAN-8/EAN-13, reserva de forma transaccional y asigna a producto existente o nuevo. |
| `Ctrl+I` | Implementado: imprime o exporta PDF con perfiles locales por terminal para impresora de etiquetas, ticket o A4. |

#### Cierre de caja

| Comando | Función requerida |
|---|---|
| `F8` | Implementado: una única operación durable por sesión conserva la retirada final inmutable. Cada arqueo tiene una clave idempotente independiente y su resultado puede reproducirse sin consumir otro intento. APP VENTA conserva únicamente identificadores y datos no secretos para recuperar el cierre tras recarga, caída o pérdida de respuesta. |

#### Confirmación diferida mediante API genérica

Implementado un manifiesto persistente y no secreto para permitir
`POST /invoices/{id}/confirm` y `POST /delivery-notes/{id}/confirm` sobre
borradores de venta:

- vincula el documento y la tienda con una huella SHA-256 de su contenido
  autorizado;
- conserva las operaciones sensibles autorizadas y la versión de cada política,
  pero nunca usuarios, contraseñas ni otras credenciales;
- un borrador anterior a esta migración, sin manifiesto, se rechaza y debe
  recrearse;
- cualquier cambio del contenido protegido invalida la huella y bloquea la
  confirmación;
- si solo ha cambiado una política, se vuelven a autorizar las operaciones
  almacenadas con credenciales efímeras antes de actualizar el manifiesto;
- pendiente de pago y exceso de crédito se autorizan siempre de nuevo al
  confirmar.

No se reconstruye la intención sensible a partir de nombres o precios ya
persistidos ni se duplica un evento operativo `CREADO`.

## Reglas funcionales

### Vaciado del carrito

- `Ctrl+F4` reinicia la venta completa: líneas, cliente, cantidades preparadas, comentario interno y descuentos manuales.
- `Ctrl+Shift+A` elimina únicamente las líneas y el estado directamente vinculado a ellas; conserva cliente y comentario interno.
- No se permite vaciar mientras existe un cobro bloqueado o una operación de tarjeta incierta.
- Debe solicitar confirmación si hay líneas.
- No debe borrar una venta aparcada ni un documento ya confirmado.

### Eliminación de descuentos

- Solo elimina descuentos introducidos manualmente.
- No elimina precios de miembro, precios de oferta ni descuentos de oferta calculados por promociones activas.
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

### Anulación compensada de tickets

- `F11` carga directamente el último ticket anulable de la terminal actual.
- `Ctrl+F11` permite buscar un ticket por su código, aunque pertenezca a otra terminal de la misma tienda.
- La autorización se valida en backend con `GESTION_VENTAS` o `GESTION_CUENTAS`.
- Un usuario autorizado introduce únicamente su propia contraseña. En autorización delegada se registran operador y autorizador.
- La operación exige motivo y conserva un identificador de solicitud para que los reintentos sean idempotentes.
- Un ticket facturado, ya anulado o con una devolución previa no se puede anular.
- Los pagos con tarjeta integrada se compensan en el datáfono antes de anular el documento. Un resultado incierto mantiene el ticket activo y recuperable.
- Las compensaciones manuales de tarjeta o transferencia exigen una referencia.
- El efectivo devuelto queda registrado como salida en la sesión de caja.
- Los vales consumidos recuperan saldo y los vales generados por el ticket se invalidan. Si un vale generado ya se consumió posteriormente, la anulación se bloquea.
- Los puntos y saldos de miembro se revierten mediante movimientos compensatorios, sin borrar el historial.
- La anulación conserva el ticket, actualiza el estado fiscal/VeriFactu y genera alerta de control.
- La reimpresión del vale restaurado es opcional y un fallo de impresión no repite la anulación.

### Conversión de ticket a factura

- `F12` precarga el último ticket convertible de la terminal y permite sustituir su código.
- El cliente fiscal es obligatorio.
- La conversión bloquea tickets anulados, con anulación en curso o con devoluciones previas.
- Se reutilizan las líneas, pagos y movimientos de stock existentes; no se duplican.

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
- La configuración se resuelve por tienda en APP GESTIÓN. Cada operación permite activar o desactivar por separado la exigencia de permiso y la de contraseña.
- APP GESTIÓN dispone de una acción de restauración que recupera exactamente los valores predeterminados del servidor.
- Mantener los eventos de control existentes para descuentos y cantidades negativas.
- Añadir un tipo explícito de alerta para el cambio temporal de precio si no existe uno reutilizable y semánticamente correcto.
- Las contraseñas nunca deben persistirse, registrarse en logs ni incluirse en la alerta.
- La anulación debe preservar la trazabilidad fiscal; nunca borrar físicamente el ticket.

### Valores predeterminados de seguridad

| Área | Operación | Permisos aceptados | Permiso | Contraseña |
|---|---|---|---|---|
| Caja | Abrir cajón (`F3`) | `ABRIR_CAJON` | Sí | No |
| Caja | Cerrar sesión (`F8`) | `GESTION_VENTAS` o `GESTION_CUENTAS` | No | Sí |
| Caja | Entrada o retirada manual (`F9`) | `GESTION_VENTAS` o `GESTION_CUENTAS` | Sí | Sí |
| Ticket | Devolución por ticket (`F10`) | `GESTION_VENTAS` | No | No |
| Ticket | Anulación (`F11` / `Ctrl+F11`) | `GESTION_VENTAS` o `GESTION_CUENTAS` | Sí | Sí |
| Ticket | Convertir a factura (`F12`) | `GESTION_VENTAS` | No | No |
| Ticket | Devolución manual (`-1 + Pausa`) | `GESTION_VENTAS` | Sí | No |
| Ticket | Eliminar venta aparcada | `GESTION_VENTAS` | No | No |
| Producto | Modificar catálogo (`F7`) | `GESTION_PRODUCTO` | Sí | No |
| Producto | Nombre temporal (`Inicio`) | `GESTION_VENTAS` | No | No |
| Producto | Precio temporal (`Ctrl+RePág`) | `CAMBIAR_PRECIO` o `GESTION_VENTAS` | Sí | Sí |
| Producto | Asignar precio a producto de precio cero | `CAMBIAR_PRECIO` o `GESTION_VENTAS` | No | No |
| Descuentos | Descuento de línea/global (`/`, `RePág`, `Ctrl+/`) | `APLICAR_DESCUENTO` | Sí | No |
| Descuentos | Descuento directo en cobro | `APLICAR_DESCUENTO` | Sí | No |
| Crédito | Confirmar como pendiente | `CUSTOMER_RECEIVABLES_CREATE` | Sí | No |
| Crédito | Superar límite | `CUSTOMER_CREDIT_OVERRIDE` | Sí | No |
| Pago | Tarjeta manual | `GESTION_VENTAS` o `GESTION_CUENTAS` | Sí | No |
| Pago | Transferencia manual | `GESTION_VENTAS` o `GESTION_CUENTAS` | Sí | No |
| Datáfono | Anulación directa | `PAYMENT_TERMINAL_VOID` | Sí | Sí |
| Datáfono | Devolución directa | `PAYMENT_TERMINAL_REFUND` | Sí | Sí |
| Datáfono | Confirmar compensación manual | `PAYMENT_TERMINAL_REFUND` | Sí | Sí |

Cuando la política exige permiso y el operador no lo tiene, la autorización
delegada registra por separado operador y autorizador. Si la política solo
exige contraseña, se reautentica al operador actual y no se permite delegación.
Los endpoints exclusivos de edición administrativa quedan fuera de esta matriz;
las rutas operativas compartidas no quedan exentas por el mero hecho de que el
usuario sea administrador.

### Defensa en profundidad de las APIs

- La autorización no puede depender del atajo o del diálogo de APP VENTA.
- Los endpoints POS, las APIs genéricas de facturas/albaranes/tickets, los
  pagos de cuentas a cobrar y el aparcado directo deben ejecutar el mismo guard
  en backend inmediatamente antes de mutar.
- Las credenciales son datos efímeros de entrada: no forman parte del comando
  documental, instantáneas, hashes de idempotencia, ventas aparcadas ni auditoría.
- Una tarjeta declarada como integrada debe estar vinculada a una operación
  persistida y validada del datáfono. Las APIs heredadas que no puedan demostrar
  esa procedencia deben rechazarla.
- Los endpoints exclusivos `PUT .../admin` mantienen su política administrativa
  propia y no se someten a esta matriz operativa.
- La confirmación especializada de una factura rectificativa comprueba primero
  sus metadatos y su tipo y mantiene comprobación, confirmación y lectura final
  dentro de una sola transacción. Un borrador de venta normal nunca puede
  confirmarse utilizando la ruta de rectificativas.
- Al aparcar una venta se conservan los indicadores de nombre y precio temporal.
  Los snapshots antiguos que no los incluyan se interpretan como `false`; un
  valor presente con tipo inválido se rechaza en vez de degradarse silenciosamente.
- La confirmación diferida genérica utiliza un manifiesto no secreto ligado a
  una huella SHA-256 y a las versiones de política. La ausencia del manifiesto o
  una huella distinta se bloquean de forma cerrada con errores públicos
  diferenciados.
- Si cambia la versión de una política sin cambiar el documento, se exige una
  nueva autorización de las operaciones sensibles almacenadas. Las credenciales
  solo existen durante esa petición y no se incorporan al manifiesto.
- La creación y confirmación en una sola transacción autoriza líneas, pendiente
  y exceso de crédito antes de confirmar, sin crear un manifiesto intermedio.

### Límite de intentos de autorización

- El contador se persiste por tienda, operador, terminal y operación; nunca por
  el usuario delegado, para evitar bloquear globalmente a un autorizador mediante
  ataques de denegación de servicio.
- Se auditan los fallos y las solicitudes bloqueadas sin registrar contraseñas.
- Un acierto elimina inmediatamente el estado de fallos.
- El contador se reinicia tras 30 minutos sin fallos. Los fallos 1 y 2 no
  añaden espera y, desde el tercero, las esperas son 5, 15, 30, 60, 120, 300,
  600 y un máximo de 900 segundos.
- Antes de comprobar una contraseña se adquiere una reserva persistente de 30
  segundos. El bloqueo pesimista en PostgreSQL garantiza que dos instancias no
  puedan comprobar simultáneamente credenciales para el mismo ámbito; una
  reserva caducada nunca puede completar la operación.
- Los errores públicos son estables: `SALE_OPERATION_AUTHORIZATION_DENIED`
  (`403`) y `SALE_OPERATION_AUTHORIZATION_THROTTLED` (`429`).

## Orden de implementación recomendado

1. Crear el registro común de comandos y migrar los comandos ya conectados sin cambiar su comportamiento.
2. Implementado: `Ctrl+O`, vaciado del carrito, eliminación de descuentos, `Ctrl+P` e `Insert` en clientes.
3. Implementado: `F9` reutilizando retirada de efectivo y reimpresión existente.
4. Implementado: flujos específicos de `F11`, `Ctrl+F11` y `F12`, con compensación durable.
5. Implementado: `Inicio` y `Ctrl+RePág` con persistencia de instantánea y autorización backend.
6. Implementado: manifiesto no secreto para confirmación diferida de borradores de venta.
7. Implementado: idempotencia durable completa de `F8`.
8. Implementado: `Ctrl+F2` y `Ctrl+I`, incluida configuración administrativa y perfiles físicos por terminal.
9. Implementado: comandos terminados añadidos a menús y modo táctil usando el registro común.

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
- `Ctrl+F4` reinicia toda la venta y `Ctrl+Shift+A` conserva los datos de cabecera no vinculados a las líneas.
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
- Reserva de autorización concurrente: una única concesión por ámbito y caducidad estricta a los 30 segundos.
- Manifiesto documental: ausencia, alteración de huella y cambio de versión de política.

### Integración/E2E

- Ejecutar cada comando con teclado y con su equivalente visual.
- Verificar funcionamiento en modo teclado y modo táctil.
- Comprobar impresión/reimpresión con el puente de hardware simulado.
- Confirmar que los totales, informes y devoluciones reflejan los cambios temporales.

## Generador EAN y etiqueta (`Ctrl+F2` y `Ctrl+I`)

### Decisiones confirmadas

- Admitir EAN-8 y EAN-13.
- Calcular y comprobar el dígito de control; también informar si un código introducido es válido.
- Para numeración exclusivamente interna usar el rango GS1 de circulación restringida `200`–`299`; estos códigos no se deben presentar como GTIN globalmente únicos.
- Permitir un código de empresa además del código propio de tienda.
- En un producto existente, asignar el código generado como `código de barras 2`.
- En un producto nuevo, asignarlo como `código de barras 1`.
- La etiqueta debe poder generarse también como PDF.

Referencia normativa técnica: [GS1 Company Prefixes y Restricted Circulation
Numbers](https://www.gs1.org/standards/id-keys/company-prefix) y
[GS1 General Specifications](https://www.gs1.org/docs/barcodes/GS1_General_Specifications.pdf).

### Diseño implementado

- EAN-13 predeterminado con selector EAN-8.
- EAN-13: `2CC + TTT + NNNNNN + D`; EAN-8: `2 + TTT + NNN + D`.
- `CC` se configura por empresa en APP GESTIÓN y `TTT` procede del código de tienda.
- La reserva dura 15 minutos y queda ligada a tienda, operador y terminal. Una reserva
  caducada puede reutilizarse; un código asignado permanece registrado y no se reutiliza.
- Producto existente: asignación como código de barras 2, con confirmación explícita
  si reemplaza el valor actual. Producto nuevo: código de barras 1 mediante la ficha completa.
- La comprobación del dígito de control no requiere permiso. Generar, reservar y asignar
  usa la política configurable `GENERATE_PRODUCT_EAN`, cuyo valor predeterminado exige
  `GESTION_PRODUCTO` y permite autorización delegada sin pedir contraseña al usuario que ya lo posee.
- `Ctrl+I` abre el buscador cuando no hay línea seleccionada y utiliza nombre, código,
  EAN y precio de catálogo. Los perfiles locales permiten impresora de etiquetas,
  impresora de tickets o A4, medidas, orientación, márgenes, separaciones, copias y
  nombre de tienda. En A4 se elige la primera etiqueta disponible. PDF está siempre disponible.

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

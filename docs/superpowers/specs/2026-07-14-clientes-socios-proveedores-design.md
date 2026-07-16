# Clientes, socios y proveedores compartidos

## Objetivo

Completar tres maestros separados —`Clientes`, `Socios` y `Proveedores`— dentro del espacio operativo de Stock, compartidos por APP VENTA y APP GESTIÓN, conectados al backend real y con el mismo lenguaje visual business-classic del ERP.

Los tres apartados deben permitir consulta, alta, edición, activación y desactivación. No se expondrá eliminación en la interfaz. La pantalla de socios concentrará además toda la gestión de fidelización por empresa.

## Decisiones cerradas

- Los apartados `Clientes`, `Socios` y `Proveedores` son independientes.
- Un socio es un cliente con una relación 1:1 opcional de fidelización (`miembro`).
- Cliente conserva identidad, datos personales, fiscales y de contacto.
- Miembro conserva código, fecha de alta, categoría, puntos, saldo, estado e historial de fidelización.
- Proveedor mostrará únicamente datos fiscales y de contacto.
- Clientes, socios y proveedores se conservan mediante activación/desactivación; no se eliminan desde frontend.
- Categorías, reglas, canales y configuración de fidelización estarán dentro de `Socios`.
- El dominio y la API mantienen la terminología técnica `member`; la interfaz española utiliza `Socio`.
- Las dos aplicaciones consumen los mismos componentes de `frontend/packages/app-common`.

## Alcance

Incluye:

- Listados separados con búsqueda, filtros, columnas y estado.
- Alta, consulta y edición de cliente, socio y proveedor.
- Activación y desactivación conservando historial.
- Ficha fiscal y de contacto de cliente y proveedor.
- Ficha de fidelización completa del socio.
- Categorías y configuración de fidelización por empresa.
- Permisos de lectura y escritura.
- Selección de cliente/proveedor desde Venta y documentos de almacén.
- Internacionalización ES/EN/ZH.
- Pruebas frontend, backend y validación integrada en ambas aplicaciones.

No incluye:

- Eliminación física desde la interfaz.
- Cuenta corriente, límite de crédito o deuda independiente del cliente.
- Productos, precios, compras, últimas entradas o representantes dentro de la ficha general del proveedor.
- Campañas comerciales masivas.
- Proveedor real de email o WhatsApp.
- Rediseño general del resto de Stock.

## Navegación y UI compartida

Dentro de la navegación lateral de Stock aparecerá una sección `Clientes y proveedores` con tres entradas:

1. `Clientes`.
2. `Socios`.
3. `Proveedores`.

Cada apartado utilizará:

- panel blanco formal sobre superficie gris/azulada;
- tabla densa con cabecera azul marino;
- radios de 4 a 6 px;
- barra compacta de búsqueda, filtros y resultado;
- botones planos equivalentes a los de Stock;
- ventana de alta/edición basada en la estructura visual de `ProductCreateDialog`;
- navegación por teclado, foco inicial y cierre seguro;
- mensajes de carga, vacío, error y guardado dentro del panel.

Los tres directorios tendrán una barra simplificada: un único buscador general, un filtro de estado (`Todos`, `Activos`, `Desactivados`) y el contador de resultados. El buscador también cubrirá población/provincia en clientes y proveedores, y códigos, número y categoría en socios. Los filtros específicos por documento, ubicación o consentimiento no se mostrarán en la barra principal.

APP VENTA y APP GESTIÓN mostrarán exactamente los mismos componentes. Las acciones visibles dependerán de los permisos del usuario.

## Cliente

### Listado

Columnas iniciales:

- `clientId`.
- Nombre o razón social.
- Tipo y número de documento.
- Teléfono.
- Email.
- Población y provincia.
- Estado.

Filtros:

- búsqueda libre por código, nombre, documento, teléfono, email, población o provincia;
- activo/desactivado.

### Ficha

Campos editables:

- nombre o razón social;
- tipo y número de documento;
- dirección;
- código postal;
- población;
- provincia;
- país ISO de dos caracteres;
- teléfono;
- email;
- cumpleaños;
- género: `MASCULINO`, `FEMENINO`, `OTRO`;
- descuento individual entre 0 y 100;
- consentimiento comercial;
- canal comercial preferido;
- notas.

Campos informativos no editables:

- UUID técnico;
- `clientId` automático e inmutable;
- estado;
- indicador de datos fiscales completos;
- existencia y estado de la relación de socio.

### Reglas

- El documento será único por empresa y tipo.
- El código se genera automáticamente con formato `C-{tienda}-{secuencia}`.
- El código no se acepta en peticiones de alta o edición.
- El alta de cliente no puede crear simultáneamente la relación de socio; debe persistirse primero el cliente y después activar su fidelización.
- Consentimiento comercial activo requiere un canal preferido activo.
- Desactivar conserva documentos e historial.
- Reactivar reutiliza el mismo registro y código.
- La interfaz no ofrecerá eliminación.

## Socio

### Relación con cliente

- Un cliente puede no ser socio.
- Un cliente puede tener como máximo una relación de miembro.
- Un socio solo puede crearse a partir de un cliente activo ya existente.
- `Nuevo socio` abre un selector de clientes; nunca una ficha personal vacía.
- Si el cliente tuvo anteriormente una relación de socio desactivada, se reactiva conservando código, fecha e historial.
- Un cliente desactivado debe reactivarse antes de poder activar su relación de socio.
- Desactivar socio no desactiva automáticamente al cliente.
- Desactivar cliente impide su uso operativo, aunque su historial de socio se conserva.

### Listado

Columnas iniciales:

- `memberId`.
- Número de socio manual.
- `clientId`.
- Nombre del cliente.
- Documento.
- Categoría.
- Puntos.
- Saldo.
- Fecha de alta.
- Estado.

Filtros:

- búsqueda libre por código de socio, número manual, código o nombre de cliente, documento, teléfono, email o categoría;
- activo/desactivado según el estado propio de la relación de socio.

### Ficha

Bloque de identidad, en modo consulta o acceso directo a la ficha del cliente:

- nombre;
- documento;
- teléfono;
- email.

Bloque de fidelización:

- `memberId` automático e inmutable;
- número de socio manual y opcional;
- fecha original de alta inmutable;
- categoría actual;
- descuento efectivo de categoría;
- puntos acumulados;
- saldo disponible;
- estado activado/desactivado;
- bloqueo de categoría automática;
- historial de movimientos.

Acciones:

- activar socio;
- desactivar socio;
- ajustar puntos con motivo obligatorio;
- ajustar saldo con motivo obligatorio;
- cambiar categoría con motivo y opción de bloquear/desbloquear cambio automático.

### Reglas de ciclo de vida

- Primera activación genera `memberId`, fecha de alta y categoría inicial.
- `memberId` usa `M-{tienda}-{secuencia}` y nunca cambia.
- El número de socio manual es opcional y único por empresa cuando se informa.
- Desactivar conserva código, fecha, categoría, puntos, saldo e historial.
- Un socio desactivado no acumula ni gasta beneficios.
- Reactivar reutiliza el código y la fecha originales.
- No existe eliminación desde la interfaz.

### Puntos, saldo y movimientos

- Los puntos no caducan.
- El saldo se controla por lotes y puede caducar según configuración.
- Los ajustes crean movimientos inmutables con usuario, fecha y motivo.
- El saldo oficial compartido entre tiendas continúa siendo autoridad del SaaS.
- El gasto de saldo respeta las reglas de sincronización ya definidas.

### Categorías dentro de Socios

Cada empresa gestiona sus propias categorías:

- nombre;
- puntos mínimos;
- porcentaje de descuento;
- descuento activado/desactivado;
- orden;
- estado;
- cambio automático o bloqueo manual por socio.

Al desactivar una categoría con socios activos:

- se trasladan a la categoría inferior activa disponible;
- si no existe una categoría inferior, se bloquea la desactivación.

### Configuración dentro de Socios

- porcentaje de saldo generado;
- política de caducidad: no caduca, 1 mes, 3 meses, 6 meses o 1 año;
- puntos por euro;
- categorías automáticas activadas/desactivadas;
- bienvenida de socio activada/desactivada;
- formato de tarjeta `QR` o `BARCODE`;
- asunto y cuerpo de bienvenida;
- canales comerciales por empresa;
- consulta y reintento de entregas de tarjeta pendientes o fallidas.

La tarjeta virtual se genera únicamente al activar socio, codifica `memberId` y requiere email válido cuando el envío esté habilitado.

## Proveedor

### Listado

Columnas iniciales:

- `supplierId`.
- Razón social.
- Nombre comercial.
- Tipo y número de documento.
- Teléfono.
- Email.
- Población y provincia.
- Estado.

Filtros:

- búsqueda libre por código, razón social, nombre comercial, documento, teléfono o email;
- activo/desactivado;
- tipo de documento;
- población o provincia.

### Ficha

Campos editables:

- razón social;
- nombre comercial;
- tipo y número de documento;
- dirección;
- código postal;
- población;
- provincia;
- país ISO de dos caracteres;
- teléfono;
- email;
- notas.

Campos informativos:

- UUID técnico;
- `supplierId` automático e inmutable;
- estado.

### Reglas

- Documento único por empresa y tipo.
- Código automático `S-{secuencia}` por empresa.
- Desactivar conserva historial y relaciones existentes.
- Un proveedor desactivado no puede seleccionarse en nuevas compras, entradas o vinculaciones.
- Reactivar reutiliza el mismo registro y código.
- No existe eliminación desde la interfaz.
- La ficha no mostrará productos, precios, entradas ni representantes.

## Integraciones

### Venta

- El selector de cliente usa clientes activos.
- Un socio activo expone categoría y descuento efectivo.
- La venta aplica las reglas existentes de precio/beneficios de socio.
- Un cliente o socio desactivado no puede seleccionarse en una nueva venta.

### Almacén

- `Salida almacén` selecciona cliente o destino activo.
- `Entrada almacén` selecciona proveedor u origen activo.
- Los documentos históricos conservan las referencias aunque la parte se desactive posteriormente.

### Producto-proveedor

Las relaciones continúan gestionándose desde el producto y compras confirmadas. Este maestro solo proporciona el proveedor activo seleccionable y sus datos fiscales/contacto.

## API

### Cliente

Se reutilizan:

- `GET /api/v1/customers`.
- `GET /api/v1/customers/{id}`.
- `POST /api/v1/customers`.
- `PUT /api/v1/customers/{id}`.
- `PATCH /api/v1/customers/{id}/deactivate`.
- `POST /api/v1/customers/{id}/member/activate`.
- `POST /api/v1/customers/{id}/member/deactivate`.
- `POST /api/v1/customers/{id}/validate-fiscal`.

Se añadirá:

- `PATCH /api/v1/customers/{id}/activate`.

El endpoint `DELETE` existente no se utilizará desde estas pantallas.

### Socio

Se reutilizan:

- `GET /api/v1/members` para el directorio separado, incluyendo estado del socio y estado del cliente.
- `GET /api/v1/members/{id}`.
- `GET /api/v1/members/{id}/movements`.
- `POST /api/v1/members/{id}/balance-adjustments`.
- `POST /api/v1/members/{id}/points-adjustments`.
- `PUT /api/v1/members/{id}/category`.
- endpoints de categorías, settings, canales y entregas de tarjeta ya existentes.

El listado separado se obtiene desde `GET /api/v1/members`. La respuesta contiene los datos propios de fidelización y solo un resumen de identidad/contacto del cliente, sin duplicarlos en el modelo de miembro. Este contrato permitirá incorporar búsqueda y paginación en backend cuando el volumen lo requiera.

### Proveedor

Se reutilizan:

- `GET /api/v1/suppliers`.
- `GET /api/v1/suppliers/{id}`.
- `POST /api/v1/suppliers`.
- `PUT /api/v1/suppliers/{id}`.
- `PATCH /api/v1/suppliers/{id}/deactivate`.

Se añadirá:

- `PATCH /api/v1/suppliers/{id}/activate`.

El endpoint `DELETE` existente no se utilizará desde estas pantallas.

## Permisos

- Cliente y socio lectura: `CUSTOMERS_READ` o `ADMIN`.
- Cliente y socio modificación/activación: `CUSTOMERS_WRITE` o `ADMIN`.
- Ajustes de saldo/puntos: `CUSTOMERS_WRITE` o `ADMIN`.
- Cambio manual de categoría: `ADMIN` según contrato actual.
- Proveedor lectura: `SUPPLIERS_READ` o `ADMIN`.
- Proveedor modificación/activación: `SUPPLIERS_WRITE` o `ADMIN`.

Sin permiso de lectura se oculta el apartado. Sin permiso de escritura se muestra en modo consulta y se ocultan acciones mutables.

## Validación y errores

- Normalizar documentos, códigos opcionales, emails y país antes de guardar.
- Rechazar duplicados con mensaje específico.
- Validar porcentajes entre 0 y 100.
- Exigir motivo en ajustes y cambios manuales.
- Impedir doble envío deshabilitando acciones durante una petición.
- Mostrar errores del backend dentro de la ventana o ficha activa.
- Recuperar y mantener el formulario si falla el guardado.
- Pedir confirmación antes de activar o desactivar.
- Mantener selección y búsqueda después de refrescar el listado.

## Pruebas

Backend:

- contratos de activación/reactivación;
- aislamiento por empresa;
- códigos inmutables;
- documentos y número de socio únicos;
- consentimiento y canal preferido;
- desactivación/reactivación conservando historial;
- categoría, puntos, saldo y movimientos;
- proveedor desactivado no seleccionable para nuevas operaciones;
- permisos de lectura y escritura.

Frontend:

- navegación separada en ambas aplicaciones;
- carga, búsqueda, filtros y estados vacíos;
- alta y edición de los tres tipos;
- modo solo lectura por permisos;
- activación/desactivación con confirmación;
- ficha y configuración de socio;
- validaciones y errores de API;
- navegación por teclado y foco del diálogo;
- integración con selectores de Venta y Almacén.

## Criterios de aceptación

- APP VENTA y APP GESTIÓN muestran los tres apartados separados con el mismo UI.
- Los tres listados muestran un buscador general, filtro de estado y contador, sin filtros redundantes en la barra principal.
- `Nuevo socio` obliga a seleccionar un cliente activo existente y nunca crea datos personales desde cero.
- Un usuario autorizado puede crear, consultar, editar, activar y desactivar clientes, socios y proveedores.
- Ninguna pantalla ofrece eliminación.
- Reactivar conserva códigos e historial.
- Socios permite gestionar fidelización y su configuración por empresa.
- Proveedor muestra solo datos fiscales y contacto.
- Venta y almacén solo permiten seleccionar registros activos.
- Todas las acciones persisten en backend y sobreviven a un refresco.
- Las pruebas frontend y backend pasan y ambas aplicaciones compilan.

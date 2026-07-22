# Integracion De VERI*FACTU En APP GESTION Y APP VENTA

## Objetivo

Integrar el dominio VERI*FACTU existente en las dos aplicaciones sin duplicar
responsabilidades ni exponer operaciones fiscales criticas en los terminales de
venta.

La decision aprobada es:

- APP GESTION contiene el modulo administrativo completo de VERI*FACTU.
- APP VENTA muestra, dentro de la pantalla Venta, el estado operativo y la cola
  de la terminal autenticada en modo de solo lectura.
- El backend sigue siendo la unica fuente de verdad para registro fiscal,
  encadenamiento, envio, estados, permisos y ambito organizativo.

Esta especificacion complementa, sin sustituir, los diseños fiscales existentes:

- `2026-06-14-verifactu-design.md`.
- `2026-06-21-verifactu-subsanaciones-design.md`.
- `2026-06-23-verifactu-certificate-management-design.md`.

No se considerara que el producto cumple la normativa solamente por completar
estas pantallas. La conformidad requiere validar cada version fiscal contra la
normativa, esquemas y servicios oficiales de AEAT vigentes, ejecutar pruebas en
el entorno oficial y preparar la declaracion responsable correspondiente.

## Situacion De Partida

El backend ya dispone de:

- Registros fiscales inmutables, cadena y huella.
- Integracion fiscal con tickets y facturas de venta.
- QR fiscal como URL asociada al documento confirmado.
- XML, XSD, SOAP, mTLS, cola, reintentos y respuestas AEAT.
- Estados de envio e historial de intentos.
- Registros defectuosos y subsanaciones administrativas.
- Activacion voluntaria y legal.
- Estado del reloj fiscal.
- Importacion y custodia de certificados mediante DPAPI.

Las carencias que resuelve este diseño son:

- No existe modulo VERI*FACTU en APP GESTION.
- APP VENTA no muestra estado ni cola de su terminal.
- Los contratos actuales de cola no filtran por terminal.
- Algunos endpoints administrativos usan `GESTION_VENTAS`, un permiso demasiado
  amplio para reintentos y subsanaciones fiscales.
- La cola administrativa actual no esta paginada.
- El frontend de impresion no consume el QR fiscal ni imprime la leyenda
  exigida.

## Limites Entre Aplicaciones

### APP VENTA

APP VENTA solo puede:

- Consultar el estado operativo resumido.
- Consultar la cola atribuida a la terminal autenticada.
- Actualizar la consulta de forma automatica o manual.
- Imprimir el QR y la leyenda fiscal del documento confirmado.
- Reimprimir exactamente la misma representacion fiscal.

APP VENTA no puede:

- Activar ni desactivar VERI*FACTU.
- Importar, sustituir o eliminar certificados.
- Forzar reintentos.
- Crear subsanaciones.
- Consultar XML, respuestas completas o trazas tecnicas de AEAT.
- Consultar la cola de otras terminales o de otras tiendas.
- Modificar ningun dato fiscal confirmado.

### APP GESTION

APP GESTION contiene:

- Resumen fiscal y estado de activacion.
- Cola completa de la tienda activa.
- Historial de intentos y respuestas tecnicas autorizadas.
- Registros defectuosos y subsanaciones.
- Certificado y avisos de vigencia.
- Reloj fiscal y diagnostico.
- Activacion y acciones administrativas permitidas.

La consulta multitienda pertenece al backend SaaS futuro. La APP GESTION local
no aceptara un `tiendaId` arbitrario enviado por el navegador.

## Permisos

Se incorporaran permisos fiscales separados de los permisos comerciales:

| Permiso | Alcance |
| --- | --- |
| `VERIFACTU_READ` | Abrir el modulo en APP GESTION y consultar estado, cola, defectuosos e intentos de la tienda activa |
| `VERIFACTU_CORRECT` | Crear subsanaciones administrativas sobre registros autorizados |
| `VERIFACTU_MANAGE` | Consultar payload tecnico autorizado, forzar reintentos scoped y ejecutar diagnosticos operativos no destructivos |
| `ADMIN` | Activar o desactivar cuando legalmente se permita y gestionar certificados |

Reglas adicionales:

- Abrir APP GESTION exige `APP_GESTION_ACCESS`, salvo el acceso implicito de
  `ADMIN`.
- Los endpoints de lectura administrativa exigen `APP_GESTION_ACCESS` y
  `VERIFACTU_READ`, salvo `ADMIN`.
- `VERIFACTU_CORRECT` y `VERIFACTU_MANAGE` no conceden por si solos acceso a la
  aplicacion ni lectura del modulo; se asignaran junto a `VERIFACTU_READ` en los
  roles que deban utilizarlos.
- Un usuario `VENTA` puede consultar el resumen y cola de su terminal desde APP
  VENTA sin adquirir acceso al modulo administrativo.
- El frontend nunca se considera una barrera de seguridad.

Los endpoints existentes protegidos solamente con `GESTION_VENTAS` se
endureceran de acuerdo con esta matriz. `GESTION_VENTAS` continuara controlando
la consulta comercial del ticket o factura, pero no concedera por si solo
reintentos ni subsanaciones fiscales.

## Politica Global De Activacion Desde SaaS

La fecha de activacion automatica no quedara fijada de forma inmutable en cada
backend local. El backend SaaS mantiene una politica global versionada por tipo
de contribuyente y la distribuye en el enlace y en cada validacion periodica de
la licencia:

| Tipo | Fecha inicial configurada |
| --- | --- |
| `SOCIEDAD` | 2027-01-01 |
| `AUTONOMO` | 2027-07-01 |

Estas fechas siguen los plazos de adaptacion vigentes publicados para los
sistemas informaticos de facturacion. El producto utiliza esa politica como
fecha de entrada automatica en modo VERI*FACTU. Si la AEAT cambia de nuevo los
plazos, un administrador SaaS podra actualizar cada bloque una sola vez y el
cambio llegara a todas las licencias activas en su siguiente validacion.

Reglas aprobadas:

- Modificar la politica exige `MANAGE_FISCAL_POLICY`; `ADMIN` lo recibe de
  forma explicita.
- Cada cambio exige fecha, motivo, actor y confirmacion, incrementa una version
  monotona y queda auditado con fecha anterior y nueva.
- El panel SaaS muestra por tipo las licencias activas y las instalaciones
  vinculadas afectadas.
- La licencia local persiste fecha, version y fecha de actualizacion; no depende
  de conectividad continua para decidir la activacion.
- Una version anterior se ignora y una misma version con contenido diferente se
  rechaza.
- Una fecha posterior nunca desactiva una instalacion cuya politica anterior ya
  entro en vigor o que ya realizo su primera remision.
- Las licencias antiguas que todavia no hayan recibido politica usan las fechas
  de fallback incluidas en el backend, exclusivamente como compatibilidad de
  transicion.
- La imposibilidad temporal de validar contra SaaS no bloquea la operacion local
  ni revierte una activacion ya alcanzada.

Fuentes normativas de referencia:

- Reglamento consolidado: <https://www.boe.es/buscar/act.php?id=BOE-A-2023-24840>.
- Nota AEAT sobre ampliacion de plazos: <https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/nota-informativa-ampliacion-plazo-adaptacion-facturacion.html>.

## APP VENTA: Integracion En La Pantalla Venta

### Ubicacion

No se creara una entrada principal ni una pantalla independiente en el inicio de
APP VENTA. La cabecera de `SaleScreen` mostrara un indicador compacto y
pulsable con el texto `VERI*FACTU` y su estado.

Al pulsarlo se abrira un panel lateral dentro de Venta. El panel no sustituira
la venta activa, no borrara el carrito y podra cerrarse con boton, `Escape` o
clic fuera sin perder datos.

### Estados De Presentacion

El backend calculara un estado de presentacion estable; el frontend no inferira
reglas fiscales a partir de mensajes de error:

- `INACTIVO`: VERI*FACTU todavia no esta activo.
- `OPERATIVO`: activo, sin elementos que requieran atencion en la terminal.
- `PENDIENTES`: existen registros pendientes o enviados a la espera de
  resultado.
- `ENVIANDO`: existe un envio en curso.
- `REQUIERE_REVISION`: existe un rechazo o incidencia que debe resolver APP
  GESTION.
- `DESCONOCIDO`: no se pudo obtener el resumen desde el backend local.

Una interrupcion de Internet o de AEAT no bloqueara la venta si el documento y
su registro fiscal quedaron confirmados localmente. Un fallo dentro de la
transaccion fiscal local si impedira confirmar el documento y mostrara el error
operativo correspondiente.

No se mostraran al cajero el sujeto del certificado, su huella, XML, payload de
respuesta, NIF de otros clientes ni mensajes tecnicos internos.

### Contenido Del Panel

El panel mostrara un resumen y una tabla compacta con un maximo inicial de 50
elementos:

- Numero de ticket o factura.
- Tipo de documento.
- Fecha y hora de actualizacion.
- Estado de envio.
- Mensaje operativo sanitizado cuando requiera revision.

El panel no tendra botones de reintento, subsanacion, configuracion ni acceso a
la venta de otro usuario. Podra incluir `Actualizar`, que solo repetira la
consulta de lectura.

La primera consulta se ejecutara al abrir Venta. Se actualizara despues de
confirmar una venta, al abrir el panel y periodicamente mientras la pantalla
este visible. El sondeo se pausara cuando la ventana quede en segundo plano para
evitar llamadas innecesarias.

## APP GESTION: Modulo VERI*FACTU

### Navegacion

VERI*FACTU sera una entrada principal de la barra lateral, visible con
`VERIFACTU_READ` o `ADMIN`. Mantendra la barra lateral persistente y abrira el
contenido dentro de `GestionShell`.

El modulo tendra las siguientes vistas internas:

1. `Resumen`.
2. `Cola de envios`.
3. `Registros defectuosos`.
4. `Certificado`.
5. `Diagnostico`.

No se mostraran pestañas, tarjetas ni acciones si no existe el contrato real
que las respalda.

### Resumen

Mostrara:

- Activo o inactivo y motivo de activacion.
- Entorno configurado.
- Trabajador de envio activo o detenido.
- Contadores por estado de la tienda.
- Antiguedad del elemento pendiente mas antiguo.
- Estado resumido del certificado.
- Estado y desviacion del reloj fiscal.
- Fecha de primera remision, cuando exista.

Activar o desactivar sera visible solo para `ADMIN`. Antes de confirmar se
mostraran las consecuencias y el backend volvera a comprobar que la operacion
sea legalmente reversible. La UI nunca calculara esa reversibilidad.

### Cola De Envios

Sera una tabla paginada y filtrable por:

- Rango de fechas.
- Estado.
- Tipo de documento.
- Operacion fiscal.
- Numero de documento.

Columnas iniciales:

- Secuencia.
- Documento.
- Operacion.
- Estado.
- Ultima actualizacion.
- Codigo de error.

El doble clic o la accion `Ver detalle` abrira el historial de intentos. El
resumen de errores sera visible con `VERIFACTU_READ`; el payload tecnico
completo exigira ademas `VERIFACTU_MANAGE` y se representara como texto
escapado, nunca como HTML.

`VERIFACTU_MANAGE` podra reintentar un registro elegible. El backend bloqueara
el registro y devolvera conflicto si ya esta enviandose o no admite reintento.
La accion sera auditada y no permitira alterar XML ni datos fiscales.

Los registros `RECHAZADO` no se reintentaran automaticamente ni se reenviaran
sin cambios. Un alta rechazada requerira subsanacion explicita. Los reintentos
manuales quedaran limitados a resultados de comunicacion pendientes
(`ENVIADO`) y defectos tecnicos expresamente clasificados como reintentables,
con version esperada, bloqueo de fila, alcance de tienda y motivo auditado.
Actualmente `INVALID_AEAT_RESPONSE` es reintentable; `INVALID_XSD` y cualquier
codigo `DEFECTUOSO` desconocido requieren revision tecnica y no pueden
reintentarse ni subsanarse.

### Registros Defectuosos

Mostrara registros `RECHAZADO`, `DEFECTUOSO` y
`ACEPTADO_CON_ERRORES`, su documento relacionado, ultimo error e historial.

`VERIFACTU_CORRECT` podra abrir el formulario de subsanacion definido por el
dominio existente. Solo permitira:

- Motivo obligatorio.
- NIF del destinatario.
- Nombre o razon social.
- Descripcion administrativa.

Importes, impuestos, lineas, numero, fecha y tipo de factura se mostraran como
solo lectura y no formaran parte del comando editable. Una correccion economica
se realizara mediante el documento rectificativo correspondiente, no mediante
subsanacion.

Decision aprobada: un registro `ACEPTADO` no admite subsanacion administrativa
desde VERI*FACTU; cualquier correccion posterior se canalizara mediante factura
rectificativa. `ACEPTADO_CON_ERRORES` podra subsanarse solamente cuando la
factura comercial sea correcta y el defecto pertenezca al registro transmitido.
Un `DEFECTUOSO` solo podra subsanarse si su codigo ha sido clasificado
explicitamente como administrativo corregible; en caso contrario se bloqueara
la accion en backend.

### Facturas rectificativas de venta

Decision aprobada: APP GESTION utilizara un flujo en dos acciones. Primero se
previsualiza el calculo real y se crea un borrador vinculado; despues un usuario
con permiso de confirmacion lo revisa y lo emite. Crear el borrador no asigna
numero fiscal ni genera el alta VERI*FACTU.

Alcance de esta fase:

- Solo metodo `I` por diferencias.
- `R1` para devolucion de mercancia, descuento o cambio de precio posterior,
  resolucion completa de la operacion y error fundado de derecho o tributario.
- `R4` para otras causas expresamente justificadas.
- `R2` y `R3` quedan pendientes de un flujo especifico de concurso y credito
  incobrable; no se seleccionan manualmente.
- La causa determina automaticamente tipo fiscal y afectacion de stock.
- Devolucion de mercancia y resolucion completa reponen stock; los ajustes
  economicos no mueven almacen.
- La emision de la rectificativa no ejecuta por si sola la devolucion del dinero.
- Los tickets y facturas simplificadas mantienen su flujo dedicado `R5`.
- Toda rectificativa referencia de forma obligatoria la factura original en el
  dominio comercial, en el snapshot y en el XML fiscal.

El backend impedira crear `RECTIFICA` por el endpoint generico, confirmar una
rectificativa sin metadatos u origen, exceder la cantidad aun disponible, o
rectificar mediante el flujo de devolucion de ticket una factura completa.

Permisos:

- Acceso obligatorio a APP GESTION: `APP_GESTION_ACCESS`, salvo `ADMIN`.
- Crear, previsualizar y modificar borrador: `GESTION_VENTAS` o
  `INVOICES_WRITE`.
- Confirmar y emitir: `GESTION_VENTAS` o `INVOICES_CONFIRM`.
- El backend aplica estas condiciones; ocultar o deshabilitar controles en el
  frontend no sustituye la autorizacion.

### Certificado

La vista sera exclusiva de `ADMIN` y mostrara solamente metadatos publicos:

- Estado.
- Titular y emisor.
- NIF.
- Numero de serie y huella publica.
- Inicio y fin de vigencia.
- Dias restantes.

Importar o sustituir exigira PKCS#12 y contraseña en una peticion multipart.
La contraseña no se persistira, no se guardara en estado global del frontend y
el control se limpiara al terminar. Eliminar exigira confirmacion explicita y
el backend decidira si la operacion esta permitida.

### Diagnostico

Mostrara el estado del reloj, modo de endpoint, disponibilidad del trabajador y
ultima comunicacion. `VERIFACTU_MANAGE` podra ejecutar comprobaciones no
destructivas. Las pruebas contra servicios oficiales no reutilizaran ventas
reales ni generaran efectos comerciales.

## Contratos Backend

### Lectura Para APP VENTA

Se añadiran contratos dedicados y sanitizados:

- `GET /api/v1/verifactu/pos/status`.
- `GET /api/v1/verifactu/pos/queue?limit=50`.

Ambos exigiran `VENTA` o `ADMIN`, sesion valida y terminal activa/aprobada.
`CurrentTerminal` resolvera la terminal a partir del token autenticado. No se
aceptara `terminalId`, `tiendaId` ni `empresaId` como parametro del cliente.

El filtro utilizara `documento.terminal_origen_id`, ya inmutable e indexado, y
la relacion del registro fiscal con el documento. Los documentos historicos
sin terminal de origen no se incluiran en APP VENTA. APP GESTION seguira
mostrandolos dentro de la tienda autorizada.

La respuesta POS nunca incluira XML, `responsePayload`, metadatos de
certificado ni datos identificativos del destinatario.

### Administracion Para APP GESTION

Los contratos administrativos se mantendran bajo
`/api/v1/verifactu/admin` y `/api/v1/verifactu/defective-records`, con los
nuevos permisos. Se incorporara paginacion en nuevas respuestas de consulta:

- Resumen y contadores.
- Cola filtrada y paginada.
- Historial paginado de intentos.
- Registros defectuosos paginados.
- Reintento explicito de un registro elegible.

El endpoint legado `POST /api/v1/verifactu/admin/retry-next` procesa una cola
global y permanecera reservado a `ADMIN`. `VERIFACTU_MANAGE` no podra usarlo:
los reintentos delegados se habilitaran mediante el nuevo endpoint explicito por
registro, limitado con `CurrentOrganization` a la tienda activa.

Los endpoints actuales no paginados se conservaran durante la migracion del
frontend y solo se retiraran en un cambio de contrato separado. No se cambiara
silenciosamente la forma JSON de una operacion ya publicada.

Mientras no existan los nuevos contratos sanitizados y scoped:

- El `status` legado, que contiene metadatos del certificado, permanecera
  restringido a `ADMIN`.
- Los historiales legados con `responsePayload` completo exigiran
  `VERIFACTU_MANAGE`, `VERIFACTU_READ` y acceso a APP GESTION.
- `retry-next` permanecera restringido a `ADMIN`, porque el trabajador actual
  selecciona el siguiente registro entre todas las tiendas. `VERIFACTU_MANAGE`
  solo podra reintentar mediante el futuro contrato explicito por registro,
  validado contra `CurrentOrganization`.

Todas las consultas se limitaran en backend a la empresa y tienda resueltas por
`CurrentOrganization`. Los repositorios utilizaran consultas paginadas con
join entre estado, registro y documento, evitando cargar todos los estados y
resolver cada registro mediante consultas N+1.

Se revisaran indices para los filtros reales. Como minimo se comprobara la
utilidad de:

- Estado y fecha de actualizacion de envio.
- Empresa, tienda y secuencia fiscal.
- Tienda, terminal de origen y fecha del documento.

No se añadira un indice sin contrastar el plan de consulta PostgreSQL.

## Impresion Fiscal

La impresion es parte obligatoria de este alcance, aunque la administracion se
ubique en APP GESTION.

La respuesta autoritativa del documento confirmado y la consulta usada en
reimpresiones incluiran un bloque fiscal de impresion versionable con:

- URL exacta del QR.
- Leyenda aplicable.
- Identificador o version del formato fiscal.

El texto de la leyenda no quedara duplicado como constante independiente en
APP VENTA. El backend proporcionara la representacion aplicable a la version
fiscal del registro.

`ConfirmedTicketPrintSnapshot`, los contratos de impresion A4 y
`TicketPrintRequest` admitiran el bloque fiscal opcional. Se imprimira en:

- Ticket y factura simplificada.
- Factura completa de venta.
- Ticket y factura rectificativa.
- Reimpresion del documento fiscal.

No se imprimira en albaranes, presupuestos, facturas de compra ni documentos
anteriores a la activacion que no tengan registro fiscal.

El puente Electron generara una representacion legible tanto en ESC/POS como en
A4. La prueba visual comprobara tamaño, contraste, zona libre, lectura desde
dispositivo real y que el contenido decodificado coincida exactamente con la
URL proporcionada por backend.

Una reimpresion recuperara otra vez el documento confirmado desde backend; no
reconstruira el QR a partir del estado local del navegador y no creara otro
registro fiscal.

## Errores Y Experiencia Operativa

- Error fiscal transaccional local: no se confirma la venta y se informa al
  usuario.
- AEAT o Internet no disponible despues del commit: venta confirmada, registro
  pendiente y estado visible sin alarma persistente por cada intento.
- Certificado ausente o invalido: ventas permitidas, envios pendientes y aviso
  persistente solo a `ADMIN` en APP GESTION.
- Rechazo o defecto: APP VENTA muestra `Requiere revision` sin datos tecnicos;
  APP GESTION muestra el detalle autorizado.
- Error de impresion: el documento permanece confirmado y se ofrece la
  reimpresion del mismo contenido fiscal.
- Backend local no disponible: se aplica el comportamiento existente de fallo
  de venta; el indicador no puede utilizarse para asumir que el sistema fiscal
  esta operativo.

Todos los mensajes se centralizaran en español, ingles y chino. Los codigos de
backend seran estables; la UI no comparara textos libres para decidir estados.

## Auditoria Y Seguridad

Se auditaran:

- Activacion y desactivacion.
- Importacion, sustitucion y eliminacion de certificado.
- Reintento manual.
- Creacion de subsanacion.
- Prueba de conexion o diagnostico iniciada por usuario.
- Exportaciones fiscales futuras.

No se auditaran como acciones de negocio las consultas periodicas de estado.
Los logs, auditoria y respuestas no contendran contraseña, clave privada ni
material PKCS#12.

El contenido XML y las respuestas AEAT se mostraran escapados y no se enviaran
a APP VENTA. Los errores POS se sanitizaran en backend.

## Rendimiento

- El resumen POS sera una consulta agregada pequeña.
- La cola POS tendra limite maximo impuesto por backend.
- Las tablas administrativas seran paginadas en servidor.
- Los filtros y orden se ejecutaran en PostgreSQL.
- El frontend cancelara o ignorara respuestas obsoletas al cambiar filtros.
- La pantalla pausara el sondeo cuando no este visible.
- El modulo de APP GESTION se cargara de forma diferida para no aumentar el
  paquete principal, que ya supera el umbral recomendado.

## Pruebas

### Backend

- Permisos positivos y negativos para cada contrato.
- `VENTA` solo obtiene documentos de la terminal autenticada.
- Un `terminalId` fabricado no puede ampliar el alcance.
- Terminal de otra tienda, inactiva o no aprobada queda rechazada.
- APP GESTION solo obtiene la tienda activa.
- Respuestas POS no contienen XML, payload, NIF ni certificado.
- Paginacion, limites, filtros y orden estable.
- Reintento concurrente e idempotencia operativa.
- Subsanacion conserva la invariabilidad economica.
- Consultas sin N+1 y planes PostgreSQL aceptables con volumen representativo.

### Frontend APP VENTA

- El indicador aparece dentro de Venta, no en el inicio.
- Estados, carga, vacio y error.
- Panel de solo lectura sin acciones fiscales.
- Cierre con boton, `Escape` y clic fuera sin perder el carrito.
- Actualizacion tras confirmar una venta.
- Pausa de sondeo cuando la ventana no esta visible.
- No se muestran datos de otras terminales ni payload tecnico.

### Frontend APP GESTION

- Navegacion y permisos del modulo.
- Tablas paginadas, filtros y estados vacios/error.
- Acciones ocultas y rechazadas por backend cuando falta permiso.
- Confirmacion de activacion, desactivacion, reintento y certificado.
- Formulario de subsanacion sin campos economicos editables.
- Renderizado seguro de respuestas tecnicas.

### Impresion Y Validacion Oficial

- QR y leyenda en ESC/POS y A4.
- Igualdad del QR entre impresion inicial y reimpresion.
- Ausencia de QR en documentos no fiscales.
- Lectura del QR impreso con dispositivos reales.
- Validacion del contenido mediante los servicios oficiales disponibles.
- Pruebas completas contra el entorno oficial de pruebas AEAT antes de
  produccion.

## Fases De Implementacion

1. Endurecer permisos y añadir contratos POS limitados por terminal.
2. Implementar el indicador y panel de APP VENTA.
3. Implementar el modulo de lectura de APP GESTION.
4. Añadir reintentos, subsanaciones, certificado y diagnostico segun permiso.
5. Distribuir la politica global de activacion desde SaaS a las licencias.
6. Integrar QR y leyenda en todas las rutas de impresion y reimpresion.
7. Ejecutar pruebas de volumen, restauracion y entorno oficial AEAT.
8. Preparar paquete de inspeccion y declaracion responsable de la version
   fiscal candidata a produccion.

## Fuera Del Primer Corte De UI

- Consulta multitienda desde APP GESTION local.
- Consulta multitienda de registros y envios fiscales desde el panel SaaS.
- Modificacion de registros fiscales confirmados.
- Modalidad no VERI*FACTU con conservacion local.
- Purga automatica de registros fiscales.
- Certificar cumplimiento solamente mediante pruebas internas.

El paquete de inspeccion y la declaracion responsable no se consideran
prescindibles para produccion, aunque se implementen despues de la primera
pantalla administrativa.

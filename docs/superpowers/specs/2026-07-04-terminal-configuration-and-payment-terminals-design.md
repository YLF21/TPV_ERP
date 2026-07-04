# Diseno De Configuracion De Terminales Y Datafonos

## Objetivo

Definir como se configuraran los dispositivos propios de cada terminal de venta
en `TPV ERP`, empezando por datafonos y dejando la misma base preparada para
impresoras, cajon portamonedas, formato local de tickets, pantalla cliente y
otros perifericos.

La regla principal es que **APP GESTION vive en el terminal servidor**, mientras
que **cada APP VENTA configura el hardware fisico de su propia caja**. El
backend local conserva la configuracion asociada a `terminal_id`, registra los
pagos y valida permisos, pero no contiene drivers de aparatos fisicos.

## Alcance

- Crear el permiso `CONFIGURACION_TERMINAL`.
- Separar configuracion general de tienda y configuracion local de terminal.
- Permitir tarjeta manual aunque no exista datafono integrado.
- Preparar el modelo para datafonos integrados por proveedor.
- Registrar resultados de cobro sin guardar datos sensibles de tarjeta.
- Dejar fuera la implementacion completa de conectores reales en esta primera
  decision de diseno.

No se sustituye `TERMINALS_MANAGE`. Ese permiso sigue orientado a administracion
de terminales desde servidor: alta, aprobacion, activacion, desactivacion y
gestion estructural. `CONFIGURACION_TERMINAL` se orienta a configurar la caja
fisica ya asignada.

## Permiso `CONFIGURACION_TERMINAL`

`CONFIGURACION_TERMINAL` sera un permiso, no un rol fijo. Cualquier rol podra
incluirlo, por ejemplo `ADMIN`, `ENCARGADO` o un rol especifico de responsable
de terminal.

Permite configurar en la terminal activa:

- datafono;
- impresora de tickets;
- impresora de etiquetas si aplica;
- cajon portamonedas;
- formato local de ticket;
- pantalla cliente;
- lector externo configurable;
- pruebas de hardware;
- activacion y desactivacion de dispositivos locales;
- modo de tarjeta manual o integrado para esa terminal.

Un vendedor con permiso de venta podra cobrar, pero no podra cambiar datafono,
impresora, formato de ticket ni perifericos sin `CONFIGURACION_TERMINAL`.

## Separacion De Responsabilidades

### APP GESTION En Servidor

APP GESTION se ejecutara en el terminal servidor y gestionara reglas generales:

- metodos de pago disponibles por empresa o tienda;
- si la tienda permite tarjeta manual;
- proveedores de datafono permitidos;
- si se permite fallback manual cuando falla un datafono integrado;
- si la referencia de tarjeta es obligatoria;
- politicas generales de impresion y ticket cuando no dependan de una caja
  concreta.

APP GESTION no configurara el aparato fisico de cada caja, porque normalmente no
tendra delante el puerto, IP, driver, SDK local ni app instalada del datafono o
impresora.

### APP VENTA En Cada Caja

Cada APP VENTA tendra una zona de configuracion local protegida por
`CONFIGURACION_TERMINAL`. Esa pantalla actuara sobre la terminal ya fijada por
instalacion/asignacion del servidor.

Permitira:

- consultar el identificador de terminal actual;
- seleccionar modo de tarjeta: manual o integrado;
- configurar el proveedor del datafono de esa caja;
- introducir parametros locales: IP, puerto, COM, USB, identificador de
  dispositivo, comercio, terminal externo o credenciales locales segun proveedor;
- probar conexion;
- lanzar cobro de prueba;
- activar o desactivar el dispositivo;
- volver a modo manual.

La configuracion fisica se asocia a `terminal_id`. Caja 1 y Caja 2 pueden usar
proveedores, dispositivos o modos distintos dentro de la misma tienda.

### Backend Local

El backend local conserva la configuracion por terminal, registra resultados y
aplica permisos. Su papel es:

- guardar configuracion local asociada a `terminal_id`;
- validar que el usuario puede modificarla;
- exponer la configuracion a APP VENTA;
- registrar pagos con proveedor, referencia y estado final;
- vincular el cobro con `documento_pago`;
- impedir que un ticket quede confirmado con un pago de tarjeta integrado no
  aprobado;
- auditar cambios de configuracion y resultados de cobro.

El backend no debe contener drivers USB/COM ni depender de SDKs locales de
fabricantes. Cuando un proveedor use una API cloud estable, podra existir un
servicio backend o modulo comun para llamarla, pero los dispositivos locales
seguiran resolviendose desde APP VENTA o un puente local.

## Modelo De Configuracion

### Configuracion General

La configuracion general de tienda o empresa incluira:

- `card_manual_enabled`: permite registrar tarjeta manual.
- `card_manual_reference_required`: exige referencia en tarjeta manual.
- `integrated_card_enabled`: permite usar datafonos integrados.
- `manual_fallback_enabled`: permite pasar a manual si falla el datafono.
- `allowed_payment_terminal_providers`: proveedores permitidos por la tienda.

Estos campos los administrara APP GESTION desde el servidor.

### Configuracion Por Terminal

La configuracion por terminal guardara:

- `terminal_id`;
- `card_mode`: `MANUAL` o `INTEGRATED`;
- `provider`: `NONE`, `REDSYS_TPV_PC`, `PAYTEF`, `PAYCOMET`,
  `GLOBAL_PAYMENTS` u otros futuros;
- `display_name`;
- `enabled`;
- `test_mode`;
- `last_connection_test_at`;
- `last_connection_status`;
- parametros no sensibles del proveedor;
- referencia a secretos protegidos cuando existan credenciales.

Los secretos se guardaran protegidos con el mecanismo de secretos de la
instalacion local. No se escribiran claves en logs, snapshots fiscales ni
respuestas de API.

## Datafonos Y Pagos Con Tarjeta

### Modo Manual

El modo manual debe estar siempre disponible si la configuracion general lo
permite.

Flujo:

1. Cajero pulsa `+` para tarjeta en APP VENTA.
2. Si la terminal esta en modo manual, APP VENTA muestra cobro de tarjeta
   manual.
3. El cajero cobra en el datafono externo no integrado.
4. Introduce referencia o autorizacion si la regla lo exige.
5. APP VENTA envia el pago al backend como tarjeta manual.
6. El backend registra `documento_pago` con la referencia indicada.

### Modo Integrado

El modo integrado se usara cuando la terminal tenga un proveedor activo y una
prueba correcta.

Flujo:

1. Cajero pulsa `+`.
2. APP VENTA calcula importe pendiente y envia la orden al conector local o
   puente de pago.
3. El datafono procesa tarjeta, contactless, movil o PIN.
4. El conector devuelve resultado: `APROBADO`, `DENEGADO`, `CANCELADO`,
   `TIMEOUT` o `ERROR`.
5. Si esta aprobado, APP VENTA envia el pago al backend con proveedor,
   autorizacion y referencia.
6. Si no esta aprobado, el backend no registra pago aprobado y APP VENTA ofrece
   reintentar, cancelar o pasar a manual cuando la tienda lo permita.

El ticket solo se confirmara cuando el pago quede aprobado o manualmente
registrado. Facturas y albaranes mantendran su flujo de pago parcial o completo
ya definido.

### Proveedores

La arquitectura contemplara conectores independientes:

- `MANUAL`: sin comunicacion con datafono.
- `REDSYS_TPV_PC`: primer candidato para integracion presencial en Espana.
- `PAYTEF`: proveedor multibanco con integracion por API/protocolo propio.
- `PAYCOMET`: proveedor del ecosistema Banco Sabadell/PAYCOMET.
- `GLOBAL_PAYMENTS`: opcion para instalaciones que lo requieran.

No se implementaran todos a la vez. La primera fase funcional debe cubrir modo
manual y dejar preparada la interfaz comun. El primer conector real se elegira
despues, con preferencia por Redsys TPV-PC si el comercio lo tiene contratado
con su entidad.

## Payment Bridge Local

Para datafonos que dependan de app instalada, puerto local, COM, USB, SDK
Windows o red local, se usara una capa local:

- embebida en APP VENTA si el proveedor lo permite;
- o como pequeno `payment-bridge` local ejecutado en la misma caja.

El puente local recibira una orden simple:

- importe;
- moneda;
- identificador de operacion;
- tipo de operacion: venta, devolucion o anulacion si aplica;
- metadatos de terminal.

Y devolvera:

- estado;
- referencia;
- codigo de autorizacion;
- mensaje tecnico;
- datos imprimibles no sensibles.

El puente local nunca expondra datos de tarjeta al backend ni a logs de APP
VENTA.

## Integracion Con Pagos Existentes

El metodo de pago `TARJETA` seguira siendo el metodo comercial visible. La
informacion de datafono es detalle operativo del pago, no un metodo de pago
distinto por cada proveedor.

`documento_pago` debera poder conservar:

- referencia;
- proveedor de datafono;
- modo manual o integrado;
- estado final de operacion;
- codigo de autorizacion cuando exista;
- terminal fisico que realizo el cobro.

Si se necesita historico tecnico de intentos, se creara una tabla separada de
operaciones de datafono. Esa tabla registrara intentos aprobados y fallidos sin
afectar a caja hasta que exista un pago aprobado vinculado a `documento_pago`.

## Auditoria Y Seguridad

Se auditaran:

- cambios de modo manual/integrado;
- altas, bajas y modificaciones de dispositivos locales;
- pruebas de conexion;
- cobros de prueba;
- cambios de fallback manual;
- pagos aprobados y rechazados cuando vengan de datafono integrado.

No se almacenara:

- numero completo de tarjeta;
- CVV;
- PIN;
- banda magnetica;
- datos EMV sensibles;
- tokens que permitan cargar una tarjeta salvo que un proveedor los documente y
  se defina una fase especifica con cumplimiento adecuado.

Los logs tecnicos deberan enmascarar credenciales, claves, referencias largas y
respuestas completas de proveedor.

## API Inicial

La API backend debera cubrir:

- consultar configuracion de terminal actual;
- actualizar configuracion local con `CONFIGURACION_TERMINAL`;
- registrar resultado de prueba de conexion;
- consultar reglas generales de pago;
- registrar pago de tarjeta manual;
- registrar pago de tarjeta integrada aprobado;
- registrar intento fallido de datafono integrado si se crea historico tecnico.

La API no ejecutara directamente drivers de datafono local. APP VENTA o el
puente local seran responsables de hablar con el dispositivo.

## Experiencia En APP VENTA

La configuracion local se abrira desde una zona protegida, no desde el flujo
normal de cobro.

Durante el cobro:

- `+` selecciona tarjeta.
- Si el modo es manual, se muestra referencia/autorizacion.
- Si el modo es integrado, se muestra espera de datafono.
- `Esc` cancela la espera si el proveedor lo permite.
- Se podra reintentar tras denegacion, error o timeout.
- Se podra pasar a manual solo si la tienda lo permite.
- Los mensajes visibles estaran en i18n.

El cajero no vera claves ni parametros tecnicos durante el cobro.

## Fuera De Alcance Inicial

- Implementar Redsys TPV-PC completo.
- Implementar PAYTEF completo.
- Implementar PAYCOMET completo.
- Devoluciones automaticas con datafono.
- Conciliacion bancaria avanzada.
- Tokenizacion de tarjetas.
- Pagos recurrentes.
- Pagos online o pay-by-link.
- Configuracion de todos los perifericos; la spec define la base comun para
  incorporarlos.

## Pruebas

La implementacion posterior debera cubrir:

- migracion del permiso `CONFIGURACION_TERMINAL`;
- bootstrap de permisos e i18n;
- validacion de que un usuario sin permiso no modifica configuracion local;
- persistencia de configuracion por `terminal_id`;
- separacion entre `TERMINALS_MANAGE` y `CONFIGURACION_TERMINAL`;
- tarjeta manual con referencia opcional y obligatoria;
- tarjeta integrada aprobada vinculada a `documento_pago`;
- intento integrado denegado o timeout sin confirmar pago;
- fallback manual permitido y bloqueado;
- ausencia de datos sensibles en respuestas y logs de pruebas;
- pruebas de cliente APP VENTA para modo manual e integrado simulado.


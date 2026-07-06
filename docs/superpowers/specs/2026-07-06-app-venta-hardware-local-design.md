# APP VENTA: hardware local del terminal

## Objetivo

APP VENTA debe controlar los dispositivos fisicos conectados al terminal de venta sin que el backend hable directamente con USB, COM, drivers o impresoras locales. El backend podra guardar la configuracion del terminal y aportar datos de empresa, tienda, terminal y documentos, pero la ejecucion de hardware sera local en Electron.

La primera fase cubre escaner de codigo de barras, impresora de ticket y cajon de dinero. Pantalla cliente, pantalla secundaria e impresora A4 quedan preparadas como extension posterior, no como parte obligatoria de esta fase.

## Alcance de la fase 1

Incluido:

- Escaner de codigo de barras en modo teclado USB.
- Listado de impresoras instaladas en Windows.
- Impresion de ticket mediante impresora de Windows.
- Configuracion preparada para modo ESC/POS directo por USB, COM o LAN.
- Apertura de cajon asociada a impresion de ticket cuando la impresora lo soporte.
- Pruebas manuales desde una pantalla de configuracion del terminal.

Fuera de esta fase:

- Comunicacion ESC/POS directa completa.
- Pantalla cliente.
- Pantalla secundaria.
- Impresion A4.
- Persistencia definitiva de configuracion en backend si el endpoint aun no esta cerrado.

## Arquitectura

La capa de hardware se divide en tres partes:

1. `desktop/main.cjs`: proceso principal de Electron. Contiene la integracion con el sistema operativo, impresoras y futuras conexiones ESC/POS.
2. `desktop/preload.cjs`: expone una API segura a React mediante `contextBridge`.
3. `packages/app-common`: consume esa API desde APP VENTA y APP GESTION sin acceder directamente a Node.

La API publica del frontend sera una interfaz como:

- `listPrinters()`
- `printTicket(request)`
- `openCashDrawer(request)`
- `getHardwareConfig()`
- `saveHardwareConfig(config)`
- `testScannerInput(code)`

Si la app se ejecuta en navegador durante desarrollo sin Electron, estas funciones deben devolver una respuesta controlada indicando que el hardware local no esta disponible.

## Configuracion del terminal

Modelo inicial:

- `scannerMode`: `KEYBOARD`
- `scannerSubmitKey`: `ENTER`
- `ticketPrinterMode`: `WINDOWS_PRINTER` o `ESCPOS`
- `ticketPrinterName`
- `openCashDrawerWithTicket`
- `cashDrawerCommandProfile`: `ESCPOS_STANDARD`
- `escposConnectionType`: `USB`, `SERIAL` o `NETWORK`
- `escposDevicePath`
- `escposSerialBaudRate`
- `escposHost`
- `escposPort`

En fase 1 solo se ejecuta `WINDOWS_PRINTER` y `KEYBOARD`. Los campos ESC/POS se guardan o muestran como preparacion, pero no deben prometer funcionamiento hasta la fase ESC/POS.

## Escaner de codigo de barras

El escaner se trata como teclado. APP VENTA recibira el codigo en el campo rapido actual y lo confirmara con `Enter`. Esto evita drivers especificos y cubre la mayoria de lectores USB.

La pantalla de configuracion debe incluir una zona de prueba:

- Campo enfocado para escanear.
- Ultimo codigo leido.
- Hora de lectura.
- Resultado: recibido correctamente o vacio.

No se anade integracion Bluetooth, serie o SDK propietario en esta fase.

## Impresora de ticket

En modo `WINDOWS_PRINTER`, Electron usara las impresoras instaladas en Windows. La configuracion selecciona una impresora por nombre.

Funciones:

- Detectar/listar impresoras.
- Imprimir ticket de prueba.
- Imprimir ticket real desde el flujo de cobro cuando este conectado.

El formato inicial del ticket sera HTML imprimible, estrecho, orientado a impresoras termicas. Debe incluir como minimo:

- empresa o nombre fiscal cuando este disponible,
- tienda y terminal,
- numero de documento,
- fecha y hora,
- lineas,
- impuestos incluidos,
- total,
- formas de pago.

El corte de papel y comandos ESC/POS avanzados quedan para la fase directa ESC/POS.

## Cajon de dinero

En fase 1 la apertura se modela como operacion asociada a impresora de ticket:

- Si `openCashDrawerWithTicket` esta activo, al imprimir un documento con forma de pago que abre caja se ejecuta la apertura.
- El backend conserva la regla de que formas de pago tienen `abreCajaRegistradora=true`.
- Si no hay impresora configurada o el modo no soporta apertura, la UI debe mostrar error claro.

La apertura directa por puerto queda para la fase ESC/POS.

## Pantalla de configuracion y pruebas

Se anadira una seccion de hardware dentro de configuracion del terminal, accesible segun permisos de gestion adecuados.

Controles:

- Selector de modo de impresora.
- Selector de impresora Windows.
- Boton detectar impresoras.
- Boton imprimir ticket de prueba.
- Boton abrir cajon.
- Activar/desactivar apertura de cajon con ticket.
- Zona de prueba del escaner.

La pantalla debe seguir el estilo visual actual de APP VENTA: fondo comun, botones grandes, iconos cuando existan, sin paneles innecesarios.

## Errores y estados

Los errores deben ser operativos y traducibles:

- impresora no configurada,
- impresora no encontrada,
- impresion cancelada o fallida,
- hardware local no disponible fuera de Electron,
- cajon no configurado,
- modo ESC/POS aun no disponible.

Ningun error de hardware debe bloquear la creacion logica del documento si el backend ya lo confirmo. Si falla la impresion, se permitira reimprimir.

## Pruebas

Pruebas automaticas:

- API de hardware no disponible en navegador devuelve respuesta controlada.
- configuracion por defecto del hardware.
- construccion de ticket de prueba con total, lineas y pagos.
- permisos o visibilidad de la pantalla si ya existe el modelo de permisos en frontend.

Pruebas manuales:

- listar impresoras en Windows,
- imprimir ticket de prueba,
- simular escaneo con teclado y escaner real,
- abrir cajon desde boton de prueba cuando exista hardware compatible.

## Orden de implementacion

1. Crear tipos e interfaz de hardware en `app-common`.
2. Ampliar `preload.cjs` y `desktop/main.cjs` con IPC seguro.
3. Implementar listado de impresoras y respuesta controlada fuera de Electron.
4. Crear UI de configuracion/pruebas.
5. Implementar ticket de prueba en modo Windows.
6. Conectar apertura de cajon de forma preparada, dejando ESC/POS directo para fase posterior.

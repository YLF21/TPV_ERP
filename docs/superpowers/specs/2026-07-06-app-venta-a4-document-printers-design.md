# APP VENTA: impresoras A4 y rutas por documento

## Objetivo

APP VENTA debe permitir configurar que impresora se usa segun el tipo de documento. La impresion sigue siendo local en Electron y no en backend.

## Pendiente de Fase 2

- Pantalla secundaria completa queda pospuesta.
- Conexion con eventos reales de venta queda pendiente hasta implementar la pantalla TPV final.
- Prueba manual con segundo monitor fisico queda pendiente de hardware real.
- Displays antiguos por puerto serie especifico quedan fuera hasta conocer modelo concreto.

## Alcance Fase 3

Incluido:

- Configuracion de impresora A4 base.
- Impresion A4 de prueba desde Electron.
- Rutas de impresion por tipo de documento.
- Seleccion de impresora y formato por documento.
- Preparacion para factura, albaran e informes.

Fuera de esta fase:

- Plantilla fiscal definitiva de factura.
- Plantilla definitiva de albaran.
- Impresion automatica conectada al flujo real de venta.
- Vista previa PDF avanzada.

## Impresoras base

La configuracion local mantiene:

- impresora ticket actual,
- impresora A4,
- modo ESC/POS para ticket/cajon.

La impresora A4 se selecciona entre impresoras Windows instaladas.

## Rutas por documento

Cada tipo de documento tendra una ruta de impresion:

- Ticket
- Factura
- Albaran
- Informe

Campos por ruta:

- `documentType`
- `printerTarget`: `TICKET_PRINTER` o `A4_PRINTER`
- `printerName`
- `paperSize`: `TICKET_80` o `A4`
- `orientation`: `PORTRAIT` o `LANDSCAPE`
- `copies`
- `printAutomatically`

Valores por defecto:

- Ticket -> `TICKET_PRINTER`, `TICKET_80`, `PORTRAIT`, 1 copia, automatico activado.
- Factura -> `A4_PRINTER`, `A4`, `PORTRAIT`, 1 copia, automatico desactivado.
- Albaran -> `A4_PRINTER`, `A4`, `PORTRAIT`, 1 copia, automatico desactivado.
- Informe -> `A4_PRINTER`, `A4`, `PORTRAIT`, 1 copia, automatico desactivado.

## Prueba A4

La prueba A4 debe imprimir una hoja con:

- titulo del documento,
- tienda,
- terminal,
- fecha/hora,
- tabla de lineas de ejemplo,
- base,
- impuestos incluidos,
- total.

## Electron

Electron imprimira A4 usando una ventana oculta con HTML y `webContents.print`. La impresion A4 no debe usar ESC/POS.

Errores:

- impresora A4 no configurada,
- impresora no encontrada o no disponible,
- impresion fallida.

## UI

La pantalla de hardware/configuracion debe incluir:

- selector de impresora A4,
- boton imprimir prueba A4,
- bloque de rutas por documento,
- selector de destino por documento,
- selector de impresora cuando aplique,
- copias,
- imprimir automaticamente.

## Pruebas

Pruebas automaticas:

- configuracion por defecto contiene rutas por documento.
- Ticket usa impresora ticket por defecto.
- Factura, albaran e informe usan A4 por defecto.
- el documento A4 de prueba tiene lineas y total.

Pruebas manuales:

- detectar impresoras,
- seleccionar impresora A4,
- imprimir prueba A4,
- cambiar ruta de factura a ticket o A4,
- guardar y volver a cargar configuracion.


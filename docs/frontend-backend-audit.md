# Auditoria de conexion frontend-backend

Fecha: 2026-07-06

Regla: el frontend no debe contener datos de negocio directos. Las pantallas deben mostrar datos procedentes de backend/base de datos o, si aun no existe conexion, un estado vacio.

## Conectado al backend

- Inicio de sesion remoto: `POST /api/v1/auth/login`.
- Consulta de stock: `GET /api/v1/stock`.
- Informes de salida: `GET /api/v1/tickets`, `GET /api/v1/invoices`, `GET /api/v1/delivery-notes`.
- Preferencias de visualizacion de informes: `GET /api/v1/sales-reports/visualization-preferences` y `PUT /api/v1/sales-reports/visualization-preferences/{reportKey}`.
- Etiqueta del servidor local en footer: usa `VITE_TPV_API_BASE_URL` o `/api/v1`.

## Local por diseno

- Hardware local del terminal: impresoras, ESC/POS, cajon, escaner, pantalla cliente y A4 se ejecutan en Electron mediante IPC local. No deben hablar directamente con el backend para ejecutar USB, COM, drivers o dialogos de Windows.
- Historial de usuarios en login: guardado en `localStorage` del terminal.

## Pendiente de conectar al backend

- Pantalla VENTA: actualmente muestra estado vacio y no llama todavia a `/products`, `/tickets`, `/invoices`, `/delivery-notes`, `/parked-sales`, `/payment-methods` ni `/cash`.
- Pantalla STOCK: carga cantidades reales desde `/stock`. Queda pendiente enriquecer la vista con codigo/nombre de articulo y almacen cuando se conecten `/products` y `/warehouses`.
- APP GESTION: pantalla base pendiente; aun no conecta modulos de clientes, proveedores, catalogo, usuarios, stock, tickets, facturas o albaranes.
- Ajustes generales: secciones de terminal, usuario, informes y sistema tienen placeholders visuales; solo hardware tiene funcionalidad local real.
- Informes de entradas y salidas de almacen: la tabla existe, pero no consume todavia `/warehouse-outputs` ni endpoints de entrada de almacen.
- Informe diario: se construye desde tickets, facturas y albaranes ya cargados; no hay endpoint dedicado de resumen diario comercial en uso desde frontend.

## Resultado

El frontend no tiene todas las funciones vinculadas al backend todavia. La base de API, login remoto, stock, informes documentales y preferencias de visualizacion si estan conectados. La pantalla de venta y APP GESTION siguen en fase de UI/prototipo o hardware local.

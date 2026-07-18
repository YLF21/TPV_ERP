# TPV ERP Frontend

Monorepo de APP VENTA, APP GESTION y los paquetes compartidos.

## Ejecucion local

Desde este directorio:

```powershell
npm.cmd install
$env:VITE_TPV_BACKEND_URL = "http://127.0.0.1:8080"
$env:VITE_TPV_TERMINAL_ID = "<uuid-terminal-local>"
npm.cmd run dev:venta
```

APP GESTION se inicia con `npm.cmd run dev:gestion`. Los comandos deben
ejecutarse desde `frontend`, donde se encuentra `package.json`.

## Verificacion

```powershell
npm.cmd test
npm.cmd run build --workspace @tpverp/app-venta
npm.cmd run build --workspace @tpverp/app-gestion
```

## Pagos con tarjeta en local

En Configuracion se selecciona el proveedor permitido y el modo simulado. La
pantalla se construye con los descriptores y capacidades devueltos por el
backend; no contiene campos ni protocolos fijos de un proveedor. Los cuatro
simuladores locales (Redsys TPV-PC, PAYTEF, PAYCOMET y Global Payments)
permiten probar aprobacion, rechazo, timeout con consulta, anulacion y
devolucion sin operaciones financieras externas.

No configure secretos reales en el navegador ni en variables `VITE_*`: estas
variables quedan incluidas en el bundle. Los secretos de una futura conexion
LIVE son referencias opacas gestionadas por el backend. Mientras no exista un
SDK oficial instalado y certificado, LIVE se muestra como no disponible.

## Ventas pendientes y cobro posterior

En APP VENTA, `F12` o el boton **Pendiente cliente** abre el flujo para crear
un albaran o una factura con saldo pendiente. Si no hay cliente seleccionado,
se abre primero el selector. El vencimiento comienza en la fecha local mas 30
dias, puede editarse antes del primer efecto de tarjeta y el backend vuelve a
calcular el total.

El acceso **DEUDAS CLIENTES** de la pantalla inicial permite filtrar albaranes
y facturas y cobrar posteriormente por efectivo, tarjeta o transferencia. La
misma vista se abre prefiltrada desde **Ver deudas** en la ficha del cliente.
Estas opciones dependen de `CUSTOMER_RECEIVABLES_READ`,
`CUSTOMER_RECEIVABLES_CREATE` y `CUSTOMER_RECEIVABLES_PAY`.

En el cobro posterior de deuda, la UI conserva identificadores idempotentes de
un intento cuyo resultado no se conoce. Tras un timeout de tarjeta se debe usar
**Consultar estado de tarjeta**; no se debe iniciar otro cargo. Un fallo de
impresion no revierte la venta o el cobro: **Reintentar impresion** usa el
snapshot autoritativo del backend.

El manual completo esta en
`../docs/customer-pending-sales-operations.md`.

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

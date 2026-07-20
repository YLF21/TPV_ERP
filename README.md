# TPV_ERP

## Estado del cobro con tarjeta

El flujo de tarjeta de APP VENTA, la configuración del terminal, la persistencia,
la recuperación de operaciones y los simuladores están implementados para
Redsys TPV-PC, PAYTEF, PAYCOMET y Global Payments.

Los gateways LIVE de **Redsys TPV-PC** y **Global Payments** están conectados al
puente HTTP local seguro del proyecto. El backend envía cobros, consultas,
anulaciones, devoluciones, recibos, conciliación y emparejamiento sin recibir
PAN, PIN ni CVV. APP VENTA habilita LIVE únicamente cuando el puente anuncia la
capacidad `CHARGE` para el proveedor seleccionado.

Para operar con un datáfono físico todavía es necesario instalar en ese puente
el SDK oficial del proveedor, configurar el dispositivo y completar la
homologación con la entidad/adquirente. El puente se configura mediante
`tpv.payment.bridge.url` y `tpv.payment.bridge.token` (variables de entorno
`TPV_PAYMENT_BRIDGE_URL` y `TPV_PAYMENT_BRIDGE_TOKEN`). Si el puente o el driver
no están disponibles, LIVE permanece deshabilitado o devuelve
`SDK_NOT_INSTALLED`; nunca simula una aprobación de producción.
No se deben guardar en Git certificados privados, claves, tokens de
emparejamiento ni credenciales del comercio. Los binarios propietarios del SDK
solo pueden incluirse si la licencia de Redsys autoriza expresamente su
redistribución.

El servicio universal y su SPI de plugins están en
[`payment-terminal-bridge`](payment-terminal-bridge/README.md). Permiten añadir
modelos y proveedores sin modificar APP VENTA: cada SDK oficial se encapsula en
un `PaymentTerminalAdapter`, se autoriza por SHA-256 y anuncia únicamente las
capacidades reales del datáfono.

Global Payments dispone además de un adaptador universal extensible por
protocolo y de un driver `SIMULATED` para las primeras pruebas sin modelo ni
movimiento de dinero. Un terminal LIVE seguirá necesitando el driver construido
con el SDK homologado para el dispositivo finalmente suministrado.

El contrato del puente y el procedimiento de instalación se describen en
[`docs/payment-terminal-live-bridge.md`](docs/payment-terminal-live-bridge.md).

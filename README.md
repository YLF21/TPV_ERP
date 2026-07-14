# TPV_ERP

## Estado del cobro con tarjeta

El flujo de tarjeta de APP VENTA, la configuración del terminal, la persistencia,
la recuperación de operaciones y los simuladores están implementados para
Redsys TPV-PC, PAYTEF, PAYCOMET y Global Payments.

La conexión con un datáfono físico todavía no está terminada. Para activar
**Redsys TPV-PC en modo LIVE** es necesario obtener de Redsys o de la entidad
bancaria el SDK/protocolo oficial, sus condiciones de distribución, el entorno
de homologación y las credenciales del comercio. Después se debe implementar o
instalar el conector local que adapte ese SDK al puente HTTP ya previsto por el
proyecto (`tpv.payment.bridge.url` y `tpv.payment.bridge.token`).

Hasta completar esa integración y homologarla con un datáfono real, el modo
LIVE devuelve `SDK_NOT_INSTALLED` y no debe utilizarse para cobros de producción.
No se deben guardar en Git certificados privados, claves, tokens de
emparejamiento ni credenciales del comercio. Los binarios propietarios del SDK
solo pueden incluirse si la licencia de Redsys autoriza expresamente su
redistribución.

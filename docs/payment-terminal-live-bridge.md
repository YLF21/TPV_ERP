# Puente LIVE universal para Redsys, PAYTEF, PAYCOMET y Global Payments

## Alcance

El backend incluye el mismo gateway LIVE para `REDSYS_TPV_PC`, `PAYTEF`,
`PAYCOMET` y `GLOBAL_PAYMENTS`. Todos hablan con un servicio local ejecutado en
la misma caja. Ese servicio es
el único proceso que debe cargar el SDK o driver oficial y comunicarse con el
datáfono.

La implementación universal del servicio está en `payment-terminal-bridge`.
Descubre adaptadores mediante Java `ServiceLoader`, selecciona el plugin por el
perfil del terminal y persiste la idempotencia antes de llamar al SDK. Un modelo
nuevo que ya utilice un SDK soportado solo requiere configuración; un protocolo
nuevo requiere otro plugin, no cambios en APP VENTA.

El backend no captura ni acepta PAN completo, PIN, CVV/CVC, banda, datos EMV ni
secretos del comercio en estos mensajes.

## Configuración del backend

```powershell
$env:TPV_PAYMENT_BRIDGE_URL = "http://127.0.0.1:9123"
$env:TPV_PAYMENT_BRIDGE_TOKEN = "un-token-local-largo-y-aleatorio"
```

Solo se admiten `localhost`, `127.0.0.1` o `::1` mediante HTTP. Todas las
peticiones incluyen `Authorization: Bearer <token>`. No publique el puerto en la
red ni guarde el token real en Git.

Cuando el puente responde y anuncia `CHARGE`, Configuración permite seleccionar
LIVE para el proveedor correspondiente. Sin puente o sin driver, LIVE continúa
bloqueado.

## Contrato HTTP

### Salud

`GET /health`

```json
{
  "available": true,
  "code": "OK",
  "version": "1.0.0"
}
```

### Capacidades por proveedor

`GET /capabilities?provider=REDSYS_TPV_PC&mode=LIVE`

```json
{
  "capabilities": ["HEALTH", "PAIR", "CHARGE", "QUERY", "VOID", "REFUND", "RECEIPT", "RECONCILIATION"]
}
```

El puente debe devolver únicamente capacidades realmente soportadas por el SDK
y por el contrato del comercio. Global Payments puede anunciar un subconjunto.

### Emparejamiento

`POST /pair`

```json
{
  "provider": "REDSYS_TPV_PC",
  "terminalId": "33333333-3333-3333-3333-333333333333",
  "mode": "LIVE",
  "pairingId": "11111111-1111-1111-1111-111111111111",
  "idempotencyKey": "11111111-1111-1111-1111-111111111111",
  "configurationReference": "terminal-payment:config",
  "configurationVersion": 4,
  "parameters": { "ip": "127.0.0.1" }
}
```

### Operaciones

`POST /operation`

```json
{
  "provider": "GLOBAL_PAYMENTS",
  "terminalId": "33333333-3333-3333-3333-333333333333",
  "mode": "LIVE",
  "operationId": "22222222-2222-2222-2222-222222222222",
  "idempotencyKey": "22222222-2222-2222-2222-222222222222",
  "command": "CHARGE",
  "amountMinor": 1234,
  "currency": "EUR",
  "originalOperationId": null,
  "reference": null,
  "configurationReference": "terminal-payment:config",
  "configurationVersion": 4,
  "parameters": {}
}
```

Comandos permitidos: `PAIRING_STATUS`, `CHARGE`, `QUERY`, `VOID`, `REFUND`,
`RECEIPT` y `RECONCILIATION`. Los importes son céntimos enteros y cada
`operationId`/`idempotencyKey` debe ser idempotente en el puente: repetir la
misma petición devuelve el resultado almacenado y nunca inicia otro cargo.

### Respuesta normalizada

```json
{
  "approved": true,
  "code": "APPROVED",
  "reference": "referencia-opaca-del-proveedor",
  "authorization": "AUTH42",
  "message": "Operación aprobada",
  "receiptText": null
}
```

Los códigos admitidos son `APPROVED`, `DECLINED`, `CANCELLED`, `VOIDED`,
`REFUNDED`, `PARTIALLY_REFUNDED`, `PENDING`, `TIMEOUT`, `ERROR`,
`REVIEW_REQUIRED`, `SDK_NOT_INSTALLED`, `PAIRED`, `PAIRING_NOT_FOUND`,
`OPERATION_NOT_FOUND`, `RECEIPT_AVAILABLE`, `RECONCILED`,
`BRIDGE_TIMEOUT`, `BRIDGE_UNAVAILABLE` y `BRIDGE_HTTP_ERROR`.

Una respuesta incoherente o mal formada se convierte en `REVIEW_REQUIRED`; no
se vuelve a cobrar automáticamente. Los recibos se vuelven a sanear antes de
persistirse.

## Redsys TPV-PC

Redsys ofrece TPV-PC como aplicación completa o como componentes integrables.
El comercio debe solicitar a su entidad el SDK/protocolo exacto, licencia,
credenciales y entorno de homologación. El adaptador local debe traducir sus
operaciones al contrato anterior y conservar la idempotencia.

Referencia oficial: [Pago presencial de Redsys](https://redsys.es/en/pago-presencial).

## Global Payments

El proyecto incluye `globalpayments-universal`, que mantiene estable el contrato
del puente y selecciona controladores internos por protocolo. Su driver
`SIMULATED` acepta `model: UNIVERSAL` y permite probar todos los flujos sin mover
dinero. No debe utilizarse como evidencia de certificación LIVE.

Global Payments publica un SDK Java para integración con terminales y documenta
conexiones TCP/IP o serie. Cuando se conozca el modelo, instale como driver del
adaptador universal la versión aprobada por el adquirente y configure únicamente
en el puente el protocolo, modelo, conexión y referencia de credenciales. El
backend no debe conectarse directamente al terminal ni recibir datos de tarjeta.

Referencias oficiales: [SDK Java para terminales](https://developer.globalpay.com/sites/default/files/2021-01/Java%20SDK%20V2.1.1.pdf)
y [repositorio Java de Global Payments](https://github.com/globalpayments/java-sdk).

## Validación antes de producción

1. Probar aprobación, rechazo y cancelación en homologación.
2. Cortar la red después de autorizar y comprobar que `QUERY` recupera la misma
   operación sin repetir el cargo.
3. Probar anulación, devolución total/parcial y reimpresión de recibo según las
   capacidades contratadas.
4. Verificar conciliación y diferencias de jornada.
5. Obtener la aprobación de Redsys/banco o Global Payments/adquirente antes de
   activar LIVE en una tienda real.

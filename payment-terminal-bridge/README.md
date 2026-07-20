# Puente universal de datáfonos

Servicio local independiente que conecta TPV ERP con datáfonos físicos mediante
plugins. APP VENTA utiliza un único contrato; cada plugin adapta el SDK oficial
de Redsys, Global Payments u otro proveedor.

## Seguridad y comportamiento

- Escucha exclusivamente en una dirección de loopback.
- Exige un token Bearer de al menos 24 caracteres.
- Solo carga JAR autorizados en `pluginDigests` y verifica su SHA-256.
- Mantiene un diario idempotente en disco antes de invocar el SDK.
- Una caída con resultado desconocido se bloquea como `REVIEW_REQUIRED`; nunca
  inicia automáticamente un segundo cobro.
- Rechaza parámetros sensibles y enmascara posibles PAN devueltos por un plugin.
- Calcula las capacidades por proveedor como la intersección de los modelos
  configurados, evitando ofrecer funciones que alguna caja no soporte.

## Construcción

```powershell
cd payment-terminal-bridge
mvn.cmd clean package
```

El ejecutable se genera en
`app/target/payment-terminal-bridge-app-0.1.0-SNAPSHOT.jar`.

## Configuración

1. Copie `bridge-config.example.json` como `bridge-config.json`.
2. Deje únicamente los perfiles instalados en esa caja.
3. Copie el adaptador y sus dependencias a `plugins`.
4. Calcule el hash de cada JAR:

```powershell
Get-FileHash .\plugins\redsys-adapter.jar -Algorithm SHA256
```

5. Añada cada nombre y hash a `pluginDigests`, por ejemplo:

```json
{
  "pluginDigests": {
    "redsys-adapter.jar": "0000000000000000000000000000000000000000000000000000000000000000"
  }
}
```

Sustituya los 64 ceros por el hash real antes de arrancar el servicio.

6. Guarde las credenciales en el almacén DPAPI de Windows y configure solo su
   referencia en `secretReference`. `--store-secret` sustituye atómicamente el
   valor anterior, por lo que también sirve para rotarlo:

```powershell
java -jar .\app\target\payment-terminal-bridge-app-0.1.0-SNAPSHOT.jar `
  --config .\bridge-config.json --store-secret windows:bridge-http-token
```
7. Inicie el servicio:

```powershell
$env:TPV_BRIDGE_CONFIG = "C:\ProgramData\TPV ERP\bridge-config.json"
java -jar .\app\target\payment-terminal-bridge-app-0.1.0-SNAPSHOT.jar
```

El ejemplo no autoriza ningún plugin: el puente puede arrancar para diagnóstico,
pero mantendrá LIVE deshabilitado hasta que se configuren los JAR reales.

## Primera prueba con Global Payments

El módulo `adapters/globalpayments-universal` produce el plugin
`payment-terminal-globalpayments-universal-adapter-0.1.0-SNAPSHOT.jar`. Incluye
el protocolo `SIMULATED`, independiente del modelo, para validar localmente
emparejamiento, cobro, consulta, anulación, devolución, recibo y conciliación sin
mover dinero.

Copie ese JAR a `plugins`, autorice su SHA-256 y use el perfil Global Payments de
`bridge-config.example.json`. El valor `connectionType: SIMULATED` impide
confundir esta prueba con un terminal LIVE. Los resultados de prueba se eligen
mediante `simulationOutcome`.

Al recibir el SDK físico, se añade un JAR que implemente
`GlobalPaymentsTerminalDriver` y se cambia el perfil al protocolo y conexión
homologados. APP VENTA y el backend conservan el mismo contrato.

## Plugins

El contrato público está en el módulo `spi`. Un adaptador implementa
`com.tpverp.bridge.spi.PaymentTerminalAdapter` y se registra mediante Java
`ServiceLoader`. Consulte [la guía de desarrollo](../docs/payment-terminal-adapter-development.md).

`adapters/vendor-universal` aporta las entradas estables para Redsys, PAYTEF y
PAYCOMET; el JAR de cada SDK físico implementa `VendorTerminalDriver`. El
catálogo efectivo se consulta con `GET /adapters` o `--diagnose`.

Para desplegarlo como servicio de Windows con verificación de hashes, backup y
rollback, consulte [windows/README.md](windows/README.md).

Los binarios, licencias, credenciales, modelos admitidos y homologación los debe
proporcionar el adquirente. El puente no convierte un SDK de pruebas en una
integración certificada de producción.

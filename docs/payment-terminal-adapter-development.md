# Desarrollo de adaptadores de datáfono

## Objetivo

Un adaptador traduce el contrato estable de TPV ERP al SDK oficial de un
proveedor. El puente no depende de clases de Redsys, Global Payments ni de un
modelo físico concreto.

La dependencia Maven del plugin es:

```xml
<dependency>
  <groupId>com.tpverp</groupId>
  <artifactId>payment-terminal-bridge-spi</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

## Implementación

El JAR implementa `PaymentTerminalAdapter`:

```java
public final class RedsysAdapter implements PaymentTerminalAdapter {
    public String adapterId() { return "redsys-sdk"; }
    public String provider() { return "REDSYS_TPV_PC"; }

    public boolean supports(TerminalProfile profile) {
        return sdkSupports(profile.model(), profile.connectionType());
    }

    public Set<BridgeCapability> capabilities(TerminalProfile profile) {
        return capabilitiesReportedByTheSdk(profile);
    }

    // health, pair y operate llaman únicamente al SDK oficial.
}
```

El archivo
`META-INF/services/com.tpverp.bridge.spi.PaymentTerminalAdapter` debe contener
el nombre completo de la implementación:

```text
com.example.redsys.RedsysAdapter
```

El plugin y todas sus dependencias se copian a `plugins`; cada JAR debe figurar
con su SHA-256 en `pluginDigests`. El directorio debe ser escribible únicamente
por el administrador y por el proceso de actualización firmado.

## Reglas obligatorias

1. `supports` valida expresamente modelo, versión y conexión.
2. `capabilities` solo anuncia operaciones homologadas para ese perfil.
3. Nunca se devuelve PAN completo, PIN, CVV/CVC, banda ni datos EMV.
4. Una respuesta dudosa nunca se convierte en `APPROVED`.
5. Después de un timeout se utiliza la consulta del proveedor; no se repite el
   cobro.
6. La referencia y autorización devueltas deben ser identificadores opacos.
7. Las credenciales se obtienen mediante `secretReference` desde el almacén del
   sistema operativo, nunca desde `parameters`.
8. El cierre libera puertos, sesiones y recursos nativos del SDK.

## Redsys

El adaptador `redsys-sdk` debe construirse contra el paquete TPV-PC y la
modalidad habilitada por la entidad. Redsys describe modalidades implantada,
App2App con SSO y SDK con SSO; la entidad debe confirmar cuál se homologa para
el comercio: <https://redsys.es/pago-presencial>.

El mismo adaptador puede soportar varios modelos si el SDK proporciona una API
común. Las diferencias se declaran mediante `supports` y `capabilities`.

## Global Payments

El módulo `globalpayments-universal` ya implementa la entrada común y no fija un
modelo. Selecciona un `GlobalPaymentsTerminalDriver` por el parámetro `protocol`;
el driver simulado permite las primeras pruebas con `model: UNIVERSAL` y
`connectionType: SIMULATED` sin realizar operaciones financieras.

Cuando se conozca el datáfono, el paquete del SDK se encapsula en otro JAR que
implementa `GlobalPaymentsTerminalDriver`. Ese JAR declara modelos, conexiones y
capacidades homologadas, y se descubre por `ServiceLoader`. No es necesario
modificar APP VENTA, el backend ni `GlobalPaymentsUniversalAdapter`.

Un driver físico puede configurar TCP/IP, USB o serie solamente si el SDK
certificado lo admite, y traduce autorización, consulta, reverso, devolución,
recibo y reportes. La referencia técnica publicada está en:
<https://developer.globalpay.com/sites/default/files/2021-01/Java%20SDK%20V2.1.1.pdf>.

La versión efectiva y el identificador de `protocol` deben ser los aprobados por
el adquirente para el país, modelo y contrato del cliente. Si no existe un driver
compatible o hay más de uno en modo `AUTO`, el adaptador falla de forma cerrada y
no anuncia capacidades LIVE.

## Redsys, PAYTEF y PAYCOMET

El módulo `adapters/vendor-universal` publica los adaptadores
`redsys-universal`, `paytef-universal` y `paycomet-universal`. El paquete oficial
de cada SDK solo tiene que implementar `VendorTerminalDriver` y registrarlo con
`ServiceLoader`; el backend y APP VENTA no cambian al variar el modelo físico.

Un driver LIVE debe devolver `certifiedForLivePayments=true`. Sin esa señal, o
si hay dos drivers compatibles en `AUTO`, el adaptador no anuncia LIVE y no
enruta ninguna operación. La señal solo se establece en un paquete que haya
superado la homologación descrita en `payment-terminal-homologation.md`.

## Pruebas mínimas por plugin y modelo

- Detección del terminal y pérdida de conexión.
- Aprobación, rechazo y cancelación por el usuario.
- Duplicación de la misma clave de idempotencia.
- Corte de red antes y después de autorizar.
- Consulta y recuperación sin segundo cargo.
- Anulación y devolución total/parcial.
- Recibo, error de impresora y reimpresión.
- Cierre, conciliación y reinicio del servicio.
- Verificación de que logs y respuestas no contienen datos de tarjeta.

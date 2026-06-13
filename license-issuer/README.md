# TPV ERP License Issuer

Aplicación de escritorio aislada para emitir licencias vinculadas a una instalación.
Usa Java 25, Spring Boot 4.0.6 y Swing; no necesita JavaFX ni un servidor web.

## Requisitos y ejecución

- JDK 25.
- Acceso a Maven Central la primera vez que se usa el Wrapper.

En Windows:

```powershell
.\mvnw.cmd clean package
java -jar target\license-issuer-0.1.0-SNAPSHOT.jar
```

En Linux o macOS:

```sh
./mvnw clean package
java -jar target/license-issuer-0.1.0-SNAPSHOT.jar
```

El formulario solicita:

- JSON de instalación.
- Ruta del PKCS#12 del emisor. Si no existe, se crea con RSA de 3072 bits.
- Contraseña del PKCS#12.
- Empresa, tienda, fechas inclusivas de vigencia y cupos de terminales/usuarios.
- Ruta de salida de la licencia JSON.

El servicio reutilizable es `LicenseIssuanceService`, desacoplado de Swing.

## Solicitud de instalación

La clave pública debe ser RSA en PEM/X.509:

```json
{
  "id": "inst-001",
  "reference": "SHOP-LPA-01",
  "publicKey": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"
}
```

## Formato de licencia v1

El JSON exportado declara la versión y algoritmos empleados:

- Payload: AES-256-GCM con nonce aleatorio de 96 bits y etiqueta de 128 bits.
- Clave AES: RSA-OAEP con SHA-256 y MGF1-SHA-256, envuelta para la instalación.
- Sobre: RSA-PSS con SHA-256, MGF1-SHA-256 y salt de 32 bytes.
- AAD: versión de contexto, id y referencia de instalación.
- `issuerKeyId`: SHA-256 de la clave pública del emisor.

La firma cubre todos los campos del sobre excepto `signature`. El payload incluye
identidad de instalación, datos comerciales, vigencia, cupos y fecha UTC de emisión.

## Uso seguro

- No guardes contraseñas, claves privadas ni PKCS#12 en Git.
- Conserva el PKCS#12 en almacenamiento cifrado y con copia de seguridad offline.
- Usa una contraseña larga y única; la aplicación la limpia del formulario tras cada uso.
- Distribuye por un canal independiente la clave pública del emisor que verificará licencias.
- Verifica la solicitud de instalación por un canal autenticado antes de emitir.
- No reutilices licencias ni edites su JSON: cualquier cambio invalida la firma.
- Limita el acceso a esta herramienta y registra externamente quién aprueba cada emisión.
- Si se compromete la clave emisora, rota la identidad y revoca su `issuerKeyId`.

Este repositorio no incluye claves ni contraseñas reales.

## Pruebas

```powershell
.\mvnw.cmd test
```

Las pruebas cubren validaciones, parseo RSA/PEM, creación/carga protegida de PKCS#12,
cifrado, envoltura, firma, detección de manipulación y exportación end-to-end.
Al emitir una licencia, la aplicacion crea o reutiliza la identidad privada PKCS#12 y exporta
`license-issuer-public.pem` en la misma carpeta. La clave PEM se instala en el servidor TPV
y se configura mediante `TPV_LICENSE_ISSUER_PUBLIC_KEY_FILE`; el archivo PKCS#12 nunca se
copia al servidor.

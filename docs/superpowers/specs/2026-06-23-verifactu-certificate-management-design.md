# Gestion Segura Del Certificado VERI*FACTU

## Objetivo

Gestionar desde el ERP el certificado cliente utilizado para comunicarse con
AEAT, sin almacenar la contrasena original del PKCS#12 y sin incluir claves
privadas en la base de datos ni en los backups.

## Alcance

Cada empresa tendra como maximo un certificado activo. Solo `ADMIN` podra:

- Importar el primer certificado.
- Sustituir el certificado activo.
- Consultar sus metadatos y estado.
- Eliminar el certificado activo cuando la normativa y el estado operativo lo
  permitan.

No se implementara comprobacion de revocacion mediante OCSP o CRL. Se
comprobaran localmente integridad, clave privada, identidad y vigencia.

## Importacion

La API recibira un archivo PKCS#12 y su contrasena mediante una peticion
multipart. El archivo tendra un limite configurable, inicialmente 10 MB.

Antes de escribir datos se validara:

- Que el contenedor PKCS#12 pueda abrirse con la contrasena indicada.
- Que contenga exactamente una identidad utilizable con certificado X.509 y
  clave privada.
- Que la clave privada corresponda al certificado.
- Que el certificado este vigente en el instante de importacion.
- Que pueda extraerse el NIF del titular.
- Que el NIF coincida exactamente con el NIF normalizado de la empresa.

Un certificado caducado, todavia no valido, sin NIF detectable o destinado a
otra empresa sera rechazado. Si se trataba de una sustitucion, el certificado
activo anterior permanecera intacto.

La contrasena recibida se mantendra como `char[]`, se limpiara de memoria al
finalizar y nunca se almacenara ni registrara.

## Almacenamiento

Tras validar el PKCS#12 se extraeran:

- La clave privada en formato PKCS#8.
- El certificado publico X.509 y su cadena publica, si existe.

La clave privada se cifrara con Windows DPAPI usando alcance de equipo local.
Se guardara en un directorio privado del servidor organizado por empresa e
identificador de certificado. No sera trasladable a otro equipo.

PostgreSQL guardara solamente:

- Empresa e identificador interno.
- Estado `ACTIVO`, `ANTERIOR` o `ELIMINADO`.
- Sujeto, emisor, numero de serie y NIF.
- Fechas de validez, importacion, sustitucion y eliminacion.
- Huella SHA-256 del certificado publico.
- Ruta relativa opaca del secreto local.
- Usuario que realizo cada operacion.

El certificado publico podra almacenarse en DER para diagnostico y
verificacion. La clave privada y la ruta absoluta nunca se devolveran por API.

## Uso En Envios

El cliente mTLS reconstruira en memoria un `KeyStore` temporal usando el
certificado publico y la clave PKCS#8 descifrada con DPAPI. Utilizara una
contrasena efimera generada para ese `KeyStore`, que se limpiara despues de
crear el cliente HTTP.

La configuracion de endpoint y sistema seguira procediendo de la configuracion
del ERP. Se eliminaran como requisitos operativos la ruta y contrasena del
certificado en variables de entorno.

Si no existe un certificado activo utilizable, los registros fiscales
permaneceran pendientes. Las ventas no se bloquearan.

## Sustitucion Y Retencion

La sustitucion sera atomica desde el punto de vista del ERP:

1. Validar completamente el nuevo PKCS#12.
2. Escribir y comprobar el nuevo secreto DPAPI en una ruta temporal.
3. Publicar el archivo definitivo.
4. Marcar el nuevo certificado como `ACTIVO`.
5. Marcar el certificado activo anterior como `ANTERIOR`.

Si falla cualquier paso anterior a la confirmacion, se eliminara el archivo
temporal y se conservara el certificado activo existente.

Solo se conservara la clave privada del certificado inmediatamente anterior.
Cuando haya una nueva sustitucion, cualquier secreto de certificados mas
antiguos se eliminara y sus metadatos pasaran a `ELIMINADO`.

El certificado anterior no podra reactivarse desde el ERP. Su secreto se
conservara durante un ano desde la sustitucion.

Una tarea mensual eliminara automaticamente ese secreto al cumplir un ano. Los
metadatos publicos y la auditoria se conservaran permanentemente.

## Avisos Y Estado

El estado administrativo mostrara:

- Si existe certificado activo.
- Titular, emisor, numero de serie y huella publica.
- Inicio y fin de vigencia.
- Dias restantes.
- Estado `VALIDO`, `PROXIMO_A_CADUCAR`, `CADUCADO`, `TODAVIA_NO_VALIDO` o
  `NO_CONFIGURADO`.

Desde 30 dias antes de caducar se generara un aviso diario para `ADMIN`. Si el
certificado caduca posteriormente, el aviso permanecera y los envios quedaran
pendientes sin impedir nuevas ventas.

No se mostraran alertas persistentes por fallos generales de red.

## Seguridad Y Auditoria

Se auditaran importacion, sustitucion, eliminacion manual y purga automatica,
incluyendo usuario, empresa, fecha y metadatos publicos antes/despues. Nunca se
incluiran contrasenas, clave privada, contenido PKCS#8 ni ruta absoluta.

El directorio de secretos se excluira expresamente de backups. La restauracion
en otro servidor dejara los envios pendientes hasta que `ADMIN` importe un
nuevo certificado para esa instalacion.

Los errores y avisos nuevos se centralizaran en los catalogos de espanol,
ingles y chino.

## API

Se anadiran operaciones bajo `/api/v1/verifactu/admin/certificates`:

- Consultar estado y metadatos del activo y del anterior.
- Importar o sustituir mediante multipart.
- Eliminar el certificado activo con confirmacion administrativa.

Todas las operaciones exigiran rol `ADMIN`, sesion y terminal validos, empresa
actual y licencia aplicable.

## Pruebas

Se cubriran como minimo:

- PKCS#12 correcto, contrasena incorrecta y ausencia de clave privada.
- NIF coincidente, ausente y perteneciente a otra empresa.
- Certificados caducados y todavia no validos.
- Extraccion PKCS#8, proteccion DPAPI y reconstruccion del `KeyStore` en memoria.
- Sustitucion atomica y conservacion del activo ante fallo.
- Retencion exclusiva del certificado anterior.
- Purga mensual al cumplir un ano.
- Ausencia de secretos en respuestas, logs, auditoria y backups.
- Envios pendientes sin bloqueo de ventas cuando el certificado no sea usable.
- Permiso exclusivo de `ADMIN` y mensajes en los tres idiomas.

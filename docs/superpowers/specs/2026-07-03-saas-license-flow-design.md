# SaaS License Flow Design

## Objetivo

Redisenar el flujo de licencia para que el SaaS sea la autoridad principal de empresa, tienda, contrato y cupos, manteniendo en el backend local una licencia cacheada para trabajar offline.

La instalacion local nueva no pedira datos fiscales ni datos de tienda al usuario. Solo generara identidad tecnica local y esperara vinculacion con SaaS.

## Decision Principal

La licencia local inicial no se elimina completa. Se conserva como infraestructura de seguridad y funcionamiento offline, pero deja de ser el flujo comercial principal.

Se quita como flujo principal: generar un archivo manualmente con `license-issuer` y activarlo en local. Ese mecanismo queda detras de SaaS o como herramienta excepcional de soporte/emergencia.

El flujo principal pasa a ser:

- SaaS crea o conoce la empresa y la tienda.
- SaaS genera un codigo de vinculacion.
- Backend local se vincula usando ese codigo.
- SaaS devuelve una licencia/snapshot firmado.
- Backend local guarda ese snapshot y lo usa para operar cuando no haya conexion.

## Estados De Instalacion

### SIN_VINCULAR

Estado inicial de una instalacion nueva.

Existe:

- `instalacion`
- identidad criptografica local
- usuario protegido `ADMIN`

No existe todavia como dato real:

- empresa fiscal definitiva
- tienda definitiva
- `codigoTienda`
- licencia activa

Permitido:

- iniciar sesion como `ADMIN`
- configurar conexion SaaS
- vincular instalacion
- consultar estado tecnico
- backup/exportacion si aplica

Bloqueado:

- ventas
- documentos fiscales
- productos reales
- terminales operativos

### VINCULADA

Estado normal.

Existe:

- empresa oficial recibida desde SaaS
- tienda oficial recibida desde SaaS
- `codigoTienda`
- licencia local activa
- token local protegido para validar contra SaaS

Permitido:

- operativa normal segun licencia
- validacion periodica contra SaaS
- funcionamiento offline dentro del margen permitido

### OFFLINE

La instalacion estaba vinculada, pero no puede validar temporalmente contra SaaS.

Permitido:

- seguir operando con la ultima licencia valida
- mostrar aviso si supera el umbral configurado

Bloqueado:

- operativa de escritura cuando se supere el margen offline maximo

### BLOQUEADA

SaaS rechazo o bloqueo la licencia.

Permitido:

- consulta
- exportacion
- impresion
- backups
- licenciamiento/vinculacion

Bloqueado:

- nuevas ventas
- confirmacion de documentos
- cambios comerciales con impacto operativo

## Identidad Criptografica Local

Al arrancar por primera vez, el backend local genera una pareja de claves:

- clave privada
- clave publica

La clave privada:

- nunca sale del servidor local
- se guarda protegida con Windows DPAPI
- identifica tecnicamente a esa instalacion

La clave publica:

- se puede enviar al SaaS
- permite que SaaS asocie la instalacion local con un servidor concreto
- permite verificar futuras comunicaciones o emitir licencias destinadas a esa instalacion

El usuario no copia ni introduce claves manualmente.

## Flujo De Vinculacion Nueva

1. En SaaS se crea empresa y tienda.
2. SaaS genera un codigo de vinculacion temporal.
3. En el backend local, `ADMIN` introduce el codigo de vinculacion.
4. Backend local envia a SaaS:
   - codigo de vinculacion
   - `instalacionId`
   - referencia de instalacion
   - clave publica local
   - version del backend
5. SaaS valida el codigo.
6. SaaS responde con:
   - id SaaS de empresa
   - id SaaS de tienda
   - NIF
   - razon social
   - nombre comercial si existe
   - direccion fiscal si existe
   - `codigoTienda`
   - regimen fiscal: `IVA` o `IGIC`
   - tipo contribuyente: `SOCIEDAD` o `AUTONOMO`
   - cupos Windows
   - cupos PDA
   - modulos activos
   - obligaciones legales activas, por ejemplo VERI*FACTU
   - fecha de validez
   - estado SaaS
   - token de instalacion
   - licencia/snapshot firmado
7. Backend local crea o actualiza:
   - `empresa`
   - `tienda`
   - `licencia`
   - configuracion fiscal inicial
8. Backend local guarda el token SaaS protegido con DPAPI.
9. Backend local queda en estado `VINCULADA`.

## Flujo De Vinculacion De Instalacion Existente

Si ya existe empresa/tienda local real, el backend tambien puede enviar:

- NIF local
- razon social local
- id local de tienda
- `codigoTienda` local

SaaS debe comparar los datos.

Si coinciden, se vincula.

Si no coinciden, debe devolver rechazo claro:

- NIF diferente
- tienda diferente
- codigo de tienda ocupado
- instalacion ya vinculada a otra tienda

No se debe sobrescribir una empresa real local sin confirmacion administrativa explicita.

## Licencia Local Como Cache

La tabla `licencia` sigue existiendo, pero conceptualmente pasa a ser una copia local del estado SaaS.

Debe conservar:

- referencia
- tienda
- instalacion
- validez
- cupos Windows/PDA
- NIF
- tipo contribuyente
- regimen fiscal
- hash
- snapshot original
- fecha de importacion/vinculacion
- ultima validacion SaaS
- estado SaaS
- historial

La licencia local no es la autoridad comercial definitiva. Es una prueba local firmada para seguir trabajando cuando SaaS no este disponible.

## Validacion Periodica

El backend local validara contra SaaS:

- al arrancar
- periodicamente en segundo plano
- cuando el admin lo solicite manualmente
- antes de operaciones criticas si la ultima validacion esta fuera del margen configurado

La validacion envia:

- `instalacionId`
- referencia de instalacion
- id local de tienda
- referencia de licencia
- hash de licencia
- version del backend

SaaS responde:

- estado: `VALIDA`, `BLOQUEADA_MANUAL`, `CADUCADA`, `REQUIERE_ACTUALIZACION`
- nueva fecha de validez
- nuevos cupos si cambiaron
- modulos activos si cambiaron
- obligaciones legales si cambiaron

## Margen Offline

Regla recomendada:

- Aviso tras 7 dias sin validacion SaaS si la licencia local esta vencida o cerca de vencer.
- Bloqueo de escrituras tras 30 dias sin validacion SaaS cuando no exista licencia valida renovada.

El margen offline evita que una caida de internet bloquee la tienda, pero impide que una licencia local funcione indefinidamente sin SaaS.

## Que Se Mantiene Del Sistema Inicial

Mantener:

- identidad de instalacion
- clave privada local protegida
- clave publica local
- entidad `License`
- historial de licencias
- cupos Windows/PDA
- NIF y tipo contribuyente
- IVA/IGIC
- validacion de estado de licencia antes de escrituras
- modo offline
- bloqueo administrativo

## Que Se Depreca

Deprecar como flujo principal:

- activacion manual por archivo de licencia
- `license-issuer` como herramienta comercial principal
- formulario manual de datos de licencia

Se puede conservar como herramienta secundaria para:

- soporte tecnico
- instalaciones sin internet
- pruebas
- emergencia

## Cambios De API Recomendados

Mantener:

- `GET /api/v1/licenses/history`
- `POST /api/v1/licenses/link-saas`

Convertir a secundario o modo soporte:

- `POST /api/v1/licenses/preview`
- `POST /api/v1/licenses/activate`

Anadir o consolidar:

- `GET /api/v1/licenses/status`
- `POST /api/v1/licenses/validate-saas`
- `POST /api/v1/licenses/unlink-saas` solo para soporte/admin avanzado

## Reglas De Seguridad

- Solo `ADMIN` puede vincular o desvincular SaaS.
- La clave privada local nunca se devuelve por API.
- El token SaaS se guarda protegido con DPAPI.
- El backend local no debe confiar solo en campos editables de base de datos.
- Las escrituras deben validar licencia, terminal, usuario, tienda y permisos.
- Si SaaS bloquea la licencia, el bloqueo debe aplicarse aunque la fecha local no haya vencido.

## Datos Oficiales

En instalacion nueva, estos datos vienen desde SaaS:

- NIF
- razon social
- empresa
- tienda
- `codigoTienda`
- regimen fiscal
- tipo contribuyente

En instalacion existente, estos datos locales solo sirven para validacion y conciliacion.

## Impacto Sobre Demo

La demo local puede mantenerse, pero no debe crear datos fiscales definitivos.

Opciones recomendadas:

- Demo tecnica antes de vincular: permite explorar configuracion, sin ventas reales.
- Demo completa solo si SaaS entrega una licencia demo temporal.

La limpieza de datos demo debe ocurrir al activar la primera vinculacion SaaS real, conservando:

- instalacion
- identidad criptografica
- `ADMIN`
- licencia SaaS activa

## Pendientes De Implementacion

- Definir si el endpoint de archivo local queda oculto en produccion o protegido por configuracion.
- Ampliar respuesta SaaS con modulos y obligaciones legales.
- Separar claramente `SIN_VINCULAR`, `VINCULADA`, `OFFLINE` y `BLOQUEADA`.
- Evitar que una instalacion nueva requiera empresa/tienda local antes de `link-saas`.
- Adaptar bootstrap para crear solo datos minimos antes de vincular.
- Adaptar tests de licencia para cubrir instalacion nueva sin empresa/tienda.

# Diseno De Subsanaciones VERI*FACTU

## Objetivo

Permitir corregir datos administrativos de un registro VERI*FACTU defectuoso
sin modificar el registro fiscal original ni alterar la realidad economica de
la factura.

## Registros Admitidos

Solo podran subsanarse registros de alta cuyo estado sea `RECHAZADO`,
`DEFECTUOSO` o `ACEPTADO_CON_ERRORES`. No se admitira una subsanacion de un
registro de anulacion ni de un registro ya subsanado correctamente.

Podran ejecutar la operacion `ADMIN` y los usuarios con el permiso
`GESTION_VENTAS`.

## Datos Corregibles

La solicitud podra corregir:

- NIF o identificacion fiscal del destinatario.
- Nombre o razon social del destinatario.
- Descripcion administrativa de la operacion.

No podran modificarse:

- Numero ni fecha de expedicion.
- Productos, cantidades o precios.
- Bases imponibles, tipos fiscales o cuotas.
- Descuentos ni importe total.
- Tipo de factura.

Una correccion economica requerira una factura o ticket rectificativo.

## Solicitud Y Validacion

La API exigira un motivo de subsanacion no vacio. Tambien conservara el
usuario, la fecha y el contexto organizativo que ejecutaron la correccion.

Antes de crear el registro se validaran el permiso, la empresa, la tienda, el
estado del registro original, el NIF corregido y la invariabilidad de todos los
datos economicos.

## Registro Fiscal

La subsanacion creara un nuevo `RegistroAlta` inmutable con:

- `Subsanacion=S`.
- `RechazoPrevio=S` cuando el estado anterior sea `RECHAZADO` por AEAT.
- `RechazoPrevio=N` para `DEFECTUOSO` o `ACEPTADO_CON_ERRORES`.
- La misma identidad de factura: NIF emisor, numero y fecha.
- Una copia fiscal completa con las correcciones administrativas autorizadas.
- Un nuevo numero de secuencia, huella y referencia al registro anterior de la
  cadena.

Se guardara una relacion `SUBSANA` desde el nuevo registro hacia el registro
defectuoso. El original, sus XML, respuestas e intentos permaneceran intactos.

## Envio Y Estados

El nuevo registro se insertara como `PENDIENTE` y se enviara inmediatamente
despues de confirmar la transaccion. Los fallos de red o de AEAT seguiran el
mecanismo general de reintentos horarios y nunca bloquearan ventas nuevas.

Cuando AEAT acepte la subsanacion, el registro nuevo pasara a `ACEPTADO` y el
registro original se mostrara como `SUBSANADO` mediante su estado mutable de
envio. Si la subsanacion es rechazada, ambos registros y todos sus errores
continuaran visibles en `Facturas defectuosas`.

## API

Se anadira una operacion explicita bajo `/api/v1/verifactu/defective-records`
que recibira el identificador del registro, el motivo obligatorio y solo los
campos administrativos corregibles. La respuesta devolvera la identidad y el
estado inicial del nuevo registro.

La API rechazara campos economicos desconocidos o intentos de modificar datos
no autorizados, en lugar de ignorarlos silenciosamente.

Los errores y avisos nuevos utilizaran el catalogo centralizado de mensajes en
espanol, ingles y chino segun el idioma del usuario.

## Pruebas

Se cubriran como minimo:

- Creacion de una subsanacion vinculada sin modificar el original.
- Rechazo de registros con estado no subsanable.
- Motivo obligatorio y permisos de acceso.
- Invariabilidad de numero, fecha, importes, impuestos y lineas.
- Generacion de `Subsanacion` y `RechazoPrevio` en el orden exigido por XSD.
- Validacion del XML contra los esquemas oficiales incluidos en el proyecto.
- Encadenamiento, huella, estado pendiente y envio posterior a la transaccion.
- Actualizacion a `SUBSANADO` solo tras aceptacion de AEAT.

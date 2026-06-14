# Diseño Del Módulo VERI*FACTU

## Objetivo

Incorporar al TPV ERP un módulo VERI*FACTU para las facturas expedidas por la
empresa, conforme al Real Decreto 1007/2023, la Orden HAC/1177/2024 y las
especificaciones técnicas vigentes de la AEAT.

El módulo se limitará a la modalidad VERI*FACTU. No implementará inicialmente
la modalidad de conservación local sin remisión, que requiere controles,
firma y registro de eventos adicionales.

La conformidad de cada versión fiscal deberá verificarse nuevamente contra los
esquemas, servicios y documentación oficial vigentes. El uso de bibliotecas o
proyectos abiertos servirá únicamente como referencia técnica.

## Alcance Fiscal

Se generarán y remitirán registros XML para:

- Tickets o facturas simplificadas de venta.
- Facturas completas de venta.
- Tickets rectificativos, identificados ante AEAT como `R5`.
- Facturas rectificativas de venta.
- Registros de anulación.
- Registros de subsanación.

Quedan fuera:

- Facturas de compra recibidas.
- Albaranes.
- Presupuestos.
- PDF, impresión u otra representación visual del documento.

Cuando un tercero o destinatario expida materialmente una factura por cuenta
de la empresa, el registro se tratará como facturación expedida por esta si así
corresponde fiscalmente.

## Licencia Y Obligación

La licencia firmada incorporará:

- NIF normalizado del titular.
- Tipo de contribuyente: `SOCIEDAD` o `AUTONOMO`.
- Datos del productor.
- Identificación y versión fiscal del software.
- Declaración responsable aplicable.

El ERP comparará el NIF firmado con `empresa.tax_id` y rechazará una licencia
destinada a otro titular. Estos datos no podrán editarse desde el ERP.

La activación automática será:

- `SOCIEDAD`: 1 de enero de 2027.
- `AUTONOMO`: 1 de julio de 2027.

Antes de la fecha obligatoria, `ADMIN` podrá activar y desactivar el entorno de
pruebas. La activación real voluntaria podrá deshacerse solamente mientras no
se haya realizado ninguna remisión real. Una vez iniciadas las remisiones, se
respetará la permanencia legal aplicable. Al alcanzar la fecha obligatoria, la
activación será automática e irreversible.

La falta de certificado no impedirá la activación legal ni bloqueará ventas.
Los registros quedarán pendientes.

Las facturas anteriores a la activación no se remitirán. La primera cadena
fiscal comenzará con el primer documento expedido después de activarla.

## Empresa, Tiendas Y Series

Cada tienda tendrá un `codigoTienda` de tres dígitos:

- Se asignará automáticamente por empresa: `001`, `002`, `003`, etc.
- Será único dentro de la empresa.
- No podrá modificarse ni reutilizarse.

Las series serán:

- Ticket: `001-YYMMDD-NNNNNN`.
- Ticket rectificativo: `TR-001-YY-NNNNNN`.
- Factura de venta: `FV-001-YY-NNNNNN`.
- Factura rectificativa de venta: `FRV-001-YY-NNNNNN`.

El bloque `001` identifica la tienda. Cada tienda tendrá contadores propios,
coordinados por el servidor. Las series existentes continuarán hasta activar
VERI*FACTU; desde ese momento se utilizarán las series con código de tienda.
Los documentos históricos conservarán su numeración.

Los distintos TPV de una tienda no aparecerán en el número fiscal.

## Arquitectura

El dominio `verifactu` se mantendrá separado del dominio comercial:

- `FiscalRecord`: registro inmutable de alta, anulación o subsanación.
- `FiscalChain`: encadenamiento SHA-256 por empresa e instalación.
- `FiscalXmlGenerator`: XML versionado según los esquemas de AEAT.
- `FiscalOutbox`: cola persistente y transaccional.
- `AeatClient`: comunicación con AEAT mediante certificado.
- `FiscalDispatcher`: envío inmediato y reintentos programados.
- `FiscalResponse`: respuestas completas e historial de intentos.
- `DefectiveInvoiceService`: consulta y subsanación de registros defectuosos.
- `FiscalCertificateService`: custodia del certificado mediante DPAPI.
- `FiscalClockService`: hora central y comprobación NTP.
- `ResponsibleDeclaration`: declaración responsable por versión fiscal.

Existirá una única cadena fiscal por empresa e instalación del ERP. Todas las
tiendas y terminales de esa empresa participarán en ella. Empresas diferentes
tendrán cadenas independientes.

La asignación del registro anterior, el cálculo de la huella y la inserción del
nuevo registro se serializarán en PostgreSQL para evitar bifurcaciones ante
confirmaciones concurrentes.

## Confirmación De Documentos

La confirmación de un documento de venta realizará en una sola transacción:

1. Validación fiscal.
2. Asignación de número y serie.
3. Congelación definitiva del contenido fiscal.
4. Creación del registro de facturación.
5. Encadenamiento y cálculo de huella SHA-256.
6. Generación del XML y datos del QR.
7. Inserción en la cola de envío.
8. Confirmación del documento y operaciones comerciales asociadas.

La comunicación con AEAT ocurrirá después de confirmar la transacción. Una
caída de AEAT o de Internet nunca bloqueará la venta.

Después de confirmar, ningún usuario, incluido `ADMIN`, podrá modificar el
contenido fiscal. Se eliminará la edición administrativa excepcional de
tickets confirmados.

## Correcciones

El ERP elegirá el procedimiento según el motivo:

- Emisión duplicada u operación inexistente: registro de anulación.
- Devolución total o parcial: ticket rectificativo `R5`.
- Error en los datos remitidos: registro de subsanación.
- Corrección de una factura real: factura rectificativa.

Los tickets rectificativos admitirán:

- Rectificación por diferencias.
- Rectificación por sustitución.

Cada rectificativa referenciará inequívocamente el documento original, tendrá
su propio número y generará un nuevo registro encadenado. El documento original
nunca se borrará ni se reutilizará.

La anulación fiscal no borrará el ticket. Los efectos sobre stock y pagos se
realizarán mediante movimientos compensatorios.

## Envío Y Estados

Tras confirmar:

- Se intentará un envío inmediato.
- Los fallos de comunicación se reintentarán cada hora.
- La recuperación de conectividad no adelantará el siguiente ciclo.
- Los pendientes se enviarán en orden de generación.
- Cada petición contendrá hasta 1.000 registros cuando el protocolo lo admita.

Estados visibles:

- `PENDIENTE`
- `ENVIANDO`
- `ACEPTADO`
- `ACEPTADO_CON_ERRORES`
- `RECHAZADO`
- `SUBSANADO`
- `ANULADO`

Los rechazos por NIF, datos, cálculos, duplicados o encadenamiento aparecerán
en **Facturas defectuosas**. No bloquearán ventas ni mostrarán alertas
persistentes. Podrán gestionarlos `ADMIN` y usuarios con un permiso específico.

Se conservarán el error original, todos los intentos, las subsanaciones y todas
las respuestas posteriores.

Solo la ausencia, caducidad, revocación o invalidez temporal del certificado
generará alertas persistentes. Un cambio normativo incompatible avisará
únicamente a `ADMIN`, permitirá seguir vendiendo y dejará envíos pendientes.

## Certificado

Habrá un certificado por empresa.

- Solo `ADMIN` podrá importarlo, sustituirlo o eliminarlo.
- La contraseña se solicitará únicamente durante la importación.
- La clave privada se cifrará mediante Windows DPAPI.
- La contraseña no se almacenará.
- La clave privada nunca se incluirá en backups.
- La sustitución quedará auditada.

Desde 30 días antes de caducar se mostrará diariamente un aviso a `ADMIN`.
Las ventas continuarán incluso si el certificado ya no es utilizable.

## QR E Impresión

Tickets, facturas y rectificativas de venta incluirán:

- QR conforme a la especificación vigente.
- Leyenda VERI*FACTU exigida.

El QR se generará localmente al confirmar. No se esperará la respuesta de AEAT
para imprimir.

Las reimpresiones mantendrán exactamente el mismo contenido fiscal y QR, sin
añadir la palabra `COPIA`. No generarán otro registro fiscal. La reimpresión sí
quedará registrada en la auditoría interna.

## Hora Y Zona

Todos los TPV utilizarán la hora asignada por el backend. Ninguna fecha u hora
fiscal procederá del equipo cliente.

El servidor consultará NTP:

- Al arrancar.
- Cada 24 horas.

Si la diferencia supera cinco minutos:

- Todos los usuarios verán un aviso.
- Las ventas y los envíos continuarán.
- El ERP no modificará automáticamente la hora de Windows.
- La comprobación quedará auditada.

Cada tienda utilizará `Atlantic/Canary` o `Europe/Madrid`, inicialmente
deducida de su provincia. Después de emitir el primer documento fiscal, su zona
horaria será inmutable. Se conservarán tanto el instante UTC como la zona
aplicada.

## Entorno De Pruebas

Existirá un entorno AEAT de pruebas completamente separado:

- Documentos y numeración ficticios.
- Cadena independiente de la real.
- Sin stock, pagos ni efectos comerciales.
- Registros eliminables por `ADMIN`.

Antes de la primera activación real se exigirá una prueba válida de conexión y
certificado.

## Persistencia E Inmutabilidad

Se añadirán tablas o estructuras para:

- Configuración y activación por empresa.
- Certificado cifrado y metadatos públicos.
- Cadena y registros fiscales.
- XML original y versión del esquema.
- Cola, intentos y respuestas.
- Relaciones de anulación, rectificación y subsanación.
- Declaraciones responsables y versiones fiscales.
- Comprobaciones NTP.

Cada registro guardará:

- Versión de esquema AEAT.
- Versión fiscal del ERP.
- Algoritmo y versión de huella.
- XML exacto enviado.
- Huella propia y referencia a la anterior.
- Identidad de empresa, instalación y sistema.
- Marcas temporales y zona.

PostgreSQL impedirá modificaciones o eliminaciones ordinarias de documentos
fiscales y registros encadenados mediante reglas de dominio y triggers.

## Conservación Y Purga

Los registros y respuestas se conservarán durante el plazo fiscal mínimo
aplicable, inicialmente cuatro años, ampliado cuando exista interrupción de
prescripción, inspección o procedimiento abierto.

No habrá eliminación automática. Solo `ADMIN` podrá ejecutar una purga
explícita y auditada después de validar el vencimiento legal y la ausencia de
procedimientos abiertos. No se eliminará información si la operación rompe la
verificabilidad de datos todavía conservados.

## Backups Y Restauración

Los backups incluirán:

- XML fiscales.
- Respuestas AEAT.
- Huellas y encadenamiento.
- Certificados públicos.
- Declaraciones y manifiestos.

Excluirán siempre la clave privada.

Después de restaurar un backup, el ERP reconciliará automáticamente registros
y respuestas con AEAT antes de reanudar envíos. Las ventas seguirán disponibles
durante la reconciliación.

## Exportación Para Inspección

`ADMIN` podrá generar un paquete firmado que contenga:

- Registros XML.
- Respuestas de AEAT.
- Historial de intentos.
- Huellas y referencias de cadena.
- Certificados públicos.
- Declaraciones responsables.
- Manifiesto de integridad.

La exportación no alterará los registros ni su estado.

## Declaración Responsable

Cada versión fiscal publicada tendrá su propia declaración responsable. Sus
datos se configurarán en `license-issuer`, se firmarán y se distribuirán con la
licencia o versión fiscal correspondiente.

El ERP verificará la firma, mostrará la declaración aplicable y asociará cada
registro con la versión que lo generó. Los datos del productor y la declaración
no serán editables.

## Pruebas

Se cubrirán como mínimo:

- XML contra XSD oficiales.
- Cálculo de huella y cadena concurrente única.
- Numeración por tienda sin duplicados.
- QR y leyendas.
- Altas, anulaciones, `R5`, rectificativas y subsanaciones.
- Respuestas aceptadas, con errores y rechazadas.
- Caídas, reintentos horarios y lotes máximos.
- Certificados ausentes, caducados y revocados.
- Activación voluntaria y automática.
- Inmutabilidad en API, JPA y PostgreSQL.
- Entorno de pruebas separado.
- Backups, restauración y reconciliación.
- Paquetes de inspección.
- Versionado de esquema y declaración responsable.
- Hora central, zonas y alertas NTP.

Antes de producción se ejecutarán pruebas contra los servicios oficiales de
pruebas de AEAT. Ninguna prueba interna ni proyecto abierto se considerará por
sí solo una certificación legal.

## Cambios Sobre Decisiones Anteriores

- Se elimina la posibilidad de que `ADMIN` edite tickets confirmados.
- Se crean tickets rectificativos y registros de subsanación.
- Las numeraciones fiscales incorporan el código de tienda.
- La cadena es única por empresa e instalación, no por tienda.
- El tipo de contribuyente y el NIF pasan a estar firmados en la licencia.

## Referencias

Fuentes normativas y técnicas principales:

- Real Decreto 1007/2023 y texto consolidado.
- Real Decreto 1619/2012, Reglamento de facturación.
- Orden HAC/1177/2024.
- Esquemas XSD, WSDL, documentación técnica y preguntas frecuentes de AEAT.

Referencias abiertas evaluadas, sin asumir que garanticen conformidad:

- `invopop/gobl.verifactu`, licencia Apache-2.0.
- `josemmo/verifactu-php`, licencia MIT.
- `mdiago/VeriFactu`, licencia AGPL-3.0; no se incorporará directamente.
- `squareetlabs/verifactu-sdk`, Java sin licencia claramente declarada; no se
  copiará ni se tratará como dependencia.

Los esquemas y servicios oficiales prevalecerán sobre Taxdown, documentación
secundaria y cualquier implementación abierta.

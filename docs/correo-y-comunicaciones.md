# Correo y comunicaciones de esPOS

Acceso: **Gestión → Configuración → Correo y comunicaciones**, con usuario ADMIN.
La interfaz está traducida a español, inglés y chino simplificado.

## Qué puede hacer la tienda

1. **Cuenta**: conectar un proveedor autorizado o guardar los datos SMTP, indicar el remitente y solicitar una prueba a una dirección propia. Guardar una cuenta no confirma la entrega del correo.
2. **Plantillas**: editar asunto, título, texto, logo HTTPS, color y botón. Variables: `{name}`, `{storeName}` y `{memberId}`. El número queda vacío para clientes que no son miembros. Vista previa aislada, tamaño móvil y prueba explícita. No se admite HTML arbitrario: los campos se escapan al generar el mensaje. Algunos lectores bloquean imágenes remotas.
3. **Automáticos**: activar la bienvenida, elegir una plantilla guardada o el texto existente y seleccionar QR/código de barras. La bienvenida incluye el código real, como imagen y adjunto PNG. Reactivar un miembro no genera otra bienvenida automáticamente. El reenvío explícito del carnet pide confirmación.
4. **Campañas**: crear borrador para todos los clientes o una selección, revisar destinatarios y exclusiones y confirmar el envío inmediato o programado. La confirmación incorpora una huella de la vista previa: si cambian el contenido, la personalización o los destinatarios, hay que revisarla de nuevo aunque el número de correos sea el mismo. Límite de 10.000 clientes por campaña. La cancelación evita los envíos pendientes; no puede recuperar un mensaje ya presentado al proveedor.
5. **Historial**: consultar bienvenidas y campañas. Un correo aceptado por SMTP no demuestra entrega ni lectura. Un fallo inequívoco permite reintento; un resultado incierto exige revisión y no se reenvía automáticamente.

El contenido de cada envío se guarda al programarlo. Editar después una plantilla no cambia los correos que ya están en cola.

## Configuración de instalación

Los trabajadores de envío están **desactivados por defecto**. Crear plantillas y borradores no envía campañas.

| Variable | Uso |
| --- | --- |
| `TPV_MAIL_ENCRYPTION_KEY` | Clave aleatoria de 32 bytes codificada en Base64. Cifra contraseñas y tokens con AES-GCM y los vincula a la empresa. Debe conservarse fuera de la base de datos y de Git. |
| `TPV_MEMBER_CARD_DELIVERY_ENABLED` | `true` activa el trabajador de bienvenidas/carnets. |
| `TPV_MEMBER_CARD_DELIVERY_DELAY_MS` | Intervalo de bienvenidas; 60.000 ms por defecto. Lote máximo de 20. |
| `TPV_COMMUNICATIONS_DELIVERY_ENABLED` | `true` activa el trabajador de campañas. |
| `TPV_COMMUNICATIONS_PUBLIC_BASE_URL` | Origen HTTPS público estable que expone la baja; no una dirección local ni una dirección de ejemplo. |
| `TPV_COMMUNICATIONS_UNSUBSCRIBE_SECRET` | Clave aleatoria independiente de al menos 32 bytes en Base64 para firmar enlaces. Conservarla permite que los enlaces antiguos sigan funcionando. |

Las propiedades `tpv.communications.batch-size` y `tpv.communications.poll-interval-ms` permiten ajustar lote e intervalo (20 y 60.000 ms por defecto). Los límites del proveedor deben respetarse; el lote no constituye una cuota distribuida global entre varios servidores.

Las credenciales anteriores sin cifrar no se utilizan para enviar. Al guardar de nuevo la configuración con la clave disponible, se cifran. Dejar la contraseña vacía conserva la existente. No cambiar ni perder la clave sin un procedimiento de descifrado y recifrado: no hay rotación automática. Las copias de seguridad requieren custodia separada de las claves.

## Google y Microsoft

Los botones se habilitan solo cuando existen registro del proveedor, secreto de aplicación, URI de retorno y clave de cifrado. No se incluyen credenciales de fabricante en el repositorio.

| Variable | Uso |
| --- | --- |
| `TPV_MAIL_OAUTH_REDIRECT_URI` | URI exacta registrada: `https://<host>/api/v1/mail-oauth/callback`. Para desarrollo se admite `http://127.0.0.1:<puerto>/api/v1/mail-oauth/callback`. |
| `TPV_MAIL_GOOGLE_CLIENT_ID` / `TPV_MAIL_GOOGLE_CLIENT_SECRET` | Registro de aplicación web confidencial en Google. |
| `TPV_MAIL_MICROSOFT_CLIENT_ID` / `TPV_MAIL_MICROSOFT_CLIENT_SECRET` | Registro de aplicación web confidencial en Microsoft Entra, con tipos de cuenta compatibles. |

El flujo usa código de autorización, PKCE S256, estado aleatorio de un solo uso con diez minutos de vigencia y tokens cifrados. Antes de aceptar la cuenta elegida se verifica la dirección confirmada por Google o se autentica por SMTP en Microsoft, sin enviar un mensaje. Se renuevan los tokens al necesitarse, conservando el token de renovación si el proveedor no devuelve otro. La renovación se guarda en una transacción independiente para conservar el token nuevo aunque después falle el envío o se revierta la operación que lo solicitó. Un fallo requiere reconexión; no se cambia silenciosamente a contraseña SMTP.

Los dos proveedores comparten las plantillas MIME, pero utilizan transportes diferentes:

- Google usa **Gmail API** y solicita `https://www.googleapis.com/auth/gmail.send` y `https://www.googleapis.com/auth/userinfo.email`. No solicita lectura del buzón ni el permiso amplio `https://mail.google.com/`. Hay que habilitar Gmail API y cumplir la verificación de aplicaciones públicas. [Envío mediante Gmail API](https://developers.google.com/workspace/gmail/api/guides/sending), [Permisos de Google](https://developers.google.com/identity/protocols/oauth2/scopes).
- Microsoft usa **SMTP XOAUTH2** y solicita `offline_access https://outlook.office.com/SMTP.Send`. La organización debe permitir SMTP AUTH y el permiso correspondiente. [Documentación de Microsoft](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth).

Una conexión OAuth verificada sustituye la autenticación anterior y elimina la contraseña SMTP que ya no se utiliza. Una autorización fallida conserva la configuración anterior. Desconectar elimina los tokens locales e inhabilita el remitente; para volver a SMTP hay que configurarlo expresamente. No revoca el consentimiento en el portal del proveedor; esa revocación se gestiona allí si se desea.

## Bajas y despliegue

Publicar solamente los métodos GET y POST de `/api/public/communications/unsubscribe` mediante HTTPS. No es necesario publicar las APIs de administración. GET muestra la confirmación; POST realiza la baja, también compatible con `List-Unsubscribe-Post`. Las respuestas no se almacenan en caché y no revelan el correo del destinatario.

El enlace utiliza una capacidad firmada y un registro persistente, no el identificador del cliente en claro. La baja conserva el cliente y su carnet, excluye su dirección en esa empresa y cancela los mensajes comerciales pendientes. La autorización se comprueba otra vez inmediatamente antes del envío. Los cambios de consentimiento generan un registro de auditoría; no se inventa evidencia histórica para permisos anteriores.

**La URL configurada no publica por sí sola el servicio.** El operador debe proporcionar DNS, certificado, proxy/ruta y disponibilidad continua. Si el backend solo funciona en el ordenador de la tienda, al apagarlo se detienen los envíos y las bajas. Para campañas reales se necesita mantener ese servicio disponible o desplegar un servicio central con acceso a la persistencia correspondiente; este cambio no despliega infraestructura externa.

Conservar el dominio y la clave de firma mantiene válidas las bajas de mensajes ya enviados. Cambiar esos valores impide enviar mensajes pendientes con enlaces antiguos inválidos. Un servicio central distinto necesitaría sincronización de bajas; no se implementa aquí una federación con otro backend.

SPF, DKIM y DMARC se configuran en el dominio/proveedor de envío. No se modifican DNS automáticamente. No hay webhooks de rebotes, quejas o confirmaciones de entrega: el historial muestra aceptación, fallo o incertidumbre según la evidencia disponible.

## Persistencia y garantías

- V301: intentos, reservas, resultado incierto, snapshot HTML e idempotencia del reenvío de carnet.
- V302: plantillas, campañas, destinatarios, bajas, auditoría y selección de bienvenida.
- V303: conexiones OAuth y autorizaciones pendientes.
- Las consultas y operaciones administrativas se limitan a la empresa actual y requieren ADMIN + acceso a Configuración.
- Cada reserva se confirma antes del envío; SMTP se ejecuta fuera de la transacción de reserva. Un reinicio tras aceptación del proveedor puede dejar un resultado incierto. No existe garantía de entrega exactamente una vez sobre SMTP.
- Los reintentos automáticos solo cubren fallos identificados anteriores a la conexión. Los resultados ambiguos nunca se reenvían automáticamente.
- Las pruebas no utilizan cuentas reales ni envían mensajes a clientes.

## Validación reproducible

Usar exclusivamente una base desechable cuyo nombre termine en `_test`. Las pruebas PostgreSQL crean y eliminan sus propios esquemas UUID; el test completo de migración instala las extensiones necesarias en esa base de pruebas.

```powershell
$env:TPV_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:<puerto>/<base>_test'
$env:TPV_TEST_DB_USERNAME = '<usuario-de-pruebas>'
$env:TPV_TEST_DB_PASSWORD = '<clave-de-pruebas>'
cd backend
.\mvnw.cmd '-Dtest=MailOAuth*Test,MailMigrationPostgreSqlTest,MailPublicEndpointSecurityTest,Communication*Test,CompanyMailServiceTest,MailFailureClassifierTest,MailSecretCipherTest,Smtp*Test,MemberSmtp*Test,MemberCardDelivery*Test,MemberLoyaltyServiceTest' test
```

En `frontend`, ejecutar las pruebas de `MailCommunicationsScreen`, `messages.test.ts`, navegación y seguridad de escritorio, y `npm run build --workspace @tpverp/app-gestion`.

Validación realizada el 20/11/2026:

- Batería integrada final del backend: **139 pruebas**, sin fallos, errores ni omisiones. Incluye migraciones completas, persistencia JPA, contratos HTTP positivos y negativos, concurrencia, OAuth, aislamiento por empresa, consentimiento, bajas, MIME y carnets sobre PostgreSQL desechable y transportes simulados.
- Interfaz, traducciones y navegación: **42 pruebas** correctas (37 del módulo de correo). Seguridad de escritorio: **22 pruebas** correctas. Total: **64 pruebas** del frontend y escritorio.
- Compilación de Gestión correcta. Verificación en Chrome con API simulada: conserva la fecha al revisar destinatarios, envía la huella actualizada al programar y conserva el formulario al cancelar el descarte. Sin errores JavaScript.
- Comprobación posterior del arranque completo: se actualizó el manifiesto y los identificadores de versión a V303, con secuencia 46. Las 41 pruebas de contrato del manifiesto y del control de arranque pasan; se mantienen las comprobaciones de coherencia del esquema.
- Revisión visual del componente con respuestas API simuladas en escritorio y ancho móvil. No constituye una prueba de toda la aplicación en móviles ni una sesión con un proveedor real.

Evidencia local: `backend/target/mail-deep-final-validation.log`, `tmp/mail-ui-deep-tests.log`, `tmp/mail-desktop-deep-tests.log`, `tmp/mail-ui-deep-build.log` y `tmp/mail-ui-review/deep-smoke.json`.

## Regresiones cubiertas en la revisión profunda

- Cuenta y envío de prueba: campos de credenciales alineados, ayuda de contraseña asociada mediante `aria-describedby`, distribución compacta y adaptación al ancho del panel. El resultado de la prueba se muestra junto al botón y se limpia al editar la cuenta o el destinatario. El formulario queda bloqueado mientras se envía la prueba.
- Rechazo de autenticación SMTP: devuelve HTTP 502 y código `message.smtp.authentication_failed`, con explicación traducida y sin incluir la respuesta privada del proveedor. Verificado con MockMvc en ES/EN/ZH. En la revisión local Gmail indicó que exige una contraseña de aplicación; corregir la interfaz no sustituye configurar esa credencial.
- Validación de estos ajustes: 44 pruebas de la pantalla, 42 del manejador HTTP y TypeScript correctos. Revisión en navegador con API simulada a 1440 y 640 píxeles: sin errores JavaScript ni desbordamiento horizontal; Guardar y Enviar accesibles por desplazamiento. Evidencia en `tmp/mail-ui-review/account-layout-*.png`. No se enviaron correos reales durante estas pruebas.

Preajustes adicionales verificados el 20/11/2026:

| Proveedor | Servidor | Puerto | Seguridad |
| --- | --- | --- | --- |
| Yahoo Mail | smtp.mail.yahoo.com | 465 | TLS |
| QQ Mail | smtp.qq.com | 465 | TLS |
| iCloud Mail | smtp.mail.me.com | 587 | STARTTLS |
| NetEase 163 | smtp.163.com | 465 | TLS |
| Tencent Exmail | smtp.exmail.qq.com | 465 | TLS |

Referencias: [Yahoo](https://help.yahoo.com/kb/imap-settings-sln4075.html), [Apple](https://support.apple.com/es-es/102525), [Tencent: configuraciones SMTP](https://intl.cloud.tencent.com/document/product/1266/71700?lang=en) y [Tencent: configuración de QQ](https://cloud.tencent.com/document/product/583/35399). Los preajustes no autentican la cuenta: el usuario debe proporcionar su contraseña de aplicación o código de autorización cuando corresponda y guardar la configuración.

- Cuenta SMTP: el selector incluye Gmail / Google Workspace, Yahoo Mail, QQ Mail, iCloud Mail, NetEase 163 y Tencent Exmail. Rellena servidor, puerto, seguridad y usuario a partir del correo del remitente. Reconoce el proveedor al cargar una cuenta guardada sin reemplazar su puerto ni su seguridad. «Otro servidor» permite editar el host manualmente. Elegir proveedor no guarda ni envía mensajes. Se conserva el borrador al cambiar de pestaña.
- Protección de credenciales: si falta la clave de cifrado, guardar una contraseña devuelve HTTP 503 con el código `message.smtp.encryption_key_missing` y un mensaje traducido; el error ya no queda oculto por una traducción inexistente. Validación adicional: 50 pruebas del backend y 38 de la pantalla de correo, sin errores; comprobación TypeScript correcta.
- Contrato HTTP de plantillas: crear, previsualizar y enviar una prueba admiten versión omitida; editar exige una versión válida y mantiene el control de concurrencia.
- Programación: la fecha elegida se conserva al volver a revisar la audiencia. La confirmación exige la huella del contenido y destinatarios revisados.
- Formularios: refrescar la cuenta pide confirmar el descarte; renovar la sesión conserva el trabajo del mismo administrador. Cambiar de administrador elimina los borradores de la sesión anterior.
- Bienvenida: guardar un campo modificado no sobrescribe otros campos cambiados externamente, incluso después de renovar la sesión.
- Primera apertura de Automáticos: corregida la creación de `MemberSettings` con identificador de empresa asignado y `@MapsId`. La versión nullable permite que Spring Data reconozca la fila nueva y use persistencia inicial. La prueba con PostgreSQL real reprodujo `null identifier` antes del cambio y verifica creación, modificación y recarga en transacciones separadas después. Validación adicional: 34 pruebas correctas, sin omisiones (`backend/target/mail-settings-validation.log`).
- Reintentos: una solicitud repetida sobre un envío pendiente conserva contador y espera. Aceptaciones parciales, desconexiones ambiguas y fallos de persistencia después del envío no provocan un reenvío automático.
- OAuth: callbacks concurrentes consumen el código una sola vez; renovaciones concurrentes hacen una sola petición. La rotación de credenciales sobrevive a la reversión de la transacción del solicitante.
- Bajas: el formulario no duplica el token; repetir la baja no modifica de nuevo la versión del cliente. Se respeta el aislamiento entre empresas y los cambios de dirección del cliente.
- Transporte: mensajes Unicode, escape HTML, cabeceras de baja, imágenes adjuntas y lectura de QR/Code128. Se distingue un rechazo previo de un resultado incierto.

No se aplicaron estas migraciones a la base habitual de la tienda. La validación usa PostgreSQL desechable, transportes simulados y un navegador con respuestas API de prueba. La instancia PostgreSQL y el servidor frontend temporales se detuvieron al terminar.

Posteriormente, al iniciar el backend de desarrollo por petición del usuario, Flyway aplicó las migraciones pendientes a `tpv_erp_dev`. El arranque conserva desactivados los trabajadores de envío de correo.

Antes de activar una instalación real: registrar el proveedor, configurar claves y servicio público de baja, autenticar la cuenta y solicitar una prueba a una dirección propia. La validación local con transportes simulados no sustituye ese piloto externo.

## Activación local de carnets (20/11/2026)

Por petición del usuario, se habilitó `member_welcome_enabled` para EMPRESA PRUEBAS TPV ERP y el lanzador local `tmp/start-backend-dev.ps1` establece `TPV_MEMBER_CARD_DELIVERY_ENABLED=true` con perfil `dev`. El valor global de instalaciones nuevas permanece desactivado. Se guardaron asunto y cuerpo predeterminados donde estaban vacíos, conservando cualquier texto personalizado. No había carnets en cola antes de activar el servicio.

El alta de un cliente marcado como miembro y con correo prepara un único carnet. El trabajador revisa la cola cada 60 segundos. La tarjeta incluye el código del miembro y se adjunta al mensaje; una reactivación no duplica la bienvenida. El resultado se consulta en Correo y comunicaciones → Historial. La autenticación SMTP debe ser válida para que el proveedor acepte el mensaje.

Asunto predeterminado: `Tu tarjeta de cliente de {storeName}`. El cuerpo saluda a `{name}`, agradece el alta, muestra `{memberId}` e indica cómo guardar y presentar el carnet adjunto. Estos valores también se devuelven en la configuración cuando no existe texto personalizado y pueden editarse desde Automáticos.

Validación: 55 pruebas correctas de alta, personalización del mensaje, scheduler, worker, transporte SMTP simulado y contrato HTTP. Evidencia: `tmp/member-auto-enable-tests.log`. No se crearon clientes de prueba ni se encolaron correos reales durante la validación.

## Enlaces de ayuda

Comprobación adicional del 20/11/2026: 74 pruebas de pantalla y navegación correctas. En Chrome se verificaron los botones (incluidas pulsaciones repetidas), los enlaces del primer paso y los cambios Gmail → Yahoo → QQ → iCloud → 163 → Exmail → Gmail → Otro servidor. Las instrucciones permanecieron abiertas al cambiar proveedor, editar la dirección y cambiar de pestaña. Las seis páginas públicas respondieron HTTP 200 en una sesión independiente sin autenticar; Google y Yahoo redirigieron al inicio de sesión esperado. No se generaron credenciales ni se enviaron mensajes. Evidencia: `tmp/mail-ui-review/provider-navigation-results.json` y `tmp/mail-provider-navigation-tests.log`.

Junto a la contraseña aparece «Abrir Google», «Abrir Yahoo», «Abrir QQ», «Abrir Apple», «Abrir 163» o «Abrir Exmail», según el proveedor seleccionado. Abre su página oficial en el navegador y despliega instrucciones breves debajo del campo, disponibles en español, inglés y chino. «Otro servidor» muestra instrucciones generales sin inventar una dirección de configuración.

El usuario inicia sesión en su proveedor, genera la contraseña de aplicación o código de autorización, lo pega en Gestión, guarda y solicita una prueba. Abrir la ayuda no guarda cambios ni envía mensajes. Los enlaces de escritorio están limitados a las seis direcciones oficiales; este cambio requiere cerrar y abrir Gestión una vez si ya estaba ejecutándose.

Fuentes consultadas el 20/11/2026: [Google](https://support.google.com/accounts/answer/185833), [Yahoo](https://en-global.help.yahoo.com/kb/new-yahoo-mail/generate-password-sln15241.html), [Apple](https://support.apple.com/en-us/102654) y las instrucciones oficiales de integración de Huawei para [QQ](https://consumer.huawei.com/cn/support/content/zh-cn16108643/), [163](https://consumer.huawei.com/cn/support/content/zh-cn00406699/) y [Exmail](https://consumer.huawei.com/cn/support/content/zh-cn16081272/). Los menús y la disponibilidad pueden variar por cuenta y política del administrador.

Validación: 85 pruebas correctas de pantalla, navegación y seguridad de escritorio; TypeScript sin errores. Revisión visual con API simulada a 1440 y 640 píxeles: botón junto a la contraseña, instrucciones desplegables, campos alineados, sin desbordamiento horizontal y acciones accesibles al desplazarse. Sin envíos reales ni modificaciones de cuentas. Evidencia: `tmp/mail-password-help-tests.log` y `tmp/mail-ui-review/account-layout-*.png`.

# Vinculación de terminales Windows

## Uso

1. Preparar la instalación y su licencia desde APP GESTIÓN en el ordenador del backend. VENTA y GESTIÓN comparten el terminal **001** y una única plaza Windows en ese equipo y perfil Windows.
2. Al abrir VENTA por primera vez aparece **Configurar conexión**. Buscar servidores o introducir equipo/dominio/IP y puerto, y pulsar **Comprobar conexión**.
3. Verificar tienda e instalación. Elegir un código libre del rango de la licencia y un nombre para el terminal. Se puede elegir 003 aunque 002 esté libre. El 001 se configura exclusivamente por la conexión local del ordenador servidor; la primera adopción requiere el administrador de la instalación si no existe una identidad local válida.
4. En los demás equipos, enviar la solicitud. En GESTIÓN, abrir **Seguridad > Terminales y PDA**, seleccionar el código pendiente y aprobarlo. La solicitud reserva la plaza durante 15 minutos; se puede cancelar y solicitar de nuevo si caduca.
5. Consultar el estado o esperar la actualización automática. Reiniciar cuando lo solicite la aplicación para activar conjuntamente el destino del proxy y la identidad del terminal. Después, iniciar sesión normalmente.

La vinculación pertenece al equipo y al terminal, compartida entre VENTA y GESTIÓN bajo el mismo usuario Windows. Al abrir la otra aplicación se utiliza el mismo código, nombre, vínculo y credencial, sin otra alta ni autorización ADMIN y sin consumir otra plaza. Si el asistente ya estaba abierto, comprueba el estado al recuperar el foco y periódicamente: muestra la vinculación compartida y, cuando el proceso aún no ha cargado el destino o vínculo nuevos, pide únicamente reiniciar. Ese reinicio carga también el ámbito correcto de los envíos pendientes. Los cambios manuales de dirección se conservan durante las comprobaciones; la pantalla de configuración de una aplicación ya vinculada sigue permitiendo cambiar la dirección.

La pantalla conserva el estilo de lista y detalle de GESTIÓN. Muestra la capacidad Windows, códigos libres, reservados y ocupados, equipo, nombre, estado e historial de vinculaciones. Los dispositivos PDA conservan su gestión y cuota independientes.

## Sustituir un ordenador

En **Terminales y PDA**, seleccionar por ejemplo 002 y pulsar **Liberar terminal**. La confirmación identifica el equipo afectado. El equipo puede estar apagado o averiado: la liberación se realiza en el backend, revoca su acceso y permite elegir 002 desde el ordenador nuevo. La vinculación y la credencial del sustituto son nuevas; se conserva el identificador lógico del terminal y su historial económico.

**Desactivar** conserva la asignación y bloquea el acceso; **Liberar** permite reutilizar el código. Ninguna de estas acciones cierra cajas, mueve fondos ni cancela cobros. Las cajas abiertas o con saldo siguen siendo visibles. Los borradores de cierre y envíos locales se aíslan por instalación y vinculación; el sustituto no reenvía datos del equipo anterior.

## Cambiar la dirección del backend

Los terminales ya vinculados intentan recuperar automáticamente una IP cambiada al iniciar VENTA o GESTIÓN. Durante el uso, **Reintentar** comprueba únicamente la IP elegida para esa sesión; cerrar y volver a abrir activa otra búsqueda. El comportamiento, la protección de identidad y el guardado del trabajo pendiente se describen en [Recuperación de conexión de esPOS](backend-connection-recovery.md).

Usar **Configurar conexión** desde el inicio de sesión, incluso si el backend anterior no responde, o desde la configuración del puesto con el permiso de configuración de terminal. Introducir o descubrir la nueva dirección, comprobarla, guardar y reiniciar.

La aplicación verifica el certificado HTTPS remoto y una respuesta firmada por la instalación. Un cambio de dirección de la misma instalación conserva identidad y envíos pendientes. Se rechaza una instalación distinta o una clave cambiada: no se envía automáticamente la credencial existente a ese destino.

En Windows, la dirección autorizada se guarda en `%ProgramData%\TPV ERP\desktop\backend-config.json`. El asistente solicita la elevación de Windows necesaria para escribir ese archivo protegido. Las credenciales se guardan cifradas mediante la protección del perfil Windows en `%APPDATA%\TPV ERP\desktop-linking.dpapi`. VENTA y GESTIÓN deben utilizar el mismo perfil Windows en el ordenador servidor.

La identidad compartida utiliza DPAPI `CurrentUser` directamente y un formato versionado. Las claves de `safeStorage` de Electron pertenecen a cada perfil de aplicación y solo se utilizan para leer el formato anterior durante la migración. Al actualizar desde ese formato, cerrar ambas aplicaciones y abrir primero la aplicación actualizada con la que se realizó la vinculación; después abrir la otra. La migración es automática, bajo bloqueo y sin cambiar terminal, credencial, vínculo ni prueba pendiente. Si el archivo no puede descifrarse, se conserva y se explica cómo completar la migración; nunca se sustituye por una identidad vacía.

El acceso a DPAPI utiliza PowerShell de Windows con entrada y salida por canales del proceso, sin pasar secretos por argumentos ni guardarlos en archivos temporales. Si su ejecución está bloqueada por una política del equipo, el inicio falla conservando la identidad.

## Descubrimiento y despliegue de red

El descubrimiento utiliza DNS-SD/mDNS, servicio `_tpv-erp._tcp.local.`, sin recorrer todas las direcciones IP. El anuncio solo proporciona candidatos; la aplicación comprueba después la identidad del servidor.

Para anunciar el acceso remoto del backend, configurar su URL HTTPS pública en `TPV_BACKEND_PUBLIC_URL`, por ejemplo `https://tpv.tienda.local:8443`. Debe ser una dirección alcanzable por los terminales y cubierta por un certificado de confianza. La configuración actual de escucha local del backend permanece separada del acceso HTTPS remoto.

Variables opcionales del backend:

| Variable | Uso |
| --- | --- |
| `TPV_BACKEND_PUBLIC_URL` | URL HTTPS que se anuncia; sin ella no se publica anuncio remoto. |
| `TPV_BACKEND_DISCOVERY_ENABLED` | `false` desactiva los anuncios; por defecto `true`. |
| `TPV_BACKEND_DISCOVERY_INTERFACES` | Lista de direcciones IPv4 locales separadas por comas para limitar interfaces; vacía utiliza las interfaces privadas activas admitidas. |

La red privada debe permitir mDNS UDP 5353 y el puerto HTTPS configurado. Si multicast no está disponible, la entrada manual sigue funcionando. No se omite la validación TLS ni se permite HTTP remoto. Para el equipo del backend se utiliza la dirección de bucle local y su puerto HTTP, normalmente `127.0.0.1:8080`.

## Actualización de instalaciones existentes

La migración conserva los UUID, credenciales e historial existentes. El servidor queda asociado a 001. Los terminales Windows anteriores sin código aparecen en GESTIÓN para asignarles explícitamente un código libre; después se adopta en ese equipo su identidad cifrada anterior mediante prueba de credencial. Esta adopción conserva los datos locales y no consume una segunda plaza. Las altas nuevas deben utilizar el nuevo asistente; la ruta antigua de solicitud queda reservada al flujo PDA.

Los contratos de mensajes y rutas para mantenimiento están en [terminal-linking-contract.md](terminal-linking-contract.md).

## Validación realizada — 4 de octubre de 2026

- Backend: 87 pruebas distintas aprobadas. Incluyen PostgreSQL 18 real en una instancia desechable, concurrencia, cuota, prueba de credencial e idempotencia, sustitución y revocación, conservación de caja, adopciones, permisos y upgrade V268 → V269 con datos existentes. Los escenarios de licencia del servicio utilizan dobles controlados; no contactan SaaS real.
- Frontend: una pasada completa de 3.129 pruebas aprobada antes de los últimos ajustes. La pasada posterior ejecutó 3.141 casos: 3.129 aprobados y 12 fallos bajo compilación simultánea. Se repitieron los siete archivos afectados y todos los tests de escritorio y pantallas modificadas, con dos workers y sin compilación simultánea: 258/258 aprobados. Tras endurecer el guardado y preservar espacios de la contraseña, las dos suites afectadas volvieron a pasar, 12/12. No se modificaron las pruebas ajenas de stock, informes o caja para conseguir esa repetición.
- Compilación de GESTIÓN, VENTA y PDA; comprobación de presupuestos de bundle aprobada. VENTA conserva un aviso de proximidad al límite CSS: 459.982/460.000 bytes; el asistente se carga por separado.
- Paquetes Windows de VENTA y GESTIÓN generados y validados: 261 archivos, incluidos los módulos de vinculación y el helper protegido. Son directorios `win-unpacked`; esto no certifica la firma ni la instalación de un instalador distribuible.
- Sintaxis de Electron y del helper PowerShell, y `git diff --check`, correctos. Revisión independiente de seguridad con correcciones verificadas para cambio de IP sin conexión anterior, entrega de identidad, ACL y escrituras concurrentes.
- Comprobación visual en navegador con datos y bridge simulados: elección 003 antes de 002, solicitud pendiente, aprobación y reinicio, y lista/detalle/historial de GESTIÓN. Las capturas se conservan en `output/playwright/terminal-linking/`.

Antes de distribuir, completar una prueba con dos equipos Windows: certificado/red local, descubrimiento multicast, UAC real (incluido otro administrador) y arranque de ambas aplicaciones con el mismo perfil. No se alteró la configuración productiva ni se instalaron estos paquetes en el equipo del usuario.

### Corrección comprobada de la identidad compartida

Se reprodujo el fallo del archivo de identidad creado por GESTIÓN: `safeStorage` podía leerlo con su perfil de Electron, pero no con el de VENTA. Tras la corrección se verificaron dos procesos reales de Electron con perfiles distintos sobre una copia cifrada de la identidad existente: GESTIÓN migró el formato y VENTA leyó la misma identidad; ambos validaron el vínculo `001` activo con el backend. El archivo original permaneció intacto, comprobado por su hash. La prueba no creó ni liberó terminales.

Las pruebas de regresión cubren migración desde cualquiera de las dos aplicaciones, lectura sin escrituras, conservación ante fallos de cifrado o de disco, rechazo de datos alterados, invalidación de caché cuando otra aplicación guarda cambios y transferencia DPAPI entre procesos Windows independientes.

Validación de esta corrección: 154 casos distintos aprobados (153 en la pasada de escritorio y pantalla de conexión, más el caso añadido de transferencia entre procesos); compilación de ambas aplicaciones y presupuestos de bundle correctos. Los paquetes corregidos están en `frontend/output/desktop-linking-fix/{gestion,venta}/win-unpacked`; se comprobaron sus 263 archivos y que los módulos empaquetados coinciden con las fuentes. La prueba de identidad entre perfiles también pasó utilizando los módulos de ambos `app.asar` nuevos. Cerrar las versiones anteriores y abrir primero GESTIÓN actualizada cuando fue GESTIÓN la que creó la vinculación.

### Reconocimiento automático entre aplicaciones

Se verificó el reconocimiento al abrir y al recuperar el foco, la detección periódica desde un asistente ya abierto y la reutilización de cualquier código activo por la segunda aplicación, sin duplicar solicitudes ni autenticación ADMIN. Los estados liberados no autorizan acceso; un equipo remoto liberado puede iniciar una solicitud nueva con prueba nueva y pendiente de aprobación. La comprobación automática conserva tanto la selección inicial de código como la selección manual y los campos editados.

Validación final de este ajuste: 173 casos distintos aprobados (pasada de 172 y repetición de los 18 de pantalla tras añadir la protección de la selección inicial), TypeScript y compilación de VENTA/GESTIÓN, presupuestos de bundle y validación de 263 archivos empaquetados. Los ejecutables de `frontend/output/desktop-production/{venta,gestion}/win-unpacked` se actualizaron con ambas aplicaciones cerradas. Se migró la identidad existente con respaldo cifrado `.electron-v1.bak` e igualdad de todo el estado antes/después. Una comprobación real por Playwright/CDP confirmó que ambos ejecutables llegan al login, tienen backend conectado, código `001`, nombre `TERMINAL PRINCIPAL`, estado `ACTIVE`, sin reinicio pendiente y sin formulario de nueva vinculación. No se inició sesión de usuario ni se creó otra vinculación. Las consultas de estado actualizan la última conexión del vínculo.

Capturas y snapshots de esta comprobación: `frontend/output/playwright/shared-terminal/`. Las sesiones de diagnóstico se cerraron antes de volver a abrir ambas aplicaciones normalmente.

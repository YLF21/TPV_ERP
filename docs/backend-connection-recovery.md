# Recuperación de conexión de esPOS

VENTA y GESTIÓN recuperan automáticamente la dirección del backend al arrancar un equipo ya vinculado. La primera conexión y la aprobación de un terminal siguen utilizando el asistente de [vinculación](terminal-linking.md).

## Arranque

1. Leer la identidad cifrada y la dirección autorizada. Probar la última IP verificada y la dirección configurada.
2. Si no responden, buscar candidatos DNS-SD/mDNS en la red privada, sin recorrer todas las IP de la red. Consultar cada adaptador IPv4 privado activo (hasta ocho), relacionar los registros PTR, TXT, SRV y A y comprobar el puerto anunciado. Un adaptador que deja de estar disponible no interrumpe las consultas por los demás.
3. Conectar a la IP candidata conservando la autoridad HTTPS original: nombre/IP del certificado, SNI y cabecera Host. La búsqueda no autoriza certificados nuevos ni desactiva TLS.
4. Verificar un desafío nuevo firmado por la instalación, el UUID y la huella de la clave guardada. Sólo entonces comprobar la credencial y la aprobación del mismo terminal, tienda y vínculo.
5. Guardar únicamente la nueva ubicación en el almacenamiento compartido DPAPI. Conservar terminal, credencial, identidad, cuotas y operaciones pendientes. La búsqueda de arranque está limitada a 15 segundos y ocho direcciones.

La IP elegida queda fija durante ese proceso de la aplicación. Si no se pudo elegir una dirección concreta, el acceso operativo permanece bloqueado; las llamadas posteriores no vuelven a resolver DNS por su cuenta. Cada aplicación valida su propia conexión al arrancar, aunque ambas compartan la identidad del equipo.

## Pérdida de conexión durante el uso

Las solicitudes operativas detectan errores de transporte y una comprobación cada 30 segundos detecta pérdidas mientras la aplicación está inactiva. No se repiten automáticamente ventas, cobros ni otras mutaciones. Un error de SaaS, una respuesta HTTP 401/403 o un error funcional del servidor no se interpreta como un cambio de IP.

El aviso bloquea la pantalla y sus atajos y ofrece dos acciones:

- **Reintentar**: verificar de nuevo el mismo servidor y la misma IP seleccionada al arrancar. Si funciona, retirar el aviso y reactivar el inicio de sesión cuando corresponda. No buscar servidores ni cambiar de dirección.
- **Cerrar aplicación**: preparar primero el guardado local y cerrar la aplicación actual. La siguiente apertura vuelve a ejecutar el procedimiento de arranque.

## Trabajo pendiente de VENTA

Antes del cierre por desconexión se guarda un checkpoint cifrado del ticket y de los identificadores de cobro, reservas y recuperaciones pendientes. Se aísla por aplicación, instalación, tienda, terminal, vínculo y usuario. No incluye contraseñas, tokens de sesión, credenciales de terminal ni autorizaciones temporales. El archivo no depende del puerto local del renderer.

Al volver a iniciar sesión, el mismo usuario puede recuperar el ticket. Las sesiones económicas se consultan por sus identificadores antes de permitir continuar: una sesión ya finalizada entrega el documento existente. La reserva de saldo de socio, el saldo disponible y los precios se concilian con el backend antes de habilitar el cobro. La recuperación no cancela ni descarta automáticamente operaciones del servidor.

Si el guardado falla, se mantiene abierta la aplicación y se muestra el error. Al retirar un checkpoint, un fallo de borrado se intenta resolver con una marca vacía cifrada; si tampoco puede guardarse, se avisa y se bloquea la salida normal para evitar restaurar después un ticket obsoleto.

## Requisitos y límites de red

El backend debe anunciar `TPV_BACKEND_PUBLIC_URL` y la red debe permitir mDNS UDP 5353 y el puerto HTTPS configurado. El backend actualiza los anuncios al cambiar sus interfaces privadas, comprobándolas cada 30 segundos. `TPV_BACKEND_DISCOVERY_INTERFACES` sigue siendo una lista explícita de IPv4: una IP nueva excluida por esa lista no se anunciará.

El certificado debe seguir siendo válido para la autoridad autorizada. La recuperación automática no sustituye certificados caducados, una instalación diferente, una nueva vinculación, un cambio de puerto o una red que bloquee multicast. En esos casos permanece disponible **Configurar conexión** para la corrección explícita. Para el ordenador del backend se conserva el acceso local de bucle (`127.0.0.1`); HTTP remoto continúa prohibido. No requiere cambiar la configuración del router.

## Comprobación nativa aislada

Después de compilar ambas aplicaciones, ejecutar desde `frontend`:

```powershell
node scripts/check-connection-recovery-native.mjs
```

La comprobación usa Electron real, un backend simulado en loopback y perfiles temporales. Comprueba el arranque, el aviso, los botones con teclado, el reintento, el cierre y reapertura con otro puerto de renderer, DPAPI de Windows y el rechazo de IPC desde una ventana auxiliar. No usa credenciales de la tienda ni modifica su base de datos. El resultado y los logs quedan en el perfil temporal indicado por el comando. Esta prueba no sustituye una prueba de cambio de IP y multicast con dos equipos físicos.

## Validación del 9 de octubre de 2026

- Frontend completo con Node 24 y dos workers: 3.408 pruebas correctas en 304 archivos. Una pasada anterior tuvo un fallo de temporización del escáner; la repetición focal y la pasada completa posterior fueron correctas. Tras separar la carga del aviso para conservar el presupuesto CSS, las 36 pruebas de los puntos afectados volvieron a pasar.
- Integración adicional con HTTPS y sockets reales: servidor en `127.0.0.3`, cambio a `127.0.0.4`, reintento sin cambiar la IP y nueva apertura que recupera la dirección. Se utilizan una CA y una firma de instalación de prueba; no se cambia ningún adaptador de red.
- Backend `mvnw.cmd -Duser.timezone=UTC verify`: 4.288 pruebas ejecutadas correctas, 256 omitidas por la configuración de la suite, cero fallos y errores. En zona Canarias, la primera pasada tuvo 27 errores ajenos a esta función en contextos que cargan V1: la comprobación antigua de 30 días de calendario no coincide con 720 horas al cruzar el cambio horario. No se cambió esa regla ni la zona horaria productiva. Failsafe indicó que no había casos adicionales que ejecutar.
- Compilación de GESTIÓN, VENTA y PDA y presupuestos de bundle correctos. VENTA mantiene el aviso de proximidad al presupuesto CSS.
- Comprobación con Electron 44.5.1 real en Windows: ambas aplicaciones, conexión activa y perdida, activación de botones mediante Enter/Espacio, lectura DPAPI de más de 64 KiB, rechazo de IPC auxiliar y reapertura con un puerto local diferente. Perfiles y backend aislados.
- Paquetes Windows de ambas aplicaciones generados y validados: 285 archivos; los nueve módulos de escritorio afectados coinciden con sus fuentes en ambos `app.asar`. Son paquetes `win-unpacked`, sin aceptación de firma de instalador.

## Validación del 10 de octubre de 2026 en Windows 10

Prueba con las aplicaciones empaquetadas, backend real de la tienda demo y Windows 10 x64 (19045) en VirtualBox 7.2.20. La máquina virtual tiene NAT y un segundo adaptador privado Host-Only. El backend mantiene la misma instalación, certificado, puerto HTTPS y autoridad autorizada; se cambia la IP real del adaptador privado del anfitrión, sin modificar el router ni la IP de su red habitual.

Se detectó y corrigió un fallo de descubrimiento cuando existen varios adaptadores: las consultas multicast dependían del adaptador predeterminado. Ahora se une y consulta el grupo mDNS por cada IPv4 privada disponible. Las pruebas incluyen una respuesta únicamente por el segundo adaptador y el fallo aislado del primero.

| Comprobación | Resultado |
| --- | --- |
| GESTIÓN: dirección anterior `192.168.82.1`, backend en `192.168.82.2`, cerrar y volver a abrir | `CONNECTED` en `.2`; bootstrap firmado y aprobación del terminal HTTP 200 |
| VENTA y GESTIÓN abiertas: cambio del backend de `.2` a `.1` | Ambas muestran el aviso de pérdida de conexión |
| Reintentar en ambas aplicaciones después del cambio | Conservan `.2`, permanecen desconectadas y no consultan el backend en `.1` |
| Cerrar ambas desde el aviso y abrir primero VENTA | VENTA descubre `.1`, valida identidad y aprobación y queda `CONNECTED` |
| Abrir GESTIÓN después | Valida la ubicación compartida `.1` y queda `CONNECTED` |
| Identidad compartida antes y después | Mismos instalación, tienda, terminal `002`, vínculo, huella de clave y hash de credencial; aprobación `ACTIVE` |
| Configuración manual | Conserva `https://10.0.2.2:8443`; cambia únicamente la ubicación física verificada |

La validación focal de descubrimiento, recuperación, TLS y almacenamiento pasó las 34 pruebas de cuatro archivos con Node 24. Los dos paquetes Windows se regeneraron y validaron (285 archivos); los cinco módulos de recuperación comprobados coinciden con las fuentes y los hashes EXE/ASAR instalados en la VM coinciden con los paquetes reconstruidos. Las evidencias locales quedan en `C:\Users\YLF\.codex\tmp\win10-ip-recovery-20261010` (capturas, estados IPC, comprobaciones de identidad sin credenciales y logs de solicitudes sin cuerpos).

Esta prueba confirma multicast y cambios efectivos de IP en una red virtual Windows. Queda pendiente una renovación DHCP y la validación entre dos equipos físicos en la red del cliente; no se presenta la prueba controlada como una renovación DHCP real. No se realizaron ventas, cobros ni cambios de stock durante esta comprobación de conexión.

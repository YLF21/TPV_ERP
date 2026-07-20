# Matriz de permisos de APP GESTION

## Principios aprobados

- Entrar en APP GESTION requiere `APP_GESTION_ACCESS`; `ADMIN` conserva acceso implícito.
- Entrar en la aplicación no concede acceso a sus módulos.
- Cada módulo y cada widget del dashboard exige el permiso de los datos que presenta.
- La configuración y eliminación de widgets es personal y no altera la configuración de otros usuarios.
- Los endpoints compartidos con APP VENTA conservan permisos operativos propios; la navegación de APP GESTION aplica además sus permisos de módulo.
- Los permisos granulares existentes se conservan para poder construir roles limitados.

## Matriz funcional

| Área | Lectura o apertura | Escritura y acciones |
| --- | --- | --- |
| APP GESTION | `APP_GESTION_ACCESS` | No concede acciones internas |
| Dashboard | `APP_GESTION_ACCESS` y permiso del widget | El usuario configura únicamente sus widgets |
| Informes de ventas | `GESTION_VENTAS` | Exportar e imprimir: `GESTION_VENTAS` |
| Tickets y facturas en gestión | `GESTION_VENTAS` | Crear, modificar, anular, rectificar y reimprimir: `GESTION_VENTAS` |
| Productos, ofertas y promociones | `GESTION_PRODUCTO` | `GESTION_PRODUCTO` |
| Stock | `GESTION_PRODUCTO` o `GESTION_ALMACEN` | Producto/precio: `GESTION_PRODUCTO`; existencias, ajustes y transferencias: `GESTION_ALMACEN` |
| Almacenes | `GESTION_ALMACEN` | Entradas, salidas y confirmaciones: `GESTION_ALMACEN` |
| Clientes | `GESTION_CLIENTE_PROVEEDOR`; la selección operativa admite `VENTA` | Alta operativa: `VENTA` o `GESTION_CLIENTE_PROVEEDOR`; modificación y baja: `GESTION_CLIENTE_PROVEEDOR` |
| Miembros | `GESTION_CLIENTE_PROVEEDOR`; la selección en venta admite `VENTA` | Alta operativa: `VENTA` o `GESTION_CLIENTE_PROVEEDOR`; saldo, puntos y configuración: `GESTION_CLIENTE_PROVEEDOR` |
| Proveedores | `GESTION_CLIENTE_PROVEEDOR` o `GESTION_ALMACEN` | Alta y modificación: cualquiera de los anteriores; eliminación: `GESTION_CLIENTE_PROVEEDOR` |
| Usuarios | `GESTION_USUARIO` | `GESTION_USUARIO` |
| Roles y permisos | `ROLES_MANAGE` | `ROLES_MANAGE` |
| Caja operativa | `VENTA` | Abrir, contar y cerrar la propia caja sin conocer importes teóricos ni diferencias |
| Caja y contabilidad | `GESTION_CUENTAS` | Ver ventas, cierres, teóricos, diferencias, históricos y configuración |
| Terminales | `CONFIGURACION_TERMINAL` | Secretos: `PAYMENT_TERMINAL_SECRETS` o `ADMIN` |
| Configuración de empresa | `ADMIN` | `ADMIN` |
| Copias de seguridad | `BACKUPS_MANAGE` | Crear/configurar: `BACKUPS_MANAGE`; restaurar o eliminar: `ADMIN` |
| Licencias y SaaS | `LICENSES_MANAGE` | Activar, vincular, bloquear o modificar: `ADMIN` |
| Auditoría | `AUDIT_READ` | Exportar: `ADMIN`; los registros no se modifican desde APP GESTION |

## Ciclo de vida de roles

- Crear, renombrar, cambiar permisos y eliminar roles exige `ROLES_MANAGE` o
  `ADMIN`.
- Los roles protegidos del sistema no se renombran, no cambian sus permisos y no
  se eliminan.
- Un rol configurable solo puede eliminarse cuando no tiene usuarios asignados.
  Si está en uso, la API responde `409 ROLE_IN_USE` e incluye `assignedUsers`
  con el número de usuarios que deben reasignarse desde la pantalla Usuarios.
- El renombrado y la eliminación se registran como `ROLE_RENAMED` y
  `ROLE_DELETED` en auditoría.
- No se realiza reasignación masiva implícita durante una eliminación.

`GESTION_CLIENTE_PROVEEDOR` es un permiso superior. Concede las capacidades de
`CUSTOMERS_READ`, `CUSTOMERS_WRITE`, `CUSTOMERS_DELETE`, `SUPPLIERS_READ`,
`SUPPLIERS_WRITE` y `SUPPLIERS_DELETE`. Los seis permisos granulares continúan
siendo válidos de forma independiente.

## Cierre ciego de caja

Un usuario con `VENTA` introduce el efectivo contado y confirma el cierre. La
respuesta no incluye efectivo esperado, efectivo disponible, fondo declarado ni
descuadre. El backend sí conserva esos valores y los sincroniza, pero solo
`GESTION_CUENTAS` o `ADMIN` pueden consultarlos. Un cierre confirmado no se edita
ni elimina; cualquier corrección futura deberá ser una operación nueva y auditada.

## Alertas de control

La nomenclatura implementada para el módulo es:

- `CONTROL_ALERTS_READ`: consultar alertas.
- `CONTROL_ALERTS_MANAGE`: revisar, cerrar o descartar alertas sin eliminarlas.
- `CONTROL_RULES_MANAGE`: crear y modificar reglas.
- Abrir la venta relacionada exige además `GESTION_VENTAS`.
- Las alertas son por tienda. La consulta multitienda corresponde al SaaS y a su alcance de datos.
- Configurar el envío al SaaS es una acción de `ADMIN`.

El dominio, la API, la pantalla y estos permisos se incorporaron en las migraciones
V75 y V76. Las reglas y alertas son locales a la tienda activa; la consulta
multitienda y la configuración de su envío pertenecen al futuro backend SaaS.

## Exportacion e impresion de informes

- Excel se genera en backend a partir de la consulta completa autorizada; no se
  limita a las filas paginadas que el navegador haya cargado.
- El backend vuelve a comprobar el permiso del tipo de informe. Para informes
  de ventas, exportar exige `GESTION_VENTAS` o `ADMIN`; `VENTA` no puede usar el
  endpoint aunque construya manualmente la peticion.
- La solicitud solo controla filtros, busqueda, orden visible y etiquetas de
  columna. Las filas se obtienen de la tienda resuelta por la sesion.
- PDF e impresion usan Electron y el dialogo nativo del sistema. El PDF contiene
  la vista imprimible del informe, sin menus ni controles operativos.
- Cada exportacion Excel queda registrada en auditoria y tiene un limite de
  50.000 filas para proteger memoria y tiempos de respuesta.

## Identidad de APP GESTION

- En produccion APP GESTION no utiliza identificadores ni credenciales incluidos
  en el bundle de Vite.
- El primer arranque solicita el `ADMIN` global de instalacion. Esa sesion crea
  o rota la credencial del unico terminal `SERVIDOR` de la tienda.
- La credencial se devuelve una sola vez y Electron la cifra mediante
  `safeStorage` (DPAPI en Windows) dentro del perfil local de la aplicacion.
- El login valida la credencial tambien para terminales `SERVIDOR`; conocer solo
  el UUID del terminal deja de ser suficiente.
- El aprovisionamiento exige exactamente una tienda local. Si todavia no existe,
  primero debe completarse la vinculacion de licencia/SaaS.
- Los valores de demostracion solo permanecen disponibles cuando Vite ejecuta en
  modo de desarrollo.

## Fuera de alcance actual

- El módulo de incidencias queda aplazado hasta definir qué proceso operativo representa.
- La exportación de auditoría se implementará cuando exista su endpoint y formato.
- Las pantallas de APP GESTION que todavía son marcadores no adquieren acciones simuladas.

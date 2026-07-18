# Dashboard configurable de APP GESTION

## Objetivo

La pantalla inicial de APP GESTION es un espacio de trabajo configurable por
usuario. Solo muestra widgets con datos reales y cada widget exige el permiso
de su fuente.

## Diseno visual

- UI de escritorio ERP clasica y compacta, coherente con APP VENTA.
- Fondo gris azulado, paneles blancos, bordes finos y azul marino como color de
  seleccion y accion principal.
- Sin tarjetas redondeadas, cristal, gradientes decorativos ni metricas de
  ejemplo.
- Barra lateral estable para navegar entre modulos.
- Barra superior del dashboard con fecha de negocio, estado de carga y accion
  `Personalizar`.

## Distribucion recomendada

Se usa un grid hibrido de doce columnas:

- Los widgets no se solapan.
- El usuario puede reordenarlos mediante arrastre.
- Puede elegir anchuras discretas de 3, 4, 6, 8 o 12 columnas.
- Puede elegir una altura de 1, 2 o 3 unidades.
- En ventanas estrechas el grid se adapta sin guardar coordenadas especificas
  del dispositivo.

No se usan ventanas flotantes con coordenadas absolutas porque complican la
accesibilidad, el cambio de resolucion, el uso por teclado y la persistencia.
Tampoco se limita la pantalla a una plantilla fija de 4, 6 u 8 huecos porque
obliga a dejar espacios vacios y dificulta incorporar widgets futuros.

## Persistencia

- La preferencia se guarda en PostgreSQL por usuario, no por terminal.
- El orden del array determina el orden visual.
- Cada entrada contiene `key`, `width` y `height`.
- El backend valida claves conocidas, duplicados, dimensiones y permisos.
- Si un usuario pierde un permiso, el widget se conserva almacenado pero no se
  devuelve. Volvera a aparecer si recupera el permiso.
- El usuario solo puede modificar su propia preferencia.

## Catalogo inicial

| Widget | Fuente | Permiso |
| --- | --- | --- |
| Ventas de hoy | Informe comercial diario de hoy y ayer | `GESTION_VENTAS` |
| Productos mas vendidos | Top de ventas del periodo diario | `GESTION_VENTAS` |
| Promociones activas | Promociones vigentes de la empresa | `GESTION_PRODUCTO` |
| Alertas de control | Resumen local de alertas nuevas, revisadas y recientes | `CONTROL_ALERTS_READ` o `CONTROL_ALERTS_MANAGE` |

El widget `control.alerts` consume `GET /api/v1/control/alerts/summary`, muestra
los contadores de alertas nuevas y revisadas y las cinco alertas mas recientes
de la tienda activa. Su accion abre el modulo completo de alertas de control.
No permite cambiar estados desde el dashboard.

Las incidencias y los avisos SaaS no se muestran hasta que existan sus dominios,
endpoints y permisos reales.

## Seguridad

- Todo endpoint de preferencia exige `APP_GESTION_ACCESS`; `ADMIN` conserva el
  acceso implicito.
- La autorizacion del catalogo se calcula de nuevo en backend.
- Las llamadas de datos conservan sus controles de autorizacion existentes.
- El frontend no se considera una barrera de seguridad.

## Evolucion

Los siguientes widgets previstos, pero no incluidos en este corte, son ventas
mensuales con comparacion, incidencias, estado de caja y avisos SaaS. Cada uno
se incorporara unicamente cuando su contrato de datos y permiso esten definidos.

# Revisión transversal de APP GESTIÓN

## Alcance y evidencia

- Captura 01: configuración del terminal servidor.
- Captura 02: alertas de control.
- Revisión del mapa de módulos y componentes de `app-gestion`.

La sesión completa no pudo recorrerse desde el navegador porque la identidad del terminal se almacena mediante la protección de Windows/Electron. Por ello, la valoración visual directa se limita a las dos capturas; el resto de observaciones se apoya en la estructura y comportamiento presentes en el repositorio.

## Estado por área

1. **Acceso y configuración — Salud: mejorable.** La pantalla es clara, pero necesita diagnóstico accionable, mostrar el servidor objetivo, comprobación de conectividad separada, revelar contraseña y ayuda de recuperación.
2. **Navegación y estructura global — Salud: buena base.** La navegación respeta permisos y agrupa módulos. Mejorar con búsqueda global, favoritos/recientes, migas de pan, persistencia de menús abiertos y una bandeja unificada de avisos.
3. **Resumen — Salud: buena.** Ya admite widgets, reordenación y actualización. Añadir comparación con periodo anterior, fecha de última actualización, indicadores de tendencia y vistas guardadas por usuario/rol.
4. **VERI*FACTU — Salud: funcional pero compleja.** Convertir estados técnicos en una cola de trabajo guiada, destacar la siguiente acción, agrupar errores repetidos, avisar de caducidad del certificado y permitir exportar evidencias.
5. **Alertas de control — Salud: buena.** Hacer clicables los contadores, mostrar `Actualizando…` y última actualización, añadir selección múltiple, acciones en lote y vistas guardadas por responsable/prioridad/plazo.
6. **Ventas — Salud: funcional.** Unificar filtros y presets, permitir comparar periodos, abrir el ticket desde cualquier métrica, exportar respetando filtros y señalar anomalías en descuentos, devoluciones y anulaciones.
7. **Stock — Salud: funcional.** Crear una vista de salud del inventario con roturas, exceso, inmovilizado y valoración; añadir propuestas de reposición, transferencias entre almacenes y trazabilidad del movimiento.
8. **Almacén — Salud: buena base operativa.** Orientar la interfaz a tareas pendientes, añadir escaneo de códigos, progreso de recepción/preparación, conciliación de diferencias y registro visible de quién confirmó cada movimiento.
9. **Clientes, socios y proveedores — Salud: mejorable.** Incorporar ficha 360º, historial, saldos, búsqueda tolerante, detección de duplicados, importación/exportación y acciones masivas seguras.
10. **Promociones — Salud: funcional.** Añadir calendario, simulación antes de activar, detección de conflictos, aprobación opcional y resultados de ventas/margen por campaña.
11. **Seguridad — Salud: buena.** Ya existen usuarios, roles, búsqueda y permisos. Mejorar con matriz comparativa de roles, auditoría de cambios, sesiones activas, advertencias para permisos críticos y recuperación de acceso.
12. **Configuración — Salud: básica.** Crear un centro de configuración con estado de terminales, métodos de pago, integraciones, copias de seguridad y lista de tareas pendientes de configuración.

## Cambios transversales de mayor impacto

1. Cabecera estándar en todos los módulos: título, contexto, actualización, acción primaria y ayuda.
2. Sistema común de filtros: aplicar, limpiar, presets guardados y contador de filtros activos.
3. Estados homogéneos de carga, vacío y error, siempre con causa útil y botón de reintento.
4. Tablas consistentes con columnas configurables, ordenación, densidad, selección múltiple y exportación.
5. Centro de actividad con alertas, tareas pendientes, errores de sincronización y caducidades.
6. Accesibilidad: foco visible, navegación completa por teclado, mensajes anunciados, contraste y objetivos táctiles suficientes.

## Fases recomendadas

- **Fase 1 — Consistencia y confianza:** estados de error, cabeceras, filtros, última actualización, diagnóstico de conexión y accesibilidad básica.
- **Fase 2 — Productividad:** búsqueda global, favoritos, vistas guardadas, acciones masivas, exportación y accesos directos entre módulos.
- **Fase 3 — Operación inteligente:** comparativas, tendencias, anomalías, reposición sugerida, caducidades y alertas configurables.
- **Fase 4 — Gobierno:** auditoría transversal, aprobaciones, sesiones, trazabilidad y cuadros por rol.

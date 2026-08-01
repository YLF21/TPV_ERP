# Auditoría del bloque de control de alertas

Captura revisada: `audit-control-alerts-current.png`

## Veredicto

El bloque ya presenta una jerarquía clara: indicadores, frecuencia de actualización, acción manual y acción primaria aparecen en un único recorrido horizontal. La siguiente mejora debería centrarse en convertir información pasiva en acciones y mostrar mejor el estado del sistema.

## Mejoras recomendadas

1. Convertir **Nuevas** y **Pendientes** en accesos directos que apliquen el filtro correspondiente. Mantener el aspecto compacto y añadir estado seleccionado cuando el filtro esté activo.
2. Mostrar feedback durante la actualización: desactivar temporalmente el botón, cambiar el texto a **Actualizando…** y mostrar después **Actualizado hace X s**. Esto evita clics repetidos y confirma que la acción terminó.
3. Hacer más explícita la actualización automática: conservar el selector, pero indicar **Automática: 15 s** o **Automática desactivada** en lugar de depender solo del encabezado del grupo.
4. En anchos reducidos, mover el bloque completo a una segunda fila sin comprimir sus controles; el orden debe mantenerse como Alertas → Actualización → Añadir regla.
5. Revisar foco visible, navegación por teclado y anuncio accesible de cambios en los contadores. La captura no permite validar estos estados interactivos.

## Prioridad sugerida

- Alta: contadores clicables y feedback de actualización.
- Media: comportamiento responsive.
- Baja: texto más explícito del modo automático y pulido visual adicional.

## Límite de la revisión

La auditoría se limita al bloque superior derecho. El mensaje de carga de alertas de la vista de previsualización pertenece al entorno simulado y no se ha considerado un defecto del componente auditado.

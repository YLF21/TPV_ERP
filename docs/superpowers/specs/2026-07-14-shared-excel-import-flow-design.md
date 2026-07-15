# Flujo compartido de importación Excel

## Objetivo

Crear un asistente común de importación Excel reutilizable en APP VENTA y APP GESTIÓN, aplicable a documentos, stock, edición masiva y futuras pantallas.

## Flujo de usuario

1. El usuario pulsa `Importar Excel`.
2. Se abre el explorador de Windows.
3. El usuario selecciona un archivo `.xlsx`, `.xls` o `.csv`.
4. Se abre una ventana con visualizador de Excel en solo lectura.
5. Debajo del visualizador aparece el mapeo de atributos:
   - atributo del sistema,
   - caja para introducir letra de columna,
   - checkbox `Actualizar` para atributos que pueden actualizar el producto maestro.
6. El usuario relaciona cada atributo con una columna.
7. Cada fila del Excel representa un producto.
8. El análisis separa las filas en tablas:
   - productos no existentes,
   - productos con precio compra distinto,
   - productos aceptados,
   - filas con errores cuando aplique.
9. Cada tabla puede tener botón `Exportar` y una acción propia:
   - añadir/revisar,
   - actualizar producto maestro,
   - importar al documento.
10. Para productos no existentes:
   - el usuario puede añadir automáticamente con valores por defecto,
   - o revisar manualmente producto a producto abriendo `Añadir producto` precargado con la fila Excel.
11. En productos aceptados habrá un botón final `Importar Excel al documento`.

## Reglas acordadas

- Identificación de producto existente solo por `código` o `código de barras`.
- No se identificará por nombre.
- Si un atributo tiene checkbox `Actualizar` activado, se actualiza el producto maestro.
- Si el checkbox no está activado, el dato se usa solo para el documento/importación actual.
- Campos mínimos para crear un producto nuevo:
  - nombre,
  - código o código de barras,
  - precio compra,
  - precio venta.
- Si una celda de precio está vacía, se usa `0`.
- Para otros atributos `not null`, se aplican valores por defecto del sistema.
- El visualizador Excel es solo lectura.

## Base técnica

- La lectura y normalización del Excel vive en `frontend/packages/app-common/src/components/excelImport.ts`.
- Las pantallas deben aportar únicamente:
  - lista de atributos,
  - columnas obligatorias,
  - campos actualizables,
  - reglas de clasificación,
  - acción final.
- El componente visual debe vivir en `app-common` para ser usado por ambas apps.

## Recomendaciones de implementación

- Guardar plantillas de mapeo por pantalla/proveedor.
- Autodetectar columnas por cabecera y permitir corrección manual por letra.
- Mantener siempre el número de fila original del Excel.
- Antes de actualizar producto maestro, mostrar diferencia `actual` vs `Excel`.
- Exportar cada tabla de resultado con las mismas columnas visibles.
- Ejecutar importaciones finales de forma transaccional en backend cuando modifiquen documento o catálogo.

# Revisión visual de diferencias del importador Excel

## Trabajo realizado

- En Documento resumen y Productos importables, cabeceras y celdas actuales de BD en azul grisáceo, y valores nuevos de Excel en verde.
- Los valores distintos se marcan con `≠`, negrita y borde lateral ámbar; la celda nueva diferente recibe fondo ámbar. Se aplica también al detalle desplegado de la fila.
- Se resaltan todas las diferencias comparables, independientemente de las casillas Actualizar y de la protección de precios cero, según la confirmación del usuario. La leyenda explica que una diferencia no implica una escritura.
- El modo Mostrar solo valores nuevos mantiene únicamente las columnas Excel y sus marcas; no incluye el valor actual en texto, título ni detalle.
- Se conservan alturas de fila, virtualización, scroll, redimensionado y textos ES/EN/ZH. No se cambian pestañas ajenas, reglas de negocio ni el contenido de las exportaciones.

## Archivos modificados

- `frontend/packages/app-common/src/components/ExcelImportReviewTable.tsx`
- `frontend/packages/app-common/src/components/ExcelImportReviewTable.test.tsx`
- `frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx`
- `frontend/packages/app-common/src/components/SharedExcelImportDialog.test.tsx`
- `frontend/packages/app-common/src/styles/shared-excel-import.css`
- `frontend/packages/app-common/src/i18n/SharedManagementMessages.ts`
- `docs/excel-review-colors-2026-09-08.md` (este informe).

Artefactos de validación locales, ignorados por Git:

- `output/excel-review-colors-visual.cjs`, `output/excel-review-colors-visual.log`.
- `output/excel-review-colors-tests.log`, `output/excel-review-colors-tests-final.log`, `output/excel-review-colors-tests-accepted.log`.
- `output/excel-review-colors-build.log`, `output/excel-review-colors-bundle.log`, `output/excel-review-colors-build-accepted.log`, `output/excel-review-colors-bundle-accepted.log`.
- `output/playwright/excel-review-colors-summary-1366.png`.
- `output/playwright/excel-review-colors-importable-1920.png`.
- `output/playwright/excel-review-colors-only-new-1920.png`.
- Dist de VENTA/GESTIÓN regenerados por sus compilaciones habituales.

## Decisiones técnicas

- Reutilizar la tabla compartida y los datos de la vista previa. La comparación visual no utiliza `changes`, que representa solo los cambios autorizados por las casillas y por las reglas de escritura.
- No comparar atributos no asignados ni valores exclusivos del documento sin equivalente en BD. Un producto inexistente no tiene un valor anterior que comparar. Una celda asignada vacía sí se distingue de un valor actual no vacío.
- Evitar diferencias artificiales por comas/puntos decimales, ceros finales o las fechas DD-MM-AA/AAAA frente a ISO. Conservar los textos originales visibles y los ceros iniciales de identificadores. Los decimales simples se comparan como texto normalizado exacto, sin perder precisión ni redondear.
- Los estilos se limitan a las dos pestañas solicitadas. Una actualización posterior de la vista previa elimina las marcas cuando los valores coinciden.
- `frontend-design` orientó la jerarquía de color manteniendo la interfaz ERP, sin fuentes, controles ni dependencias nuevas. La comprobación visual utilizó Playwright disponible en el proyecto; el CLI no estaba instalado y no se añadió ninguna dependencia.

## Validaciones realizadas

- 113 pruebas correctas en cinco archivos: tabla, diálogo, altas manuales y traducciones. La primera ejecución señaló una expectativa nueva incorrecta sobre el número total de columnas; se sustituyó por comprobaciones semánticas de Cantidad en Stock/Almacén.
- Casos de diferencias sin escrituras previstas, precios cero, tres decimales, precisión alta en filas inválidas, fechas, impuestos, modo de precio, identificadores con ceros iniciales, inexistentes, campos sin asignación y actualización del detalle abierto.
- Exportaciones conservan las claves y valores originales; el resaltado no genera llamadas de escritura. Comprobado que la modalidad solo Excel no filtra valores de BD en el DOM ni en el detalle.
- Inspección de capturas reales del componente a 1366×768 y 1920×1080 con respuestas API de prueba interceptadas: no se lee ni modifica la BD habitual. Cabeceras y celdas distinguidas, alturas de 32 px y redimensionado por teclado de 8 px verificados. La virtualización de 5.000 filas sigue cubierta por la prueba existente.
- Contraste calculado: cabecera BD 8,62:1, cabecera Excel 8,01:1, textos de celdas al menos 14,64:1 y símbolo de diferencia 6,95:1; supera 4,5:1 para estos pares.
- Compilaciones VENTA/GESTIÓN y presupuesto de bundle comprobados; `git diff --check` sin errores.

## Riesgos detectados

- El resaltado informa de diferencias, no de lo que necesariamente se guardará. Los permisos, casillas, validaciones y protección del cero siguen controlando las escrituras.
- Los colores se aplican a la interfaz; no se cambia el formato de color del XLSX exportado.
- VENTA conserva sus avisos de proximidad al presupuesto de tamaño; no se han introducido cambios para ese asunto.
- La prueba visual es de interfaz con datos preparados, no una importación E2E de un libro real contra la BD del usuario.

## Trabajo pendiente

Sin implementación pendiente para el resaltado solicitado. No se ha ampliado el trabajo a colorear las exportaciones XLSX.

## Próximos pasos recomendados

Reabrir Documento resumen o Productos importables en la aplicación de desarrollo para revisar el resultado con el archivo del usuario. No hace falta reiniciar ni migrar el backend. Aplicar sigue siendo una revisión sin guardar fichas.

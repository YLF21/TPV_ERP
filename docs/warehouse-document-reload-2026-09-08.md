# Corrección de la recarga de facturas de entrada — 08/09/2026

## Trabajo realizado

Se corrigieron dos defectos del frontend:

1. Al guardar un documento con productos creados en el importador, el padre recibía la respuesta del servidor y reconstruía las líneas utilizando únicamente el catálogo cargado antes de esas altas. Además, borraba la caché de los productos importados. Por ello desaparecían sus códigos, las líneas quedaban inválidas y Guardar/Confirmar se deshabilitaban, aunque el borrador ya estaba guardado.
2. La aritmética binaria de JavaScript redondeaba ocho líneas un céntimo por debajo del cálculo decimal del backend. En el borrador revisado, la pantalla mostraba 1.993,13 € en lugar de 1.993,21 €.

La recarga conserva ahora los productos de la sesión y, si falta alguno al abrir un borrador, recupera los datos necesarios mediante una lectura del catálogo autorizado de Almacén. Nunca borra el identificador persistido por una ausencia en la caché. Los datos propios de la línea —nombre, cantidad, precio, descuento y precio personalizado— se conservan.

El cálculo de importes usa aritmética decimal exacta con enteros y redondeo HALF_UP. Mantiene el orden del backend: multiplicar precio por cantidad, redondear el subtotal de línea a céntimos, aplicar el descuento de línea y redondear; sumar líneas y aplicar el descuento del documento. Los precios unitarios conservan sus tres decimales hasta la multiplicación.

## Archivos modificados

- `frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx`: restauración de líneas, conservación y recuperación del catálogo, consumo de importes decimales.
- `frontend/packages/app-common/src/components/WarehouseDocumentReload.test.tsx`: siete pruebas de regresión de guardado, recarga, catálogo incompleto, errores y respuestas tardías.
- `frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx`: casos de céntimos y precios de tres decimales en las funciones del documento.
- `frontend/packages/app-common/src/money.ts`: operaciones decimales reutilizables, sin dependencias nuevas.
- `frontend/packages/app-common/src/money.test.ts`: catorce casos de aritmética decimal, incluyendo empates de redondeo, descuentos, cantidades fraccionarias y valores no finitos.
- `docs/warehouse-document-reload-2026-09-08.md`: este informe.

Los archivos que ya tenían cambios ajenos se editaron de forma localizada, conservando esos cambios. No se modificó `backend-saas/docker-compose.dev.yml`.

## Decisiones técnicas

- Se reutiliza `/products/warehouse-options`, que conserva los permisos y los datos de compra necesarios para Almacén. No se añadió ningún endpoint ni una petición por cada línea.
- La recuperación del catálogo solo se solicita si faltan productos persistidos. Las respuestas se cancelan o ignoran al cerrar/cambiar de documento; un fallo no genera un bucle de reintentos.
- Un producto realmente inexistente o una lectura fallida no habilitan artificialmente Guardar/Confirmar.
- Se conserva el token de la importación guardada y el flujo existente de actualización del proveedor. No cambian las reglas de confirmación.
- Se reutilizaron estilos y mensajes ES/EN/ZH existentes. No hay cambios de apariencia ni nuevos controles.
- No se alteraron backend, esquema, precios, descuentos, existencias ni registros del documento. Tampoco se cambiaron otros flujos de cálculo, como el TPV de venta.

## Validaciones realizadas

- Consulta PostgreSQL con transacción `READ ONLY`, `default_transaction_read_only=on`, límite de tiempo y `ROLLBACK`: borrador en estado BORRADOR, versión 1, 125 líneas, 1.149 unidades y cero referencias a productos ausentes.
- Comparación de las 125 líneas guardadas con las funciones decimales corregidas: cero discrepancias; total 1.993,21 €.
- 84 pruebas iniciales de recarga, documento, panel y dinero: correctas.
- Regresión final con diez archivos de pruebas y dos workers: **230/230 correctas**, incluyendo importador automático/manual, impresión, API frontend de Almacén y guard ES/EN/ZH. Log: `output/warehouse-document-reload-regression-final.log`.
- Las pruebas de Guardar y Confirmar utilizan API simulada y no escriben en la BD real.
- APP VENTA y APP GESTIÓN: comprobación TypeScript y compilación correctas. Log: `output/warehouse-document-reload-build.log`.
- Presupuesto de bundle: correcto. VENTA conserva avisos por proximidad a sus límites (JS 772.862/800.000 bytes; CSS 450.119/460.000). Log: `output/warehouse-document-reload-bundle.log`.
- `git diff --check`: correcto; solo avisos de normalización CRLF/LF.
- Los servidores frontend de los puertos 5173 y 5174 devuelven los módulos corregidos con HTTP 200. No se reiniciaron las aplicaciones ni el backend.

## Riesgos detectados

- La comprobación visual de la ventana concreta del usuario no se ha realizado. Que Vite sirva los módulos actualizados no demuestra por sí solo el estado de una ventana Electron ya abierta.
- Una ejecución concurrente de la suite ampliada falló en una aserción de traducción china del importador. La suite aislada pasó 135/135 y la repetición completa pasó 230/230 sin modificar esa prueba. Conviene vigilar su sincronización si reaparece.
- La recuperación usa el catálogo existente de Almacén. En catálogos muy grandes mantiene el coste actual de esa API; no se ha ampliado el alcance con una API nueva.
- La corrección decimal cubre este flujo de documentos de Almacén; no constituye una auditoría de todos los cálculos monetarios del ERP.

## Trabajo pendiente

- Verificar en la ventana del usuario que el borrador se muestra con códigos y líneas válidas y con el total 1.993,21 €.
- No se ha efectuado un guardado ni una confirmación real durante esta intervención, para no modificar la factura del usuario.

## Próximos pasos recomendados

Si la ventana continúa mostrando el estado anterior, volver a abrir el borrador ya guardado desde el listado. No repetir la importación ni crear otra factura. Revisar el documento antes de que el usuario decida guardarlo o confirmarlo.

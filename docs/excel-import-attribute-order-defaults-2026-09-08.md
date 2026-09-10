# Importador Excel: orden de atributos y valores predeterminados

Fecha: 08-09-2026. Alcance: última distribución y criterio de tipos/predeterminados confirmado por el usuario. No es una certificación de cierre de todos los planes históricos del importador.

## Trabajo realizado

Se sustituyó la distribución automática en bloques de diez por tres grupos explícitos:

| Columna 1 | Columna 2 | Columna 3 |
| --- | --- | --- |
| Código | Precio de venta | Tipo de producto |
| Código de barras | Precio de miembro | Usar precio |
| Nombre | Precio mayorista | Prohibido descuento |
| Descripción | Precio de oferta | Impuestos |
| Cantidad, solo Almacén | Descuento de oferta % | Impuestos incluidos |
| Precio de compra | Oferta desde | Cantidad por paquete |
| Descuento de compra | Oferta hasta | Stock mínimo |
| Familia / Subfamilia | Oferta activa | Stock máximo |
| Código de barras 2 | | |

- Stock omite Cantidad y conserva los grupos segundo y tercero en su sitio.
- Oferta activa reutiliza el desplegable editable existente: columna Excel o valor fijo `0 = No / false`, `1 = Sí / true`; vaciar elimina la asignación.
- Tipo de producto utiliza `1 = Unidad`, `2 = Peso`, `3 = Servicio`, tanto al leer una columna como al seleccionar un valor fijo.
- Configuraciones nuevas y «Limpiar configuración»: Tipo de producto 1, Prohibido descuento 0, Impuestos incluidos 1 e impuesto activo predeterminado de la tienda. Usar precio y Oferta activa conservan su comportamiento sin asignación automática nueva.
- Se preservan columnas, valores fijos y casillas guardadas. La autodetección rellena atributos sin asignación guardada, sin reemplazar la plantilla. Los antiguos valores fijos UNIT/WEIGHT/SERVICE se muestran como 1/2/3.
- Si los impuestos llegan después de abrir la ventana, se selecciona el predeterminado solamente mientras el usuario no haya editado ese control. Si no hay un único predeterminado activo, no se escoge un impuesto arbitrario.
- Comentarios desaparece de la configuración y de las tablas organizadas; se descartan sus asignaciones antiguas. No se borran comentarios de productos ni celdas de la cuadrícula original o su exportación.
- El orden de atributos también se utiliza en las tablas organizadas y sus columnas de exportación.
- La comparación visual y XLSX considera equivalentes los códigos numéricos y los tipos internos (por ejemplo, 2 y WEIGHT), evitando diferencias falsas.

## Archivos modificados

Archivos de producto modificados durante este alcance, aunque algunos ya contenían cambios anteriores:

1. `frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx`: grupos, selectores, predeterminados, compatibilidad de plantillas y comparación.
2. `frontend/packages/app-common/src/components/SharedExcelImportDialog.test.tsx`: cobertura de orden, controles, plantillas, impuestos asíncronos y tipos equivalentes.
3. `frontend/packages/app-common/src/components/StockScreen.tsx`: transmitir actividad y marca de impuesto predeterminado al importador.
4. `frontend/packages/app-common/src/components/StockScreen.test.tsx`: adaptar la expectativa de distribución a nueve filas.
5. `frontend/packages/app-common/src/styles/shared-excel-import.css`: nueve filas y posicionamiento explícito sin flujo automático por columnas.
6. `frontend/packages/app-common/src/api/productExcelImports.ts`: reflejar defaultTax y active, ya devueltos por el endpoint de impuestos existente.
7. `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPreviewService.java`: códigos numéricos de tipo y valor global de Oferta activa con las validaciones existentes.
8. `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportSummaryService.java`: equivalencia de tipos al resaltar diferencias en XLSX.
9. `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPresentation.java`: explicación EN/ZH de los tipos numéricos admitidos.
10. `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewServiceTest.java`: lectura numérica/global, oferta válida, incompleta y booleano inválido.
11. `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportSummaryServiceTest.java`: mantener el valor Excel 2 sin resaltarlo como diferencia frente a WEIGHT.
12. `docs/excel-import-attribute-order-defaults-2026-09-08.md`: este informe.

Los archivos temporales de la pantalla aislada se retiraron de `frontend/e2e/support`; sus copias, capturas y comprobación de navegador se conservaron en `output/playwright/excel-attribute-order-*`. Los registros de pruebas y compilación están en `output/excel-attribute-order-*`. No se modificó `backend-saas/docker-compose.dev.yml` ni se descartaron cambios ajenos.

## Decisiones técnicas

- Sin dependencias, migraciones ni cambios de enumeraciones de la BD. La numeración es un contrato de entrada del importador que se normaliza a UNIT/WEIGHT/SERVICE; esos valores antiguos siguen admitidos.
- Se reutilizaron ErpSelect, la API de impuestos y el normalizador de vista previa. No se introdujeron nuevos componentes ni endpoints.
- Los predeterminados no marcan automáticamente «Actualizar»: se mantiene la separación entre configuración de lectura y permiso explícito para modificar cada atributo.
- Familia conserva su control combinado y sus reglas anteriores de códigos de tres/seis dígitos.
- La habilidad frontend-design se aplicó conservando la interfaz ERP existente, campos de 52 px, controles cuadrados, colores y foco. No se introdujo otro lenguaje visual.
- Oferta activa = 1 continúa exigiendo la oferta completa correspondiente; no se relajaron validaciones para aceptar el nuevo selector.

## Validaciones realizadas

- Frontend: **264 pruebas aprobadas, 11 archivos**, incluyendo importador, tabla de revisión, altas manuales, Stock, Almacén, API y guardas ES/EN/ZH.
- Backend: **231 pruebas aprobadas, 9 suites, sin fallos ni omitidas**. Se ejecutó `ProductExcelImport*Test,!*PostgreSqlTest` en la copia aislada `output/warehouse-draft-backend`, sin reconstruir el backend en uso.
- Compilaciones de APP VENTA y APP GESTIÓN aprobadas, incluida comprobación TypeScript.
- Presupuesto de bundle aprobado, con avisos de proximidad al límite en APP VENTA.
- `git diff --check` y comprobación de espacios en los archivos nuevos del importador aprobados.
- Navegador real mediante Playwright en una pantalla aislada sin credenciales ni backend: ratón y teclado, selección 1/2/3, Oferta activa 0/1, columna AA, limpieza del control, anchura real de 52 px y ausencia del atributo Comentarios.
- Inspección de capturas en 1366×768 y 1920×1080; Stock y Almacén en ES, y Stock en EN/ZH. Se utilizaron 5.000 filas locales de prueba. En resolución baja se comprobó que el foco desplaza el panel para hacer visible el último atributo.
- El navegador de prueba solo presentó un 404 del favicon de la pantalla aislada; no errores de ejecución de la interfaz.

## Riesgos detectados

- APP VENTA está cerca del presupuesto existente: JS 772.862/800.000 bytes; CSS 450.119/460.000 bytes. No se ampliaron esos límites.
- En Almacén a 1366×768, el panel de configuración necesita un pequeño desplazamiento vertical para mostrar la última fila; es accesible con desplazamiento y teclado. En 1920×1080 se visualizan todos los atributos.
- Si una tienda carece de impuesto activo predeterminado, el usuario deberá escoger uno; no se presume ningún porcentaje.
- La versión actualmente abierta puede seguir cargando los recursos/backend anteriores hasta su actualización coordinada.

## Trabajo pendiente

- Recargar o desplegar los recursos compilados y reiniciar de forma coordinada el backend que se utiliza habitualmente, cuando no haya trabajo abierto que se pueda perder. No se cerraron ni reiniciaron las aplicaciones del usuario.
- Comprobar esta versión en la aplicación instalada. En este alcance no se ejecutaron operaciones sobre datos reales ni las suites PostgreSQL, y no se debe interpretar la revisión visual aislada como una prueba de escritura en producción.
- No quedan cambios de código pendientes identificados para el alcance de orden, selectores y predeterminados aquí descrito; la validación instalada anterior sigue pendiente.

## Próximos pasos recomendados

Con el trabajo guardado y las aplicaciones cerradas, activar frontend y backend conjuntamente. Después comprobar una configuración guardada y «Limpiar configuración», abrir el Excel y aplicar la vista previa antes de ejecutar cualquier alta o actualización.

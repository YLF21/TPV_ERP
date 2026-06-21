# Diseño del diagrama completo de base de datos

## Objetivo

Documentar el esquema PostgreSQL consolidado por las migraciones `V1` a `V13`: las 44 tablas, todos sus atributos, claves, relaciones y restricciones estructurales, además de los 5 triggers en una vista independiente que explique qué conectan y qué impiden o validan.

## Fuentes de verdad

- `backend/src/main/resources/db/migration/V1__esquema_inicial.sql`
- Todas las migraciones posteriores hasta `V13__idioma_usuario.sql`, aplicadas en orden.

El diagrama mostrará el estado final y no conservará índices o restricciones eliminados por migraciones posteriores. Los cambios de columna posteriores, como `producto_proveedor.referencia_proveedor` nullable, se reflejarán en su forma final.

## Entregables

1. Un documento Markdown editable con:
   - leyenda;
   - mapa general de los dominios y relaciones;
   - diagramas ER detallados por módulo;
   - catálogo de triggers;
   - notas sobre restricciones que Mermaid no pueda expresar directamente.
2. Diagramas renderizados en SVG y PNG, derivados de las fuentes editables.
3. Un inventario verificable de tablas, atributos, PK, FK, unicidades, checks e índices relevantes.

## Organización visual

Se usará un enfoque híbrido para evitar una única lámina ilegible:

1. Organización, instalación, licencias y seguridad.
2. Catálogo e inventario.
3. Clientes, proveedores y comerciales.
4. Documentos, líneas, pagos, vales y ventas aparcadas.
5. Núcleo fiscal VeriFactu y envíos.
6. Auditoría y copias de seguridad.

El mapa general mostrará las 44 tablas y sus conexiones principales. Cada vista modular mostrará todos los atributos de sus tablas. Las entidades compartidas se repetirán como referencias mínimas cuando haga falta mantener comprensible una relación entre módulos.

## Convenciones

- `PK`: clave primaria.
- `FK`: clave foránea.
- `UK`: restricción o índice único.
- `NN`: columna `NOT NULL`.
- `NULL`: columna opcional.
- Las relaciones indicarán cardinalidad y, cuando exista, `ON DELETE CASCADE`.
- Las claves foráneas compuestas se rotularán explícitamente.
- Los checks complejos, índices parciales y reglas cruzadas se describirán junto al diagrama correspondiente.

## Triggers

Los triggers se representarán aparte mediante un diagrama de flujo y una tabla explicativa. Para cada uno se documentará:

- evento y momento (`BEFORE`/`AFTER`, `INSERT`/`UPDATE`/`DELETE`);
- tabla disparadora;
- función ejecutada;
- tablas o filas consultadas;
- condición validada;
- operación restringida y código de error;
- carácter diferible cuando corresponda.

Los cinco triggers incluidos serán:

- `tr_producto_identificador_cruzado`;
- `tr_registro_fiscal_cadena`;
- `tr_cadena_fiscal_cabeza`;
- `tr_registro_fiscal_inmutable`;
- `tr_relacion_fiscal_inmutable`.

## Verificación

La generación incluirá comprobaciones automáticas que comparen el inventario documentado contra las sentencias `CREATE TABLE` y `CREATE TRIGGER` de las migraciones. La revisión final comprobará que:

- aparecen exactamente 44 tablas y 5 triggers;
- cada columna final aparece una vez en su entidad detallada;
- todas las FK se dibujan o se documentan como relación cruzada;
- los SVG y PNG se renderizan sin errores ni recortes;
- las restricciones eliminadas en migraciones posteriores no se presentan como vigentes.

## Fuera de alcance

- Inferir tablas desde las entidades Java cuando no existan en las migraciones.
- Conectarse a una base de datos externa o incluir datos reales.
- Modificar el esquema, las migraciones o el código de la aplicación.

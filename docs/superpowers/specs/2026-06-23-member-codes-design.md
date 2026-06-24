# Diseño de members y códigos comerciales

## Objetivo

Sustituir por completo el concepto `SOCIO` por `MEMBER` y asignar códigos legibles e inmutables a tiendas, clientes, members, proveedores y comerciales. La generación debe funcionar con varias tiendas y servidores que sincronizan datos sin introducir códigos duplicados.

## Terminología

El dominio, la API, las migraciones, las restricciones, los mensajes y las pruebas usarán `MEMBER` o `member`. No se conservarán nombres activos con `SOCIO` o `socio`; las migraciones históricas de Flyway no se modificarán.

Los prefijos aprobados son:

- `C`: cliente.
- `M`: member.
- `S`: supplier/proveedor.
- `CO`: commercial/comercial.

## Modelo de datos

### Tienda

`tienda` incorpora `code_store`, formado por tres dígitos (`001`, `002`, ...), único dentro de cada empresa e inmutable después de su asignación.

### Cliente y member

`cliente` incorpora:

- `code_client`: obligatorio, inmutable y único dentro de la empresa, con formato `C-{code_store}-{secuencia de seis dígitos}`.
- `client_code_store_id`: tienda que emitió `code_client`.
- `is_member`: booleano obligatorio, inicialmente `false`.
- `code_member`: inmutable y único dentro de la empresa; usa el formato `M-{code_store}-{secuencia de seis dígitos}` y solo se asigna cuando el cliente se convierte por primera vez en member.
- `member_code_store_id`: tienda donde se produjo el alta como member.
- `num_member`: dato opcional introducido por el usuario. Admite cualquier carácter, se recortan los espacios exteriores, una cadena vacía se convierte en `NULL` y el valor resultante es único por empresa.
- `member_since`: fecha del primer alta como member.
- `member_balance`: sustituye a `saldo_socio` y conserva precisión monetaria de dos decimales.

`code_member`, `member_code_store_id` y `member_since` son nulos mientras el cliente nunca haya sido member. Si deja de serlo, se conservan como histórico y no se vuelven a generar en una reactivación posterior.

### Proveedor y comercial

`proveedor` incorpora `code_supplier`, obligatorio, inmutable y único dentro de la empresa, con formato `S-{secuencia de seis dígitos}`.

`comercial` incorpora `code_commercial`, obligatorio, inmutable y único dentro de la empresa, con formato `CO-{secuencia de seis dígitos}`.

Estos códigos no contienen tienda porque ambas entidades tienen poca frecuencia de creación y ámbito empresarial.

### Contadores

Una tabla de contadores mantiene el último número de clientes y members por tienda. La reserva se realiza bloqueando la fila correspondiente dentro de la transacción, por lo que dos altas simultáneas en una tienda reciben números distintos.

Proveedores y comerciales calculan el siguiente número por empresa. Una restricción única detecta una colisión excepcional y el servicio reintenta automáticamente la asignación con el siguiente número. La colisión no se traslada al usuario como un error corregible manualmente.

Los contadores no reutilizan números eliminados ni retroceden. Los códigos son identificadores de negocio, mientras que los UUID existentes continúan siendo las claves técnicas y referencias entre tablas.

## Reglas de negocio

### Creación de cliente

La tienda activa se guarda como emisora. Dentro de la misma transacción se persisten los datos, se reserva el siguiente número local y se asigna `code_client`. El código no puede modificarse por la API.

Una importación masiva normaliza los NIF, ordena los registros por NIF y usa el UUID como desempate estable. Después reserva un rango consecutivo para toda la importación. Una creación individual consume solamente el siguiente número.

### Alta y baja como member

Al cambiar `is_member` de `false` a `true` por primera vez:

1. Se reserva el siguiente número de member en la tienda activa.
2. Se asignan `code_member` y `member_code_store_id`.
3. `member_since` toma la fecha actual del servidor.
4. La tarifa del cliente cambia a `MEMBER`.
5. Quedan habilitados los movimientos de `member_balance`.

Al desactivar el estado member:

- La tarifa vuelve a `VENTA`.
- Se conservan `code_member`, `member_since` y `member_balance` como histórico.
- Se rechazan nuevos movimientos de saldo mientras `is_member` sea `false`.

Al reactivarlo se reutilizan código y fecha originales. No se consume otro número.

La coherencia entre `is_member` y la tarifa se aplica en el dominio y mediante restricciones de base de datos. Un cliente activo como member usa `MEMBER`; uno que no lo es no puede usar esa tarifa.

## Renombrado integral

El cambio abarca:

- `CustomerRate.SOCIO` a `CustomerRate.MEMBER`.
- `PriceTier.SOCIO` a `PriceTier.MEMBER`.
- Columnas, entidades, repositorios y DTO relacionados con el saldo.
- La tabla de movimientos, que pasa a denominarse `member_balance_movement`.
- Valores persistidos de tarifas de clientes y productos.
- Rutas o campos públicos que contengan el término anterior.
- Mensajes e internacionalizaciones en español, inglés y chino.
- Pruebas, fixtures y documentación activa.

Las migraciones Flyway ya ejecutadas permanecen intactas; una migración nueva transforma el esquema y los datos.

## Migración de datos existentes

La migración se ejecuta en este orden:

1. Asigna `code_store` a las tiendas de cada empresa por nombre normalizado y UUID como desempate.
2. Para cada empresa, usa la tienda con menor `code_store` como emisora técnica de los clientes existentes.
3. Asigna `code_client` ordenando los clientes por NIF normalizado y UUID.
4. Deriva `is_member` del valor legado de tarifa.
5. Asigna `code_member` a los members existentes con el mismo orden por NIF.
6. Establece `member_since` de esos members en la fecha de ejecución de la migración, ya que no existe una fecha histórica fiable.
7. Renombra y transforma todos los valores y objetos de saldo y tarifa a `MEMBER`/`member`.
8. Asigna `code_supplier` por NIF normalizado y UUID.
9. Asigna `code_commercial` por nombre normalizado y UUID.
10. Inicializa los contadores con los máximos asignados y activa las restricciones finales de nulabilidad, formato, unicidad y coherencia.

La migración debe ser determinista e idempotente dentro de la ejecución transaccional de Flyway. No depende de que las tablas estén vacías.

## API y errores

Las respuestas de cliente exponen los nuevos campos. Las peticiones aceptan `is_member` y `num_member`, pero nunca permiten proporcionar ni modificar códigos automáticos, tiendas emisoras, saldo o fecha de alta.

Las respuestas de proveedor y comercial exponen sus códigos, que tampoco se aceptan como entrada editable. Los intentos de duplicar `num_member` producen un error de conflicto localizado. Los movimientos de saldo para no-members producen un error de regla de negocio localizado.

El renombrado de `SOCIO` a `MEMBER` es deliberadamente incompatible en la API: los consumidores deben usar el nuevo término tras la actualización.

## Concurrencia y sincronización

Los códigos de cliente y member incorporan `code_store`; servidores de tiendas distintas pueden asignarlos sin coordinar el número local. Dentro de una misma tienda, el bloqueo del contador serializa únicamente la reserva del número, no toda la creación.

Los códigos de proveedor y comercial se coordinan por empresa. Si servidores distintos proponen el mismo número, solo uno supera la restricción única; el otro recalcula y reintenta automáticamente dentro de un límite acotado. Si todos los reintentos fallan, se informa de un conflicto operativo sin guardar parcialmente el registro.

## Pruebas

La implementación seguirá TDD y cubrirá:

- Contrato y transformación completa de la migración PostgreSQL.
- Formatos, unicidad por empresa e inmutabilidad de todos los códigos.
- Numeración independiente entre tiendas para clientes y members.
- Altas simultáneas dentro de una tienda sin duplicados.
- Reserva ordenada por NIF en importaciones masivas.
- Primera alta, baja y reactivación de member.
- Bloqueo del saldo cuando `is_member` es `false` y conservación histórica.
- Unicidad de `num_member` aceptando caracteres arbitrarios.
- Reintento de colisiones de proveedor y comercial.
- Contratos de API con `MEMBER` y ausencia del término anterior.
- Mensajes localizados y regresión del conjunto completo de pruebas.

## Fuera de alcance

No se cambia el UUID como clave primaria, no se reutilizan números, no se permite editar códigos y no se diseña en esta entrega el mecanismo periódico de sincronización entre servidores. Los códigos quedan preparados para que dicho mecanismo pueda combinar datos sin colisiones entre tiendas.

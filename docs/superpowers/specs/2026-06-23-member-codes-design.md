# DiseÃ±o de members y cÃ³digos comerciales

## Objetivo

Sustituir por completo el concepto `SOCIO` por `MEMBER` y asignar cÃ³digos legibles e inmutables a tiendas, clientes, members, proveedores y comerciales. La generaciÃ³n debe funcionar con varias tiendas y servidores que sincronizan datos sin introducir cÃ³digos duplicados.

## TerminologÃ­a

El dominio, la API, las migraciones, las restricciones, los mensajes y las pruebas usarÃ¡n `MEMBER` o `member`. No se conservarÃ¡n nombres activos con `SOCIO` o `socio`; las migraciones histÃ³ricas de Flyway no se modificarÃ¡n.

Los prefijos aprobados son:

- `C`: cliente.
- `M`: member.
- `S`: supplier/proveedor.
- `CO`: commercial/comercial.

## Modelo de datos

### Tienda

`tienda` incorpora `code_store`, formado por tres dÃ­gitos (`001`, `002`, ...), Ãºnico dentro de cada empresa e inmutable despuÃ©s de su asignaciÃ³n.

### Cliente y member

`cliente` incorpora:

- `client_id`: obligatorio, inmutable y Ãºnico dentro de la empresa, con formato `C-{code_store}-{secuencia de seis dÃ­gitos}`.
- `client_code_store_id`: tienda que emitiÃ³ `client_id`.
- `is_member`: booleano obligatorio, inicialmente `false`.
- `member_id`: inmutable y Ãºnico dentro de la empresa; usa el formato `M-{code_store}-{secuencia de seis dÃ­gitos}` y solo se asigna cuando el cliente se convierte por primera vez en member.
- `member_code_store_id`: tienda donde se produjo el alta como member.
- `num_member`: dato opcional introducido por el usuario. Admite cualquier carÃ¡cter, se recortan los espacios exteriores, una cadena vacÃ­a se convierte en `NULL` y el valor resultante es Ãºnico por empresa.
- `member_since`: fecha del primer alta como member.
- `member_balance`: sustituye a `saldo_socio` y conserva precisiÃ³n monetaria de dos decimales.

`member_id`, `member_code_store_id` y `member_since` son nulos mientras el cliente nunca haya sido member. Si deja de serlo, se conservan como histÃ³rico y no se vuelven a generar en una reactivaciÃ³n posterior.

### Proveedor y comercial

`proveedor` incorpora `supplier_id`, obligatorio, inmutable y Ãºnico dentro de la empresa, con formato `S-{secuencia de seis dÃ­gitos}`.

`comercial` incorpora `commercial_id`, obligatorio, inmutable y Ãºnico dentro de la empresa, con formato `CO-{secuencia de seis dÃ­gitos}`.

Estos cÃ³digos no contienen tienda porque ambas entidades tienen poca frecuencia de creaciÃ³n y Ã¡mbito empresarial.

### Contadores

Una tabla de contadores mantiene el Ãºltimo nÃºmero de clientes y members por tienda. La reserva se realiza bloqueando la fila correspondiente dentro de la transacciÃ³n, por lo que dos altas simultÃ¡neas en una tienda reciben nÃºmeros distintos.

Proveedores y comerciales calculan el siguiente nÃºmero por empresa. Una restricciÃ³n Ãºnica detecta una colisiÃ³n excepcional y el servicio reintenta automÃ¡ticamente la asignaciÃ³n con el siguiente nÃºmero. La colisiÃ³n no se traslada al usuario como un error corregible manualmente.

Los contadores no reutilizan nÃºmeros eliminados ni retroceden. Los cÃ³digos son identificadores de negocio, mientras que los UUID existentes continÃºan siendo las claves tÃ©cnicas y referencias entre tablas.

## Reglas de negocio

### CreaciÃ³n de cliente

La tienda activa se guarda como emisora. Dentro de la misma transacciÃ³n se persisten los datos, se reserva el siguiente nÃºmero local y se asigna `client_id`. El cÃ³digo no puede modificarse por la API.

Una importaciÃ³n masiva normaliza los NIF, ordena los registros por NIF y usa el UUID como desempate estable. DespuÃ©s reserva un rango consecutivo para toda la importaciÃ³n. Una creaciÃ³n individual consume solamente el siguiente nÃºmero.

### Alta y baja como member

Al cambiar `is_member` de `false` a `true` por primera vez:

1. Se reserva el siguiente nÃºmero de member en la tienda activa.
2. Se asignan `member_id` y `member_code_store_id`.
3. `member_since` toma la fecha actual del servidor.
4. La tarifa del cliente cambia a `MEMBER`.
5. Quedan habilitados los movimientos de `member_balance`.

Al desactivar el estado member:

- La tarifa vuelve a `VENTA`.
- Se conservan `member_id`, `member_since` y `member_balance` como histÃ³rico.
- Se rechazan nuevos movimientos de saldo mientras `is_member` sea `false`.

Al reactivarlo se reutilizan cÃ³digo y fecha originales. No se consume otro nÃºmero.

La coherencia entre `is_member` y la tarifa se aplica en el dominio y mediante restricciones de base de datos. Un cliente activo como member usa `MEMBER`; uno que no lo es no puede usar esa tarifa.

## Renombrado integral

El cambio abarca:

- `CustomerRate.SOCIO` a `CustomerRate.MEMBER`.
- `PriceTier.SOCIO` a `PriceTier.MEMBER`.
- Columnas, entidades, repositorios y DTO relacionados con el saldo.
- La tabla de movimientos, que pasa a denominarse `member_balance_movement`.
- Valores persistidos de tarifas de clientes y productos.
- Rutas o campos pÃºblicos que contengan el tÃ©rmino anterior.
- Mensajes e internacionalizaciones en espaÃ±ol, inglÃ©s y chino.
- Pruebas, fixtures y documentaciÃ³n activa.

Las migraciones Flyway ya ejecutadas permanecen intactas; una migraciÃ³n nueva transforma el esquema y los datos.

## MigraciÃ³n de datos existentes

La migraciÃ³n se ejecuta en este orden:

1. Asigna `code_store` a las tiendas de cada empresa por nombre normalizado y UUID como desempate.
2. Para cada empresa, usa la tienda con menor `code_store` como emisora tÃ©cnica de los clientes existentes.
3. Asigna `client_id` ordenando los clientes por NIF normalizado y UUID.
4. Deriva `is_member` del valor legado de tarifa.
5. Asigna `member_id` a los members existentes con el mismo orden por NIF.
6. Establece `member_since` de esos members en la fecha de ejecuciÃ³n de la migraciÃ³n, ya que no existe una fecha histÃ³rica fiable.
7. Renombra y transforma todos los valores y objetos de saldo y tarifa a `MEMBER`/`member`.
8. Asigna `supplier_id` por NIF normalizado y UUID.
9. Asigna `commercial_id` por nombre normalizado y UUID.
10. Inicializa los contadores con los mÃ¡ximos asignados y activa las restricciones finales de nulabilidad, formato, unicidad y coherencia.

La migraciÃ³n debe ser determinista e idempotente dentro de la ejecuciÃ³n transaccional de Flyway. No depende de que las tablas estÃ©n vacÃ­as.

## API y errores

Las respuestas de cliente exponen los nuevos campos. Las peticiones aceptan `is_member` y `num_member`, pero nunca permiten proporcionar ni modificar cÃ³digos automÃ¡ticos, tiendas emisoras, saldo o fecha de alta.

Las respuestas de proveedor y comercial exponen sus cÃ³digos, que tampoco se aceptan como entrada editable. Los intentos de duplicar `num_member` producen un error de conflicto localizado. Los movimientos de saldo para no-members producen un error de regla de negocio localizado.

El renombrado de `SOCIO` a `MEMBER` es deliberadamente incompatible en la API: los consumidores deben usar el nuevo tÃ©rmino tras la actualizaciÃ³n.

## Concurrencia y sincronizaciÃ³n

Los cÃ³digos de cliente y member incorporan `code_store`; servidores de tiendas distintas pueden asignarlos sin coordinar el nÃºmero local. Dentro de una misma tienda, el bloqueo del contador serializa Ãºnicamente la reserva del nÃºmero, no toda la creaciÃ³n.

Los cÃ³digos de proveedor y comercial se coordinan por empresa. Si servidores distintos proponen el mismo nÃºmero, solo uno supera la restricciÃ³n Ãºnica; el otro recalcula y reintenta automÃ¡ticamente dentro de un lÃ­mite acotado. Si todos los reintentos fallan, se informa de un conflicto operativo sin guardar parcialmente el registro.

## Pruebas

La implementaciÃ³n seguirÃ¡ TDD y cubrirÃ¡:

- Contrato y transformaciÃ³n completa de la migraciÃ³n PostgreSQL.
- Formatos, unicidad por empresa e inmutabilidad de todos los cÃ³digos.
- NumeraciÃ³n independiente entre tiendas para clientes y members.
- Altas simultÃ¡neas dentro de una tienda sin duplicados.
- Reserva ordenada por NIF en importaciones masivas.
- Primera alta, baja y reactivaciÃ³n de member.
- Bloqueo del saldo cuando `is_member` es `false` y conservaciÃ³n histÃ³rica.
- Unicidad de `num_member` aceptando caracteres arbitrarios.
- Reintento de colisiones de proveedor y comercial.
- Contratos de API con `MEMBER` y ausencia del tÃ©rmino anterior.
- Mensajes localizados y regresiÃ³n del conjunto completo de pruebas.

## Fuera de alcance

No se cambia el UUID como clave primaria, no se reutilizan nÃºmeros, no se permite editar cÃ³digos y no se diseÃ±a en esta entrega el mecanismo periÃ³dico de sincronizaciÃ³n entre servidores. Los cÃ³digos quedan preparados para que dicho mecanismo pueda combinar datos sin colisiones entre tiendas.

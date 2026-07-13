# Compatibilidad del almacén de secretos con el proxy de Spring

## Problema

El perfil `dev` arranca correctamente PostgreSQL y aplica Flyway hasta `V59`, pero Spring no puede crear el bean `paymentSecretStore`. `ProtectedPaymentSecretStore` está declarado `final` y sus métodos usan `@Transactional`, por lo que el proxy CGLIB requerido por Spring no puede heredar de la clase.

## Diseño aprobado

- Mantener `PaymentSecretStore` como interfaz pública del componente.
- Mantener las transacciones en los métodos de `ProtectedPaymentSecretStore`.
- Hacer que `ProtectedPaymentSecretStore` sea extensible eliminando exclusivamente el modificador `final`.
- No cambiar la configuración global de proxies ni la protección DPAPI.

## Verificación

- Añadir una prueba de contexto mínima que registre `ProtectedPaymentSecretStore` como bean y confirme que Spring puede crear su proxy transaccional.
- Ejecutar primero la prueba contra el código actual para reproducir el fallo.
- Aplicar el cambio mínimo y comprobar que la prueba pasa.
- Ejecutar las pruebas del módulo de secretos y una comprobación de arranque del backend con el perfil `dev`.

## Alcance

No modifica el formato de secretos, la base de datos, las migraciones, las APIs ni la configuración de proveedores de pago.

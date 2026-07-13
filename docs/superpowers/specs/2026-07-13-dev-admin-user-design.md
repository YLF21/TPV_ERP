# Usuario ADMIN para pruebas locales

## Objetivo

Permitir iniciar sesión en el perfil `dev` con el usuario `ADMIN` y la contraseña `0000`, incluso después de recrear la base de datos local.

## Diseño aprobado

- Ampliar exclusivamente `DevSampleDataSeeder`.
- Crear un rol protegido `ADMIN` para la tienda demo.
- Crear un usuario protegido y activo `ADMIN` con contraseña BCrypt generada desde `0000`.
- Asociar el usuario a la tienda demo mediante `usuario_tienda`.
- Usar identificadores UUID deterministas y operaciones `ON CONFLICT` para que el seeding sea idempotente.
- Mantener intacto el usuario `VENDEDOR/0000`.
- No añadir migraciones ni datos ADMIN a perfiles distintos de `dev`.

## Seguridad y alcance

La contraseña conocida solo existe en datos locales de demostración cargados cuando está activo el perfil `dev`. El hash se genera con el `PasswordEncoder` configurado; no se almacena la contraseña en texto plano en PostgreSQL.

## Verificación

- Añadir una prueba del seeder que confirme rol protegido, usuario protegido/activo, asociación con la tienda y compatibilidad de la contraseña `0000`.
- Confirmar que ejecutar el seeder más de una vez no duplica el rol, el usuario ni su asociación.
- Ejecutar las pruebas de autenticación y del seeder.
- Reiniciar el backend con el perfil `dev` y verificar el login `ADMIN/0000` contra la API.

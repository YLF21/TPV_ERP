# Diseño De Refactorización Del Backend

## Objetivo

Revisar y refactorizar `backend/` y `license-issuer/` para reducir duplicación,
separar responsabilidades reutilizables y acortar clases cuando mejore su
legibilidad, sin modificar la API REST, el esquema PostgreSQL, los formatos
criptográficos ni el comportamiento observable.

## Criterios

- Mantener Java 25, Spring Boot 4.0.6, Maven, PostgreSQL 18 y las dependencias actuales.
- No reducir líneas mediante nombres abreviados, expresiones crípticas o clases genéricas sin cohesión.
- Extraer código únicamente cuando exista duplicación real, una responsabilidad independiente o una reutilización próxima clara.
- Mantener las entidades centradas en invariantes de dominio.
- Mantener controladores pequeños y sin lógica de negocio.
- Evitar cambios de formato en licencias y backups.
- No modificar las migraciones ya aplicadas salvo que aparezca un defecto funcional demostrado.

## Comentarios

Los comentarios usarán `//` dentro del método, junto al bloque que explican.

Se comentarán:

- Métodos públicos con una secuencia funcional relevante.
- Decisiones de seguridad, criptografía, transacciones o persistencia que no sean evidentes.
- Algoritmos, formatos binarios y operaciones atómicas.
- Reglas cuyo motivo no pueda deducirse directamente del nombre.

No se comentarán:

- Getters, constructores y delegaciones triviales.
- Validaciones obvias expresadas claramente por el código.
- Cada línea o asignación.
- El final de cada método.

## Utilidades Reutilizables

### Validación

Crear utilidades pequeñas para textos obligatorios, normalización a mayúsculas
y mensajes seguros de excepciones. Las entidades conservarán sus invariantes,
pero dejarán de repetir implementaciones idénticas.

### Criptografía Y Codificación

Centralizar:

- SHA-256 en hexadecimal.
- Generación de secretos aleatorios URL-safe.
- Conversión de claves públicas PEM.
- Base64 y lectura estricta cuando comparta reglas.

No se unificarán artificialmente Gson y Jackson. El emisor y el backend deben
conservar la serialización canónica exigida por sus firmas.

### Contexto De Instalación

Crear un componente de contexto local para obtener la instalación y tienda
únicas. Sustituirá las búsquedas repetidas con `findAll().stream().findFirst()`
y concentrará los errores de instalación incompleta.

## División De Responsabilidades

### Backups

`BackupService` se dividirá, como mínimo, en:

- Coordinación de configuración y ejecución.
- Política de nombres y retención.
- Creación y restauración PostgreSQL cifrada.

`BackupFileCrypto` y `RecoveryKeyPackage` mantendrán sus formatos actuales. Solo
se extraerán operaciones internas cuando reduzcan complejidad sin ocultar el
protocolo binario.

### Seguridad

`SecurityAdministrationService` separará:

- Administración de usuarios.
- Administración de roles y permisos.
- Cambio protegido de contraseña `ADMIN`.

La protección de `ADMIN` seguirá aplicada en entidades y servicios.

### Licencias

Compartir utilidades PEM, hash y validación textual, manteniendo separados:

- Decodificación criptográfica.
- Previsualización y activación transaccional.
- Lectura de la clave pública del proveedor.

### Terminales E Instalación

Reutilizar el contexto local y la generación segura de credenciales. La
validación de cupos permanecerá en el servicio de terminales.

### Emisor

Reducir duplicación de PEM/Base64 y separar el guardado de archivos de la
emisión criptográfica. La interfaz Swing solo coordinará campos y acciones.

## Compatibilidad

La refactorización debe conservar:

- Rutas, cuerpos y respuestas de la API.
- Nombres de tablas, columnas y migraciones.
- Sobres de licencia versión 1.
- Formatos de backup y recuperación versión 1.
- Sesiones, permisos, cupos y reglas de `ADMIN`.
- Perfiles y variables de entorno.

## Pruebas

El trabajo se realizará por módulos mediante caracterización y TDD:

1. Añadir o reforzar una prueba que describa el comportamiento conservado.
2. Ejecutarla antes del cambio.
3. Aplicar una extracción o división pequeña.
4. Ejecutar pruebas específicas.
5. Ejecutar la suite completa al cerrar cada módulo.

La verificación final exigirá:

- `backend`: pruebas completas con PostgreSQL local.
- `license-issuer`: pruebas completas.
- Compilación limpia de ambos proyectos.
- `git diff --check`.
- Comparación de rutas API y migraciones para confirmar que no cambiaron.

## Resultado Esperado

- Clases de aplicación más pequeñas y cohesionadas.
- Menos implementaciones duplicadas.
- Utilidades reutilizables con nombres específicos.
- Comentarios útiles sin ruido.
- Igual comportamiento demostrado por pruebas.


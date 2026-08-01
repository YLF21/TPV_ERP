# TPV ERP

Sistema de punto de venta y gestión compuesto por aplicaciones locales,
servicios SaaS y herramientas operativas. El repositorio es un monorepo, pero
cada módulo conserva su propio ciclo de compilación.

## Módulos

| Ruta | Responsabilidad | Tecnología |
| --- | --- | --- |
| `backend` | API local, ventas, stock, facturación, VeriFactu y copias de seguridad | Java 25, Spring Boot, PostgreSQL |
| `frontend` | APP VENTA y APP GESTIÓN de escritorio | React, TypeScript, Electron |
| `backend-saas` | Licencias, sincronización, administración y portal de cliente | Java 25, Spring Boot, PostgreSQL |
| `frontend-saas` | Panel web de administración y portal SaaS | React, TypeScript, Vite |
| `payment-terminal-bridge` | Puente local seguro y adaptadores de datáfonos | Java 25 |
| `license-issuer` | Emisor offline de licencias firmadas | Java 25, Swing |

La documentación operativa y las especificaciones están en `docs/`. Cada
módulo contiene además un README específico.

## Requisitos

- JDK 25.
- Node.js 24 y npm.
- PostgreSQL 17 o posterior.
- Windows para Electron, DPAPI y las integraciones locales de hardware.

## Inicio local

El backend ya no selecciona `dev` implícitamente. El perfil debe indicarse de
forma expresa:

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:TPV_DB_PASSWORD = "<password-local>"
$env:TPV_DEV_UNLICENSED_ACCESS_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

El servidor escucha en `127.0.0.1` por defecto. Para acceso desde la red local,
configura `TPV_SERVER_ADDRESS` deliberadamente y aplica las reglas de firewall
correspondientes.

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev:venta
```

Para el SaaS:

```powershell
cd backend-saas
Copy-Item .env.example .env
docker compose up -d
$env:SPRING_PROFILES_ACTIVE = "demo"
.\mvnw.cmd spring-boot:run
```

Antes de gestionar claves de integraciones, define
`TPV_SAAS_SECRET_ENCRYPTION_KEY` con 32 bytes aleatorios codificados en Base64.
La clave debe proceder del gestor de secretos del entorno y no de Git.

## Verificación

```powershell
cd backend
.\mvnw.cmd verify

cd ..\backend-saas
.\mvnw.cmd verify

cd ..\payment-terminal-bridge
.\mvnw.cmd verify

cd ..\license-issuer
.\mvnw.cmd verify

cd ..\frontend
npm.cmd test
npm.cmd run build

cd ..\frontend-saas
npm.cmd test
npm.cmd run build
```

GitHub Actions ejecuta estas verificaciones en cada pull request. Dependabot
revisa semanalmente Maven, npm y las propias acciones.

## Seguridad

- No guardes `.env`, tokens, certificados, licencias emitidas, solicitudes de
  instalación, PKCS#12 ni claves privadas en Git.
- Las contraseñas SaaS nuevas usan bcrypt. Los hashes SHA-256 históricos se
  actualizan automáticamente tras una autenticación correcta.
- Las claves de integraciones SaaS nuevas se cifran con AES-256-GCM.
- Las credenciales del panel SaaS solo permanecen en memoria y se eliminan al
  cerrar o recargar la pestaña.
- El puente de pagos escucha en loopback, exige un token fuerte y autoriza los
  plugins por digest SHA-256.

Consulta `docs/payment-terminal-live-bridge.md` para datáfonos y
`docs/verifactu-certificate-windows-operations.md` para certificados VeriFactu.

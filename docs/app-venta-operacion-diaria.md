# Operación diaria de APP VENTA

## Caja y turno

La sección `Ajustes > Terminal > Caja y turno` permite consultar y operar la caja del
terminal autenticado:

1. Preparar el fondo inicial entre sesiones.
2. Abrir la caja.
3. Registrar entradas y retiradas de efectivo.
4. Consultar efectivo esperado, disponible y resumen diario.
5. Cerrar la caja indicando fondo retenido y retirada final.

Las entradas extraordinarias requieren usuario y contraseña de un autorizador. El
backend conserva la validación de permisos y la auditoría; la interfaz no sustituye
esas comprobaciones.

Permisos admitidos por el backend:

- `ADMIN`
- `VENTA` o `CASH_OPERATE` para apertura, cierre y movimientos habituales.
- `GESTION_CUENTAS` o `CASH_CONFIGURE` para fondos entre sesiones.
- `CASH_READ` para consulta y conciliación.

## Estado operativo

La sección `Ajustes > Sistema > Estado operativo` reúne:

- Estado de VERI*FACTU, certificado, entorno y envío automático.
- Desviación del reloj del sistema.
- Cola de sincronización: pendientes, en envío, enviados y errores.
- Reintento controlado del siguiente envío fiscal.
- Vaciado manual de la cola de sincronización.

Estas acciones están pensadas para administración y soporte. Si el usuario no tiene
permisos, la tarjeta muestra el estado como no disponible sin revelar detalles
internos.

## Prueba local recomendada

1. Iniciar PostgreSQL y el backend con el perfil de desarrollo.
2. Iniciar APP VENTA y acceder con un usuario `ADMIN`.
3. Abrir `Ajustes > Terminal`, preparar fondo, abrir caja y registrar una entrada y
   una retirada.
4. Verificar el efectivo esperado y cerrar la caja.
5. Abrir `Ajustes > Sistema` y comprobar que VERI*FACTU, reloj y sincronización
   responden.
6. Ejecutar:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test -- --run packages/app-common/src/components/CashOperationsCard.test.tsx packages/app-common/src/components/OperationalStatusCard.test.tsx
npm.cmd run build --workspace @tpverp/app-venta
```

## Límites que siguen dependiendo de terceros

La comunicación física de tarjeta requiere el SDK, servicio local, credenciales y
homologación oficial del proveedor del datáfono. La interfaz y los simuladores no
reemplazan esa certificación. Del mismo modo, la activación fiscal real exige
certificado y configuración válida del entorno de producción.

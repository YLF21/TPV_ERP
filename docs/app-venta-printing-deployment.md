# Preparación de impresión de APP VENTA en Windows

La impresora de tickets es hardware local del terminal. Antes de poner un puesto
en producción, un técnico debe ejecutar el preflight desde PowerShell con
permisos de administrador:

```powershell
.\tools\prepare-app-venta-printing.ps1
```

El script:

- configura `Spooler` con inicio automático y lo inicia si fuera necesario;
- habilita el registro `Microsoft-Windows-PrintService/Operational`;
- lee la cola de tickets de `%APPDATA%\Electron\hardware-config.json`;
- verifica que la cola configurada exista;
- no imprime ningún documento de prueba.

Puede indicarse una cola explícita antes de guardar la configuración local:

```powershell
.\tools\prepare-app-venta-printing.ps1 -PrinterName "RP-12N"
```

Para una comprobación posterior de sólo lectura, sin modificar servicios ni
registros:

```powershell
.\tools\prepare-app-venta-printing.ps1 -CheckOnly
```

APP VENTA supervisa la disponibilidad de la cola mientras permanece abierta.
Una incidencia de impresión se avisa al operador, pero nunca revierte ni repite
un cobro confirmado. El ticket autoritativo permanece en backend y se puede
reimprimir desde la gestión de tickets cuando el hardware vuelva a estar
disponible.

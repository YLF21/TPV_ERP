# Despliegue del puente como servicio de Windows

Los scripts despliegan el JAR como servicio mediante WinSW, validan SHA-256 y
guardan copias recuperables antes de cada actualización. No descargan ni confían
automáticamente en ejecutables, SDK o drivers.

1. Obtenga Java 25 (o la versión fijada por el proyecto) y WinSW x64 desde sus
   canales oficiales. Verifique y conserve sus hashes.
2. Copie `bridge-config.example.json`, adapte perfiles/rutas y mantenga LIVE
   desactivado hasta instalar un driver certificado.
3. Ejecute `Install-TpvPaymentBridge.ps1` como administrador pasando rutas y
   hashes. Después almacene el token con `--store-secret
   windows:bridge-http-token` y arranque el servicio.
4. Instale drivers con `Install-TpvPaymentAdapter.ps1`. El script copia el JAR,
   actualiza la allow-list `pluginDigests`, ejecuta `--validate` y restaura el
   backup si falla.
5. Actualice el puente con `Update-TpvPaymentBridge.ps1`; si la validación o el
   arranque falla, repone automáticamente JAR y configuración anteriores.

`--diagnose` muestra el catálogo real de adaptadores, modos, protocolos,
conexiones, certificación LIVE y salud de cada perfil. Un adaptador sin manifest
válido o sin certificación LIVE se rechaza de forma cerrada.

WinSW y los SDK propietarios no se versionan en este repositorio. Sus licencias,
firmas y homologación corresponden al proveedor/adquirente y al modelo físico.

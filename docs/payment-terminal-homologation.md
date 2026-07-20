# Matriz de conformidad y homologación de datáfonos

El contrato universal está implementado, pero un proveedor/modelo solo puede
habilitarse en LIVE cuando su `AdapterManifest` declara un driver físico
certificado y el banco/adquirente ha aprobado la combinación de versión,
comercio, país, protocolo y firmware.

| Área | SIMULATED automatizado | Driver LIVE de laboratorio | Homologación adquirente |
|---|---:|---:|---:|
| Separación estricta SIMULATED/LIVE | Sí | Obligatoria | Evidencia |
| Manifest, modelo, protocolo y conexión | Sí | Obligatoria | Aprobación |
| Aprobación, rechazo y cancelación | Sí | Obligatoria | Obligatoria |
| Idempotencia y repetición de la misma clave | Sí | Obligatoria | Obligatoria |
| Corte antes/después de autorizar y QUERY | Sí | Obligatoria | Obligatoria |
| Reinicio del servicio y recuperación | Sí | Obligatoria | Evidencia |
| Void y devolución total/parcial | Sí | Según capacidades | Según contrato |
| Recibo/reimpresión sin datos sensibles | Sí | Obligatoria | Obligatoria |
| Conciliación y descuadre | Sí | Según capacidades | Según contrato |
| Instalación, actualización y rollback | Scripts | Ensayo de caja | Evidencia |

## Puertas de activación LIVE

1. El JAR y todas sus dependencias están firmados o verificados por SHA-256 y
   figuran en `pluginDigests`.
2. El manifest indica `LIVE`, protocolo/conexión compatibles y
   `certifiedLiveDriverInstalled=true`.
3. `--validate` y `--diagnose` terminan sin perfiles no disponibles.
4. Se ejecuta la matriz anterior con el modelo y firmware exactos de la caja.
5. Se conserva el acta o identificador de homologación del adquirente.
6. Se verifica que ningún log, respuesta o recibo contenga PAN completo, PIN,
   CVV/CVC, pista o criptogramas EMV.

PAYTEF y PAYCOMET ya pueden usar el mismo gateway universal del backend. Hasta
que se instale un plugin oficial compatible, el catálogo del puente no anuncia
capacidades LIVE y la configuración queda bloqueada de forma segura. Redsys y
Global Payments siguen exactamente la misma regla.

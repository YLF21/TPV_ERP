# Adaptador universal de Global Payments

Este módulo proporciona un único `PaymentTerminalAdapter` para Global Payments
sin acoplar TPV ERP a un modelo de datáfono. El adaptador selecciona internamente
un `GlobalPaymentsTerminalDriver` mediante el parámetro `protocol` del perfil.

## Estado actual

- `SIMULATED`: incluido y apto para pruebas locales; nunca mueve dinero.
- `AUTO`: selecciona un driver solo si existe exactamente uno compatible.
- Protocolos LIVE: quedan cerrados con `DRIVER_NOT_INSTALLED` hasta instalar el
  driver construido con el SDK certificado por el adquirente.

El modo simulado acepta cualquier valor de `model`, por lo que puede probarse
antes de conocer el dispositivo físico:

```json
{
  "provider": "GLOBAL_PAYMENTS",
  "adapterId": "globalpayments-universal",
  "model": "UNIVERSAL",
  "connectionType": "SIMULATED",
  "secretReference": null,
  "parameters": {
    "protocol": "SIMULATED",
    "simulationOutcome": "APPROVED"
  }
}
```

`simulationOutcome` puede ser `APPROVED`, `DECLINED`, `CANCELLED`, `PENDING`,
`TIMEOUT`, `REVIEW_REQUIRED` o `ERROR`.

## Añadir un SDK físico posteriormente

El nuevo JAR implementa `GlobalPaymentsTerminalDriver` y registra la clase en:

```text
META-INF/services/com.tpverp.bridge.globalpayments.GlobalPaymentsTerminalDriver
```

El driver declara su identificador de protocolo, modelos/conexiones compatibles
y capacidades homologadas. El perfil cambia `protocol`, `connectionType`,
`model` y `secretReference`; APP VENTA, el backend y el contrato HTTP no cambian.

No se debe implementar un protocolo propietario a partir de suposiciones. El
driver LIVE necesita el SDK, documentación, licencia, terminal de pruebas y
homologación proporcionados por Global Payments o por el adquirente.

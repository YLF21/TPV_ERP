# Plantilla de datos para emitir una licencia

## Objetivo

Crear un archivo de texto sencillo que sirva como formulario previo para reunir
los datos necesarios antes de generar una licencia mediante `license-issuer`.

## Contenido

La plantilla incluira:

- Datos de la solicitud de instalacion: ruta del JSON recibido del TPV.
- Datos fiscales: NIF y tipo de contribuyente.
- Datos comerciales: empresa y tienda.
- Vigencia: fechas inicial y final en formato ISO `AAAA-MM-DD`.
- Cupos: terminales Windows y PDA.
- Regimen fiscal: `IVA` o `IGIC`.
- Rutas del almacen PKCS#12 y del archivo de licencia de salida.
- Una lista breve de pasos para generar e importar la licencia.

## Seguridad

El archivo no solicitara ni almacenara la contrasena del PKCS#12, claves
privadas, firmas ni contenido criptografico. Esos elementos se gestionaran
exclusivamente desde la aplicacion emisora.

## Formato

El resultado sera `plantilla-licencia.txt`, escrito en texto plano y con
marcadores claramente reemplazables. No sera una licencia valida por si mismo:
el archivo `license.json` final debe generarse y firmarse con `license-issuer`.

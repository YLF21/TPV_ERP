# License Template Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear una plantilla de texto segura y clara para recopilar los datos necesarios para emitir una licencia TPV ERP.

**Architecture:** Se añadira un unico archivo de texto en la raiz del repositorio. La plantilla reflejara los campos aceptados por `LicenseDetails` y explicara que la licencia final debe generarse criptograficamente con `license-issuer`.

**Tech Stack:** Texto plano, aplicacion Java Swing `license-issuer`.

---

### Task 1: Crear y verificar la plantilla

**Files:**
- Create: `plantilla-licencia.txt`

- [ ] **Step 1: Crear el formulario**

Incluir estos campos:

```text
Solicitud de instalacion
NIF
Tipo de contribuyente
Empresa
Tienda
Fecha inicial
Fecha final
Terminales Windows
Terminales PDA
Regimen fiscal
Ruta PKCS#12
Ruta de salida
```

- [ ] **Step 2: Añadir instrucciones de uso y seguridad**

Explicar cómo obtener la solicitud, abrir `license-issuer`, completar los datos,
generar `license.json` e importarlo. Indicar expresamente que la contraseña y la
clave privada no deben escribirse en la plantilla.

- [ ] **Step 3: Verificar el contenido**

Run:

```powershell
Get-Content plantilla-licencia.txt
rg -n "NIF|SOCIEDAD|AUTONOMO|IVA|IGIC|Windows|PDA|license.json|contrasena|clave privada" plantilla-licencia.txt
```

Expected: el archivo se puede leer y contiene todos los conceptos obligatorios.

- [ ] **Step 4: Revisar el estado Git**

Run:

```powershell
git diff --check
git status --short
```

Expected: sin errores de formato y `plantilla-licencia.txt` aparece como archivo nuevo.

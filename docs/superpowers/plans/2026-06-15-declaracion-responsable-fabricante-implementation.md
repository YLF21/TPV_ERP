# Declaracion Responsable del Fabricante Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generar una plantilla Word editable y visualmente verificada para la declaracion responsable de TPV ERP.

**Architecture:** Un script reproducible con `python-docx` generara el documento A4 a partir del contenido normativo aprobado. La salida se sometera a comprobaciones estructurales y a renderizado DOCX a PNG para revisar todas las paginas.

**Tech Stack:** Python, python-docx, OOXML y LibreOffice headless.

---

### Task 1: Generar la plantilla Word

**Files:**
- Create: `tools/generate_responsible_declaration_template.py`
- Create: `documentos/Plantilla_Declaracion_Responsable_TPV_ERP.docx`

- [ ] **Step 1: Implementar el generador**

Crear un documento A4 con estilos administrativos, nota de uso, apartados
`1.a)` a `1.l)`, anexo opcional, campos sombreados y bloque de firma.

- [ ] **Step 2: Ejecutar el generador**

Run:

```powershell
& $BUNDLED_PYTHON tools/generate_responsible_declaration_template.py
```

Expected: se crea `documentos/Plantilla_Declaracion_Responsable_TPV_ERP.docx`.

- [ ] **Step 3: Comprobar estructura y contenido**

Run:

```powershell
& $BUNDLED_PYTHON -c "from docx import Document; d=Document(r'documentos/Plantilla_Declaracion_Responsable_TPV_ERP.docx'); t='\n'.join(p.text for p in d.paragraphs); assert all(f'1.{c})' in t for c in 'abcdefghijkl'); assert 'DECLARACIÓN RESPONSABLE DEL SISTEMA INFORMÁTICO DE FACTURACIÓN' in t"
```

Expected: exit code 0.

### Task 2: Renderizar y revisar

**Files:**
- Read: `documentos/Plantilla_Declaracion_Responsable_TPV_ERP.docx`
- Create temporarily: `documentos/render-declaracion-responsable/page-*.png`

- [ ] **Step 1: Renderizar el documento**

Run:

```powershell
& $BUNDLED_PYTHON $DOCS_RENDERER documentos/Plantilla_Declaracion_Responsable_TPV_ERP.docx --output_dir documentos/render-declaracion-responsable
```

Expected: una imagen PNG por cada pagina.

- [ ] **Step 2: Inspeccionar todas las paginas**

Abrir cada PNG y comprobar que no existen cortes, solapamientos, campos
ilegibles, saltos incoherentes ni problemas en encabezado o pie.

- [ ] **Step 3: Corregir y repetir si es necesario**

Modificar el generador, regenerar el DOCX y volver a renderizar hasta que todas
las paginas superen la inspeccion visual.

- [ ] **Step 4: Verificacion final**

Run:

```powershell
git diff --check
Get-Item documentos/Plantilla_Declaracion_Responsable_TPV_ERP.docx
Get-ChildItem documentos/render-declaracion-responsable/page-*.png
```

Expected: sin errores de formato, DOCX no vacio y PNGs presentes.

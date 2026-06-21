# Database Diagram Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a complete, verified, editable and rendered representation of the PostgreSQL schema produced by migrations V1 through V13.

**Architecture:** A focused Python generator reads the migration files in Flyway order, consolidates the supported `CREATE TABLE` and `ALTER TABLE` changes, and emits Mermaid ER sources plus a Markdown catalog. A verification test checks the expected 44-table/5-trigger inventory and critical post-migration changes; Mermaid CLI renders every source to SVG and PNG.

**Tech Stack:** Python 3 standard library, unittest, Mermaid ER/flowchart syntax, `@mermaid-js/mermaid-cli` 11.15.0, Markdown.

---

## File structure

- `tools/generate_database_diagrams.py`: migration ordering, DDL extraction, final-schema consolidation, module assignment, Mermaid/Markdown generation and CLI entry point.
- `tools/test_generate_database_diagrams.py`: focused regression tests for inventory, altered columns, relationships and triggers.
- `docs/database/README.md`: generated entry point, legend, global map, module index and restriction catalog.
- `docs/database/diagrams/*.mmd`: editable Mermaid sources for the global map, six detailed modules and trigger flow.
- `docs/database/rendered/*.svg`: scalable renders.
- `docs/database/rendered/*.png`: raster renders.

### Task 1: Build the consolidated schema extractor

**Files:**
- Create: `tools/generate_database_diagrams.py`
- Create: `tools/test_generate_database_diagrams.py`

- [ ] **Step 1: Write failing inventory and migration-consolidation tests**

Define tests that call these exact public functions:

```python
from pathlib import Path
from generate_database_diagrams import load_schema

MIGRATIONS = Path(__file__).parents[1] / "backend/src/main/resources/db/migration"

def test_consolidated_inventory():
    schema = load_schema(MIGRATIONS)
    assert len(schema.tables) == 44
    assert set(schema.triggers) == {
        "tr_producto_identificador_cruzado",
        "tr_registro_fiscal_cadena",
        "tr_cadena_fiscal_cabeza",
        "tr_registro_fiscal_inmutable",
        "tr_relacion_fiscal_inmutable",
    }

def test_later_migrations_are_applied():
    schema = load_schema(MIGRATIONS)
    assert schema.tables["producto_proveedor"].columns["referencia_proveedor"].nullable
    assert "idioma" in schema.tables["usuario"].columns
    assert "codigo_vale" in schema.tables["documento_pago"].columns
    assert "num_ticket" in schema.tables["documento"].columns
```

- [ ] **Step 2: Run the tests and confirm the extractor is absent**

Run: `python -m unittest tools/test_generate_database_diagrams.py -v`

Expected: failure importing `generate_database_diagrams`.

- [ ] **Step 3: Implement the schema model and DDL subset parser**

Implement immutable data records `Column`, `ForeignKey`, `Constraint`, `Trigger`, mutable `Table`, and `Schema`. Implement `ordered_migrations(path)`, `split_sql_statements(sql)`, `split_top_level(body)`, `parse_create_table(statement, schema)`, `apply_alter_table(statement, schema)`, `parse_trigger(statement, schema)`, and `load_schema(path)`.

The parser must support the exact migration constructs in scope:

```python
CREATE_TABLE_RE = re.compile(r"^create\s+table\s+(\w+)\s*\((.*)\)\s*$", re.I | re.S)
ALTER_TABLE_RE = re.compile(r"^alter\s+table\s+(\w+)\s+(.*)$", re.I | re.S)
TRIGGER_RE = re.compile(
    r"^create\s+(constraint\s+)?trigger\s+(\w+)\s+(.*?)\s+on\s+(\w+)\s+(.*?)execute\s+function\s+(\w+)\s*\(\s*\)\s*$",
    re.I | re.S,
)
```

Statement splitting must preserve PL/pgSQL dollar-quoted bodies. Top-level comma splitting must track parentheses and quoted strings. Table parsing must distinguish columns from table-level `primary key`, `unique`, `foreign key`, `constraint` and `check` clauses. Alter parsing must apply `ADD COLUMN`, `ALTER COLUMN ... DROP NOT NULL`, and added constraints while ignoring DML and index-only statements.

- [ ] **Step 4: Run extractor tests**

Run: `python -m unittest tools/test_generate_database_diagrams.py -v`

Expected: all Task 1 tests pass.

- [ ] **Step 5: Commit the extractor**

```powershell
git add tools/generate_database_diagrams.py tools/test_generate_database_diagrams.py
git commit -m "tools: extract consolidated database schema"
```

### Task 2: Generate the editable diagrams and restriction catalog

**Files:**
- Modify: `tools/generate_database_diagrams.py`
- Modify: `tools/test_generate_database_diagrams.py`
- Create: `docs/database/README.md`
- Create: `docs/database/diagrams/overview.mmd`
- Create: `docs/database/diagrams/01-organization-security.mmd`
- Create: `docs/database/diagrams/02-catalog-inventory.mmd`
- Create: `docs/database/diagrams/03-parties.mmd`
- Create: `docs/database/diagrams/04-documents-payments.mmd`
- Create: `docs/database/diagrams/05-verifactu.mmd`
- Create: `docs/database/diagrams/06-operations-backup.mmd`
- Create: `docs/database/diagrams/07-triggers.mmd`

- [ ] **Step 1: Add failing generation coverage tests**

```python
def test_every_table_is_in_exactly_one_detailed_module(tmp_path):
    schema = load_schema(MIGRATIONS)
    manifest = generate_artifacts(schema, tmp_path)
    detailed = [name for name in manifest.modules if name != "overview"]
    emitted = [table for name in detailed for table in manifest.modules[name]]
    assert len(emitted) == 44
    assert set(emitted) == set(schema.tables)

def test_every_foreign_key_and_trigger_is_documented(tmp_path):
    schema = load_schema(MIGRATIONS)
    manifest = generate_artifacts(schema, tmp_path)
    readme = (tmp_path / "README.md").read_text(encoding="utf-8")
    assert manifest.foreign_key_count == sum(len(t.foreign_keys) for t in schema.tables.values())
    assert all(name in readme for name in schema.triggers)
```

- [ ] **Step 2: Run tests and confirm generation functions are absent**

Run: `python -m unittest tools/test_generate_database_diagrams.py -v`

Expected: failure importing or calling `generate_artifacts`.

- [ ] **Step 3: Implement deterministic module assignment and Mermaid emitters**

Add `MODULE_TABLES`, containing all 44 names exactly once, and implement:

```python
def render_entity(table: Table) -> str: ...
def render_relationship(fk: ForeignKey) -> str: ...
def render_module(schema: Schema, names: tuple[str, ...]) -> str: ...
def render_overview(schema: Schema) -> str: ...
def render_triggers(schema: Schema) -> str: ...
def render_readme(schema: Schema, manifest: Manifest) -> str: ...
def generate_artifacts(schema: Schema, output_dir: Path) -> Manifest: ...
```

Entity rows must contain the final SQL type, `PK`, `FK`, `UK`, and `NN` markers. Relationship labels must include FK column names, composite mappings and `CASCADE` where applicable. `README.md` must list all checks, unique/partial indexes, composite keys and the five trigger rules that Mermaid ER syntax cannot fully encode.

- [ ] **Step 4: Generate sources and verify tests**

Run: `python tools/generate_database_diagrams.py --migrations backend/src/main/resources/db/migration --output docs/database`

Expected: eight `.mmd` files and `docs/database/README.md` are generated.

Run: `python -m unittest tools/test_generate_database_diagrams.py -v`

Expected: all tests pass.

- [ ] **Step 5: Commit editable artifacts**

```powershell
git add tools/generate_database_diagrams.py tools/test_generate_database_diagrams.py docs/database/README.md docs/database/diagrams
git commit -m "docs: generate complete database diagrams"
```

### Task 3: Render SVG and PNG assets

**Files:**
- Create: `docs/database/rendered/*.svg`
- Create: `docs/database/rendered/*.png`

- [ ] **Step 1: Render every Mermaid source as SVG**

```powershell
Get-ChildItem docs/database/diagrams/*.mmd | ForEach-Object {
    npx --yes @mermaid-js/mermaid-cli -i $_.FullName -o (Join-Path 'docs/database/rendered' ($_.BaseName + '.svg')) -b transparent
}
```

Expected: eight SVG files with no Mermaid syntax errors.

- [ ] **Step 2: Render every Mermaid source as PNG**

```powershell
Get-ChildItem docs/database/diagrams/*.mmd | ForEach-Object {
    npx --yes @mermaid-js/mermaid-cli -i $_.FullName -o (Join-Path 'docs/database/rendered' ($_.BaseName + '.png')) -b white -s 2
}
```

Expected: eight non-empty PNG files.

- [ ] **Step 3: Verify asset inventory and dimensions**

Run a Python check using Pillow to assert 8 SVG files, 8 PNG files, non-zero file sizes, and positive PNG dimensions. If Pillow is unavailable, use the bundled workspace Python runtime after loading workspace dependencies.

- [ ] **Step 4: Commit rendered assets**

```powershell
git add docs/database/rendered
git commit -m "docs: render database diagram assets"
```

### Task 4: Final audit and visual review

**Files:**
- Modify if necessary: `docs/database/README.md`
- Modify if necessary: `docs/database/diagrams/*.mmd`
- Modify if necessary: `docs/database/rendered/*`

- [ ] **Step 1: Run the full automated audit**

Run: `python -m unittest tools/test_generate_database_diagrams.py -v`

Expected: all tests pass, including exact totals of 44 tables and 5 triggers.

- [ ] **Step 2: Regenerate from a clean output directory and compare**

Generate into a temporary directory and compare its Markdown and Mermaid files byte-for-byte with `docs/database`; this proves deterministic generation.

- [ ] **Step 3: Open the overview, each detailed SVG and trigger SVG in the in-app browser**

Verify visually that text is readable, no entity is clipped, relationship lines render, and the trigger diagram separates connections from restrictions. Regenerate at an adjusted width/scale if any render is clipped.

- [ ] **Step 4: Check repository hygiene**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; unrelated pre-existing user changes remain untouched.

- [ ] **Step 5: Commit any final presentation corrections**

```powershell
git add docs/database tools/generate_database_diagrams.py tools/test_generate_database_diagrams.py
git commit -m "docs: finalize database schema reference"
```

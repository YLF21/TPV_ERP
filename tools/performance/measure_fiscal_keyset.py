"""Opt-in fiscal SQL benchmark: synthetic own-schema data, never application tables.

Uses only Python stdlib and psql inside an explicitly selected disposable Docker
PostgreSQL. No schema is dropped. Generated files contain synthetic data/plans,
not credentials. This measures SELECTs, not fiscal writes or HTTP/JVM throughput.
"""

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import textwrap
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = ROOT / "backend/src/main/java/com/tpverp/backend/verifactu/FiscalRecordReadRepository.java"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"


def run(command, statement=None):
    result = subprocess.run(command, input=statement, text=True, encoding="utf-8",
                            capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def sql_parts():
    source = REPOSITORY.read_text(encoding="utf-8")
    base = textwrap.dedent(re.search(r'BASE_FROM = """(.*?)""";', source, re.S)[1]).strip()
    cursor_section = source[source.index("var anchorComparison"):source.index("public Optional<Row> findDetail")]
    projection = textwrap.dedent(re.search(r'return jdbc.query\("""(.*?)"""', cursor_section, re.S)[1]).strip()
    # Fail closed if production no longer has the keyset structure measured here.
    for fragment in ('record.secuencia <= :snapshotSequence', 'record.secuencia ',
                     ':anchorSequence order by record.secuencia ', ', record.id ', ' limit :limit'):
        if fragment not in cursor_section:
            raise RuntimeError("Repository cursor changed; update the benchmark extraction")
    if re.search(r'record\.snapshot|xml|artefacto', projection, re.I):
        raise RuntimeError("Fiscal list projection contains a heavy payload")
    filters = {}
    for expression in re.findall(r'filters.add\("(.*?)"\);', source):
        key = re.search(r':([A-Za-z]+)', expression)[1]
        filters[key] = expression.replace("\\\\", "\\")
    max_sequence = re.search(r'var value = jdbc.queryForObject\("""(.*?)"""',
                             source[source.index("public long maxSequence"):], re.S)[1]
    return source, projection, base, filters, textwrap.dedent(max_sequence).strip()


def literal(value):
    return str(value) if isinstance(value, int) else "'" + str(value).replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--user", default="fiscal_test")
    parser.add_argument("--records", type=int, default=1_000_000)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--confirm-isolated", action="store_true", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"codex-verifactu-[A-Za-z0-9-]+", args.container):
        parser.error("Only explicitly named codex-verifactu disposable containers are allowed")
    if not re.fullmatch(r"[a-z0-9_]+_test", args.database):
        parser.error("The isolated database name must end in _test")
    if args.records < 1000 or not 1 <= args.repetitions <= 10:
        parser.error("records >= 1000 and 1 <= repetitions <= 10 required")
    # Request only harmless inspect fields. Never read/log Config.Env/passwords.
    bindings = json.loads(run(["docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", args.container]))
    ports = bindings.get("5432/tcp") or []
    if not ports or any(p["HostIp"] != "127.0.0.1" or p["HostPort"] == "5432" for p in ports):
        parser.error("Container must publish PostgreSQL on non-default loopback only")
    schema = "fiscal_scale_" + uuid.uuid4().hex
    args.output.mkdir(parents=True, exist_ok=False)
    command = ["docker", "exec", "-i", args.container, "psql", "-X", "-q", "-A", "-t",
               "-v", "ON_ERROR_STOP=1", "-U", args.user, "-d", args.database]

    def sql(statement, timeout=120):
        return run(command, f"set search_path to {schema}, pg_catalog; set statement_timeout='{timeout}s';\n" + statement)

    def write(name, content):
        (args.output / name).write_text(content, encoding="utf-8")

    source, projection, base, filters, max_sequence = sql_parts()
    metadata = json.loads(sql("""select json_build_object('database',current_database(),
        'version',version(),'settings',(select json_object_agg(name,setting) from pg_settings
        where name in ('shared_buffers','work_mem','effective_cache_size','jit',
        'max_parallel_workers_per_gather','random_page_cost','seq_page_cost')));"""))
    metadata.update(schema=schema, synthetic_records=args.records, repetitions=args.repetitions,
                    utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                    source_sha256=hashlib.sha256(source.encode()).hexdigest(),
                    limitations=["Synthetic two-tenant distribution; no real data",
                                 "Minimal read columns + snapshot marker; no fiscal write triggers/FKs",
                                 "Read indexes extracted from V210/V211; no index migration applied",
                                 "SELECT projection/filter text extracted from current Java",
                                 "No HTTP/JVM/network, exports, concurrency or cold-cache claim"])
    write("metadata.json", json.dumps(metadata, indent=2))
    ddl = f"""create schema {schema};
set search_path to {schema}, pg_catalog;
create table registro_fiscal (
 id uuid primary key, cadena_id uuid not null, empresa_id uuid not null,
 tienda_id uuid not null, instalacion_id uuid not null, documento_id uuid,
 secuencia bigint not null, operacion varchar(16) not null,
 tipo_documento_fiscal varchar(4) not null, serie_numero varchar(64) not null,
 fecha_expedicion date not null, generado_en timestamptz not null,
 modo_fiscal varchar(16) not null, cuota_total numeric(19,2), importe_total numeric(19,2),
 huella_anterior varchar(64), huella varchar(64) not null, snapshot jsonb not null,
 unique(cadena_id, secuencia));
create table estado_envio_fiscal (
 registro_id uuid primary key, estado varchar(24) not null, actualizado_en timestamptz not null);
"""
    index_sources = []
    for filename in ("V210__fiscal_read_indexes.sql", "V211__fiscal_cursor_read_indexes.sql"):
        content = (MIGRATIONS / filename).read_text(encoding="utf-8")
        index_sources.append({"file": filename, "sha256": hashlib.sha256(content.encode()).hexdigest()})
        for index in re.findall(r"create index(?: concurrently)?\s+\w+\s+on registro_fiscal\s*\(.*?;", content, re.S | re.I):
            ddl += index.replace(" concurrently", "") + "\n"
    write("schema.sql", ddl)
    sql(ddl)
    main_rows = args.records * 4 // 5
    seed = f"""insert into registro_fiscal
select md5('synthetic-record-'||n)::uuid,md5('synthetic-chain-'||tenant)::uuid,
 md5('synthetic-company-'||tenant)::uuid,md5('synthetic-store-'||tenant)::uuid,
 md5('synthetic-install-'||tenant)::uuid,md5('synthetic-document-'||n)::uuid,n,
 case when n%31=0 then 'ANULACION' else 'ALTA' end,
 case when n%17=0 then 'R5' when n%11=0 then 'F1' else 'F2' end,
 'S-'||lpad(n::text,9,'0'),(timestamptz '2024-10-01 00:00:00+00'+n*interval '1 minute')::date,
 timestamptz '2024-10-01 00:00:00+00'+n*interval '1 minute',
 case when n%23=0 then 'NO_VERIFACTU' else 'VERIFACTU' end,2.10,12.10,
 repeat(md5((n-1)::text),2),repeat(md5(n::text),2),
 jsonb_build_object('synthetic',true,'payload',repeat(md5(n::text),64))
from (select n,case when n<={main_rows} then 1 else 2 end as tenant
 from generate_series(1,{args.records}) n) seeded;
insert into estado_envio_fiscal select id,
 case when secuencia%101=0 then 'PENDIENTE' when secuencia%89=0 then 'RECHAZADO' else 'ACEPTADO' end,
 generado_en+interval '1 minute' from registro_fiscal where modo_fiscal='VERIFACTU';
"""
    write("seed.sql", f"set search_path to {schema}, pg_catalog;\n" + seed)
    print(f"Creating {args.records:,} synthetic records in own schema {schema}", flush=True)
    started = time.monotonic()
    sql(seed, timeout=600)
    sql("vacuum (analyze) registro_fiscal; vacuum (analyze) estado_envio_fiscal;", timeout=600)
    metadata.update(seed_analyze_seconds=round(time.monotonic()-started, 3),
                    index_sources=index_sources,
                    exact_counts=json.loads(sql("""select json_build_object('records',count(*),
                        'primary_scope',count(*) filter (where empresa_id=md5('synthetic-company-1')::uuid))
                        from registro_fiscal;""")),
                    tables=json.loads(sql(f"""select json_agg(row_to_json(t)) from (
                        select relname,n_live_tup,pg_total_relation_size(relid) bytes
                        from pg_stat_user_tables where schemaname='{schema}' order by relname) t;""")))
    write("metadata.json", json.dumps(metadata, indent=2))
    parameters = {"companyId": str(uuid.UUID(hashlib.md5(b"synthetic-company-1").hexdigest())),
                  "storeId": str(uuid.UUID(hashlib.md5(b"synthetic-store-1").hexdigest())),
                  "installationId": str(uuid.UUID(hashlib.md5(b"synthetic-install-1").hexdigest())),
                  "snapshotSequence": main_rows, "anchorSequence": 2**63-1, "limit": 51}

    def bind(query, values):
        return re.sub(r":([A-Za-z]+)", lambda m: literal(values[m[1]]), query)

    def query(extra=None, anchor=None, previous=False):
        values = parameters | (extra or {})
        if anchor is not None:
            values["anchorSequence"] = anchor
        conditions = "".join(" and " + filters[key] for key in (extra or {}))
        order = "asc" if previous else "desc"
        comparison = ">" if previous else "<"
        return bind(projection + "\n" + base + conditions +
                    f" and record.secuencia <= :snapshotSequence and record.secuencia {comparison} :anchorSequence "
                    f"order by record.secuencia {order}, record.id {order} limit :limit", values)

    late = dt.date(2024, 10, 1)+dt.timedelta(minutes=main_rows)
    queries = {
        "snapshot_max": bind(max_sequence, parameters),
        "first_page": query(),
        "deep_page": query(anchor=10_000),
        "previous_deep_page": query(anchor=10_000, previous=True),
        "filtered_recent_30d": query({"dateFrom": str(late-dt.timedelta(days=30)), "dateTo": str(late)}),
        "filtered_old_30d": query({"dateFrom": "2024-10-01", "dateTo": "2024-10-30"}),
        "filtered_number_prefix": query({"documentNumberPrefix": "s-000010%"}),
        "filtered_number_exact": query({"documentNumber": "s-000010001"}),
        "filtered_operation_type_mode": query({"operation": "ALTA", "documentType": "R5", "fiscalMode": "VERIFACTU"}),
    }
    summaries = []
    for name, statement in queries.items():
        write(name+".sql", f"set search_path to {schema}, pg_catalog;\n"+statement+";\n")
        samples = []
        for repetition in range(args.repetitions):
            plan = json.loads(sql("explain (analyze, buffers, format json) "+statement))
            write(f"{name}.{repetition+1}.json", json.dumps(plan, indent=2))
            samples.append(plan[0]["Execution Time"])
        root = plan[0]["Plan"]
        summary = dict(query=name, median_ms=round(statistics.median(samples), 3),
                       min_ms=min(samples), max_ms=max(samples), rows=root["Actual Rows"],
                       shared_hit_blocks=root.get("Shared Hit Blocks",0),
                       shared_read_blocks=root.get("Shared Read Blocks",0))
        summaries.append(summary)
        print(f"{name}: {summary['median_ms']} ms median; {summary['rows']} rows", flush=True)
        write("summary.json", json.dumps(summaries, indent=2))
    write("cleanup.sql", f"-- Only after manually verifying the own-schema UUID in the isolated test DB.\ndrop schema {schema} cascade;\n")
    print("Complete. Own schema retained; no application/public table was read or changed.", flush=True)


if __name__ == "__main__":
    main()

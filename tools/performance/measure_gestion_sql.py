"""Reproducible read-query benchmark on an explicitly selected, isolated PostgreSQL.

Uses Python's standard library and psql. Never copies business rows: LIKE copies
columns, CHECK constraints and indexes, but not foreign keys or triggers. This is
a SELECT benchmark, not a substitute for Flyway/JPA integrity integration tests.
The default volumes are 500k documents, 1.5m lines and 500k alerts across 4 stores.
PGPASSWORD must contain the temporary test database password; it is never logged.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
import uuid


TABLES = (
    "tienda", "documento", "documento_linea", "documento_relacion", "producto",
    "producto_identificador", "factura_rectificacion_venta", "control_evento", "control_alerta",
)
REPO = Path(__file__).resolve().parents[2]
OVERVIEW_SOURCE = REPO / "backend/src/main/java/com/tpverp/backend/ui/GestionSalesOverviewRepository.java"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--psql", default=r"C:\Program Files\PostgreSQL\18\bin\psql.exe")
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--database", required=True, help="Temporary database with migrated public schema")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--documents", type=int, default=500_000)
    parser.add_argument("--alerts", type=int, default=500_000)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.port == 5432 or not 1024 <= args.port <= 65535:
        parser.error("Select the explicit non-default port of the isolated test PostgreSQL")
    if min(args.documents, args.alerts) < 100 or args.repetitions < 1:
        parser.error("Volumes must be >= 100, repetitions >= 1")
    if not os.environ.get("PGPASSWORD"):
        parser.error("PGPASSWORD must be set for the isolated test database")
    args.output.mkdir(parents=True, exist_ok=False)
    schema = "perf_gestion_" + uuid.uuid4().hex
    command = [args.psql, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
               "-h", "127.0.0.1", "-p", str(args.port), "-U", args.user, "-d", args.database]

    def sql(statement, scoped=True, timeout_seconds=90):
        prefix = (f"set search_path to {schema}, public; set statement_timeout='{timeout_seconds}s';\n"
                  if scoped else "")
        result = subprocess.run(command, input=prefix + statement, text=True,
                                encoding="utf-8", capture_output=True, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    metadata = json.loads(sql("""
        select json_build_object('version',version(),'database',current_database(),
            'migration',(select version from public.flyway_schema_history
                         where success and version is not null order by installed_rank desc limit 1),
            'settings',(select json_object_agg(name,setting) from pg_settings where name in
              ('shared_buffers','work_mem','effective_cache_size','max_parallel_workers_per_gather',
               'jit','random_page_cost','seq_page_cost')));
        """, scoped=False))
    metadata.update(schema=schema, documents=args.documents, lines=3 * args.documents,
                    alerts=args.alerts, repetitions=args.repetitions,
                    date="2026-09-17", source_sha256=hashlib.sha256(OVERVIEW_SOURCE.read_bytes()).hexdigest(),
                    limitations=["Synthetic distribution; no production-data copy",
                                 "Copied columns, defaults, CHECKs, indexes; no FK/triggers",
                                 "Overview SQL extracted verbatim from current repository",
                                 "Alert SQL is equivalent to Criteria/entity-graph reads; no JVM/HTTP timing",
                                 "First execution is not guaranteed cold; repeated runs retain normal server cache",
                                 "Concurrent UI/test load and hardware can affect timings"])
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(f"Schema {schema}; seed {args.documents} documents, {args.alerts} alerts", flush=True)
    ddl = "create schema " + schema + ";\nset search_path to " + schema + ", public;\n"
    ddl += "\n".join(f"create table {schema}.{table} (like public.{table} including all);" for table in TABLES)
    (args.output / "schema.sql").write_text(ddl, encoding="utf-8")
    sql(ddl, scoped=False)
    # No copied default may point at a public sequence, since even a synthetic INSERT
    # must never mutate shared application state.
    sequence_defaults = sql(f"""select count(*) from information_schema.columns
        where table_schema='{schema}' and column_default like '%nextval%';""")
    if sequence_defaults != "0":
        raise RuntimeError("Unsafe sequence default in copied schema; no data has been inserted")
    seed = seed_sql(args.documents, args.alerts)
    (args.output / "seed.sql").write_text(seed, encoding="utf-8")
    seed_started = time.monotonic()
    sql(seed, timeout_seconds=600)
    for table in TABLES:
        sql(f"vacuum (analyze) {schema}.{table};")
    metadata["seed_seconds"] = round(time.monotonic() - seed_started, 3)
    metadata["tables"] = json.loads(sql(f"""select json_agg(row_to_json(stats)) from (
        select relname,n_live_tup,pg_total_relation_size(relid) as bytes
        from pg_stat_user_tables where schemaname='{schema}' order by relname) stats;"""))
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(f"Seed/analyze complete in {metadata['seed_seconds']}s", flush=True)
    alerts = alert_queries()
    # A read-only A/B comparison of an explicit event/store predicate. This uses
    # existing indexes and does not change either the application or the schema.
    variants = {name + "_event_store": alerts[name].replace(
                    "where a.tienda_id=md5('store-1')::uuid",
                    "where e.tienda_id=md5('store-1')::uuid and a.tienda_id=md5('store-1')::uuid")
                for name in ("alerts_page_7d", "alerts_count_7d", "alerts_groups_7d",
                             "alerts_page_366d", "alerts_page_366d_offset100000",
                             "alerts_dashboard_recent_all_history")}
    queries = overview_queries() | alerts | variants
    rows = []
    for name, query in queries.items():
        (args.output / f"{name}.sql").write_text(
            f"set search_path to {schema}, public;\n" + query + ";\n", encoding="utf-8")
        samples = []
        for repeat in range(args.repetitions):
            plan = json.loads(sql("explain (analyze, buffers, format json) " + query))
            (args.output / f"{name}.{repeat + 1}.json").write_text(json.dumps(plan, indent=2), encoding="utf-8")
            samples.append(plan[0]["Execution Time"])
        root = plan[0]["Plan"]
        row = dict(query=name, first_ms=samples[0], median_ms=round(statistics.median(samples), 3),
                   min_ms=min(samples), max_ms=max(samples), rows=root["Actual Rows"],
                   shared_hit_blocks=root.get("Shared Hit Blocks", 0),
                   shared_read_blocks=root.get("Shared Read Blocks", 0),
                   temp_read_blocks=root.get("Temp Read Blocks", 0),
                   temp_written_blocks=root.get("Temp Written Blocks", 0))
        rows.append(row)
        print(f"{name}: median {row['median_ms']} ms; range {min(samples)}..{max(samples)} ms", flush=True)
        (args.output / "summary.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    # Retain only this generated schema for review. The explicit cleanup command is
    # an artifact; this program never deletes an existing or application schema.
    (args.output / "cleanup.sql").write_text(f"drop schema {schema} cascade;\n", encoding="utf-8")
    print(f"Complete. Plans and explicit own-schema cleanup: {args.output}", flush=True)


def seed_sql(documents, alerts):
    return f"""
    insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
    select md5('store-'||s)::uuid,md5('company-'||case when s<3 then 1 else 2 end)::uuid,
           lpad(s::text,3,'0'),'SYNTHETIC STORE '||s,
           '{{"linea1":"SYNTHETIC","ciudad":"TEST","codigoPostal":"00000","provincia":"TEST","pais":"ES"}}'::jsonb,
           'synthetic-'||s,'Atlantic/Canary','EUR','es-ES' from generate_series(1,4) s;
    insert into producto(id,tienda_id,familia_id,impuesto_id,nombre)
    select md5('product-'||s||'-'||p)::uuid,md5('store-'||s)::uuid,
           md5('family-'||s)::uuid,md5('tax-'||s)::uuid,'SYNTHETIC PRODUCT '||p
    from generate_series(1,4) s cross join generate_series(1,4000) p;
    insert into producto_identificador(id,tienda_id,producto_id,tipo,valor)
    select md5('code-'||s||'-'||p)::uuid,md5('store-'||s)::uuid,
           md5('product-'||s||'-'||p)::uuid,'CODIGO','P'||lpad(p::text,6,'0')
    from generate_series(1,4) s cross join generate_series(1,4000) p;
    insert into documento(id,tienda_id,almacen_id,tipo,estado,numero,fecha,creado_en,creado_por,
                          base_total,impuesto_total,total)
    select md5('document-'||n)::uuid,md5('store-'||s)::uuid,md5('warehouse-'||s||'-'||(n%2))::uuid,
           case when n%20=0 then 'FACTURA_VENTA' when n%19=0 then 'RECTIFICATIVA_VENTA'
                when n%17=0 then 'ALBARAN_VENTA' else 'TICKET' end,
           case when n%89=0 then 'BORRADOR' when n%97=0 then 'ANULADO' else 'CONFIRMADO' end,
           'SYN-'||n, date '2026-09-17' - (731 - ((n-1)*732/{documents})::int),
           timestamptz '2024-09-17 08:00:00+00'+(n-1)*interval '732 days'/{documents},
           md5('user-'||s)::uuid,
           case when n%101=0 then 0 when n%19=0 and n%20<>0 then -30 else 30 end,
           case when n%101=0 then 0 when n%19=0 and n%20<>0 then -2.10 else 2.10 end,
           case when n%101=0 then 0 when n%19=0 and n%20<>0 then -32.10 else 32.10 end
    from (select n,case when n%10<8 then 1 else 2+(n%3) end s from generate_series(1,{documents}) n) source;
    insert into documento_relacion(documento_id,origen_id,tipo)
    select md5('document-'||n)::uuid,md5('document-'||(n-3))::uuid,'FACTURA_DE'
    from generate_series(20,{documents},20) n;
    insert into factura_rectificacion_venta(documento_id,origen_documento_id,tipo_fiscal,metodo,motivo,detalle,afecta_stock,creado_en)
    select md5('document-'||n)::uuid,md5('document-'||(case when n>=30 then n-30 else n-3 end))::uuid,'R4','I',
           case when n%2=0 then 'POST_SALE_DISCOUNT' else 'GOODS_RETURN' end,
           'SYNTHETIC BENCHMARK CORRECTION',n%2<>0,timestamptz '2026-09-17 12:00:00+00'
    from generate_series(19,{documents},19) n where n%20<>0;
    insert into documento_linea(id,documento_id,producto_id,posicion,cantidad,codigo,nombre,
                               precio_unitario,impuestos_incluidos,regimen_impuesto,porcentaje_impuesto,base,impuesto,total)
    select md5('line-'||n||'-'||p)::uuid,md5('document-'||n)::uuid,
           md5('product-'||s||'-'||(1+(n*17+p)%4000))::uuid,p,
           case when n%19=0 and n%20<>0 then -1 else 1 end,
           'P'||lpad((1+(n*17+p)%4000)::text,6,'0'),'SYNTHETIC PRODUCT '||(1+(n*17+p)%4000),
           10.70,true,'IGIC',7,
           case when n%101=0 then 0 when n%19=0 and n%20<>0 then -10 else 10 end,
           case when n%101=0 then 0 when n%19=0 and n%20<>0 then -0.70 else 0.70 end,
           case when n%101=0 then 0 when n%19=0 and n%20<>0 then -10.70 else 10.70 end
    from (select n,case when n%10<8 then 1 else 2+(n%3) end s from generate_series(1,{documents}) n) source
    cross join generate_series(1,3) p;
    insert into control_evento(id,tienda_id,regla_id,regla_numero_version,regla_nombre,tipo,origen_tipo,origen_id,
                               documento_numero,usuario_id,usuario_nombre,ocurrido_en,datos)
    select md5('event-'||n)::uuid,md5('store-'||s)::uuid,md5('rule-'||s||'-'||(n%8))::uuid,1,
           'SYNTHETIC RULE '||(n%8),'MANUAL_DISCOUNT_OVER_PERCENT','SYNTHETIC',md5('source-'||n)::uuid,
           'SYN-'||n,md5('user-'||s||'-'||(n%20))::uuid,'SYNTHETIC USER '||(n%20),
           timestamptz '2026-09-18 00:00:00+00'-interval '732 days'+(n-1)*interval '732 days'/{alerts},
           jsonb_build_object('synthetic',true,'detail',repeat('BENCHMARK ',40),'discountPercent',20)
    from (select n,case when n%10<8 then 1 else 2+(n%3) end s from generate_series(1,{alerts}) n) source;
    insert into control_alerta(id,tienda_id,evento_id,estado,creada_en,actualizada_en,prioridad,asignada_a,vence_en)
    select md5('alert-'||n)::uuid,md5('store-'||s)::uuid,md5('event-'||n)::uuid,
           case when (n/10)%10<3 then 'NEW' when (n/10)%10<5 then 'REVIEWED' when (n/10)%10<9 then 'CLOSED' else 'DISMISSED' end,
           timestamptz '2026-09-18 00:00:00+00'-interval '732 days'+(n-1)*interval '732 days'/{alerts},
           timestamptz '2026-09-18 00:00:00+00'-interval '732 days'+(n-1)*interval '732 days'/{alerts},
           case when n%7=0 then 'HIGH' when n%11=0 then 'CRITICAL' else 'MEDIUM' end,
           case when n%4=0 then md5('user-'||s||'-1')::uuid else null end,
           case when n%3=0 then timestamptz '2026-09-01 12:00:00+00' else null end
    from (select n,case when n%10<8 then 1 else 2+(n%3) end s from generate_series(1,{alerts}) n) source;
    """


def overview_queries():
    source = OVERVIEW_SOURCE.read_text(encoding="utf-8")
    logical = re.search(r'LOGICAL_DOCUMENTS = """(.*?)""";', source, re.S).group(1)
    tails = re.findall(r'jdbc\.query\("with logical_documents as \(" \+ logicalDocuments\(warehouseId\) \+ """(.*?)""", parameters', source, re.S)
    if len(tails) != 2:
        raise RuntimeError("Overview SQL source changed: review extraction before measuring")
    result = {}
    end = dt.date(2026, 9, 17)
    for days in (7, 30, 366):
        for warehouse in (False, True):
            for index, kind in enumerate(("daily", "products")):
                start = end - dt.timedelta(days=days * (2 if kind == "daily" else 1) - 1)
                query = "with logical_documents as (" + logical.replace("%s", "and d.almacen_id=:warehouseId" if warehouse else "") + tails[index]
                for name, literal in {"storeId": "md5('store-1')::uuid", "companyId": "md5('company-1')::uuid",
                                      "warehouseId": "md5('warehouse-1-0')::uuid",
                                      "from": f"date '{start}'", "to": f"date '{end}'"}.items():
                    query = re.sub(r":" + name + r"\b", literal, query)
                result[f"overview_{kind}_{days}d" + ("_warehouse" if warehouse else "")] = query
    return result


def alert_queries():
    result = {}
    join = "from control_alerta a join control_evento e on e.id=a.evento_id"
    end = dt.date(2026, 9, 18)
    for days in (7, 30, 366):
        start = end - dt.timedelta(days=days)
        predicate = ("where a.tienda_id=md5('store-1')::uuid "
                     f"and e.ocurrido_en>=timestamptz '{start} 00:00:00+00' "
                     f"and e.ocurrido_en<timestamptz '{end} 00:00:00+00'")
        base = join + " " + predicate
        result[f"alerts_page_{days}d"] = "select a.*,e.* " + base + " order by e.ocurrido_en desc,a.id desc limit 25"
        result[f"alerts_count_{days}d"] = "select count(a.id) " + base
        result[f"alerts_groups_{days}d"] = "select e.regla_id,a.estado,count(*) " + base + " group by e.regla_id,a.estado"
        if days == 366:
            result["alerts_page_366d_offset100000"] = "select a.*,e.* " + base + " order by e.ocurrido_en desc,a.id desc offset 100000 limit 25"
        if days == 30:
            filtered = base + " and a.estado='NEW' and a.prioridad='HIGH' and (lower(e.regla_nombre) like '%rule 0%' or lower(e.usuario_nombre) like '%rule 0%' or lower(e.documento_numero) like '%rule 0%')"
            result["alerts_search_page_30d"] = "select a.*,e.* " + filtered + " order by e.ocurrido_en desc,a.id desc limit 25"
            result["alerts_search_count_30d"] = "select count(a.id) " + filtered
            result["alerts_search_groups_30d"] = "select e.regla_id,a.estado,count(*) " + filtered + " group by e.regla_id,a.estado"
    result["alerts_dashboard_counts_all_history"] = "select a.estado,count(*) from control_alerta a where a.tienda_id=md5('store-1')::uuid group by a.estado"
    result["alerts_dashboard_recent_all_history"] = "select a.*,e.* " + join + " where a.tienda_id=md5('store-1')::uuid order by e.ocurrido_en desc,a.id desc limit 5"
    return result


if __name__ == "__main__":
    main()

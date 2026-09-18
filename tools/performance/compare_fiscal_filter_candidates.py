"""Read-only A/B of candidate materialization on an existing synthetic own schema.

Does not edit application SQL, create indexes, mutate rows or alter planner flags.
Consumes the prior benchmark's query/evidence directory and writes a new one.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics

from measure_fiscal_keyset import REPOSITORY, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--user", default="fiscal_test")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    args = parser.parse_args()
    if not re.fullmatch(r"codex-verifactu-[A-Za-z0-9-]+", args.container) or not re.fullmatch(r"[a-z0-9_]+_test", args.database):
        parser.error("Explicit disposable container/test database required")
    bindings = json.loads(run(["docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", args.container]))
    ports = bindings.get("5432/tcp") or []
    if not ports or any(p["HostIp"] != "127.0.0.1" or p["HostPort"] == "5432" for p in ports):
        parser.error("Non-default loopback test PostgreSQL required")
    metadata = json.loads((args.input / "metadata.json").read_text(encoding="utf-8"))
    schema = metadata["schema"]
    if not re.fullmatch(r"fiscal_scale_[a-f0-9]{32}", schema) or metadata["database"] != args.database:
        parser.error("Input is not the expected own-schema evidence")
    source_hash = hashlib.sha256(REPOSITORY.read_text(encoding="utf-8").encode()).hexdigest()
    if metadata["source_sha256"] != source_hash:
        parser.error("Repository changed since baseline; regenerate the benchmark first")
    if not 1 <= args.repetitions <= 10:
        parser.error("repetitions must be 1..10")
    args.output.mkdir(parents=True, exist_ok=False)
    command = ["docker", "exec", "-i", args.container, "psql", "-X", "-q", "-A", "-t",
               "-v", "ON_ERROR_STOP=1", "-U", args.user, "-d", args.database]

    def sql(statement):
        # All measured statements run in a read-only transaction; only this
        # generated schema and built-ins are in search_path (never public).
        return run(command, f"begin read only; set local search_path to {schema}, pg_catalog; "
                   "set local statement_timeout='120s';\n" + statement + ";\ncommit;")

    def write(name, value):
        (args.output / name).write_text(value, encoding="utf-8")

    def read_query(name):
        lines = (args.input / (name + ".sql")).read_text(encoding="utf-8").splitlines()
        if lines[0] != f"set search_path to {schema}, pg_catalog;":
            raise ValueError("Unexpected SQL schema header")
        query = "\n".join(lines[1:]).strip().removesuffix(";")
        if ";" in query or re.search(r"\b(public|insert|update|delete|drop|create|alter)\b", query, re.I):
            raise ValueError("Only the prior synthetic SELECT is accepted")
        return query

    cases = {name: read_query(name) for name in (
        "filtered_old_30d", "filtered_number_prefix", "filtered_recent_30d")}
    first = read_query("first_page")
    cases["broad_date_all_history"] = first.replace(" and record.secuencia <=",
        " and record.fecha_expedicion >= '2024-01-01' and record.fecha_expedicion <= '2027-01-01' and record.secuencia <=")
    cases["broad_prefix_all_history"] = first.replace(" and record.secuencia <=",
        " and lower(record.serie_numero) like 's-%' escape '\\' and record.secuencia <=")
    results = []
    for name, baseline in cases.items():
        projection, tail = baseline.split("from registro_fiscal record", 1)
        _, where_and_order = tail.split("where ", 1)
        where, order_limit = where_and_order.rsplit(" order by ", 1)
        order, limit = order_limit.rsplit(" limit ", 1)
        if limit.strip() != "51" or "snapshot" in projection.lower() or "xml" in projection.lower():
            raise ValueError("Unexpected list projection/limit")
        selected_order = order.replace("record.", "")
        materialized = f"""with candidates as materialized (
 select record.id, record.secuencia from registro_fiscal record where {where}
), selected as materialized (
 select id,secuencia from candidates order by {selected_order} limit {limit}
)
{projection}from selected
join registro_fiscal record on record.id=selected.id
left join estado_envio_fiscal state on state.registro_id=record.id
order by {order}"""
        pair = {}
        for variant, statement in (("baseline", baseline), ("candidates", materialized)):
            write(f"{name}.{variant}.sql", f"begin read only; set local search_path to {schema}, pg_catalog;\n{statement};\ncommit;\n")
            fingerprint = sql("select md5(coalesce(jsonb_agg(to_jsonb(q) order by secuencia desc, record_id desc)::text,'[]')) from (" + statement + ") q")
            times = []
            for repetition in range(args.repetitions):
                plan = json.loads(sql("explain (analyze,buffers,format json) " + statement))
                write(f"{name}.{variant}.{repetition+1}.json", json.dumps(plan, indent=2))
                times.append(plan[0]["Execution Time"])
            pair[variant] = {"median_ms": round(statistics.median(times), 3),
                             "min_ms": min(times), "max_ms": max(times),
                             "result_fingerprint": fingerprint, "rows": plan[0]["Plan"]["Actual Rows"]}
        if pair["baseline"]["result_fingerprint"] != pair["candidates"]["result_fingerprint"]:
            raise RuntimeError("A/B produced different rows/order: " + name)
        row = {"query": name, "identical_result": True, **pair}
        results.append(row)
        write("summary.json", json.dumps(results, indent=2))
        print(f"{name}: baseline {pair['baseline']['median_ms']} ms; candidates {pair['candidates']['median_ms']} ms; identical", flush=True)
    write("metadata.json", json.dumps({"schema": schema, "source_sha256": source_hash,
          "database": args.database, "repetitions": args.repetitions,
          "baseline_directory": str(args.input), "read_only": True,
          "no_new_indexes_or_planner_flags": True}, indent=2))


if __name__ == "__main__":
    main()

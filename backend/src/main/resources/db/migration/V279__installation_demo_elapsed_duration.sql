-- Java creates a 720-hour demo. Calendar days depend on the session timezone
-- and can span 719/721 hours over DST. Preserve all existing installation dates.
alter table instalacion drop constraint instalacion_check;

create function tpv_validate_installation_demo_duration()
returns trigger language plpgsql as $$
begin
    if TG_OP = 'UPDATE' then
        if NEW.creada_en is not distinct from OLD.creada_en
           and NEW.demo_hasta is not distinct from OLD.demo_hasta then
            return null;
        end if;
    end if;
    if not isfinite(NEW.creada_en) or not isfinite(NEW.demo_hasta)
       or NEW.demo_hasta is distinct from NEW.creada_en + interval '720 hours' then
        raise exception 'Installation demo must last exactly 720 elapsed hours'
            using errcode = '23514', constraint = 'instalacion_demo_elapsed_check',
                  table = TG_TABLE_NAME, schema = TG_TABLE_SCHEMA;
    end if;
    return null;
end;
$$;

create trigger instalacion_demo_elapsed_check
after insert or update on instalacion
for each row execute function tpv_validate_installation_demo_duration();

package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.ReportStatistics.invalid;

import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public record AnalyticsScope(UUID company, UUID store, String province) {
    public static final String PROVINCE = """
            case when upper(btrim(s.store_address->>'pais'))='ES'
                 and btrim(s.store_address->>'codigoPostal') ~ '^(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{3}$'
                 then left(btrim(s.store_address->>'codigoPostal'),2) else 'UNKNOWN' end
            """;

    // Historical assignments count once per license/store even after re-pairing.
    static final String LICENSE_LINKS = """
            select id license_id, company_id, store_id from saas_license where store_id is not null
            union select license_id, company_id, store_id from saas_pairing_code
            union select license_id, company_id, store_id from saas_installation
            """;

    public AnalyticsScope {
        if (province != null && !province.matches("UNKNOWN|0[1-9]|[1-4][0-9]|5[0-2]")) throw invalid("province");
    }

    public MapSqlParameterSource parameters() {
        return new MapSqlParameterSource().addValue("companyId", company).addValue("storeId", store)
                .addValue("province", province);
    }

    public void validate(NamedParameterJdbcTemplate jdbc) {
        if (store != null) {
            String match = company == null ? "" : " and company_id=:companyId";
            if (!jdbc.queryForObject("select exists(select 1 from saas_store where id=:storeId" + match + ")",
                    parameters(), Boolean.class)) throw invalid("storeId");
        } else if (company != null && !jdbc.queryForObject(
                "select exists(select 1 from saas_company where id=:companyId)", parameters(), Boolean.class)) {
            throw invalid("companyId");
        }
    }

    public static MapSqlParameterSource periods(MapSqlParameterSource p, ReportStatistics.Period current, ReportStatistics.Period other) {
        return p.addValue("from", current.from()).addValue("to", current.to())
                .addValue("otherFrom", other == null ? null : other.from(), Types.DATE)
                .addValue("otherTo", other == null ? null : other.to(), Types.DATE)
                .addValue("comparing", other != null);
    }

    public String stores() {
        return (company == null ? "" : " and s.company_id=:companyId")
                + (store == null ? "" : " and s.id=:storeId")
                + (province == null ? "" : " and (" + PROVINCE + ")=:province");
    }

    String facts(String alias) {
        return (company == null ? "" : " and " + alias + ".company_id=:companyId")
                + (store == null ? "" : " and " + alias + ".store_id=:storeId")
                + (province == null ? "" : " and coalesce((select " + PROVINCE + " from saas_store s where s.id="
                    + alias + ".store_id and s.company_id=" + alias + ".company_id),'UNKNOWN')=:province");
    }

    String companies() {
        return (company == null ? "" : " and c.id=:companyId")
                + (store == null && province == null ? "" :
                " and exists(select 1 from saas_store s where s.company_id=c.id" + stores() + ")");
    }

    String licenses() {
        return (company == null ? "" : " and l.company_id=:companyId")
                + (store == null && province == null ? "" : " and exists(" + """
                   select 1 from linked_stores link join saas_store s
                       on s.id=link.store_id and s.company_id=link.company_id
                       where link.license_id=l.id and link.company_id=l.company_id
                   """ + stores() + ")");
    }
}

package com.tpverp.saas.admin;

/** Reporting equivalent only; billing documents and their historical amounts are unchanged. */
public final class StorePricingSummary {
    private StorePricingSummary() { }

    public static final String SQL = """
            select company_id,
                   case when count(*) filter (where service_price is null or billing_period is null) > 0
                        then null
                        else round(sum(case when billing_period = 'ANNUAL' then service_price / 12
                                            else service_price end), 2)
                   end as monthly_equivalent
            from saas_store where active
            group by company_id
            """;
}

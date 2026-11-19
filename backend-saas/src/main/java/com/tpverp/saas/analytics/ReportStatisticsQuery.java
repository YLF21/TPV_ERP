package com.tpverp.saas.analytics;

import static com.tpverp.saas.analytics.ReportStatistics.GroupBy;
import static com.tpverp.saas.document.CommercialDocumentFacts.COUNTS_AS_SALE;

/** All SQL fragments are fixed expressions or whitelisted enums. Filters are bound parameters. */
final class ReportStatisticsQuery {
    private ReportStatisticsQuery() {}

    static boolean temporal(GroupBy group) {
        return group == GroupBy.DAY || group == GroupBy.WEEK || group == GroupBy.MONTH;
    }

    private static String groupKey(GroupBy group, String alias, String date) {
        return switch (group) {
            case DAY -> "(" + date + " - p.start_date)::text";
            case WEEK -> "((date_trunc('week'," + date + ")::date-date_trunc('week',p.start_date)::date)/7)::text";
            case MONTH -> "((extract(year from " + date + ")-extract(year from p.start_date))*12"
                    + "+extract(month from " + date + ")-extract(month from p.start_date))::int::text";
            case COMPANY -> alias + ".company_id::text";
            case STORE -> "coalesce(" + alias + ".store_id::text,'UNKNOWN')";
            case PROVINCE -> AnalyticsScope.PROVINCE;
        };
    }

    private static String storeAddressJoin(GroupBy group, String alias) {
        return group == GroupBy.PROVINCE
                ? "left join saas_store s on s.id=" + alias + ".store_id and s.company_id=" + alias + ".company_id" : "";
    }

    static String aggregates(GroupBy group, AnalyticsScope scope) {
        String footprintKey = switch (group) {
            case COMPANY -> "s.company_id::text";
            case STORE -> "s.id::text";
            case PROVINCE -> AnalyticsScope.PROVINCE;
            default -> "''::text";
        };
        String keys = temporal(group)
                ? "select n::text key from generate_series(0,:bucketCount-1) n"
                : "select distinct group_key as key from buckets where group_key is not null"
                        + (group == GroupBy.PROVINCE ? " union select key from footprint" : "");
        String labels = switch (group) {
            case COMPANY -> "select k.key, c.name label from keys k join saas_company c on c.id=k.key::uuid";
            case STORE -> """
                    select k.key, coalesce(s.name,'UNKNOWN') label from keys k
                    left join saas_store s on s.id=cast(nullif(k.key,'UNKNOWN') as uuid)
                    """;
            default -> "select key,key label from keys";
        };
        String order = temporal(group) ? "key::int" : "lower(label),key";
        String useTotals = temporal(group) ? "true" : "k.key=''";
        return """
                with periods(side,start_date,end_date) as (
                    select 0,cast(:from as date),cast(:to as date)
                    union all select 1,cast(:otherFrom as date),cast(:otherTo as date) where :comparing
                ), linked_stores as (%s), footprint as (
                    select %s as key,count(distinct s.id) stores,count(distinct l.id) licenses
                    from saas_store s left join linked_stores link on link.store_id=s.id and link.company_id=s.company_id
                    left join saas_license l on l.id=link.license_id and l.company_id=s.company_id
                    where true %s group by 1
                ), all_footprint as (
                    select (select count(*) from saas_store s where true %s) stores,
                           (select count(*) from saas_license l where true %s) licenses
                ), facts as (
                    select p.side,%s as group_key,d.currency,d.total,%s as eligible,d.document_status,d.store_id,
                           null::text entity_type,null::text projection_status
                    from periods p join saas_commercial_document d on d.business_date between p.start_date and p.end_date
                    %s where true %s
                    union all
                    select p.side,%s,null,null,false,null,e.store_id,e.entity_type,e.projection_status
                    from periods p join saas_sync_event e on e.received_at >= (p.start_date::timestamp at time zone 'UTC')
                         and e.received_at < ((p.end_date+1)::timestamp at time zone 'UTC')
                    %s where true %s
                ), buckets as (
                    select side,group_key,currency,entity_type,projection_status,count(currency) documents,
                           count(*) filter(where eligible) eligible,sum(total) filter(where eligible) amount,
                           count(*) filter(where eligible and total<0) returns,
                           count(distinct store_id) filter(where eligible) contributing_stores,
                           count(*) filter(where document_status='ANULADO') cancelled,count(entity_type) events
                    from facts group by side,currency,entity_type,projection_status,grouping sets ((group_key),())
                ), keys as (%s), labels as (%s), page_keys as (
                    select key,label,row_number() over(order by %s) position from labels
                    order by position limit :size offset :offset
                ), selected as (
                    select key,label,position from page_keys union all select '','',0
                )
                select k.key,k.label,(select count(*) from keys) total_groups,b.side,b.currency,b.entity_type,
                       b.projection_status,b.documents,b.eligible,b.amount,b.cancelled,b.events,b.returns,b.contributing_stores,
                       case when %s then a.stores else coalesce(f.stores,0) end stores,
                       case when %s then a.licenses else coalesce(f.licenses,0) end licenses
                from selected k cross join all_footprint a left join footprint f on f.key=k.key
                left join buckets b on coalesce(b.group_key,'')=k.key
                order by k.position,b.side,b.currency
                """.formatted(AnalyticsScope.LICENSE_LINKS, footprintKey, scope.stores(), scope.stores(), scope.licenses(),
                groupKey(group, "d", "d.business_date"), COUNTS_AS_SALE, storeAddressJoin(group,"d"), scope.facts("d"),
                groupKey(group,"e","(e.received_at at time zone 'UTC')::date"),
                storeAddressJoin(group,"e"), scope.facts("e"), keys, labels, order, useTotals, useTotals);
    }
}

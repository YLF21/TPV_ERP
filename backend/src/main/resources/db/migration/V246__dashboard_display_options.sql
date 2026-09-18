-- Extend existing per-user preferences without changing saved widget layouts.
alter table preferencia_dashboard
    add column options jsonb not null default
        '{"defaultPeriod":"MONTH","trendDisplay":"LINE","productDisplay":"BAR","density":"COMFORTABLE","showComparison":true}'::jsonb;

alter table preferencia_dashboard add constraint preferencia_dashboard_options_ck check (
    jsonb_typeof(options) = 'object'
    and options ?& array['defaultPeriod', 'trendDisplay', 'productDisplay', 'density', 'showComparison']
    and options->>'defaultPeriod' in ('TODAY', 'LAST_7_DAYS', 'LAST_30_DAYS', 'MONTH')
    and options->>'trendDisplay' in ('LINE', 'BAR', 'TABLE')
    and options->>'productDisplay' in ('BAR', 'TABLE')
    and options->>'density' in ('COMFORTABLE', 'COMPACT')
    and jsonb_typeof(options->'showComparison') = 'boolean'
    and not (options @> '{"defaultPeriod":null}' or options @> '{"trendDisplay":null}'
        or options @> '{"productDisplay":null}' or options @> '{"density":null}')
);

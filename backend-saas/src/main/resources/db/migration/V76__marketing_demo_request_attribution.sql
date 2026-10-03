alter table saas_marketing_demo_request
    add column landing_path varchar(500),
    add column referrer varchar(1000),
    add column utm_source varchar(160),
    add column utm_medium varchar(160),
    add column utm_campaign varchar(200);

create index idx_saas_marketing_demo_request_campaign
    on saas_marketing_demo_request(utm_campaign, created_at desc)
    where utm_campaign is not null;

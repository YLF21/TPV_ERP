-- Optional labels captured by the source's DocumentAttributionResolver. Unknown history stays null.
-- user_name represents confirmadoPor ?? creadoPor; raw actor IDs retain their separate meanings.
alter table saas_commercial_document
    add column user_name varchar(255),
    add column terminal_name varchar(255);

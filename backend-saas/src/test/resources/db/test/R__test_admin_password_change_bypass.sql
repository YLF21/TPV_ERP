-- Integration suites authenticate legacy fixtures directly; production credentials
-- are overridden only in the isolated test migration location.
update saas_admin_user
set username = lower(btrim(username)),
    password_hash = '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918',
    active = true,
    must_change_password = false
where lower(btrim(username)) in ('admin', 'viewer');

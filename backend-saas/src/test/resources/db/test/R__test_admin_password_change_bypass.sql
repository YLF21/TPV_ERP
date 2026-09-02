-- Integration suites authenticate legacy fixtures directly; mandatory-change behavior has dedicated tests.
update saas_admin_user
set must_change_password = false
where username in ('admin', 'viewer');

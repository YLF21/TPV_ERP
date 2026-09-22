-- Keep subscription records and historical audits. Retire only the unavailable capability.
DELETE FROM saas_admin_role_permission WHERE permission_code = 'MANAGE_SUBSCRIPTIONS';
DELETE FROM saas_admin_permission WHERE code = 'MANAGE_SUBSCRIPTIONS';

alter table saas_admin_audit_log
    add column origin varchar(16) not null default 'OTHER'
    check (origin in ('OTHER', 'SAAS_ADMIN'));

-- Older writers used "system" whenever there was no authenticated operator.
-- Classify only known administrative actions with a matching staff account.
update saas_admin_audit_log a set origin = 'SAAS_ADMIN'
where lower(a.username) <> 'system'
  and exists (select 1 from saas_admin_user u where u.username = a.username)
  and a.action in (
        'ACK_INTEGRATION_OUTBOX', 'ACK_SECURITY_OUTBOX', 'ACTIVATE_ADMIN_USER',
        'ADD_COMPANY', 'ADD_SUPPORT_TICKET_COMMENT', 'ASSIGN_STORE_CODE',
        'BLOCK_LICENSE', 'CANCEL_MEMBER_CATEGORY_BOOTSTRAP_INCIDENT', 'CANCEL_MEMBER_POINTS_BOOTSTRAP_V21',
        'CANCEL_MEMBER_WALLET_BOOTSTRAP_V2', 'CHANGE_ADMIN_PASSWORD', 'CHANGE_TENANT_PASSWORD',
        'CREATE_ADMIN_USER', 'CREATE_BILLING_INVOICE', 'CREATE_BILLING_PAYMENT',
        'CREATE_ERP_CUSTOMER', 'CREATE_ERP_PRODUCT', 'CREATE_ERP_SUPPLIER',
        'CREATE_ERP_WAREHOUSE', 'CREATE_INTEGRATION', 'CREATE_INVENTORY_MOVEMENT',
        'CREATE_LICENSE', 'CREATE_SALES_DOCUMENT', 'CREATE_STORE',
        'CREATE_STORE_REPAIR', 'CREATE_SUPPORT_TICKET', 'CREATE_TENANT_USER',
        'DEACTIVATE_ADMIN_USER', 'DEACTIVATE_ERP_CUSTOMER', 'DEACTIVATE_ERP_PRODUCT',
        'DEACTIVATE_ERP_SUPPLIER', 'DEACTIVATE_ERP_WAREHOUSE', 'DEACTIVATE_TENANT_USER',
        'DESIGNATE_LOYALTY_BOOTSTRAP_SOURCE', 'DISASTER_RECOVERY_APPROVED', 'DISASTER_RECOVERY_AUTHORIZED',
        'DISASTER_RECOVERY_CANCELLED', 'DISASTER_RECOVERY_REAUTHORIZED', 'EDIT_COMPANY_DATA',
        'ESCALATE_STORE_FAILURE', 'READ_NOTIFICATION', 'RECOVER_SYNC_EVENT',
        'REGENERATE_PAIRING_CODE', 'RENEW_LICENSE', 'REQUEUE_INTEGRATION_OUTBOX',
        'REQUEUE_SECURITY_OUTBOX', 'REVOKE_INSTALLATION', 'REVOKE_PAIRING_CODE',
        'REVOKE_TENANT_ACCESS', 'START_MEMBER_POINTS_BOOTSTRAP_V21', 'START_MEMBER_WALLET_BOOTSTRAP_V2',
        'SUPPORT_INTERVENTION', 'UNBLOCK_LICENSE', 'UPDATE_COMPANY_OPERATIONS',
        'UPDATE_COMPANY_PROFILE', 'UPDATE_DEMO_REQUEST_FOLLOW_UP', 'UPDATE_DEMO_REQUEST_STATUS',
        'UPDATE_FISCAL_PROVISIONING', 'UPDATE_INVOICE_FISCAL', 'UPDATE_STORE',
        'UPDATE_STORE_ACTIVITY', 'UPDATE_SUPPORT_TICKET', 'UPDATE_TENANT_ACCESS',
        'UPDATE_VERIFACTU_ACTIVATION_POLICY'
  );

create index ix_saas_staff_audit_order on saas_admin_audit_log(created_at desc, id desc)
    where origin = 'SAAS_ADMIN';

create function reject_saas_audit_changes() returns trigger language plpgsql as $$
begin
    raise exception 'Audit records are append-only' using errcode = '55000';
end;
$$;

create trigger immutable_saas_audit before update or delete or truncate
    on saas_admin_audit_log for each statement execute function reject_saas_audit_changes();

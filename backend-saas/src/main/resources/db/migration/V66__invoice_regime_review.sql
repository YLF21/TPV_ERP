-- New companies are registered without a tax regime. Their invoices remain
-- pending until the existing fiscal review explicitly supplies the regime.
-- Preserve all historical invoice data and decisions.
alter table saas_billing_invoice alter column tax_regime drop not null;

alter table saas_billing_invoice
    add constraint ck_saas_invoice_regime_review
    check (tax_regime is not null or fiscal_status = 'PENDING_TAX_DATA');

alter table saas_invoice_fiscal_decision_audit
    add column previous_tax_regime varchar(16),
    add column new_tax_regime varchar(16),
    add constraint ck_saas_fiscal_audit_previous_regime
        check (previous_tax_regime in ('IVA', 'IGIC')),
    add constraint ck_saas_fiscal_audit_new_regime
        check (new_tax_regime in ('IVA', 'IGIC'));

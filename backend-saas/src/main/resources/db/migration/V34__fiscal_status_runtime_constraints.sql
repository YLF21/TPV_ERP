alter table saas_fiscal_status
    add constraint ck_saas_fiscal_status_runtime_class
        check (runtime_class in ('SANDBOX', 'REAL')),
    add constraint ck_saas_fiscal_status_endpoint_environment
        check (endpoint_environment in ('TEST', 'PRODUCTION')),
    add constraint ck_saas_fiscal_status_transport_mode
        check (transport_mode in ('SIMULATED', 'AEAT')),
    add constraint ck_saas_fiscal_status_runtime_combination
        check ((runtime_class = 'SANDBOX'
                    and endpoint_environment = 'TEST'
                    and transport_mode = 'SIMULATED')
                or (runtime_class = 'REAL' and transport_mode = 'AEAT'));

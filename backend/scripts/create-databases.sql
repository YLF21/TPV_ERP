\set ON_ERROR_STOP on

CREATE ROLE tpv_erp LOGIN PASSWORD :'dev_password';
CREATE ROLE tpv_erp_test LOGIN PASSWORD :'test_password';

CREATE DATABASE tpv_erp_dev OWNER tpv_erp ENCODING 'UTF8';
CREATE DATABASE tpv_erp_test OWNER tpv_erp_test ENCODING 'UTF8';

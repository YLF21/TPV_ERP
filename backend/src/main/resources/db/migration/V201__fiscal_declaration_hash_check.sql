-- The responsible-declaration digest is part of the immutable SIF identity.
-- It remains nullable for SANDBOX/TEST evidence, but any persisted value must
-- be a SHA-256 digest. Existing evidence is not rewritten: historical lower-case
-- values remain valid and comparisons are normalized in the application layer.
alter table version_sistema_fiscal
    add constraint ck_version_sistema_fiscal_declaracion_hash
    check (declaracion_hash is null or declaracion_hash ~ '^[0-9A-Fa-f]{64}$');

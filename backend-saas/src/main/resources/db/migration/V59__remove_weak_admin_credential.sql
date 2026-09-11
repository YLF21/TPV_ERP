-- Revoca la credencial corta introducida por V58 antes de aceptar tráfico.
-- En producción, AdminProductionGuard exige rotarla mediante
-- TPV_SAAS_BOOTSTRAP_ADMIN_PASSWORD. El perfil local aplica después su seed
-- temporal de 13 caracteres y obliga a cambiarla en el primer acceso.
update saas_admin_user
set password_hash = '2471a9eb4d709d78c59cb8141ec108cce7db9c71b901d76b01cb1efcc2913b94',
    must_change_password = true
where lower(btrim(username)) = 'admin'
  and password_hash = '9af15b336e6a9619928537df30b2e6a2376569fcf9d7e773eccede65606529a0';

-- Credencial conocida exclusivamente para el entorno local.
-- El perfil de producción no incluye classpath:db/local.
update saas_admin_user
set username = 'ADMIN',
    password_hash = '9af15b336e6a9619928537df30b2e6a2376569fcf9d7e773eccede65606529a0',
    must_change_password = true
where lower(username) = 'admin';

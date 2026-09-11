-- Credencial administrativa solicitada expresamente para producción.
-- Se conserva el hash SHA-256 histórico que el login actualiza a bcrypt al autenticar.
update saas_admin_user
set username = 'ADMIN',
    password_hash = '9af15b336e6a9619928537df30b2e6a2376569fcf9d7e773eccede65606529a0',
    active = true,
    must_change_password = false
where lower(btrim(username)) = 'admin';

-- El viewer seed no debe dejar una segunda credencial pública activa.
update saas_admin_user
set active = false
where lower(btrim(username)) = 'viewer'
  and password_hash in (
      '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918',
      '9af15b336e6a9619928537df30b2e6a2376569fcf9d7e773eccede65606529a0'
  );

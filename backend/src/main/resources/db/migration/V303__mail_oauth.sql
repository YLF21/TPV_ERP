CREATE TABLE mail_oauth_connection (
    empresa_id uuid PRIMARY KEY REFERENCES empresa(id),
    provider varchar(16) NOT NULL CHECK (provider IN ('GOOGLE', 'MICROSOFT')),
    email varchar(320) NOT NULL,
    access_token text NOT NULL,
    refresh_token text NOT NULL,
    expires_at timestamptz NOT NULL
);

-- One pending consent flow per company; a new connection attempt invalidates the old one.
CREATE TABLE mail_oauth_flow (
    empresa_id uuid PRIMARY KEY REFERENCES empresa(id),
    state_hash varchar(64) NOT NULL UNIQUE,
    provider varchar(16) NOT NULL CHECK (provider IN ('GOOGLE', 'MICROSOFT')),
    email varchar(320) NOT NULL,
    verifier text NOT NULL,
    consumed boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL
);

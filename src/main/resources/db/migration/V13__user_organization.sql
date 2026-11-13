-- V13: cuentas industriales (WORKER / SSO_RESPONSIBLE) ligadas a una organización.
-- La organización es una cuenta CLIENT (la empresa), por eso referencia a users(id).
-- Las cuentas existentes quedan con NULL y siguen funcionando igual.
ALTER TABLE users ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_organization
    FOREIGN KEY (organization_id) REFERENCES users(id) ON DELETE RESTRICT;
CREATE INDEX idx_users_organization ON users (organization_id);

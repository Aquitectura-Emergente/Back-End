-- Puesto y dosímetro habituales del operario: solo sugieren valores al iniciar
-- su jornada; la jornada sigue guardando los suyos.
ALTER TABLE personal_dosimetry_memberships ADD COLUMN default_job_position_id BIGINT NULL;
ALTER TABLE personal_dosimetry_memberships ADD COLUMN default_device_id BIGINT NULL;

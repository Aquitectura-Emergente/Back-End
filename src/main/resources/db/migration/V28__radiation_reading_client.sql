-- Cliente al que se atribuye cada lectura: el dueño del Edge que la envió, que
-- no siempre es el dueño del dispositivo (préstamo). Null en las anteriores,
-- que se siguen atribuyendo al dueño del dispositivo.
ALTER TABLE radiation_readings ADD COLUMN client_id BIGINT;
ALTER TABLE radiation_readings ADD CONSTRAINT fk_radiation_readings_client
    FOREIGN KEY (client_id) REFERENCES users (id);
CREATE INDEX ix_radiation_readings_client ON radiation_readings (client_id, recorded_at);

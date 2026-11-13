-- Conectividad que informa cada Edge de planta por MQTT (vía Device Management).
-- Es aparte de `status`, que es la activación administrativa del SSO.
ALTER TABLE plant_edges ADD COLUMN connectivity VARCHAR(10);
ALTER TABLE plant_edges ADD COLUMN connectivity_changed_at DATETIME(6);

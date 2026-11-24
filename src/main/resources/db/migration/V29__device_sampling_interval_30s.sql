-- El muestreo por defecto pasa de 1 s a 30 s, igual que el firmware del dosímetro.
-- Solo cambian los dispositivos que seguían con el valor por defecto anterior;
-- el historial de configuraciones (device_configs) se conserva tal cual.
UPDATE devices SET sampling_interval_ms = 30000 WHERE sampling_interval_ms = 1000;

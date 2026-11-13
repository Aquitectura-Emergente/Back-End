-- La orden del usuario para el enchufe ya no es un estado deseado que el Edge
-- consulta por HTTP: se publica por MQTT y queda en plug_commands.
ALTER TABLE devices DROP COLUMN desired_plug;

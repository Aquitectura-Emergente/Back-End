-- Acuse y escalamiento de alertas de operario (informe §5.6.3, RF25, US60).
--
-- El contexto Alerts solo sabía de alarmas dirigidas a CLIENTES: tenía
-- `resolved`/`resolved_at` y destinatarios en `alert_recipient_clients`. Le
-- faltaba por completo la alerta del OPERARIO, que es la que Occupational
-- Health necesita para dejar evidencia: su handler AlertEvidenceEventHandler ya
-- existía y esperaba `AlertAcknowledged` y `AlertEscalated`, pero nadie los
-- producía porque la función no estaba construida.
--
-- Todas las columnas son NULL: las alarmas de cliente que ya existen siguen
-- siendo válidas sin tocarlas, y una alarma solo es de operario cuando
-- `worker_id` está relleno. Con `ddl-auto=validate`, la entidad Alert tiene que
-- declarar exactamente estas siete.
--
-- DATETIME(6) y no TIMESTAMP(6) por coherencia con el resto del esquema y con
-- `resolved_at`, que está en esta misma tabla. Es la única extensión de MySQL
-- que se usa aquí; el resto es SQL portable de cara al paso a PostgreSQL.

-- A quién concierne la alerta. `organization_id` hace falta para el sobre del
-- evento, que lo exige en todos los mensajes de integración.
ALTER TABLE alerts ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE alerts ADD COLUMN worker_id BIGINT NULL;
-- Jornada de exposición a la que pertenece, cuando la alerta viene de una.
ALTER TABLE alerts ADD COLUMN session_id VARCHAR(36) NULL;

-- Las dos marcas de evidencia. Se sellan con la hora REAL del suceso, no con la
-- de llegada: una alerta sincronizada de forma diferida debe constar cuándo
-- ocurrió. Son independientes a propósito — un escalamiento SIN acuse previo es
-- justo el caso que hace que Occupational Health suba la prioridad (regla 10).
ALTER TABLE alerts ADD COLUMN acknowledged_at DATETIME(6) NULL;
ALTER TABLE alerts ADD COLUMN escalated_at DATETIME(6) NULL;

-- A quién se escaló y por qué.
ALTER TABLE alerts ADD COLUMN sso_responsible_id BIGINT NULL;
ALTER TABLE alerts ADD COLUMN escalation_reason VARCHAR(500) NULL;

-- La bandeja del operario se consulta por operario y organización.
CREATE INDEX idx_alerts_worker ON alerts (organization_id, worker_id);

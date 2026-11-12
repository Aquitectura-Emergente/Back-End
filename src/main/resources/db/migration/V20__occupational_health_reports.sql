-- Occupational Health & Mitigation, segunda parte: ComplianceReport, las dos
-- proyecciones locales, la auditoría, el inbox y la idempotencia.
--
-- Desvíos respecto al informe (§5.6.6.2), además de los ya anotados en V13:
--  - El UNIQUE de compliance_reports incluye subject_worker_id, que es NULL en
--    los reportes por zona. MySQL no considera los NULL en un índice UNIQUE, así
--    que dos reportes de zona del mismo período y versión no chocarían. Se usa
--    una columna generada que sustituye el NULL por 0 y el UNIQUE va sobre ella.
--  - El informe reparte las referencias de origen de una entrada en tres
--    colecciones (exceedance_event_ids, alert_ids, action_ids). Aquí van en una
--    sola tabla con una columna de tipo: la consulta que importa —"¿tiene esta
--    cifra al menos una referencia?", que es lo que mide QAS-07— es la misma, y
--    evita tres tablas con idéntica forma.
--  - El informe bloquea UPDATE y DELETE sobre las tablas de auditoría con
--    permisos de base de datos y un rol retention_job. Eso son GRANT sobre el
--    servidor, fuera del alcance de una migración: aquí la inmutabilidad la
--    sostienen los agregados (sin setters públicos) y RetentionJob es el único
--    que borra. El GRANT queda pendiente para el despliegue.

-- ── Reporte de cumplimiento ──────────────────────────────────────────────────
CREATE TABLE compliance_reports (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id          BIGINT       NOT NULL,
    scope                    VARCHAR(20)  NOT NULL,
    subject_worker_id        BIGINT       NULL,
    period_from              DATE         NOT NULL,
    period_to                DATE         NOT NULL,
    version                  INT          NOT NULL,
    evidence_digest_algorithm VARCHAR(20) NOT NULL,
    evidence_digest_value    VARCHAR(128) NOT NULL,
    file_reference           VARCHAR(500) NULL,
    exported_at              DATETIME(6)  NULL,
    generated_by             BIGINT       NOT NULL,
    generated_at             DATETIME(6)  NOT NULL,
    subject_key              BIGINT AS (IFNULL(subject_worker_id, 0)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT ck_compliance_reports_scope CHECK (scope IN ('WORKER', 'PLANT_ZONE')),
    CONSTRAINT ck_compliance_reports_period CHECK (period_from <= period_to),
    CONSTRAINT ck_compliance_reports_version CHECK (version >= 1),
    UNIQUE KEY uq_compliance_reports_version
        (organization_id, scope, subject_key, period_from, period_to, version),
    KEY ix_compliance_reports_subject (organization_id, subject_worker_id, period_from)
) ENGINE = InnoDB;

CREATE TABLE report_entries (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id      BIGINT        NOT NULL,
    compliance_report_id BIGINT        NOT NULL,
    session_id           VARCHAR(64)   NULL,
    entry_date           DATE          NULL,
    final_dose           DECIMAL(12,4) NULL,
    dose_unit            VARCHAR(10)   NULL,
    dose_method_id       VARCHAR(50)   NULL,
    dose_limit           DECIMAL(12,4) NULL,
    dose_percentage      DECIMAL(6,2)  NULL,
    -- Agregados del reporte por zona. Excluyentes con los campos de dosis de
    -- arriba: una entrada de alcance WORKER lleva la dosis de una jornada, una
    -- de alcance PLANT_ZONE lleva promedio y máximo de la zona y nunca puede
    -- señalar a un operario (RF32, RNF11).
    zone_id              BIGINT        NULL,
    zone_average_value   DECIMAL(12,4) NULL,
    zone_max_value       DECIMAL(12,4) NULL,
    zone_reading_count   INT           NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_report_entries_report
        FOREIGN KEY (compliance_report_id) REFERENCES compliance_reports (id),
    KEY ix_report_entries_report (compliance_report_id)
) ENGINE = InnoDB;

-- Cada cifra del reporte referencia el registro que la sustenta (QAS-07).
CREATE TABLE report_entry_sources (
    report_entry_id BIGINT      NOT NULL,
    source_type     VARCHAR(20) NOT NULL,
    source_ref      VARCHAR(64) NOT NULL,
    CONSTRAINT fk_report_entry_sources_entry
        FOREIGN KEY (report_entry_id) REFERENCES report_entries (id),
    CONSTRAINT ck_report_entry_sources_type
        CHECK (source_type IN ('SESSION', 'EXCEEDANCE', 'ALERT', 'ACTION')),
    KEY ix_report_entry_sources_entry (report_entry_id)
) ENGINE = InnoDB;

-- Tramos sincronizados de forma diferida o con cobertura incompleta.
CREATE TABLE report_entry_trace_notes (
    report_entry_id BIGINT       NOT NULL,
    -- from_at es obligatorio porque la nota reutiliza el value object
    -- ShiftInterval, que no admite un tramo sin inicio. to_at sí puede faltar:
    -- una nota puede cubrir un tramo que seguía abierto al cerrar el período.
    from_at         DATETIME(6)  NOT NULL,
    to_at           DATETIME(6)  NULL,
    cause           VARCHAR(300) NOT NULL,
    CONSTRAINT fk_report_entry_trace_notes_entry
        FOREIGN KEY (report_entry_id) REFERENCES report_entries (id),
    KEY ix_report_entry_trace_notes_entry (report_entry_id)
) ENGINE = InnoDB;

-- ── Proyecciones locales ─────────────────────────────────────────────────────
-- Copia de lo que publican Dosimetry y Alerts, para no consultarlos en cada
-- exportación. No son fuente de verdad: se reconstruyen desde sus eventos.
CREATE TABLE closed_session_records (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id BIGINT        NOT NULL,
    session_id      VARCHAR(64)   NOT NULL,
    worker_id       BIGINT        NOT NULL,
    final_dose      DECIMAL(12,4) NULL,
    dose_unit       VARCHAR(10)   NULL,
    dose_method_id  VARCHAR(50)   NULL,
    dose_limit      DECIMAL(12,4) NULL,
    dose_percentage DECIMAL(6,2)  NULL,
    coverage        VARCHAR(30)   NULL,
    finalized_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_closed_session_records (organization_id, session_id),
    KEY ix_closed_session_records_worker (organization_id, worker_id, finalized_at)
) ENGINE = InnoDB;

CREATE TABLE alert_evidence_records (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id BIGINT      NOT NULL,
    alert_id        VARCHAR(64) NOT NULL,
    worker_id       BIGINT      NULL,
    session_id      VARCHAR(64) NULL,
    alert_type      VARCHAR(50) NULL,
    acknowledged_at DATETIME(6) NULL,
    escalated_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_alert_evidence_records (organization_id, alert_id),
    KEY ix_alert_evidence_records_worker (organization_id, worker_id)
) ENGINE = InnoDB;

-- ── Auditoría ────────────────────────────────────────────────────────────────
-- Decisiones, exportaciones y accesos denegados, con actor, recurso, acción,
-- resultado y fecha. No guarda valores de exposición: el informe lo pide
-- explícitamente porque son datos de salud (Ley 29733).
CREATE TABLE mitigation_audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NULL,
    actor_id        BIGINT       NULL,
    resource_type   VARCHAR(40)  NOT NULL,
    resource_id     VARCHAR(64)  NULL,
    action          VARCHAR(40)  NOT NULL,
    outcome         VARCHAR(20)  NOT NULL,
    detail          VARCHAR(300) NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_mitigation_audit_outcome CHECK (outcome IN ('GRANTED', 'DENIED')),
    KEY ix_mitigation_audit_org (organization_id, occurred_at),
    KEY ix_mitigation_audit_outcome (outcome, occurred_at)
) ENGINE = InnoDB;

-- ── Inbox e idempotencia ─────────────────────────────────────────────────────
-- Descarta un mensaje ya procesado por el mismo consumidor (§5.6.4).
CREATE TABLE processed_messages (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    consumer_name   VARCHAR(80) NOT NULL,
    organization_id BIGINT      NOT NULL,
    event_id        VARCHAR(64) NOT NULL,
    processed_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_processed_messages (consumer_name, organization_id, event_id),
    KEY ix_processed_messages_at (processed_at)
) ENGINE = InnoDB;

-- Idempotency-Key de las mutaciones: un doble toque en el móvil no aprueba dos
-- veces (§5.6.2). Guarda a qué recurso resolvió la primera llamada.
CREATE TABLE idempotency_records (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    endpoint         VARCHAR(120) NOT NULL,
    resource_id      BIGINT       NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_idempotency_records (organization_id, idempotency_key, endpoint),
    KEY ix_idempotency_records_at (created_at)
) ENGINE = InnoDB;

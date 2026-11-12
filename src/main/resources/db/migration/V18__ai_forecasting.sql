-- ─────────────────────────────────────────────────────────────────────────────
-- V18: Bounded context AI Forecasting & Analytics (com.emsafe.aiforecasting).
--
-- V13-V17 están reservadas por feature/iam, feature/occupational-health-mitigation
-- y feature/personal-dosimetry.
-- Los IDs de organización, operario, sesión, zona y puesto son identificadores de
-- integración: no hay claves foráneas hacia tablas de otros contextos.
-- Índices y montos en uT·s con escala 6; fechas en UTC como DATETIME(6).
-- ─────────────────────────────────────────────────────────────────────────────

-- Modelo de lectura local: una fila por sesión abierta.
CREATE TABLE af_worker_exposure_snapshot (
    session_id          VARCHAR(64)    NOT NULL,
    version             BIGINT         NOT NULL,
    organization_id     BIGINT         NOT NULL,
    worker_id           BIGINT         NOT NULL,
    zone_id             VARCHAR(64)    NULL,
    position_id         BIGINT         NULL,
    accumulated_index   DECIMAL(38,6)  NOT NULL,
    limit_index         DECIMAL(38,6)  NOT NULL,
    session_started_at  DATETIME(6)    NOT NULL,
    planned_shift_end   DATETIME(6)    NULL,
    captured_at         DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    CONSTRAINT pk_af_snapshot PRIMARY KEY (session_id),
    INDEX ix_af_snapshot_org_zone (organization_id, zone_id),
    INDEX ix_af_snapshot_worker (worker_id)
);

-- Agregado PreventiveAssessment.
-- active_session_key = session_id mientras no esté CLOSED, NULL al cerrarse: la
-- restricción única garantiza una sola evaluación activa por sesión.
CREATE TABLE af_preventive_assessment (
    id                        VARCHAR(36)    NOT NULL,
    version                   BIGINT         NOT NULL,
    organization_id           BIGINT         NOT NULL,
    worker_id                 BIGINT         NOT NULL,
    session_id                VARCHAR(64)    NOT NULL,
    active_session_key        VARCHAR(64)    NULL,
    status                    VARCHAR(20)    NOT NULL,
    criticality               VARCHAR(20)    NULL,
    close_reason              VARCHAR(20)    NULL,
    -- ExposureProjection
    current_index             DECIMAL(38,6)  NOT NULL,
    limit_index               DECIMAL(38,6)  NOT NULL,
    accumulation_rate         DECIMAL(38,6)  NOT NULL,
    projected_index           DECIMAL(38,6)  NOT NULL,
    remaining_margin          DECIMAL(38,6)  NOT NULL,
    consumed_ratio            DECIMAL(38,6)  NOT NULL,
    projected_ratio           DECIMAL(38,6)  NOT NULL,
    estimated_exceedance_at   DATETIME(6)    NULL,
    shift_end                 DATETIME(6)    NOT NULL,
    -- RotationRecommendation (opcional)
    rec_type                  VARCHAR(20)    NULL,
    rec_source_worker_id      BIGINT         NULL,
    rec_target_worker_id      BIGINT         NULL,
    rec_source_position_id    BIGINT         NULL,
    rec_target_position_id    BIGINT         NULL,
    rec_benefit_index         DECIMAL(38,6)  NULL,
    rec_reason                VARCHAR(500)   NULL,
    -- NaturalLanguageRedaction (opcional)
    redaction_text            TEXT           NULL,
    redaction_source          VARCHAR(20)    NULL,
    redaction_generated_at    DATETIME(6)    NULL,
    -- Trazabilidad
    model_id                  VARCHAR(50)    NOT NULL,
    model_version             VARCHAR(20)    NOT NULL,
    limit_version_id          BIGINT         NULL,
    trigger_event_id          VARCHAR(100)   NULL,
    assessed_at               DATETIME(6)    NOT NULL,
    closed_at                 DATETIME(6)    NULL,
    CONSTRAINT pk_af_assessment PRIMARY KEY (id),
    CONSTRAINT uk_af_assessment_active_session UNIQUE (active_session_key),
    INDEX ix_af_assessment_org_status (organization_id, status),
    INDEX ix_af_assessment_worker (worker_id),
    INDEX ix_af_assessment_session (session_id),
    INDEX ix_af_assessment_assessed_at (assessed_at)
);

-- Outbox transaccional de eventos salientes. id da el orden de inserción.
CREATE TABLE af_outbox (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    event_id         VARCHAR(64)    NOT NULL,
    event_type       VARCHAR(64)    NOT NULL,
    schema_version   INT            NOT NULL,
    organization_id  BIGINT         NOT NULL,
    aggregate_id     VARCHAR(64)    NULL,
    occurred_at      DATETIME(6)    NOT NULL,
    correlation_id   VARCHAR(100)   NULL,
    causation_id     VARCHAR(100)   NULL,
    payload          TEXT           NOT NULL,
    published_at     DATETIME(6)    NULL,
    CONSTRAINT pk_af_outbox PRIMARY KEY (id),
    CONSTRAINT uk_af_outbox_event UNIQUE (event_id),
    INDEX ix_af_outbox_pending (published_at, occurred_at, id)
);

-- Idempotencia del inbox.
CREATE TABLE af_processed_message (
    event_id      VARCHAR(100)  NOT NULL,
    processed_at  DATETIME(6)   NOT NULL,
    CONSTRAINT pk_af_processed_message PRIMARY KEY (event_id)
);

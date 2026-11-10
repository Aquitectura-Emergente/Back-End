-- Bounded context Occupational Health & Mitigation (segmento industrial).
-- Cubre los agregados WorkerShift y MitigationAction. ComplianceReport llega
-- en una migración posterior, junto con su código.
--
-- Desvíos respecto al informe (§5.6.6.2), que especifica PostgreSQL:
--  - PK simple `id` AUTO_INCREMENT en vez de PK compuesta (organization_id, id).
--    Mantener la PK compuesta obligaría a @IdClass en todas las entidades y
--    rompería la convención del resto del backend. El aislamiento por
--    organización se preserva: organization_id entra en cada UNIQUE y en cada
--    índice de consulta, y ninguna query lo omite.
--  - El UNIQUE parcial `(organization_id, worker_id) WHERE state = 'ACTIVE'` no
--    existe en MySQL. Se emula con una columna generada que vale worker_id solo
--    mientras el turno está activo y NULL en cualquier otro estado: MySQL ignora
--    los NULL en un índice UNIQUE, así que el efecto es el mismo.
--  - La restricción EXCLUDE con btree_gist que impide tramos solapados tampoco
--    existe en MySQL. La invariante la protege WorkerShift en el dominio
--    (startAssignment / reassign cierran el tramo vigente antes de abrir otro) y
--    aquí queda un UNIQUE (worker_shift_id, from_at) como última barrera.

-- La organización a la que pertenece un Responsable de SSO o un operario.
-- Nullable: las cuentas del segmento hogar (ADMIN, TECHNICIAN, CLIENT) no la usan.
ALTER TABLE users ADD COLUMN organization_id BIGINT NULL;

CREATE TABLE worker_shifts (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT       NOT NULL,
    worker_id           BIGINT       NOT NULL,
    exposure_session_id VARCHAR(64)  NULL,
    shift_date          DATE         NOT NULL,
    state               VARCHAR(20)  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    active_worker_id    BIGINT AS (IF(state = 'ACTIVE', worker_id, NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT ck_worker_shifts_state CHECK (state IN ('ACTIVE', 'CLOSED')),
    UNIQUE KEY uq_worker_shifts_active (organization_id, active_worker_id),
    KEY ix_worker_shifts_worker (organization_id, worker_id, shift_date)
) ENGINE = InnoDB;

CREATE TABLE position_assignments (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT      NOT NULL,
    worker_shift_id  BIGINT      NOT NULL,
    job_position_id  BIGINT      NOT NULL,
    plant_zone_id    BIGINT      NOT NULL,
    from_at          DATETIME(6) NOT NULL,
    to_at            DATETIME(6) NULL,
    source_action_id BIGINT      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_position_assignments_shift
        FOREIGN KEY (worker_shift_id) REFERENCES worker_shifts (id),
    CONSTRAINT ck_position_assignments_interval CHECK (to_at IS NULL OR from_at < to_at),
    UNIQUE KEY uq_position_assignments_from (worker_shift_id, from_at),
    KEY ix_position_assignments_zone (organization_id, plant_zone_id)
) ENGINE = InnoDB;

CREATE TABLE mitigation_actions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT       NOT NULL,
    type                VARCHAR(20)  NOT NULL,
    origin              VARCHAR(20)  NOT NULL,
    state               VARCHAR(20)  NOT NULL,
    priority            VARCHAR(10)  NOT NULL,
    source_event_id     VARCHAR(64)  NULL,
    trigger_kind        VARCHAR(30)  NULL,
    trigger_detected_at DATETIME(6)  NULL,
    justification       TEXT         NULL,
    decided_by          BIGINT       NULL,
    decided_at          DATETIME(6)  NULL,
    decision_reason     TEXT         NULL,
    outcome             VARCHAR(300) NULL,
    superseded_by_id    BIGINT       NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_mitigation_actions_type
        CHECK (type IN ('ROTATION', 'RELOCATION', 'RESCHEDULING')),
    CONSTRAINT ck_mitigation_actions_origin
        CHECK (origin IN ('AI_SUGGESTION', 'LIMIT_EXCEEDED', 'MANUAL')),
    CONSTRAINT ck_mitigation_actions_state
        CHECK (state IN ('PROPOSED', 'APPROVED', 'APPLIED', 'FAILED', 'REJECTED', 'NOT_VIABLE')),
    CONSTRAINT ck_mitigation_actions_priority CHECK (priority IN ('NORMAL', 'HIGH')),
    -- Evita que una misma excedencia o sugerencia abra dos acciones si el
    -- mensaje llega dos veces (§5.6.6.2). Los NULL no cuentan, así que las
    -- acciones MANUAL no compiten por esta clave.
    UNIQUE KEY uq_mitigation_actions_source_event (organization_id, source_event_id),
    KEY ix_mitigation_actions_queue (organization_id, state, created_at)
) ENGINE = InnoDB;

CREATE TABLE affected_workers (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id      BIGINT        NOT NULL,
    mitigation_action_id BIGINT        NOT NULL,
    worker_id            BIGINT        NOT NULL,
    from_position_id     BIGINT        NULL,
    to_position_id       BIGINT        NULL,
    to_zone_id           BIGINT        NULL,
    -- `limit` es palabra reservada en MySQL: la columna del informe se llama
    -- aquí dose_limit.
    dose_value           DECIMAL(12,4) NULL,
    dose_unit            VARCHAR(10)   NULL,
    dose_method_id       VARCHAR(50)   NULL,
    dose_limit           DECIMAL(12,4) NULL,
    dose_percentage      DECIMAL(6,2)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_affected_workers_action
        FOREIGN KEY (mitigation_action_id) REFERENCES mitigation_actions (id),
    KEY ix_affected_workers_worker (organization_id, worker_id)
) ENGINE = InnoDB;

-- Bitácora append-only de transiciones. La aplicación nunca hace UPDATE ni
-- DELETE sobre esta tabla; el informe lo exige para sostener la auditoría.
CREATE TABLE mitigation_action_history (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id      BIGINT      NOT NULL,
    mitigation_action_id BIGINT      NOT NULL,
    from_state           VARCHAR(20) NULL,
    to_state             VARCHAR(20) NOT NULL,
    actor_id             BIGINT      NULL,
    occurred_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_mitigation_history_action
        FOREIGN KEY (mitigation_action_id) REFERENCES mitigation_actions (id),
    KEY ix_mitigation_history_action (mitigation_action_id, occurred_at)
) ENGINE = InnoDB;

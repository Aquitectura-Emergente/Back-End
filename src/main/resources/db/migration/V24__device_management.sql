-- Device Management: tipo, ciclo de vida, conectividad, configuración versionada,
-- auditoría, telemetría cruda, enchufe, nodos Edge, códigos de organización y outbox.
--
--  - `devices.status` sigue siendo el estado operativo que usan las órdenes de
--    trabajo y los frontends; `lifecycle_status` es el ciclo físico.
--  - La configuración vigente vive en `devices`; `device_configs` guarda el
--    historial (append-only), igual que `device_audit_entries`.
--  - La idempotencia de la ingesta es la restricción única de `telemetry_readings`
--    (deviceId + sequence + capturedAt), no SQL propio de MySQL.
--  - `AUTO_INCREMENT` y `DATETIME(6)` por consistencia con V1→V23 (ver V22).

ALTER TABLE devices ADD COLUMN device_type VARCHAR(30);
ALTER TABLE devices ADD COLUMN lifecycle_status VARCHAR(20);
ALTER TABLE devices ADD COLUMN connectivity VARCHAR(10);
ALTER TABLE devices ADD COLUMN connectivity_changed_at DATETIME(6);
ALTER TABLE devices ADD COLUMN connectivity_edge_node_id VARCHAR(100);
ALTER TABLE devices ADD COLUMN last_seen_at DATETIME(6);
ALTER TABLE devices ADD COLUMN last_reported_type VARCHAR(30);
ALTER TABLE devices ADD COLUMN firmware_version VARCHAR(50);
ALTER TABLE devices ADD COLUMN battery_level INT;
ALTER TABLE devices ADD COLUMN boot_id VARCHAR(64);
ALTER TABLE devices ADD COLUMN last_sequence BIGINT;
ALTER TABLE devices ADD COLUMN plug_power VARCHAR(10);
ALTER TABLE devices ADD COLUMN plug_command_id VARCHAR(64);
ALTER TABLE devices ADD COLUMN plug_source VARCHAR(10);
ALTER TABLE devices ADD COLUMN plug_changed_at DATETIME(6);
ALTER TABLE devices ADD COLUMN config_version INT;
ALTER TABLE devices ADD COLUMN alert_threshold_ut DECIMAL(10,3);
ALTER TABLE devices ADD COLUMN threshold_defined_by VARCHAR(20);
ALTER TABLE devices ADD COLUMN sampling_interval_ms BIGINT;
ALTER TABLE devices ADD COLUMN inactivity_timeout_s BIGINT;
ALTER TABLE devices ADD COLUMN config_updated_at DATETIME(6);

UPDATE devices SET device_type = 'FIXED_SENSOR';
UPDATE devices SET lifecycle_status = 'IN_STOCK' WHERE status = 'unregistered';
UPDATE devices SET lifecycle_status = 'RETIRED' WHERE status = 'inactive';
UPDATE devices SET lifecycle_status = 'INSTALLED' WHERE lifecycle_status IS NULL;
UPDATE devices SET connectivity = 'OFFLINE';
UPDATE devices SET config_version = 1, alert_threshold_ut = 300.000, threshold_defined_by = 'SYSTEM_DEFAULT',
                   sampling_interval_ms = 1000, inactivity_timeout_s = 300, config_updated_at = created_at;

-- Falla de forma explícita si hay seriales repetidos: hay que resolverlos antes.
ALTER TABLE devices ADD CONSTRAINT uq_devices_serial UNIQUE (serial_number);

CREATE TABLE device_configs (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    device_id            BIGINT        NOT NULL,
    config_version       INT           NOT NULL,
    alert_threshold_ut   DECIMAL(10,3) NOT NULL,
    threshold_defined_by VARCHAR(20)   NOT NULL,
    sampling_interval_ms BIGINT        NOT NULL,
    inactivity_timeout_s BIGINT        NOT NULL,
    changed_by           VARCHAR(150)  NOT NULL,
    changed_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_device_configs_version UNIQUE (device_id, config_version)
);

CREATE TABLE device_audit_entries (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    device_id     BIGINT       NOT NULL,
    device_serial VARCHAR(100),
    entry_type    VARCHAR(40)  NOT NULL,
    field_name    VARCHAR(60),
    old_value     VARCHAR(255),
    new_value     VARCHAR(255),
    actor         VARCHAR(150) NOT NULL,
    details       VARCHAR(500),
    occurred_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_device_audit_device ON device_audit_entries (device_id, occurred_at);

CREATE TABLE telemetry_readings (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    device_id          BIGINT        NOT NULL,
    device_serial      VARCHAR(100)  NOT NULL,
    organization_id    BIGINT,
    edge_node_id       VARCHAR(100)  NOT NULL,
    message_id         VARCHAR(64)   NOT NULL,
    sequence_number    BIGINT        NOT NULL,
    captured_at        DATETIME(6)   NOT NULL,
    captured_at_source VARCHAR(20)   NOT NULL,
    received_at        DATETIME(6)   NOT NULL,
    flux_ut            DECIMAL(12,3) NOT NULL,
    origin             VARCHAR(10)   NOT NULL,
    quality            VARCHAR(10)   NOT NULL,
    boot_id            VARCHAR(64)   NOT NULL,
    reported_type      VARCHAR(30)   NOT NULL,
    routing            VARCHAR(30)   NOT NULL,
    routing_ref        VARCHAR(64),
    routing_detail     VARCHAR(255),
    ingested_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_telemetry_identity UNIQUE (device_serial, sequence_number, captured_at)
);
CREATE INDEX ix_telemetry_routing ON telemetry_readings (device_serial, routing, captured_at);

CREATE TABLE plug_commands (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    command_id           VARCHAR(36)  NOT NULL,
    plug_device_id       BIGINT,
    plug_serial          VARCHAR(100) NOT NULL,
    action               VARCHAR(5)   NOT NULL,
    requested_by         VARCHAR(10)  NOT NULL,
    requested_by_user_id BIGINT,
    requested_at         DATETIME(6)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    resolved_at          DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_plug_commands_command UNIQUE (command_id)
);
CREATE INDEX ix_plug_commands_plug ON plug_commands (plug_serial, requested_at);

CREATE TABLE edge_nodes (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    edge_node_id            VARCHAR(100) NOT NULL,
    organization_id         BIGINT       NOT NULL,
    segment                 VARCHAR(10)  NOT NULL,
    connectivity            VARCHAR(10)  NOT NULL,
    connectivity_changed_at DATETIME(6),
    last_seen_at            DATETIME(6),
    created_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_edge_nodes_edge UNIQUE (edge_node_id),
    CONSTRAINT fk_edge_nodes_organization FOREIGN KEY (organization_id) REFERENCES users (id)
);

CREATE TABLE dm_organization_codes (
    code            VARCHAR(64) NOT NULL,
    organization_id BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT uq_dm_organization_codes_org UNIQUE (organization_id),
    CONSTRAINT fk_dm_organization_codes_org FOREIGN KEY (organization_id) REFERENCES users (id)
);

CREATE TABLE device_management_events (
    id              VARCHAR(36) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    organization_id BIGINT,
    payload         TEXT        NOT NULL,
    occurred_at     DATETIME(6) NOT NULL,
    published_at    DATETIME(6),
    PRIMARY KEY (id)
);
CREATE INDEX ix_device_management_events_pending ON device_management_events (published_at, occurred_at);

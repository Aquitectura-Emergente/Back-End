-- V13-V15 are reserved by feature/occupational-health-mitigation.
-- References to IAM and IoT are scalar integration IDs, not cross-context foreign keys.
CREATE TABLE personal_dosimetry_memberships (
    user_id BIGINT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    granted_by BIGINT NOT NULL,
    granted_at DATETIME(6) NOT NULL,
    INDEX ix_pd_membership_org (organization_id)
);

CREATE TABLE personal_dosimetry_limits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    job_position_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    limit_index DECIMAL(38,12) NOT NULL,
    warning_ratio DECIMAL(7,6) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    criterion_reference VARCHAR(250) NOT NULL,
    changed_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_pd_limit_version UNIQUE (organization_id, job_position_id, version_number),
    INDEX ix_pd_limit_effective (organization_id, job_position_id, effective_from)
);

CREATE TABLE personal_exposure_sessions (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    job_position_id BIGINT NOT NULL,
    active_worker_id BIGINT,
    active_device_id BIGINT,
    limit_version_id BIGINT NOT NULL,
    limit_index DECIMAL(38,12) NOT NULL,
    warning_ratio DECIMAL(7,6) NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6),
    reconciliation_deadline DATETIME(6),
    finalized_at DATETIME(6),
    latest_capture_to DATETIME(6) NOT NULL,
    accumulated_index DECIMAL(38,12) NOT NULL,
    covered_micros BIGINT NOT NULL,
    warning_raised BOOLEAN NOT NULL,
    limit_exceeded BOOLEAN NOT NULL,
    CONSTRAINT uk_pd_active_worker UNIQUE (active_worker_id),
    CONSTRAINT uk_pd_active_device UNIQUE (active_device_id),
    CONSTRAINT fk_pd_session_limit FOREIGN KEY (limit_version_id) REFERENCES personal_dosimetry_limits(id),
    INDEX ix_pd_session_history (organization_id, worker_id, started_at)
);

CREATE TABLE personal_exposure_readings (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    organization_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    boot_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    sequence_number BIGINT NOT NULL,
    field_microtesla DECIMAL(20,6) NOT NULL,
    captured_from DATETIME(6) NOT NULL,
    captured_to DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT uk_pd_reading_identity UNIQUE (organization_id, device_id, boot_id, sequence_number),
    CONSTRAINT fk_pd_reading_session FOREIGN KEY (session_id) REFERENCES personal_exposure_sessions(id),
    INDEX ix_pd_reading_interval (session_id, captured_from, captured_to)
);

CREATE TABLE personal_dosimetry_outbox (
    id VARCHAR(36) PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    INDEX ix_pd_pending_events (published_at, occurred_at)
);

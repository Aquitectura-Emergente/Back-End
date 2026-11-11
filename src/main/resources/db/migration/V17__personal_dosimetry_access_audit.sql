CREATE TABLE personal_dosimetry_access_audit (
    id VARCHAR(36) PRIMARY KEY,
    principal_name VARCHAR(150),
    http_method VARCHAR(10) NOT NULL,
    resource_path VARCHAR(512) NOT NULL,
    response_status INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    INDEX ix_pd_audit_time (occurred_at, id)
);

-- Índices sobre personal_exposure_sessions:
-- 1) ExposureQueryFacade.closedSessions (histórico de jornadas CLOSED por organización y ventana de cierre).
-- 2) DosimetryReconciliationJob (jornadas CLOSING cuyo reconciliation_deadline ya venció).
CREATE INDEX ix_pd_session_org_state_ended
    ON personal_exposure_sessions (organization_id, state, ended_at);

CREATE INDEX ix_pd_session_reconciliation
    ON personal_exposure_sessions (state, reconciliation_deadline);

package com.emsafe.occupationalhealth.application.events;

import java.util.List;

/**
 * Mitigation → Alerts (informe §5.6.3).
 *
 * Este contexto pide el aviso; no lo despacha. Y viaja solo con
 * identificadores: ni nombres ni valores de dosis, porque la exposición de un
 * operario es dato sensible (Ley 29733, informe §5.6.4).
 */
public record MitigationNoticeRequested(
        EventEnvelope envelope,
        Long actionId,
        String recipientRole,
        List<Long> workerIds,
        String noticeType,
        String severity) {

    public static final String NOTICE_MITIGATION_APPLIED = "MITIGATION_APPLIED";
    public static final String NOTICE_RESCHEDULING_PROPOSED = "RESCHEDULING_PROPOSED";
}

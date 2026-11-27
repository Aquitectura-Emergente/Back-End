package com.emsafe.personaldosimetry.application;

/** Se publica al guardar un evento en el outbox, para entregarlo sin esperar al relay. */
public record DosimetryEventRecorded(String eventType) {
}

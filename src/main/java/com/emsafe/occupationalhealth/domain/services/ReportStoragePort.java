package com.emsafe.occupationalhealth.domain.services;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;

/**
 * Guarda y recupera el archivo exportado (informe §5.6.4, ReportStoragePort).
 *
 * El informe lo implementa sobre Azure Blob Storage con enlaces temporales de
 * 15 minutos. Aquí no hay Azure: el adaptador guarda en disco y el archivo se
 * sirve por la API, autenticado y auditado. Es más restrictivo que un enlace
 * temporal —no existe una URL que pueda compartirse por error— y el puerto es
 * el mismo, así que migrar a Blob solo cambia el adaptador.
 */
public interface ReportStoragePort {

    /**
     * Guarda el contenido y devuelve la referencia con la que recuperarlo.
     * La referencia es opaca: el dominio no interpreta su forma.
     */
    String store(ComplianceReport report, byte[] content, String extension);

    /** Recupera el contenido ya guardado. */
    byte[] retrieve(String fileReference);

    /**
     * Borra el archivo al vencer su plazo de conservación. En el informe esto lo
     * hace la política de ciclo de vida de Blob Storage; aquí lo pide
     * RetentionJob explícitamente.
     *
     * No falla si el archivo ya no está: el objetivo es que deje de existir.
     */
    void delete(String fileReference);
}

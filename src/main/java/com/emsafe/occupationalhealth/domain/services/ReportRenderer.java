package com.emsafe.occupationalhealth.domain.services;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;

/**
 * Convierte un reporte en el archivo exportable (informe §5.6.4, RF31).
 *
 * Es un puerto para que el formato no entre en el dominio: el reporte sabe qué
 * contiene y qué lo sustenta, no cómo se dibuja. El digest se calcula sobre el
 * contenido canónico y no sobre el archivo, así que cambiar de formato no
 * invalida la evidencia ya emitida.
 */
public interface ReportRenderer {

    /** El archivo listo para entregar. */
    byte[] render(ComplianceReport report);

    /** Extensión del formato que produce, para nombrar el archivo. */
    String fileExtension();

    /** Content-Type con el que servirlo. */
    String contentType();
}

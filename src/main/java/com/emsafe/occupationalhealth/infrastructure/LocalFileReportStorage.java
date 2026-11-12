package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;
import com.emsafe.occupationalhealth.domain.services.ReportStoragePort;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guarda los PDF exportados en disco (sustituye a AzureBlobReportStorage del
 * informe §5.6.4).
 *
 * Los archivos se organizan por organización, lo que mantiene separadas las
 * plantas también en el sistema de ficheros. No se generan URLs: el archivo se
 * sirve siempre por la API, autenticado y auditado, así que no existe un enlace
 * que pueda acabar compartido por error.
 *
 * La referencia devuelta es relativa a la raíz configurada y se valida al
 * recuperarla: una referencia que intente salirse de esa raíz se rechaza.
 */
@Slf4j
@Component
public class LocalFileReportStorage implements ReportStoragePort {

    private final Path root;

    public LocalFileReportStorage(
            @Value("${emsafe.occupational-health.report-storage-path:./data/compliance-reports}")
            String rootPath) {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
    }

    @Override
    public String store(ComplianceReport report, byte[] content, String extension) {
        String relative = "org-" + report.getOrganizationId()
                + "/report-" + report.getId() + "-v" + report.getVersion() + "." + extension;
        Path target = resolveInsideRoot(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            log.info("Stored compliance report {} at {}", report.getId(), target);
            return relative;
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Could not store compliance report " + report.getId(), ex);
        }
    }

    @Override
    public byte[] retrieve(String fileReference) {
        Path source = resolveInsideRoot(fileReference);
        if (!Files.exists(source)) {
            throw new ResourceNotFoundException("Exported report file", fileReference);
        }
        try {
            return Files.readAllBytes(source);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read report file " + fileReference, ex);
        }
    }

    @Override
    public void delete(String fileReference) {
        if (fileReference == null) return;
        try {
            boolean removed = Files.deleteIfExists(resolveInsideRoot(fileReference));
            if (removed) {
                log.info("Deleted expired report file {}", fileReference);
            }
        } catch (IOException ex) {
            // No se propaga: la purga de un lote no debe pararse porque un
            // archivo esté bloqueado. Queda en el log para revisarlo.
            log.error("Could not delete report file {}", fileReference, ex);
        }
    }

    /**
     * Resuelve la referencia dentro de la raíz configurada.
     *
     * La comprobación de que el resultado sigue bajo la raíz no es decorativa:
     * la referencia viene de la base de datos, y sin ella un valor manipulado
     * —con `..`— podría leer o escribir cualquier archivo del servidor.
     */
    private Path resolveInsideRoot(String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Report reference escapes the storage root: " + relative);
        }
        return resolved;
    }
}

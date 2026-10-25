package com.emsafe.iottelemetry.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Representación de una lectura para consumir desde web/móvil.
 * Mantiene la clave `field_uT` para ser consistente con el payload del edge.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadingDto {

    private Long id;

    /** Serial físico del sensor (lo que manda el edge). */
    private String serialNumber;

    /** id numérico autogenerado del Device en BD (para enlazar en el front). */
    private Long deviceDbId;

    private String deviceName;

    @JsonProperty("field_uT")
    private Double fieldUT;

    private String level;

    private String message;

    /** Estado del relé reportado por el dispositivo en esta lectura (ON | OFF). */
    private String plug;

    private String location;

    private Double latitude;

    private Double longitude;

    private Long clientId;

    private String clientName;

    private LocalDateTime recordedAt;
}

package com.emsafe.iottelemetry.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Payload que envía el edge app en cada medición:
 * <pre>
 * { "serialNumber": "...", "field_uT": 123.4, "level": "CAUTION",
 *   "message": "Precaución: exposición moderada", "plug": "ON" }
 * </pre>
 */
@Getter
@Setter
public class ReadingIngestRequest {

    /** Serial físico del sensor (= Device.serialNumber). Llave de cruce con el device. */
    private String serialNumber;

    /** Campo magnético en microtesla. Se almacena en RadiationReading.value. */
    @JsonProperty("field_uT")
    private Double fieldUT;

    /** Nivel calculado por el edge (SAFE | CAUTION | DANGER) — smart edge, nadie lo recalcula. */
    private String level;

    /** Mensaje descriptivo de la lectura. */
    private String message;

    /** Estado del relé reportado por el dispositivo (ON | OFF). */
    private String plug;
}

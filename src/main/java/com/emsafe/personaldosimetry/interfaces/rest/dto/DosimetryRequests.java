package com.emsafe.personaldosimetry.interfaces.rest.dto;

import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class DosimetryRequests {
    private DosimetryRequests() {}

    public record GrantMembership(@NotNull @Positive Long userId,
                                  @NotNull @Positive Long organizationId,
                                  @NotNull MembershipRole role) {}
    public record ConfigureLimit(@NotNull @Positive Long jobPositionId,
                                 @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal limitIndex,
                                 @NotNull @DecimalMin(value = "0", inclusive = false)
                                 @DecimalMax(value = "1", inclusive = false) BigDecimal warningRatio,
                                 @NotNull Instant effectiveFrom,
                                 @NotBlank @Size(max = 250) String reference) {}
    public record OpenSession(@NotNull @Positive Long workerId, @NotNull @Positive Long deviceId,
                              @NotNull @Positive Long jobPositionId, Instant startedAt) {}
    public record IngestReading(@NotNull @Positive Long deviceId,
                                @NotBlank @Size(max = 64) String bootId,
                                @NotNull @PositiveOrZero Long sequence,
                                @NotNull @DecimalMin("0") BigDecimal fieldMicrotesla,
                                @NotBlank @Pattern(regexp = "uT") String unit,
                                @NotNull Instant capturedFrom, @NotNull Instant capturedTo,
                                Boolean saturated) {
        /** {@code saturated}: el sensor llegÃ³ a su tope y el valor es un mÃ­nimo. Opcional. */
        public IngestReading(Long deviceId, String bootId, Long sequence, BigDecimal fieldMicrotesla, String unit,
                             Instant capturedFrom, Instant capturedTo) {
            this(deviceId, bootId, sequence, fieldMicrotesla, unit, capturedFrom, capturedTo, null);
        }
    }
    public record CloseSession(Instant endedAt) {}
    public record FinalizeSession(boolean deliveryConfirmed) {}
}

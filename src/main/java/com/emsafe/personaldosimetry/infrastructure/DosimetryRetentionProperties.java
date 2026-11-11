package com.emsafe.personaldosimetry.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "emsafe.personal-dosimetry.retention")
public class DosimetryRetentionProperties {
    // Zero disables deletion until the project chooses a retention period.
    @Min(0) @Max(365000)
    private int auditDays = 0;
    @Min(0) @Max(365000)
    private int publishedEventDays = 0;
    @Min(1) @Max(1000)
    private int batchSize = 500;
}

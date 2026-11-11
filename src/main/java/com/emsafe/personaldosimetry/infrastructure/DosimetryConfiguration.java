package com.emsafe.personaldosimetry.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DosimetryRetentionProperties.class)
public class DosimetryConfiguration {
    @Bean
    public Clock dosimetryClock() {
        return Clock.systemUTC();
    }
}

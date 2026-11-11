package com.emsafe.personaldosimetry.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class DosimetryConfiguration {
    @Bean
    public Clock dosimetryClock() {
        return Clock.systemUTC();
    }
}

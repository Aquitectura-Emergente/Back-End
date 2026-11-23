package com.emsafe.bdd;

import com.emsafe.devicemanagement.infrastructure.mqtt.RecordingMqttGateway;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Contexto de Spring de los escenarios: H2 en memoria, datos de demo sembrados
 * y el broker reemplazado por un doble que guarda lo publicado. Cada escenario
 * arranca con la base recién sembrada.
 */
@CucumberContextConfiguration
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bdd;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "emsafe.device-management.seed-demo-data=true",
        "emsafe.device-management.seed-demo-session=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CucumberSpringConfiguration {

    @TestConfiguration
    static class Broker {
        @Bean
        @Primary
        RecordingMqttGateway recordingMqttGateway() {
            return new RecordingMqttGateway();
        }
    }
}

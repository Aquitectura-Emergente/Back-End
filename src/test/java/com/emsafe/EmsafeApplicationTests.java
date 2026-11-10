package com.emsafe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // NON_KEYWORDS=VALUE: H2 2.x reserva VALUE, y la columna
        // radiation_readings.value se llama así. Sin esto el create-drop de
        // Hibernate falla con un error de sintaxis y el contexto no arranca.
        // Este test estaba rojo antes de tocar nada (verificado en origin/develop),
        // oculto porque el CI y el Dockerfile compilan con -DskipTests.
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false"
})
class EmsafeApplicationTests {

    @Test
    void contextLoads() {
    }
}

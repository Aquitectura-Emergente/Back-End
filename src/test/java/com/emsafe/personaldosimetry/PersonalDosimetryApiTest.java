package com.emsafe.personaldosimetry;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:personal-dosimetry;DB_CLOSE_DELAY=-1;MODE=MySQL;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class PersonalDosimetryApiTest extends PersonalDosimetryApiContract {}

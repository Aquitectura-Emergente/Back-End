package com.emsafe.personaldosimetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=false"
})
class PersonalDosimetryMysqlIT extends PersonalDosimetryApiContract {
    @Autowired JdbcTemplate database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = MysqlTestDatabase.url();
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", MysqlTestDatabase::username);
        properties.add("spring.datasource.password", MysqlTestDatabase::password);
    }

    @Test
    void migrationsRunOnRealMySqlAndAuditTableUsesMicroseconds() {
        assertThat(database.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IN ('16','17')", Integer.class))
                .isEqualTo(2);
        assertThat(database.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'personal_dosimetry_access_audit' AND column_name = 'occurred_at'", Integer.class))
                .isEqualTo(6);
    }
}

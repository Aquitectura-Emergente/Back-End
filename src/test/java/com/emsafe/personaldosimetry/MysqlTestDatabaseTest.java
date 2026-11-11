package com.emsafe.personaldosimetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MysqlTestDatabaseTest {
    @Test
    void acceptsDedicatedDatabaseWithoutChangingConnectionOptions() {
        String url = "jdbc:mysql://127.0.0.1:13306/emsafe_pd_test_abc123?serverTimezone=UTC";
        assertThat(MysqlTestDatabase.validateUrl(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"jdbc:h2:mem:test", "jdbc:mysql://localhost:3306/emsafe_db",
            "jdbc:mysql://localhost:3306/emsafe_pd_test/other", "jdbc:mysql://user:secret@localhost:3306/emsafe_pd_test"})
    void rejectsMissingConfigurationOrAnApplicationDatabase(String url) {
        assertThatThrownBy(() -> MysqlTestDatabase.validateUrl(url)).isInstanceOf(IllegalStateException.class);
    }
}

package com.emsafe.aiforecasting.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Regla 2 del plan: el dominio no importa Spring, JPA ni Hibernate. */
class DomainPurityTest {

    private static final Path DOMAIN = Path.of("src/main/java/com/emsafe/aiforecasting/domain");
    private static final List<String> FORBIDDEN =
            List.of("org.springframework", "jakarta.persistence", "javax.persistence", "org.hibernate", "lombok");

    @Test
    void domainHasNoFrameworkImports() throws IOException {
        assertThat(DOMAIN).isDirectory();
        try (Stream<Path> files = Files.walk(DOMAIN)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .filter(l -> l.startsWith("import"))
                                    .filter(l -> FORBIDDEN.stream().anyMatch(l::contains))
                                    .map(l -> p.getFileName() + ": " + l);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).isEmpty();
        }
    }
}

package com.emsafe.iam.infrastructure.security;

import com.emsafe.iam.infrastructure.security.JwtSecretValidator.InvalidJwtSecretException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidatorTest {

    /** Secreto como el que genera {@code openssl rand -base64 48}: 64 caracteres, 48 bytes. */
    private static String strongSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Nested
    class Validate {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void rechazaSecretoAusente(String secret) {
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("no está definido");
        }

        @Test
        void rechazaElSecretoDeDesarrollo() {
            assertThatThrownBy(() -> JwtSecretValidator.validate(JwtSecretValidator.DEV_SECRET))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("desarrollo");
        }

        @Test
        void rechazaElSecretoDePruebas() {
            assertThatThrownBy(() -> JwtSecretValidator.validate(JwtSecretValidator.TEST_SECRET))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("desarrollo/pruebas");
        }

        @Test
        void rechazaSecretoCorto() {
            String secret = "CAMBIAME_jwt"; // el marcador de deploy/.env.example
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("12 caracteres")
                    .hasMessageContaining("32");
        }

        @Test
        void rechazaSecretoDe31Caracteres() {
            assertThatThrownBy(() -> JwtSecretValidator.validate("A".repeat(31)))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("31 caracteres");
        }

        @Test
        void rechazaSecretoQueNoEsBase64() {
            String secret = "este-secreto-no-es-base64-valido!!!!"; // 36 caracteres
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("Base64");
        }

        @Test
        void rechazaSecretoQueDecodificadoMideMenosDe32Bytes() {
            String secret = "A".repeat(32); // 32 caracteres Base64 → 24 bytes
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .hasMessageContaining("24 bytes");
        }

        @Test
        void aceptaUnSecretoFuerte() {
            assertThatCode(() -> JwtSecretValidator.validate(strongSecret())).doesNotThrowAnyException();
        }

        @Test
        void aceptaElMinimoExacto() {
            String secret = Base64.getEncoder().encodeToString(new byte[32]); // 44 caracteres, 32 bytes
            assertThatCode(() -> JwtSecretValidator.validate(secret)).doesNotThrowAnyException();
        }

        @Test
        void elMensajeNuncaIncluyeElSecreto() {
            String secret = "B".repeat(32);
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret))
                    .isInstanceOf(InvalidJwtSecretException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
        }
    }

    @Nested
    class ArranquePorPerfil {

        private ApplicationContextRunner runner(String profiles, String secret) {
            ApplicationContextRunner r = new ApplicationContextRunner()
                    .withPropertyValues("spring.profiles.active=" + profiles)
                    .withUserConfiguration(JwtSecretValidator.class);
            return secret == null ? r : r.withPropertyValues("jwt.secret=" + secret);
        }

        @ParameterizedTest
        @ValueSource(strings = {"prod", "prod,ingestion", "demo"})
        void sinSecretoNoArranca(String profiles) {
            runner(profiles, null).run(context -> assertFailsWithInvalidSecret(context.getStartupFailure()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"prod", "demo"})
        void conElSecretoDeDesarrolloNoArranca(String profiles) {
            runner(profiles, JwtSecretValidator.DEV_SECRET)
                    .run(context -> assertFailsWithInvalidSecret(context.getStartupFailure()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"prod", "demo"})
        void conUnSecretoCortoNoArranca(String profiles) {
            runner(profiles, "CAMBIAME_jwt").run(context -> assertFailsWithInvalidSecret(context.getStartupFailure()));
        }

        /** El fallo puede llegar tal cual o envuelto por Spring; lo que importa es la causa. */
        private void assertFailsWithInvalidSecret(Throwable failure) {
            assertThat(failure).isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                    .isInstanceOf(InvalidJwtSecretException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"prod", "prod,ingestion", "demo"})
        void conUnSecretoValidoArranca(String profiles) {
            runner(profiles, strongSecret()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(JwtSecretValidator.class);
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {"dev", "test"})
        void enDevYTestNoValidaNiFalla(String profiles) {
            runner(profiles, null).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JwtSecretValidator.class);
            });
        }
    }

    @Nested
    class MensajeDeArranque {

        @Test
        void explicaLaCausaYQueHacerSinMostrarElSecreto() {
            String secret = "B".repeat(32);
            InvalidJwtSecretException cause = null;
            try {
                JwtSecretValidator.validate(secret);
            } catch (InvalidJwtSecretException e) {
                cause = e;
            }
            assertThat(cause).isNotNull();

            FailureAnalysis analysis = new JwtSecretFailureAnalyzer()
                    .analyze(new IllegalStateException("envoltorio", cause));

            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).contains("prod/demo").contains("24 bytes")
                    .doesNotContain(secret);
            assertThat(analysis.getAction()).contains("JWT_SECRET").contains("openssl rand -base64 48")
                    .doesNotContain(secret);
        }
    }
}

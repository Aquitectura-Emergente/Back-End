package com.emsafe.iam.infrastructure.security;

import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Impide arrancar en los perfiles {@code prod} y {@code demo} con un secreto JWT inseguro.
 *
 * <p>Es un {@link BeanFactoryPostProcessor} a propósito: corre antes de que se cree cualquier
 * bean (DataSource, Flyway, JwtUtil...), así que el fallo es inmediato y su mensaje no queda
 * tapado por otro error. En {@code dev} y {@code test} el bean no existe.</p>
 *
 * <p>Rechaza el secreto si:</p>
 * <ul>
 *   <li>falta o está vacío ({@code JWT_SECRET} no definido);</li>
 *   <li>es el valor de desarrollo o el de las pruebas, que están publicados en el repositorio;</li>
 *   <li>tiene menos de {@value #MIN_LENGTH} caracteres;</li>
 *   <li>no es Base64 válido o decodificado mide menos de {@value #MIN_KEY_BYTES} bytes:
 *       {@link JwtUtil} lo decodifica como Base64 y HMAC-SHA256 exige 256 bits. Sin esta
 *       comprobación la aplicación arrancaría y fallaría en cada login.</li>
 * </ul>
 *
 * <p>Los mensajes nunca incluyen el secreto.</p>
 */
@Component
@Profile({"prod", "demo"})
public class JwtSecretValidator implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    public static final String PROPERTY = "jwt.secret";
    public static final int MIN_LENGTH = 32;
    public static final int MIN_KEY_BYTES = 32;

    /** Valor de application-dev.properties. */
    static final String DEV_SECRET =
            "ZW1zYWZlLXNlY3JldC1rZXktZm9yLWRldi1vbmx5LW5lZWRzLTMyLWJ5dGVzLW1pbmltdW0h";
    /** Valor de application-test.properties y de las pruebas de integración. */
    static final String TEST_SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=";
    private static final Set<String> PUBLISHED_SECRETS = Set.of(DEV_SECRET, TEST_SECRET);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        validate(environment.getProperty(PROPERTY));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * @throws InvalidJwtSecretException si el secreto no sirve para producción.
     */
    public static void validate(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new InvalidJwtSecretException("JWT_SECRET no está definido o está vacío.");
        }
        if (PUBLISHED_SECRETS.contains(secret.trim())) {
            throw new InvalidJwtSecretException(
                    "JWT_SECRET es el valor de desarrollo/pruebas publicado en el repositorio.");
        }
        if (secret.length() < MIN_LENGTH) {
            throw new InvalidJwtSecretException("JWT_SECRET tiene " + secret.length()
                    + " caracteres; el mínimo es " + MIN_LENGTH + ".");
        }
        byte[] key;
        try {
            key = Decoders.BASE64.decode(secret);
        } catch (RuntimeException e) {
            throw new InvalidJwtSecretException("JWT_SECRET no es Base64 válido.");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new InvalidJwtSecretException("JWT_SECRET decodificado mide " + key.length
                    + " bytes; HMAC-SHA256 exige al menos " + MIN_KEY_BYTES + ".");
        }
    }

    /** Secreto JWT inválido para prod/demo. El mensaje nunca contiene el secreto. */
    public static class InvalidJwtSecretException extends IllegalStateException {
        public InvalidJwtSecretException(String reason) {
            super(reason);
        }
    }
}
